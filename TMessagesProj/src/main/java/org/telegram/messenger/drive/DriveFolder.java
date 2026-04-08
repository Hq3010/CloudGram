/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.messenger.drive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Serializable;
import java.util.UUID;

/**
 * Represents a virtual folder in TeleDrive.
 *
 * Folders are purely a client-side concept: they do not exist as separate Telegram
 * entities. The folder tree is serialised to JSON and stored by {@link MetadataManager}.
 */
public class DriveFolder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Sentinel value for the root "My Drive" level — not a real folder object. */
    public static final String ROOT_ID = "";

    // ── Predefined colors for folder icons ────────────────────────────────────
    public static final String COLOR_BLUE   = "#1A73E8";
    public static final String COLOR_GREEN  = "#34A853";
    public static final String COLOR_RED    = "#EA4335";
    public static final String COLOR_YELLOW = "#FBBC05";
    public static final String COLOR_PURPLE = "#8B5CF6";
    public static final String COLOR_TEAL   = "#0D9488";

    // ── Fields ────────────────────────────────────────────────────────────────

    /** UUID string, unique per folder. */
    public String id;

    /** User-visible folder name. */
    public String name;

    /**
     * ID of the parent folder.
     * {@link #ROOT_ID} (empty string) means this folder is at the root level.
     */
    public String parentId = ROOT_ID;

    /** Unix timestamp (seconds) when the folder was created. */
    public long createdAt;

    /** Hex color for the folder icon, e.g. {@value #COLOR_BLUE}. */
    public String color = COLOR_BLUE;

    /** Optional emoji displayed alongside the folder icon, e.g. "📁". */
    public String emoji = "📁";

    // ─────────────────────────────────────────────────────────────────────────

    public DriveFolder() {}

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create a new folder with a generated UUID and default styling.
     *
     * @param name     Display name for the folder.
     * @param parentId Parent folder ID, or {@code null} / {@link #ROOT_ID} for the root.
     */
    @NonNull
    public static DriveFolder create(@NonNull String name, @Nullable String parentId) {
        DriveFolder folder = new DriveFolder();
        folder.id       = UUID.randomUUID().toString();
        folder.name     = name;
        folder.parentId = (parentId != null && !parentId.isEmpty()) ? parentId : ROOT_ID;
        folder.createdAt = System.currentTimeMillis() / 1000L;
        folder.color    = COLOR_BLUE;
        folder.emoji    = "📁";
        return folder;
    }

    /** Returns {@code true} if this folder lives directly under the root. */
    public boolean isTopLevel() {
        return ROOT_ID.equals(parentId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DriveFolder)) return false;
        return id != null && id.equals(((DriveFolder) o).id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @NonNull
    @Override
    public String toString() {
        return "DriveFolder{id='" + id + "', name='" + name + "', parent='" + parentId + "'}";
    }
}
