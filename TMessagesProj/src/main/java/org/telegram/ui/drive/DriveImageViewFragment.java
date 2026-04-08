/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Full-screen in-app image viewer with pinch-to-zoom and drag-to-pan.
 */
public class DriveImageViewFragment extends BaseFragment {

    private static final float MAX_SCALE = 8f;

    private String filePath;
    private String fileName;

    private ImageView imageView;
    private ProgressBar progressBar;
    private TextView errorView;

    private final Matrix matrix = new Matrix();
    private final Matrix savedMatrix = new Matrix();
    private float minScale = 1f;

    private static final int NONE = 0, DRAG = 1, ZOOM = 2;
    private int mode = NONE;
    private final PointF startPoint = new PointF();

    public static DriveImageViewFragment create(String filePath, String fileName) {
        DriveImageViewFragment f = new DriveImageViewFragment();
        f.filePath = filePath;
        f.fileName = fileName != null ? fileName : "Image";
        return f;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle(fileName);
        actionBar.setBackgroundColor(0xFF000000);
        actionBar.setTitleColor(0xFFFFFFFF);
        actionBar.setItemsBackgroundColor(0x44FFFFFF, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0xFF000000);
        fragmentView = frame;

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        frame.addView(imageView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressBar = new ProgressBar(context);
        frame.addView(progressBar, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        errorView = new TextView(context);
        errorView.setTextColor(0xFFFFFFFF);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        errorView.setVisibility(View.GONE);
        frame.addView(errorView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        setupGestures(imageView);
        loadImage();
        return fragmentView;
    }

    private void loadImage() {
        if (filePath == null) { showError("File not found"); return; }
        new Thread(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(filePath, opts);
                int maxDim = 2048;
                int sample = 1;
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeFile(filePath, opts);
                if (bm != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (imageView == null) return;
                        imageView.setImageBitmap(bm);
                        imageView.post(() -> initMatrix(bm));
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                    });
                } else {
                    showError("Cannot decode this image");
                }
            } catch (Exception e) {
                FileLog.e(e);
                showError("Error: " + e.getMessage());
            }
        }).start();
    }

    private void initMatrix(Bitmap bm) {
        if (imageView == null || bm == null) return;
        float vw = imageView.getWidth(), vh = imageView.getHeight();
        if (vw == 0 || vh == 0) return;
        float scale = Math.min(vw / bm.getWidth(), vh / bm.getHeight());
        minScale = scale;
        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate((vw - bm.getWidth() * scale) / 2f, (vh - bm.getHeight() * scale) / 2f);
        imageView.setImageMatrix(matrix);
    }

    private void showError(String msg) {
        AndroidUtilities.runOnUIThread(() -> {
            if (errorView != null) { errorView.setText(msg); errorView.setVisibility(View.VISIBLE); }
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        });
    }

    private void setupGestures(ImageView iv) {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(
                iv.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float[] v = new float[9];
                matrix.getValues(v);
                float cur = v[Matrix.MSCALE_X];
                float factor = d.getScaleFactor();
                float newScale = cur * factor;
                if (newScale < minScale) factor = minScale / cur;
                else if (newScale > MAX_SCALE) factor = MAX_SCALE / cur;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                iv.setImageMatrix(matrix);
                return true;
            }
        });

        GestureDetector tapDetector = new GestureDetector(iv.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float[] v = new float[9];
                matrix.getValues(v);
                float cur = v[Matrix.MSCALE_X];
                float target = (cur > minScale * 1.5f) ? minScale : minScale * 3f;
                float factor = target / cur;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                iv.setImageMatrix(matrix);
                return true;
            }
        });

        iv.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    mode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mode = ZOOM;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && !scaleDetector.isInProgress()) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
                        iv.setImageMatrix(matrix);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
            }
            return true;
        });
    }
}
