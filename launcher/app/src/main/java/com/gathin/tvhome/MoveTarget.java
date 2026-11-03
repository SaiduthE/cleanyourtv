package com.gathin.tvhome;

/**
 * Where a carried tile goes on each arrow key in reorder mode, with no Android in it so it runs in JVM tests.
 * MainActivity moves the tile with it, and the arrow hints beside the tile show only the directions it answers.
 */
final class MoveTarget {

    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    private MoveTarget() { }

    /**
     * The position a tile at {@code from} lands on when moved in {@code dir} among n tiles in rows of {@code columns},
     * or -1 when it stays. Left and Right wrap across rows, like a list; Up and Down move a whole row, and Down into
     * a short last row lands on its last tile. A single row has no rows to move between. rtl_app_grid mirrors the
     * grid: position 0 is on the right, so Left moves towards the end.
     */
    static int target(int dir, int from, int n, int columns, boolean singleRow, boolean rtl) {
        if (from < 0 || from >= n || columns < 1) return -1;
        int to;
        switch (dir) {
            case LEFT:
                to = rtl ? from + 1 : from - 1;
                break;
            case RIGHT:
                to = rtl ? from - 1 : from + 1;
                break;
            case UP:
                if (singleRow) return -1;
                to = from - columns;
                break;
            case DOWN:
                if (singleRow) return -1;
                if (from + columns < n) to = from + columns;
                else to = from / columns < (n - 1) / columns ? n - 1 : from;
                break;
            default:
                return -1;
        }
        return to < 0 || to >= n || to == from ? -1 : to;
    }

    /** The directions that move the tile, as a bit set: bit {@code 1 << LEFT} and so on. */
    static int available(int from, int n, int columns, boolean singleRow, boolean rtl) {
        int mask = 0;
        for (int dir = LEFT; dir <= DOWN; dir++) {
            if (target(dir, from, n, columns, singleRow, rtl) >= 0) mask |= 1 << dir;
        }
        return mask;
    }

    static boolean has(int mask, int dir) {
        return (mask & (1 << dir)) != 0;
    }
}
