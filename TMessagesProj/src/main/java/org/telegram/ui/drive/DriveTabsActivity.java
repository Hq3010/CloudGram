/*
 * TeleDrive — file storage app built on top of Telegram infrastructure.
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.ui.drive;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ViewPagerActivity;

public class DriveTabsActivity extends ViewPagerActivity {

    private static final int PRIMARY_COLOR    = 0xFF1A73E8;
    private static final int UNSELECTED_COLOR = 0xFF9AA0A6;
    private static final int SELECTED_PILL_BG = 0xFFE8F0FE;
    private static final int BORDER_C         = 0xFFDADCE0;

    private final ImageView[]   tabIcons    = new ImageView[3];
    private final TextView[]    tabLabels   = new TextView[3];
    private final FrameLayout[] tabPillBgs  = new FrameLayout[3];

    private static final int[] TAB_ICON_RES = {
        R.drawable.filled_chatlist2,
        R.drawable.settings_folders,
        R.drawable.filled_profile_settings
    };

    private static final String[] TAB_NAMES = {"Home", "Files", "Settings"};

    @Override
    protected int getStartPosition() {
        return 0;
    }

    @Override
    protected int getFragmentsCount() {
        return 3;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        BaseFragment f;
        switch (position) {
            case 1:  f = new DriveFilesFragment();    break;
            case 2:  f = new DriveSettingsFragment(); break;
            default: f = new DriveHomeFragment();     break;
        }
        f.setCurrentAccount(currentAccount);
        return f;
    }

    @Override
    public void onResume() {
        super.onResume();
        Context ctx = getParentActivity();
        if (ctx != null) {
            DriveAppLockManager lockMgr = DriveAppLockManager.getInstance(ctx);
            if (lockMgr.shouldShowLockScreen()) {
                ctx.startActivity(new Intent(ctx, DriveAppLockActivity.class));
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Context ctx = getParentActivity();
        if (ctx != null) {
            DriveAppLockManager lockMgr = DriveAppLockManager.getInstance(ctx);
            if (lockMgr.isLockEnabled()) {
                lockMgr.lockSession();
            }
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);

        final int TAB_BAR_DP = 56;

        // Build bottom tab bar (white bg)
        LinearLayout tabBar = new LinearLayout(context);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFFFFFFFF);

        // Thin 1dp top border
        View divider = new View(context);
        divider.setBackgroundColor(BORDER_C);

        for (int i = 0; i < 3; i++) {
            LinearLayout tabItem = new LinearLayout(context);
            tabItem.setOrientation(LinearLayout.VERTICAL);
            tabItem.setGravity(Gravity.CENTER);
            tabItem.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(2));

            // Pill highlight behind icon (visible when selected)
            FrameLayout pillWrapper = new FrameLayout(context);
            FrameLayout pillBg = new FrameLayout(context);
            android.graphics.drawable.GradientDrawable pillDrawable = new android.graphics.drawable.GradientDrawable();
            pillDrawable.setColor(0xFFE8F0FE);
            pillDrawable.setCornerRadius(AndroidUtilities.dp(16));
            pillBg.setBackground(pillDrawable);
            pillBg.setVisibility(View.GONE);
            pillWrapper.addView(pillBg, new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(64), AndroidUtilities.dp(28)));
            tabPillBgs[i] = pillBg;

            ImageView icon = new ImageView(context);
            icon.setImageResource(TAB_ICON_RES[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            pillWrapper.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
            tabItem.addView(pillWrapper, LayoutHelper.createLinear(64, 28, Gravity.CENTER_HORIZONTAL));
            tabIcons[i] = icon;

            TextView label = new TextView(context);
            label.setText(TAB_NAMES[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setGravity(Gravity.CENTER);
            tabItem.addView(label, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));
            tabLabels[i] = label;

            final int pos = i;
            tabItem.setOnClickListener(v -> {
                viewPager.scrollToPosition(pos);
                updateTabSelection(pos);
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            tabBar.addView(tabItem, lp);
        }

        // Add divider and tabBar to contentView; exact heights set after insets are known
        contentView.addView(divider);
        contentView.addView(tabBar);
        updateTabSelection(0);

        // Apply window insets so the tab bar sits above the system navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int tabBarHeight   = AndroidUtilities.dp(TAB_BAR_DP);
            int totalBarHeight = tabBarHeight + navBarHeight;

            // Tab bar: full width, sits at bottom, height = tab area + nav bar inset
            FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, totalBarHeight);
            tabLp.gravity = Gravity.BOTTOM;
            tabBar.setLayoutParams(tabLp);
            // Extra bottom padding inside tab bar so icons sit in the 56dp zone
            tabBar.setPadding(0, 0, 0, navBarHeight);

            // Divider sits right above the tab bar
            FrameLayout.LayoutParams divLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            divLp.gravity = Gravity.BOTTOM;
            divLp.bottomMargin = totalBarHeight;
            divider.setLayoutParams(divLp);

            // Push viewPager content up by the full bar height
            viewPager.setPadding(0, 0, 0, totalBarHeight);
            viewPager.setClipToPadding(false);

            return insets;
        });

        // Request insets immediately (handles cases where listener fires before layout)
        ViewCompat.requestApplyInsets(contentView);

        return fragmentView = contentView;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        updateTabSelection(viewPager.currentPosition);
    }

    public void updateTabSelection(int tab) {
        for (int i = 0; i < 3; i++) {
            boolean selected = (i == tab);
            int color = selected ? PRIMARY_COLOR : UNSELECTED_COLOR;
            if (tabIcons[i] != null) {
                tabIcons[i].setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
            if (tabLabels[i] != null) {
                tabLabels[i].setTextColor(color);
                tabLabels[i].setTypeface(null,
                        selected ? android.graphics.Typeface.BOLD
                                 : android.graphics.Typeface.NORMAL);
            }
            if (tabPillBgs[i] != null) {
                if (selected) {
                    android.graphics.drawable.GradientDrawable pillD =
                            new android.graphics.drawable.GradientDrawable();
                    pillD.setColor(SELECTED_PILL_BG);
                    pillD.setCornerRadius(AndroidUtilities.dp(8));
                    tabPillBgs[i].setBackground(pillD);
                    tabPillBgs[i].setVisibility(View.VISIBLE);
                } else {
                    tabPillBgs[i].setVisibility(View.GONE);
                }
            }
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        // Forward to the currently visible inner fragment
        FragmentState state = fragmentsArr.get(viewPager.currentPosition);
        if (state != null && state.fragment != null) {
            state.fragment.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        FragmentState state = fragmentsArr.get(viewPager.currentPosition);
        if (state != null && state.fragment instanceof DriveFilesFragment) {
            DriveFilesFragment filesFragment = (DriveFilesFragment) state.fragment;
            if (filesFragment.navigateUp()) {
                return true;
            }
        }
        return super.onBackPressed(invoked);
    }
}
