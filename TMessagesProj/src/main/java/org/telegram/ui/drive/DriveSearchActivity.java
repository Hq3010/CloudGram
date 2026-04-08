/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriveSearchActivity extends BaseFragment {

    // Palette (matches the rest of the app)
    private static final int BG_PAGE    = 0xFFF8FAFD;
    private static final int BG_SURFACE = 0xFFFFFFFF;
    private static final int ACCENT     = 0xFF1A73E8;
    private static final int TEXT_PRI   = 0xFF1F1F1F;
    private static final int TEXT_SEC   = 0xFF5F6368;
    private static final int BORDER     = 0xFFDADCE0;

    private static final String[] FILTER_LABELS = {"All", "Images", "Videos", "Documents", "Audio"};
    private static final int[] FILTER_TYPES = {
            -1, DriveFile.TYPE_IMAGE, DriveFile.TYPE_VIDEO,
            DriveFile.TYPE_DOCUMENT, DriveFile.TYPE_AUDIO
    };

    private EditText searchEdit;
    private final TextView[] chips = new TextView[FILTER_LABELS.length];
    private LinearLayout resultsContainer;
    private LinearLayout emptyState;
    private ScrollView scrollView;

    private int selectedFilter = 0;
    private Runnable searchRunnable;
    private final List<DriveFile>   resultFiles   = new ArrayList<>();
    private final List<DriveFolder> resultFolders = new ArrayList<>();

    @Override
    public View createView(Context context) {
        // ── ActionBar: white + back + search field ────────────────────────────
        actionBar.setBackgroundColor(BG_SURFACE);
        actionBar.setItemsColor(TEXT_PRI, false);
        actionBar.setTitleColor(TEXT_PRI);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        // Embed EditText inside ActionBar
        searchEdit = new EditText(context);
        searchEdit.setHint("Search files, folders…");
        searchEdit.setHintTextColor(0xFF9AA0A6);
        searchEdit.setTextColor(TEXT_PRI);
        searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchEdit.setBackground(null);
        searchEdit.setSingleLine();
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        FrameLayout.LayoutParams searchLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48));
        searchLp.gravity = Gravity.CENTER_VERTICAL;
        // Leave room for the back-arrow button (~56 dp)
        searchLp.leftMargin = AndroidUtilities.dp(56);
        searchLp.rightMargin = AndroidUtilities.dp(16);
        actionBar.addView(searchEdit, searchLp);

        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleSearch(s.toString());
            }
        });

        // ── Root ──────────────────────────────────────────────────────────────
        fragmentView = new FrameLayout(context);
        ((FrameLayout) fragmentView).setBackgroundColor(BG_PAGE);

        // ── Filter chips row ──────────────────────────────────────────────────
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(BG_SURFACE);

        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                AndroidUtilities.dp(12), AndroidUtilities.dp(10));

        for (int i = 0; i < FILTER_LABELS.length; i++) {
            TextView chip = new TextView(context);
            chip.setText(FILTER_LABELS[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(7),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(7));
            chips[i] = chip;
            styleChip(chip, i == 0);

            final int idx = i;
            chip.setOnClickListener(v -> {
                selectedFilter = idx;
                refreshChips();
                scheduleSearch(searchEdit.getText().toString());
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, AndroidUtilities.dp(8), 0);
            chipRow.addView(chip, lp);
        }

        chipScroll.addView(chipRow);

        // ── Scrollable results area ───────────────────────────────────────────
        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(BG_PAGE);

        LinearLayout scrollContent = new LinearLayout(context);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(80));

        resultsContainer = new LinearLayout(context);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(resultsContainer, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Empty state
        emptyState = new LinearLayout(context);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(60),
                AndroidUtilities.dp(32), AndroidUtilities.dp(32));
        emptyState.setVisibility(View.GONE);

        ImageView emptyIcon = new ImageView(context);
        emptyIcon.setImageResource(R.drawable.msg_search);
        emptyIcon.setColorFilter(BORDER, PorterDuff.Mode.SRC_IN);
        emptyState.addView(emptyIcon, LayoutHelper.createLinear(56, 56, Gravity.CENTER));

        TextView emptyTitle = new TextView(context);
        emptyTitle.setText("No results found");
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyTitle.setTypeface(emptyTitle.getTypeface(), Typeface.BOLD);
        emptyTitle.setTextColor(TEXT_PRI);
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(6));
        emptyState.addView(emptyTitle, titleLp);

        TextView emptySub = new TextView(context);
        emptySub.setText("Try searching with different keywords");
        emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptySub.setTextColor(TEXT_SEC);
        emptySub.setGravity(Gravity.CENTER);
        emptyState.addView(emptySub);

        scrollContent.addView(emptyState, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView.addView(scrollContent, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ── Assemble ──────────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(chipScroll, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Thin divider under chips
        View chipDivider = new View(context);
        chipDivider.setBackgroundColor(BORDER);
        root.addView(chipDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));

        root.addView(scrollView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 0, 1f));

        ((FrameLayout) fragmentView).addView(root, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Auto-show keyboard
        AndroidUtilities.runOnUIThread(() -> {
            searchEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT);
        }, 200);

        return fragmentView;
    }

    // ── Search logic ──────────────────────────────────────────────────────────

    private void scheduleSearch(String raw) {
        if (searchRunnable != null) AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        if (TextUtils.isEmpty(raw.trim())) {
            resultFiles.clear();
            resultFolders.clear();
            rebuildResults(false);
            return;
        }
        final String q = raw.trim();
        searchRunnable = () -> {
            int type = FILTER_TYPES[selectedFilter];
            if (type < 0) {
                // Search both files and folders
                DriveRepository.getInstance(currentAccount).searchAll(q, (files, folders) -> {
                    resultFiles.clear();
                    resultFolders.clear();
                    resultFiles.addAll(files);
                    resultFolders.addAll(folders);
                    rebuildResults(true);
                });
            } else {
                // Files only (filter active)
                resultFolders.clear();
                DriveRepository.getInstance(currentAccount).searchFiles(q, type, files -> {
                    resultFiles.clear();
                    resultFiles.addAll(files);
                    rebuildResults(true);
                });
            }
        };
        AndroidUtilities.runOnUIThread(searchRunnable, 400);
    }

    private void rebuildResults(boolean searched) {
        if (resultsContainer == null) return;
        resultsContainer.removeAllViews();
        Context ctx = getParentActivity();
        if (ctx == null) return;

        boolean hasAny = !resultFolders.isEmpty() || !resultFiles.isEmpty();

        if (!searched || !hasAny) {
            emptyState.setVisibility(searched ? View.VISIBLE : View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);

        // ── Folders section ───────────────────────────────────────────────────
        if (!resultFolders.isEmpty()) {
            addSectionHeader(ctx, "Folders (" + resultFolders.size() + ")");
            for (DriveFolder folder : resultFolders) {
                resultsContainer.addView(buildFolderRow(ctx, folder));
            }
        }

        // ── Files section ─────────────────────────────────────────────────────
        if (!resultFiles.isEmpty()) {
            addSectionHeader(ctx, "Files (" + resultFiles.size() + ")");
            for (DriveFile file : resultFiles) {
                resultsContainer.addView(buildFileRow(ctx, file));
            }
        }
    }

    private void addSectionHeader(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setTextColor(TEXT_SEC);
        tv.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(AndroidUtilities.dp(4), AndroidUtilities.dp(16),
                AndroidUtilities.dp(4), AndroidUtilities.dp(6));
        tv.setLayoutParams(lp);
        resultsContainer.addView(tv);
    }

    private View buildFolderRow(Context ctx, DriveFolder folder) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(13),
                AndroidUtilities.dp(14), AndroidUtilities.dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE);
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setStroke(AndroidUtilities.dp(1), BORDER);
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, AndroidUtilities.dp(6));
        row.setLayoutParams(rowLp);

        // Folder icon circle
        FrameLayout circle = new FrameLayout(ctx);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(0x1A34A853);
        circle.setBackground(circleBg);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.settings_folders);
        icon.setColorFilter(0xFF34A853, PorterDuff.Mode.SRC_IN);
        icon.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        circle.addView(icon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(40), AndroidUtilities.dp(40)));
        row.addView(circle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        TextView name = new TextView(ctx);
        name.setText(folder.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setTextColor(TEXT_PRI);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(AndroidUtilities.dp(12), 0, 0, 0);
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT,
                1f, Gravity.CENTER_VERTICAL));
        return row;
    }

    private View buildFileRow(Context ctx, DriveFile file) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(13),
                AndroidUtilities.dp(14), AndroidUtilities.dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SURFACE);
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setStroke(AndroidUtilities.dp(1), BORDER);
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, AndroidUtilities.dp(6));
        row.setLayoutParams(rowLp);

        // File icon circle
        String fextS = fileExt(file.name);
        int iconColor = fileColorByExt(file);
        FrameLayout circle = new FrameLayout(ctx);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor((iconColor & 0x00FFFFFF) | 0x1A000000);
        circle.setBackground(circleBg);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(fileIcon(file));
        if (fileIconSelfColored(fextS)) {
            icon.clearColorFilter();
        } else {
            icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        }
        icon.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        circle.addView(icon, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(40), AndroidUtilities.dp(40)));

        // Spinner overlay
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(
                ctx, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        spinner.getIndeterminateDrawable().setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        circle.addView(spinner, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(22), AndroidUtilities.dp(22), Gravity.CENTER));

        row.addView(circle, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        // Info column
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(AndroidUtilities.dp(12), 0, 0, 0);

        TextView name = new TextView(ctx);
        name.setText(file.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setTextColor(TEXT_PRI);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView meta = new TextView(ctx);
        meta.setText(file.formatSize() + "  ·  " + formatDate(file.uploadedAt));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(TEXT_SEC);
        info.addView(meta, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT,
                1f, Gravity.CENTER_VERTICAL));

        // Tap to open
        row.setOnClickListener(v -> openFile(file, spinner));
        return row;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void styleChip(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(20));
        if (selected) {
            bg.setColor(ACCENT);
            chip.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(AndroidUtilities.dp(1), BORDER);
            chip.setTextColor(TEXT_SEC);
        }
        chip.setBackground(bg);
    }

    private void refreshChips() {
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] != null) styleChip(chips[i], i == selectedFilter);
        }
    }

    private static int fileTypeColor(int type) {
        switch (type) {
            case DriveFile.TYPE_IMAGE:    return 0xFF34A853;
            case DriveFile.TYPE_VIDEO:    return 0xFFFBBC05;
            case DriveFile.TYPE_AUDIO:    return 0xFF8B5CF6;
            case DriveFile.TYPE_DOCUMENT: return 0xFF4285F4;
            default:                      return ACCENT;
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
                return 0xFF4285F4;
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
            case "sh": case "bash": case "zsh": case "fish":
            case "bat": case "ps1": case "cmd":             return 0xFF4CAF50;
            case "json":                                    return 0xFF5C6BC0;
            case "xml":                                     return 0xFFFF7043;
            case "yaml": case "yml":                        return 0xFFCB171E;
            case "sql":                                     return 0xFF336791;
            default:                                        return ACCENT;
        }
    }

    private static boolean fileIconSelfColored(String ext) {
        switch (ext) {
            case "py": case "pyw": case "pyi": return true;
            default: return false;
        }
    }

    // ── File opening ─────────────────────────────────────────────────────────

    private void openFile(DriveFile file, android.widget.ProgressBar spinner) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        java.io.File local = findLocal(ctx, file);
        if (local != null) { openLocal(file, local); return; }

        // Show spinner, hide icon
        if (spinner != null) {
            spinner.setVisibility(View.VISIBLE);
            if (spinner.getParent() instanceof FrameLayout) {
                FrameLayout frame = (FrameLayout) spinner.getParent();
                for (int i = 0; i < frame.getChildCount(); i++) {
                    if (frame.getChildAt(i) instanceof ImageView)
                        frame.getChildAt(i).setVisibility(View.INVISIBLE);
                }
            }
        }
        DriveRepository.getInstance(currentAccount).downloadFile(file,
                new DriveRepository.DownloadCallback() {
                    private void hideSpinner() {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (spinner == null) return;
                            spinner.setVisibility(View.GONE);
                            if (spinner.getParent() instanceof FrameLayout) {
                                FrameLayout frame = (FrameLayout) spinner.getParent();
                                for (int i = 0; i < frame.getChildCount(); i++) {
                                    if (frame.getChildAt(i) instanceof ImageView)
                                        frame.getChildAt(i).setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                    @Override public void onProgress(float p) {}
                    @Override public void onSuccess(java.io.File localFile) {
                        hideSpinner();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (localFile != null && localFile.exists()) {
                                openLocal(file, localFile);
                            } else {
                                Context c = getParentActivity();
                                if (c != null) Toast.makeText(c,
                                        "Error: file does not exist after download", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    @Override public void onError(String msg) {
                        hideSpinner();
                        AndroidUtilities.runOnUIThread(() -> {
                            Context c = getParentActivity();
                            if (c != null) Toast.makeText(c,
                                    "Download failed: " + msg, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private java.io.File findLocal(Context ctx, DriveFile file) {
        java.io.File f = new java.io.File(ctx.getExternalCacheDir(), "drive_uploads/" + file.name);
        if (f.exists()) return f;
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
            new DriveAudioPlayerSheet(ctx, local.getAbsolutePath(), driveFile.name).show();
            return;
        }
        launchViewIntent(ctx, local, driveFile.mimeType);
    }

    private void launchViewIntent(Context ctx, java.io.File file, String mimeType) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri,
                    mimeType != null && !mimeType.isEmpty() ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app available to open this file type", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatDate(long unixSeconds) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                .format(new Date(unixSeconds * 1000L));
    }

    private static String fileExt(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.getDefault()) : "";
    }

    private static int fileIcon(DriveFile file) {
        String ext = fileExt(file.name);
        switch (file.documentType) {
            case DriveFile.TYPE_IMAGE:    return R.drawable.msg_photos;
            case DriveFile.TYPE_AUDIO:    return R.drawable.files_music;
            case DriveFile.TYPE_VIDEO:    return R.drawable.msg_video;
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
            case "sh": case "bash": case "zsh": case "fish":
            case "bat": case "ps1": case "cmd":
                                              return R.drawable.ic_file_shell;
            case "json":                      return R.drawable.ic_file_json;
            case "xml":                       return R.drawable.ic_file_xml;
            case "yaml": case "yml":          return R.drawable.ic_file_yaml;
            case "sql":                       return R.drawable.ic_file_sql;
            case "pdf":
                return R.drawable.msg_sendfile;
            default:
                return R.drawable.msg_sendfile;
        }
    }
}
