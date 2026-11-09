package com.gathin.tvhome;

/**
 * The arithmetic behind the clock row's widget chips (Beside the clock, then Beside weather): where a slot's view goes
 * among its neighbours, the gap before each chip, and which chips still fit in the width the row was given. Every
 * size comes in as a measured value; nothing here knows a pixel width. No Android in it, so the JVM tests cover it.
 */
final class ClockRowLayout {

    private ClockRowLayout() { }

    /**
     * Where a slot's view goes among the widget views that follow the clock block: after every view of the slots that
     * come before it in the row, so the order does not depend on which widget was docked first.
     * @param viewsPerSlot how many views each clock-row slot holds now, in row order
     * @param slotOrder the slot's place in that order
     */
    static int offsetOf(int[] viewsPerSlot, int slotOrder) {
        int offset = 0;
        for (int i = 0; i < slotOrder && i < viewsPerSlot.length; i++) offset += viewsPerSlot[i];
        return offset;
    }

    /**
     * The gap before a chip: the first chip keeps the margin its widget asked for (the weather chip tucks in under
     * the clock), every later one follows the chip before it at the plain gap.
     */
    static int marginBefore(boolean firstInRow, int ownMargin, int gap) {
        return firstInRow ? ownMargin : gap;
    }

    /**
     * The gap before the first chip. Beside the date the weather chip tucks in under the divider (its own, negative
     * margin); with the date under the time there is no divider, so the chip follows the time at a plain gap (its
     * own padding makes up the rest, about 24 dp from the time to the art).
     * @param dateBeside the date and its divider sit in the row, after the time
     * @param ownMargin what the widget asked for
     * @param belowGap the gap after the time when the row ends with it
     */
    static int firstChipMargin(boolean dateBeside, int ownMargin, int belowGap) {
        return dateBeside ? ownMargin : belowGap;
    }

    /**
     * Where each chip's left edge belongs: the first one {@code margin[0]} after {@code start} (the right edge of the
     * last view of the clock block, margin included), each next one its margin after the chip before it.
     * @param start the clock block's right edge
     * @param margins the gap before each chip
     * @param widths each chip's measured width
     */
    static int[] chipLefts(int start, int[] margins, int[] widths) {
        int[] out = new int[widths.length];
        int cursor = start;
        for (int i = 0; i < widths.length; i++) {
            out[i] = cursor + margins[i];
            cursor = out[i] + widths[i];
        }
        return out;
    }

    /** How far a chip laid out at {@code actual} has to move to stand at {@code expected}. */
    static int shift(int expected, int actual) {
        return expected - actual;
    }

    /**
     * Which of the wanted chips fit in the row, left to right. A chip needs its margin plus its natural width; the
     * first chip in the row is never hidden (it always was shown, squeezed if need be), and once one chip does not
     * fit, none after it is shown either, so a later narrow chip never jumps ahead of a wider one.
     * @param available the row's measured width
     * @param used what the clock block already takes of it
     * @param widths each chip's natural width, in row order
     * @param margins the gap before each chip as {@link #marginBefore} gives it
     * @param wanted false for a slot with no widget on screen
     */
    static boolean[] fit(int available, int used, int[] widths, int[] margins, boolean[] wanted) {
        boolean[] out = new boolean[widths.length];
        boolean first = true;
        boolean stopped = false;
        int taken = used;
        for (int i = 0; i < widths.length; i++) {
            if (!wanted[i]) continue;
            int need = margins[i] + widths[i];
            if (first) {
                out[i] = true;
                taken += need;
                first = false;
            } else if (!stopped && taken + need <= available) {
                out[i] = true;
                taken += need;
            } else {
                stopped = true;
            }
        }
        return out;
    }

    /**
     * The gap before each chip once it is known which chips are on screen: the first chip that is shown keeps the
     * margin its widget asked for (see {@link #firstChipMargin}), every later shown chip follows at the plain gap. A
     * slot whose widget is hidden (no add-on, no data) never takes the first place, so the chip behind it is not left
     * with the narrow gap meant for a chip that follows another.
     * @param shown whether each slot's chip is on screen, in row order
     * @param ownMargins what each slot's widget asked for
     */
    static int[] chipMargins(boolean[] shown, int[] ownMargins, boolean dateBeside, int belowGap, int gap) {
        int[] out = new int[shown.length];
        boolean first = true;
        for (int i = 0; i < shown.length; i++) {
            if (shown[i] && first) {
                out[i] = marginBefore(true, firstChipMargin(dateBeside, ownMargins[i], belowGap), gap);
                first = false;
            } else {
                out[i] = gap;
            }
        }
        return out;
    }

    /**
     * Which chips of the top bar fit. The bar sits at the right edge and the clock block at the left, so the bar may
     * take everything except the clock block and the gap between them; the round buttons always stay, a chip is shown
     * only while the whole bar still fits in that room. Chips are taken in the order given, and a chip that does not
     * fit is skipped on its own (the one on the other side of the buttons may still fit).
     * @param headerWidth the header's width
     * @param minLeft the least the left side needs (the time and its AM/PM)
     * @param gap the space the layout keeps between the left side and the bar
     * @param barBase the bar's width with no chip in it
     * @param needs each chip's margin plus natural width
     * @param wanted false for a slot with no widget on screen
     */
    static boolean[] barFit(int headerWidth, int minLeft, int gap, int barBase, int[] needs, boolean[] wanted) {
        boolean[] out = new boolean[needs.length];
        int limit = headerWidth - gap - minLeft;
        int taken = barBase;
        for (int i = 0; i < needs.length; i++) {
            if (!wanted[i]) continue;
            if (taken + needs[i] <= limit) {
                out[i] = true;
                taken += needs[i];
            }
        }
        return out;
    }

    /**
     * How wide the slot picker's boxes are, so that none runs into the top buttons or into another box. Every box
     * would like {@code preferred}; when the row cannot hold them they shrink together down to {@code minBox}, and
     * after that the {@code shrinkable} piece (the date beside the time) gives way, down to {@code minShrinkable}.
     * @param available the row's width
     * @param fixed what the row holds that cannot shrink
     * @param shrinkable the width of the piece that may give way
     * @param boxMargins the gap before each box
     * @return element 0 is the width for the shrinkable piece, the rest the width of each box
     */
    static int[] shrinkToFit(int available, int fixed, int shrinkable, int minShrinkable, int[] boxMargins,
                             int preferred, int minBox) {
        int n = boxMargins.length;
        int[] out = new int[n + 1];
        int margins = 0;
        for (int m : boxMargins) margins += m;
        int spare = available - fixed - margins;
        int boxSpace = spare - shrinkable;
        int box = preferred;
        int give = shrinkable;
        if (n > 0 && boxSpace < n * preferred) {
            box = Math.max(minBox, Math.min(preferred, boxSpace / n));
            if (box == minBox && n * minBox > boxSpace) give = Math.max(Math.min(minShrinkable, shrinkable), Math.min(shrinkable, spare - n * minBox));
        }
        out[0] = give;
        for (int i = 1; i <= n; i++) out[i] = box;
        return out;
    }

    /**
     * Where the captions under the slot boxes start, left to right, so no two overlap and none leaves the screen: each
     * is centred under its own box when it can be and is pushed aside only as far as needed. The widths must fit
     * between {@code minLeft} and {@code maxRight} with {@code gap} between neighbours.
     * @param centers the boxes' centres, left to right
     * @param widths each caption's width
     */
    static int[] captionLefts(int[] centers, int[] widths, int minLeft, int maxRight, int gap) {
        int n = centers.length;
        int[] lefts = new int[n];
        for (int i = 0; i < n; i++) {
            lefts[i] = centers[i] - widths[i] / 2;
            if (i == 0) lefts[i] = Math.max(lefts[i], minLeft);
            else lefts[i] = Math.max(lefts[i], lefts[i - 1] + widths[i - 1] + gap);
        }
        for (int i = n - 1; i >= 0; i--) {
            int limit = (i == n - 1 ? maxRight : lefts[i + 1] - gap) - widths[i];
            lefts[i] = Math.min(lefts[i], limit);
        }
        return lefts;
    }
}
