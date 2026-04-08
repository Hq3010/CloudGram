/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.drive.DriveFile;
import org.telegram.messenger.drive.DriveRepository;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriveStarredFragment extends BaseFragment {

    private static final int PRIMARY_COLOR = 0xFF1A73E8;

    private RecyclerListView recyclerView;
    private StarredAdapter adapter;
    private TextView emptyView;

    private final List<DriveFile> starredFiles = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        DriveRepository.getInstance(currentAccount).getStarredFiles(files -> {
            starredFiles.clear();
            starredFiles.addAll(files);
            if (adapter != null) adapter.notifyDataSetChanged();
            updateEmptyState();
        });
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("Starred");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;

        recyclerView = new RecyclerListView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new StarredAdapter();
        recyclerView.setAdapter(adapter);
        recyclerView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < starredFiles.size()) {
                showFileOptions(context, starredFiles.get(position));
            }
            return true;
        });
        frame.addView(recyclerView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("No starred files yet");
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        frame.addView(emptyView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        updateEmptyState();
        return fragmentView;
    }

    private void showFileOptions(Context context, DriveFile file) {
        new AlertDialog.Builder(context)
                .setTitle(file.name)
                .setItems(new CharSequence[]{"Unstar", "Delete"},
                        (dialog, which) -> {
                            if (which == 0) {
                                DriveRepository.getInstance(currentAccount).setFileStarred(
                                        file, false,
                                        new DriveRepository.SimpleCallback() {
                                            @Override public void onSuccess() {
                                                starredFiles.remove(file);
                                                if (adapter != null) adapter.notifyDataSetChanged();
                                                updateEmptyState();
                                            }
                                            @Override public void onError(String msg) { /* ignore */ }
                                        });
                            } else if (which == 1) {
                                confirmDelete(context, file);
                            }
                        })
                .show();
    }

    private void confirmDelete(Context context, DriveFile file) {
        new AlertDialog.Builder(context)
                .setTitle("Delete")
                .setMessage("Delete this file?")
                .setPositiveButton("Delete", (dialog, which) ->
                        DriveRepository.getInstance(currentAccount).deleteFile(file,
                                new DriveRepository.SimpleCallback() {
                                    @Override public void onSuccess() {
                                        starredFiles.remove(file);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        updateEmptyState();
                                    }
                                    @Override public void onError(String msg) { /* ignore */ }
                                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateEmptyState() {
        if (emptyView == null) return;
        boolean isEmpty = starredFiles.isEmpty();
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private static int fileTypeColor(int type) {
        switch (type) {
            case DriveFile.TYPE_IMAGE:    return 0xFF34A853;
            case DriveFile.TYPE_VIDEO:   return 0xFFFBBC05;
            case DriveFile.TYPE_AUDIO:   return 0xFF8B5CF6;
            case DriveFile.TYPE_DOCUMENT: return 0xFF9E9E9E;
            default:                     return PRIMARY_COLOR;
        }
    }

    private static String formatDate(long unixSeconds) {
        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(unixSeconds * 1000L));
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class StarredAdapter extends RecyclerView.Adapter<StarredAdapter.ViewHolder> {

        @Override
        public int getItemCount() {
            return starredFiles.size();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            DriveFile file = starredFiles.get(position);
            holder.name.setText(file.name);
            holder.date.setText(formatDate(file.uploadedAt));
            holder.icon.setColorFilter(fileTypeColor(file.documentType), PorterDuff.Mode.SRC_IN);
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView date;

            ViewHolder(Context ctx) {
                super(new LinearLayout(ctx));
                LinearLayout row = (LinearLayout) itemView;
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
                row.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));

                icon = new ImageView(ctx);
                icon.setImageResource(R.drawable.settings_folders);
                row.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));

                LinearLayout info = new LinearLayout(ctx);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(AndroidUtilities.dp(12), 0, 0, 0);

                name = new TextView(ctx);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                name.setTypeface(name.getTypeface(), Typeface.NORMAL);
                name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                name.setSingleLine();
                name.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(name, LayoutHelper.createLinear(
                        LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

                date = new TextView(ctx);
                date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                date.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                info.addView(date, LayoutHelper.createLinear(
                        LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

                row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT,
                        1f, Gravity.CENTER_VERTICAL));
            }
        }
    }
}
