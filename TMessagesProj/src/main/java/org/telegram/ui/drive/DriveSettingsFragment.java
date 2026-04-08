/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.List;

public class DriveSettingsFragment extends BaseFragment {

    // Same palette as Home tab
    private static final int BG_PAGE    = 0xFFF8FAFD;
    private static final int BG_SURFACE = 0xFFFFFFFF;
    private static final int ACCENT     = 0xFF1A73E8;
    private static final int TEXT_PRI   = 0xFF1F1F1F;
    private static final int TEXT_SEC   = 0xFF5F6368;
    private static final int BORDER     = 0xFFDADCE0;

    @Override
    public View createView(Context context) {
        // White ActionBar like Home tab
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Settings");
        actionBar.setTitleColor(TEXT_PRI);
        actionBar.setBackgroundColor(BG_SURFACE);
        actionBar.setItemsColor(TEXT_SEC, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(BG_PAGE);

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(BG_PAGE);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        // Offset content below ActionBar (ActionBar overlays fragmentView from y=0)
        int topOffset = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8);
        page.setPadding(0, topOffset, 0, AndroidUtilities.dp(24));

        // ── Profile card ──────────────────────────────────────────────────────
        TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        String name  = user != null ? UserObject.getUserName(user) : "Unknown";
        String phone = (user != null && user.phone != null) ? "+" + user.phone : "";

        LinearLayout profileCard = new LinearLayout(context);
        profileCard.setOrientation(LinearLayout.HORIZONTAL);
        profileCard.setGravity(Gravity.CENTER_VERTICAL);
        setCardStyle(profileCard);
        profileCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(18),
                AndroidUtilities.dp(16), AndroidUtilities.dp(18));

        // Real Telegram profile photo with initials fallback
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        BackupImageView avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(26)); // 52dp circle
        if (user != null) {
            avatarView.setForUserOrChat(user, avatarDrawable);
        } else {
            avatarView.setImageDrawable(avatarDrawable);
        }
        profileCard.addView(avatarView, LayoutHelper.createLinear(52, 52, Gravity.CENTER_VERTICAL));

        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(AndroidUtilities.dp(16), 0, 0, 0);

        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameTv.setTypeface(nameTv.getTypeface(), Typeface.BOLD);
        nameTv.setTextColor(TEXT_PRI);
        infoCol.addView(nameTv);

        TextView phoneTv = new TextView(context);
        phoneTv.setText(phone);
        phoneTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        phoneTv.setTextColor(TEXT_SEC);
        phoneTv.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        infoCol.addView(phoneTv);

        profileCard.addView(infoCol, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        addCard(page, profileCard);

        // ── Storage ───────────────────────────────────────────────────────────
        addSectionLabel(context, page, "TELEGRAM STORAGE");
        LinearLayout storageCard = new LinearLayout(context);
        storageCard.setOrientation(LinearLayout.HORIZONTAL);
        storageCard.setGravity(Gravity.CENTER_VERTICAL);
        setCardStyle(storageCard);
        storageCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));

        storageCard.addView(makeIconCircle(context, R.drawable.drive_ic_files, 0xFFFA7B17),
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        LinearLayout stCol = new LinearLayout(context);
        stCol.setOrientation(LinearLayout.VERTICAL);
        stCol.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        TextView stTitle = makeTitle(context, "Telegram Cloud Data");
        final TextView stVal = makeSub(context, "Calculating…");
        stCol.addView(stTitle);
        stCol.addView(stVal);
        storageCard.addView(stCol, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));
        addCard(page, storageCard);

        new Thread(() -> {
            List<DriveFile> all = DriveStorage.getInstance(currentAccount).getAllFiles();
            // Count only files that actually exist (have a valid docId)
            long bytes = 0;
            int cnt = 0;
            for (DriveFile f : all) {
                if (f.docId != 0) { bytes += f.size; cnt++; }
            }
            final String sz = android.text.format.Formatter.formatFileSize(context, bytes);
            final int finalCnt = cnt;
            AndroidUtilities.runOnUIThread(() ->
                    stVal.setText(sz + " on cloud  ·  " + finalCnt + " files"));
        }).start();

        // ── Cache ─────────────────────────────────────────────────────────────
        addSectionLabel(context, page, "CACHE");
        LinearLayout cacheCard = new LinearLayout(context);
        cacheCard.setOrientation(LinearLayout.VERTICAL);
        setCardStyle(cacheCard);

        // Cache size row
        LinearLayout cacheSizeRow = new LinearLayout(context);
        cacheSizeRow.setOrientation(LinearLayout.HORIZONTAL);
        cacheSizeRow.setGravity(Gravity.CENTER_VERTICAL);
        cacheSizeRow.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        cacheSizeRow.addView(makeIconCircle(context, R.drawable.msg_stats, 0xFF5F6368),
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        LinearLayout cacheCol = new LinearLayout(context);
        cacheCol.setOrientation(LinearLayout.VERTICAL);
        cacheCol.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        cacheCol.addView(makeTitle(context, "Cache Size"));
        final TextView cacheVal = makeSub(context, "Calculating…");
        cacheCol.addView(cacheVal);
        cacheSizeRow.addView(cacheCol, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));
        cacheCard.addView(cacheSizeRow, new LinearLayout.LayoutParams(-1, -2));

        addDividerInCard(context, cacheCard);

        // Clear cache row
        addClickableRow(context, cacheCard, R.drawable.msg_clearcache, 0xFFEA4335,
                "Clear Cache", "", () -> confirmClearCache(context, cacheVal));
        addCard(page, cacheCard);

        // Async: calculate cache size
        new Thread(() -> {
            long bytes = calcCacheSize(context);
            final String sz = android.text.format.Formatter.formatFileSize(context, bytes);
            AndroidUtilities.runOnUIThread(() -> cacheVal.setText(sz));
        }).start();

        // ── Privacy ───────────────────────────────────────────────────────────
        addSectionLabel(context, page, "PRIVACY");
        LinearLayout privacyCard = new LinearLayout(context);
        privacyCard.setOrientation(LinearLayout.VERTICAL);
        setCardStyle(privacyCard);

        // App Lock toggle row
        final DriveAppLockManager lockMgr = DriveAppLockManager.getInstance(context);
        LinearLayout lockToggleRow = new LinearLayout(context);
        lockToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        lockToggleRow.setGravity(Gravity.CENTER_VERTICAL);
        lockToggleRow.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));

        lockToggleRow.addView(makeIconCircle(context, R.drawable.ic_lock_header, 0xFF7B1FA2),
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        LinearLayout lockCol = new LinearLayout(context);
        lockCol.setOrientation(LinearLayout.VERTICAL);
        lockCol.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        lockCol.addView(makeTitle(context, "App Lock"));
        final TextView lockStatus = makeSub(context,
                lockMgr.isLockEnabled() ? "On — " + lockMgr.getLockTypeLabel() : "Off");
        lockCol.addView(lockStatus);
        lockToggleRow.addView(lockCol, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));

        // Switch
        final android.widget.Switch lockSwitch = new android.widget.Switch(context);
        lockSwitch.setChecked(lockMgr.isLockEnabled());
        lockToggleRow.addView(lockSwitch, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        privacyCard.addView(lockToggleRow, new LinearLayout.LayoutParams(-1, -2));

        addDividerInCard(context, privacyCard);

        // Lock type chooser row
        addClickableRow(context, privacyCard, R.drawable.msg_secret, ACCENT,
                "Lock Type", lockMgr.isLockEnabled() ? lockMgr.getLockTypeLabel() : "Not set", () -> {
                    showLockTypeChooser(context, lockStatus, lockSwitch);
                });

        addCard(page, privacyCard);

        lockSwitch.setOnCheckedChangeListener((btn, isOn) -> {
            if (isOn) {
                if (!lockMgr.isLockEnabled()) {
                    showLockTypeChooser(context, lockStatus, lockSwitch);
                }
            } else {
                lockMgr.disableLock();
                lockStatus.setText("Off");
            }
        });

        // ── About ─────────────────────────────────────────────────────────────
        addSectionLabel(context, page, "ABOUT");
        LinearLayout aboutCard = new LinearLayout(context);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        setCardStyle(aboutCard);
        addInfoRow(context, aboutCard, R.drawable.ic_cloud_outline, ACCENT,
                "App Name", "CloudGram", true);
        addDividerInCard(context, aboutCard);
        addInfoRow(context, aboutCard, R.drawable.settings_faq, 0xFF757575,
                "Version", "1.0", false);
        addDividerInCard(context, aboutCard);
        addInfoRow(context, aboutCard, R.drawable.menu_invit_telegram, 0xFF229ED9,
                "Telegram", "@QuanQuan49", true);
        addCard(page, aboutCard);

        // ── Developer ──────────────────────────────────────────────────────────
        addSectionLabel(context, page, "DEVELOPER");
        LinearLayout devCard = new LinearLayout(context);
        devCard.setOrientation(LinearLayout.VERTICAL);
        setCardStyle(devCard);
        addClickableRow(context, devCard, R.drawable.msg_instant_link, 0xFF24292E,
                "GitHub", "@Hq3010", () -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Hq3010"));
                        context.startActivity(intent);
                    } catch (Exception ignored) {}
                });
        addCard(page, devCard);

        // ── Logout ────────────────────────────────────────────────────────────
        addSectionLabel(context, page, "");
        LinearLayout logoutCard = new LinearLayout(context);
        logoutCard.setGravity(Gravity.CENTER);
        setCardStyle(logoutCard);
        logoutCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        TextView logoutTv = new TextView(context);
        logoutTv.setText("Log Out");
        logoutTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        logoutTv.setTextColor(0xFFE53935);
        logoutTv.setGravity(Gravity.CENTER);
        logoutCard.addView(logoutTv, LayoutHelper.createLinear(-1, -2, Gravity.CENTER));
        logoutCard.setOnClickListener(v -> confirmLogout(context));
        addCard(page, logoutCard);

        scroll.addView(page, LayoutHelper.createFrame(-1, -2));
        ((FrameLayout) fragmentView).addView(scroll, LayoutHelper.createFrame(-1, -1));
        return fragmentView;
    }

    private void confirmClearCache(Context ctx, TextView cacheVal) {
        long bytes = calcCacheSize(ctx);
        String sz = android.text.format.Formatter.formatFileSize(ctx, bytes);
        new AlertDialog.Builder(ctx)
                .setTitle("Clear Cache")
                .setMessage("Clear " + sz + " cache?\n(Downloaded files will be removed, Telegram cloud data is not affected)")
                .setPositiveButton("Clear", (d, w) -> {
                    new Thread(() -> {
                        doClearCache(ctx);
                        long after = calcCacheSize(ctx);
                        final String newSz = android.text.format.Formatter.formatFileSize(ctx, after);
                        AndroidUtilities.runOnUIThread(() -> {
                            cacheVal.setText(newSz);
                            Toast.makeText(ctx, "Cache cleared (" + sz + ")", Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Returns total bytes across all app cache directories. */
    private static long calcCacheSize(Context ctx) {
        long total = 0;
        total += dirSize(ctx.getCacheDir());
        total += dirSize(ctx.getExternalCacheDir());
        total += dirSize(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE));
        total += dirSize(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT));
        return total;
    }

    /** Deletes all files in the app's cache directories. */
    private static void doClearCache(Context ctx) {
        deleteDir(ctx.getCacheDir());
        deleteDir(ctx.getExternalCacheDir());
        deleteDir(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE));
        deleteDir(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT));
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) size += dirSize(f);
            else size += f.length();
        }
        return size;
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }

    private void showLockTypeChooser(Context ctx, TextView lockStatus, android.widget.Switch lockSwitch) {
        // Build custom dialog matching app style
        android.app.Dialog dialog = new android.app.Dialog(ctx);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20),
                AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(20));
        bg.setColor(0xFFFFFFFF);
        root.setBackground(bg);

        // Title
        TextView title = new TextView(ctx);
        title.setText("Choose Lock Type");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT_PRI);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // Spacer
        View spacer = new View(ctx);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(16)));

        String[] types = {"PIN", "Password", "Pattern"};
        int[] lockTypes = {DriveAppLockManager.LOCK_PIN, DriveAppLockManager.LOCK_PASSWORD, DriveAppLockManager.LOCK_PATTERN};
        int[] icons = {R.drawable.msg_permissions, R.drawable.msg_secret, R.drawable.msg_qrcode};
        int[] colors = {0xFF1A73E8, 0xFF7B1FA2, 0xFF00897B};

        for (int i = 0; i < types.length; i++) {
            final int lockType = lockTypes[i];
            final String typeName = types[i];

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(12));

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(AndroidUtilities.dp(12));
            rowBg.setColor(0xFFF8FAFD);
            row.setBackground(rowBg);

            row.addView(makeIconCircle(ctx, icons[i], colors[i]),
                    LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            TextView label = new TextView(ctx);
            label.setText(typeName);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(TEXT_PRI);
            label.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
            row.addView(label, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));

            ImageView arrow = new ImageView(ctx);
            arrow.setImageResource(R.drawable.msg_arrowright);
            arrow.setColorFilter(BORDER, android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(arrow, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                new DriveAppLockSetupSheet(ctx, lockType, () -> {
                    lockStatus.setText("On — " + typeName);
                    lockSwitch.setChecked(true);
                }).show();
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, 0, 0, AndroidUtilities.dp(6));
            root.addView(row, rowLp);
        }

        // Cancel button
        View spacer2 = new View(ctx);
        root.addView(spacer2, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(8)));

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancelBtn.setTextColor(TEXT_SEC);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(4));
        cancelBtn.setOnClickListener(v -> {
            dialog.dismiss();
            // If lock was not set, turn off switch
            DriveAppLockManager mgr = DriveAppLockManager.getInstance(ctx);
            if (!mgr.isLockEnabled()) {
                lockSwitch.setChecked(false);
                lockStatus.setText("Off");
            }
        });
        root.addView(cancelBtn, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(root);
        dialog.setOnCancelListener(d -> {
            DriveAppLockManager mgr = DriveAppLockManager.getInstance(ctx);
            if (!mgr.isLockEnabled()) {
                lockSwitch.setChecked(false);
                lockStatus.setText("Off");
            }
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    AndroidUtilities.dp(300),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void confirmLogout(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of CloudGram?")
                .setPositiveButton("Log Out", (d, w) ->
                        MessagesController.getInstance(currentAccount).performLogout(1))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setCardStyle(LinearLayout card) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE);
        bg.setCornerRadius(AndroidUtilities.dp(14));
        bg.setStroke(AndroidUtilities.dp(1), BORDER);
        card.setBackground(bg);
    }

    private FrameLayout makeIconCircle(Context ctx, int iconRes, int color) {
        FrameLayout frame = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        frame.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        iv.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9),
                AndroidUtilities.dp(9), AndroidUtilities.dp(9));
        frame.addView(iv, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(40), AndroidUtilities.dp(40)));
        return frame;
    }

    private void addInfoRow(Context ctx, LinearLayout parent,
                            int iconRes, int iconColor,
                            String label, String value, boolean pad) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                AndroidUtilities.dp(16), AndroidUtilities.dp(13));

        row.addView(makeIconCircle(ctx, iconRes, iconColor),
                LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL));

        TextView labelTv = makeTitle(ctx, label);
        labelTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        row.addView(labelTv, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));

        TextView valueTv = makeSub(ctx, value);
        row.addView(valueTv, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addClickableRow(Context ctx, LinearLayout parent,
                                 int iconRes, int iconColor,
                                 String label, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                AndroidUtilities.dp(16), AndroidUtilities.dp(13));

        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(v -> onClick.run());

        row.addView(makeIconCircle(ctx, iconRes, iconColor),
                LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL));

        TextView labelTv = makeTitle(ctx, label);
        labelTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        row.addView(labelTv, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));

        TextView valueTv = makeSub(ctx, value);
        valueTv.setTextColor(ACCENT);
        row.addView(valueTv, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        // Right arrow
        ImageView arrow = new ImageView(ctx);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(BORDER, PorterDuff.Mode.SRC_IN);
        arrow.setPadding(AndroidUtilities.dp(4), 0, 0, 0);
        row.addView(arrow, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addDividerInCard(Context ctx, LinearLayout parent) {
        View div = new View(ctx);
        div.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(1));
        lp.setMargins(AndroidUtilities.dp(68), 0, 0, 0);
        parent.addView(div, lp);
    }

    private void addCard(LinearLayout page, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(AndroidUtilities.dp(16), 0,
                AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        page.addView(card, lp);
    }

    private void addSectionLabel(Context ctx, LinearLayout parent, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setTextColor(ACCENT);
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        parent.addView(tv, lp);
    }

    private TextView makeTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(TEXT_PRI);
        return tv;
    }

    private TextView makeSub(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(TEXT_SEC);
        tv.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        return tv;
    }
}
