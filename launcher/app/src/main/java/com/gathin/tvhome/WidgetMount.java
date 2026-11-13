package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One slot's place in Home's header layout: it adds a widget's view to the clock row or to the top bar (before the
 * Inputs pill, before the network pill, or after the Settings gear), makes room
 * for it, and takes it out again. Every view it adds carries {@link #TAG}, so the top bar's own code (which looks
 * for "the first button") skips them.
 *
 * The two clock-row slots (Beside the clock, Beside weather) share one {@link ClockRow}: the divider and the compact
 * date are there while either has a widget, and the chips follow the clock block in slot order whichever was docked
 * first. The top-bar slots share one {@link BarRow}, which hides a chip that would not leave the clock block
 * its room.
 */
final class WidgetMount {

    static final String TAG = "home_widget";

    static boolean isWidgetView(View v) {
        return TAG.equals(v.getTag());
    }

    final WidgetSlot slot;
    private final Activity activity;
    private final float density;
    // The clock row (CLOCK_SIDE, BESIDE_WEATHER).
    private ClockRow clock;
    private int ownMarginDp;
    private int heightDp;
    // The top bar (TOP_BAR_START, BESIDE_NETWORK, TOP_BAR_END).
    private ViewGroup bar;
    private BarRow barRow;

    private View content;
    private boolean contentShown;

    private WidgetMount(Activity activity, WidgetSlot slot) {
        this.activity = activity;
        this.slot = slot;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /**
     * Whether Home's header can host the slot with these layout choices (what {@link #create} decides on Home itself,
     * known without Home: the Widgets manager inside Launcher settings asks before it draws its picture of the header).
     */
    static boolean slotAvailable(WidgetSlot slot, boolean rtlMenuBar, boolean topMenu) {
        if (rtlMenuBar) return false;
        // With the date under the time the clock row ends with the time and its chips follow it, so Beside the clock
        // is there whichever way the date is laid out.
        return slot == WidgetSlot.CLOCK_SIDE || !topMenu;
    }

    /**
     * The mount for a slot, or null when the layout cannot host it: the mirrored Hebrew header, a header the row
     * cannot be found in, or the one-gear top menu for the top bar slots. The second slot of the clock row (and of
     * the top bar) shares the first one's row, so it is given that mount ({@code lead}).
     */
    static WidgetMount create(Activity activity, WidgetSlot slot, boolean topMenu, WidgetMount lead) {
        try {
            if (activity.getResources().getBoolean(R.bool.rtl_menu_bar)) return null;
            WidgetMount mount = new WidgetMount(activity, slot);
            if (slot == WidgetSlot.CLOCK_SIDE) return mount.initClockRow() ? mount : null;
            if (slot.inClockRow()) {
                if (lead == null || lead.clock == null) return null;
                mount.clock = lead.clock;
                mount.clock.mounts[slot.rowOrder()] = mount;
                return mount;
            }
            if (topMenu) return null;
            View buttons = activity.findViewById(R.id.topButtons);
            if (!(buttons instanceof ViewGroup)) return null;
            mount.bar = (ViewGroup) buttons;
            if (slot == WidgetSlot.TOP_BAR_START) {
                mount.barRow = new BarRow(activity, (ViewGroup) buttons);
            } else {
                if (lead == null || lead.barRow == null) return null;
                mount.barRow = lead.barRow;
            }
            mount.barRow.mounts[slot.barOrder()] = mount;
            return mount;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean initClockRow() {
        View clockView = activity.findViewById(R.id.clock);
        View date = activity.findViewById(R.id.date);
        if (clockView == null || date == null || !(clockView.getParent() instanceof LinearLayout)) return false;
        LinearLayout row = (LinearLayout) clockView.getParent();
        View dateColumn = (View) date.getParent();
        // date_below_clock moves the date column under the clock: the row then ends with the time.
        boolean dateBelow = dateColumn.getParent() != row && dateColumn.getParent() == row.getParent();
        if (dateColumn.getParent() != row && !dateBelow) return false;
        // A second line like the clock's own, in the same colour (white when header_white recoloured it).
        View first = activity.findViewById(R.id.clockDivider);
        View divider = new View(activity);
        divider.setTag(TAG);
        Drawable bg = first.getBackground();
        divider.setBackgroundColor(bg instanceof ColorDrawable ? ((ColorDrawable) bg).getColor() : 0x33FFFFFF);
        divider.setVisibility(View.GONE);
        // Below the clock the widget follows the time directly, so its divider goes at the end of the row.
        int at = dateBelow ? row.getChildCount() : row.indexOfChild(dateColumn) + 1;
        row.addView(divider, at,
                new LinearLayout.LayoutParams((LinearLayout.LayoutParams) first.getLayoutParams()));
        // A focused chip grows and casts a shadow past the row's edges.
        row.setClipChildren(false);
        if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).setClipChildren(false);
        clock = new ClockRow(row, dateColumn, (TextView) date, divider, dateBelow, density);
        clock.mounts[slot.rowOrder()] = this;
        return true;
    }

    // ---- the widget's view --------------------------------------------------------------------------------------

    /**
     * Puts the widget's view in the slot (hidden by the widget until it has something to show).
     * @param clockMarginStartDp the gap before it in the clock row when it is the first chip there, where the
     *                           weather chip tucks in under the divider; later chips follow at a plain gap
     */
    void add(View view, int heightDp, int clockMarginStartDp) {
        remove();
        content = view;
        view.setTag(TAG);
        // A chip kept on screen because the owner was on it moves on once focus leaves it.
        view.setOnFocusChangeListener((v, has) -> {
            if (!has) refit();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (clock != null) {
            this.heightDp = heightDp;
            this.ownMarginDp = clockMarginStartDp;
            clock.attach(this, view, lp);
        } else if (slot == WidgetSlot.TOP_BAR_START) {
            bar.addView(view, 0, lp);
            apply();
        } else if (slot == WidgetSlot.BESIDE_NETWORK) {
            // Right before the network pill (a hidden pill keeps its place, so the chip then sits where it would be),
            // after the Inputs pill and any top_bar_apps buttons, which are put in before the pill too.
            View network = activity.findViewById(R.id.btnWifi);
            int at = network == null ? -1 : bar.indexOfChild(network);
            lp.setMarginStart(dp(10));
            if (at >= 0) bar.addView(view, at, lp);
            else bar.addView(view, lp);
            apply();
        } else {
            lp.setMarginStart(dp(10));
            bar.addView(view, lp);
            apply();
        }
    }

    /** Takes the widget's view out and gives its room back. */
    void remove() {
        if (content != null && content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
        content = null;
        contentShown = false;
        apply();
    }

    /** The widget has something to show (true) or hides itself (false). */
    void setShown(boolean shown) {
        contentShown = shown;
        apply();
    }

    private void apply() {
        if (clock != null) clock.update();
        else if (barRow != null) barRow.scheduleFit();
    }

    private void refit() {
        if (clock != null) clock.scheduleFit();
        else if (barRow != null) barRow.scheduleFit();
    }

    private int viewCount() {
        return content != null && content.getParent() != null ? 1 : 0;
    }

    /** The view's rectangle in {@code ancestor}'s coordinates. */
    static Rect boundsIn(ViewGroup ancestor, View view) {
        Rect r = new Rect(0, 0, view.getWidth(), view.getHeight());
        ancestor.offsetDescendantRectToMyCoords(view, r);
        return r;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- the clock row ------------------------------------------------------------------------------------------

    /**
     * The clock row as the widgets share it: the chips after the clock block in slot order, the gaps between them,
     * the divider and compact date while any chip is there, and the check (from measured sizes, see
     * {@link ClockRowLayout#fit}) that a later chip never runs past the row into the top buttons.
     */
    private static final class ClockRow {
        /** The gap between one chip and the next. */
        private static final int GAP_DP = 8;
        /** The gap between the time and the first chip when the date is under the time (the chip pads 8 dp more). */
        private static final int FIRST_GAP_BELOW_DP = 16;

        final LinearLayout row;
        final View dateColumn;
        final TextView dateText;
        final View divider;
        final boolean dateBelow;
        final float density;
        final WidgetMount[] mounts = new WidgetMount[WidgetSlot.clockRow().length];
        private boolean fitPosted;

        ClockRow(LinearLayout row, View dateColumn, TextView dateText, View divider, boolean dateBelow, float density) {
            this.row = row;
            this.dateColumn = dateColumn;
            this.dateText = dateText;
            this.divider = divider;
            this.dateBelow = dateBelow;
            this.density = density;
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scheduleFit());
        }

        private int dp(float v) {
            return Math.round(v * density);
        }

        /** Whether the slot's chip is wanted on screen: its widget has something to show and the view is in the row. */
        private boolean wanted(WidgetMount m) {
            return m != null && m.content != null && m.contentShown && m.content.getParent() == row;
        }

        /**
         * The gap before each slot's chip in dp: the first chip that is wanted follows the time, the rest follow at
         * the plain gap (see {@link ClockRowLayout#chipMargins}); a slot with no widget on screen never takes the
         * first place.
         */
        private int[] marginsDp() {
            int n = mounts.length;
            boolean[] shown = new boolean[n];
            int[] own = new int[n];
            for (int i = 0; i < n; i++) {
                shown[i] = wanted(mounts[i]);
                own[i] = mounts[i] == null ? 0 : mounts[i].ownMarginDp;
            }
            return ClockRowLayout.chipMargins(shown, own, !dateBelow, FIRST_GAP_BELOW_DP, GAP_DP);
        }

        /** Adds the view after the views of the slots before this one, then settles gaps and heights. */
        void attach(WidgetMount mount, View view, LinearLayout.LayoutParams lp) {
            int[] counts = new int[mounts.length];
            for (int i = 0; i < mounts.length; i++) counts[i] = mounts[i] == null ? 0 : mounts[i].viewCount();
            int index = row.indexOfChild(divider) + 1
                    + ClockRowLayout.offsetOf(counts, mount.slot.rowOrder()) + counts[mount.slot.rowOrder()];
            row.addView(view, index, lp);
            update();
        }

        /** Gaps, heights, divider, compact date and fit after anything in the row changed. */
        void update() {
            // The first chip on screen keeps its own margin; the others follow at the plain gap.
            int[] margins = marginsDp();
            for (int i = 0; i < mounts.length; i++) {
                WidgetMount m = mounts[i];
                if (m == null || m.viewCount() == 0 || !(m.content.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) m.content.getLayoutParams();
                // Each chip keeps its own height, centred in the row: a 42 dp icon circle (Brightness) next to the
                // taller weather chip stays a circle, the same shape as the top bar's buttons, instead of an oval.
                if (lp.getMarginStart() == dp(margins[i]) && lp.height == dp(m.heightDp)) continue;
                lp.setMarginStart(dp(margins[i]));
                lp.height = dp(m.heightDp);
                m.content.setLayoutParams(lp);
            }
            boolean room = false;
            for (WidgetMount m : mounts) if (m != null && m.contentShown) room = true;
            // Below the clock there is no date beside the time to separate from, so no divider and no compact date.
            divider.setVisibility(room && !dateBelow ? View.VISIBLE : View.GONE);
            if (!dateBelow) setCompactDate(room);
            scheduleFit();
        }

        /**
         * The date column normally fills the rest of the row (weight 1) so nothing can come after it. With a widget it
         * is as wide as its text, capped so a long date cannot push the widget into the top buttons. Its texts wrap
         * too, or a longer date tomorrow would keep today's width (as moveDateBelowClock notes).
         */
        private void setCompactDate(boolean compact) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dateColumn.getLayoutParams();
            lp.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            lp.weight = compact ? 0f : 1f;
            dateColumn.setLayoutParams(lp);
            ViewGroup texts = (ViewGroup) dateColumn;
            for (int i = 0; i < texts.getChildCount(); i++) {
                View text = texts.getChildAt(i);
                ViewGroup.LayoutParams tlp = text.getLayoutParams();
                tlp.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
                text.setLayoutParams(tlp);
            }
            dateText.setMaxWidth(compact ? dp(240) : Integer.MAX_VALUE);
        }

        private void scheduleFit() {
            if (fitPosted) return;
            fitPosted = true;
            row.post(() -> {
                fitPosted = false;
                fit();
            });
        }

        /**
         * Puts every chip right after the clock block, from the sizes the views measured to: the block's right edge
         * (the last view that is not a widget, margin included), then each chip's margin and width in turn. A chip
         * the row laid out anywhere else is moved to that place, so it can never float away from the time.
         */
        private void align() {
            int start = 0;
            java.util.List<View> chips = new java.util.ArrayList<>();
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getVisibility() == View.GONE) continue;
                if (isWidgetView(child)) {
                    chips.add(child);
                } else if (chips.isEmpty()) {
                    start = child.getRight() + ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).rightMargin;
                }
            }
            int[] margins = new int[chips.size()];
            int[] widths = new int[chips.size()];
            for (int i = 0; i < margins.length; i++) {
                margins[i] = ((ViewGroup.MarginLayoutParams) chips.get(i).getLayoutParams()).leftMargin;
                widths[i] = chips.get(i).getWidth();
            }
            int[] lefts = ClockRowLayout.chipLefts(start, margins, widths);
            for (int i = 0; i < lefts.length; i++) {
                View chip = chips.get(i);
                if (chip.getWidth() == 0) continue;
                float shift = ClockRowLayout.shift(lefts[i], chip.getLeft());
                if (chip.getTranslationX() != shift) chip.setTranslationX(shift);
            }
        }

        /**
         * Hides a later chip that does not fit beside the earlier ones, from the sizes the views measure to: the
         * row's width, what the clock block (everything up to the divider) takes, and each chip's natural width.
         * Settled in one pass, so hiding a chip does not change what the next pass sees.
         */
        private void fit() {
            int available = row.getWidth();
            if (available <= 0) return;
            align();
            int divider = row.indexOfChild(this.divider);
            int used = 0;
            for (int i = 0; i <= divider; i++) {
                View child = row.getChildAt(i);
                if (child.getVisibility() == View.GONE) continue;
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                used += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            }
            int n = mounts.length;
            int[] widths = new int[n];
            int[] margins = new int[n];
            boolean[] wanted = new boolean[n];
            for (int i = 0; i < n; i++) wanted[i] = wanted(mounts[i]);
            int[] marginsDp = marginsDp();
            for (int i = 0; i < n; i++) {
                if (!wanted[i]) continue;
                WidgetMount m = mounts[i];
                margins[i] = dp(marginsDp[i]);
                m.content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(Math.max(0, m.content.getLayoutParams().height),
                                View.MeasureSpec.EXACTLY));
                widths[i] = m.content.getMeasuredWidth();
            }
            boolean[] fits = ClockRowLayout.fit(available, used, widths, margins, wanted);
            for (int i = 0; i < n; i++) {
                if (!wanted[i]) continue;
                View v = mounts[i].content;
                int want = fits[i] ? View.VISIBLE : View.GONE;
                // A chip the owner is on keeps its place: it moves on only when focus has left it.
                if (v.getVisibility() != want && !(want == View.GONE && v.hasFocus())) v.setVisibility(want);
            }
        }
    }

    // ---- the top bar --------------------------------------------------------------------------------------------

    /**
     * The top bar as its two widget slots share it. The round buttons always stay; a chip is shown only while the
     * whole bar still leaves the clock block its room (see {@link ClockRowLayout#barFit}), so a chip never pushes the
     * buttons off the screen or onto the time, whatever the buttons' styles make of their widths. Checked after every
     * layout, so a text-style button, a longer Wi-Fi name or the VPN pill showing up is taken into account.
     */
    private static final class BarRow {
        final ViewGroup bar;
        final View clock;
        final View ampm;
        final View leftBlock;
        final WidgetMount[] mounts = new WidgetMount[WidgetSlot.bar().length];
        private boolean fitPosted;

        BarRow(Activity activity, ViewGroup bar) {
            this.bar = bar;
            this.clock = activity.findViewById(R.id.clock);
            this.ampm = activity.findViewById(R.id.ampm);
            this.leftBlock = activity.findViewById(R.id.leftBlock);
            // A focused chip grows and casts a shadow past the bar's edges.
            bar.setClipChildren(false);
            bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scheduleFit());
        }

        void scheduleFit() {
            if (fitPosted) return;
            fitPosted = true;
            bar.post(() -> {
                fitPosted = false;
                fit();
            });
        }

        private void fit() {
            if (!(bar.getParent() instanceof View) || clock == null) return;
            int headerWidth = ((View) bar.getParent()).getWidth();
            if (headerWidth <= 0 || clock.getWidth() <= 0) return;
            int n = mounts.length;
            boolean[] wanted = new boolean[n];
            int[] needs = new int[n];
            for (int i = 0; i < n; i++) {
                WidgetMount m = mounts[i];
                if (m == null || m.content == null || !m.contentShown || m.content.getParent() != bar) continue;
                wanted[i] = true;
                m.content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(Math.max(0, m.content.getLayoutParams().height),
                                View.MeasureSpec.EXACTLY));
                needs[i] = m.content.getMeasuredWidth() + ((ViewGroup.MarginLayoutParams) m.content.getLayoutParams()).getMarginStart();
            }
            int base = 0;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getVisibility() == View.GONE || isWidgetView(child)) continue;
                base += child.getWidth() + ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).getMarginStart()
                        + ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).getMarginEnd();
            }
            int clockRight = clock.getRight();
            if (ampm != null && ampm.getVisibility() != View.GONE) clockRight = Math.max(clockRight, ampm.getRight());
            int minLeft = clockRight - clock.getLeft();
            int gap = leftBlock != null && leftBlock.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) leftBlock.getLayoutParams()).getMarginEnd() : 0;
            boolean[] fits = ClockRowLayout.barFit(headerWidth, minLeft, gap, base, needs, wanted);
            for (int i = 0; i < n; i++) {
                if (!wanted[i]) continue;
                View v = mounts[i].content;
                int want = fits[i] ? View.VISIBLE : View.GONE;
                // A chip the owner is on keeps its place: it moves on only when focus has left it.
                if (v.getVisibility() != want && !(want == View.GONE && v.hasFocus())) v.setVisibility(want);
            }
        }
    }
}
