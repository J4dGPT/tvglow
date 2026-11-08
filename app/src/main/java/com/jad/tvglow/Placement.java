package com.jad.tvglow;

/**
 * Turns two plain answers ("where does the strip start?" and "which way does it run?")
 * into the Reverse and Shift settings, so nobody has to tune them by trial and error.
 *
 * Zones are numbered clockwise as seen from the couch, starting at the bottom of the left
 * side: up the left, across the top, down the right, back along the bottom. Positions are
 * measured along that same loop in zone units, starting at the bottom-left corner.
 */
public final class Placement {
    private Placement() {}

    public static final int BOTTOM_LEFT = 0, LEFT = 1, TOP_LEFT = 2, TOP = 3,
            TOP_RIGHT = 4, RIGHT = 5, BOTTOM_RIGHT = 6, BOTTOM = 7;

    /** Where a position sits along the loop, in zone units from the bottom-left corner. */
    static double coordinate(int pos, int[] c) {
        int l = c[0], t = c[1], r = c[2], b = c[3];
        switch (pos) {
            case LEFT:
                return l / 2.0;
            case TOP_LEFT:
                return l;
            case TOP:
                return l + t / 2.0;
            case TOP_RIGHT:
                return l + t;
            case RIGHT:
                return l + t + r / 2.0;
            case BOTTOM_RIGHT:
                return l + t + r;
            case BOTTOM:
                return l + t + r + b / 2.0;
            default:
                return 0;
        }
    }

    /** The zone the strip's first segment covers, given where it starts and its direction. */
    static int firstZone(int pos, boolean clockwise, int[] c) {
        int n = c[0] + c[1] + c[2] + c[3];
        double p = coordinate(pos, c);
        int z = clockwise ? (int) Math.floor(p + 1e-9) : (int) Math.ceil(p - 1e-9) - 1;
        return Math.floorMod(z, n);
    }

    /** @return {reverse (0 or 1), shift} that line the strip up with the screen. */
    public static int[] solve(int pos, boolean clockwise, int[] counts) {
        int n = counts[0] + counts[1] + counts[2] + counts[3];
        int z = firstZone(pos, clockwise, counts);
        if (clockwise) return new int[]{0, Math.floorMod(-z, n)};
        return new int[]{1, Math.floorMod(z + 1, n)};
    }

    /** Plain-language direction choices for a start position: {clockwise, counterclockwise}. */
    public static String[] directionLabels(int pos) {
        switch (pos) {
            case LEFT:
                return new String[]{"Up", "Down"};
            case TOP_LEFT:
                return new String[]{"Right, along the top", "Down the left side"};
            case TOP:
                return new String[]{"To the right", "To the left"};
            case TOP_RIGHT:
                return new String[]{"Down the right side", "Left, along the top"};
            case RIGHT:
                return new String[]{"Down", "Up"};
            case BOTTOM_RIGHT:
                return new String[]{"Left, along the bottom", "Up the right side"};
            case BOTTOM:
                return new String[]{"To the left", "To the right"};
            default:
                return new String[]{"Up the left side", "Right, along the bottom"};
        }
    }
}
