package com.gathin.tvhome;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * The Widgets manager inside Launcher settings' own window: the grid of widgets, the slot picker (over a drawn
 * preview of Home's header, see {@link HomePreview}) and a widget's settings. Every screen is a layer on top of the
 * panel, which stays open and dimmed underneath; when the last one closes, focus returns to the row it was on.
 * Nothing here starts or finishes an activity. Choices are saved at once, and {@code onChanged} lets the panel mark
 * them so Home rebuilds when it next resumes.
 */
final class WidgetsFlow implements WidgetOverlays.Host {

    private final Activity activity;
    private final FrameLayout root;
    private final Config config;
    private final boolean rtlHeader;
    private final WidgetStore store;
    private final Runnable onChanged;
    private final Runnable onClosed;
    private WidgetPlacements placements;
    private WidgetOverlays.Overlay overlay;
    /** The view that had focus before the first screen opened; it gets focus back when the last one closes. */
    private View returnFocus;

    WidgetsFlow(Activity activity, FrameLayout root, Config config, Runnable onChanged, Runnable onClosed) {
        this.activity = activity;
        this.root = root;
        this.config = config;
        this.rtlHeader = activity.getResources().getBoolean(R.bool.rtl_menu_bar);
        this.store = new WidgetStore(activity);
        this.onChanged = onChanged;
        this.onClosed = onClosed;
        this.placements = store.placements();
    }

    // ---- what the screens ask ------------------------------------------------------------------------------------

    @Override public Activity activity() { return activity; }
    @Override public boolean animations() { return config.animations; }
    @Override public boolean reducedMotion() { return config.reducedMotion; }
    Config config() { return config; }
    WidgetStore store() { return store; }
    WidgetPlacements placements() { return placements; }

    /** Whether Home's header can host the slot with the owner's layout choices (see WidgetMount.create). */
    boolean slotAvailable(WidgetSlot slot) {
        return WidgetMount.slotAvailable(slot, rtlHeader, config.topMenu);
    }

    WidgetPlacements.Result place(WidgetDef def, WidgetSlot slot) {
        WidgetPlacements.Result result = placements.place(def.id, slot, WidgetRegistry.RULES);
        if (result == WidgetPlacements.Result.PLACED || result == WidgetPlacements.Result.SWAPPED) {
            store.save(placements);
            onChanged.run();
        }
        return result;
    }

    void remove(WidgetDef def) {
        placements.remove(def.id);
        store.save(placements);
        onChanged.run();
    }

    /** An option changed in a widget's settings: Home shows it when it resumes. */
    void optionChanged() {
        onChanged.run();
    }

    void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    // ---- screens -------------------------------------------------------------------------------------------------

    /** The Widgets row: the grid of widgets over the panel. */
    void open() {
        placements = store.placements();
        openManager();
    }

    void openManager() {
        show(new WidgetOverlays.Grid(this));
    }

    void openSlots(WidgetDef def) {
        show(new WidgetOverlays.Slots(this, def));
    }

    void openSettings(WidgetDef def) {
        if (def.options.isEmpty()) openManager();
        else show(new WidgetOverlays.Settings(this, def));
    }

    boolean isOpen() {
        return overlay != null;
    }

    private void show(WidgetOverlays.Overlay next) {
        if (overlay == null) returnFocus = activity.getCurrentFocus();
        close(false);
        overlay = next;
        root.addView(next.layer(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        next.opened();
    }

    /** Closes the open screen and returns focus to the settings row it came from. */
    @Override
    public void closeOverlay() {
        close(true);
    }

    private void close(boolean restoreFocus) {
        WidgetOverlays.Overlay open = overlay;
        if (open == null) return;
        overlay = null;
        open.closed();
        root.removeView(open.layer());
        if (!restoreFocus) return;
        View back = returnFocus;
        returnFocus = null;
        if (back != null && back.isAttachedToWindow() && back.getVisibility() == View.VISIBLE) back.requestFocus();
        onClosed.run();
    }

    /** True while a screen is open: the keys it uses are its own, whatever has focus behind it. */
    boolean dispatchKey(KeyEvent event) {
        return overlay != null && overlay.dispatch(event);
    }
}
