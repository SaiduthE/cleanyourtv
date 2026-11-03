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
 * One slot's place in Home's header layout: it adds a widget's view to the clock row or to the top bar, makes room
 * for it, and takes it out again. Every view it adds carries {@link #TAG}, so the top bar's own code (which looks
 * for "the first button") skips them. Also draws an invisible placeholder while the slot picker is open, so the real
 * layout shows where a widget would sit.
 *
 * The two clock-row slots (Beside the clock, Beside weather) share one {@link ClockRow}: the divider and the compact
 * date are there while either has a widget, and the chips follow the clock block in slot order whichever was docked
 * first.
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
    // The top bar (TOP_BAR_START, TOP_BAR_END).
    private ViewGroup bar;

    private View content;
    private View placeholder;
    private boolean contentShown;
    private boolean previewing;

    private WidgetMount(Activity activity, WidgetSlot slot) {
        this.activity = activity;
        this.slot = slot;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /**
     * Whether Home's header can host the slot with these layout choices (what {@link #create} decides on Home itself,
     * known without Home: the Widgets manager inside Launcher settings asks before it draws its picture of the header).
     */
    static boolean slotAvailable(WidgetSlot slot, boolean rtlMenuBar, boolean dateBelowClock, boolean topMenu) {
        if (rtlMenuBar) return false;
        return slot == WidgetSlot.CLOCK_SIDE ? !dateBelowClock : !topMenu;
    }

    /**
     * The mount for a slot, or null when the layout cannot host it: the mirrored Hebrew header, the date moved
     * below the clock in a way the row cannot follow, or the one-gear top menu for the top bar slots. The clock
     * row's second slot shares the first one's row, so it is given that mount ({@code clockSideMount}).
     */
    static WidgetMount create(Activity activity, WidgetSlot slot, boolean topMenu, WidgetMount clockSideMount) {
        try {
            if (activity.getResources().getBoolean(R.bool.rtl_menu_bar)) return null;
            WidgetMount mount = new WidgetMount(activity, slot);
            if (slot == WidgetSlot.CLOCK_SIDE) return mount.initClockRow() ? mount : null;
            if (slot.inClockRow()) {
                if (clockSideMount == null || clockSideMount.clock == null) return null;
                mount.clock = clockSideMount.clock;
                mount.clock.mounts[slot.rowOrder()] = mount;
                return mount;
            }
            if (topMenu) return null;
            View buttons = activity.findViewById(R.id.topButtons);
            if (!(buttons instanceof ViewGroup)) return null;
            mount.bar = (ViewGroup) buttons;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (clock != null) {
            this.heightDp = heightDp;
            this.ownMarginDp = clockMarginStartDp;
            clock.attach(this, view, lp);
        } else if (slot == WidgetSlot.TOP_BAR_START) {
            bar.addView(view, 0, lp);
        } else {
            lp.setMarginStart(dp(10));
            bar.addView(view, lp);
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
    }

    private int viewCount() {
        return (content != null && content.getParent() != null ? 1 : 0)
                + (placeholder != null && placeholder.getParent() != null ? 1 : 0);
    }

    // ---- the slot picker's preview ------------------------------------------------------------------------------

    /** Opens room for a widget of this width in the real layout and returns the (invisible) view that holds it. */
    View beginPreview(int widthDp, int heightDp) {
        endPreview();
        previewing = true;
        if (clock != null && content != null && contentShown && content.getParent() != null) {
            // A chip the row had hidden for lack of room: show it, so the picker can outline it where it sits.
            content.setVisibility(View.VISIBLE);
            apply();
            return content;
        }
        placeholder = new View(activity);
        placeholder.setTag(TAG);
        placeholder.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (clock != null) {
            if (content == null) {
                this.heightDp = heightDp;
                this.ownMarginDp = slot == WidgetSlot.CLOCK_SIDE ? -10 : 8;
            }
            clock.attach(this, placeholder, lp);
        } else if (slot == WidgetSlot.TOP_BAR_START) {
            bar.addView(placeholder, 0, lp);
        } else {
            lp.setMarginStart(dp(10));
            bar.addView(placeholder, lp);
        }
        apply();
        return placeholder;
    }

    void endPreview() {
        if (placeholder != null && placeholder.getParent() instanceof ViewGroup) {
            ((ViewGroup) placeholder.getParent()).removeView(placeholder);
        }
        placeholder = null;
        previewing = false;
        apply();
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
            // The first slot with a view keeps its own margin; the others follow at the plain gap.
            boolean first = true;
            int height = 0;
            for (WidgetMount m : mounts) if (m != null && m.viewCount() > 0) height = Math.max(height, m.heightDp);
            for (WidgetMount m : mounts) {
                if (m == null || m.viewCount() == 0) continue;
                int margin = ClockRowLayout.marginBefore(first, m.ownMarginDp, GAP_DP);
                first = false;
                for (View v : new View[] {m.content, m.placeholder}) {
                    if (v == null || !(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    if (lp.getMarginStart() == dp(margin) && lp.height == dp(height)) continue;
                    lp.setMarginStart(dp(margin));
                    lp.height = dp(height);
                    v.setLayoutParams(lp);
                }
            }
            boolean room = false;
            for (WidgetMount m : mounts) if (m != null && (m.contentShown || m.previewing)) room = true;
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
         * Hides a later chip that does not fit beside the earlier ones, from the sizes the views measure to: the
         * row's width, what the clock block (everything up to the divider) takes, and each chip's natural width.
         * Settled in one pass, so hiding a chip does not change what the next pass sees.
         */
        private void fit() {
            int available = row.getWidth();
            if (available <= 0) return;
            for (WidgetMount m : mounts) if (m != null && m.previewing) return;   // the picker shows every slot
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
            boolean first = true;
            for (int i = 0; i < n; i++) {
                WidgetMount m = mounts[i];
                if (m == null || m.content == null || !m.contentShown || m.content.getParent() != row) continue;
                wanted[i] = true;
                margins[i] = dp(ClockRowLayout.marginBefore(first, m.ownMarginDp, GAP_DP));
                first = false;
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
}
