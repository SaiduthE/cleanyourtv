package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A drawn copy of Home's header (greeting, clock, date, the top buttons) at Home's own sizes and margins, with a box
 * where each widget slot sits, so the slot picker inside Launcher settings can show where a widget would go without
 * leaving the settings. It is only a picture: nothing in it takes focus or does anything.
 *
 * The header is laid out as in activity_main.xml: 56 dp in from the sides, 26 dp down, the clock block on the left
 * and the buttons at the right edge, level with the clock row. With the date under the time (date_below_clock, as
 * Home has it by default) the clock row is the time and then the chips, 16 dp after the time, and the date is a
 * small line under it with no divider; otherwise a widget in the clock row takes the room after the date (and a
 * second line before it). {@link WidgetMount} makes the same on Home.
 */
final class HomePreview extends FrameLayout {

    static final int CLOCK_SLOT_W_DP = 220, CLOCK_SLOT_H_DP = 52;
    static final int BAR_SLOT_W_DP = 132, BAR_SLOT_H_DP = 42;

    private final Map<WidgetSlot, View> slotViews = new EnumMap<>(WidgetSlot.class);

    /**
     * @param available whether the layout can host a slot at all (a slot it cannot is not drawn)
     * @param open the slots the widget being placed can take; the rest are drawn only when something is docked there
     */
    HomePreview(Context ctx, Config cfg, WidgetPlacements placements, Predicate<WidgetSlot> available,
                List<WidgetSlot> open) {
        super(ctx);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xF2181B22, 0xF20E1015});
        float r = dp(ctx, 20);
        bg.setCornerRadii(new float[] {0, 0, 0, 0, r, r, r, r});
        setBackground(bg);
        setPadding(dp(ctx, 56), dp(ctx, 26), dp(ctx, 56), dp(ctx, 66));

        RelativeLayout header = new RelativeLayout(ctx);
        header.setClipChildren(false);
        addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int leftId = View.generateViewId();
        int buttonsId = View.generateViewId();

        LinearLayout left = new LinearLayout(ctx);
        left.setId(leftId);
        left.setOrientation(LinearLayout.VERTICAL);
        RelativeLayout.LayoutParams leftLp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        leftLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        leftLp.addRule(RelativeLayout.START_OF, buttonsId);
        leftLp.setMarginEnd(dp(ctx, 16));
        header.addView(left, leftLp);

        if (cfg.showGreeting) {
            String hello = ctx.getString(R.string.good_evening);
            if (cfg.greetingName != null && !cfg.greetingName.isEmpty()) {
                hello = ctx.getString(R.string.greeting_with_name, hello, cfg.greetingName);
            }
            TextView greeting = text(ctx, hello.toUpperCase(), 12, 0x8CFFFFFF, R.string.font_medium);
            greeting.setLetterSpacing(0.22f);
            greeting.setSingleLine(true);
            greeting.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.setMarginStart(dp(ctx, 4));
            left.addView(greeting, gp);
        }

        LinearLayout clockRow = new LinearLayout(ctx);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setBaselineAligned(true);
        left.addView(clockRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 52, 0xFFFFFFFF, R.string.font_light);
        clock.setLetterSpacing(-0.04f);
        clockRow.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock ampm = new TextClock(ctx);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("");
        style(ampm, 15, 0xB3FFFFFF, R.string.font_medium);
        ampm.setLetterSpacing(0.08f);
        ampm.setAllCaps(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.setMarginStart(dp(ctx, 6));
        ap.gravity = Gravity.BOTTOM;
        ap.bottomMargin = dp(ctx, 8);
        clockRow.addView(ampm, ap);

        boolean clockSide = available.test(WidgetSlot.CLOCK_SIDE)
                && (open.contains(WidgetSlot.CLOCK_SIDE) || placements.widgetIn(WidgetSlot.CLOCK_SIDE) != null);
        boolean dateBelow = cfg.dateBelowClock;
        // The gap before the first chip: beside the date it sits 6 dp after the divider (the weather chip's -10 dp
        // tuck, after the divider's own 16 dp); under the time it follows the time directly, 16 dp (the real chip pads
        // 8 dp more, so its art starts about 24 dp after the time). Later chips follow at the plain 8 dp.
        int firstChipMarginDp = ClockRowLayout.firstChipMargin(!dateBelow, 6, 16);

        TextClock date = new TextClock(ctx);
        date.setFormat12Hour(ctx.getString(R.string.date_format_home));
        date.setFormat24Hour(ctx.getString(R.string.date_format_home));
        style(date, 14, 0xA6FFFFFF, R.string.font_regular);
        date.setLetterSpacing(0.14f);
        date.setAllCaps(true);
        date.setSingleLine(true);
        date.setEllipsize(TextUtils.TruncateAt.END);
        if (dateBelow) {
            // Home's date line under the time: 60% of the date's size (never below 12 sp), 4 dp down, left with the time.
            date.setTextSize(Math.max(12f, 14f * 0.6f));
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            below.topMargin = dp(ctx, 4);
            left.addView(date, below);
        } else {
            clockRow.addView(divider(ctx), dividerParams(ctx));
            date.setMaxWidth(dp(ctx, 240));
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dp1.gravity = Gravity.BOTTOM;
            dp1.bottomMargin = dp(ctx, 9);
            clockRow.addView(date, dp1);
        }

        if (clockSide) {
            if (!dateBelow) clockRow.addView(divider(ctx), dividerParams(ctx));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(ctx, CLOCK_SLOT_W_DP),
                    dp(ctx, CLOCK_SLOT_H_DP));
            sp.gravity = Gravity.CENTER_VERTICAL;
            sp.setMarginStart(dp(ctx, firstChipMarginDp));
            View box = slotBox(ctx, placements.widgetIn(WidgetSlot.CLOCK_SIDE));
            slotViews.put(WidgetSlot.CLOCK_SIDE, box);
            clockRow.addView(box, sp);
        }

        if (available.test(WidgetSlot.BESIDE_WEATHER)
                && (open.contains(WidgetSlot.BESIDE_WEATHER) || placements.widgetIn(WidgetSlot.BESIDE_WEATHER) != null)) {
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(ctx, CLOCK_SLOT_W_DP),
                    dp(ctx, CLOCK_SLOT_H_DP));
            wp.gravity = Gravity.CENTER_VERTICAL;
            wp.setMarginStart(dp(ctx, clockSide ? 8 : firstChipMarginDp));
            View weatherBox = slotBox(ctx, placements.widgetIn(WidgetSlot.BESIDE_WEATHER));
            slotViews.put(WidgetSlot.BESIDE_WEATHER, weatherBox);
            clockRow.addView(weatherBox, wp);
        }

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setId(buttonsId);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        RelativeLayout.LayoutParams bp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.addRule(RelativeLayout.ALIGN_PARENT_END);
        if (dateBelow && cfg.iconOnlyButtons && !cfg.topMenu) {
            // Home keeps the icons level with the time, not with the date line under it.
            bp.addRule(RelativeLayout.ALIGN_TOP, leftId);
            header.addView(buttons, bp);
            View.OnLayoutChangeListener level = (v, l0, t0, r0, b0, ol, ot, or, ob) ->
                    buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                            + (clock.getHeight() - buttons.getHeight()) / 2f);
            clockRow.addOnLayoutChangeListener(level);
            buttons.addOnLayoutChangeListener(level);
        } else {
            bp.addRule(RelativeLayout.ALIGN_BOTTOM, leftId);
            bp.bottomMargin = dp(ctx, 6);
            header.addView(buttons, bp);
        }

        if (available.test(WidgetSlot.TOP_BAR_START)
                && (open.contains(WidgetSlot.TOP_BAR_START) || placements.widgetIn(WidgetSlot.TOP_BAR_START) != null)) {
            addBarSlot(ctx, buttons, WidgetSlot.TOP_BAR_START, placements, 0);
        }
        if (cfg.topMenu) {
            buttons.addView(pill(ctx, 0, R.drawable.ic_settings, true));
        } else {
            if (cfg.showInputsPill) buttons.addView(pill(ctx, R.string.inputs, R.drawable.ic_input, cfg.iconOnlyInputs));
            if (cfg.showNetworkPill) buttons.addView(pill(ctx, R.string.wifi, R.drawable.ic_wifi, cfg.iconOnlyButtons));
            buttons.addView(pill(ctx, R.string.settings, R.drawable.ic_settings, cfg.iconOnlyButtons));
        }
        if (available.test(WidgetSlot.TOP_BAR_END)
                && (open.contains(WidgetSlot.TOP_BAR_END) || placements.widgetIn(WidgetSlot.TOP_BAR_END) != null)) {
            addBarSlot(ctx, buttons, WidgetSlot.TOP_BAR_END, placements, 10);
        }
        ClockFormat.apply(this, cfg.clockFormat);
    }

    /** The box that stands for the slot (its outline is drawn over it by the slot picker), or null when not drawn. */
    View slotView(WidgetSlot slot) {
        return slotViews.get(slot);
    }

    private void addBarSlot(Context ctx, LinearLayout buttons, WidgetSlot slot, WidgetPlacements placements,
                            int marginStartDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, BAR_SLOT_W_DP), dp(ctx, BAR_SLOT_H_DP));
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.setMarginStart(dp(ctx, marginStartDp));
        View box = slotBox(ctx, placements.widgetIn(slot));
        slotViews.put(slot, box);
        if (slot == WidgetSlot.TOP_BAR_START) {
            lp.setMarginEnd(dp(ctx, 0));
            buttons.addView(box, 0, lp);
        } else {
            buttons.addView(box, lp);
        }
    }

    /** A rounded chip with the docked widget's icon and name, or empty when nothing is docked in the slot. */
    private static View slotBox(Context ctx, String widgetId) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        WidgetDef def = widgetId == null ? null : WidgetRegistry.get(widgetId);
        if (def == null) return box;
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x1AFFFFFF);
        chip.setCornerRadius(dp(ctx, 14));
        box.setBackground(chip);
        box.setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(def.iconRes);
        icon.setAlpha(0.7f);
        box.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)));
        TextView name = text(ctx, ctx.getString(def.labelRes), 13, 0x99FFFFFF, R.string.font_medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        np.setMarginStart(dp(ctx, 6));
        box.addView(name, np);
        return box;
    }

    private static TextView pill(Context ctx, int labelRes, int iconRes, boolean iconOnly) {
        int style = iconOnly ? R.style.TopButtonIcon : R.style.TopButton;
        TextView pill = new TextView(ctx, null, 0, style);
        pill.setFocusable(false);
        pill.setClickable(false);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        if (iconOnly) {
            pill.setContentDescription(labelRes == 0 ? "" : ctx.getString(labelRes));
        } else {
            pill.setText(labelRes);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                iconOnly ? dp(ctx, 42) : ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 42));
        lp.setMarginStart(dp(ctx, 10));
        pill.setLayoutParams(lp);
        return pill;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0x33FFFFFF);
        return v;
    }

    private static LinearLayout.LayoutParams dividerParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 1), dp(ctx, 26));
        lp.gravity = Gravity.BOTTOM;
        lp.setMarginStart(dp(ctx, 16));
        lp.setMarginEnd(dp(ctx, 16));
        lp.bottomMargin = dp(ctx, 8);
        return lp;
    }

    private static TextView text(Context ctx, String s, float sp, int color, int fontRes) {
        TextView t = new TextView(ctx);
        t.setText(s);
        style(t, sp, color, fontRes);
        return t;
    }

    private static void style(TextView t, float sp, int color, int fontRes) {
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(t.getContext(), fontRes));
        t.setIncludeFontPadding(false);
    }

    private static int dp(Context ctx, float v) {
        return PanelRows.dp(ctx, v);
    }
}
