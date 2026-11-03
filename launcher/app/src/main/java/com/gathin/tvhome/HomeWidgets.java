package com.gathin.tvhome;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.EnumMap;
import java.util.Map;

/**
 * The widgets docked in Home's header, and the Widgets manager that arranges them. It builds each widget from the
 * saved placements (see {@link WidgetStore}), keeps the D-pad order across the header (clock row, then the top bar
 * with its widgets before and after the buttons), and owns the one overlay that can be open: the manager's grid, the
 * slot picker over the real Home, a widget's settings, or the brightness control.
 *
 * MainActivity creates it once, forwards resume, pause and key events, and opens the manager when Launcher settings
 * asks for it.
 */
final class HomeWidgets {

    /** What the widgets ask of Home for the keys they handle. */
    interface Nav {
        /** Down: back to the dock tile Up came from. */
        void focusGrid();

        /** Right of a widget before the bar: the first top-bar button. */
        void focusFirstButton();

        /** Left of a widget after the bar: the last top-bar button. */
        void focusLastButton();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final boolean animations, reducedMotion;
    private final Nav nav;
    private final WidgetStore store;
    private final Map<WidgetSlot, WidgetMount> mounts = new EnumMap<>(WidgetSlot.class);
    private final Map<WidgetSlot, HomeWidget> live = new EnumMap<>(WidgetSlot.class);
    private WidgetPlacements placements;
    private WidgetOverlays.Overlay overlay;
    private boolean resumed;
    /** The view that had focus before the first overlay opened; it gets focus back when the last one closes. */
    private android.view.View returnFocus;

    HomeWidgets(Activity activity, FrameLayout root, boolean animations, boolean reducedMotion, boolean topMenu, Nav nav) {
        this.activity = activity;
        this.root = root;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.nav = nav;
        this.store = new WidgetStore(activity);
        for (WidgetSlot slot : WidgetSlot.values()) {
            WidgetMount mount = WidgetMount.create(activity, slot, topMenu);
            if (mount != null) mounts.put(slot, mount);
        }
        rebuild();
    }

    Activity activity() { return activity; }
    FrameLayout root() { return root; }
    Nav nav() { return nav; }
    WidgetStore store() { return store; }
    boolean animations() { return animations; }
    boolean reducedMotion() { return reducedMotion; }
    WidgetPlacements placements() { return placements; }
    WidgetMount mount(WidgetSlot slot) { return mounts.get(slot); }
    HomeWidget live(WidgetSlot slot) { return live.get(slot); }

    /** Whether the layout can host the slot right now (the mirrored Hebrew header and the one-gear menu cannot). */
    boolean slotAvailable(WidgetSlot slot) {
        return mounts.containsKey(slot);
    }

    // ---- building ------------------------------------------------------------------------------------------------

    /** Removes every widget and docks the ones the saved placements name. */
    void rebuild() {
        for (HomeWidget widget : live.values()) widget.detach();
        live.clear();
        placements = store.placements();
        for (WidgetSlot slot : WidgetSlot.values()) {
            WidgetMount mount = mounts.get(slot);
            String id = placements.widgetIn(slot);
            WidgetDef def = id == null ? null : WidgetRegistry.get(id);
            if (mount == null || def == null || !def.supports(slot)) continue;
            HomeWidget widget = def.factory.create(new WidgetContext(activity, def, slot, mount, animations,
                    reducedMotion, this));
            if (widget == null) continue;
            live.put(slot, widget);
            if (resumed) widget.resume();
        }
    }

    void resume() {
        resumed = true;
        for (HomeWidget widget : live.values()) widget.resume();
    }

    void pause() {
        resumed = false;
        close(false);   // another app is in front: leave focus alone
        returnFocus = null;
        for (HomeWidget widget : live.values()) widget.pause();
    }

    // ---- the header's D-pad order --------------------------------------------------------------------------------

    /** Left from the first top-bar button: the widget before the bar, else the one in the clock row. */
    boolean focusBeforeBar() {
        HomeWidget start = live.get(WidgetSlot.TOP_BAR_START);
        if (start != null && start.focus()) return true;
        HomeWidget clock = live.get(WidgetSlot.CLOCK_SIDE);
        return clock != null && clock.focus();
    }

    /** Right from the last top-bar button: the widget after the bar. */
    boolean focusAfterBar() {
        HomeWidget end = live.get(WidgetSlot.TOP_BAR_END);
        return end != null && end.focus();
    }

    /** A widget's Left (-1) or Right (+1): the next focusable thing along the header. Always consumes the key. */
    boolean neighbour(WidgetSlot from, int direction) {
        switch (from) {
            case CLOCK_SIDE:
                if (direction > 0) {
                    HomeWidget start = live.get(WidgetSlot.TOP_BAR_START);
                    if (start == null || !start.focus()) nav.focusFirstButton();
                }
                break;
            case TOP_BAR_START:
                if (direction > 0) nav.focusFirstButton();
                else {
                    HomeWidget clock = live.get(WidgetSlot.CLOCK_SIDE);
                    if (clock != null) clock.focus();
                }
                break;
            default:
                if (direction < 0) nav.focusLastButton();
                break;
        }
        return true;
    }

    // ---- placing -------------------------------------------------------------------------------------------------

    /** Moves a widget to a slot (or swaps) and applies it at once. */
    WidgetPlacements.Result place(WidgetDef def, WidgetSlot slot) {
        WidgetPlacements.Result result = placements.place(def.id, slot, WidgetRegistry.RULES);
        if (result == WidgetPlacements.Result.PLACED || result == WidgetPlacements.Result.SWAPPED) {
            store.save(placements);
            rebuild();
        }
        return result;
    }

    void remove(WidgetDef def) {
        placements.remove(def.id);
        store.save(placements);
        rebuild();
    }

    /** An option changed or an action ran: show the widget again. */
    void optionChanged(WidgetDef def) {
        WidgetSlot slot = placements.slotOf(def.id);
        HomeWidget widget = slot == null ? null : live.get(slot);
        if (widget != null) widget.refresh();
    }

    // ---- overlays ------------------------------------------------------------------------------------------------

    /** The manager's grid of widgets (Launcher settings, Widgets). */
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

    /** OK on the brightness chip. */
    void openBrightnessControl(BrightnessWidget widget) {
        show(new WidgetOverlays.BrightnessQuick(this, widget));
    }

    void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }

    void show(WidgetOverlays.Overlay next) {
        if (overlay == null) returnFocus = activity.getCurrentFocus();
        close(false);
        overlay = next;
        root.addView(next.layer(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        next.opened();
    }

    /** Closes whatever is open, leaving no trace on Home. */
    void closeOverlay() {
        close(true);
    }

    private void close(boolean restoreFocus) {
        WidgetOverlays.Overlay open = overlay;
        if (open == null) return;
        overlay = null;
        open.closed();
        root.removeView(open.layer());
        if (!restoreFocus) return;
        android.view.View back = returnFocus;
        returnFocus = null;
        if (back != null && back.isAttachedToWindow() && back.getVisibility() == android.view.View.VISIBLE) {
            back.requestFocus();
        } else {
            nav.focusGrid();
        }
    }

    /** True while an overlay is open: the keys it uses are its own, whatever has focus behind it. */
    boolean dispatchKey(KeyEvent event) {
        return overlay != null && overlay.dispatch(event);
    }
}
