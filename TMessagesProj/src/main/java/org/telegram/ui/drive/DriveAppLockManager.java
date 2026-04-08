/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;

/**
 * Manages app lock state: lock type, stored credential hash, enabled flag.
 * Uses SharedPreferences for persistence.
 */
public class DriveAppLockManager {

    public static final int LOCK_NONE     = 0;
    public static final int LOCK_PIN      = 1;
    public static final int LOCK_PASSWORD = 2;
    public static final int LOCK_PATTERN  = 3;

    private static final String PREFS_NAME      = "drive_app_lock";
    private static final String KEY_ENABLED      = "lock_enabled";
    private static final String KEY_LOCK_TYPE    = "lock_type";
    private static final String KEY_CREDENTIAL   = "lock_credential_hash";
    private static final String KEY_CREDENTIAL_LEN = "lock_credential_length";

    private static DriveAppLockManager sInstance;
    private final SharedPreferences prefs;

    // In-memory flag: true when the app is currently "unlocked" for this session
    private boolean sessionUnlocked = false;

    private DriveAppLockManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized DriveAppLockManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DriveAppLockManager(context);
        }
        return sInstance;
    }

    public boolean isLockEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public int getLockType() {
        return prefs.getInt(KEY_LOCK_TYPE, LOCK_NONE);
    }

    public String getLockTypeLabel() {
        switch (getLockType()) {
            case LOCK_PIN:      return "PIN";
            case LOCK_PASSWORD: return "Password";
            case LOCK_PATTERN:  return "Pattern";
            default:            return "None";
        }
    }

    /** Enable lock with given type and raw credential (will be hashed). */
    public void enableLock(int lockType, String rawCredential) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_LOCK_TYPE, lockType)
                .putString(KEY_CREDENTIAL, hash(rawCredential))
                .putInt(KEY_CREDENTIAL_LEN, rawCredential.length())
                .apply();
        sessionUnlocked = true; // User just set it up, don't lock them out
    }

    public void disableLock() {
        prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .putInt(KEY_LOCK_TYPE, LOCK_NONE)
                .remove(KEY_CREDENTIAL)
                .remove(KEY_CREDENTIAL_LEN)
                .apply();
        sessionUnlocked = true;
    }

    /** Get the length of the stored credential (for PIN auto-verify at exact length). Returns -1 if unknown. */
    public int getCredentialLength() {
        return prefs.getInt(KEY_CREDENTIAL_LEN, -1);
    }

    /** Verify a raw credential against the stored hash. */
    public boolean verify(String rawCredential) {
        String stored = prefs.getString(KEY_CREDENTIAL, "");
        if (stored.isEmpty()) return false;
        return stored.equals(hash(rawCredential));
    }

    public boolean isSessionUnlocked() {
        return sessionUnlocked;
    }

    public void setSessionUnlocked(boolean unlocked) {
        this.sessionUnlocked = unlocked;
    }

    /** Call when app goes to background to re-lock. */
    public void lockSession() {
        sessionUnlocked = false;
    }

    /** Whether the lock screen should be shown right now. */
    public boolean shouldShowLockScreen() {
        return isLockEnabled() && !sessionUnlocked;
    }

    // ── Hashing ──────────────────────────────────────────────────────────────

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input; // fallback (should never happen)
        }
    }
}
