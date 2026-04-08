/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger.drive;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages TeleDrive metadata (folder tree + file→folder assignments) with two layers:
 *
 * <ol>
 *   <li><b>Local</b> — the {@link DriveStorage} SQLite cache, always available offline.</li>
 *   <li><b>Remote</b> — a special text message in the user's Saved Messages that stores
 *       the metadata as a JSON snapshot prefixed with {@link #METADATA_MARKER}.
 *       This allows restoring the folder structure after reinstall or on another device.</li>
 * </ol>
 *
 * <h3>Remote sync strategy</h3>
 * <ul>
 *   <li>On first launch (or after data clear), {@link #syncFromRemote(SyncCallback)} scans
 *       the last {@value #REMOTE_SEARCH_LIMIT} Saved Messages looking for the marker.</li>
 *   <li>On every metadata change, {@link #schedulePush()} debounces and calls
 *       {@link #pushToRemote()} after {@link #PUSH_DEBOUNCE_MS} ms.</li>
 * </ul>
 */
public class MetadataManager implements NotificationCenter.NotificationCenterDelegate {

    // ── Constants ─────────────────────────────────────────────────────────────
    /** First line of the remote metadata message — used as a unique marker. */
    public static final String METADATA_MARKER   = "🗄 TeleDrive Metadata v1";
    /** Maximum number of Saved Messages to scan when searching for the marker. */
    private static final int   REMOTE_SEARCH_LIMIT = 500;
    /** Debounce interval before writing the metadata to Telegram (ms). */
    private static final long  PUSH_DEBOUNCE_MS    = 3_000;
    /** SharedPreferences key storing the remote metadata message ID. */
    private static final String PREF_META_MSG_ID   = "drive_meta_msg_id";
    /** SharedPreferences name for TeleDrive config. */
    private static final String PREFS_NAME         = "teledrive_config";
    /** JSON schema version embedded in the remote payload. */
    private static final int    JSON_VERSION        = 1;

    // ── Singleton per account ─────────────────────────────────────────────────
    private static volatile MetadataManager[] sInstances =
            new MetadataManager[UserConfig.MAX_ACCOUNT_COUNT];

    @NonNull
    public static MetadataManager getInstance(int account) {
        if (sInstances[account] == null) {
            synchronized (MetadataManager.class) {
                if (sInstances[account] == null) {
                    sInstances[account] = new MetadataManager(account);
                }
            }
        }
        return sInstances[account];
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private final int            mAccount;
    private final DriveStorage   mStorage;
    private final DispatchQueue  mQueue;

    /** True while a push to Telegram is in flight (prevents double-push). */
    private volatile boolean mPushPending;

    private MetadataManager(int account) {
        mAccount = account;
        mStorage = DriveStorage.getInstance(account);
        mQueue   = new DispatchQueue("DriveMetaQueue_" + account);
    }

    // ── Public interface ──────────────────────────────────────────────────────

    /** Callback for {@link #syncFromRemote}. */
    public interface SyncCallback {
        /** Called on the main thread when sync finishes (success or failure). */
        void onSyncComplete(boolean success, @Nullable String errorMessage);
    }

    /**
     * Scan Saved Messages for the metadata marker and restore the local
     * {@link DriveStorage} from the JSON payload found there.
     *
     * If no remote metadata is found, the local cache is kept as-is (which may
     * already be populated from a previous session).
     *
     * Safe to call from the UI thread.
     */
    public void syncFromRemote(@Nullable SyncCallback callback) {
        mQueue.postRunnable(() -> {
            boolean success = false;
            String error = null;
            try {
                success = doSyncFromRemote();
            } catch (Exception e) {
                error = e.getMessage();
                FileLog.e("[MetadataManager] syncFromRemote error", e);
            }
            final boolean finalSuccess = success;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) callback.onSyncComplete(finalSuccess, finalError);
            });
        });
    }

    /**
     * Persist a new or updated {@link DriveFolder} to the local DB and schedule
     * a remote push.
     */
    public void saveFolder(@NonNull DriveFolder folder) {
        mQueue.postRunnable(() -> {
            mStorage.saveFolder(folder);
            schedulePush();
        });
    }

    /**
     * Delete a folder (and all its child files) from local DB and schedule remote push.
     *
     * @param folderId UUID of the folder to remove.
     * @param deleteFiles If {@code true}, files in the folder are also removed from local DB
     *                    (the caller should separately delete the Telegram messages).
     */
    public void deleteFolder(@NonNull String folderId, boolean deleteFiles) {
        mQueue.postRunnable(() -> {
            if (deleteFiles) {
                for (DriveFile f : mStorage.getFilesInFolder(folderId)) {
                    mStorage.deleteFile(f.messageId);
                }
            }
            mStorage.deleteFolder(folderId);
            schedulePush();
        });
    }

    /**
     * Recursively delete a folder tree (all subfolders + files) from local DB
     * and schedule a remote metadata push.
     */
    public void deleteFolderTree(@NonNull String folderId) {
        mQueue.postRunnable(() -> {
            mStorage.deleteFolderTree(folderId);
            schedulePush();
        });
    }

    /**
     * Record a newly uploaded file in local DB and schedule remote push.
     *
     * @param file Fully populated {@link DriveFile} including Telegram doc fields.
     */
    public void registerFile(@NonNull DriveFile file) {
        registerFile(file, null);
    }

    /**
     * Register a file and invoke an optional callback on the main thread after the DB write.
     */
    public void registerFile(@NonNull DriveFile file, @Nullable Runnable onDone) {
        mQueue.postRunnable(() -> {
            mStorage.saveFile(file);
            schedulePush();
            if (onDone != null) AndroidUtilities.runOnUIThread(onDone);
        });
    }

    /**
     * Same as {@link #registerFile} but uses INSERT OR IGNORE, so an existing
     * entry (e.g. one already placed in the correct folder) is never overwritten.
     */
    public void registerFileIfAbsent(@NonNull DriveFile file) {
        registerFileIfAbsent(file, null);
    }

    /**
     * Same as {@link #registerFileIfAbsent(DriveFile)} with an optional main-thread callback.
     */
    public void registerFileIfAbsent(@NonNull DriveFile file, @Nullable Runnable onDone) {
        mQueue.postRunnable(() -> {
            boolean added = mStorage.saveFileIfAbsent(file);
            if (added) schedulePush();
            if (onDone != null && added) AndroidUtilities.runOnUIThread(onDone);
        });
    }

    /** Remove a file entry from local DB and schedule remote push. */
    public void unregisterFile(long messageId) {
        mQueue.postRunnable(() -> {
            mStorage.deleteFile(messageId);
            schedulePush();
        });
    }

    /**
     * Update display name and/or folder assignment of a file.
     *
     * @param messageId    Telegram message ID.
     * @param newName      New display name, or {@code null} to keep current.
     * @param newFolderId  New folder UUID, or {@code null} to keep current.
     */
    public void updateFileMeta(long messageId,
                                @Nullable String newName,
                                @Nullable String newFolderId) {
        mQueue.postRunnable(() -> {
            mStorage.updateFileMeta(messageId, newName, newFolderId);
            schedulePush();
        });
    }

    /** Toggle the starred state of a file. */
    public void setFileStarred(long messageId, boolean starred) {
        mQueue.postRunnable(() -> {
            mStorage.setFileStarred(messageId, starred);
            schedulePush();
        });
    }

    // ── Remote sync internals ─────────────────────────────────────────────────

    /**
     * Debounce: cancel any pending push and schedule a new one after the debounce delay.
     * Must be called from {@link #mQueue}.
     */
    private void schedulePush() {
        mQueue.cancelRunnable(mPushRunnable);
        mQueue.postRunnable(mPushRunnable, PUSH_DEBOUNCE_MS);
    }

    private final Runnable mPushRunnable = this::pushToRemote;

    /**
     * Serialise local DB to JSON and push the metadata to Saved Messages.
     *
     * Strategy: always send a NEW message (so it is always the most recent
     * text message in Saved Messages), then delete the previous metadata message.
     * This guarantees the fallback scan in {@link #doSyncFromRemote()} can
     * always find it — even after a reinstall where SharedPreferences are lost.
     */
    @WorkerThread
    private void pushToRemote() {
        if (mPushPending) return;
        mPushPending = true;
        try {
            String json = buildJson();
            String text = METADATA_MARKER + "\n" + json;
            long oldMsgId = getStoredMetaMessageId();

            // Send the new message first — saveMetaMessageId() is called inside.
            sendRemoteMessage(text);

            // Delete the old metadata message after the new one is confirmed.
            if (oldMsgId > 0) {
                deleteRemoteMessage(oldMsgId);
            }
        } catch (Exception e) {
            FileLog.e("[MetadataManager] pushToRemote error", e);
        } finally {
            mPushPending = false;
        }
    }

    /** Search Saved Messages for the marker; restore local DB if found. */
    @WorkerThread
    private boolean doSyncFromRemote() {
        long savedMsgId = getStoredMetaMessageId();
        if (savedMsgId > 0) {
            // We already know the message ID — try to load it directly.
            String json = fetchMessageText(savedMsgId);
            if (json != null) {
                restoreFromJson(json);
                return true;
            }
            // Message was deleted; fall through to scan.
            saveMetaMessageId(0);
        }

        // Scan recent Saved Messages for the marker.
        List<String> recentTexts = fetchRecentSavedMessages(REMOTE_SEARCH_LIMIT);
        for (String text : recentTexts) {
            if (text.startsWith(METADATA_MARKER)) {
                String json = text.substring(METADATA_MARKER.length()).trim();
                if (!TextUtils.isEmpty(json)) {
                    restoreFromJson(json);
                    return true;
                }
            }
        }
        return false; // No remote metadata found — treat local cache as authoritative.
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    @NonNull
    @WorkerThread
    private String buildJson() throws JSONException {
        List<DriveFolder> folders = mStorage.getAllFolders();
        List<DriveFile>   files   = mStorage.getAllFiles();

        JSONObject root = new JSONObject();
        root.put("v", JSON_VERSION);
        root.put("ts", System.currentTimeMillis() / 1000L);

        // Folders array
        JSONArray foldersArr = new JSONArray();
        for (DriveFolder d : folders) {
            JSONObject o = new JSONObject();
            o.put("id",  d.id);
            o.put("n",   d.name);
            o.put("pid", d.parentId);
            o.put("ts",  d.createdAt);
            o.put("c",   d.color);
            o.put("e",   d.emoji);
            foldersArr.put(o);
        }
        root.put("folders", foldersArr);

        // Files array — only TeleDrive-specific overrides (name, folderId, starred).
        // The rest (size, mimeType, docId…) is always recoverable from Telegram message history.
        JSONArray filesArr = new JSONArray();
        for (DriveFile f : files) {
            JSONObject o = new JSONObject();
            o.put("mid",  f.messageId);
            o.put("n",    f.name);
            o.put("fid",  f.folderId);
            o.put("star", f.isStarred ? 1 : 0);
            filesArr.put(o);
        }
        root.put("files", filesArr);

        return root.toString();
    }

    @WorkerThread
    private void restoreFromJson(@NonNull String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);

            // Restore folders
            JSONArray foldersArr = root.optJSONArray("folders");
            if (foldersArr != null) {
                for (int i = 0; i < foldersArr.length(); i++) {
                    JSONObject o = foldersArr.getJSONObject(i);
                    DriveFolder d = new DriveFolder();
                    d.id        = o.getString("id");
                    d.name      = o.optString("n", "Folder");
                    d.parentId  = o.optString("pid", DriveFolder.ROOT_ID);
                    d.createdAt = o.optLong("ts", 0);
                    d.color     = o.optString("c", DriveFolder.COLOR_BLUE);
                    d.emoji     = o.optString("e", "📁");
                    mStorage.saveFolder(d);
                }
            }

            // Restore file metadata overrides
            JSONArray filesArr = root.optJSONArray("files");
            if (filesArr != null) {
                for (int i = 0; i < filesArr.length(); i++) {
                    JSONObject o = filesArr.getJSONObject(i);
                    long   mid     = o.getLong("mid");
                    String name    = o.optString("n", null);
                    String folderId = o.optString("fid", DriveFolder.ROOT_ID);
                    boolean starred = o.optInt("star", 0) == 1;

                    // Only update if the file already exists in local cache
                    // (it may not if this is a fresh install — DriveRepository
                    //  will populate it later by scanning Saved Messages history).
                    DriveFile existing = mStorage.getFile(mid);
                    if (existing != null) {
                        mStorage.updateFileMeta(mid, name, folderId);
                        mStorage.setFileStarred(mid, starred);
                    } else {
                        // Stub entry so the folder assignment survives until the
                        // full message history scan re-populates full details.
                        DriveFile stub = new DriveFile();
                        stub.messageId  = mid;
                        stub.account    = mAccount;
                        stub.name       = name != null ? name : "file_" + mid;
                        stub.folderId   = folderId;
                        stub.isStarred  = starred;
                        mStorage.saveFile(stub);
                    }
                }
            }
            FileLog.d("[MetadataManager] restored " +
                      (foldersArr != null ? foldersArr.length() : 0) + " folders, " +
                      (filesArr   != null ? filesArr.length()   : 0) + " file entries");
        } catch (JSONException e) {
            FileLog.e("[MetadataManager] restoreFromJson error", e);
        }
    }

    // ── Telegram API helpers ──────────────────────────────────────────────────

    /**
     * Send a new text message to Saved Messages and persist the returned message ID.
     * NOTE: This is a blocking call — must run on {@link #mQueue}.
     */
    @WorkerThread
    private void sendRemoteMessage(@NonNull String text) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) return; // Not logged in

        // Build a bare sendMessage RPC request
        TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
        req.peer    = MessagesController.getInstance(mAccount).getInputPeer(selfId);
        req.message = text;
        req.random_id = (long) (Math.random() * Long.MAX_VALUE);
        req.flags  |= 512; // no_webpage

        final Object lock = new Object();
        final long[] result = {0};

        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                TLRPC.Updates updates = (TLRPC.Updates) response;
                if (!updates.updates.isEmpty()) {
                    for (TLRPC.Update update : updates.updates) {
                        if (update instanceof TLRPC.TL_updateMessageID) {
                            // This gives us the real message ID
                        }
                        if (update instanceof TLRPC.TL_updateNewMessage) {
                            result[0] = ((TLRPC.TL_updateNewMessage) update).message.id;
                        }
                    }
                }
            }
            synchronized (lock) { lock.notifyAll(); }
        });

        // Wait for the response (max 10 s)
        synchronized (lock) {
            try { lock.wait(10_000); } catch (InterruptedException ignored) {}
        }
        if (result[0] > 0) {
            saveMetaMessageId(result[0]);
            FileLog.d("[MetadataManager] remote metadata message sent, id=" + result[0]);
        }
    }

    /**
     * Delete a specific Saved Messages message by ID.
     * Non-blocking — fire-and-forget.
     */
    @WorkerThread
    private void deleteRemoteMessage(long messageId) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0 || messageId <= 0) return;

        TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
        req.id.add((int) messageId);
        req.revoke = true;
        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                FileLog.w("[MetadataManager] deleteRemoteMessage error: " + error.text);
            } else {
                FileLog.d("[MetadataManager] old metadata message deleted, id=" + messageId);
            }
        });
    }

    /**
     * Edit an existing remote metadata message with new JSON content.
     * Must run on {@link #mQueue}.
     * @deprecated No longer used — pushToRemote() now always sends a new message.
     */
    @Deprecated
    @WorkerThread
    private void editRemoteMessage(long messageId, @NonNull String newText) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) return;

        TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
        req.peer       = MessagesController.getInstance(mAccount).getInputPeer(selfId);
        req.id         = (int) messageId;
        req.message    = newText;
        req.flags     |= 2048; // edit message text
        req.no_webpage = true;

        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                FileLog.w("[MetadataManager] editRemoteMessage error: " + error.text);
                if ("MESSAGE_ID_INVALID".equals(error.text) || "MESSAGE_NOT_MODIFIED".equals(error.text)) {
                    // Message was deleted; clear stored ID so next push creates a new one
                    if (!"MESSAGE_NOT_MODIFIED".equals(error.text)) {
                        mQueue.postRunnable(() -> saveMetaMessageId(0));
                    }
                }
            } else {
                FileLog.d("[MetadataManager] remote metadata updated, msgId=" + messageId);
            }
        });
    }

    /**
     * Fetch the text of a specific Saved Messages message by ID.
     * Blocking — must run on {@link #mQueue}.
     */
    @Nullable
    @WorkerThread
    private String fetchMessageText(long messageId) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) return null;

        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add((int) messageId);

        final String[] result = {null};
        final Object lock = new Object();

        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                if (!msgs.messages.isEmpty()) {
                    TLRPC.Message msg = msgs.messages.get(0);
                    if (msg != null && msg.message != null
                            && msg.message.startsWith(METADATA_MARKER)) {
                        result[0] = msg.message;
                    }
                }
            }
            synchronized (lock) { lock.notifyAll(); }
        });

        synchronized (lock) {
            try { lock.wait(10_000); } catch (InterruptedException ignored) {}
        }
        return result[0];
    }

    /**
     * Fetch the text content of the most recent {@code limit} Saved Messages
     * that are plain text (no media).
     * Blocking — must run on {@link #mQueue}.
     */
    @NonNull
    @WorkerThread
    private List<String> fetchRecentSavedMessages(int limit) {
        List<String> texts = new ArrayList<>();
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) return texts;

        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer   = MessagesController.getInstance(mAccount).getInputPeer(selfId);
        req.limit  = limit;
        req.offset_id   = 0;
        req.offset_date = 0;
        req.add_offset  = 0;
        req.max_id  = 0;
        req.min_id  = 0;
        req.hash    = 0;

        final Object lock = new Object();
        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                for (TLRPC.Message msg : msgs.messages) {
                    if (msg != null && !TextUtils.isEmpty(msg.message)
                            && msg.media == null) {
                        texts.add(msg.message);
                    }
                }
            }
            synchronized (lock) { lock.notifyAll(); }
        });

        synchronized (lock) {
            try { lock.wait(10_000); } catch (InterruptedException ignored) {}
        }
        return texts;
    }

    // ── SharedPreferences helpers ─────────────────────────────────────────────

    private long getStoredMetaMessageId() {
        return getPrefs().getLong(PREF_META_MSG_ID + "_" + mAccount, 0);
    }

    private void saveMetaMessageId(long id) {
        getPrefs().edit().putLong(PREF_META_MSG_ID + "_" + mAccount, id).apply();
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    // ── NotificationCenterDelegate (reserved for future use) ─────────────────

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        // No subscriptions yet — reserved for future real-time sync hooks.
    }
}
