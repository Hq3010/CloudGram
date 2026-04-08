/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.drive.DriveRepository;
import java.util.Map;

public class DriveUploadQueueSheet extends Dialog
        implements DriveRepository.UploadProgressListener {

    private static final int TEXT_PRI  = 0xFF1F1F1F;
    private static final int TEXT_SEC  = 0xFF5F6368;
    private static final int ACCENT    = 0xFF1A73E8;
    private static final int BG        = 0xFFFFFFFF;
    private static final int BORDER    = 0xFFDADCE0;

    private final int mAccount;
    private LinearLayout mList;
    private TextView mEmpty;

    public DriveUploadQueueSheet(Context ctx, int account) {
        super(ctx, android.R.style.Theme_Material_Light_Dialog_Alert);
        mAccount = account;
        buildUI(ctx);
    }

    private void buildUI(Context ctx) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Header
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        TextView title = new TextView(ctx);
        title.setText("Uploading");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(TEXT_PRI);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(ctx);
        closeBtn.setText("✕");
        closeBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        closeBtn.setTextColor(TEXT_SEC);
        closeBtn.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4),
                AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        closeBtn.setOnClickListener(v -> dismiss());
        header.addView(closeBtn);
        root.addView(header);

        // Divider
        View div = new View(ctx);
        div.setBackgroundColor(BORDER);
        root.addView(div, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));

        // Empty state
        mEmpty = new TextView(ctx);
        mEmpty.setText("No files uploading");
        mEmpty.setTextColor(TEXT_SEC);
        mEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24),
                AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        root.addView(mEmpty);

        // List
        ScrollView scroll = new ScrollView(ctx);
        mList = new LinearLayout(ctx);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        scroll.addView(mList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        Window w = getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getDecorView().setBackground(null);
            // Rounded top corners
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(BG);
            bg.setCornerRadii(new float[]{
                    AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                    0, 0, 0, 0});
            root.setBackground(bg);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        DriveRepository.getInstance(mAccount).addUploadListener(this);
        rebuildList();
    }

    @Override
    protected void onStop() {
        super.onStop();
        DriveRepository.getInstance(mAccount).removeUploadListener(this);
    }

    // ── UploadProgressListener ───────────────────────────────────────────

    @Override
    public void onUploadStarted(String fileName) {
        AndroidUtilities.runOnUIThread(this::rebuildList);
    }

    @Override
    public void onUploadProgress(String fileName, float progress) {
        AndroidUtilities.runOnUIThread(() -> updateProgress(fileName, progress));
    }

    @Override
    public void onUploadFinished(String fileName, boolean success) {
        AndroidUtilities.runOnUIThread(this::rebuildList);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void rebuildList() {
        Map<String, Float> uploads = DriveRepository.getInstance(mAccount).getActiveUploads();
        mList.removeAllViews();
        if (uploads.isEmpty()) {
            mEmpty.setVisibility(View.VISIBLE);
            mList.setVisibility(View.GONE);
        } else {
            mEmpty.setVisibility(View.GONE);
            mList.setVisibility(View.VISIBLE);
            Context ctx = mList.getContext();
            for (Map.Entry<String, Float> e : uploads.entrySet()) {
                mList.addView(buildRow(ctx, e.getKey(), e.getValue()));
            }
        }
    }

    private void updateProgress(String fileName, float progress) {
        for (int i = 0; i < mList.getChildCount(); i++) {
            View child = mList.getChildAt(i);
            if (child.getTag() instanceof String &&
                    child.getTag().equals(fileName)) {
                ProgressBar bar = child.findViewWithTag("bar_" + fileName);
                TextView pct = child.findViewWithTag("pct_" + fileName);
                if (bar != null) bar.setProgress(Math.round(progress * 100));
                if (pct != null) {
                    if (progress >= 1.0f) {
                        pct.setText("Completed ✓");
                        pct.setTextColor(0xFF34A853);
                    } else {
                        pct.setText(Math.round(progress * 100) + "%");
                        pct.setTextColor(ACCENT);
                    }
                }
                return;
            }
        }
        // Row not found - rebuild
        rebuildList();
    }

    private View buildRow(Context ctx, String fileName, float progress) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag(fileName);
        row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(8),
                AndroidUtilities.dp(4), AndroidUtilities.dp(8));

        // File name + percentage
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, AndroidUtilities.dp(6));

        // File icon
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setImageResource(R.drawable.ic_upload_queue);
        icon.setColorFilter(ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        icon.setPadding(0, 0, AndroidUtilities.dp(10), 0);
        top.addView(icon, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(24), AndroidUtilities.dp(24)));

        TextView nameView = new TextView(ctx);
        nameView.setText(fileName);
        nameView.setTextColor(TEXT_PRI);
        nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(nameView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(ctx);
        pct.setTag("pct_" + fileName);
        if (progress >= 1.0f) {
            pct.setText("Completed ✓");
            pct.setTextColor(0xFF34A853);
        } else {
            pct.setText(Math.round(progress * 100) + "%");
            pct.setTextColor(ACCENT);
        }
        pct.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        pct.setPadding(AndroidUtilities.dp(8), 0, 0, 0);
        top.addView(pct);

        row.addView(top);

        // Progress bar
        ProgressBar bar = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setTag("bar_" + fileName);
        bar.setMax(100);
        bar.setProgress(Math.round(progress * 100));
        android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable)
                bar.getProgressDrawable();
        if (ld != null) {
            android.graphics.drawable.Drawable prog = ld.findDrawableByLayerId(android.R.id.progress);
            if (prog instanceof android.graphics.drawable.ClipDrawable) {
                android.graphics.drawable.ClipDrawable clip = (android.graphics.drawable.ClipDrawable) prog;
                android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
                fill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                fill.setCornerRadius(AndroidUtilities.dp(2));
                fill.setColor(ACCENT);
                clip.setDrawable(fill);
            }
            android.graphics.drawable.Drawable bg2 = ld.findDrawableByLayerId(android.R.id.background);
            if (bg2 != null) {
                android.graphics.drawable.GradientDrawable bgFill = new android.graphics.drawable.GradientDrawable();
                bgFill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bgFill.setCornerRadius(AndroidUtilities.dp(2));
                bgFill.setColor(BORDER);
                ld.setDrawableByLayerId(android.R.id.background, bgFill);
            }
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(4));
        row.addView(bar, barLp);

        return row;
    }
}
