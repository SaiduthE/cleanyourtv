package com.gathin.tvhome;

/** D-pad movement over the manager's grid of tiles (rows x columns, the last row possibly short). No Android in it. */
final class WidgetGridNav {

    private WidgetGridNav() { }

    /**
     * The tile after one press. dx is -1 or 1 for Left and Right, dy is -1 or 1 for Up and Down. Never wraps: a press
     * at an edge stays put. Down from a column the short last row lacks lands on that row's last tile.
     */
    static int move(int index, int dx, int dy, int columns, int count) {
        if (count <= 0 || columns <= 0) return 0;
        index = Math.max(0, Math.min(index, count - 1));
        if (dx != 0) {
            int next = index + dx;
            boolean sameRow = next / columns == index / columns;
            return sameRow && next >= 0 && next < count ? next : index;
        }
        if (dy < 0) return index - columns >= 0 ? index - columns : index;
        int below = index + columns;
        if (below < count) return below;
        // A row exists below but is short: its last tile. In the last row there is nothing below.
        int lastRowStart = ((count - 1) / columns) * columns;
        return index < lastRowStart ? count - 1 : index;
    }
}
