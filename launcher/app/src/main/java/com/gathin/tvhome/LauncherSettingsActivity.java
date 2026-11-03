package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import com.gathin.tvhome.SettingsModel.Group;
import com.gathin.tvhome.SettingsModel.Row;

/**
 * Remote-friendly settings owned by the launcher, as a card on the right over a dimmed Home: four groups of compact
 * rows (label left, value right). OK opens a small list over the row with the current choice ticked; holding OK shows
 * what the row does. System settings stay one row away. Colours come from {@link PanelTheme}, so the card follows
 * the active style.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    static final String EXTRA_EDIT_DOCK = "com.gathin.tvhome.extra.EDIT_DOCK_FROM_SETTINGS";
    /** Set after the first hold-OK help, so the footer hint goes away for good. */
    private static final String HINT_SEEN_KEY = "hint_hold_seen";
    private static final int CARD_WIDTH_DP = 326;
    private static final long TIP_SHOWN_MS = 6000;

    /** The row focused last, kept while the process lives. */
    private static Row lastRow;

    private SharedPreferences choices;
    private Config config;
    private boolean rtl;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    private FrameLayout root;
    private FrameLayout card;
    private ScrollView list;
    private TextView footer;
    private final Map<Row, SettingRowView> views = new EnumMap<>(Row.class);
    private final List<View> headers = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HoldKey hold = new HoldKey();
    private SettingRowView holdRow;
    private OptionPopup popup;
    private HoldTooltip tooltip;
    /** Keys whose down was used up (closing a popup or a tooltip): their repeats and up are ignored. */
    private final Set<Integer> swallowUp = new HashSet<>();
    private boolean closing;

    private final Runnable holdTick = new Runnable() {
        @Override
        public void run() {
            if (!hold.tracking() || holdRow == null) return;
            long now = SystemClock.uptimeMillis();
            holdRow.setHoldProgress(hold.progress(now));
            if (hold.due(now)) {
                holdRow.setPressed(false);
                showHelp(rowOf(holdRow), holdRow);
                return;
            }
            handler.postDelayed(this, 16);
        }
    };
    private final Runnable tipClose = () -> {
        if (tooltip != null) tooltip.dismiss(anim(100));
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        loadState();
        PanelRows.fullScreen(this);
        buildUi();
        setContentView(root);
        updateRows();
        Row first = lastRow != null && views.containsKey(lastRow) ? lastRow : firstRow();
        SettingRowView target = views.get(first);
        if (target != null) target.requestFocus();
        int ms = anim(200);
        if (ms > 0) {
            card.setAlpha(0f);
            card.setTranslationX(PanelRows.dp(this, 24));
            card.animate().alpha(1f).translationX(0f).setDuration(ms)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) updateRows();   // Remote buttons may have changed their count
    }

    @Override
    protected void onPause() {
        cancelHold();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void loadState() {
        config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
    }

    /** Animation length: instant with Animations off, at most 90 ms with reduced motion. */
    private int anim(int base) {
        return SettingsModel.duration(animations, config.reducedMotion, base);
    }

    // ---- building the card --------------------------------------------------------------------------------------

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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBaselineAligned(true);
        TextView title = new TextView(this);
        title.setText(R.string.launcher_settings);
        title.setTextSize(22);
        title.setTextColor(theme.textPrimary);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String themeName = themeName();
        if (!themeName.isEmpty()) {
            TextView name = new TextView(this);
            name.setText(themeName);
            name.setTextSize(13);
            name.setTextColor(0x80FFFFFF);
            name.setTypeface(UiFont.get(this, R.string.font_regular));
            name.setSingleLine(true);
            name.setIncludeFontPadding(false);
            head.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        FrameLayout.LayoutParams headParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(this, 28));
        headParams.setMargins(PanelRows.dp(this, 20), PanelRows.dp(this, 20), PanelRows.dp(this, 20), 0);
        card.addView(head, headParams);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 0, 0, PanelRows.dp(this, 40));
        boolean weather = weatherAvailable();
        for (Group group : SettingsModel.groups()) {
            List<Row> inGroup = SettingsModel.rows(group, config.openOnStartChoice, weather, config.dockRow);
            if (inGroup.isEmpty()) continue;
            TextView header = PanelRows.header(this, groupLabel(group));
            rows.addView(header);
            headers.add(header);
            for (Row row : inGroup) {
                SettingRowView view = new SettingRowView(this, label(row),
                        true, anim(120));
                view.setTag(row);
                view.setLayoutParams(PanelRows.rowParams(this));
                view.setOnClickListener(v -> activate(row, (SettingRowView) v));
                view.setOnFocusChangeListener((v, focused) -> {
                    if (focused) onRowFocused(row, v);
                });
                rows.addView(view);
                views.put(row, view);
            }
        }

        list = new ScrollView(this);
        list.setFillViewport(false);
        list.setVerticalScrollBarEnabled(false);
        // Feather the top and bottom: rows dissolve into the card's edge instead of being cut off.
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(PanelRows.dp(this, PanelUi.FADE_DP));
        list.setClipToPadding(false);
        list.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams listParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        listParams.setMargins(PanelRows.dp(this, 12), PanelRows.dp(this, 58), PanelRows.dp(this, 12),
                PanelRows.dp(this, 34));
        card.addView(list, listParams);

        footer = new TextView(this);
        footer.setText(R.string.ls_footer_hint);
        footer.setTextSize(12);
        footer.setTextColor(0x5CFFFFFF);
        footer.setTypeface(UiFont.get(this, R.string.font_regular));
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        footer.setPaddingRelative(PanelRows.dp(this, 28), 0, PanelRows.dp(this, 12), 0);
        footer.setVisibility(choices.getBoolean(HINT_SEEN_KEY, false) ? View.GONE : View.VISIBLE);
        card.addView(footer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(this, 34), Gravity.BOTTOM));

        tooltip = new HoldTooltip(this, root, card);
    }

    /** The theme's name for the top right of the card, or empty. */
    private String themeName() {
        JSONObject overlay = ThemeOverlay.read(this);
        return overlay == null ? "" : overlay.optString(StyleInstaller.KEY_THEME, "").trim();
    }

    private Row firstRow() {
        for (Group group : SettingsModel.groups()) {
            List<Row> inGroup = SettingsModel.rows(group, config.openOnStartChoice, weatherAvailable(), config.dockRow);
            if (!inGroup.isEmpty()) return inGroup.get(0);
        }
        return Row.COLUMNS;
    }

    /**
     * Whether the weather add-on's source is in this build. The two weather rows stay hidden until WeatherSource
     * exists, so this panel builds without it. When it lands, replace the reflection with a direct reference and
     * give WEATHER_ON_HOME its On/Off options and WEATHER_SETTINGS its activity in {@link #activate}.
     */
    private static boolean weatherAvailable() {
        try {
            Class.forName("com.gathin.tvhome.WeatherSource");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void onRowFocused(Row row, View view) {
        lastRow = row;
        // Reveal the group name above the first row of a group when it scrolls into place.
        int index = ((ViewGroup) view.getParent()).indexOfChild(view);
        if (index > 0) {
            View before = ((ViewGroup) view.getParent()).getChildAt(index - 1);
            if (headers.contains(before) && list.getScrollY() > before.getTop()) {
                list.smoothScrollTo(0, before.getTop());
            }
        }
    }

    // ---- rows: labels, values, help -----------------------------------------------------------------------------

    private String groupLabel(Group group) {
        switch (group) {
            case HOME_LAYOUT: return getString(R.string.ls_group_home_layout);
            case LOOK: return getString(R.string.ls_group_look);
            case BUTTONS: return getString(R.string.ls_group_buttons);
            default: return getString(R.string.ls_group_system);
        }
    }

    private String label(Row row) {
        switch (row) {
            case COLUMNS: return getString(R.string.columns_label);
            case ROWS_BELOW_DOCK: return getString(R.string.rows_below_dock_label);
            case DOCK_OPACITY: return getString(R.string.dock_opacity_label);
            case OPEN_ON_START: return getString(R.string.open_on_start_label);
            case STYLE: return getString(R.string.ls_style_label);
            case WALLPAPER: return getString(R.string.wallpaper_label);
            case CLOCK: return getString(R.string.ls_clock_label);
            case WEATHER_ON_HOME: return getString(R.string.ls_weather_on_home);
            case WEATHER_SETTINGS: return getString(R.string.ls_weather_settings);
            case ANIMATIONS: return getString(R.string.ls_animations_label);
            case ADVANCED: return getString(R.string.advanced_effects);
            case REMOTE_BUTTONS: return getString(R.string.remote_buttons);
            case REORDER: return getString(R.string.ls_reorder);
            case EDIT_DOCK: return getString(R.string.dock_edit);
            case DEFAULT_HOME: return getString(R.string.home_settings);
            case TV_SETTINGS: return getString(R.string.tv_settings);
            default: return getString(R.string.ls_reset_theme);
        }
    }

    private String help(Row row) {
        switch (row) {
            case COLUMNS: return getString(R.string.ls_help_columns);
            case ROWS_BELOW_DOCK: return getString(R.string.ls_help_rows_below);
            case DOCK_OPACITY: return getString(R.string.ls_help_dock_opacity);
            case OPEN_ON_START: return getString(R.string.ls_help_open_on_start);
            case STYLE: return getString(R.string.ls_help_style);
            case WALLPAPER: return getString(R.string.ls_help_wallpaper);
            case CLOCK: return getString(R.string.ls_help_clock);
            case WEATHER_ON_HOME: return getString(R.string.ls_help_weather_on_home);
            case WEATHER_SETTINGS: return getString(R.string.ls_help_weather_settings);
            case ANIMATIONS: return getString(R.string.ls_help_animations);
            case ADVANCED:
                return animations ? getString(R.string.advanced_effects_warning)
                        : getString(R.string.ls_help_advanced_off) + " " + getString(R.string.advanced_effects_warning);
            case REMOTE_BUTTONS: return getString(R.string.remote_buttons_help);
            case REORDER: return getString(R.string.ls_help_reorder);
            case EDIT_DOCK: return getString(R.string.ls_help_edit_dock);
            case DEFAULT_HOME: return getString(R.string.home_settings_help);
            case TV_SETTINGS: return getString(R.string.ls_help_tv_settings);
            default:
                return resetCount() == 0 ? getString(R.string.ls_help_reset_none)
                        : getString(R.string.follow_theme_hint);
        }
    }

    private String value(Row row) {
        switch (row) {
            case COLUMNS: return String.valueOf(columns);
            case ROWS_BELOW_DOCK: return String.valueOf(rowsBelowDock);
            case DOCK_OPACITY: return SettingsModel.percent(dockOpacity);
            case OPEN_ON_START: return openOnStartLabel();
            case STYLE: return styleLabel();
            case WALLPAPER: return wallpaperLabel();
            case CLOCK: return clockLabel(clockFormat);
            case ANIMATIONS: return getString(animations ? R.string.on : R.string.off);
            case ADVANCED: return getString(advancedEffects ? R.string.on : R.string.off);
            case REMOTE_BUTTONS: {
                int n = KeyMap.merged(this, config.appKeys).size();
                return n == 0 ? getString(R.string.ls_remote_none) : getString(R.string.ls_remote_count, n);
            }
            case RESET_THEME: {
                int n = resetCount();
                return n == 0 ? getString(R.string.ls_reset_none)
                        : n == 1 ? getString(R.string.ls_reset_one) : getString(R.string.ls_reset_many, n);
            }
            default: return null;
        }
    }

    private void updateRows() {
        for (Map.Entry<Row, SettingRowView> e : views.entrySet()) {
            Row row = e.getKey();
            SettingRowView view = e.getValue();
            view.setValue(value(row));
            view.setDimmed((row == Row.ADVANCED && !animations) || (row == Row.RESET_THEME && resetCount() == 0));
            if (row == Row.STYLE) {
                String active = StyleInstaller.activeStyleId(this);
                view.setSwatch(active == null ? null : new StylePreviewDrawable(active));
            }
        }
    }

    private Row rowOf(SettingRowView view) {
        return (Row) view.getTag();
    }

    private String clockLabel(String format) {
        if ("12".equals(format)) return getString(R.string.clock_format_12);
        if ("24".equals(format)) return getString(R.string.clock_format_24);
        return getString(R.string.clock_format_system);
    }

    private String wallpaperLabel() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        return "none".equals(current) ? getString(R.string.wallpaper_none)
                : current != null ? current : getString(R.string.wallpaper_theme_default);
    }

    private String styleLabel() {
        String active = StyleInstaller.activeStyleId(this);
        if (active == null) return getString(R.string.ls_style_custom);
        for (String[] style : StyleInstaller.styles(this)) if (style[0].equals(active)) return style[1];
        return active;
    }

    private int resetCount() {
        return SettingsModel.resetCount(choices::contains);
    }

    // ---- activating a row ---------------------------------------------------------------------------------------

    private void activate(Row row, SettingRowView view) {
        switch (row) {
            case COLUMNS:
                options(row, view, SettingsModel.numberLabels(SettingsModel.COLUMNS),
                        SettingsModel.indexOf(SettingsModel.COLUMNS, columns), i -> {
                            columns = SettingsModel.COLUMNS[i];
                            choices.edit().putInt("columns", columns).apply();
                            committed(row);
                        });
                break;
            case ROWS_BELOW_DOCK:
                options(row, view, SettingsModel.numberLabels(SettingsModel.ROWS_BELOW_DOCK),
                        SettingsModel.indexOf(SettingsModel.ROWS_BELOW_DOCK, rowsBelowDock), i -> {
                            rowsBelowDock = SettingsModel.ROWS_BELOW_DOCK[i];
                            choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, rowsBelowDock).apply();
                            committed(row);
                        });
                break;
            case DOCK_OPACITY:
                options(row, view, SettingsModel.percentLabels(SettingsModel.DOCK_OPACITIES),
                        SettingsModel.indexOf(SettingsModel.DOCK_OPACITIES, dockOpacity), i -> {
                            dockOpacity = SettingsModel.DOCK_OPACITIES[i];
                            choices.edit().putInt(Config.DOCK_OPACITY_KEY, dockOpacity).apply();
                            committed(row);
                        });
                break;
            case CLOCK:
                options(row, view, new String[] {getString(R.string.clock_format_system),
                                getString(R.string.clock_format_12), getString(R.string.clock_format_24)},
                        SettingsModel.indexOf(SettingsModel.CLOCK_FORMATS, clockFormat), i -> {
                            clockFormat = SettingsModel.CLOCK_FORMATS[i];
                            choices.edit().putString(Config.CLOCK_FORMAT_KEY, clockFormat).apply();
                            committed(row);
                        });
                break;
            case ANIMATIONS:
                options(row, view, new String[] {getString(R.string.on), getString(R.string.off)},
                        animations ? 0 : 1, i -> {
                            animations = i == 0;
                            if (!animations) advancedEffects = false;
                            choices.edit().putBoolean("animations", animations)
                                    .putBoolean("advanced_effects", advancedEffects).apply();
                            committed(row);
                        });
                break;
            case ADVANCED:
                if (!animations) {
                    showHelp(row, view);
                    break;
                }
                options(row, view, new String[] {getString(R.string.on), getString(R.string.off)},
                        advancedEffects ? 0 : 1, i -> {
                            if (i == 1) setAdvanced(false);
                            else if (!advancedEffects) {
                                confirm(view, getString(R.string.ls_advanced_confirm_title),
                                        getString(R.string.advanced_effects_warning), getString(R.string.enable),
                                        getString(R.string.ls_keep_off), true, () -> setAdvanced(true));
                            }
                        });
                break;
            case WALLPAPER:
                showWallpaperPicker(view);
                break;
            case OPEN_ON_START:
                showOpenOnStartPicker(view);
                break;
            case STYLE:
                showStylePicker(view);
                break;
            case REMOTE_BUTTONS:
                open(new Intent(this, RemoteKeysActivity.class));
                break;
            case REORDER:
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
                break;
            case EDIT_DOCK:
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_EDIT_DOCK, true));
                finish();
                break;
            case DEFAULT_HOME:
                open(new Intent(this, HomeSettingsActivity.class));
                break;
            case TV_SETTINGS:
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
                break;
            case RESET_THEME:
                if (resetCount() == 0) {
                    showHelp(row, view);
                    break;
                }
                confirm(view, getString(R.string.ls_reset_confirm_title), getString(R.string.ls_reset_confirm_body),
                        getString(R.string.ls_reset), getString(android.R.string.cancel), false, this::resetTheme);
                break;
            default:
                // WEATHER_ON_HOME and WEATHER_SETTINGS: hidden until WeatherSource exists (see weatherAvailable).
                showHelp(row, view);
                break;
        }
    }

    /** A choice was made in a popup: save the change count, redraw the values and pulse the changed one. */
    private void committed(Row row) {
        changed();
        updateRows();
        SettingRowView view = views.get(row);
        if (view != null) view.flashValue(anim(200) > 0);
        if (row == Row.ANIMATIONS) {
            SettingRowView advanced = views.get(Row.ADVANCED);
            if (advanced != null) advanced.setDimmed(!animations);
        }
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        committed(Row.ADVANCED);
    }

    private void resetTheme() {
        SharedPreferences.Editor editor = choices.edit();
        for (String key : SettingsModel.RESET_KEYS) editor.remove(key);
        editor.commit();
        loadState();
        committed(Row.RESET_THEME);
    }

    // ---- popups -------------------------------------------------------------------------------------------------

    /** A list of options over the row; the numeric lists run reversed under rtl_app_grid. */
    private void options(Row row, SettingRowView view, String[] labels, int current, IntConsumer pick) {
        boolean reversed = getResources().getBoolean(R.bool.rtl_app_grid) && SettingsModel.reversesInRtl(row);
        int[] order = SettingsModel.order(labels.length, reversed);
        OptionPopup p = newPopup(view, OptionPopup.WIDTH_DP, OptionPopup.ITEM_DP);
        int focus = 0;
        for (int i = 0; i < order.length; i++) {
            boolean isCurrent = order[i] == current;
            p.add(labels[order[i]], null, null, isCurrent);
            if (isCurrent) focus = i;
        }
        p.show(list, focus, position -> pick.accept(order[position]), null);
    }

    private OptionPopup newPopup(SettingRowView view, int widthDp, int itemDp) {
        cancelHold();
        tooltip.dismiss(0);
        popup = new OptionPopup(this, root, card, view, widthDp, itemDp, anim(140));
        return popup;
    }

    /** A card with a title, a sentence and two buttons over the row. */
    private void confirm(SettingRowView view, String title, String body, String yes, String no, boolean focusYes,
                         Runnable onYes) {
        OptionPopup p = newPopup(view, OptionPopup.WIDE_WIDTH_DP, OptionPopup.ITEM_DP);
        p.message(title, body).noTicks();
        p.add(yes, null, null, false);
        p.add(no, null, null, false);
        p.show(list, focusYes ? 0 : 1, position -> {
            if (position == 0) onYes.run();
        }, null);
    }

    private void showWallpaperPicker(SettingRowView view) {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        // Position 0 is the theme's own choice, then the pictures, then None.
        String[] labels = new String[images.size() + 2];
        labels[0] = getString(R.string.wallpaper_theme_default);
        for (int i = 0; i < images.size(); i++) labels[i + 1] = images.get(i).getName();
        labels[images.size() + 1] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked;
        if (current == null) checked = 0;
        else if ("none".equals(current)) checked = images.size() + 1;
        else {
            checked = -1;
            for (int i = 0; i < images.size(); i++) if (images.get(i).getName().equals(current)) checked = i + 1;
        }
        options(Row.WALLPAPER, view, labels, checked, which -> {
            if (which == 0) {
                choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            } else if (which == images.size() + 1) {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            } else {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, images.get(which - 1).getName()).apply();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            }
            committed(Row.WALLPAPER);
        });
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker(SettingRowView view) {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        options(Row.OPEN_ON_START, view, labels, checked, which -> {
            if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
            }
            // A pick here takes effect at the next boot; Home has nothing to rebuild.
            updateRows();
            SettingRowView changedRow = views.get(Row.OPEN_ON_START);
            if (changedRow != null) changedRow.flashValue(anim(200) > 0);
        });
    }

    /** The picture-and-caption list of Clean Look styles; a pick is confirmed, because Home restarts. */
    private void showStylePicker(SettingRowView view) {
        List<String[]> styles = StyleInstaller.styles(this);
        String active = StyleInstaller.activeStyleId(this);
        OptionPopup p = newPopup(view, OptionPopup.WIDE_WIDTH_DP, OptionPopup.WIDE_ITEM_DP);
        p.header(getString(R.string.ls_styles_header));
        int focus = 0;
        for (int i = 0; i < styles.size(); i++) {
            String[] style = styles.get(i);
            boolean isActive = style[0].equals(active);
            p.add(style[1], styleCaption(style), new StylePreviewDrawable(style[0]), isActive);
            if (isActive) focus = i;
        }
        p.add(getString(R.string.ls_style_builtin), getString(R.string.ls_style_builtin_caption),
                new StylePreviewDrawable(null), false);
        p.show(list, focus, position -> {
            boolean builtin = position == styles.size();
            String id = builtin ? null : styles.get(position)[0];
            String name = builtin ? getString(R.string.ls_style_builtin) : styles.get(position)[1];
            if (!builtin && id.equals(active)) return;
            confirm(view, name, getString(R.string.ls_style_confirm_body), getString(R.string.ls_apply),
                    getString(android.R.string.cancel), true, () -> StyleInstaller.apply(this, id, name));
        }, null);
    }

    private String styleCaption(String[] style) {
        switch (style[0]) {
            case "cinematic": return getString(R.string.ls_style_cinematic_caption);
            case "glass": return getString(R.string.ls_style_glass_caption);
            case "aurora": return getString(R.string.ls_style_aurora_caption);
            case "minimal": return getString(R.string.ls_style_minimal_caption);
            case "six-columns": return getString(R.string.ls_style_six_columns_caption);
            default: return style[2];
        }
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---- hold OK: the explanation -------------------------------------------------------------------------------

    private void showHelp(Row row, SettingRowView view) {
        tooltip.show(view, label(row), help(row), anim(120));
        if (!choices.getBoolean(HINT_SEEN_KEY, false)) {
            choices.edit().putBoolean(HINT_SEEN_KEY, true).apply();
            footer.setVisibility(View.GONE);
        }
        handler.removeCallbacks(tipClose);
        handler.postDelayed(tipClose, TIP_SHOWN_MS);
    }

    private void cancelHold() {
        handler.removeCallbacks(holdTick);
        hold.cancel();
        if (holdRow != null) {
            holdRow.setHoldProgress(0f);
            holdRow.setPressed(false);
            holdRow = null;
        }
    }

    /**
     * Keys. A popup takes Back, Left/Right (close) and Up/Down (no wrap). Otherwise OK is watched for a hold: a short
     * press clicks the row on release, a long one shows its explanation and the release is swallowed. Any key
     * closes a showing tooltip and is used up by that.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (swallowUp.contains(code)) {
            if (!down) swallowUp.remove(code);
            return true;
        }
        if (popup != null && popup.isShowing()) return popupKey(event, code, down);
        if (tooltip != null && tooltip.isShowing() && down
                && !(HoldKey.isHoldKey(code) && hold.tracking())) {
            handler.removeCallbacks(tipClose);
            tooltip.dismiss(anim(100));
            swallowUp.add(code);
            return true;
        }
        if (HoldKey.isHoldKey(code)) {
            if (holdKey(event, down)) return true;
        } else if (down) {
            if (hold.tracking()) cancelHold();
            int open = rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            View focused = getCurrentFocus();
            if (code == open && event.getRepeatCount() == 0 && focused instanceof SettingRowView) {
                focused.performClick();   // Right opens a row, the same as OK
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean popupKey(KeyEvent event, int code, boolean down) {
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.getRepeatCount() == 0) {
                popup.dismiss();
                swallowUp.add(code);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
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

    private boolean holdKey(KeyEvent event, boolean down) {
        View focused = getCurrentFocus();
        if (down) {
            if (!(focused instanceof SettingRowView) && !hold.tracking()) return false;
            if (hold.down(SystemClock.uptimeMillis(), event.getRepeatCount())) {
                holdRow = (SettingRowView) focused;
                holdRow.setPressed(true);
                handler.post(holdTick);
            }
            return true;
        }
        SettingRowView row = holdRow;
        HoldKey.Up up = hold.up();
        handler.removeCallbacks(holdTick);
        holdRow = null;
        if (row != null) {
            row.setHoldProgress(0f);
            row.setPressed(false);
        }
        switch (up) {
            case CLICK:
                if (row != null) row.performClick();
                return true;
            case CONSUMED:
                handler.removeCallbacks(tipClose);
                handler.postDelayed(tipClose, TIP_SHOWN_MS);
                return true;
            default:
                return false;
        }
    }

    /** Back closes the panel with a short slide out. */
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

    // ---- saving -------------------------------------------------------------------------------------------------

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
