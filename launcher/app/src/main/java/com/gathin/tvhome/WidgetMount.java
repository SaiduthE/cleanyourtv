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
 */
final class WidgetMount {

    static final String TAG = "home_widget";

    static boolean isWidgetView(View v) {
        return TAG.equals(v.getTag());
    }

    final WidgetSlot slot;
    private final Activity activity;
    private final float density;
    // The clock row (CLOCK_SIDE).
    private LinearLayout row;
    private View dateColumn;
    private TextView dateText;
    private View divider;
    private boolean dateBelow;
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
     * The mount for a slot, or null when the layout cannot host it: the mirrored Hebrew header, the date moved
     * below the clock (date_below_clock), or the one-gear top menu for the top bar slots.
     */
    static WidgetMount create(Activity activity, WidgetSlot slot, boolean topMenu) {
        try {
            if (activity.getResources().getBoolean(R.bool.rtl_menu_bar)) return null;
            WidgetMount mount = new WidgetMount(activity, slot);
            if (slot == WidgetSlot.CLOCK_SIDE) return mount.initClockRow() ? mount : null;
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
        View clock = activity.findViewById(R.id.clock);
        View date = activity.findViewById(R.id.date);
        if (clock == null || date == null || !(clock.getParent() instanceof LinearLayout)) return false;
        row = (LinearLayout) clock.getParent();
        dateColumn = (View) date.getParent();
        // date_below_clock moves the date column under the clock: the row then ends with the time.
        dateBelow = dateColumn.getParent() != row && dateColumn.getParent() == row.getParent();
        if (dateColumn.getParent() != row && !dateBelow) return false;
        dateText = (TextView) date;
        // A second line like the clock's own, in the same colour (white when header_white recoloured it).
        View first = activity.findViewById(R.id.clockDivider);
        divider = new View(activity);
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
        return true;
    }

    // ---- the widget's view --------------------------------------------------------------------------------------

    /**
     * Puts the widget's view in the slot (hidden by the widget until it has something to show).
     * @param clockMarginStartDp the gap before it in the clock row, where the weather chip tucks in under the divider
     */
    void add(View view, int heightDp, int clockMarginStartDp) {
        remove();
        content = view;
        view.setTag(TAG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (slot == WidgetSlot.CLOCK_SIDE) {
            lp.setMarginStart(dp(clockMarginStartDp));
            row.addView(view, row.indexOfChild(divider) + 1, lp);
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
        if (slot != WidgetSlot.CLOCK_SIDE) return;
        boolean room = contentShown || previewing;
        // Below the clock there is no date beside the time to separate from, so no divider and no compact date.
        divider.setVisibility(room && !dateBelow ? View.VISIBLE : View.GONE);
        if (!dateBelow) setCompactDate(room);
    }

    /**
     * The date column normally fills the rest of the row (weight 1) so nothing can come after it. With a widget it is
     * as wide as its text, capped so a long date cannot push the widget into the top buttons. Its texts wrap too,
     * or a longer date tomorrow would keep today's width (as moveDateBelowClock notes).
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

    // ---- the slot picker's preview ------------------------------------------------------------------------------

    /** Opens room for a widget of this width in the real layout and returns the (invisible) view that holds it. */
    View beginPreview(int widthDp, int heightDp) {
        endPreview();
        previewing = true;
        placeholder = new View(activity);
        placeholder.setTag(TAG);
        placeholder.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        if (slot == WidgetSlot.CLOCK_SIDE) {
            row.addView(placeholder, row.indexOfChild(divider) + 1, lp);
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
}
