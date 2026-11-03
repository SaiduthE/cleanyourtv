package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Launcher settings > Top bar buttons > Per button: one row for each top-bar button (Settings, Inputs, Discover,
 * VPN) with its style on the right (Icons, Text, Icons + text). OK opens the same small popup list as the settings
 * rows. A pick is saved at once with the change count, so Home rebuilds when it resumes.
 */
public final class TopBarButtonsActivity extends Activity {

    private static final int CARD_WIDTH_DP = 326;
    private static final int MOVE_MS = 180;

    private Config config;
    private Map<TopButtonStyle.Button, String> styles;
    private FrameLayout root;
    private FrameLayout card;
    private LinearLayout rows;
    private final SettingRowView[] views = new SettingRowView[TopButtonStyle.Button.values().length];
    private OptionPopup popup;
    private final Set<Integer> swallowUp = new HashSet<>();
    private boolean closing;
    private boolean rtl;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    /** The row value / popup text of a style: Icons, Text, Icons + text, or Mixed. */
    static String styleLabel(Context ctx, String style) {
        if (TopButtonStyle.ICON.equals(style)) return ctx.getString(R.string.ls_tbb_icons);
        if (TopButtonStyle.TEXT.equals(style)) return ctx.getString(R.string.ls_tbb_text);
        if (TopButtonStyle.BOTH.equals(style)) return ctx.getString(R.string.ls_tbb_both);
        return ctx.getString(R.string.ls_tbb_mixed);
    }

    private String buttonLabel(TopButtonStyle.Button button) {
        switch (button) {
            case SETTINGS: return getString(R.string.settings);
            case INPUTS: return getString(R.string.inputs);
            case DISCOVER: return getString(R.string.discover);
            default: return getString(R.string.vpn);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = Config.load(this);
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        styles = TopButtonStyler.load(this, config);
        PanelRows.fullScreen(this);
        buildUi();
        setContentView(root);
        views[0].requestFocus();
        int ms = anim(200);
        if (ms > 0) {
            card.setAlpha(0f);
            card.setTranslationX(PanelRows.dp(this, 24));
            card.animate().alpha(1f).translationX(0f).setDuration(ms)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private int anim(int base) {
        return SettingsModel.duration(config.animations, config.reducedMotion, base);
    }

    private void buildUi() {
        PanelTheme theme = PanelTheme.get(this);
        root = new FrameLayout(this);
        if (rtl) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        View scrim = new View(this);
        scrim.setBackgroundColor(PanelRows.SCRIM);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        card = new FrameLayout(this);
        card.setBackground(PanelRows.cardBackground(this));
        card.setElevation(PanelRows.dp(this, 16));
        card.setClipToOutline(true);
        int margin = PanelRows.dp(this, 16);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(PanelRows.dp(this, CARD_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        cardParams.setMargins(0, margin, margin, margin);
        root.addView(card, cardParams);

        View wash = new View(this);
        wash.setBackground(PanelRows.cardWash(this, CARD_WIDTH_DP));
        card.addView(wash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(R.string.ls_top_bar_buttons);
        title.setTextSize(22);
        title.setTextColor(theme.textPrimary);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(this, 28));
        titleParams.setMargins(PanelRows.dp(this, 20), PanelRows.dp(this, 20), PanelRows.dp(this, 20), 0);
        card.addView(title, titleParams);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (TopButtonStyle.Button button : TopButtonStyle.Button.values()) {
            SettingRowView view = new SettingRowView(this, buttonLabel(button), true, anim(MOVE_MS));
            view.setLayoutParams(PanelRows.rowParams(this));
            view.setValue(styleLabel(this, styles.get(button)));
            view.setOnClickListener(v -> pick(button, (SettingRowView) v));
            rows.addView(view);
            views[button.ordinal()] = view;
        }
        FrameLayout.LayoutParams listParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.setMargins(PanelRows.dp(this, 12), PanelRows.dp(this, 58), PanelRows.dp(this, 12), 0);
        card.addView(rows, listParams);
    }

    private void pick(TopButtonStyle.Button button, SettingRowView view) {
        popup = new OptionPopup(this, root, card, view, OptionPopup.WIDTH_DP, OptionPopup.ITEM_DP, anim(140));
        String current = styles.get(button);
        for (String style : TopButtonStyle.STYLES) popup.add(styleLabel(this, style), null, null, style.equals(current));
        popup.show(rows, TopButtonStyle.indexOf(current), position -> {
            String style = TopButtonStyle.STYLES[position];
            if (style.equals(styles.get(button))) return;
            styles.put(button, style);
            TopButtonStyler.save(this, TopButtonStyle.one(button, style));
            setResult(RESULT_OK);
            view.setValue(styleLabel(this, style));
            view.flashValue(anim(200) > 0);
        }, null);
    }

    /** A popup takes Back, Left/Right (close) and Up/Down, as in Launcher settings. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (swallowUp.contains(code)) {
            if (!down) swallowUp.remove(code);
            return true;
        }
        if (popup != null && popup.isShowing()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && event.getRepeatCount() == 0) {
                    popup.dismiss();
                    swallowUp.add(code);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) popup.move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        int open = rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        View focused = getCurrentFocus();
        if (down && code == open && event.getRepeatCount() == 0 && focused instanceof SettingRowView) {
            focused.performClick();   // Right opens a row, the same as OK
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Back closes the page with a short slide out. */
    @Override
    public void onBackPressed() {
        if (closing) return;
        closing = true;
        int ms = anim(150);
        if (ms <= 0) {
            super.onBackPressed();
            return;
        }
        card.animate().alpha(0f).translationX(PanelRows.dp(this, 24)).setDuration(ms)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }
}
