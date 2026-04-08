/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger.drive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local SQLite cache for TeleDrive file and folder metadata.
 *
 * Each Telegram account gets its own database file: {@code teledrive_0.db},
 * {@code teledrive_1.db}, etc.
 *
 * All public methods may be called from any thread; they execute synchronously
 * in the caller's thread. Callers that need async behaviour should wrap calls
 * in a background thread / {@link org.telegram.messenger.DispatchQueue}.
 */
public class DriveStorage extends SQLiteOpenHelper {

    // ── Schema ────────────────────────────────────────────────────────────────
    private static final int    DB_VERSION    = 1;
    private static final String DB_NAME_FMT   = "teledrive_%d.db";

    private static final String TABLE_FILES   = "drive_files";
    private static final String TABLE_FOLDERS = "drive_folders";

    // drive_files columns
    private static final String COL_F_MESSAGE_ID    = "message_id";
    private static final String COL_F_NAME          = "name";
    private static final String COL_F_MIME          = "mime_type";
    private static final String COL_F_SIZE          = "size";
    private static final String COL_F_UPLOADED_AT   = "uploaded_at";
    private static final String COL_F_FOLDER_ID     = "folder_id";
    private static final String COL_F_THUMB         = "thumbnail_path";
    private static final String COL_F_STARRED       = "is_starred";
    private static final String COL_F_DOC_TYPE      = "document_type";
    private static final String COL_F_DOC_ID        = "doc_id";
    private static final String COL_F_DC_ID         = "dc_id";
    private static final String COL_F_ACCESS_HASH   = "access_hash";
    private static final String COL_F_FILE_REF      = "file_reference";

    // drive_folders columns
    private static final String COL_D_ID         = "id";
    private static final String COL_D_NAME       = "name";
    private static final String COL_D_PARENT_ID  = "parent_id";
    private static final String COL_D_CREATED_AT = "created_at";
    private static final String COL_D_COLOR      = "color";
    private static final String COL_D_EMOJI      = "emoji";

    // ── Singleton per account ─────────────────────────────────────────────────
    private static volatile DriveStorage[] sInstances =
            new DriveStorage[UserConfig.MAX_ACCOUNT_COUNT];

    @NonNull
    public static DriveStorage getInstance(int account) {
        if (sInstances[account] == null) {
            synchronized (DriveStorage.class) {
                if (sInstances[account] == null) {
                    sInstances[account] = new DriveStorage(
                            ApplicationLoader.applicationContext, account);
                }
            }
        }
        return sInstances[account];
    }

    private final int mAccount;

    private DriveStorage(@NonNull Context context, int account) {
        super(context, String.format(Locale.US, DB_NAME_FMT, account), null, DB_VERSION);
        mAccount = account;
    }

    // ── SQLiteOpenHelper ──────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_FILES + " (" +
            COL_F_MESSAGE_ID  + " INTEGER PRIMARY KEY, " +
            COL_F_NAME        + " TEXT    NOT NULL, " +
            COL_F_MIME        + " TEXT    DEFAULT '', " +
            COL_F_SIZE        + " INTEGER DEFAULT 0, " +
            COL_F_UPLOADED_AT + " INTEGER DEFAULT 0, " +
            COL_F_FOLDER_ID   + " TEXT    DEFAULT '', " +
            COL_F_THUMB       + " TEXT, " +
            COL_F_STARRED     + " INTEGER DEFAULT 0, " +
            COL_F_DOC_TYPE    + " INTEGER DEFAULT 0, " +
            COL_F_DOC_ID      + " INTEGER DEFAULT 0, " +
            COL_F_DC_ID       + " INTEGER DEFAULT 0, " +
            COL_F_ACCESS_HASH + " INTEGER DEFAULT 0, " +
            COL_F_FILE_REF    + " BLOB" +
            ")"
        );

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_FOLDERS + " (" +
            COL_D_ID         + " TEXT    PRIMARY KEY, " +
            COL_D_NAME       + " TEXT    NOT NULL, " +
            COL_D_PARENT_ID  + " TEXT    DEFAULT '', " +
            COL_D_CREATED_AT + " INTEGER DEFAULT 0, " +
            COL_D_COLOR      + " TEXT    DEFAULT '" + DriveFolder.COLOR_BLUE + "', " +
            COL_D_EMOJI      + " TEXT    DEFAULT '📁'" +
            ")"
        );

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_folder  ON " + TABLE_FILES   + "(" + COL_F_FOLDER_ID + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_starred ON " + TABLE_FILES   + "(" + COL_F_STARRED   + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_date    ON " + TABLE_FILES   + "(" + COL_F_UPLOADED_AT + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_parent ON " + TABLE_FOLDERS + "(" + COL_D_PARENT_ID + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Reserved for future schema migrations.
        FileLog.d("[DriveStorage] onUpgrade from v" + oldVersion + " to v" + newVersion);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FILE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /** Insert or replace a file entry. */
    @WorkerThread
    public void saveFile(@NonNull DriveFile file) {
        ContentValues cv = fileToValues(file);
        try {
            getWritableDatabase().insertWithOnConflict(
                    TABLE_FILES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e("[DriveStorage] saveFile error", e);
        }
    }

    /**
     * Insert a file entry only if no entry with the same messageId already exists.
     * Use this when you want to preserve folder assignments made by the user.
     *
     * @return true if the row was inserted, false if it already existed.
     */
    @WorkerThread
    public boolean saveFileIfAbsent(@NonNull DriveFile file) {
        ContentValues cv = fileToValues(file);
        try {
            long rowId = getWritableDatabase().insertWithOnConflict(
                    TABLE_FILES, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            return rowId != -1;
        } catch (Exception e) {
            FileLog.e("[DriveStorage] saveFileIfAbsent error", e);
            return false;
        }
    }

    /** Remove a file by its Telegram message ID. */
    @WorkerThread
    public void deleteFile(long messageId) {
        try {
            getWritableDatabase().delete(
                    TABLE_FILES,
                    COL_F_MESSAGE_ID + "=?",
                    new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] deleteFile error", e);
        }
    }

    /** Update only the starred flag for a file. */
    @WorkerThread
    public void setFileStarred(long messageId, boolean starred) {
        ContentValues cv = new ContentValues(1);
        cv.put(COL_F_STARRED, starred ? 1 : 0);
        try {
            getWritableDatabase().update(
                    TABLE_FILES, cv,
                    COL_F_MESSAGE_ID + "=?",
                    new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] setFileStarred error", e);
        }
    }

    /** Update display name and folder assignment for a file (rename / move). */
    @WorkerThread
    public void updateFileMeta(long messageId, @Nullable String newName, @Nullable String newFolderId) {
        ContentValues cv = new ContentValues(2);
        if (newName     != null) cv.put(COL_F_NAME,      newName);
        if (newFolderId != null) cv.put(COL_F_FOLDER_ID, newFolderId);
        if (cv.size() == 0) return;
        try {
            getWritableDatabase().update(
                    TABLE_FILES, cv,
                    COL_F_MESSAGE_ID + "=?",
                    new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] updateFileMeta error", e);
        }
    }

    /** Update Telegram document identity fields for an existing file entry. */
    @WorkerThread
    public void updateDocInfo(long messageId, long docId, int dcId, long accessHash,
                              @Nullable byte[] fileReference) {
        ContentValues cv = new ContentValues(4);
        cv.put(COL_F_DOC_ID,      docId);
        cv.put(COL_F_DC_ID,       dcId);
        cv.put(COL_F_ACCESS_HASH, accessHash);
        cv.put(COL_F_FILE_REF,    fileReference);
        try {
            getWritableDatabase().update(
                    TABLE_FILES, cv,
                    COL_F_MESSAGE_ID + "=?",
                    new String[]{String.valueOf(messageId)});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] updateDocInfo error", e);
        }
    }

    /**
     * Replace a stub entry that has a temporary (negative) messageId with the real
     * server-assigned messageId and full document info.
     */
    @WorkerThread
    public void fixTempFileEntry(long oldMsgId, long newMsgId, long docId, int dcId,
                                 long accessHash, @Nullable byte[] fileReference,
                                 @Nullable String mimeType, long size) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            // Update the primary key and doc fields in one shot
            ContentValues cv = new ContentValues(8);
            cv.put(COL_F_MESSAGE_ID, newMsgId);
            cv.put(COL_F_DOC_ID,     docId);
            cv.put(COL_F_DC_ID,      dcId);
            cv.put(COL_F_ACCESS_HASH, accessHash);
            cv.put(COL_F_FILE_REF,   fileReference);
            if (mimeType != null && !mimeType.isEmpty()) cv.put(COL_F_MIME, mimeType);
            if (size > 0) cv.put(COL_F_SIZE, size);
            db.update(TABLE_FILES, cv, COL_F_MESSAGE_ID + "=?",
                    new String[]{String.valueOf(oldMsgId)});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e("[DriveStorage] fixTempFileEntry error", e);
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) {}
        }
    }

    /** Return all files inside a specific folder (use {@link DriveFolder#ROOT_ID} for root). */
    @WorkerThread
    @NonNull
    public List<DriveFile> getFilesInFolder(@NonNull String folderId) {
        return queryFiles(
                COL_F_FOLDER_ID + "=?",
                new String[]{folderId},
                COL_F_UPLOADED_AT + " DESC");
    }

    /** Return all starred files across all folders, newest first. */
    @WorkerThread
    @NonNull
    public List<DriveFile> getStarredFiles() {
        return queryFiles(
                COL_F_STARRED + "=1",
                null,
                COL_F_UPLOADED_AT + " DESC");
    }

    /** Return the N most recently uploaded files across all folders. */
    @WorkerThread
    @NonNull
    public List<DriveFile> getRecentFiles(int limit) {
        List<DriveFile> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_FILES, null,
                null, null, null, null,
                COL_F_UPLOADED_AT + " DESC",
                String.valueOf(limit))) {
            while (c.moveToNext()) result.add(cursorToFile(c));
        } catch (Exception e) {
            FileLog.e("[DriveStorage] getRecentFiles error", e);
        }
        return result;
    }

    /**
     * Full-text search across file names (case-insensitive LIKE).
     *
     * @param query Raw search term (will be wrapped with % wildcards).
     */
    @WorkerThread
    @NonNull
    public List<DriveFile> searchFiles(@NonNull String query) {
        return queryFiles(
                COL_F_NAME + " LIKE ?",
                new String[]{"%" + query + "%"},
                COL_F_UPLOADED_AT + " DESC");
    }

    /**
     * Search files filtered by type, e.g. {@link DriveFile#TYPE_IMAGE}.
     * Pass {@code -1} to search all types.
     */
    @WorkerThread
    @NonNull
    public List<DriveFile> searchFiles(@NonNull String query, int type) {
        if (type < 0) return searchFiles(query);
        return queryFiles(
                COL_F_NAME + " LIKE ? AND " + COL_F_DOC_TYPE + "=?",
                new String[]{"%" + query + "%", String.valueOf(type)},
                COL_F_UPLOADED_AT + " DESC");
    }

    /** Fetch a single file by its Telegram message ID, or {@code null} if not found. */
    @WorkerThread
    @Nullable
    public DriveFile getFile(long messageId) {
        List<DriveFile> list = queryFiles(
                COL_F_MESSAGE_ID + "=?",
                new String[]{String.valueOf(messageId)},
                null);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Total number of files in the local cache. */
    @WorkerThread
    public int getFileCount() {
        try {
            return (int) getReadableDatabase()
                    .compileStatement("SELECT COUNT(*) FROM " + TABLE_FILES)
                    .simpleQueryForLong();
        } catch (Exception e) {
            FileLog.e("[DriveStorage] getFileCount error", e);
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FOLDER OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /** Insert or replace a folder entry. */
    @WorkerThread
    public void saveFolder(@NonNull DriveFolder folder) {
        ContentValues cv = folderToValues(folder);
        try {
            getWritableDatabase().insertWithOnConflict(
                    TABLE_FOLDERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e("[DriveStorage] saveFolder error", e);
        }
    }

    /** Remove a folder by ID. Does NOT cascade-delete files (caller's responsibility). */
    @WorkerThread
    public void deleteFolder(@NonNull String folderId) {
        try {
            getWritableDatabase().delete(
                    TABLE_FOLDERS,
                    COL_D_ID + "=?",
                    new String[]{folderId});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] deleteFolder error", e);
        }
    }

    /**
     * Recursively delete a folder and ALL its contents (subfolders + files) from local DB.
     * Call on a worker thread.
     */
    @WorkerThread
    public void deleteFolderTree(@NonNull String folderId) {
        for (DriveFolder sub : getFoldersInParent(folderId)) {
            deleteFolderTree(sub.id);
        }
        for (DriveFile f : getFilesInFolder(folderId)) {
            deleteFile(f.messageId);
        }
        deleteFolder(folderId);
    }

    /**
     * Recursively collect ALL files in a folder subtree (this folder + all subfolders).
     * Call on a worker thread.
     */
    @WorkerThread
    @NonNull
    public List<DriveFile> getAllFilesInTree(@NonNull String rootFolderId) {
        List<DriveFile> result = new ArrayList<>();
        collectFilesRecursive(rootFolderId, result);
        return result;
    }

    private void collectFilesRecursive(@NonNull String folderId, @NonNull List<DriveFile> out) {
        out.addAll(getFilesInFolder(folderId));
        for (DriveFolder sub : getFoldersInParent(folderId)) {
            collectFilesRecursive(sub.id, out);
        }
    }

    /** Update display name (and optionally color/emoji) of an existing folder. */
    @WorkerThread
    public void updateFolderMeta(@NonNull String folderId,
                                  @Nullable String newName,
                                  @Nullable String newColor,
                                  @Nullable String newEmoji) {
        ContentValues cv = new ContentValues(3);
        if (!TextUtils.isEmpty(newName))  cv.put(COL_D_NAME,  newName);
        if (!TextUtils.isEmpty(newColor)) cv.put(COL_D_COLOR, newColor);
        if (!TextUtils.isEmpty(newEmoji)) cv.put(COL_D_EMOJI, newEmoji);
        if (cv.size() == 0) return;
        try {
            getWritableDatabase().update(
                    TABLE_FOLDERS, cv,
                    COL_D_ID + "=?",
                    new String[]{folderId});
        } catch (Exception e) {
            FileLog.e("[DriveStorage] updateFolderMeta error", e);
        }
    }

    /** Return all direct child folders of a given parent folder. */
    @WorkerThread
    @NonNull
    public List<DriveFolder> getFoldersInParent(@NonNull String parentId) {
        return queryFolders(
                COL_D_PARENT_ID + "=?",
                new String[]{parentId},
                COL_D_NAME + " ASC");
    }

    /** Fetch a single folder by its UUID, or {@code null} if not found. */
    @WorkerThread
    @Nullable
    public DriveFolder getFolder(@NonNull String folderId) {
        List<DriveFolder> list = queryFolders(
                COL_D_ID + "=?",
                new String[]{folderId},
                null);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Return all folders (used for metadata serialisation). */
    @WorkerThread
    @NonNull
    public List<DriveFolder> getAllFolders() {
        return queryFolders(null, null, COL_D_CREATED_AT + " ASC");
    }

    /** Search folders by name (case-insensitive LIKE). */
    @WorkerThread
    @NonNull
    public List<DriveFolder> searchFolders(@NonNull String query) {
        return queryFolders(
                COL_D_NAME + " LIKE ?",
                new String[]{"%" + query + "%"},
                COL_D_CREATED_AT + " DESC");
    }

    /** Return all files (used for metadata serialisation). */
    @WorkerThread
    @NonNull
    public List<DriveFile> getAllFiles() {
        return queryFiles(null, null, COL_F_UPLOADED_AT + " ASC");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    @NonNull
    private List<DriveFile> queryFiles(@Nullable String selection,
                                        @Nullable String[] selectionArgs,
                                        @Nullable String orderBy) {
        List<DriveFile> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_FILES, null,
                selection, selectionArgs,
                null, null, orderBy)) {
            while (c.moveToNext()) result.add(cursorToFile(c));
        } catch (Exception e) {
            FileLog.e("[DriveStorage] queryFiles error", e);
        }
        return result;
    }

    @NonNull
    private List<DriveFolder> queryFolders(@Nullable String selection,
                                            @Nullable String[] selectionArgs,
                                            @Nullable String orderBy) {
        List<DriveFolder> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_FOLDERS, null,
                selection, selectionArgs,
                null, null, orderBy)) {
            while (c.moveToNext()) result.add(cursorToFolder(c));
        } catch (Exception e) {
            FileLog.e("[DriveStorage] queryFolders error", e);
        }
        return result;
    }

    @NonNull
    private static DriveFile cursorToFile(@NonNull Cursor c) {
        DriveFile f = new DriveFile();
        f.messageId    = c.getLong(c.getColumnIndexOrThrow(COL_F_MESSAGE_ID));
        f.name         = c.getString(c.getColumnIndexOrThrow(COL_F_NAME));
        f.mimeType     = c.getString(c.getColumnIndexOrThrow(COL_F_MIME));
        f.size         = c.getLong(c.getColumnIndexOrThrow(COL_F_SIZE));
        f.uploadedAt   = c.getLong(c.getColumnIndexOrThrow(COL_F_UPLOADED_AT));
        f.folderId     = c.getString(c.getColumnIndexOrThrow(COL_F_FOLDER_ID));
        f.thumbnailPath = c.getString(c.getColumnIndexOrThrow(COL_F_THUMB));
        f.isStarred    = c.getInt(c.getColumnIndexOrThrow(COL_F_STARRED)) == 1;
        f.documentType = c.getInt(c.getColumnIndexOrThrow(COL_F_DOC_TYPE));
        f.docId        = c.getLong(c.getColumnIndexOrThrow(COL_F_DOC_ID));
        f.dcId         = c.getInt(c.getColumnIndexOrThrow(COL_F_DC_ID));
        f.accessHash   = c.getLong(c.getColumnIndexOrThrow(COL_F_ACCESS_HASH));
        int refIdx     = c.getColumnIndexOrThrow(COL_F_FILE_REF);
        if (!c.isNull(refIdx)) f.fileReference = c.getBlob(refIdx);
        return f;
    }

    @NonNull
    private static DriveFolder cursorToFolder(@NonNull Cursor c) {
        DriveFolder d = new DriveFolder();
        d.id        = c.getString(c.getColumnIndexOrThrow(COL_D_ID));
        d.name      = c.getString(c.getColumnIndexOrThrow(COL_D_NAME));
        d.parentId  = c.getString(c.getColumnIndexOrThrow(COL_D_PARENT_ID));
        d.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_D_CREATED_AT));
        d.color     = c.getString(c.getColumnIndexOrThrow(COL_D_COLOR));
        d.emoji     = c.getString(c.getColumnIndexOrThrow(COL_D_EMOJI));
        return d;
    }

    @NonNull
    private static ContentValues fileToValues(@NonNull DriveFile f) {
        ContentValues cv = new ContentValues(13);
        cv.put(COL_F_MESSAGE_ID,  f.messageId);
        cv.put(COL_F_NAME,        f.name != null ? f.name : "");
        cv.put(COL_F_MIME,        f.mimeType != null ? f.mimeType : "");
        cv.put(COL_F_SIZE,        f.size);
        cv.put(COL_F_UPLOADED_AT, f.uploadedAt);
        cv.put(COL_F_FOLDER_ID,   f.folderId != null ? f.folderId : DriveFolder.ROOT_ID);
        cv.put(COL_F_THUMB,       f.thumbnailPath);
        cv.put(COL_F_STARRED,     f.isStarred ? 1 : 0);
        cv.put(COL_F_DOC_TYPE,    f.documentType);
        cv.put(COL_F_DOC_ID,      f.docId);
        cv.put(COL_F_DC_ID,       f.dcId);
        cv.put(COL_F_ACCESS_HASH, f.accessHash);
        cv.put(COL_F_FILE_REF,    f.fileReference);
        return cv;
    }

    @NonNull
    private static ContentValues folderToValues(@NonNull DriveFolder d) {
        ContentValues cv = new ContentValues(6);
        cv.put(COL_D_ID,         d.id);
        cv.put(COL_D_NAME,       d.name != null ? d.name : "");
        cv.put(COL_D_PARENT_ID,  d.parentId != null ? d.parentId : DriveFolder.ROOT_ID);
        cv.put(COL_D_CREATED_AT, d.createdAt);
        cv.put(COL_D_COLOR,      d.color != null ? d.color : DriveFolder.COLOR_BLUE);
        cv.put(COL_D_EMOJI,      d.emoji != null ? d.emoji : "📁");
        return cv;
    }
}
