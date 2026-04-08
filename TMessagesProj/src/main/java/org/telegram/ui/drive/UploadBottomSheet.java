/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

public class UploadBottomSheet {

    public static void show(Context context, BaseFragment parentFragment, String targetFolderId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Upload to TeleDrive");
        builder.setItems(new CharSequence[]{"Choose file from storage"}, (dialog, which) -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                parentFragment.getParentActivity()
                        .startActivityForResult(Intent.createChooser(intent, "Select file"), 3001);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        builder.show();
    }
}
