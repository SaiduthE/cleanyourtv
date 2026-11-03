package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Widgets manager's screens, in the style of the settings panels (the panel colours, fonts and focus slabs),
 * always on top of whatever is behind them: the grid of widgets, the slot picker (over a preview of Home's header),
 * a widget's settings, and, on Home, the brightness control. {@link WidgetsFlow} (inside Launcher settings) and
 * {@link HomeWidgets} (on Home) each keep at most one open and send it the keys.
 */
final class WidgetOverlays {

    private WidgetOverlays() { }

    private static int dp(Context ctx, float v) {
        return PanelRows.dp(ctx, v);
    }

    private static TextView text(Context ctx, String s, float sp, int color, int fontRes) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(ctx, fontRes));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The opaque card every screen but the slot picker sits in. */
    private static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(PanelRows.popupBackground(ctx, PanelRows.CARD_RADIUS_DP));
        box.setElevation(dp(ctx, 28));
        int pad = dp(ctx, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    static String slotLabel(Context ctx, WidgetSlot slot) {
        switch (slot) {
            case CLOCK_SIDE: return ctx.getString(R.string.widget_slot_clock_side);
            case BESIDE_WEATHER: return ctx.getString(R.string.widget_slot_beside_weather);
            case TOP_BAR_START: return ctx.getString(R.string.widget_slot_top_bar_start);
            default: return ctx.getString(R.string.widget_slot_top_bar_end);
        }
    }

    /** What every screen needs of whoever shows it: the activity, the motion settings, and a way to close. */
    interface Host {
        Activity activity();

        boolean animations();

        boolean reducedMotion();

        void closeOverlay();
    }

    // ---- the base ------------------------------------------------------------------------------------------------

    /** One open screen: a full-screen layer that takes the arrows, OK and Back, and passes every other key on. */
    abstract static class Overlay {
        final Host host;
        final Activity activity;
        private final FrameLayout layer;
        private final Set<Integer> pressed = new HashSet<>();

        Overlay(Host host, int scrim) {
            this.host = host;
            this.activity = host.activity();
            layer = new FrameLayout(activity);
            layer.setBackgroundColor(scrim);
            layer.setClickable(true);
            // Above Home's glass and tiles, which have elevation of their own.
            layer.setElevation(dp(activity, 80));
        }

        final FrameLayout layer() {
            return layer;
        }

        int anim(int baseMs) {
            return SettingsModel.duration(host.animations(), host.reducedMotion(), baseMs);
        }

        void opened() {
            int ms = anim(160);
            if (ms > 0) {
                layer.setAlpha(0f);
                layer.animate().alpha(1f).setDuration(ms).start();
            }
        }

        void closed() { }

        /** An arrow: dx and dy are -1, 0 or 1. */
        void onArrow(int dx, int dy) { }

        void onOk() { }

        void onBack() {
            host.closeOverlay();
        }

        final boolean dispatch(KeyEvent event) {
            int code = event.getKeyCode();
            int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
            if (dx == 0 && dy == 0 && !ok && !back) return false;   // volume and the like pass through
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (dx != 0 || dy != 0) onArrow(dx, dy);
                else if (event.getRepeatCount() == 0) pressed.add(code);
                return true;
            }
            // OK and Back act on the release, and only if their press began here: the press that opened this
            // screen must not also choose something on it.
            if (pressed.remove(code) && !event.isCanceled()) {
                if (ok) onOk();
                else onBack();
            }
            return true;
        }
    }

    // ---- the grid of widgets -------------------------------------------------------------------------------------

    static final class Grid extends Overlay {
        private final WidgetsFlow flow;
        private static final int COLUMNS = 3;
        private static final int TILE_W = 150, TILE_H = 116, GAP = 8, MAX_ROWS = 3;
        private final List<WidgetDef> defs = new ArrayList<>(WidgetRegistry.all());
        private final List<View> tiles = new ArrayList<>();
        private int selected;

        Grid(WidgetsFlow flow) {
            super(flow, 0xB3000000);
            this.flow = flow;
            Context ctx = activity;
            LinearLayout box = card(ctx);
            box.addView(text(ctx, ctx.getString(R.string.widgets_title), 22, 0xFFFFFFFF, R.string.font_medium));
            TextView sub = text(ctx, ctx.getString(R.string.widgets_hint), 13, 0x80FFFFFF, R.string.font_regular);
            sub.setPadding(0, dp(ctx, 4), 0, dp(ctx, 14));
            box.addView(sub);

            LinearLayout rows = new LinearLayout(ctx);
            rows.setOrientation(LinearLayout.VERTICAL);
            int rowCount = (defs.size() + COLUMNS - 1) / COLUMNS;
            for (int r = 0; r < rowCount; r++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < COLUMNS; c++) {
                    int i = r * COLUMNS + c;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, TILE_W), dp(ctx, TILE_H));
                    lp.setMargins(dp(ctx, GAP / 2f), dp(ctx, GAP / 2f), dp(ctx, GAP / 2f), dp(ctx, GAP / 2f));
                    if (i < defs.size()) {
                        View tile = tile(defs.get(i));
                        tiles.add(tile);
                        row.addView(tile, lp);
                    } else {
                        row.addView(new View(ctx), lp);   // keeps the last row's tiles on their columns
                    }
                }
                rows.addView(row);
            }
            if (rowCount > MAX_ROWS) {
                ScrollView scroll = new ScrollView(ctx);
                scroll.setVerticalScrollBarEnabled(false);
                scroll.addView(rows);
                box.addView(scroll, new LinearLayout.LayoutParams(dp(ctx, COLUMNS * (TILE_W + GAP)),
                        dp(ctx, MAX_ROWS * (TILE_H + GAP))));
            } else {
                box.addView(rows);
            }
            layer().addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        private View tile(WidgetDef def) {
            Context ctx = activity;
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
            tile.setBackground(PanelRows.slabBackground(ctx, 16, TILE_H, anim(120)));
            boolean available = def.availability.available(ctx);
            WidgetSlot slot = flow.placements().slotOf(def.id);
            tile.setAlpha(available || slot != null ? 1f : 0.55f);

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(def.iconRes);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
            TextView label = text(ctx, ctx.getString(def.labelRes), 16, 0xEBFFFFFF, R.string.font_medium);
            label.setPadding(0, dp(ctx, 8), 0, 0);
            tile.addView(label);
            String caption = !available && slot == null ? ctx.getString(R.string.widget_not_installed)
                    : slot == null ? ctx.getString(R.string.widget_not_on_home) : slotLabel(ctx, slot);
            TextView sub = text(ctx, caption, 11, 0x99FFFFFF, R.string.font_regular);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(ctx, 3), 0, 0);
            tile.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setContentDescription(ctx.getString(def.labelRes) + ", " + caption);
            return tile;
        }

        @Override
        void opened() {
            super.opened();
            focusTile();
        }

        private void focusTile() {
            if (selected >= 0 && selected < tiles.size()) tiles.get(selected).requestFocus();
        }

        @Override
        void onArrow(int dx, int dy) {
            selected = WidgetGridNav.move(selected, dx, dy, COLUMNS, tiles.size());
            focusTile();
        }

        @Override
        void onOk() {
            if (selected < 0 || selected >= defs.size()) return;
            WidgetDef def = defs.get(selected);
            // A missing source is said plainly, and nothing is placed; one already on Home can still be moved or removed.
            if (!def.availability.available(activity) && flow.placements().slotOf(def.id) == null) {
                flow.toast(def.unavailableRes);
                return;
            }
            flow.openSlots(def);
        }
    }

    // ---- the slot picker, over a preview of Home's header ---------------------------------------------------------------------

    static final class Slots extends Overlay {
        private final WidgetsFlow flow;
        private final WidgetDef def;
        private final List<WidgetSlot> order = new ArrayList<>();
        private final Map<WidgetSlot, View> outlines = new EnumMap<>(WidgetSlot.class);
        private final HomePreview preview;
        private final Map<WidgetSlot, TextView> captions = new EnumMap<>(WidgetSlot.class);
        private final TextView status;
        private final View remove;
        private int selected;      // index into order; -1 is the Remove button
        private int lastSlot;
        private ViewTreeObserver.OnGlobalLayoutListener layout;

        Slots(WidgetsFlow flow, WidgetDef def) {
            super(flow, 0x99000000);
            this.flow = flow;
            this.def = def;
            Context ctx = activity;
            WidgetSlot current = flow.placements().slotOf(def.id);
            for (WidgetSlot slot : WidgetSlot.values()) {
                if (def.supports(slot) && flow.slotAvailable(slot)) order.add(slot);
            }
            // Home's header drawn to size, with the slots where the real Home has them.
            preview = new HomePreview(ctx, flow.config(), flow.placements(), flow::slotAvailable, order);
            layer().addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            for (WidgetSlot slot : order) {
                View outline = new View(ctx);
                outline.setFocusable(true);
                outline.setClickable(true);
                outline.setBackground(outlineBackground());
                outline.setSelected(slot == current);
                outline.setVisibility(View.INVISIBLE);   // placed once the real layout has made room
                outline.setContentDescription(slotLabel(ctx, slot));
                outlines.put(slot, outline);
                layer().addView(outline, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
                TextView caption = text(ctx, caption(slot), 13, 0xEBFFFFFF, R.string.font_medium);
                caption.setGravity(Gravity.CENTER);
                caption.setVisibility(View.INVISIBLE);
                captions.put(slot, caption);
                layer().addView(caption, new FrameLayout.LayoutParams(dp(ctx, 190),
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
                WidgetPlacements.Result r = flow.placements().check(def.id, slot, WidgetRegistry.RULES);
                if (r == WidgetPlacements.Result.OCCUPIED) outline.setAlpha(0.5f);
            }
            if (current != null && order.contains(current)) selected = order.indexOf(current);
            else {
                for (int i = 0; i < order.size(); i++) {
                    if (flow.placements().widgetIn(order.get(i)) == null) { selected = i; break; }
                }
            }
            lastSlot = selected;

            LinearLayout bottom = new LinearLayout(ctx);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView title = text(ctx, ctx.getString(R.string.widget_slots_title, ctx.getString(def.labelRes)), 24,
                    0xFFFFFFFF, R.string.font_medium);
            bottom.addView(title);
            TextView hint = text(ctx, ctx.getString(R.string.widget_slots_hint), 13, 0xA6FFFFFF, R.string.font_regular);
            hint.setPadding(0, dp(ctx, 6), 0, 0);
            bottom.addView(hint);
            status = text(ctx, "", 14, 0xFFFFC857, R.string.font_medium);
            status.setPadding(0, dp(ctx, 10), 0, 0);
            status.setGravity(Gravity.CENTER);
            bottom.addView(status, new LinearLayout.LayoutParams(dp(ctx, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (current != null) {
                SettingRowView row = new SettingRowView(ctx, ctx.getString(R.string.widget_remove), false, anim(120));
                remove = row;
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(ctx, 240), dp(ctx, PanelRows.ROW_DP));
                rp.topMargin = dp(ctx, 12);
                bottom.addView(row, rp);
            } else {
                remove = null;
            }
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            bp.bottomMargin = dp(ctx, 44);
            layer().addView(bottom, bp);
        }

        /** Dashed at rest, the panel's focus slab when focused, the accent when it is the current place. */
        private Drawable outlineBackground() {
            PanelTheme theme = PanelTheme.get(activity);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[] {android.R.attr.state_focused},
                    shape(theme.focusFill, dp(activity, 3), theme.focusStroke, 0, 0));
            states.addState(new int[] {android.R.attr.state_selected},
                    shape(theme.selectedFill, dp(activity, 2), theme.selectedStroke, 0, 0));
            states.addState(new int[0], shape(0x14FFFFFF, dp(activity, 2), 0xB3FFFFFF, dp(activity, 8), dp(activity, 5)));
            return states;
        }

        private GradientDrawable shape(int fill, int stroke, int strokeColor, float dash, float gap) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setColor(fill);
            d.setCornerRadius(dp(activity, 14));
            if (dash > 0) d.setStroke(stroke, strokeColor, dash, gap);
            else d.setStroke(stroke, strokeColor);
            return d;
        }

        private String caption(WidgetSlot slot) {
            Context ctx = activity;
            String name = slotLabel(ctx, slot);
            String occupant = flow.placements().widgetIn(slot);
            if (occupant == null) return name;
            WidgetDef other = WidgetRegistry.get(occupant);
            String otherName = other == null ? occupant : ctx.getString(other.labelRes);
            if (occupant.equals(def.id)) return name + " (" + ctx.getString(R.string.widget_slot_now) + ")";
            WidgetPlacements.Result r = flow.placements().check(def.id, slot, WidgetRegistry.RULES);
            return name + "\n" + ctx.getString(r == WidgetPlacements.Result.SWAPPED ? R.string.widget_slot_swap
                    : R.string.widget_slot_taken, otherName);
        }

        @Override
        void opened() {
            super.opened();
            ViewTreeObserver observer = layer().getViewTreeObserver();
            layout = () -> {
                if (layer().getViewTreeObserver().isAlive()) {
                    layer().getViewTreeObserver().removeOnGlobalLayoutListener(layout);
                }
                placeOutlines();
            };
            observer.addOnGlobalLayoutListener(layout);
            layer().requestLayout();
            focusSelected();
        }

        private void placeOutlines() {
            int screenW = layer().getWidth();
            for (WidgetSlot slot : order) {
                View target = preview.slotView(slot);
                if (target == null || target.getWidth() == 0) continue;
                Rect r = WidgetMount.boundsIn(layer(), target);
                int pad = dp(activity, 4);
                View outline = outlines.get(slot);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) outline.getLayoutParams();
                lp.width = r.width() + 2 * pad;
                lp.height = r.height() + 2 * pad;
                lp.leftMargin = r.left - pad;
                lp.topMargin = r.top - pad;
                outline.setLayoutParams(lp);
                outline.setVisibility(View.VISIBLE);
                TextView caption = captions.get(slot);
                FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) caption.getLayoutParams();
                // No wider than its own outline, so the captions of two chips side by side never overlap.
                cp.width = Math.min(cp.width, r.width() + 2 * pad);
                int center = r.centerX();
                cp.leftMargin = Math.max(dp(activity, 8), Math.min(center - cp.width / 2,
                        screenW - cp.width - dp(activity, 8)));
                cp.topMargin = r.bottom + pad + dp(activity, 8);
                caption.setLayoutParams(cp);
                caption.setVisibility(View.VISIBLE);
            }
            focusSelected();
        }

        private void focusSelected() {
            if (selected < 0) {
                if (remove != null) remove.requestFocus();
            } else if (selected < order.size()) {
                outlines.get(order.get(selected)).requestFocus();
            }
        }

        @Override
        void closed() {
            if (layout != null && layer().getViewTreeObserver().isAlive()) {
                layer().getViewTreeObserver().removeOnGlobalLayoutListener(layout);
            }
        }

        @Override
        void onArrow(int dx, int dy) {
            if (dy > 0 && selected >= 0 && remove != null) {
                lastSlot = selected;
                selected = -1;
            } else if (dy < 0 && selected < 0) {
                selected = Math.min(lastSlot, order.size() - 1);
            } else if (dx != 0 && selected >= 0) {
                selected = Math.max(0, Math.min(order.size() - 1, selected + dx));
            }
            focusSelected();
        }

        @Override
        void onOk() {
            Context ctx = activity;
            if (selected < 0) {
                flow.remove(def);
                flow.toast(ctx.getString(R.string.widget_removed, ctx.getString(def.labelRes)));
                flow.openManager();
                return;
            }
            if (selected >= order.size()) return;
            WidgetSlot slot = order.get(selected);
            WidgetPlacements.Result r = flow.placements().check(def.id, slot, WidgetRegistry.RULES);
            if (r == WidgetPlacements.Result.OCCUPIED) {
                // Refused, and said so: the occupant cannot move to where this widget is now.
                String other = flow.placements().widgetIn(slot);
                WidgetDef otherDef = WidgetRegistry.get(other);
                String otherName = otherDef == null ? other : ctx.getString(otherDef.labelRes);
                WidgetSlot mine = flow.placements().slotOf(def.id);
                status.setText(mine == null ? ctx.getString(R.string.widget_taken_none, otherName)
                        : ctx.getString(R.string.widget_taken_stuck, otherName, slotLabel(ctx, mine)));
                return;
            }
            String swapped = null;
            if (r == WidgetPlacements.Result.SWAPPED) {
                WidgetDef otherDef = WidgetRegistry.get(flow.placements().widgetIn(slot));
                swapped = otherDef == null ? null : ctx.getString(otherDef.labelRes);
            }
            flow.place(def, slot);
            if (swapped != null) flow.toast(ctx.getString(R.string.widget_swapped, swapped));
            flow.openSettings(def);
        }

        @Override
        void onBack() {
            flow.openManager();
        }
    }

    // ---- a widget's settings -------------------------------------------------------------------------------------

    static final class Settings extends Overlay {
        private final WidgetsFlow flow;
        private final WidgetDef def;
        private final List<SettingRowView> rows = new ArrayList<>();
        private int selected;

        Settings(WidgetsFlow flow, WidgetDef def) {
            super(flow, 0xB3000000);
            this.flow = flow;
            this.def = def;
            Context ctx = activity;
            LinearLayout box = card(ctx);
            box.addView(text(ctx, ctx.getString(R.string.widget_settings_title, ctx.getString(def.labelRes)), 22,
                    0xFFFFFFFF, R.string.font_medium));
            View gap = new View(ctx);
            box.addView(gap, new LinearLayout.LayoutParams(1, dp(ctx, 12)));
            for (WidgetOption option : def.options) {
                SettingRowView row = new SettingRowView(ctx, ctx.getString(option.labelRes), option.isAction(), anim(120));
                row.setLayoutParams(PanelRows.rowParams(ctx));
                box.addView(row, new LinearLayout.LayoutParams(dp(ctx, 380), dp(ctx, PanelRows.ROW_DP)));
                rows.add(row);
            }
            SettingRowView done = new SettingRowView(ctx, ctx.getString(R.string.widget_done), false, anim(120));
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(dp(ctx, 380), dp(ctx, PanelRows.ROW_DP));
            doneLp.topMargin = dp(ctx, 8);
            box.addView(done, doneLp);
            rows.add(done);
            layer().addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            refreshValues();
        }

        private void refreshValues() {
            for (int i = 0; i < def.options.size(); i++) {
                WidgetOption option = def.options.get(i);
                if (option.isAction()) continue;
                rows.get(i).setValue(activity.getString(option.labelOf(flow.store().option(def, option))));
            }
        }

        @Override
        void opened() {
            super.opened();
            rows.get(selected).requestFocus();
        }

        @Override
        void onArrow(int dx, int dy) {
            if (dy != 0) {
                selected = Math.max(0, Math.min(rows.size() - 1, selected + dy));
                rows.get(selected).requestFocus();
            } else if (selected < def.options.size()) {
                WidgetOption option = def.options.get(selected);
                if (!option.isAction()) step(option, dx > 0);
            }
        }

        private void step(WidgetOption option, boolean forward) {
            String now = flow.store().option(def, option);
            flow.store().put(WidgetOption.storageKey(def.id, option.key), forward ? option.next(now) : option.previous(now));
            refreshValues();
            rows.get(selected).flashValue(anim(200) > 0);
            flow.optionChanged();
        }

        @Override
        void onOk() {
            if (selected >= def.options.size()) {
                flow.openManager();
                return;
            }
            WidgetOption option = def.options.get(selected);
            if (option.isAction()) def.actions.run(activity, option.key);
            else step(option, true);
        }

        @Override
        void onBack() {
            flow.openManager();
        }
    }

    // ---- the brightness control ----------------------------------------------------------------------------------

    static final class BrightnessQuick extends Overlay {
        private final BrightnessWidget widget;
        private final TextView value;
        private final TextView hint;
        private final LevelBar bar;
        private BrightnessSource.Level level;
        private boolean inFlight;
        private boolean closedAlready;

        BrightnessQuick(Host host, BrightnessWidget widget) {
            super(host, 0x00000000);
            this.widget = widget;
            Context ctx = activity;
            LinearLayout box = card(ctx);
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(ctx, ctx.getString(R.string.widget_brightness), 18, 0xFFFFFFFF, R.string.font_medium);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            value = text(ctx, "--%", 18, 0xFFFFFFFF, R.string.font_medium);
            head.addView(value);
            box.addView(head, new LinearLayout.LayoutParams(dp(ctx, 380), ViewGroup.LayoutParams.WRAP_CONTENT));
            bar = new LevelBar(ctx);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(ctx, 380), dp(ctx, 10));
            bp.topMargin = dp(ctx, 14);
            bp.bottomMargin = dp(ctx, 12);
            box.addView(bar, bp);
            hint = text(ctx, ctx.getString(R.string.widget_brightness_hint), 13, 0x99FFFFFF, R.string.font_regular);
            box.addView(hint, new LinearLayout.LayoutParams(dp(ctx, 380), ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(ctx, 56);
            layer().addView(box, lp);
        }

        @Override
        void opened() {
            super.opened();
            widget.source().read(this::show);
        }

        private void show(BrightnessSource.Level reading) {
            if (closedAlready) return;
            inFlight = false;
            level = reading;
            if (reading == null) {
                value.setText("--%");
                bar.set(0);
                hint.setText(R.string.widget_brightness_unreachable);
                return;
            }
            value.setText(reading.level + "%");
            bar.set(reading.level);
            hint.setText(reading.canDraw ? R.string.widget_brightness_hint : R.string.widget_brightness_no_overlay);
        }

        @Override
        void onArrow(int dx, int dy) {
            if (dx == 0 || inFlight) return;
            inFlight = true;   // a held key repeats faster than the add-on answers: drop the ones in between
            widget.source().step(dx, widget.stepSize(), this::show);
        }

        @Override
        void onOk() {
            host.closeOverlay();
        }

        @Override
        void closed() {
            closedAlready = true;
            if (level != null) widget.setLevel(level);   // the chip shows what was last set
        }
    }

    /** A thin track with the accent filled to the level. */
    private static final class LevelBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int accent;
        private int percent;

        LevelBar(Context ctx) {
            super(ctx);
            accent = PanelTheme.get(ctx).accent;
        }

        void set(int percent) {
            this.percent = Math.max(0, Math.min(100, percent));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(0x33FFFFFF);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setColor(accent);
            rect.set(0, 0, Math.max(getHeight(), getWidth() * percent / 100f), getHeight());
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
