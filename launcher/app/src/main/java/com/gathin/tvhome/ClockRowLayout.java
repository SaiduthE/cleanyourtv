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
}
