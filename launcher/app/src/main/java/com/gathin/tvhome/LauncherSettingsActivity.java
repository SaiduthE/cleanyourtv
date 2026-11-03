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
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
 * rows (label left, value right). OK opens a small list over the row with the current choice ticked. System settings stay one row away. Colours come from {@link PanelTheme}, so the card follows
 * the active style.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    static final String EXTRA_EDIT_DOCK = "com.gathin.tvhome.extra.EDIT_DOCK_FROM_SETTINGS";
    private static final int CARD_WIDTH_DP = 326;
    /** One length for the highlight fade and the list glide, so they move together. */
    private static final int MOVE_MS = 180;

    /** The row focused last, kept while the process lives. */
    private static Row lastRow;

    private SharedPreferences choices;
    private Config config;
    private boolean rtl;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private TopShelfLogic.Mode topShelf;
    private boolean animations;
    private boolean advancedEffects;
    private boolean appArtwork;
    private boolean photoCredit;
    /** The add-on's photo settings (switch, themes, status), as last answered; see PhotoSettingsClient. */
    private PhotoSettings photos = PhotoSettings.unavailable(PhotoSettings.State.LOADING);
    private PhotoSettingsClient photoClient;
    /** The Photo themes list while it is open, so a late answer from the add-on can redraw its ticks. */
    private OptionPopup themesPopup;

    private FrameLayout root;
    private FrameLayout card;
    private GlideScrollView list;
    private final Map<Row, SettingRowView> views = new EnumMap<>(Row.class);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HoldKey hold = new HoldKey(ViewConfiguration.getLongPressTimeout());
    private SettingRowView holdRow;
    private HoldTip tip;
    private OptionPopup popup;
    private WidgetsFlow widgetsFlow;
    /** Keys whose down was used up (closing a popup): their repeats and up are ignored. */
    private final Set<Integer> swallowUp = new HashSet<>();
    private boolean closing;

    /**
     * The default long-press time passed with OK still down: it is a hold, so the release will not click, and the
     * row's tip card fades in until OK is released, another key is pressed or 5 s pass.
     */
    private final Runnable holdDue = () -> {
        if (hold.tracking() && holdRow != null && hold.due(SystemClock.uptimeMillis())) {
            holdRow.setPressed(false);
            Object tag = holdRow.getTag();
            if (tag instanceof Row) showHelp((Row) tag, holdRow);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        loadState();
        photoClient = new PhotoSettingsClient(this, this::onPhotoSettings);
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
        if (photoClient != null) photoClient.load();   // the add-on may have been installed or opened meanwhile
    }

    @Override
    protected void onPause() {
        cancelHold();        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (photoClient != null) photoClient.close();
        super.onDestroy();
    }

    private void loadState() {
        config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        topShelf = TopShelfLogic.Mode.parse(choices.getString(Config.TOP_SHELF_KEY, null));
        appArtwork = choices.getBoolean(Config.TOP_SHELF_ARTWORK_KEY, true);
        photoCredit = choices.getBoolean(Config.TOP_SHELF_CREDIT_KEY, true);
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
        for (Group group : SettingsModel.groups()) {
            List<Row> inGroup = SettingsModel.rows(group, config.openOnStartChoice, config.dockRow);
            if (inGroup.isEmpty()) continue;
            TextView header = PanelRows.header(this, groupLabel(group));
            rows.addView(header);
            for (Row row : inGroup) {
                SettingRowView view = new SettingRowView(this, label(row),
                        true, anim(MOVE_MS));
                view.setTag(row);
                if (row == Row.REFRESH_PHOTOS) view.setStatusStyle();
                view.setLayoutParams(PanelRows.rowParams(this));
                view.setOnClickListener(v -> activate(row, (SettingRowView) v));
                view.setOnFocusChangeListener((v, focused) -> {
                    if (focused) onRowFocused(row, v);
                });
                rows.addView(view);
                views.put(row, view);
            }
        }

        list = new GlideScrollView(this);
        list.setDurationMs(anim(MOVE_MS));
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
                PanelRows.dp(this, 12));
        card.addView(list, listParams);
    }

    /** The theme's name for the top right of the card, or empty. */
    private String themeName() {
        JSONObject overlay = ThemeOverlay.read(this);
        return overlay == null ? "" : overlay.optString(StyleInstaller.KEY_THEME, "").trim();
    }

    private Row firstRow() {
        for (Group group : SettingsModel.groups()) {
            List<Row> inGroup = SettingsModel.rows(group, config.openOnStartChoice, config.dockRow);
            if (!inGroup.isEmpty()) return inGroup.get(0);
        }
        return Row.COLUMNS;
    }

    private void onRowFocused(Row row, View view) {
        lastRow = row;   // the list follows the focus by itself (GlideScrollView)
    }

    // ---- rows: labels, values, help -----------------------------------------------------------------------------

    private String groupLabel(Group group) {
        switch (group) {
            case HOME_LAYOUT: return getString(R.string.ls_group_home_layout);
            case LOOK: return getString(R.string.ls_group_look);
            case TOP_SHELF: return getString(R.string.ls_group_top_shelf);
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
            case TOP_SHELF: return getString(R.string.ls_top_shelf_label);
            case APP_ARTWORK: return getString(R.string.ls_app_artwork_label);
            case PHOTOS: return getString(R.string.ls_photos_label);
            case PHOTO_THEMES: return getString(R.string.ls_photo_themes_label);
            case REFRESH_PHOTOS: return getString(R.string.ls_refresh_photos_label);
            case PHOTO_CREDIT: return getString(R.string.ls_photo_credit_label);
            case CLOCK: return getString(R.string.ls_clock_label);
            case WIDGETS: return getString(R.string.ls_widgets);
            case ANIMATIONS: return getString(R.string.ls_animations_label);
            case ADVANCED: return getString(R.string.advanced_effects);
            case REMOTE_BUTTONS: return getString(R.string.remote_buttons);
            case TOP_BAR_BUTTONS: return getString(R.string.ls_top_bar_buttons);
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
            case TOP_SHELF: return getString(R.string.ls_help_top_shelf);
            case APP_ARTWORK: return getString(R.string.ls_help_app_artwork);
            case PHOTOS: return photosAvailabilityHelp(R.string.ls_help_photos);
            case PHOTO_THEMES: return photosAvailabilityHelp(R.string.ls_help_photo_themes);
            case REFRESH_PHOTOS:
                return photos.available() && !photos.enabled ? getString(R.string.ls_help_refresh_photos_off)
                        : photosAvailabilityHelp(R.string.ls_help_refresh_photos);
            case PHOTO_CREDIT: return getString(R.string.ls_help_photo_credit);
            case CLOCK: return getString(R.string.ls_help_clock);
            case WIDGETS: return getString(R.string.ls_help_widgets);
            case ANIMATIONS: return getString(R.string.ls_help_animations);
            case ADVANCED:
                return getString(animations ? R.string.ls_help_advanced : R.string.ls_help_advanced_off);
            case REMOTE_BUTTONS: return getString(R.string.ls_help_remote_buttons);
            case TOP_BAR_BUTTONS: return getString(R.string.ls_help_top_bar_buttons);
            case REORDER: return getString(R.string.ls_help_reorder);
            case EDIT_DOCK: return getString(R.string.ls_help_edit_dock);
            case DEFAULT_HOME: return getString(R.string.ls_help_default_home);
            case TV_SETTINGS: return getString(R.string.ls_help_tv_settings);
            default:
                return resetCount() == 0 ? getString(R.string.ls_help_reset_none)
                        : getString(R.string.ls_help_reset);
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
            case TOP_SHELF: return topShelfLabel(topShelf);
            case APP_ARTWORK: return getString(appArtwork ? R.string.on : R.string.off);
            case PHOTOS: return photos.photosValue();
            case PHOTO_THEMES: return photos.themesValue();
            case REFRESH_PHOTOS: return photos.refreshValue();
            case PHOTO_CREDIT: return getString(photoCredit ? R.string.on : R.string.off);
            case CLOCK: return clockLabel(clockFormat);
            case WIDGETS: return WidgetStore.summary(this);
            case ANIMATIONS: return getString(animations ? R.string.on : R.string.off);
            case ADVANCED: return getString(advancedEffects ? R.string.on : R.string.off);
            case TOP_BAR_BUTTONS: return TopBarButtonsActivity.styleLabel(this,
                    TopButtonStyle.common(TopButtonStyler.load(this, config)));
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
            view.setDimmed((row == Row.ADVANCED && !animations) || (row == Row.RESET_THEME && resetCount() == 0)
                    || (SettingsModel.isAddOnRow(row) && !photos.available())
                    || (row == Row.REFRESH_PHOTOS && photos.available() && !photos.enabled));
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

    private String topShelfLabel(TopShelfLogic.Mode mode) {
        return getString(mode == TopShelfLogic.Mode.OFF ? R.string.off : R.string.ls_top_shelf_layered);
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
            case TOP_SHELF: {
                TopShelfLogic.Mode[] modes = TopShelfLogic.Mode.values();
                String[] labels = new String[modes.length];
                for (int i = 0; i < modes.length; i++) labels[i] = topShelfLabel(modes[i]);
                options(row, view, labels, topShelf.ordinal(), i -> {
                    topShelf = modes[i];
                    choices.edit().putString(Config.TOP_SHELF_KEY, topShelf.key).apply();
                    committed(row);
                });
                break;
            }
            case APP_ARTWORK:
                options(row, view, new String[] {getString(R.string.on), getString(R.string.off)},
                        appArtwork ? 0 : 1, i -> {
                            appArtwork = i == 0;
                            choices.edit().putBoolean(Config.TOP_SHELF_ARTWORK_KEY, appArtwork).apply();
                            committed(row);
                        });
                break;
            case PHOTO_CREDIT:
                options(row, view, new String[] {getString(R.string.on), getString(R.string.off)},
                        photoCredit ? 0 : 1, i -> {
                            photoCredit = i == 0;
                            choices.edit().putBoolean(Config.TOP_SHELF_CREDIT_KEY, photoCredit).apply();
                            committed(row);
                        });
                break;
            case PHOTOS:
                if (!photoRowReady(row, view)) break;
                options(row, view, new String[] {getString(R.string.on), getString(R.string.off)},
                        photos.enabled ? 0 : 1, i -> {
                            boolean on = i == 0;
                            if (on == photos.enabled) return;
                            photos = photos.withEnabled(on);
                            photoClient.setEnabled(on);   // on: the add-on starts the first fetch; off: it deletes the photos
                            committed(row);
                        });
                break;
            case PHOTO_THEMES:
                if (photoRowReady(row, view)) showPhotoThemes(view);
                break;
            case REFRESH_PHOTOS:
                if (!photoRowReady(row, view)) break;
                if (!photos.enabled) {
                    showHelp(row, view);
                    break;
                }
                if (!photos.refreshing) {
                    photos = photos.withRefreshing();
                    photoClient.refresh();   // the status is polled until the add-on is done
                    updateRows();
                }
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
            case TOP_BAR_BUTTONS:
                showTopBarButtonsPicker(view);
                break;
            case WIDGETS:
                // The Widgets manager opens over this panel, which stays open (dimmed) underneath.
                cancelHold();
                widgetsFlow().open();
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

    /** One style for all four top-bar buttons, or Per button for the list that sets each. */
    private void showTopBarButtonsPicker(SettingRowView view) {
        String common = TopButtonStyle.common(TopButtonStyler.load(this, config));
        String[] labels = new String[TopButtonStyle.STYLES.length + 1];
        for (int i = 0; i < TopButtonStyle.STYLES.length; i++) {
            labels[i] = TopBarButtonsActivity.styleLabel(this, TopButtonStyle.STYLES[i]);
        }
        labels[TopButtonStyle.STYLES.length] = getString(R.string.ls_tbb_per_button);
        int current = TopButtonStyle.MIXED.equals(common) ? -1 : TopButtonStyle.indexOf(common);
        options(Row.TOP_BAR_BUTTONS, view, labels, current, which -> {
            if (which == TopButtonStyle.STYLES.length) {
                open(new Intent(this, TopBarButtonsActivity.class));
                return;
            }
            TopButtonStyler.save(this, TopButtonStyle.allTo(TopButtonStyle.STYLES[which]));
            setResult(RESULT_OK);
            updateRows();
            SettingRowView changedRow = views.get(Row.TOP_BAR_BUTTONS);
            if (changedRow != null) changedRow.flashValue(anim(200) > 0);
        });
    }

    // ---- Top Shelf photos (they live in the Continue watching add-on) -------------------------------------------

    /** Why a photo row is greyed, or {@code ready} when it is not. */
    private String photosAvailabilityHelp(int ready) {
        switch (photos.state) {
            case NOT_INSTALLED: return getString(R.string.ls_help_photos_missing);
            case OUTDATED: return getString(R.string.ls_help_photos_outdated);
            case READY: return getString(ready);
            default: return getString(R.string.ls_help_photos_unavailable);
        }
    }

    /** False (after explaining why, and asking the add-on again) while the add-on cannot be used. */
    private boolean photoRowReady(Row row, SettingRowView view) {
        if (photos.available()) return true;
        showHelp(row, view);
        if (photos.state == PhotoSettings.State.NO_ANSWER) photoClient.load();
        return false;
    }

    /** The add-on answered (or did not): redraw the rows, the open themes list and the refresh outcome. */
    private void onPhotoSettings(PhotoSettings next) {
        boolean wasRefreshing = photos.refreshing;
        int before = photos.count;
        photos = next;
        if (PhotoSettings.ERR_AT_LEAST_ONE.equals(next.error)) {
            Toast.makeText(this, R.string.ls_photo_themes_last, Toast.LENGTH_SHORT).show();
        }
        if (root == null) return;
        updateRows();
        if (themesPopup != null && themesPopup.isShowing() && next.available()) {
            for (int i = 0; i < next.keys.size(); i++) themesPopup.setTicked(i, next.on.contains(next.keys.get(i)));
        }
        if (wasRefreshing && !next.refreshing && next.available()) {
            SettingRowView refresh = views.get(Row.REFRESH_PHOTOS);
            if (refresh != null) refresh.flashValue(anim(200) > 0);
            if (next.count != before) changed();   // Home picks the new photos up when it is rebuilt
        }
    }

    /** One switch per photo theme; the list stays open so several can be changed. At least one stays on. */
    private void showPhotoThemes(SettingRowView view) {
        final List<String> keys = photos.keys;
        OptionPopup p = newPopup(view, OptionPopup.WIDTH_DP, OptionPopup.ITEM_DP).stayOpen();
        p.header(getString(R.string.ls_photo_themes_header));
        for (int i = 0; i < keys.size(); i++) p.add(photos.labels.get(i), null, null, photos.on.contains(keys.get(i)));
        themesPopup = p;
        p.show(list, 0, position -> {
            Set<String> next = PhotoSettings.toggled(photos.keys, photos.on, keys.get(position));
            if (next == null) {
                Toast.makeText(this, R.string.ls_photo_themes_last, Toast.LENGTH_SHORT).show();
                return;
            }
            photos = photos.withOn(next);
            p.setTicked(position, next.contains(keys.get(position)));
            photoClient.setCategories(next);
            updateRows();
        }, () -> {
            themesPopup = null;
            SettingRowView changedRow = views.get(Row.PHOTO_THEMES);
            if (changedRow != null) changedRow.flashValue(anim(200) > 0);
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

    // ---- a row that is switched off: say why --------------------------------------------------------------------

    private void showHelp(Row row, SettingRowView view) {
        if (tip == null) tip = new HoldTip(this, root, card);
        tip.show(view, help(row), anim(140));
    }

    private void hideTip(boolean animate) {
        if (tip != null) tip.hide(animate && anim(100) > 0);
    }

    private void cancelHold() {
        hideTip(false);
        handler.removeCallbacks(holdDue);
        hold.cancel();
        if (holdRow != null) {
            holdRow.setPressed(false);
            holdRow = null;
        }
    }

    /**
     * Keys. A popup takes Back, Left/Right (close) and Up/Down (no wrap). Otherwise OK is watched for a hold: a short
     * press clicks the row on release, a long one does nothing and its release is swallowed.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (swallowUp.contains(code)) {
            if (!down) swallowUp.remove(code);
            return true;
        }
        // Any key but the repeats of the held OK ends the tip.
        if (down && tip != null && tip.isShowing() && !(HoldKey.isHoldKey(code) && event.getRepeatCount() > 0)) {
            hideTip(true);
        }
        // The Widgets manager's screens are on top of the panel and take the arrows, OK and Back while open.
        if (widgetsFlow != null && widgetsFlow.dispatchKey(event)) return true;
        if (popup != null && popup.isShowing()) return popupKey(event, code, down);
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

    /** The Widgets manager for this opening of the Widgets row (it reads the current choices). */
    private WidgetsFlow widgetsFlow() {
        widgetsFlow = new WidgetsFlow(this, root, config, this::changed, () -> {
            updateRows();   // the Widgets row says how many are on Home
            SettingRowView row = views.get(Row.WIDGETS);
            if (row != null) row.flashValue(anim(200) > 0);
        });
        return widgetsFlow;
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
                handler.postDelayed(holdDue, HoldKey.HOLD_MS);
            }
            return true;
        }
        SettingRowView row = holdRow;
        HoldKey.Up up = hold.up();
        handler.removeCallbacks(holdDue);
        if (up == HoldKey.Up.CONSUMED) hideTip(true);   // OK released after a hold: the tip goes, nothing is clicked
        holdRow = null;
        if (row != null) row.setPressed(false);
        switch (up) {
            case CLICK:
                if (row != null) row.performClick();
                return true;
            case CONSUMED:
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
