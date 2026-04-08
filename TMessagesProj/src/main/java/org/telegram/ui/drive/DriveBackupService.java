/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveFolder;
import org.telegram.messenger.drive.DriveRepository;
import org.telegram.messenger.drive.DriveStorage;

import java.io.File;
import java.util.List;

public class DriveBackupService extends Service {

    private static final String CHANNEL_ID = "teledrive_backup";
    private static final int NOTIF_ID = 8001;
    private static final String PREF_LAST_BACKUP = "drive_last_backup_date_";

    private int account;
    private ContentObserver mediaObserver;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            account = intent.getIntExtra("account", 0);
        }
        startForeground(NOTIF_ID, buildNotification("TeleDrive Backup", "Watching for new photos…"));
        startObserving();
        return START_STICKY;
    }

    private void startObserving() {
        if (mediaObserver != null) return;
        mediaObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onNewMedia(uri);
            }
        };
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
        getContentResolver().registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
    }

    private void onNewMedia(Uri uri) {
        new Thread(() -> {
            try {
                String[] projection = {
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_ADDED
                };
                android.database.Cursor cursor = getContentResolver().query(
                        uri, projection, null, null,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC LIMIT 1");
                if (cursor != null && cursor.moveToFirst()) {
                    String path = cursor.getString(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                    String name = cursor.getString(
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                    cursor.close();
                    if (path != null && new File(path).exists()) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                                getApplicationContext());
                        String lastBackup = prefs.getString(PREF_LAST_BACKUP + account, "");
                        if (!lastBackup.equals(path)) {
                            prefs.edit().putString(PREF_LAST_BACKUP + account, path).apply();
                            String folderId = getOrCreateBackupFolder();
                            DriveRepository.getInstance(account).uploadFile(DriveBackupService.this, android.net.Uri.fromFile(new File(path)), folderId,
                                    new DriveRepository.UploadCallback() {
                                        @Override public void onProgress(float p) { }
                                        @Override public void onSuccess(DriveFile file) {
                                            updateNotification("TeleDrive Backup",
                                                    "Backed up: " + name);
                                        }
                                        @Override public void onError(String msg) {
                                            FileLog.e("BackupService upload error: " + msg);
                                        }
                                    });
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }).start();
    }

    private String getOrCreateBackupFolder() {
        List<DriveFolder> folders = DriveStorage.getInstance(account)
                .getFoldersInParent(DriveFolder.ROOT_ID);
        for (DriveFolder f : folders) {
            if ("Camera Backup".equals(f.name)) return f.id;
        }
        return DriveFolder.ROOT_ID;
    }

    private Notification buildNotification(String title, String text) {
        createNotificationChannel();
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        return builder.build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(title, text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TeleDrive Backup", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Auto backup photos and videos");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (mediaObserver != null) {
            getContentResolver().unregisterContentObserver(mediaObserver);
            mediaObserver = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
