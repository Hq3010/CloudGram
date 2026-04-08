/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveRepository;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

public class DriveFilePreviewActivity extends BaseFragment {

    private DriveFile file;
    private ImageView imageView;
    private ProgressBar progressBar;
    private TextView errorView;

    public static DriveFilePreviewActivity create(int account, DriveFile file) {
        DriveFilePreviewActivity f = new DriveFilePreviewActivity();
        f.setCurrentAccount(account);
        f.file = file;
        return f;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle(file != null ? file.name : "Preview");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(0xFF000000);

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);
        frame.addView(imageView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressBar = new ProgressBar(context);
        frame.addView(progressBar, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setTextColor(0xFFFFFFFF);
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        frame.addView(errorView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        loadPreview();
        return fragmentView;
    }

    private void loadPreview() {
        if (file == null) return;
        String cachedPath = DriveRepository.getInstance(currentAccount).getDownloadPath(file);
        if (cachedPath != null) {
            showImage(cachedPath);
            return;
        }
        DriveRepository.getInstance(currentAccount).downloadFile(file,
                new DriveRepository.DownloadCallback() {
                    @Override public void onProgress(float p) { }
                    @Override public void onSuccess(java.io.File localFile) {
                        showImage(localFile.getAbsolutePath());
                    }
                    @Override public void onError(String msg) {
                        if (errorView != null) {
                            errorView.setText("Failed to load: " + msg);
                            errorView.setVisibility(View.VISIBLE);
                        }
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    }
                });
    }

    private void showImage(String path) {
        if (imageView == null) return;
        android.graphics.Bitmap bm = BitmapFactory.decodeFile(path);
        if (bm != null) {
            imageView.setImageBitmap(bm);
            imageView.setVisibility(View.VISIBLE);
        } else {
            if (errorView != null) {
                errorView.setText("Cannot display this file");
                errorView.setVisibility(View.VISIBLE);
            }
        }
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }
}
