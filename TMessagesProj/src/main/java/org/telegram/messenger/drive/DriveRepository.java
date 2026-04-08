/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger.drive;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.telegram.messenger.FileLoader;

/**
 * Main entry point for all TeleDrive data operations.
 *
 * <h3>Architecture</h3>
 * <pre>
 * UI Layer
 *   │
 *   ▼
 * DriveRepository  ←─── orchestrates ───→  Telegram SDK (SendMessagesHelper / FileLoader)
 *   │                                            │
 *   ▼                                            │ notifications
 * DriveStorage (SQLite local cache)              │
 *   │                                            ▼
 * MetadataManager ←──── remote JSON ─── Saved Messages (Telegram)
 * </pre>
 *
 * <h3>Threading</h3>
 * <ul>
 *   <li>All callback methods ({@code onSuccess}, {@code onError}, {@code onProgress})
 *       are delivered on the <b>main thread</b>.</li>
 *   <li>Internal DB / IO work runs on a background {@link DispatchQueue}.</li>
 * </ul>
 *
 * <h3>Obtaining an instance</h3>
 * <pre>
 *   DriveRepository repo = DriveRepository.getInstance(currentAccount);
 * </pre>
 */
public class DriveRepository implements NotificationCenter.NotificationCenterDelegate {

    // ── Singleton per Telegram account ────────────────────────────────────────
    private static volatile DriveRepository[] sInstances =
            new DriveRepository[UserConfig.MAX_ACCOUNT_COUNT];

    @NonNull
    public static DriveRepository getInstance(int account) {
        if (sInstances[account] == null) {
            synchronized (DriveRepository.class) {
                if (sInstances[account] == null) {
                    sInstances[account] = new DriveRepository(account);
                }
            }
        }
        return sInstances[account];
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private final int             mAccount;
    private final DriveStorage    mStorage;
    private final MetadataManager mMeta;
    private final DispatchQueue   mQueue;

    /**
     * Tracks in-progress uploads: file path (as used by Telegram upload) → pending info.
     * Guarded by {@code mPendingUploads}.
     */
    private final Map<String, PendingUpload> mPendingUploads = new HashMap<>();

    /**
     * Temp-ID → PendingUpload: set when the temp message fires in didReceiveNewMessages,
     * moved to mRealIdToPending when messageReceivedByServer arrives.
     * Guarded by {@code mTempIdToPending}.
     */
    private final Map<Integer, PendingUpload> mTempIdToPending = new HashMap<>();

    /**
     * Real-ID → PendingUpload: set in handleMessageReceivedByServer after bridging
     * tempId → realId. Consumed in handleNewMessages when the confirmed message fires.
     * NOTE: messageReceivedByServer always passes null for the MessageObject arg, so we
     * cannot extract doc metadata there — we must wait for the confirmed didReceiveNewMessages.
     * Guarded by {@code mRealIdToPending}.
     */
    private final Map<Integer, PendingUpload> mRealIdToPending = new HashMap<>();

    private final java.util.concurrent.CopyOnWriteArrayList<UploadProgressListener> mUploadListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    // fileName → progress (0.0-1.0); LinkedHashMap preserves insertion order
    private final java.util.LinkedHashMap<String, Float> mActiveUploads = new java.util.LinkedHashMap<>();

    private static final class PendingUpload {
        final String     folderId;
        final String     fileName;
        final UploadCallback callback;
        long fileSize;
        PendingUpload(String folderId, String fileName, UploadCallback callback) {
            this.folderId = folderId;
            this.fileName = fileName;
            this.callback = callback;
        }
    }

    private DriveRepository(int account) {
        mAccount = account;
        mStorage = DriveStorage.getInstance(account);
        mMeta    = MetadataManager.getInstance(account);
        mQueue   = new DispatchQueue("DriveRepoQueue_" + account);

        // Subscribe to Telegram notification events
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileUploadProgressChanged);
        // Sync: detect deletions made from the Telegram side
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDeleted);
        // Most reliable upload confirmation: temp-ID → real-ID mapping
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CALLBACKS
    // ═════════════════════════════════════════════════════════════════════════

    public interface UploadCallback {
        void onProgress(float progress);      // 0.0 – 1.0
        void onSuccess(@NonNull DriveFile file);
        void onError(@NonNull String message);
    }

    public interface UploadProgressListener {
        void onUploadStarted(String fileName);
        void onUploadProgress(String fileName, float progress);  // 0.0–1.0
        void onUploadFinished(String fileName, boolean success);
    }

    public interface DownloadCallback {
        void onProgress(float progress);      // 0.0 – 1.0
        void onSuccess(@NonNull File localFile);
        void onError(@NonNull String message);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(@NonNull String message);
    }

    public interface ListCallback {
        void onResult(@NonNull List<DriveFile> files, @NonNull List<DriveFolder> folders);
    }

    public interface SearchCallback {
        void onResult(@NonNull List<DriveFile> files);
    }

    public interface CombinedSearchCallback {
        void onResult(@NonNull List<DriveFile> files, @NonNull List<DriveFolder> folders);
    }

    /** Notified whenever TeleDrive's file list changes (upload, delete, or remote sync). */
    public interface DriveChangeListener {
        void onFilesChanged();
    }

    private final List<DriveChangeListener> mChangeListeners = new ArrayList<>();

    public void addChangeListener(@NonNull DriveChangeListener l) {
        if (!mChangeListeners.contains(l)) mChangeListeners.add(l);
    }

    public void removeChangeListener(@NonNull DriveChangeListener l) {
        mChangeListeners.remove(l);
    }

    private void notifyFilesChanged() {
        // Must be called on UI thread
        for (DriveChangeListener l : new ArrayList<>(mChangeListeners)) l.onFilesChanged();
    }

    public void addUploadListener(UploadProgressListener l)    { mUploadListeners.add(l); }
    public void removeUploadListener(UploadProgressListener l) { mUploadListeners.remove(l); }

    public java.util.Map<String, Float> getActiveUploads() {
        synchronized (mActiveUploads) {
            return new java.util.LinkedHashMap<>(mActiveUploads);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Initialise the repository for the given account.
     * Call once from the application's main activity after login.
     *
     * Triggers a remote metadata sync in the background.
     */
    @MainThread
    public void init() {
        mMeta.syncFromRemote((success, error) -> {
            if (!success) {
                FileLog.d("[DriveRepository] No remote metadata found (fresh install or first use).");
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FILE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Upload a file (identified by {@code uri}) to the user's Saved Messages,
     * assigning it to {@code folderId}.
     *
     * Progress and result are delivered via {@code callback} on the main thread.
     *
     * @param context  Android context (used to resolve the URI).
     * @param uri      Content URI of the file to upload.
     * @param folderId Target folder UUID, or {@link DriveFolder#ROOT_ID} for root.
     * @param callback Upload lifecycle callbacks.
     */
    @MainThread
    public void uploadFile(@NonNull Context context,
                           @NonNull Uri uri,
                           @NonNull String folderId,
                           @NonNull UploadCallback callback) {
        mQueue.postRunnable(() -> {
            try {
                doUpload(context, uri, folderId, callback);
            } catch (Exception e) {
                FileLog.e("[DriveRepository] uploadFile error", e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(
                        e.getMessage() != null ? e.getMessage() : "Upload failed"));
            }
        });
    }

    @NonNull
    private void doUpload(@NonNull Context context,
                          @NonNull Uri uri,
                          @NonNull String folderId,
                          @NonNull UploadCallback callback) throws Exception {
        // 1. Resolve file info from URI
        FileInfo info = resolveFileInfo(context, uri);

        // 2. Copy to a Telegram-accessible path (if needed)
        String filePath = ensureAccessiblePath(context, uri, info.name);

        // 3. Register as pending so we can match the notification later
        PendingUpload pu = new PendingUpload(folderId, info.name, callback);
        try { pu.fileSize = new java.io.File(filePath).length(); } catch (Exception ignored) {}
        synchronized (mPendingUploads) {
            mPendingUploads.put(filePath, pu);
        }
        android.util.Log.d("TeleDrive", "doUpload: path=" + filePath
                + " fileName=" + info.name + " folderId=" + folderId
                + " pendingCount=" + mPendingUploads.size());

        // 4. Kick off the Telegram upload
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) {
            synchronized (mPendingUploads) { mPendingUploads.remove(filePath); }
            AndroidUtilities.runOnUIThread(() -> callback.onError("Not logged in"));
            return;
        }

        final String displayName = info.name;
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (mActiveUploads) { mActiveUploads.put(displayName, 0f); }
            for (UploadProgressListener l : mUploadListeners) l.onUploadStarted(displayName);
        });

        final String finalPath = filePath;
        AndroidUtilities.runOnUIThread(() ->
            SendMessagesHelper.prepareSendingDocument(
                    AccountInstance.getInstance(mAccount),
                    finalPath,          // path
                    finalPath,          // originalPath
                    null,               // uri (already resolved to path)
                    null,               // caption
                    info.mimeType,      // mime
                    selfId,             // dialogId = Saved Messages
                    null,               // replyToMsg
                    null,               // replyToTopMsg
                    null,               // storyItem
                    null,               // quote
                    null,               // editingMessageObject
                    true,               // notify
                    0,                  // scheduleDate
                    null,               // inputContent
                    null,               // quickReplyShortcut
                    0,                  // quickReplyShortcutId
                    false               // invertMedia
            )
        );
    }

    /**
     * Download a file to the local cache directory.
     *
     * @param file     The {@link DriveFile} to download.
     * @param callback Download lifecycle callbacks.
     */
    @MainThread
    public void downloadFile(@NonNull DriveFile file, @NonNull DownloadCallback callback) {
        // Always fetch a fresh file_reference from Telegram before downloading.
        // This prevents FILE_REFERENCE_EXPIRED errors on files stored from previous sessions.
        AndroidUtilities.runOnUIThread(() -> callback.onProgress(0f));
        refreshDocInfoThenDownload(file, callback);
    }

    /**
     * Fetch the Telegram message by its ID, extract the document's identity fields,
     * persist them, then start the actual download.
     */
    private void refreshDocInfoThenDownload(@NonNull DriveFile file,
                                             @NonNull DownloadCallback callback) {
        if (file.messageId > 0) {
            // We have a real server-assigned message ID — fetch it directly
            fetchByMessageId(file, callback);
        } else {
            // Temporary/negative message ID (saved before server confirmation)
            // — scan recent Saved Messages history to find the file by name
            scanHistoryForFile(file, callback);
        }
    }

    /** Fetch a specific message by ID and extract its document. */
    private void fetchByMessageId(@NonNull DriveFile file, @NonNull DownloadCallback callback) {
        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id.add((int) file.messageId);
        android.util.Log.d("TeleDrive", "fetchByMsgId: msgId=" + file.messageId);
        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(
                        "Không thể lấy thông tin file: " + (error != null ? error.text : "no response")));
                return;
            }
            TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
            android.util.Log.d("TeleDrive", "fetchByMsgId response: " + msgs.messages.size() + " msgs");
            if (applyDocFromMessages(msgs.messages, file)) {
                AndroidUtilities.runOnUIThread(() -> doDownloadFile(file, callback));
            } else {
                // Message ID might have changed — fall back to history scan
                scanHistoryForFile(file, callback);
            }
        });
    }

    /**
     * Scan the last 100 messages in Saved Messages to find a document matching
     * {@code file.name}. Used when the stored messageId is stale/negative.
     */
    private void scanHistoryForFile(@NonNull DriveFile file, @NonNull DownloadCallback callback) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Not logged in"));
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer         = MessagesController.getInstance(mAccount).getInputPeer(selfId);
        req.limit        = 100;
        req.offset_id    = 0;
        req.offset_date  = 0;
        req.add_offset   = 0;
        req.max_id       = 0;
        req.min_id       = 0;
        req.hash         = 0;
        android.util.Log.d("TeleDrive", "scanHistory for file=" + file.name);
        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                AndroidUtilities.runOnUIThread(() -> callback.onError(
                        "Không thể quét lịch sử: " + (error != null ? error.text : "no response")));
                return;
            }
            TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
            android.util.Log.d("TeleDrive", "scanHistory: " + msgs.messages.size() + " msgs");
            if (applyDocFromMessages(msgs.messages, file)) {
                AndroidUtilities.runOnUIThread(() -> doDownloadFile(file, callback));
            } else {
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Không tìm thấy file trong Telegram. Thử tải lại file."));
            }
        });
    }

    /**
     * Try to find a document in {@code messages} that matches {@code file.name}.
     * If found, updates file's docId/dcId/etc. in memory AND in the DB.
     * @return true if a matching document was found and applied.
     */
    private boolean applyDocFromMessages(@NonNull java.util.List<TLRPC.Message> messages,
                                          @NonNull DriveFile file) {
        for (TLRPC.Message msg : messages) {
            if (!(msg.media instanceof TLRPC.TL_messageMediaDocument)) continue;
            TLRPC.Document rawDoc = ((TLRPC.TL_messageMediaDocument) msg.media).document;
            if (!(rawDoc instanceof TLRPC.TL_document)) continue;
            TLRPC.TL_document doc = (TLRPC.TL_document) rawDoc;
            if (doc.id == 0) continue;

            // Match by filename (for history scan) or accept any doc (for exact msgId fetch)
            String docName = getDocumentFileName(doc);
            boolean nameMatches = file.name.equals(docName);
            boolean isSameMsg   = (msg.id == (int) file.messageId);
            if (!nameMatches && !isSameMsg) continue;

            long oldMsgId = file.messageId;
            file.messageId     = msg.id;
            file.docId         = doc.id;
            file.dcId          = doc.dc_id;
            file.accessHash    = doc.access_hash;
            file.fileReference = doc.file_reference;
            if (file.mimeType == null || file.mimeType.isEmpty()) file.mimeType = doc.mime_type;
            if (file.size == 0) file.size = doc.size;

            android.util.Log.d("TeleDrive", "applyDoc: oldMsgId=" + oldMsgId
                    + " newMsgId=" + file.messageId + " docId=" + file.docId + " dcId=" + file.dcId);

            if (oldMsgId != file.messageId) {
                // Primary key changed — use fixTempFileEntry
                mStorage.fixTempFileEntry(oldMsgId, file.messageId, file.docId, file.dcId,
                        file.accessHash, file.fileReference, file.mimeType, file.size);
            } else {
                mStorage.updateDocInfo(file.messageId, file.docId, file.dcId,
                        file.accessHash, file.fileReference);
            }
            return true;
        }
        return false;
    }

    private void doDownloadFile(@NonNull DriveFile file, @NonNull DownloadCallback callback) {
        TLRPC.TL_document doc = buildDoc(file);
        String expectedName = org.telegram.messenger.FileLoader.getAttachFileName(doc);
        android.util.Log.d("TeleDrive", "doDownloadFile key=" + expectedName + " file=" + file.name
                + " docId=" + file.docId + " dcId=" + file.dcId);
        // Guard: skip if this exact key is already actively downloading
        if (mDownloadCallbacks.containsKey(expectedName)) {
            android.util.Log.d("TeleDrive", "doDownloadFile: already downloading " + expectedName + ", skipping");
            return;
        }
        mDownloadCallbacks.put(expectedName, new PendingDownload(file, callback));
        org.telegram.messenger.FileLoader.getInstance(mAccount)
                .loadFile(doc, "drive_" + mAccount, 1, 0);
        AndroidUtilities.runOnUIThread(() -> callback.onProgress(0f));
    }

    /**
     * Returns the local cached path for {@code file} if already downloaded, or {@code null}.
     */
    @Nullable
    public String getDownloadPath(@NonNull DriveFile file) {
        if (file.docId == 0) return null;
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = file.docId;
        doc.dc_id = file.dcId;
        doc.access_hash = file.accessHash;
        doc.file_reference = file.fileReference != null ? file.fileReference : new byte[0];
        doc.size = file.size;
        doc.mime_type = file.mimeType != null ? file.mimeType : "";
        TLRPC.TL_documentAttributeFilename attr = new TLRPC.TL_documentAttributeFilename();
        attr.file_name = file.name;
        doc.attributes.add(attr);
        String fileName = FileLoader.getAttachFileName(doc);
        // Check MEDIA_DIR_DOCUMENT first (cacheType=0 permanent)
        java.io.File localFile = new java.io.File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), fileName);
        if (localFile.exists()) return localFile.getAbsolutePath();
        // Fallback: MEDIA_DIR_CACHE (cacheType=1 temp)
        localFile = new java.io.File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
        if (localFile.exists()) return localFile.getAbsolutePath();
        return null;
    }

    /** Bundles a download's file info, callback, and retry count together. */
    private static final class PendingDownload {
        final DriveFile      file;
        final DownloadCallback callback;
        int retryCount;
        PendingDownload(DriveFile f, DownloadCallback cb) { file = f; callback = cb; }
    }

    /** Active download callbacks, keyed by expected local filename. */
    private final Map<String, PendingDownload> mDownloadCallbacks = new HashMap<>();

    /**
     * Delete a file from TeleDrive: removes it from Saved Messages AND from the local cache.
     *
     * @param file     The {@link DriveFile} to delete.
     * @param callback Result callback.
     */
    @MainThread
    public void deleteFile(@NonNull DriveFile file, @NonNull SimpleCallback callback) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) {
            callback.onError("Not logged in");
            return;
        }

        TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
        req.id.add((int) file.messageId);
        req.revoke = true; // Delete for everyone (including Saved Messages history)

        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Delete failed: " + error.text));
                return;
            }
            // Remove from local cache
            mMeta.unregisterFile(file.messageId);
            AndroidUtilities.runOnUIThread(callback::onSuccess);
        });
    }

    /**
     * Rename a file (display name only — does not modify the Telegram message).
     *
     * @param file    The file to rename.
     * @param newName New display name.
     * @param callback Result callback.
     */
    @MainThread
    public void renameFile(@NonNull DriveFile file,
                           @NonNull String newName,
                           @NonNull SimpleCallback callback) {
        if (TextUtils.isEmpty(newName)) {
            callback.onError("Name cannot be empty");
            return;
        }
        mMeta.updateFileMeta(file.messageId, newName, null);
        AndroidUtilities.runOnUIThread(callback::onSuccess);
    }

    /**
     * Move a file to a different folder.
     *
     * @param file        The file to move.
     * @param newFolderId Target folder UUID, or {@link DriveFolder#ROOT_ID} for root.
     * @param callback    Result callback.
     */
    @MainThread
    public void moveFile(@NonNull DriveFile file,
                         @NonNull String newFolderId,
                         @NonNull SimpleCallback callback) {
        mMeta.updateFileMeta(file.messageId, null, newFolderId);
        AndroidUtilities.runOnUIThread(callback::onSuccess);
    }

    /** Toggle the starred (pinned) state of a file. */
    @MainThread
    public void setFileStarred(@NonNull DriveFile file,
                                boolean starred,
                                @NonNull SimpleCallback callback) {
        mMeta.setFileStarred(file.messageId, starred);
        AndroidUtilities.runOnUIThread(callback::onSuccess);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FOLDER OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Create a new folder.
     *
     * @param name      Display name.
     * @param parentId  Parent folder UUID, or {@link DriveFolder#ROOT_ID}.
     * @param callback  Result callback.
     */
    @MainThread
    public void createFolder(@NonNull String name,
                             @NonNull String parentId,
                             @NonNull SimpleCallback callback) {
        if (TextUtils.isEmpty(name)) {
            callback.onError("Folder name cannot be empty");
            return;
        }
        DriveFolder folder = DriveFolder.create(name, parentId);
        // Save on the same queue that listFolder uses — guarantees the folder is
        // in the DB before any subsequent listFolder call on the same queue sees it.
        mQueue.postRunnable(() -> {
            mStorage.saveFolder(folder);
            mMeta.saveFolder(folder); // async push to remote (fire-and-forget)
            AndroidUtilities.runOnUIThread(callback::onSuccess);
        });
    }

    /**
     * Rename or recolor an existing folder.
     *
     * @param folderId  UUID of the folder.
     * @param newName   New name, or {@code null} to keep current.
     * @param newColor  New hex color, or {@code null} to keep current.
     * @param newEmoji  New emoji, or {@code null} to keep current.
     * @param callback  Result callback.
     */
    @MainThread
    public void updateFolder(@NonNull String folderId,
                             @Nullable String newName,
                             @Nullable String newColor,
                             @Nullable String newEmoji,
                             @NonNull SimpleCallback callback) {
        mQueue.postRunnable(() -> {
            mStorage.updateFolderMeta(folderId, newName, newColor, newEmoji);
            // Re-read and push to remote
            DriveFolder updated = mStorage.getFolder(folderId);
            if (updated != null) mMeta.saveFolder(updated);
        });
        AndroidUtilities.runOnUIThread(callback::onSuccess);
    }

    /**
     * Delete a folder and all its contents.
     *
     * Files inside the folder are removed from Telegram (Saved Messages) and
     * from the local cache.
     *
     * @param folderId UUID of the folder to delete.
     * @param callback Result callback; called after local changes are applied
     *                 (remote deletes happen asynchronously).
     */
    @MainThread
    public void deleteFolder(@NonNull String folderId, @NonNull SimpleCallback callback) {
        mQueue.postRunnable(() -> {
            // Recursively collect ALL files in the entire folder subtree BEFORE deletion
            List<DriveFile> allFiles = mStorage.getAllFilesInTree(folderId);

            // Delete all local DB entries (files + subfolders) and push updated metadata
            mMeta.deleteFolderTree(folderId);

            // Delete every Telegram message in the tree in one batch request
            if (!allFiles.isEmpty()) {
                TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
                for (DriveFile f : allFiles) req.id.add((int) f.messageId);
                req.revoke = true;
                ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) ->
                        AndroidUtilities.runOnUIThread(callback::onSuccess));
            } else {
                AndroidUtilities.runOnUIThread(callback::onSuccess);
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LISTING & SEARCH
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * List the contents of a folder (files + immediate child folders).
     *
     * @param folderId Folder to list, or {@link DriveFolder#ROOT_ID} for the root.
     * @param callback Result callback on the main thread.
     */
    @MainThread
    public void listFolder(@NonNull String folderId, @NonNull ListCallback callback) {
        mQueue.postRunnable(() -> {
            List<DriveFile>   files   = mStorage.getFilesInFolder(folderId);
            List<DriveFolder> folders = mStorage.getFoldersInParent(folderId);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(files, folders));
        });
    }

    /**
     * Return the N most recently uploaded files (across all folders).
     *
     * @param limit    Maximum number of results.
     * @param callback Result callback on the main thread.
     */
    @MainThread
    public void getRecentFiles(int limit, @NonNull SearchCallback callback) {
        mQueue.postRunnable(() -> {
            List<DriveFile> files = mStorage.getRecentFiles(limit);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(files));
        });
    }

    /**
     * Return all starred / pinned files.
     *
     * @param callback Result callback on the main thread.
     */
    @MainThread
    public void getStarredFiles(@NonNull SearchCallback callback) {
        mQueue.postRunnable(() -> {
            List<DriveFile> files = mStorage.getStarredFiles();
            AndroidUtilities.runOnUIThread(() -> callback.onResult(files));
        });
    }

    /**
     * Full-text search across all file names.
     *
     * @param query Search term.
     * @param type  File type filter ({@link DriveFile#TYPE_IMAGE}, etc.), or {@code -1} for all.
     * @param callback Result callback on the main thread.
     */
    @MainThread
    public void searchFiles(@NonNull String query, int type, @NonNull SearchCallback callback) {
        if (TextUtils.isEmpty(query)) {
            callback.onResult(new ArrayList<>());
            return;
        }
        mQueue.postRunnable(() -> {
            List<DriveFile> files = mStorage.searchFiles(query, type);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(files));
        });
    }

    /** Search both files and folders, returning combined results. */
    @MainThread
    public void searchAll(@NonNull String query, @NonNull CombinedSearchCallback callback) {
        if (TextUtils.isEmpty(query)) {
            callback.onResult(new ArrayList<>(), new ArrayList<>());
            return;
        }
        mQueue.postRunnable(() -> {
            List<DriveFile>   files   = mStorage.searchFiles(query, -1);
            List<DriveFolder> folders = mStorage.searchFolders(query);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(files, folders));
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NOTIFICATION CENTER DELEGATE
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != mAccount) return;

        if (id == NotificationCenter.didReceiveNewMessages) {
            handleNewMessages(args);
        } else if (id == NotificationCenter.messageReceivedByServer) {
            handleMessageReceivedByServer(args);
        } else if (id == NotificationCenter.messageSendError) {
            handleSendError(args);
        } else if (id == NotificationCenter.fileLoaded) {
            handleFileLoaded(args);
        } else if (id == NotificationCenter.fileLoadFailed) {
            handleFileLoadFailed(args);
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            handleFileLoadProgress(args);
        } else if (id == NotificationCenter.fileUploadProgressChanged) {
            handleUploadProgress(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            handleMessagesDeleted(args);
        }
    }

    /**
     * Called when new messages arrive in Saved Messages.
     *
     * TEMP (id < 0): matches pending upload by attachPath → parks in mTempIdToPending.
     * messageReceivedByServer will then bridge tempId→realId into mRealIdToPending.
     *
     * CONFIRMED (id > 0): if mRealIdToPending has an entry, registers with correct folder.
     * Otherwise treats as orphan (from another device) → ROOT with CONFLICT_IGNORE.
     */
    private void handleNewMessages(Object[] args) {
        // args: [0]=dialogId(Long|Integer), [1]=messages(ArrayList), [2]=scheduled, [3]=mode
        if (args == null || args.length < 2) return;
        long dialogId = 0L;
        if (args[0] instanceof Long)         dialogId = (Long) args[0];
        else if (args[0] instanceof Integer) dialogId = ((Integer) args[0]).longValue();

        ArrayList<?> msgs = (ArrayList<?>) args[1];
        if (msgs == null) return;

        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (dialogId != selfId) return;

        for (Object obj : msgs) {
            if (!(obj instanceof org.telegram.messenger.MessageObject)) continue;
            org.telegram.messenger.MessageObject mo = (org.telegram.messenger.MessageObject) obj;
            if (mo.messageOwner == null) continue;

            long moDialogId = mo.getDialogId();
            if (moDialogId != selfId) continue;

            if (mo.messageOwner.id < 0) {
                // ── PHASE 1: temp message ───────────────────────────────────
                // Telegram sets attachPath = filePath on the local temp message.
                // Match against mPendingUploads and park in mTempIdToPending.
                String ap = mo.messageOwner.attachPath;
                android.util.Log.d("TeleDrive", "Phase1: tempId=" + mo.messageOwner.id
                        + " attachPath=" + ap
                        + " pendingKeys=" + mPendingUploads.keySet());
                if (TextUtils.isEmpty(ap)) continue;

                PendingUpload pending = null;
                String pendingKey = null;
                synchronized (mPendingUploads) {
                    if (mPendingUploads.containsKey(ap)) {
                        pendingKey = ap;
                        pending    = mPendingUploads.get(ap);
                    } else {
                        String apName = new java.io.File(ap).getName();
                        for (Map.Entry<String, PendingUpload> e : mPendingUploads.entrySet()) {
                            if (apName.equals(e.getValue().fileName)) {
                                pendingKey = e.getKey(); pending = e.getValue(); break;
                            }
                        }
                        if (pendingKey == null) {
                            for (Map.Entry<String, PendingUpload> e : mPendingUploads.entrySet()) {
                                if (apName.equalsIgnoreCase(e.getValue().fileName)) {
                                    pendingKey = e.getKey(); pending = e.getValue(); break;
                                }
                            }
                        }
                    }
                    if (pendingKey != null) mPendingUploads.remove(pendingKey);
                }
                if (pending != null) {
                    synchronized (mTempIdToPending) {
                        mTempIdToPending.put(mo.messageOwner.id, pending);
                    }
                    android.util.Log.d("TeleDrive", "Phase1: MATCHED tempId=" + mo.messageOwner.id
                            + " fileName=" + pending.fileName + " folderId=" + pending.folderId);
                } else {
                    android.util.Log.d("TeleDrive", "Phase1: NO MATCH for attachPath=" + ap);
                }

            } else if (mo.messageOwner.id > 0) {
                // ── PHASE 2: confirmed message ──────────────────────────────
                // Check if messageReceivedByServer already bridged tempId→realId.
                PendingUpload pending;
                synchronized (mRealIdToPending) {
                    pending = mRealIdToPending.remove(mo.messageOwner.id);
                }
                android.util.Log.d("TeleDrive", "Phase2: realId=" + mo.messageOwner.id
                        + " attachPath=" + mo.messageOwner.attachPath
                        + " bridgeHit=" + (pending != null)
                        + " realIdKeys=" + mRealIdToPending.keySet()
                        + " tempIdKeys=" + mTempIdToPending.keySet()
                        + " pendingKeys=" + mPendingUploads.keySet());

                TLRPC.MessageMedia media = mo.messageOwner.media;
                if (!(media instanceof TLRPC.TL_messageMediaDocument)) continue;
                TLRPC.Document rawDoc = ((TLRPC.TL_messageMediaDocument) media).document;
                if (!(rawDoc instanceof TLRPC.TL_document)) continue;
                TLRPC.TL_document doc = (TLRPC.TL_document) rawDoc;
                if (doc.id == 0) continue;

                String docName = getDocumentFileName(doc);

                // Fallback 1: match by attachPath against mPendingUploads (exact key match)
                if (pending == null) {
                    String ap = mo.messageOwner.attachPath;
                    if (!TextUtils.isEmpty(ap)) {
                        synchronized (mPendingUploads) {
                            pending = mPendingUploads.remove(ap);
                        }
                        // Also try filename from attachPath against mPendingUploads values
                        if (pending == null) {
                            String apName = new java.io.File(ap).getName();
                            synchronized (mPendingUploads) {
                                for (java.util.Iterator<Map.Entry<String, PendingUpload>> it =
                                        mPendingUploads.entrySet().iterator(); it.hasNext(); ) {
                                    Map.Entry<String, PendingUpload> e = it.next();
                                    if (apName.equalsIgnoreCase(e.getValue().fileName)) {
                                        pending = e.getValue(); it.remove(); break;
                                    }
                                }
                            }
                        }
                    }
                }
                // Fallback 2: bridge failed → scan mTempIdToPending by filename
                if (pending == null) {
                    synchronized (mTempIdToPending) {
                        for (java.util.Iterator<Map.Entry<Integer, PendingUpload>> it =
                                mTempIdToPending.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<Integer, PendingUpload> e = it.next();
                            if (docName.equalsIgnoreCase(e.getValue().fileName)) {
                                pending = e.getValue();
                                it.remove();
                                break;
                            }
                        }
                    }
                }
                // Fallback 3: Phase 1 also missed → scan mPendingUploads by docName
                if (pending == null) {
                    synchronized (mPendingUploads) {
                        for (java.util.Iterator<Map.Entry<String, PendingUpload>> it =
                                mPendingUploads.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<String, PendingUpload> e = it.next();
                            if (docName.equalsIgnoreCase(e.getValue().fileName)) {
                                pending = e.getValue();
                                it.remove();
                                break;
                            }
                        }
                    }
                }
                // Fallback 4: if exactly one pending upload exists, assume it's ours
                if (pending == null) {
                    synchronized (mPendingUploads) {
                        if (mPendingUploads.size() == 1) {
                            Map.Entry<String, PendingUpload> sole = mPendingUploads.entrySet().iterator().next();
                            pending = sole.getValue();
                            mPendingUploads.clear();
                        }
                    }
                }

                DriveFile driveFile = new DriveFile();
                driveFile.messageId     = mo.messageOwner.id;
                driveFile.account       = mAccount;
                driveFile.mimeType      = doc.mime_type;
                driveFile.size          = doc.size;
                driveFile.uploadedAt    = mo.messageOwner.date;
                driveFile.documentType  = DriveFile.typeFromMime(doc.mime_type);
                driveFile.docId         = doc.id;
                driveFile.dcId          = doc.dc_id;
                driveFile.accessHash    = doc.access_hash;
                driveFile.fileReference = doc.file_reference;

                if (pending != null) {
                    // Known upload from this device: use correct folder + filename
                    android.util.Log.d("TeleDrive", "Phase2: MATCHED realId=" + mo.messageOwner.id
                            + " fileName=" + pending.fileName + " folderId=" + pending.folderId);
                    driveFile.folderId = pending.folderId;
                    driveFile.name     = pending.fileName;
                    final PendingUpload fp = pending;
                    final DriveFile     ff = driveFile;
                    final String        fn = pending.fileName;
                    // Register file with callback: notify UI only after DB write completes
                    mMeta.registerFile(driveFile, () -> {
                        // Flash 100% first — handles both real uploads and Telegram-cached sends
                        synchronized (mActiveUploads) { mActiveUploads.put(fn, 1.0f); }
                        for (UploadProgressListener l : mUploadListeners) l.onUploadProgress(fn, 1.0f);
                        fp.callback.onProgress(1.0f);
                        // After a short delay, remove the row so the user sees the completed state
                        AndroidUtilities.runOnUIThread(() -> {
                            synchronized (mActiveUploads) { mActiveUploads.remove(fn); }
                            for (UploadProgressListener l : mUploadListeners) l.onUploadFinished(fn, true);
                            fp.callback.onSuccess(ff);
                            notifyFilesChanged();
                        }, 800);
                    });
                } else {
                    // Orphan: from another device or missed match → ROOT, CONFLICT_IGNORE
                    android.util.Log.d("TeleDrive", "Phase2: ORPHAN realId=" + mo.messageOwner.id
                            + " docName=" + docName + " → ROOT_ID");
                    driveFile.folderId = DriveFolder.ROOT_ID;
                    driveFile.name     = !TextUtils.isEmpty(docName) ? docName
                                                                      : ("file_" + mo.messageOwner.id);
                    mMeta.registerFileIfAbsent(driveFile, () -> notifyFilesChanged());
                }
            }
        }
    }

    /**
     * Called when Telegram confirms a sent message was received by the server.
     * args[0] = Integer tempId  (original local negative ID)
     * args[1] = Integer realId  (server-assigned positive ID)
     * args[2] = MessageObject or null  (Telegram always passes null here!)
     * args[3] = Long dialogId
     *
     * IMPORTANT: Telegram does NOT fire didReceivedNewMessages again for the confirmed
     * message. The temp message is updated in-place. So Phase 2 never runs for local uploads.
     * Therefore we must register the file HERE, not in handleNewMessages Phase 2.
     */
    private void handleMessageReceivedByServer(Object[] args) {
        if (args == null || args.length < 2) return;

        int tempId = args[0] instanceof Integer ? (Integer) args[0] : 0;
        int realId = args[1] instanceof Integer ? (Integer) args[1] : 0;
        if (tempId >= 0 || realId <= 0) return; // tempId must be negative

        PendingUpload pending;
        synchronized (mTempIdToPending) {
            pending = mTempIdToPending.remove(tempId);
        }
        android.util.Log.d("TeleDrive", "Bridge: tempId=" + tempId + " realId=" + realId
                + " found=" + (pending != null));
        if (pending == null) return; // not our upload

        // Register a stub file entry with the correct folder.
        // Doc metadata (docId, dcId, accessHash, etc.) will be filled in
        // by syncFilesWithTelegram when it runs next.
        final DriveFile driveFile = new DriveFile();
        driveFile.messageId    = realId;
        driveFile.account      = mAccount;
        driveFile.folderId     = pending.folderId;
        driveFile.name         = pending.fileName;
        driveFile.mimeType     = guessMimeType(pending.fileName);
        driveFile.documentType = DriveFile.typeFromMime(driveFile.mimeType);
        driveFile.size         = pending.fileSize;
        driveFile.uploadedAt   = (int)(System.currentTimeMillis() / 1000L);

        android.util.Log.d("TeleDrive", "Bridge: registering file realId=" + realId
                + " name=" + pending.fileName + " folderId=" + pending.folderId);

        final PendingUpload fp = pending;
        final String        fn = pending.fileName;
        mMeta.registerFile(driveFile, () -> {
            // Flash 100% first
            synchronized (mActiveUploads) { mActiveUploads.put(fn, 1.0f); }
            for (UploadProgressListener l : mUploadListeners) l.onUploadProgress(fn, 1.0f);
            fp.callback.onProgress(1.0f);
            // After a short delay, remove the row so the user sees the completed state
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (mActiveUploads) { mActiveUploads.remove(fn); }
                for (UploadProgressListener l : mUploadListeners) l.onUploadFinished(fn, true);
                fp.callback.onSuccess(driveFile);
                notifyFilesChanged();
            }, 800);
        });
    }

    /** Simple MIME type guess from file extension for stub entries. */
    private static String guessMimeType(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".flac"))
            return "audio/" + lower.substring(lower.lastIndexOf('.') + 1);
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov"))
            return "video/" + lower.substring(lower.lastIndexOf('.') + 1);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
        return "application/octet-stream";
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TELEGRAM → TELEDRIVE  SYNC
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called when the Telegram app deletes messages (even from Saved Messages).
     * args[0] = ArrayList<Integer> deleted message IDs
     * args[1] = long channelId (0 for non-channel)
     */
    private void handleMessagesDeleted(Object[] args) {
        if (args == null || args.length < 1) return;
        if (!(args[0] instanceof ArrayList)) return;
        // Only handle non-channel deletes (Saved Messages is a user chat, channelId=0)
        long channelId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0L;
        if (channelId != 0) return;

        ArrayList<?> ids = (ArrayList<?>) args[0];
        if (ids.isEmpty()) return;

        mQueue.postRunnable(() -> {
            boolean changed = false;
            for (Object obj : ids) {
                if (!(obj instanceof Integer)) continue;
                long msgId = ((Integer) obj).longValue();
                if (mStorage.getFile(msgId) != null) {
                    mStorage.deleteFile(msgId);
                    FileLog.d("[DriveSync] Telegram deleted msgId=" + msgId + " — removed from TeleDrive");
                    changed = true;
                }
            }
            if (changed) AndroidUtilities.runOnUIThread(this::notifyFilesChanged);
        });
    }

    /**
     * Full reconciliation: fetch all messages from Saved Messages and remove any
     * TeleDrive DB entries whose Telegram message no longer exists.
     * Also updates doc metadata (docId/dcId/accessHash) for surviving entries.
     * Call this on app open / fragment resume.
     */
    public void syncFilesWithTelegram(@Nullable SimpleCallback callback) {
        long selfId = UserConfig.getInstance(mAccount).getClientUserId();
        if (selfId == 0) {
            if (callback != null) callback.onError("Not logged in");
            return;
        }
        // Paginate Saved Messages history and collect all real message IDs + doc info
        fetchAllSavedMessages(selfId, new HashSet<>(), new HashMap<>(), (msgIds, docMap) -> {
            mQueue.postRunnable(() -> {
                List<DriveFile> local = mStorage.getAllFiles();
                boolean changed = false;

                // Build a set of local message IDs for fast lookup
                Set<Long> localMsgIds = new HashSet<>();
                for (DriveFile f : local) localMsgIds.add(f.messageId);

                // 1. Remove stale DB entries (message deleted from Telegram)
                for (DriveFile f : local) {
                    if (f.messageId <= 0) continue;
                    if (!msgIds.contains((int) f.messageId)) {
                        mStorage.deleteFile(f.messageId);
                        FileLog.d("[DriveSync] removed stale: " + f.name);
                        changed = true;
                    } else if (f.docId == 0 && docMap.containsKey((int) f.messageId)) {
                        // Update doc metadata for entries that had docId=0
                        DocInfo di = docMap.get((int) f.messageId);
                        if (di != null) {
                            mStorage.updateDocInfo(f.messageId, di.docId, di.dcId,
                                    di.accessHash, di.fileReference);
                            FileLog.d("[DriveSync] updated doc info for: " + f.name);
                        }
                    }
                }

                // 2. Add files found in Telegram that are NOT yet in our local DB
                //    (uploaded from another device, or failed match in handleNewMessages)
                for (Map.Entry<Integer, DocInfo> entry : docMap.entrySet()) {
                    long msgId = entry.getKey();
                    if (localMsgIds.contains(msgId)) continue; // already registered
                    DocInfo di = entry.getValue();
                    DriveFile newFile = new DriveFile();
                    newFile.messageId    = msgId;
                    newFile.account      = mAccount;
                    newFile.name         = !TextUtils.isEmpty(di.fileName)
                            ? di.fileName : ("file_" + msgId);
                    newFile.mimeType     = di.mimeType != null ? di.mimeType : "";
                    newFile.size         = di.size;
                    newFile.uploadedAt   = di.date;
                    newFile.folderId     = DriveFolder.ROOT_ID;
                    newFile.documentType = DriveFile.typeFromMime(di.mimeType);
                    newFile.docId        = di.docId;
                    newFile.dcId         = di.dcId;
                    newFile.accessHash   = di.accessHash;
                    newFile.fileReference = di.fileReference;
                    // CONFLICT_IGNORE: never overwrite an entry already placed in a folder
                    boolean inserted = mStorage.saveFileIfAbsent(newFile);
                    if (inserted) {
                        FileLog.d("[DriveSync] added new file from Telegram: " + newFile.name);
                        changed = true;
                    }
                }

                final boolean didChange = changed;
                AndroidUtilities.runOnUIThread(() -> {
                    if (didChange) notifyFilesChanged();
                    if (callback != null) callback.onSuccess();
                });
            });
        });
    }

    /** Lightweight holder for document identity + display fields collected during sync. */
    private static class DocInfo {
        long  docId; int dcId; long accessHash; byte[] fileReference;
        // Extra fields collected for new-file registration
        String fileName; String mimeType; long size; int date;
        DocInfo(long docId, int dcId, long accessHash, byte[] fr,
                String fileName, String mimeType, long size, int date) {
            this.docId = docId; this.dcId = dcId;
            this.accessHash = accessHash; this.fileReference = fr;
            this.fileName = fileName; this.mimeType = mimeType;
            this.size = size; this.date = date;
        }
    }

    /**
     * Recursive paginator: fetches ALL messages from Saved Messages in batches of 100.
     * No page limit — continues until Telegram returns an empty/partial page.
     */
    private void fetchAllSavedMessages(long selfId, Set<Integer> accIds,
                                       Map<Integer, DocInfo> accDocs,
                                       SyncResultCallback onDone) {
        int offsetId = accIds.isEmpty() ? 0
                : accIds.stream().mapToInt(Integer::intValue).min().orElse(0);

        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer        = MessagesController.getInstance(mAccount).getInputPeer(selfId);
        req.offset_id   = offsetId;
        req.offset_date = 0;
        req.add_offset  = 0;
        req.limit       = 100;
        req.max_id      = 0;
        req.min_id      = 0;
        req.hash        = 0;

        ConnectionsManager.getInstance(mAccount).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                onDone.onResult(accIds, accDocs);
                return;
            }
            List<TLRPC.Message> msgs = ((TLRPC.messages_Messages) response).messages;
            if (msgs.isEmpty()) { onDone.onResult(accIds, accDocs); return; }

            for (TLRPC.Message m : msgs) {
                accIds.add(m.id);
                if (m.media instanceof TLRPC.TL_messageMediaDocument) {
                    TLRPC.Document d = ((TLRPC.TL_messageMediaDocument) m.media).document;
                    if (d instanceof TLRPC.TL_document && d.id != 0) {
                        // Collect filename from attributes
                        String fname = "";
                        for (TLRPC.DocumentAttribute attr : d.attributes) {
                            if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                                String n = ((TLRPC.TL_documentAttributeFilename) attr).file_name;
                                if (!TextUtils.isEmpty(n)) { fname = n; break; }
                            }
                        }
                        accDocs.put(m.id, new DocInfo(d.id, d.dc_id, d.access_hash,
                                d.file_reference, fname, d.mime_type, d.size, m.date));
                    }
                }
            }
            if (msgs.size() < 100) {
                // Reached the end of Saved Messages history
                onDone.onResult(accIds, accDocs);
            } else {
                // More pages exist — keep going
                fetchAllSavedMessages(selfId, accIds, accDocs, onDone);
            }
        });
    }

    private interface SyncResultCallback {
        void onResult(Set<Integer> msgIds, Map<Integer, DocInfo> docMap);
    }

    private void handleSendError(Object[] args) {
        if (args == null || args.length == 0) return;
        if (!(args[0] instanceof org.telegram.messenger.MessageObject)) return;
        org.telegram.messenger.MessageObject mo =
                (org.telegram.messenger.MessageObject) args[0];
        if (mo.messageOwner == null) return;

        String path = mo.messageOwner.attachPath;
        PendingUpload pending = null;
        synchronized (mPendingUploads) {
            if (path != null) pending = mPendingUploads.remove(path);
        }
        if (pending != null) {
            final PendingUpload finalPending = pending;
            final String fn = pending.fileName;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (mActiveUploads) { mActiveUploads.remove(fn); }
                for (UploadProgressListener l : mUploadListeners) l.onUploadFinished(fn, false);
                finalPending.callback.onError("Upload failed — please try again");
            });
        }
    }

    private void handleFileLoaded(Object[] args) {
        if (args == null || args.length < 2) return;
        String fileName = (String) args[0];
        android.util.Log.d("TeleDrive", "fileLoaded key=" + fileName);
        PendingDownload pd = mDownloadCallbacks.remove(fileName);
        if (pd == null) return;

        if (args[1] instanceof File) {
            final File localFile = (File) args[1];
            android.util.Log.d("TeleDrive", "fileLoaded path=" + localFile.getAbsolutePath()
                    + " exists=" + localFile.exists());
            AndroidUtilities.runOnUIThread(() -> {
                pd.callback.onProgress(1f);
                pd.callback.onSuccess(localFile);
            });
        } else {
            android.util.Log.d("TeleDrive", "fileLoaded args[1] is not File: "
                    + (args[1] == null ? "null" : args[1].getClass().getName()));
        }
    }

    private void handleFileLoadFailed(Object[] args) {
        if (args == null || args.length == 0) return;
        String fileName = (String) args[0];
        android.util.Log.d("TeleDrive", "fileLoadFailed key=" + fileName
                + " retryCount=" + (mDownloadCallbacks.containsKey(fileName)
                        ? mDownloadCallbacks.get(fileName).retryCount : "?"));

        PendingDownload pd = mDownloadCallbacks.remove(fileName);
        if (pd == null) return;

        if (pd.retryCount < 2) {
            // Retry: re-issue the FileLoader request (handles transient network blips)
            pd.retryCount++;
            mDownloadCallbacks.put(fileName, pd);
            TLRPC.TL_document retryDoc = buildDoc(pd.file);
            android.util.Log.d("TeleDrive", "fileLoadFailed: retrying (" + pd.retryCount + ") for " + pd.file.name);
            org.telegram.messenger.FileLoader.getInstance(mAccount)
                    .loadFile(retryDoc, "drive_" + mAccount, 1, 0);
        } else {
            android.util.Log.d("TeleDrive", "fileLoadFailed: giving up for " + pd.file.name);
            AndroidUtilities.runOnUIThread(() ->
                    pd.callback.onError("Tải xuống thất bại. Vui lòng thử lại sau."));
        }
    }

    private void handleFileLoadProgress(Object[] args) {
        if (args == null || args.length < 3) return;
        String fileName  = (String) args[0];
        PendingDownload pd = mDownloadCallbacks.get(fileName);
        if (pd == null) return;
        long loaded = 0, total = 0;
        if (args[1] instanceof Long) loaded = (Long) args[1];
        if (args[2] instanceof Long) total  = (Long) args[2];
        if (total > 0) {
            final float progress = (float) loaded / total;
            AndroidUtilities.runOnUIThread(() -> pd.callback.onProgress(progress));
        }
    }

    /** Build a minimal TLRPC.TL_document from a DriveFile (same logic as doDownloadFile). */
    private static TLRPC.TL_document buildDoc(@NonNull DriveFile file) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id             = file.docId;
        doc.dc_id          = file.dcId;
        doc.access_hash    = file.accessHash;
        doc.file_reference = file.fileReference != null ? file.fileReference : new byte[0];
        doc.size           = file.size;
        doc.mime_type      = file.mimeType != null ? file.mimeType : "";
        TLRPC.TL_documentAttributeFilename attr = new TLRPC.TL_documentAttributeFilename();
        attr.file_name = file.name;
        doc.attributes.add(attr);
        return doc;
    }

    private void handleUploadProgress(Object[] args) {
        if (args == null || args.length < 3) return;
        if (!(args[0] instanceof String)) return;
        String path = (String) args[0];
        long uploaded = args[1] instanceof Long ? (Long) args[1] : 0L;
        long total    = args[2] instanceof Long ? (Long) args[2] : 0L;
        // -1L = error/cancel signal from SendMessagesHelper; ignore
        if (uploaded < 0 || total < 0) return;
        float progress = (total > 0) ? Math.min(1f, (float) uploaded / total) : 0f;

        PendingUpload pending;
        synchronized (mPendingUploads) {
            // 1. Exact path match
            pending = mPendingUploads.get(path);
            // 2. Fallback: match by filename (handles path-normalization differences)
            if (pending == null) {
                String base = new java.io.File(path).getName();
                for (java.util.Map.Entry<String, PendingUpload> e : mPendingUploads.entrySet()) {
                    if (new java.io.File(e.getKey()).getName().equals(base)) {
                        pending = e.getValue();
                        break;
                    }
                }
            }
        }
        // 3. Fallback: entry may have moved to mTempIdToPending after Phase 1
        if (pending == null) {
            String base = new java.io.File(path).getName();
            synchronized (mTempIdToPending) {
                for (PendingUpload pu : mTempIdToPending.values()) {
                    if (base.equals(pu.fileName) || base.equalsIgnoreCase(pu.fileName)) {
                        pending = pu;
                        break;
                    }
                }
            }
        }
        // 4. Fallback: entry may have moved to mRealIdToPending after bridging
        if (pending == null) {
            String base = new java.io.File(path).getName();
            synchronized (mRealIdToPending) {
                for (PendingUpload pu : mRealIdToPending.values()) {
                    if (base.equals(pu.fileName) || base.equalsIgnoreCase(pu.fileName)) {
                        pending = pu;
                        break;
                    }
                }
            }
        }
        if (pending == null) {
            android.util.Log.d("TeleDrive", "Progress: NO MATCH path=" + path
                    + " uploaded=" + uploaded + "/" + total
                    + " pendingKeys=" + mPendingUploads.keySet()
                    + " tempIdNames=[" + tempIdNames() + "]"
                    + " realIdNames=[" + realIdNames() + "]");
            return;
        }
        android.util.Log.d("TeleDrive", "Progress: MATCH path=" + path
                + " progress=" + (int)(progress*100) + "% fileName=" + pending.fileName);

        final float fp = progress;
        final PendingUpload fpu = pending;
        final String fn = pending.fileName;
        AndroidUtilities.runOnUIThread(() -> {
            fpu.callback.onProgress(fp);
            synchronized (mActiveUploads) {
                mActiveUploads.put(fn, fp);
            }
            for (UploadProgressListener l : mUploadListeners) l.onUploadProgress(fn, fp);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String tempIdNames() {
        StringBuilder sb = new StringBuilder();
        synchronized (mTempIdToPending) {
            for (PendingUpload pu : mTempIdToPending.values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(pu.fileName);
            }
        }
        return sb.toString();
    }

    private String realIdNames() {
        StringBuilder sb = new StringBuilder();
        synchronized (mRealIdToPending) {
            for (PendingUpload pu : mRealIdToPending.values()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(pu.fileName);
            }
        }
        return sb.toString();
    }

    /**
     * Resolve display name and MIME type from a content URI.
     */
    @NonNull
    private FileInfo resolveFileInfo(@NonNull Context ctx, @NonNull Uri uri) {
        String name     = "file";
        String mimeType = ctx.getContentResolver().getType(uri);

        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx);
            }
        } catch (Exception ignored) {}

        if (TextUtils.isEmpty(mimeType)) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot >= 0) ext = name.substring(dot + 1).toLowerCase();
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mimeType == null) mimeType = "application/octet-stream";
        }

        return new FileInfo(name, mimeType);
    }

    /**
     * If the URI is a content:// URI, copy the file to the app's cache dir so
     * Telegram's upload machinery can access it via a plain file path.
     */
    @NonNull
    private String ensureAccessiblePath(@NonNull Context ctx,
                                         @NonNull Uri uri,
                                         @NonNull String fileName) throws Exception {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            return uri.getPath();
        }

        // Must NOT use internal getCacheDir() — Telegram's isInternalUri() blocks /data/data/...
        // External cache path (/sdcard/Android/data/<pkg>/cache) passes the check.
        File extCache = ctx.getExternalCacheDir();
        File cacheDir = new File(extCache != null ? extCache : ctx.getCacheDir(), "drive_uploads");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File dest = new File(cacheDir, fileName);

        try (InputStream in  = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new Exception("Cannot open input stream for URI: " + uri);
            byte[] buf = new byte[32 * 1024];
            int    read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
        return dest.getAbsolutePath();
    }

    /** Extract the filename from a TLRPC document's attributes. */
    @NonNull
    private static String getDocumentFileName(@NonNull TLRPC.TL_document doc) {
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                String n = ((TLRPC.TL_documentAttributeFilename) attr).file_name;
                if (!TextUtils.isEmpty(n)) return n;
            }
        }
        return "file_" + doc.id;
    }

    private static final class FileInfo {
        final String name;
        final String mimeType;
        FileInfo(String name, String mimeType) {
            this.name     = name;
            this.mimeType = mimeType;
        }
    }
}
