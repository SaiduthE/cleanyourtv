package com.gathin.tvhome;

import android.view.View;
import android.view.ViewGroup;

/**
 * Left and Right along Home's top bar: the next focusable thing in the bar's own child order, widgets included. The
 * bar's children run in the order they are drawn (a widget docked beside the network pill sits right before it), so
 * the neighbour of any pill or chip is simply the next focusable child that way. The pure part works on a row of
 * flags so the JVM tests cover it without views.
 */
final class BarFocus {
    private BarFocus() {
    }

    /** The index of the next focusable entry after {@code from} in {@code direction} (-1 left, +1 right), or -1 at the end. */
    static int next(boolean[] focusable, int from, int direction) {
        if (from < 0 || from >= focusable.length || direction == 0) return -1;
        int step = direction < 0 ? -1 : 1;
        for (int i = from + step; i >= 0 && i < focusable.length; i += step) {
            if (focusable[i]) return i;
        }
        return -1;
    }

    /** The first (direction +1) or last (-1) focusable entry, or -1 when none is. */
    static int edge(boolean[] focusable, int direction) {
        if (direction < 0) {
            for (int i = focusable.length - 1; i >= 0; i--) if (focusable[i]) return i;
        } else {
            for (int i = 0; i < focusable.length; i++) if (focusable[i]) return i;
        }
        return -1;
    }

    /** Which of the bar's children can take focus now: shown, enabled and focusable (a hidden pill or chip is skipped). */
    static boolean[] focusable(ViewGroup bar) {
        boolean[] out = new boolean[bar.getChildCount()];
        for (int i = 0; i < out.length; i++) {
            View child = bar.getChildAt(i);
            out[i] = child.getVisibility() == View.VISIBLE && child.isEnabled() && child.isFocusable();
        }
        return out;
    }

    /** Moves focus from {@code from} to the next focusable child of {@code bar} that way; false at the end of the bar. */
    static boolean move(ViewGroup bar, View from, int direction) {
        int target = next(focusable(bar), bar.indexOfChild(from), direction);
        return target >= 0 && bar.getChildAt(target).requestFocus();
    }

    /** Focuses the first (direction +1) or last (-1) focusable child of {@code bar}; false when there is none. */
    static boolean focusEdge(ViewGroup bar, int direction) {
        int target = edge(focusable(bar), direction);
        return target >= 0 && bar.getChildAt(target).requestFocus();
    }
}
