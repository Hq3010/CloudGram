/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Color;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveFolder;
import org.telegram.messenger.drive.DriveRepository;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

public class DriveFilesFragment extends BaseFragment
        implements DriveRepository.DriveChangeListener {

    private static final int REQUEST_UPLOAD = 3001;
    private static final int PRIMARY_COLOR = 0xFF1A73E8;

    // MD3 design tokens
    private static final int BG_PAGE          = 0xFFF8FAFD;
    private static final int ACCENT           = 0xFF1A73E8;
    private static final int TEXT_PRIMARY_C   = 0xFF1F1F1F;
    private static final int TEXT_SECONDARY_C = 0xFF5F6368;
    private static final int BORDER_C         = 0xFFDADCE0;

    // Adapter view types
    private static final int ITEM_TYPE_FOLDER        = 0;
    private static final int ITEM_TYPE_FILE          = 1;
    private static final int ITEM_TYPE_FOLDER_HEADER = 2;
    private static final int ITEM_TYPE_FILE_HEADER   = 3;

    private RecyclerListView recyclerView;
    private FilesAdapter adapter;
    private LinearLayout breadcrumbContainer;
    private TextView emptyView;

    private final Stack<String> folderStack = new Stack<>();
    private final Stack<String> folderNameStack = new Stack<>();
    private final List<DriveFolder> currentFolders = new ArrayList<>();
    private final List<DriveFile> currentFiles = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        DriveRepository.getInstance(currentAccount).addChangeListener(this);
        DriveRepository.getInstance(currentAccount).syncFilesWithTelegram(null);
        folderStack.push(DriveFolder.ROOT_ID);
        folderNameStack.push("CloudGram");
        loadContents(DriveFolder.ROOT_ID);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        DriveRepository.getInstance(currentAccount).removeChangeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onFilesChanged() {
        // Refresh the current folder view when remote changes are detected
        String currentFolder = folderStack.isEmpty() ? DriveFolder.ROOT_ID : folderStack.peek();
        loadContents(currentFolder);
    }

    @Override
    public View createView(Context context) {
        // Match Home tab header style: white bg, dark title, gray icons
        android.graphics.drawable.Drawable cloudIcon =
                ContextCompat.getDrawable(context, R.drawable.ic_cloud_outline);
        if (cloudIcon != null) {
            cloudIcon.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        }
        actionBar.setTitle("CloudGram", cloudIcon);
        actionBar.setTitleColor(0xFF1F1F1F);
        actionBar.setBackgroundColor(0xFFFFFFFF);
        actionBar.setItemsColor(0xFF5F6368, false);
        // Back button hidden at root; shown only when inside a subfolder
        actionBar.setBackButtonImage(0);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    navigateUp();
                } else if (id == 99) {
                    DriveUploadQueueSheet sheet = new DriveUploadQueueSheet(getParentActivity(), currentAccount);
                    sheet.show();
                } else if (id == 1) {
                    // Search
                    presentFragment(new DriveSearchActivity());
                }
            }
        });
        // Upload queue icon + search icon
        org.telegram.ui.ActionBar.ActionBarMenu menu = actionBar.createMenu();
        org.telegram.ui.ActionBar.ActionBarMenuItem queueItem = menu.addItem(99, R.drawable.ic_upload_queue);
        queueItem.setIconColor(ACCENT);
        menu.addItem(1, R.drawable.outline_header_search);

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(BG_PAGE);

        // ── Breadcrumb bar (white, 1dp bottom border) ─────────────────────────
        HorizontalScrollView breadcrumbScroll = new HorizontalScrollView(context);
        breadcrumbScroll.setHorizontalScrollBarEnabled(false);
        breadcrumbScroll.setBackgroundColor(0xFFFFFFFF);

        // 1dp bottom divider via foreground trick
        View breadcrumbDivider = new View(context);
        breadcrumbDivider.setBackgroundColor(BORDER_C);
        frame.addView(breadcrumbDivider,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.TOP, 0, 44, 0, 0));

        breadcrumbContainer = new LinearLayout(context);
        breadcrumbContainer.setOrientation(LinearLayout.HORIZONTAL);
        breadcrumbContainer.setGravity(Gravity.CENTER_VERTICAL);
        breadcrumbContainer.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        breadcrumbScroll.addView(breadcrumbContainer,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        frame.addView(breadcrumbScroll,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP));

        // ── File list (GridLayoutManager: folders 1-span, everything else 2-span) ──
        adapter = new FilesAdapter();

        GridLayoutManager gridLayoutManager = new GridLayoutManager(context, 2);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter == null) return 2;
                return adapter.getItemViewType(position) == ITEM_TYPE_FOLDER ? 1 : 2;
            }
        });

        recyclerView = new RecyclerListView(context);
        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setBackgroundColor(0x00000000);
        recyclerView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(80));
        recyclerView.setClipToPadding(false);
        recyclerView.setAdapter(adapter);
        recyclerView.setOnItemClickListener((view, position) -> {
            int logical = adapter.toLogicalPosition(position);
            if (logical >= 0) {
                // Retrieve downloadBar stored in itemView tag (set in FileViewHolder constructor)
                Object tag = view.getTag();
                android.widget.ProgressBar bar =
                        (tag instanceof android.widget.ProgressBar)
                                ? (android.widget.ProgressBar) tag : null;
                onItemClick(logical, bar);
            }
        });
        recyclerView.setOnItemLongClickListener((view, position) -> {
            int logical = adapter.toLogicalPosition(position);
            if (logical >= 0) {
                onItemLongClick(context, logical);
                return true;
            }
            return false;
        });
        frame.addView(recyclerView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                        Gravity.TOP, 0, 45, 0, 0));

        // ── Empty state ───────────────────────────────────────────────────────
        emptyView = new TextView(context);
        emptyView.setText("📁\n\nNo files yet");
        emptyView.setTextColor(TEXT_SECONDARY_C);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        frame.addView(emptyView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                        Gravity.CENTER, 0, 44, 0, 0));

        // ── FAB (pill) ────────────────────────────────────────────────────────
        TextView fab = new TextView(context);
        fab.setText("+  New");
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        fab.setTypeface(fab.getTypeface(), Typeface.BOLD);
        fab.setTextColor(0xFFFFFFFF);
        fab.setGravity(Gravity.CENTER);
        fab.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        fab.setBackground(makePillBg(ACCENT));
        fab.setOnClickListener(v -> showFabMenu(context));
        frame.addView(fab, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.BOTTOM | Gravity.END, 0, 0, 16, 16));

        updateBreadcrumb();
        return fragmentView;
    }

    private static android.graphics.drawable.GradientDrawable makePillBg(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(AndroidUtilities.dp(50));
        return d;
    }

    private void showFabMenu(Context context) {
        String currentFolder = folderStack.isEmpty() ? DriveFolder.ROOT_ID : folderStack.peek();
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
            showNewFolderDialog(context, currentFolder);
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
        int lightColor = (iconColor & 0x00FFFFFF) | 0x1A000000;
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
            String targetFolder = folderStack.isEmpty() ? DriveFolder.ROOT_ID : folderStack.peek();
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
                        getParentActivity(), uri, targetFolder,
                        new DriveRepository.UploadCallback() {
                            @Override public void onProgress(float p) { }
                            @Override public void onSuccess(DriveFile file) {
                                AndroidUtilities.runOnUIThread(() -> loadContents(targetFolder));
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

    private void showNewFolderDialog(Context context, String parentId) {
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
        titleTv.setTypeface(titleTv.getTypeface(), android.graphics.Typeface.BOLD);
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
        cancelBtn.setTypeface(cancelBtn.getTypeface(), android.graphics.Typeface.BOLD);
        cancelBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        TextView createBtn = new TextView(context);
        createBtn.setText("Create");
        createBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        createBtn.setTextColor(ACCENT);
        createBtn.setTypeface(createBtn.getTypeface(), android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(0xFFE8F0FE);
        btnBg.setCornerRadius(AndroidUtilities.dp(10));
        createBtn.setBackground(btnBg);
        createBtn.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        createBtn.setOnClickListener(v -> {
            String name = nameEdit.getText().toString().trim();
            if (!name.isEmpty()) {
                DriveRepository.getInstance(currentAccount).createFolder(name, parentId,
                        new DriveRepository.SimpleCallback() {
                            @Override public void onSuccess() {
                                loadContents(folderStack.isEmpty()
                                        ? DriveFolder.ROOT_ID : folderStack.peek());
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

    private void loadContents(String folderId) {
        DriveRepository.getInstance(currentAccount).listFolder(folderId,
                (files, folders) -> {
                    currentFolders.clear();
                    currentFiles.clear();
                    if (folders != null) currentFolders.addAll(folders);
                    if (files != null) currentFiles.addAll(files);
                    if (adapter != null) adapter.notifyDataSetChanged();
                    updateBreadcrumb();
                    updateEmptyState();
                });
    }

    private void navigateTo(DriveFolder folder) {
        folderStack.push(folder.id);
        folderNameStack.push(folder.name);
        if (actionBar != null) {
            actionBar.setTitle(folder.name);
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        loadContents(folder.id);
    }

    public boolean navigateUp() {
        if (folderStack.size() <= 1) return false;
        folderStack.pop();
        folderNameStack.pop();
        String parentId   = folderStack.peek();
        String parentName = folderNameStack.peek();
        boolean atRoot = folderStack.size() <= 1;
        if (actionBar != null) {
            if (atRoot) {
                // Restore cloud icon at root
                android.graphics.drawable.Drawable icon =
                        ContextCompat.getDrawable(getParentActivity(), R.drawable.ic_cloud_outline);
                if (icon != null) icon.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
                actionBar.setTitle(parentName, icon);
            } else {
                actionBar.setTitle(parentName);
            }
            actionBar.setBackButtonImage(atRoot ? 0 : R.drawable.ic_ab_back);
        }
        loadContents(parentId);
        return true;
    }

    // onBackPressed - not an override of BaseFragment (which uses boolean param)
    public boolean onBackPressed() {
        return navigateUp();
    }

    private void onItemClick(int position, android.widget.ProgressBar bar) {
        if (position < currentFolders.size()) {
            navigateTo(currentFolders.get(position));
        } else {
            int fileIndex = position - currentFolders.size();
            if (fileIndex >= 0 && fileIndex < currentFiles.size()) {
                openFile(currentFiles.get(fileIndex), bar);
            }
        }
    }

    private void openFile(DriveFile file, android.widget.ProgressBar bar) {
        Context ctx = getParentActivity();
        if (ctx == null) return;

        java.io.File local = findLocal(ctx, file);
        if (local != null) { openLocal(file, local); return; }

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
                    @Override public void onProgress(float p) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (bar != null) {
                                bar.setProgress(Math.round(p * 10000));
                            }
                        });
                    }
                    @Override public void onSuccess(java.io.File localFile) {
                        hideBar();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (localFile != null && localFile.exists()) {
                                openLocal(file, localFile);
                            } else {
                                Context c = getParentActivity();
                                if (c != null) Toast.makeText(c, "Error: file does not exist after download", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    @Override public void onError(String msg) {
                        hideBar();
                        AndroidUtilities.runOnUIThread(() -> {
                            Context c = getParentActivity();
                            if (c != null) Toast.makeText(c, "Download failed: " + msg, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private java.io.File findLocal(Context ctx, DriveFile file) {
        // Uploaded from this device
        java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "drive_uploads/" + file.name);
        if (f.exists()) return f;
        // Previously downloaded via FileLoader
        String p = DriveRepository.getInstance(currentAccount).getDownloadPath(file);
        if (p != null) {
            java.io.File cached = new java.io.File(p);
            if (cached.exists()) return cached;
        }
        return null;
    }

    private void openLocal(DriveFile driveFile, java.io.File local) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        if (driveFile.documentType == DriveFile.TYPE_IMAGE) {
            presentFragment(DriveImageViewFragment.create(local.getAbsolutePath(), driveFile.name));
            return;
        }
        if (driveFile.documentType == DriveFile.TYPE_AUDIO) {
            // Build playlist from all audio files in current folder
            java.util.List<String> paths = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            int startIdx = 0;
            for (int i = 0; i < currentFiles.size(); i++) {
                DriveFile f = currentFiles.get(i);
                if (f.documentType == DriveFile.TYPE_AUDIO) {
                    java.io.File lf = findLocal(ctx, f);
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

    private void launchViewIntent(Context ctx, java.io.File file, String mimeType) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType != null && !mimeType.isEmpty() ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app to open this file type", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onItemLongClick(Context context, int position) {
        int fileIndex = position - currentFolders.size();
        if (fileIndex < 0 || fileIndex >= currentFiles.size()) return;
        DriveFile file = currentFiles.get(fileIndex);

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

        // Divider
        View divider = new View(context);
        divider.setBackgroundColor(BORDER_C);
        card.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 4));

        // Menu items
        String[] labels = {"Delete", "Share", "Info"};
        int[] icons = {R.drawable.msg_delete, R.drawable.msg_share, R.drawable.msg_info};
        int[] colors = {0xFFEA4335, TEXT_PRIMARY_C, TEXT_PRIMARY_C};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(14),
                    AndroidUtilities.dp(4), AndroidUtilities.dp(14));
            android.graphics.drawable.GradientDrawable rippleBg = new android.graphics.drawable.GradientDrawable();
            rippleBg.setCornerRadius(AndroidUtilities.dp(10));
            rippleBg.setColor(0x00000000);
            row.setBackground(rippleBg);

            ImageView icon = new ImageView(context);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(colors[i], PorterDuff.Mode.SRC_IN);
            row.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            TextView label = new TextView(context);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(colors[i]);
            if (i == 0) label.setTypeface(label.getTypeface(), Typeface.BOLD);
            row.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                switch (idx) {
                    case 0: confirmDelete(context, file); break;
                    case 1: shareFile(file); break;
                    case 2: showFileInfoDialog(file); break;
                }
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
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showFileInfoDialog(DriveFile file) {
        Context ctx = getParentActivity();
        if (ctx == null) return;

        android.app.Dialog dialog = new android.app.Dialog(ctx,
                android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(AndroidUtilities.dp(20));
        card.setBackground(cardBg);
        card.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24),
                AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        // Icon + Title row
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, AndroidUtilities.dp(18));
        FrameLayout iconCircle = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable circleBg = new android.graphics.drawable.GradientDrawable();
        circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circleBg.setColor(ACCENT);
        iconCircle.setBackground(circleBg);
        ImageView infoIcon = new ImageView(ctx);
        infoIcon.setImageResource(R.drawable.msg_info);
        infoIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        infoIcon.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9),
                AndroidUtilities.dp(9), AndroidUtilities.dp(9));
        iconCircle.addView(infoIcon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        titleRow.addView(iconCircle, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));
        TextView titleTv = new TextView(ctx);
        titleTv.setText("File Info");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(TEXT_PRIMARY_C);
        titleTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        titleRow.addView(titleTv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Info rows
        String[][] info = {
                {"Name", file.name},
                {"Size", file.formatSize()},
                {"Upload Date", formatDate(file.uploadedAt)}
        };
        for (String[] pair : info) {
            LinearLayout infoRow = new LinearLayout(ctx);
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
            TextView lbl = new TextView(ctx);
            lbl.setText(pair[0]);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            lbl.setTextColor(TEXT_SECONDARY_C);
            lbl.setTypeface(lbl.getTypeface(), Typeface.BOLD);
            infoRow.addView(lbl, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            TextView val = new TextView(ctx);
            val.setText(pair[1]);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            val.setTextColor(TEXT_PRIMARY_C);
            val.setGravity(Gravity.END);
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            infoRow.addView(val, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(infoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        // OK button
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        TextView okBtn = new TextView(ctx);
        okBtn.setText("Close");
        okBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        okBtn.setTextColor(ACCENT);
        okBtn.setTypeface(okBtn.getTypeface(), Typeface.BOLD);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(0xFFE8F0FE);
        btnBg.setCornerRadius(AndroidUtilities.dp(10));
        okBtn.setBackground(btnBg);
        okBtn.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        okBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(okBtn);
        card.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout outer = new FrameLayout(ctx);
        outer.setBackgroundColor(0x00000000);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        outer.addView(card, cardLp);
        dialog.setContentView(outer);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        TextView msg = new TextView(context);
        msg.setText("Delete \"" + file.name + "\" from CloudGram?");
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        msg.setTextColor(TEXT_SECONDARY_C);
        msg.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        card.addView(msg, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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
                            loadContents(folderStack.isEmpty()
                                    ? DriveFolder.ROOT_ID : folderStack.peek());
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
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void onFolderMoreClick(Context context, int folderIdx) {
        if (folderIdx < 0 || folderIdx >= currentFolders.size()) return;
        DriveFolder folder = currentFolders.get(folderIdx);

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
        titleTv.setText(folder.name);
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

        // Delete folder row
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(14),
                AndroidUtilities.dp(4), AndroidUtilities.dp(14));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_delete);
        icon.setColorFilter(0xFFEA4335, PorterDuff.Mode.SRC_IN);
        row.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        TextView label = new TextView(context);
        label.setText("Delete Folder");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(0xFFEA4335);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        row.addView(label);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDeleteFolder(context, folder);
        });
        card.addView(row);

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
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void confirmDeleteFolder(Context context, DriveFolder folder) {
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
        titleTv.setText("Delete Folder");
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(TEXT_PRIMARY_C);
        titleTv.setPadding(AndroidUtilities.dp(14), 0, 0, 0);
        titleRow.addView(titleTv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Message
        TextView msgTv = new TextView(context);
        msgTv.setText("Delete \"" + folder.name + "\" and all files inside?");
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
            DriveRepository.getInstance(currentAccount).deleteFolder(folder.id,
                    new DriveRepository.SimpleCallback() {
                        @Override public void onSuccess() {
                            loadContents(folderStack.isEmpty()
                                    ? DriveFolder.ROOT_ID : folderStack.peek());
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
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void updateBreadcrumb() {
        if (breadcrumbContainer == null) return;
        breadcrumbContainer.removeAllViews();
        Context ctx = breadcrumbContainer.getContext();
        List<String> names = new ArrayList<>(folderNameStack);
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            boolean isActive = (i == names.size() - 1);
            TextView seg = new TextView(ctx);
            seg.setText(names.get(i));
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            if (isActive) {
                // Pill with ACCENT background, white text
                android.graphics.drawable.GradientDrawable pillBg =
                        new android.graphics.drawable.GradientDrawable();
                pillBg.setColor(ACCENT);
                pillBg.setCornerRadius(AndroidUtilities.dp(50));
                seg.setBackground(pillBg);
                seg.setTextColor(0xFFFFFFFF);
                seg.setTypeface(seg.getTypeface(), Typeface.BOLD);
                seg.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4),
                        AndroidUtilities.dp(12), AndroidUtilities.dp(4));
            } else {
                // Parent: TEXT_SECONDARY, no background
                seg.setTextColor(TEXT_SECONDARY_C);
                seg.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4),
                        AndroidUtilities.dp(4), AndroidUtilities.dp(4));
            }
            seg.setOnClickListener(v -> navigateToIndex(idx));
            breadcrumbContainer.addView(seg);
            if (!isActive) {
                TextView sep = new TextView(ctx);
                sep.setText(" › ");
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                sep.setTextColor(0xFF9AA0A6);
                breadcrumbContainer.addView(sep);
            }
        }
    }

    private void navigateToIndex(int idx) {
        while (folderStack.size() > idx + 1) {
            folderStack.pop();
            folderNameStack.pop();
        }
        String folderId   = folderStack.isEmpty() ? DriveFolder.ROOT_ID : folderStack.peek();
        String folderName = folderNameStack.isEmpty() ? "CloudGram" : folderNameStack.peek();
        if (actionBar != null) {
            if (idx == 0) {
                // Restore cloud icon at root
                android.graphics.drawable.Drawable icon =
                        ContextCompat.getDrawable(getParentActivity(), R.drawable.ic_cloud_outline);
                if (icon != null) icon.setBounds(0, 0, AndroidUtilities.dp(22), AndroidUtilities.dp(22));
                actionBar.setTitle(folderName, icon);
            } else {
                actionBar.setTitle(folderName);
            }
            actionBar.setBackButtonImage(idx > 0 ? R.drawable.ic_ab_back : 0);
        }
        loadContents(folderId);
    }

    private void updateEmptyState() {
        if (emptyView == null) return;
        boolean isEmpty = currentFolders.isEmpty() && currentFiles.isEmpty();
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
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

    // ── per-file icon & color helpers ─────────────────────────────────────

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
            case "pdf": case "doc": case "docx": case "odt":
            case "xls": case "xlsx": case "ods":
            case "ppt": case "pptx": case "odp":
                return R.drawable.msg_filehq;
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
            // Markdown
            case "md": case "markdown":                     return 0xFF083FA1;
            // Web / frontend
            case "js": case "jsx": case "mjs":              return 0xFFE6A817;
            case "ts": case "tsx":                          return 0xFF3178C6;
            case "html": case "htm":                        return 0xFFE34C26;
            case "css": case "scss": case "sass": case "less": return 0xFF264DE4;
            // Python (self-colored icon — neutral bg)
            case "py": case "pyw": case "pyi":              return 0xFF9E9E9E;
            // JVM
            case "java":                                    return 0xFFED8B00;
            case "kt": case "kts":                          return 0xFF7F52FF;
            case "scala": case "groovy":                    return 0xFFDC322F;
            // C-family
            case "c": case "h":                             return 0xFF00599C;
            case "cpp": case "cxx": case "cc": case "hpp": case "hxx": return 0xFF00599C;
            case "cs":                                      return 0xFF68217A;
            // Scripting / systems
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
            // Data formats
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

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class FilesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        /**
         * Visual position layout (for a mix of folders + files):
         *   [0]           FOLDER_HEADER  (if folders exist)
         *   [1..nF]       FOLDER items   (1 span each → 2-column grid)
         *   [nF+1]        FILE_HEADER    (if files exist)
         *   [nF+2..end]   FILE items     (2 spans → full-width)
         * If no folders: FILE_HEADER at 0, FILE items from 1.
         */
        @Override
        public int getItemViewType(int position) {
            int nF = currentFolders.size();
            int nFi = currentFiles.size();
            if (nF > 0) {
                if (position == 0)    return ITEM_TYPE_FOLDER_HEADER;
                if (position <= nF)   return ITEM_TYPE_FOLDER;
                int rem = position - nF - 1;
                if (nFi > 0) {
                    if (rem == 0) return ITEM_TYPE_FILE_HEADER;
                    return ITEM_TYPE_FILE;
                }
            } else if (nFi > 0) {
                if (position == 0) return ITEM_TYPE_FILE_HEADER;
                return ITEM_TYPE_FILE;
            }
            return ITEM_TYPE_FILE;
        }

        @Override
        public int getItemCount() {
            int count = currentFolders.size() + currentFiles.size();
            if (!currentFolders.isEmpty()) count++; // folder section header
            if (!currentFiles.isEmpty())   count++; // file section header
            return count;
        }

        /** Converts a visual adapter position to the logical position expected by onItemClick(). */
        int toLogicalPosition(int visualPos) {
            int nF = currentFolders.size();
            int nFi = currentFiles.size();
            if (nF > 0) {
                if (visualPos == 0) return -1;              // folder header
                if (visualPos <= nF) return visualPos - 1;  // logical folder index
                int rem = visualPos - nF - 1;
                if (nFi > 0) {
                    if (rem == 0) return -1;                // file header
                    return nF + (rem - 1);                  // logical file index
                }
            } else if (nFi > 0) {
                if (visualPos == 0) return -1;              // file header
                return nF + (visualPos - 1);
            }
            return -1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            switch (viewType) {
                case ITEM_TYPE_FOLDER_HEADER:
                case ITEM_TYPE_FILE_HEADER:
                    return new SectionHeaderViewHolder(ctx);
                case ITEM_TYPE_FOLDER:
                    return new FolderViewHolder(ctx);
                default:
                    return new FileViewHolder(ctx);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);

            if (holder instanceof SectionHeaderViewHolder) {
                ((SectionHeaderViewHolder) holder).title.setText(
                        viewType == ITEM_TYPE_FOLDER_HEADER ? "Folders" : "Files");

            } else if (holder instanceof FolderViewHolder) {
                // Folder index: position - 1 (skip the folder header)
                int folderIdx = position - 1;
                DriveFolder folder = currentFolders.get(folderIdx);
                FolderViewHolder fh = (FolderViewHolder) holder;
                fh.name.setText(folder.name);
                int fc = PRIMARY_COLOR;
                if (folder.color != null && !folder.color.isEmpty()) {
                    try { fc = Color.parseColor(folder.color); }
                    catch (IllegalArgumentException ignored) { fc = PRIMARY_COLOR; }
                }
                fh.icon.setColorFilter(fc, PorterDuff.Mode.SRC_IN);
                fh.moreBtnAction = () -> {
                    int actualIdx = fh.getAdapterPosition() - 1;
                    if (actualIdx >= 0 && actualIdx < currentFolders.size()) {
                        onFolderMoreClick(fh.itemView.getContext(), actualIdx);
                    }
                };

            } else if (holder instanceof FileViewHolder) {
                // File start index: (nF+2) if folders exist, else 1
                int nF = currentFolders.size();
                int fileStart = nF > 0 ? nF + 2 : 1;
                int fileIdx = position - fileStart;
                DriveFile file = currentFiles.get(fileIdx);
                FileViewHolder fh = (FileViewHolder) holder;
                fh.name.setText(file.name);
                fh.size.setText(file.formatSize());
                fh.date.setText(formatDate(file.uploadedAt));
                int tc = fileColorByExt(file);
                android.graphics.drawable.GradientDrawable circleBg =
                        new android.graphics.drawable.GradientDrawable();
                circleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                String fext = fileExt(file.name);
                if (fileIconSelfColored(fext)) {
                    circleBg.setColor(0x1A9E9E9E);
                    fh.iconFrame.setBackground(circleBg);
                    fh.icon.setImageResource(fileIcon(file));
                    fh.icon.clearColorFilter();
                } else {
                    circleBg.setColor(tc);
                    fh.iconFrame.setBackground(circleBg);
                    fh.icon.setImageResource(fileIcon(file));
                    fh.icon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
                }
                // Reset download bar state + re-tag itemView for click listener lookup
                fh.downloadBar.setProgress(0);
                fh.downloadBar.setVisibility(View.GONE);
                fh.itemView.setTag(fh.downloadBar);
                fh.moreBtnAction = () -> {
                    int logical = toLogicalPosition(fh.getAdapterPosition());
                    if (logical >= 0) {
                        Context c = fh.itemView.getContext();
                        DriveFilesFragment.this.onItemLongClick(c, logical);
                    }
                };
            }
        }
    }

    /** Section header: "Folders" or "Files" — full-width, 13sp bold. */
    private static class SectionHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        SectionHeaderViewHolder(Context ctx) {
            super(new TextView(ctx));
            title = (TextView) itemView;
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
            title.setTextColor(0xFF1F1F1F);
            title.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(6));
            title.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** Folder card: white, 12dp radius, 1dp border. Fills 1 span (half-width in 2-col grid). */
    private static class FolderViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView  name;
        final TextView  moreBtn;
        Runnable moreBtnAction;

        FolderViewHolder(Context ctx) {
            super(new FrameLayout(ctx));
            FrameLayout outer = (FrameLayout) itemView;
            outer.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            // Extra right padding so folder name text stays clear of the ⋮ button
            card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(36), AndroidUtilities.dp(12));
            card.setBackground(makeCardBg());

            // Top row: folder icon only (moreBtn is now an overlay on outer)
            icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.settings_folders);
            card.addView(icon, LayoutHelper.createLinear(28, 28));

            name = new TextView(ctx);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
            name.setTextColor(0xFF1F1F1F);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, AndroidUtilities.dp(6), 0, 0);
            card.addView(name, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(AndroidUtilities.dp(6), AndroidUtilities.dp(4),
                    AndroidUtilities.dp(6), AndroidUtilities.dp(4));
            outer.addView(card, cardLp);

            // ⋮ button: DIRECT child of outer so RecyclerListView detects it as clickable
            // and skips the item-click handler when this button is tapped.
            moreBtn = new TextView(ctx);
            moreBtn.setText("⋮");
            moreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            moreBtn.setTextColor(0xFF5F6368);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setClickable(true);
            moreBtn.setFocusable(true);
            moreBtn.setOnClickListener(v -> { if (moreBtnAction != null) moreBtnAction.run(); });
            FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(36), AndroidUtilities.dp(36));
            moreLp.gravity = Gravity.TOP | Gravity.END;
            moreLp.setMargins(0, AndroidUtilities.dp(4), AndroidUtilities.dp(6), 0);
            outer.addView(moreBtn, moreLp);
        }

        static android.graphics.drawable.GradientDrawable makeCardBg() {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setColor(0xFFFFFFFF);
            d.setCornerRadius(AndroidUtilities.dp(12));
            d.setStroke(AndroidUtilities.dp(1), 0xFFDADCE0);
            return d;
        }
    }

    /** File row card: white, 12dp radius, 1dp border, colored circle icon, name+meta, ⋮ button. */
    private static class FileViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconFrame;
        final ImageView   icon;
        final android.widget.ProgressBar downloadBar;  // horizontal progress line below card
        final TextView    name;
        final TextView    size;
        final TextView    date;
        final TextView    moreBtn;
        Runnable moreBtnAction;

        FileViewHolder(Context ctx) {
            super(new FrameLayout(ctx));
            FrameLayout outer = (FrameLayout) itemView;
            outer.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Vertical wrapper: card + progress bar beneath it
            LinearLayout wrapper = new LinearLayout(ctx);
            wrapper.setOrientation(LinearLayout.VERTICAL);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            // Extra right padding so name text stays clear of the ⋮ button overlay
            card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(48), AndroidUtilities.dp(12));
            card.setBackground(FolderViewHolder.makeCardBg());

            // Colored circle icon (36dp)
            iconFrame = new FrameLayout(ctx);
            icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.settings_folders);
            icon.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(7),
                    AndroidUtilities.dp(7), AndroidUtilities.dp(7));
            iconFrame.addView(icon, new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(36), AndroidUtilities.dp(36)));
            card.addView(iconFrame, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

            // Name (bold 14sp) + meta (size · date, 12sp)
            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(AndroidUtilities.dp(12), 0, 0, 0);

            name = new TextView(ctx);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
            name.setTextColor(0xFF1F1F1F);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout meta = new LinearLayout(ctx);
            meta.setOrientation(LinearLayout.HORIZONTAL);

            size = new TextView(ctx);
            size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            size.setTextColor(0xFF5F6368);
            meta.addView(size, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            TextView dot = new TextView(ctx);
            dot.setText("  ·  ");
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            dot.setTextColor(0xFF5F6368);
            meta.addView(dot, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            date = new TextView(ctx);
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            date.setTextColor(0xFF5F6368);
            meta.addView(date, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            info.addView(meta, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            card.addView(info, LayoutHelper.createLinear(
                    0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(AndroidUtilities.dp(12), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(12), 0);
            wrapper.addView(card, cardLp);

            // Horizontal download progress bar — sits directly below the card
            downloadBar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            downloadBar.setMax(10000);
            downloadBar.setProgress(0);
            downloadBar.setVisibility(View.GONE);
            // Tint the bar with accent blue
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.content.res.ColorStateList tint =
                        android.content.res.ColorStateList.valueOf(0xFF1A73E8);
                downloadBar.setProgressTintList(tint);
                downloadBar.setProgressBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFFDADCE0));
            }
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(3));
            barLp.setMargins(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(3));
            wrapper.addView(downloadBar, barLp);

            FrameLayout.LayoutParams wrapperLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            outer.addView(wrapper, wrapperLp);

            // ⋮ button: DIRECT child of outer so RecyclerListView detects it as clickable
            moreBtn = new TextView(ctx);
            moreBtn.setText("⋮");
            moreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            moreBtn.setTextColor(0xFF5F6368);
            moreBtn.setGravity(Gravity.CENTER);
            moreBtn.setClickable(true);
            moreBtn.setFocusable(true);
            moreBtn.setOnClickListener(v -> { if (moreBtnAction != null) moreBtnAction.run(); });
            FrameLayout.LayoutParams moreLp = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(44), FrameLayout.LayoutParams.MATCH_PARENT);
            moreLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            moreLp.setMargins(0, AndroidUtilities.dp(3), AndroidUtilities.dp(12), AndroidUtilities.dp(3));
            outer.addView(moreBtn, moreLp);
            // Store downloadBar in itemView tag for easy retrieval in click listener
            outer.setTag(downloadBar);
        }
    }

    private void shareFile(DriveFile file) {
        Context ctx = getParentActivity();
        if (ctx == null) return;

        // Check upload cache first
        java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "drive_uploads/" + file.name);
        if (f.exists()) { shareLocalFile(file, f.getAbsolutePath()); return; }

        // Check FileLoader cache
        String path = DriveRepository.getInstance(currentAccount).getDownloadPath(file);
        if (path != null) { shareLocalFile(file, path); return; }

        Toast.makeText(ctx, "Downloading for sharing…", Toast.LENGTH_SHORT).show();
        DriveRepository.getInstance(currentAccount).downloadFile(file,
                new DriveRepository.DownloadCallback() {
                    @Override public void onProgress(float p) { }
                    @Override public void onSuccess(java.io.File localFile) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (localFile != null && localFile.exists()) {
                                shareLocalFile(file, localFile.getAbsolutePath());
                            }
                        });
                    }
                    @Override public void onError(String msg) {
                        AndroidUtilities.runOnUIThread(() -> {
                            Context c = getParentActivity();
                            if (c != null) Toast.makeText(c, "Download failed: " + msg, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void shareLocalFile(DriveFile file, String localPath) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                getParentActivity(),
                getParentActivity().getPackageName() + ".provider",
                new java.io.File(localPath)
        );
        android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setType(file.mimeType != null ? file.mimeType : "*/*");
        shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getParentActivity().startActivity(android.content.Intent.createChooser(shareIntent, "Share " + file.name));
    }
}
