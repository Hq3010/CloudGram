/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger.drive;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;

/**
 * Represents a file stored in TeleDrive (backed by a Telegram message in Saved Messages).
 *
 * The actual binary data lives on Telegram's CDN. Only metadata that Telegram doesn't
 * store natively (display name override, folder assignment, starred state) is managed
 * separately via {@link MetadataManager}.
 */
public class DriveFile implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Document type constants ────────────────────────────────────────────────
    public static final int TYPE_GENERIC   = 0;
    public static final int TYPE_IMAGE     = 1;
    public static final int TYPE_VIDEO     = 2;
    public static final int TYPE_AUDIO     = 3;
    public static final int TYPE_DOCUMENT  = 4;

    // ── Core identity ─────────────────────────────────────────────────────────
    /** Telegram message ID in Saved Messages. Primary key. */
    public long messageId;

    /** Telegram account index (0-based) this file belongs to. */
    public int account;

    // ── Display metadata ──────────────────────────────────────────────────────
    /** User-visible file name (may differ from the original Telegram document name). */
    public String name;

    /** MIME type string, e.g. "application/pdf", "image/jpeg". */
    public String mimeType;

    /** File size in bytes. */
    public long size;

    /** Unix timestamp (seconds) when the file was uploaded. */
    public long uploadedAt;

    /** ID of the parent {@link DriveFolder}. Empty string means root. */
    public String folderId = DriveFolder.ROOT_ID;

    /** Local filesystem path of cached thumbnail (may be null). */
    @Nullable
    public String thumbnailPath;

    /** Whether the user has starred / pinned this file. */
    public boolean isStarred;

    /** Broad category; use TYPE_* constants for comparisons. */
    public int documentType = TYPE_GENERIC;

    // ── Telegram document references (needed to reconstruct downloads) ────────
    public long docId;
    public int  dcId;
    public long accessHash;
    public byte[] fileReference;

    // ─────────────────────────────────────────────────────────────────────────

    public DriveFile() {}

    /**
     * Derive a broad {@code TYPE_*} category from a MIME type string.
     */
    public static int typeFromMime(@Nullable String mime) {
        if (TextUtils.isEmpty(mime)) return TYPE_GENERIC;
        if (mime.startsWith("image/"))       return TYPE_IMAGE;
        if (mime.startsWith("video/"))       return TYPE_VIDEO;
        if (mime.startsWith("audio/"))       return TYPE_AUDIO;
        if (mime.equals("application/pdf")
                || mime.startsWith("text/")
                || mime.contains("document")
                || mime.contains("spreadsheet")
                || mime.contains("presentation")) return TYPE_DOCUMENT;
        return TYPE_GENERIC;
    }

    /** Human-readable size string, e.g. "4.2 MB". */
    @NonNull
    public String formatSize() {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    /** Returns the file extension from the name, lower-cased (e.g. "pdf"), or "" if none. */
    @NonNull
    public String getExtension() {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DriveFile)) return false;
        DriveFile other = (DriveFile) o;
        return messageId == other.messageId && account == other.account;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(messageId) * 31 + account;
    }

    @NonNull
    @Override
    public String toString() {
        return "DriveFile{id=" + messageId + ", name='" + name + "', size=" + formatSize() + "}";
    }
}
