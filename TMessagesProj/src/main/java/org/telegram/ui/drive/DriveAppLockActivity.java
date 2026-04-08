/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * Full-screen lock screen shown when the app opens and lock is enabled.
 * Supports PIN, password, and pattern unlock.
 */
public class DriveAppLockActivity extends Activity {

    private static final int ACCENT   = 0xFF1A73E8;
    private static final int BG_PAGE  = 0xFFF8FAFD;
    private static final int TEXT_PRI = 0xFF1F1F1F;
    private static final int TEXT_SEC = 0xFF5F6368;
    private static final int ERROR_C  = 0xFFEA4335;

    private DriveAppLockManager lockManager;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lockManager = DriveAppLockManager.getInstance(this);

        if (!lockManager.shouldShowLockScreen()) {
            finishAndUnlock();
            return;
        }

        int lockType = lockManager.getLockType();
        switch (lockType) {
            case DriveAppLockManager.LOCK_PIN:
                setContentView(buildPinView());
                break;
            case DriveAppLockManager.LOCK_PASSWORD:
                setContentView(buildPasswordView());
                break;
            case DriveAppLockManager.LOCK_PATTERN:
                setContentView(buildPatternView());
                break;
            default:
                finishAndUnlock();
                break;
        }
    }

    @Override
    public void onBackPressed() {
        // Don't allow back to bypass lock - go to home screen instead
        moveTaskToBack(true);
    }

    private void finishAndUnlock() {
        lockManager.setSessionUnlocked(true);
        finish();
        overridePendingTransition(0, 0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PIN VIEW
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPinView() {
        LinearLayout root = createRoot();

        // Lock icon
        root.addView(createLockIcon());

        // Title
        TextView title = new TextView(this);
        title.setText("Enter PIN");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT_PRI);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(-1, -2, 0, dp(16), 0, dp(8)));

        // Status
        statusText = new TextView(this);
        statusText.setText("Enter your PIN to unlock");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTextColor(TEXT_SEC);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, lp(-1, -2, 0, 0, 0, dp(24)));

        // PIN dots display
        final TextView pinDots = new TextView(this);
        pinDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        pinDots.setTextColor(ACCENT);
        pinDots.setGravity(Gravity.CENTER);
        pinDots.setLetterSpacing(0.5f);
        root.addView(pinDots, lp(-1, dp(48), dp(40), 0, dp(40), dp(32)));

        // Number pad
        final StringBuilder pinBuilder = new StringBuilder();
        LinearLayout numPad = buildNumPad(pinBuilder, pinDots);
        root.addView(numPad, lp(-1, -2, dp(32), 0, dp(32), 0));

        return wrapInScroll(root);
    }

    private LinearLayout buildNumPad(StringBuilder pinBuilder, TextView pinDots) {
        LinearLayout numPad = new LinearLayout(this);
        numPad.setOrientation(LinearLayout.VERTICAL);
        numPad.setGravity(Gravity.CENTER);

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"OK", "0", "⌫"}
        };

        final int expectedLen = lockManager.getCredentialLength();

        for (String[] row : keys) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);

            for (String key : row) {
                TextView btn = new TextView(this);
                btn.setText(key);
                boolean isOk = key.equals("OK");
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, key.equals("⌫") ? 20 : isOk ? 16 : 24);
                btn.setTextColor(isOk ? 0xFFFFFFFF : TEXT_PRI);
                btn.setGravity(Gravity.CENTER);
                btn.setTypeface(null, Typeface.BOLD);

                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(28));
                bg.setColor(isOk ? ACCENT : 0xFFFFFFFF);
                btn.setBackground(bg);
                btn.setElevation(dp(1));

                btn.setOnClickListener(v -> {
                    if (isOk) {
                        if (pinBuilder.length() >= 4) {
                            tryUnlock(pinBuilder.toString(), pinBuilder, pinDots);
                        }
                        return;
                    }
                    if (key.equals("⌫")) {
                        if (pinBuilder.length() > 0) {
                            pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                        }
                    } else {
                        if (pinBuilder.length() < 8) {
                            pinBuilder.append(key);
                        }
                    }
                    // Update dots display
                    StringBuilder dots = new StringBuilder();
                    for (int i = 0; i < pinBuilder.length(); i++) dots.append("●");
                    pinDots.setText(dots.toString());

                    // Auto-verify only when PIN length is known and matches exactly
                    if (expectedLen > 0 && pinBuilder.length() == expectedLen) {
                        pinDots.postDelayed(() -> tryUnlock(pinBuilder.toString(), pinBuilder, pinDots), 200);
                    }
                });

                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(72), dp(56));
                btnLp.setMargins(dp(6), dp(4), dp(6), dp(4));
                rowLayout.addView(btn, btnLp);
            }

            numPad.addView(rowLayout, new LinearLayout.LayoutParams(-1, -2));
        }

        return numPad;
    }

    private void tryUnlock(String pin, StringBuilder pinBuilder, TextView pinDots) {
        if (lockManager.verify(pin)) {
            finishAndUnlock();
        } else {
            // If it's exactly 4 digits and wrong, show error. If longer, wait for more input or auto-check.
            statusText.setText("Wrong PIN");
            statusText.setTextColor(ERROR_C);
            pinDots.setTextColor(ERROR_C);
            pinDots.postDelayed(() -> {
                pinBuilder.setLength(0);
                pinDots.setText("");
                pinDots.setTextColor(ACCENT);
                statusText.setText("Enter your PIN to unlock");
                statusText.setTextColor(TEXT_SEC);
            }, 600);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PASSWORD VIEW
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPasswordView() {
        LinearLayout root = createRoot();

        root.addView(createLockIcon());

        TextView title = new TextView(this);
        title.setText("Enter Password");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT_PRI);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(-1, -2, 0, dp(16), 0, dp(8)));

        statusText = new TextView(this);
        statusText.setText("Enter your password to unlock");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTextColor(TEXT_SEC);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, lp(-1, -2, 0, 0, 0, dp(24)));

        // Password input
        EditText input = new EditText(this);
        input.setHint("Password");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(TEXT_PRI);
        input.setHintTextColor(0xFFBDBDBD);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setGravity(Gravity.CENTER);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(12));
        inputBg.setColor(0xFFFFFFFF);
        inputBg.setStroke(dp(1), 0xFFDADCE0);
        input.setBackground(inputBg);
        root.addView(input, lp(-1, -2, dp(40), 0, dp(40), dp(20)));

        // Unlock button
        TextView unlockBtn = new TextView(this);
        unlockBtn.setText("Unlock");
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        unlockBtn.setTypeface(null, Typeface.BOLD);
        unlockBtn.setTextColor(0xFFFFFFFF);
        unlockBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(12));
        btnBg.setColor(ACCENT);
        unlockBtn.setBackground(btnBg);
        unlockBtn.setPadding(dp(24), dp(14), dp(24), dp(14));
        unlockBtn.setOnClickListener(v -> {
            String pw = input.getText().toString();
            if (lockManager.verify(pw)) {
                finishAndUnlock();
            } else {
                statusText.setText("Wrong password");
                statusText.setTextColor(ERROR_C);
                input.setText("");
                input.postDelayed(() -> {
                    statusText.setText("Enter your password to unlock");
                    statusText.setTextColor(TEXT_SEC);
                }, 1500);
            }
        });
        root.addView(unlockBtn, lp(-1, -2, dp(40), 0, dp(40), 0));

        input.setOnEditorActionListener((v, actionId, event) -> {
            unlockBtn.performClick();
            return true;
        });

        // Auto-focus
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        }, 300);

        return wrapInScroll(root);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PATTERN VIEW
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPatternView() {
        LinearLayout root = createRoot();

        root.addView(createLockIcon());

        TextView title = new TextView(this);
        title.setText("Draw Pattern");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(TEXT_PRI);
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(-1, -2, 0, dp(16), 0, dp(8)));

        statusText = new TextView(this);
        statusText.setText("Draw your pattern to unlock");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusText.setTextColor(TEXT_SEC);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, lp(-1, -2, 0, 0, 0, dp(24)));

        // Pattern view
        DrivePatternView patternView = new DrivePatternView(this);
        patternView.setOnPatternListener(pattern -> {
            if (lockManager.verify(pattern)) {
                finishAndUnlock();
            } else {
                statusText.setText("Wrong pattern");
                statusText.setTextColor(ERROR_C);
                patternView.showError();
                patternView.postDelayed(() -> {
                    statusText.setText("Draw your pattern to unlock");
                    statusText.setTextColor(TEXT_SEC);
                }, 1000);
            }
        });
        root.addView(patternView, lp(dp(280), dp(280), 0, 0, 0, 0));
        // Center it
        ((LinearLayout.LayoutParams) patternView.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        return root;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private LinearLayout createRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG_PAGE);
        root.setPadding(dp(24), dp(60), dp(24), dp(40));
        return root;
    }

    private View wrapInScroll(LinearLayout content) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(BG_PAGE);
        sv.setFillViewport(true);
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private View createLockIcon() {
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.msg_secret);
        icon.setColorFilter(ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        icon.setLayoutParams(lp);
        return icon;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
