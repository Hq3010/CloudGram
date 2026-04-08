/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.PaintDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Modern bottom-sheet audio player with playlist, repeat, shuffle,
 * and background playback support.
 */
public class DriveAudioPlayerSheet extends BottomSheet {

    // ── Design tokens ────────────────────────────────────────────────────────
    private static final int COLOR_ACCENT    = 0xFF1A73E8;
    private static final int COLOR_BG_TOP    = 0xFFE8F0FE;
    private static final int COLOR_BG_BOT    = 0xFFFFFFFF;
    private static final int COLOR_TEXT_PRI  = 0xFF1F1F1F;
    private static final int COLOR_TEXT_SEC  = 0xFF5F6368;
    private static final int COLOR_ART_BG    = 0xFFD2E3FC;
    private static final int COLOR_INACTIVE  = 0xFF9AA0A6;
    private static final int COLOR_GREEN     = 0xFF34A853;

    // ── Repeat modes ─────────────────────────────────────────────────────────
    private static final int REPEAT_OFF  = 0;
    private static final int REPEAT_ALL  = 1;
    private static final int REPEAT_ONE  = 2;

    // ── Static player holder (survives sheet dismiss for background play) ────
    private static MediaPlayer sMediaPlayer;
    private static boolean     sBackgroundPlay = false;
    private static int         sCurrentIndex   = -1;
    private static List<String> sPlaylistPaths;
    private static List<String> sPlaylistNames;

    // ── Instance state ───────────────────────────────────────────────────────
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<String> playlistPaths;
    private List<String> playlistNames;
    private int currentIndex;

    private int     repeatMode = REPEAT_OFF;
    private boolean shuffleOn  = false;
    private List<Integer> shuffleOrder;

    private SeekBar   seekBar;
    private TextView  currentTimeView;
    private TextView  totalTimeView;
    private ImageView playPauseBtn;
    private ImageView prevBtn;
    private ImageView nextBtn;
    private ImageView repeatBtn;
    private ImageView shuffleBtn;
    private TextView  titleView;
    private TextView  subtitleView;
    private TextView  musicNote;
    private boolean   prepared = false;
    private boolean   playing  = false;
    private boolean   userSeeking = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (sMediaPlayer != null && playing && !userSeeking) {
                tickProgress();
                handler.postDelayed(this, 250);
            }
        }
    };

    // ── Single-file constructor (backward compat) ────────────────────────────
    public DriveAudioPlayerSheet(Context context, String filePath, String fileName) {
        this(context,
                new ArrayList<>(Collections.singletonList(filePath)),
                new ArrayList<>(Collections.singletonList(fileName)),
                0);
    }

    // ── Playlist constructor ─────────────────────────────────────────────────
    public DriveAudioPlayerSheet(Context context,
                                  List<String> paths, List<String> names, int startIndex) {
        super(context, true);
        this.playlistPaths = new ArrayList<>(paths);
        this.playlistNames = new ArrayList<>(names);
        this.currentIndex  = startIndex;
        sPlaylistPaths = this.playlistPaths;
        sPlaylistNames = this.playlistNames;
        sCurrentIndex  = startIndex;
        buildShuffleOrder();
        buildContent(context);
        initPlayer();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI
    // ═════════════════════════════════════════════════════════════════════════

    private void buildContent(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12),
                AndroidUtilities.dp(24), AndroidUtilities.dp(28));

        // Gradient background
        PaintDrawable gradient = new PaintDrawable();
        gradient.setShape(new RectShape());
        gradient.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int width, int height) {
                return new LinearGradient(0, 0, 0, height,
                        COLOR_BG_TOP, COLOR_BG_BOT, Shader.TileMode.CLAMP);
            }
        });
        root.setBackground(gradient);

        // ── Drag handle ──────────────────────────────────────────────────────
        FrameLayout handleWrap = new FrameLayout(context);
        View handle = new View(context);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(0xFFBBD4F8);
        handleShape.setCornerRadius(AndroidUtilities.dp(2.5f));
        handle.setBackground(handleShape);
        handleWrap.addView(handle, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(40), AndroidUtilities.dp(5), Gravity.CENTER_HORIZONTAL));
        root.addView(handleWrap, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 20, 0, 0, 0, 16));

        // ── Album art ────────────────────────────────────────────────────────
        FrameLayout artFrame = new FrameLayout(context);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(AndroidUtilities.dp(24));
        artBg.setColor(COLOR_ART_BG);
        artFrame.setBackground(artBg);
        artFrame.setElevation(AndroidUtilities.dp(8));

        musicNote = new TextView(context);
        musicNote.setText("♫");
        musicNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        musicNote.setTextColor(COLOR_ACCENT);
        musicNote.setGravity(Gravity.CENTER);
        artFrame.addView(musicNote, new FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(artFrame, LayoutHelper.createLinear(
                160, 160, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));

        // ── Song title ───────────────────────────────────────────────────────
        titleView = new TextView(context);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(COLOR_TEXT_PRI);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // ── Subtitle (track N of M) ─────────────────────────────────────────
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setTextColor(COLOR_TEXT_SEC);
        subtitleView.setGravity(Gravity.CENTER);
        root.addView(subtitleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // ── SeekBar ──────────────────────────────────────────────────────────
        seekBar = new SeekBar(context);
        seekBar.getProgressDrawable().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        seekBar.getThumb().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
        seekBar.setPadding(0, 0, 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) updateTimeViews(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (prepared && sMediaPlayer != null) {
                    sMediaPlayer.seekTo(sb.getProgress());
                }
            }
        });
        root.addView(seekBar, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

        // ── Time row ─────────────────────────────────────────────────────────
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        currentTimeView = new TextView(context);
        currentTimeView.setText("0:00");
        currentTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        currentTimeView.setTextColor(COLOR_TEXT_SEC);
        timeRow.addView(currentTimeView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        timeRow.addView(new View(context), LayoutHelper.createLinear(0, 0, 1f));
        totalTimeView = new TextView(context);
        totalTimeView.setText("0:00");
        totalTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        totalTimeView.setTextColor(COLOR_TEXT_SEC);
        timeRow.addView(totalTimeView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(timeRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        // ── Main controls: prev | -15s | play/pause | +15s | next ──────────
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        // Previous
        prevBtn = makeIconBtn(context, R.drawable.ic_drive_back, COLOR_TEXT_PRI, 28);
        prevBtn.setRotation(180);
        prevBtn.setAlpha(playlistPaths.size() > 1 ? 1f : 0.35f);
        prevBtn.setOnClickListener(v -> playPrev());
        controls.addView(prevBtn, LayoutHelper.createLinear(
                44, 44, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        // -15s
        TextView skipBack = makeSkipBtn(context, "−15");
        skipBack.setOnClickListener(v -> seekRelative(-15000));
        controls.addView(skipBack, LayoutHelper.createLinear(
                48, 36, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        // Play/Pause — large circle
        playPauseBtn = new ImageView(context);
        playPauseBtn.setImageResource(R.drawable.ic_drive_play);
        playPauseBtn.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        playPauseBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPauseBtn.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18),
                AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(COLOR_ACCENT);
        playPauseBtn.setBackground(playBg);
        playPauseBtn.setElevation(AndroidUtilities.dp(4));
        playPauseBtn.setOnClickListener(v -> togglePlay());
        controls.addView(playPauseBtn, LayoutHelper.createLinear(
                72, 72, Gravity.CENTER_VERTICAL));

        // +15s
        TextView skipFwd = makeSkipBtn(context, "+15");
        skipFwd.setOnClickListener(v -> seekRelative(15000));
        controls.addView(skipFwd, LayoutHelper.createLinear(
                48, 36, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        // Next
        nextBtn = makeIconBtn(context, R.drawable.ic_drive_back, COLOR_TEXT_PRI, 28);
        nextBtn.setAlpha(playlistPaths.size() > 1 ? 1f : 0.35f);
        nextBtn.setOnClickListener(v -> playNext());
        controls.addView(nextBtn, LayoutHelper.createLinear(
                44, 44, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        root.addView(controls, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        // ── Bottom row: shuffle | repeat | bg-play toggle ────────────────────
        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER);

        // Shuffle
        shuffleBtn = makeIconBtn(context, R.drawable.player_new_shuffle, COLOR_INACTIVE, 22);
        shuffleBtn.setOnClickListener(v -> toggleShuffle());
        bottomRow.addView(shuffleBtn, LayoutHelper.createLinear(
                40, 40, Gravity.CENTER_VERTICAL, 0, 0, 24, 0));

        // Repeat
        repeatBtn = makeIconBtn(context, R.drawable.player_new_repeatall, COLOR_INACTIVE, 22);
        repeatBtn.setOnClickListener(v -> cycleRepeat());
        bottomRow.addView(repeatBtn, LayoutHelper.createLinear(
                40, 40, Gravity.CENTER_VERTICAL, 0, 0, 24, 0));

        // Background play toggle
        TextView bgPlayBtn = new TextView(context);
        bgPlayBtn.setText(sBackgroundPlay ? "BG Play: On" : "BG Play: Off");
        bgPlayBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bgPlayBtn.setTypeface(null, Typeface.BOLD);
        bgPlayBtn.setTextColor(sBackgroundPlay ? COLOR_ACCENT : COLOR_INACTIVE);
        bgPlayBtn.setGravity(Gravity.CENTER);
        GradientDrawable bgPlayBg = new GradientDrawable();
        bgPlayBg.setCornerRadius(AndroidUtilities.dp(16));
        bgPlayBg.setColor(sBackgroundPlay ? 0xFFE8F0FE : 0xFFF0F0F0);
        bgPlayBtn.setBackground(bgPlayBg);
        bgPlayBtn.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8),
                AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        bgPlayBtn.setOnClickListener(v -> {
            sBackgroundPlay = !sBackgroundPlay;
            bgPlayBtn.setText(sBackgroundPlay ? "BG Play: On" : "BG Play: Off");
            bgPlayBtn.setTextColor(sBackgroundPlay ? COLOR_ACCENT : COLOR_INACTIVE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(16));
            bg.setColor(sBackgroundPlay ? 0xFFE8F0FE : 0xFFF0F0F0);
            bgPlayBtn.setBackground(bg);
        });
        bottomRow.addView(bgPlayBtn);

        root.addView(bottomRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateTrackInfo();
        setCustomView(root);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PLAYER ENGINE
    // ═════════════════════════════════════════════════════════════════════════

    private void initPlayer() {
        releasePlayer();
        try {
            sMediaPlayer = new MediaPlayer();
            sMediaPlayer.setDataSource(playlistPaths.get(currentIndex));
            sMediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                seekBar.setMax(mp.getDuration());
                if (totalTimeView != null)
                    totalTimeView.setText(fmt(mp.getDuration()));
                updateTimeViews(0);
                // Auto-play
                sMediaPlayer.start();
                playing = true;
                handler.post(tickRunnable);
                if (playPauseBtn != null)
                    playPauseBtn.setImageResource(R.drawable.ic_drive_pause);
            });
            sMediaPlayer.setOnCompletionListener(mp -> onTrackCompleted());
            sMediaPlayer.prepareAsync();
        } catch (Exception e) {
            FileLog.e("[DriveAudio] init error", e);
        }
    }

    private void onTrackCompleted() {
        if (repeatMode == REPEAT_ONE) {
            // Repeat same track
            if (sMediaPlayer != null) {
                sMediaPlayer.seekTo(0);
                sMediaPlayer.start();
                if (seekBar != null) seekBar.setProgress(0);
                updateTimeViews(0);
            }
            return;
        }
        // Try next track
        int nextIdx = getNextIndex();
        if (nextIdx >= 0) {
            currentIndex = nextIdx;
            sCurrentIndex = nextIdx;
            prepared = false;
            updateTrackInfo();
            initPlayer();
        } else {
            // Playlist ended
            playing = false;
            handler.removeCallbacks(tickRunnable);
            if (playPauseBtn != null) playPauseBtn.setImageResource(R.drawable.ic_drive_play);
            if (seekBar != null) seekBar.setProgress(0);
            updateTimeViews(0);
        }
    }

    private void togglePlay() {
        if (!prepared || sMediaPlayer == null) return;
        if (playing) {
            sMediaPlayer.pause();
            playing = false;
            handler.removeCallbacks(tickRunnable);
            if (playPauseBtn != null) playPauseBtn.setImageResource(R.drawable.ic_drive_play);
        } else {
            sMediaPlayer.start();
            playing = true;
            handler.post(tickRunnable);
            if (playPauseBtn != null) playPauseBtn.setImageResource(R.drawable.ic_drive_pause);
        }
    }

    private void playNext() {
        if (playlistPaths.size() <= 1) return;
        int nextIdx = getNextIndex();
        if (nextIdx < 0) nextIdx = 0; // wrap around
        currentIndex = nextIdx;
        sCurrentIndex = nextIdx;
        prepared = false;
        updateTrackInfo();
        initPlayer();
    }

    private void playPrev() {
        if (playlistPaths.size() <= 1) return;
        // If > 3 sec into track, restart it; else go to previous
        if (sMediaPlayer != null && prepared && sMediaPlayer.getCurrentPosition() > 3000) {
            sMediaPlayer.seekTo(0);
            if (seekBar != null) seekBar.setProgress(0);
            updateTimeViews(0);
            return;
        }
        int prevIdx = getPrevIndex();
        if (prevIdx < 0) prevIdx = playlistPaths.size() - 1;
        currentIndex = prevIdx;
        sCurrentIndex = prevIdx;
        prepared = false;
        updateTrackInfo();
        initPlayer();
    }

    private void seekRelative(int deltaMs) {
        if (!prepared || sMediaPlayer == null) return;
        int dur = sMediaPlayer.getDuration();
        int pos = Math.max(0, Math.min(dur, sMediaPlayer.getCurrentPosition() + deltaMs));
        sMediaPlayer.seekTo(pos);
        if (seekBar != null) seekBar.setProgress(pos);
        updateTimeViews(pos);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SHUFFLE / REPEAT
    // ═════════════════════════════════════════════════════════════════════════

    private void toggleShuffle() {
        shuffleOn = !shuffleOn;
        if (shuffleBtn != null) {
            shuffleBtn.setColorFilter(shuffleOn ? COLOR_ACCENT : COLOR_INACTIVE,
                    PorterDuff.Mode.SRC_IN);
        }
        if (shuffleOn) buildShuffleOrder();
    }

    private void cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        if (repeatBtn != null) {
            switch (repeatMode) {
                case REPEAT_OFF:
                    repeatBtn.setImageResource(R.drawable.player_new_repeatall);
                    repeatBtn.setColorFilter(COLOR_INACTIVE, PorterDuff.Mode.SRC_IN);
                    break;
                case REPEAT_ALL:
                    repeatBtn.setImageResource(R.drawable.player_new_repeatall);
                    repeatBtn.setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
                    break;
                case REPEAT_ONE:
                    repeatBtn.setImageResource(R.drawable.player_new_repeatone);
                    repeatBtn.setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_IN);
                    break;
            }
        }
    }

    private void buildShuffleOrder() {
        shuffleOrder = new ArrayList<>();
        for (int i = 0; i < playlistPaths.size(); i++) shuffleOrder.add(i);
        Collections.shuffle(shuffleOrder);
    }

    private int getNextIndex() {
        if (shuffleOn) {
            int pos = shuffleOrder.indexOf(currentIndex);
            int next = (pos + 1) % shuffleOrder.size();
            if (next == 0 && repeatMode == REPEAT_OFF) return -1;
            return shuffleOrder.get(next);
        }
        int next = currentIndex + 1;
        if (next >= playlistPaths.size()) {
            if (repeatMode == REPEAT_ALL) return 0;
            return -1;
        }
        return next;
    }

    private int getPrevIndex() {
        if (shuffleOn) {
            int pos = shuffleOrder.indexOf(currentIndex);
            int prev = (pos - 1 + shuffleOrder.size()) % shuffleOrder.size();
            return shuffleOrder.get(prev);
        }
        return currentIndex - 1;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private void updateTrackInfo() {
        if (currentIndex < 0 || currentIndex >= playlistNames.size()) return;
        String name = playlistNames.get(currentIndex);
        String display = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.')) : name;
        if (titleView != null) titleView.setText(display);
        if (subtitleView != null) {
            if (playlistPaths.size() > 1) {
                subtitleView.setText("Track " + (currentIndex + 1) + " / " + playlistPaths.size());
            } else {
                subtitleView.setText("CloudGram Audio");
            }
        }
    }

    private void tickProgress() {
        if (sMediaPlayer == null) return;
        try {
            int pos = sMediaPlayer.getCurrentPosition();
            if (seekBar != null) seekBar.setProgress(pos);
            updateTimeViews(pos);
        } catch (Exception ignored) {}
    }

    private void updateTimeViews(int posMs) {
        if (currentTimeView != null) currentTimeView.setText(fmt(posMs));
        if (totalTimeView != null && sMediaPlayer != null && prepared)
            totalTimeView.setText(fmt(sMediaPlayer.getDuration()));
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    private ImageView makeIconBtn(Context ctx, int resId, int color, int iconDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(resId);
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8),
                AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        return iv;
    }

    private TextView makeSkipBtn(Context ctx, String label) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(COLOR_TEXT_PRI);
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    private void releasePlayer() {
        handler.removeCallbacks(tickRunnable);
        if (sMediaPlayer != null) {
            try {
                if (playing) sMediaPlayer.stop();
                sMediaPlayer.release();
            } catch (Exception ignored) {}
            sMediaPlayer = null;
        }
        prepared = false;
        playing = false;
    }

    /** Stop static player from outside (e.g. when app exits). */
    public static void stopBackgroundPlayback() {
        if (sMediaPlayer != null) {
            try {
                sMediaPlayer.stop();
                sMediaPlayer.release();
            } catch (Exception ignored) {}
            sMediaPlayer = null;
        }
        sBackgroundPlay = false;
    }

    @Override
    public void dismissInternal() {
        handler.removeCallbacks(tickRunnable);
        if (!sBackgroundPlay) {
            // Not background play → stop everything
            releasePlayer();
        }
        // If background play enabled, keep sMediaPlayer alive
        super.dismissInternal();
    }
}
