/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveFolder;
import org.telegram.messenger.drive.DriveRepository;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriveHomeFragment extends BaseFragment
        implements DriveRepository.DriveChangeListener {

    private static final int REQUEST_UPLOAD = 3001;
    private static final int SEARCH_BTN = 1;
    private static final int UPLOAD_QUEUE_BTN = 99;
    private static final int PRIMARY_COLOR = 0xFF1A73E8;

    // MD3 design tokens
    private static final int BG_PAGE          = 0xFFF8FAFD;
    private static final int BG_SURFACE       = 0xFFFFFFFF;
    private static final int ACCENT           = 0xFF1A73E8;
    private static final int TEXT_PRIMARY_C   = 0xFF1F1F1F;
    private static final int TEXT_SECONDARY_C = 0xFF5F6368;
    private static final int BORDER_C         = 0xFFDADCE0;

    private LinearLayout recentContainer;
    private TextView recentEmpty;

    private final List<DriveFile> recentFiles = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        DriveRepository.getInstance(currentAccount).addChangeListener(this);
        // Always fetch fresh data from Telegram first, then show local DB results
        DriveRepository.getInstance(currentAccount).syncFilesWithTelegram(null);
        loadData();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        DriveRepository.getInstance(currentAccount).removeChangeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onFilesChanged() {
        loadData();
    }

    private void loadData() {
        DriveRepository repo = DriveRepository.getInstance(currentAccount);
        repo.getRecentFiles(20, files -> {
            recentFiles.clear();
            recentFiles.addAll(files);
            updateRecentUI();
        });
    }

    @Override
    public View createView(Context context) {
        // White ActionBar with dark title
        android.graphics.drawable.Drawable cloudIcon =
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_cloud_outline);
        if (cloudIcon != null) {
            cloudIcon.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        }
        actionBar.setTitle("CloudGram", cloudIcon);
        actionBar.setTitleColor(0xFF1F1F1F);
        actionBar.setBackgroundColor(0xFFFFFFFF);
        actionBar.setItemsColor(0xFF5F6368, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == UPLOAD_QUEUE_BTN) {
                    DriveUploadQueueSheet sheet = new DriveUploadQueueSheet(getParentActivity(), currentAccount);
                    sheet.show();
                } else if (id == SEARCH_BTN) {
                    presentFragment(new DriveSearchActivity());
                }
            }
        });
        // Upload queue icon + search icon
        org.telegram.ui.ActionBar.ActionBarMenu menu = actionBar.createMenu();
        org.telegram.ui.ActionBar.ActionBarMenuItem queueItem = menu.addItem(UPLOAD_QUEUE_BTN, R.drawable.ic_upload_queue);
        queueItem.setIconColor(ACCENT);
        menu.addItem(SEARCH_BTN, R.drawable.outline_header_search);

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(BG_PAGE);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BG_PAGE);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(80));

        // ── Search bar (pill outline) ─────────────────────────────────────────
        LinearLayout searchBar = new LinearLayout(context);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        searchBar.setBackground(pillOutlineBg(BG_SURFACE, BORDER_C));
        searchBar.setMinimumHeight(AndroidUtilities.dp(48));
        searchBar.setOnClickListener(v -> presentFragment(new DriveSearchActivity()));

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
        searchBar.addView(searchIcon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));

        TextView searchHint = new TextView(context);
        searchHint.setText("Search in Drive");
        searchHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        searchHint.setTextColor(TEXT_SECONDARY_C);
        searchHint.setPadding(AndroidUtilities.dp(10), 0, 0, 0);
        searchBar.addView(searchHint, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48));
        searchLp.setMargins(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), 0);
        content.addView(searchBar, searchLp);

        // ── All Files section (2-column grid) ────────────────────────────────
        TextView recentHeader = new TextView(context);
        recentHeader.setText("All Files");
        recentHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        recentHeader.setTypeface(recentHeader.getTypeface(), Typeface.BOLD);
        recentHeader.setTextColor(TEXT_PRIMARY_C);
        recentHeader.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32),
                AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        content.addView(recentHeader, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        recentContainer = new LinearLayout(context);
        recentContainer.setOrientation(LinearLayout.VERTICAL);
        recentContainer.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        content.addView(recentContainer, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        recentEmpty = new TextView(context);
        recentEmpty.setText("No recent files");
        recentEmpty.setTextColor(TEXT_SECONDARY_C);
        recentEmpty.setGravity(Gravity.CENTER);
        recentEmpty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32),
                AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        recentEmpty.setVisibility(View.GONE);
        content.addView(recentEmpty, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView.addView(content, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        frame.addView(scrollView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // ── FAB (pill) ────────────────────────────────────────────────────────
        TextView fab = new TextView(context);
        fab.setText("+  New");
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        fab.setTypeface(fab.getTypeface(), Typeface.BOLD);
        fab.setTextColor(0xFFFFFFFF);
        fab.setGravity(Gravity.CENTER);
        fab.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        fab.setBackground(pillBg(ACCENT));
        fab.setOnClickListener(v -> showFabMenu(context));
        frame.addView(fab, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.BOTTOM | Gravity.END, 0, 0, 16, 16));

        updateRecentUI();

        return fragmentView;
    }

    private static android.graphics.drawable.GradientDrawable cardBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(AndroidUtilities.dp(12));
        d.setStroke(AndroidUtilities.dp(1), 0xFFDADCE0);
        return d;
    }

    private static android.graphics.drawable.GradientDrawable pillBg(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(AndroidUtilities.dp(50));
        return d;
    }

    private static android.graphics.drawable.GradientDrawable pillOutlineBg(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(AndroidUtilities.dp(50));
        d.setStroke(AndroidUtilities.dp(1), stroke);
        return d;
    }

    private void showFabMenu(Context context) {
        android.app.Dialog sheet = new android.app.Dialog(context, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);

        // ── Root card (rounded top corners only) ─────────────────────────────
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable sheetBg = new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(0xFFFFFFFF);
        float[] radii = new float[]{
                AndroidUtilities.dp(20), AndroidUtilities.dp(20),
                AndroidUtilities.dp(20), AndroidUtilities.dp(20),
                0, 0, 0, 0
        };
        sheetBg.setCornerRadii(radii);
        root.setBackground(sheetBg);
        root.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));

        // ── Handle bar ────────────────────────────────────────────────────────
        android.view.View handle = new android.view.View(context);
        android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
        handleBg.setColor(0xFFDADCE0);
        handleBg.setCornerRadius(AndroidUtilities.dp(4));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(36), AndroidUtilities.dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.setMargins(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        root.addView(handle, handleLp);

        // ── Title ─────────────────────────────────────────────────────────────
        TextView titleTv = new TextView(context);
        titleTv.setText("Add New");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(0xFF1F1F1F);
        titleTv.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        root.addView(titleTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Divider ───────────────────────────────────────────────────────────
        android.view.View divider1 = new android.view.View(context);
        divider1.setBackgroundColor(0xFFF1F3F4);
        root.addView(divider1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));

        // ── Option: Upload file ───────────────────────────────────────────────
        LinearLayout uploadRow = buildMenuRow(context,
                R.drawable.msg_sendfile, 0xFF1A73E8,
                "Upload File", "Choose a file from your device");
        uploadRow.setOnClickListener(v -> {
            sheet.dismiss();
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                getParentActivity().startActivityForResult(
                        Intent.createChooser(intent, "Choose file to upload"), REQUEST_UPLOAD);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        root.addView(uploadRow);

        // ── Divider ───────────────────────────────────────────────────────────
        android.view.View divider2 = new android.view.View(context);
        divider2.setBackgroundColor(0xFFF1F3F4);
        LinearLayout.LayoutParams div2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1));
        div2Lp.setMargins(AndroidUtilities.dp(72), 0, 0, 0);
        root.addView(divider2, div2Lp);

        // ── Option: New folder ────────────────────────────────────────────────
        LinearLayout folderRow = buildMenuRow(context,
                R.drawable.settings_folders, 0xFF34A853,
                "New Folder", "Create a folder to organize files");
        folderRow.setOnClickListener(v -> {
            sheet.dismiss();
            showNewFolderDialog(context);
        });
        root.addView(folderRow);

        sheet.setContentView(root);
        android.view.Window w = sheet.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        sheet.show();
    }

    /** Builds a tappable menu row: icon circle + two-line text. */
    private LinearLayout buildMenuRow(Context context, int iconRes, int iconColor,
                                      String label, String sub) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14),
                AndroidUtilities.dp(20), AndroidUtilities.dp(14));

        // Ripple feedback
        android.util.TypedValue tv = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        // Icon circle
        FrameLayout circle = new FrameLayout(context);
        android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
        circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int lightColor = (iconColor & 0x00FFFFFF) | 0x1A000000; // ~10% alpha tint
        circleBg.setColor(lightColor);
        circle.setBackground(circleBg);

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        icon.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10),
                AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        circle.addView(icon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        row.addView(circle, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        // Text block
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(AndroidUtilities.dp(16), 0, 0, 0);

        TextView labelTv = new TextView(context);
        labelTv.setText(label);
        labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        labelTv.setTypeface(labelTv.getTypeface(), Typeface.BOLD);
        labelTv.setTextColor(0xFF1F1F1F);
        textCol.addView(labelTv);

        TextView subTv = new TextView(context);
        subTv.setText(sub);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subTv.setTextColor(0xFF5F6368);
        textCol.addView(subTv);

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        return row;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_UPLOAD && resultCode == android.app.Activity.RESULT_OK && data != null) {
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            // Multi-select: ClipData contains all selected files
            if (data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            int total = uris.size();
            String msg = total == 1 ? "Uploading 1 file…" : "Uploading " + total + " files…";
            Toast.makeText(getParentActivity(), msg, Toast.LENGTH_SHORT).show();
            for (Uri uri : uris) {
                DriveRepository.getInstance(currentAccount).uploadFile(
                        getParentActivity(), uri, DriveFolder.ROOT_ID,
                        new DriveRepository.UploadCallback() {
                            @Override public void onProgress(float p) { }
                            @Override public void onSuccess(DriveFile file) {
                                AndroidUtilities.runOnUIThread(() -> loadData());
                            }
                            @Override public void onError(String errMsg) {
                                AndroidUtilities.runOnUIThread(() ->
                                        Toast.makeText(getParentActivity(),
                                                "Error: " + errMsg, Toast.LENGTH_LONG).show());
                            }
                        });
            }
        }
    }

    private void showNewFolderDialog(Context context) {
        // Fully custom dialog — white card, no system chrome (immune to dark mode)
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);

        // ── Card container ────────────────────────────────────────────────────
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(AndroidUtilities.dp(20));
        card.setBackground(cardBg);
        card.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24),
                AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        // ── Icon + title row ──────────────────────────────────────────────────
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, AndroidUtilities.dp(18));

        FrameLayout iconCircle = new FrameLayout(context);
        android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
        circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circleBg.setColor(ACCENT);
        iconCircle.setBackground(circleBg);
        android.widget.ImageView folderIcon = new android.widget.ImageView(context);
        folderIcon.setImageResource(R.drawable.settings_folders);
        folderIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        folderIcon.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9),
                AndroidUtilities.dp(9), AndroidUtilities.dp(9));
        iconCircle.addView(folderIcon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        titleRow.addView(iconCircle, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        TextView titleTv = new TextView(context);
        titleTv.setText("New Folder");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(0xFF1F1F1F);
        titleTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        titleRow.addView(titleTv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ── Input field ───────────────────────────────────────────────────────
        android.widget.EditText nameEdit = new android.widget.EditText(context);
        nameEdit.setHint("Enter folder name…");
        nameEdit.setHintTextColor(0xFF9AA0A6);
        nameEdit.setTextColor(0xFF1F1F1F);
        nameEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(0xFFF0F4FF);
        inputBg.setCornerRadius(AndroidUtilities.dp(12));
        inputBg.setStroke(AndroidUtilities.dp(2), ACCENT);
        nameEdit.setBackground(inputBg);
        nameEdit.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        nameEdit.setSingleLine();
        card.addView(nameEdit, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        // ── Button row ────────────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView cancelBtn = new TextView(context);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancelBtn.setTextColor(0xFF5F6368);
        cancelBtn.setTypeface(cancelBtn.getTypeface(), Typeface.BOLD);
        cancelBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        TextView createBtn = new TextView(context);
        createBtn.setText("Create");
        createBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        createBtn.setTextColor(ACCENT);
        createBtn.setTypeface(createBtn.getTypeface(), Typeface.BOLD);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(0xFFE8F0FE);
        btnBg.setCornerRadius(AndroidUtilities.dp(10));
        createBtn.setBackground(btnBg);
        createBtn.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        createBtn.setOnClickListener(v -> {
            String name = nameEdit.getText().toString().trim();
            if (!name.isEmpty()) {
                DriveRepository.getInstance(currentAccount).createFolder(name, DriveFolder.ROOT_ID,
                        new DriveRepository.SimpleCallback() {
                            @Override public void onSuccess() {
                                loadData();
                            }
                            @Override public void onError(String msg) { /* ignore */ }
                        });
                dialog.dismiss();
            }
        });
        btnRow.addView(createBtn);

        card.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ── Wrap card in outer frame with side margins ─────────────────────
        FrameLayout outer = new FrameLayout(context);
        outer.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        outer.addView(card, cardLp);

        dialog.setContentView(outer);
        // Transparent window background + correct width
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        nameEdit.requestFocus();
        dialog.show();
    }

    private void updateRecentUI() {
        if (recentContainer == null) return;
        recentContainer.removeAllViews();
        if (recentFiles.isEmpty()) {
            recentEmpty.setVisibility(View.VISIBLE);
            recentContainer.setVisibility(View.GONE);
        } else {
            recentEmpty.setVisibility(View.GONE);
            recentContainer.setVisibility(View.VISIBLE);
            Context ctx = recentContainer.getContext();
            // Build rows of 2 cards
            for (int i = 0; i < recentFiles.size(); i += 2) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, AndroidUtilities.dp(8));
                row.setLayoutParams(rowLp);

                row.addView(buildRecentCard(ctx, recentFiles.get(i)));
                if (i + 1 < recentFiles.size()) {
                    View spacer = new View(ctx);
                    row.addView(spacer, new LinearLayout.LayoutParams(AndroidUtilities.dp(8), 1));
                    row.addView(buildRecentCard(ctx, recentFiles.get(i + 1)));
                } else {
                    // Odd last card: fill right half with empty space
                    View placeholder = new View(ctx);
                    row.addView(placeholder, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                }
                recentContainer.addView(row);
            }
        }
    }

    /** File card: white bg, 12dp radius, 1dp border. Bottom-right ⋮ opens delete menu. */
    private View buildRecentCard(Context context, DriveFile file) {
        // Outer FrameLayout so we can overlay the ⋮ button
        FrameLayout outer = new FrameLayout(context);
        outer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Vertical wrapper: card + progress bar beneath it
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12),
                AndroidUtilities.dp(8), AndroidUtilities.dp(12));
        card.setBackground(cardBg());

        // Row 1: type-color icon + filename
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(context);
        icon.setImageResource(fileIcon(file));
        String fextH = fileExt(file.name);
        if (fileIconSelfColored(fextH)) {
            icon.clearColorFilter();
        } else {
            icon.setColorFilter(fileColorByExt(file), PorterDuff.Mode.SRC_IN);
        }
        row1.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL));

        TextView name = new TextView(context);
        name.setText(file.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setTextColor(TEXT_PRIMARY_C);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(24), 0);
        row1.addView(name, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        card.addView(row1, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Row 2: date
        TextView meta = new TextView(context);
        meta.setText(file.formatSize() + "  ·  " + formatDate(file.uploadedAt));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setTextColor(TEXT_SECONDARY_C);
        meta.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        card.addView(meta, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(90));
        wrapper.addView(card, cardLp);

        // Horizontal download progress bar — sits directly below the card
        android.widget.ProgressBar downloadBar = new android.widget.ProgressBar(
                context, null, android.R.attr.progressBarStyleHorizontal);
        downloadBar.setMax(10000);
        downloadBar.setProgress(0);
        downloadBar.setVisibility(View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            downloadBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A73E8));
            downloadBar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFFDADCE0));
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(3));
        barLp.setMargins(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        wrapper.addView(downloadBar, barLp);

        card.setOnClickListener(v -> openFile(context, file, downloadBar));
        outer.addView(wrapper, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        // ⋮ button — top-right overlay
        TextView moreBtn = new TextView(context);
        moreBtn.setText("⋮");
        moreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        moreBtn.setTextColor(0xFF9AA0A6);
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setOnClickListener(v -> showFileMenu(context, file));
        FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(
                AndroidUtilities.dp(30), AndroidUtilities.dp(30), Gravity.TOP | Gravity.END);
        moreLp.setMargins(0, AndroidUtilities.dp(4), AndroidUtilities.dp(4), 0);
        outer.addView(moreBtn, moreLp);

        return outer;
    }

    private void showFileMenu(Context context, DriveFile file) {
        android.app.Dialog dialog = new android.app.Dialog(context,
                android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(AndroidUtilities.dp(20));
        card.setBackground(cardBg);
        card.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20),
                AndroidUtilities.dp(20), AndroidUtilities.dp(12));

        // Title
        TextView titleTv = new TextView(context);
        titleTv.setText(file.name);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(TEXT_PRIMARY_C);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleTv.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        card.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(BORDER_C);
        card.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 4));

        // Menu items
        String[] labels = {"Open File", "Delete"};
        int[] icons = {R.drawable.msg_openin, R.drawable.msg_delete};
        int[] colors = {TEXT_PRIMARY_C, 0xFFEA4335};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(14),
                    AndroidUtilities.dp(4), AndroidUtilities.dp(14));

            ImageView icon = new ImageView(context);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(colors[i], PorterDuff.Mode.SRC_IN);
            row.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            TextView label = new TextView(context);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(colors[i]);
            if (i == 1) label.setTypeface(label.getTypeface(), Typeface.BOLD);
            row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (idx == 0) openFile(context, file, null);
                else confirmDelete(context, file);
            });
            card.addView(row);
        }

        FrameLayout outer = new FrameLayout(context);
        outer.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        outer.addView(card, cardLp);
        dialog.setContentView(outer);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void confirmDelete(Context context, DriveFile file) {
        android.app.Dialog dialog = new android.app.Dialog(context,
                android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(AndroidUtilities.dp(20));
        card.setBackground(cardBg);
        card.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24),
                AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        // Icon + Title
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, AndroidUtilities.dp(14));
        FrameLayout iconCircle = new FrameLayout(context);
        android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
        circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circleBg.setColor(0xFFEA4335);
        iconCircle.setBackground(circleBg);
        ImageView delIcon = new ImageView(context);
        delIcon.setImageResource(R.drawable.msg_delete);
        delIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        delIcon.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9),
                AndroidUtilities.dp(9), AndroidUtilities.dp(9));
        iconCircle.addView(delIcon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        titleRow.addView(iconCircle, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));
        TextView titleTv = new TextView(context);
        titleTv.setText("Delete File");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(TEXT_PRIMARY_C);
        titleTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        titleRow.addView(titleTv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Message
        TextView msgTv = new TextView(context);
        msgTv.setText("Delete \"" + file.name + "\" from CloudGram?");
        msgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        msgTv.setTextColor(TEXT_SECONDARY_C);
        msgTv.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        card.addView(msgTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Button row
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setGravity(Gravity.END);
        TextView cancelBtn = new TextView(context);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancelBtn.setTextColor(TEXT_SECONDARY_C);
        cancelBtn.setTypeface(cancelBtn.getTypeface(), Typeface.BOLD);
        cancelBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        TextView deleteBtn = new TextView(context);
        deleteBtn.setText("Delete");
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        deleteBtn.setTextColor(0xFFFFFFFF);
        deleteBtn.setTypeface(deleteBtn.getTypeface(), Typeface.BOLD);
        android.graphics.drawable.GradientDrawable delBtnBg = new android.graphics.drawable.GradientDrawable();
        delBtnBg.setColor(0xFFEA4335);
        delBtnBg.setCornerRadius(AndroidUtilities.dp(10));
        deleteBtn.setBackground(delBtnBg);
        deleteBtn.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        deleteBtn.setOnClickListener(v -> {
            dialog.dismiss();
            DriveRepository.getInstance(currentAccount).deleteFile(file,
                    new DriveRepository.SimpleCallback() {
                        @Override public void onSuccess() {
                            AndroidUtilities.runOnUIThread(() -> loadData());
                        }
                        @Override public void onError(String m) {
                            Toast.makeText(context, "Error: " + m, Toast.LENGTH_SHORT).show();
                        }
                    });
        });
        btnRow.addView(deleteBtn);
        card.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout outer = new FrameLayout(context);
        outer.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        outer.addView(card, cardLp);
        dialog.setContentView(outer);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void openFile(Context ctx, DriveFile file, android.widget.ProgressBar bar) {
        if (ctx == null) ctx = getParentActivity();
        if (ctx == null) return;
        final Context fCtx = ctx;

        // Check local cache (uploaded from this device)
        java.io.File f = new java.io.File(fCtx.getExternalCacheDir(), "drive_uploads/" + file.name);
        if (f.exists()) { openLocal(fCtx, file, f); return; }

        // Check Telegram FileLoader cache (previously downloaded)
        String p = DriveRepository.getInstance(currentAccount).getDownloadPath(file);
        if (p != null) {
            java.io.File cached = new java.io.File(p);
            if (cached.exists()) { openLocal(fCtx, file, cached); return; }
        }

        // Show progress bar at 0
        if (bar != null) {
            bar.setProgress(0);
            bar.setVisibility(View.VISIBLE);
        }
        DriveRepository.getInstance(currentAccount).downloadFile(file,
                new DriveRepository.DownloadCallback() {
                    private void hideBar() {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (bar != null) bar.setVisibility(View.GONE);
                        });
                    }
                    @Override public void onProgress(float prog) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (bar != null) bar.setProgress(Math.round(prog * 10000));
                        });
                    }
                    @Override public void onSuccess(java.io.File localFile) {
                        hideBar();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (localFile != null && localFile.exists()) {
                                openLocal(fCtx, file, localFile);
                            } else {
                                Toast.makeText(fCtx, "Error: file does not exist after download", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    @Override public void onError(String msg) {
                        hideBar();
                        AndroidUtilities.runOnUIThread(() ->
                                Toast.makeText(fCtx, "Download failed: " + msg, Toast.LENGTH_LONG).show());
                    }
                });
    }

    private void openLocal(Context ctx, DriveFile driveFile, java.io.File local) {
        if (driveFile.documentType == DriveFile.TYPE_IMAGE) {
            presentFragment(DriveImageViewFragment.create(local.getAbsolutePath(), driveFile.name));
            return;
        }
        if (driveFile.documentType == DriveFile.TYPE_AUDIO) {
            // Build playlist from recent audio files
            java.util.List<String> paths = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            int startIdx = 0;
            for (int i = 0; i < recentFiles.size(); i++) {
                DriveFile f = recentFiles.get(i);
                if (f.documentType == DriveFile.TYPE_AUDIO) {
                    java.io.File lf = findLocalFile(ctx, f);
                    if (lf != null) {
                        if (f.messageId == driveFile.messageId) startIdx = paths.size();
                        paths.add(lf.getAbsolutePath());
                        names.add(f.name);
                    }
                }
            }
            if (paths.isEmpty()) {
                paths.add(local.getAbsolutePath());
                names.add(driveFile.name);
                startIdx = 0;
            }
            new DriveAudioPlayerSheet(ctx, paths, names, startIdx).show();
            return;
        }
        launchViewIntent(ctx, local, driveFile.mimeType);
    }

    private java.io.File findLocalFile(Context ctx, DriveFile file) {
        java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "drive_uploads/" + file.name);
        if (f.exists()) return f;
        String p = DriveRepository.getInstance(currentAccount).getDownloadPath(file);
        if (p != null) {
            java.io.File cached = new java.io.File(p);
            if (cached.exists()) return cached;
        }
        return null;
    }

    private void launchViewIntent(Context ctx, java.io.File file, String mimeType) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".provider", file);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType != null && !mimeType.isEmpty() ? mimeType : "*/*");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app to open this file type", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static int fileTypeColor(int type) {
        switch (type) {
            case DriveFile.TYPE_IMAGE:    return 0xFF34A853;
            case DriveFile.TYPE_VIDEO:    return 0xFFFBBC05;
            case DriveFile.TYPE_AUDIO:    return 0xFF8B5CF6;
            case DriveFile.TYPE_DOCUMENT: return 0xFF1A73E8;
            default:                      return 0xFF9E9E9E;
        }
    }

    private static String fileExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static int fileIcon(DriveFile file) {
        String ext = fileExt(file.name);
        switch (file.documentType) {
            case DriveFile.TYPE_IMAGE: return R.drawable.msg_photos;
            case DriveFile.TYPE_AUDIO: return R.drawable.files_music;
            case DriveFile.TYPE_VIDEO: return R.drawable.msg_video;
            default: break;
        }
        switch (ext) {
            case "zip": case "rar": case "tar": case "gz":
            case "7z": case "bz2": case "xz": case "tgz":
                return R.drawable.large_archive;
            case "txt": case "log": case "rst":
                return R.drawable.large_notes;
            case "md": case "markdown":       return R.drawable.ic_file_md;
            case "js": case "jsx": case "mjs":return R.drawable.ic_file_js;
            case "ts": case "tsx":            return R.drawable.ic_file_ts;
            case "html": case "htm":          return R.drawable.ic_file_html;
            case "css": case "scss": case "sass": case "less":
                                              return R.drawable.ic_file_css;
            case "py": case "pyw": case "pyi":return R.drawable.ic_file_python;
            case "java":                      return R.drawable.ic_file_java;
            case "kt": case "kts":            return R.drawable.ic_file_kotlin;
            case "scala": case "groovy":      return R.drawable.menu_feature_code;
            case "c": case "h":               return R.drawable.ic_file_c;
            case "cpp": case "cxx": case "cc": case "hpp": case "hxx":
                                              return R.drawable.ic_file_cpp;
            case "cs":                        return R.drawable.ic_file_csharp;
            case "rb": case "erb":            return R.drawable.ic_file_ruby;
            case "php":                       return R.drawable.ic_file_php;
            case "go":                        return R.drawable.ic_file_go;
            case "rs":                        return R.drawable.ic_file_rust;
            case "swift":                     return R.drawable.ic_file_swift;
            case "dart":                      return R.drawable.ic_file_dart;
            case "lua":                       return R.drawable.ic_file_lua;
            case "r": case "rmd":             return R.drawable.menu_feature_code;
            case "sh": case "bash": case "zsh": case "fish":
            case "bat": case "ps1": case "cmd":
                                              return R.drawable.ic_file_shell;
            case "json":                      return R.drawable.ic_file_json;
            case "xml":                       return R.drawable.ic_file_xml;
            case "yaml": case "yml":          return R.drawable.ic_file_yaml;
            case "sql":                       return R.drawable.ic_file_sql;
            default:
                return R.drawable.msg_filehq;
        }
    }

    private static int fileColorByExt(DriveFile file) {
        String ext = fileExt(file.name);
        switch (file.documentType) {
            case DriveFile.TYPE_IMAGE:    return 0xFF34A853;
            case DriveFile.TYPE_AUDIO:    return 0xFF8B5CF6;
            case DriveFile.TYPE_VIDEO:    return 0xFFFBBC05;
            case DriveFile.TYPE_DOCUMENT:
                if ("pdf".equals(ext)) return 0xFFEA4335;
                return 0xFF1A73E8;
            default: break;
        }
        switch (ext) {
            case "zip": case "rar": case "tar": case "gz":
            case "7z": case "bz2": case "xz": case "tgz":  return 0xFFFA7B17;
            case "txt": case "log": case "rst":             return 0xFF757575;
            case "md": case "markdown":                     return 0xFF083FA1;
            case "js": case "jsx": case "mjs":              return 0xFFE6A817;
            case "ts": case "tsx":                          return 0xFF3178C6;
            case "html": case "htm":                        return 0xFFE34C26;
            case "css": case "scss": case "sass": case "less": return 0xFF264DE4;
            case "py": case "pyw": case "pyi":              return 0xFF9E9E9E;
            case "java":                                    return 0xFFED8B00;
            case "kt": case "kts":                          return 0xFF7F52FF;
            case "scala": case "groovy":                    return 0xFFDC322F;
            case "c": case "h":                             return 0xFF00599C;
            case "cpp": case "cxx": case "cc": case "hpp": case "hxx": return 0xFF00599C;
            case "cs":                                      return 0xFF68217A;
            case "rb": case "erb":                          return 0xFF701516;
            case "php":                                     return 0xFF4F5D95;
            case "go":                                      return 0xFF00ADD8;
            case "rs":                                      return 0xFFDEA584;
            case "swift":                                   return 0xFFF05138;
            case "dart":                                    return 0xFF00B4AB;
            case "lua":                                     return 0xFF000080;
            case "r": case "rmd":                           return 0xFF276DC3;
            case "sh": case "bash": case "zsh": case "fish":
            case "bat": case "ps1": case "cmd":             return 0xFF4CAF50;
            case "json":                                    return 0xFF5C6BC0;
            case "xml":                                     return 0xFFFF7043;
            case "yaml": case "yml":                        return 0xFFCB171E;
            case "sql":                                     return 0xFF336791;
            default:                                        return 0xFF9E9E9E;
        }
    }

    private static boolean fileIconSelfColored(String ext) {
        switch (ext) {
            case "py": case "pyw": case "pyi": return true;
            default: return false;
        }
    }

    private static String formatDate(long unixSeconds) {
        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(unixSeconds * 1000L));
    }
}
