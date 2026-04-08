/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Bottom sheet for setting up a new app lock (PIN, password, or pattern).
 * Used from DriveSettingsFragment.
 */
public class DriveAppLockSetupSheet extends BottomSheet {

    private static final int ACCENT   = 0xFF1A73E8;
    private static final int TEXT_PRI = 0xFF1F1F1F;
    private static final int TEXT_SEC = 0xFF5F6368;
    private static final int BORDER   = 0xFFDADCE0;

    public interface OnLockSetCallback {
        void onLockSet();
    }

    private final int lockType;
    private final DriveAppLockManager lockManager;
    private final OnLockSetCallback callback;

    // State for two-step confirmation
    private String firstEntry = null;
    private TextView statusText;

    public DriveAppLockSetupSheet(Context context, int lockType, OnLockSetCallback callback) {
        super(context, true);
        this.lockType = lockType;
        this.lockManager = DriveAppLockManager.getInstance(context);
        this.callback = callback;
        buildContent(context);
    }

    private void buildContent(Context context) {
        switch (lockType) {
            case DriveAppLockManager.LOCK_PIN:
                setCustomView(buildPinSetup(context));
                break;
            case DriveAppLockManager.LOCK_PASSWORD:
                setCustomView(buildPasswordSetup(context));
                break;
            case DriveAppLockManager.LOCK_PATTERN:
                setCustomView(buildPatternSetup(context));
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PIN SETUP
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPinSetup(Context context) {
        LinearLayout root = createRoot(context);

        TextView title = makeTitle(context, "Set PIN");
        root.addView(title, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));

        statusText = makeSub(context, "Enter a PIN (4-8 digits)");
        root.addView(statusText, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 20));

        // PIN dots
        final TextView pinDots = new TextView(context);
        pinDots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        pinDots.setTextColor(ACCENT);
        pinDots.setGravity(Gravity.CENTER);
        pinDots.setLetterSpacing(0.5f);
        root.addView(pinDots, LayoutHelper.createLinear(-1, AndroidUtilities.dp(48), 32, 0, 32, 24));

        // Number pad
        final StringBuilder pinBuilder = new StringBuilder();
        LinearLayout numPad = buildNumPad(context, pinBuilder, pinDots);
        root.addView(numPad, LayoutHelper.createLinear(-1, -2, 24, 0, 24, 12));

        // Confirm button
        TextView confirmBtn = makeAccentButton(context, "Confirm");
        confirmBtn.setOnClickListener(v -> {
            String pin = pinBuilder.toString();
            if (pin.length() < 4) {
                statusText.setText("PIN must be at least 4 digits");
                statusText.setTextColor(0xFFEA4335);
                return;
            }
            if (firstEntry == null) {
                firstEntry = pin;
                pinBuilder.setLength(0);
                pinDots.setText("");
                statusText.setText("Confirm your PIN");
                statusText.setTextColor(TEXT_SEC);
            } else {
                if (firstEntry.equals(pin)) {
                    lockManager.enableLock(DriveAppLockManager.LOCK_PIN, pin);
                    if (callback != null) callback.onLockSet();
                    dismiss();
                } else {
                    statusText.setText("PINs don't match. Try again.");
                    statusText.setTextColor(0xFFEA4335);
                    firstEntry = null;
                    pinBuilder.setLength(0);
                    pinDots.setText("");
                    pinDots.postDelayed(() -> {
                        statusText.setText("Enter a PIN (4-8 digits)");
                        statusText.setTextColor(TEXT_SEC);
                    }, 1500);
                }
            }
        });
        root.addView(confirmBtn, LayoutHelper.createLinear(-1, -2, 32, 8, 32, 16));

        return root;
    }

    private LinearLayout buildNumPad(Context context, StringBuilder pinBuilder, TextView pinDots) {
        LinearLayout numPad = new LinearLayout(context);
        numPad.setOrientation(LinearLayout.VERTICAL);
        numPad.setGravity(Gravity.CENTER);

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"", "0", "⌫"}
        };

        for (String[] row : keys) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);

            for (String key : row) {
                if (key.isEmpty()) {
                    View spacer = new View(context);
                    rowLayout.addView(spacer, new LinearLayout.LayoutParams(
                            AndroidUtilities.dp(64), AndroidUtilities.dp(48)));
                    continue;
                }

                TextView btn = new TextView(context);
                btn.setText(key);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, key.equals("⌫") ? 18 : 22);
                btn.setTextColor(TEXT_PRI);
                btn.setGravity(Gravity.CENTER);
                btn.setTypeface(null, Typeface.BOLD);

                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(24));
                bg.setColor(0xFFF0F0F0);
                btn.setBackground(bg);

                btn.setOnClickListener(v -> {
                    if (key.equals("⌫")) {
                        if (pinBuilder.length() > 0) {
                            pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                        }
                    } else {
                        if (pinBuilder.length() < 8) {
                            pinBuilder.append(key);
                        }
                    }
                    StringBuilder dots = new StringBuilder();
                    for (int i = 0; i < pinBuilder.length(); i++) dots.append("●");
                    pinDots.setText(dots.toString());
                });

                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        AndroidUtilities.dp(64), AndroidUtilities.dp(48));
                btnLp.setMargins(AndroidUtilities.dp(5), AndroidUtilities.dp(3),
                        AndroidUtilities.dp(5), AndroidUtilities.dp(3));
                rowLayout.addView(btn, btnLp);
            }

            numPad.addView(rowLayout, new LinearLayout.LayoutParams(-1, -2));
        }

        return numPad;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PASSWORD SETUP
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPasswordSetup(Context context) {
        LinearLayout root = createRoot(context);

        root.addView(makeTitle(context, "Set Password"),
                LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));

        statusText = makeSub(context, "Enter a password (4+ characters)");
        root.addView(statusText, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 20));

        EditText input = makeInput(context, "Password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(input, LayoutHelper.createLinear(-1, -2, 8, 0, 8, 16));

        TextView confirmBtn = makeAccentButton(context, "Confirm");
        confirmBtn.setOnClickListener(v -> {
            String pw = input.getText().toString();
            if (pw.length() < 4) {
                statusText.setText("Password must be at least 4 characters");
                statusText.setTextColor(0xFFEA4335);
                return;
            }
            if (firstEntry == null) {
                firstEntry = pw;
                input.setText("");
                statusText.setText("Confirm your password");
                statusText.setTextColor(TEXT_SEC);
            } else {
                if (firstEntry.equals(pw)) {
                    lockManager.enableLock(DriveAppLockManager.LOCK_PASSWORD, pw);
                    if (callback != null) callback.onLockSet();
                    dismiss();
                } else {
                    statusText.setText("Passwords don't match. Try again.");
                    statusText.setTextColor(0xFFEA4335);
                    firstEntry = null;
                    input.setText("");
                    input.postDelayed(() -> {
                        statusText.setText("Enter a password (4+ characters)");
                        statusText.setTextColor(TEXT_SEC);
                    }, 1500);
                }
            }
        });
        root.addView(confirmBtn, LayoutHelper.createLinear(-1, -2, 8, 0, 8, 16));

        // Auto show keyboard
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
        }, 300);

        return root;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PATTERN SETUP
    // ═════════════════════════════════════════════════════════════════════════

    private View buildPatternSetup(Context context) {
        LinearLayout root = createRoot(context);

        root.addView(makeTitle(context, "Set Pattern"),
                LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));

        statusText = makeSub(context, "Draw a pattern (connect 4+ dots)");
        root.addView(statusText, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));

        DrivePatternView patternView = new DrivePatternView(context);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(260), AndroidUtilities.dp(260));
        pvLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(patternView, pvLp);

        patternView.setOnPatternListener(pattern -> {
            if (firstEntry == null) {
                firstEntry = pattern;
                statusText.setText("Draw the pattern again to confirm");
                statusText.setTextColor(TEXT_SEC);
                patternView.postDelayed(patternView::reset, 400);
            } else {
                if (firstEntry.equals(pattern)) {
                    lockManager.enableLock(DriveAppLockManager.LOCK_PATTERN, pattern);
                    if (callback != null) callback.onLockSet();
                    dismiss();
                } else {
                    statusText.setText("Patterns don't match. Try again.");
                    statusText.setTextColor(0xFFEA4335);
                    firstEntry = null;
                    patternView.showError();
                    patternView.postDelayed(() -> {
                        statusText.setText("Draw a pattern (connect 4+ dots)");
                        statusText.setTextColor(TEXT_SEC);
                    }, 1500);
                }
            }
        });

        return root;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private LinearLayout createRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16),
                AndroidUtilities.dp(20), AndroidUtilities.dp(20));

        // Drag handle
        FrameLayout handleWrap = new FrameLayout(context);
        View handle = new View(context);
        GradientDrawable hs = new GradientDrawable();
        hs.setColor(0xFFDADCE0);
        hs.setCornerRadius(AndroidUtilities.dp(2.5f));
        handle.setBackground(hs);
        handleWrap.addView(handle, new FrameLayout.LayoutParams(
                AndroidUtilities.dp(40), AndroidUtilities.dp(5), Gravity.CENTER_HORIZONTAL));
        root.addView(handleWrap, LayoutHelper.createLinear(-1, 20, 0, 0, 0, 12));

        return root;
    }

    private TextView makeTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(TEXT_PRI);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView makeSub(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(TEXT_SEC);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private EditText makeInput(Context ctx, String hint, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setTextColor(TEXT_PRI);
        et.setHintTextColor(0xFFBDBDBD);
        et.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        et.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(AndroidUtilities.dp(1), BORDER);
        et.setBackground(bg);
        return et;
    }

    private TextView makeAccentButton(Context ctx, String text) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(ACCENT);
        btn.setBackground(bg);
        btn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        return btn;
    }
}
