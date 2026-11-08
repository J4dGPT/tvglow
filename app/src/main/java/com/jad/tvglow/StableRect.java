package com.jad.tvglow;

/**
 * Letterbox and pillarbox detection with hysteresis. A dark scene can look like black bars
 * for a moment; reacting to that per frame makes the sampled area (and the colors) jump.
 * Here the sampled rectangle only moves after a new one has been seen consistently:
 * quickly when the picture grows (bars gone), slowly when it shrinks (bars appearing),
 * and never on fully black frames.
 */
public final class StableRect {
    static final long GROW_MS = 400, SHRINK_MS = 3000;

    private int[] active;
    private int[] candidate;
    private long candidateSince;

    public int[] update(int[] detected, int w, int h, boolean dark, long nowMs) {
        if (active == null) active = new int[]{0, 0, w, h};
        if (dark) {
            candidate = null;
            return active;
        }
        int tx = Math.max(1, w / 50), ty = Math.max(1, h / 50);
        if (close(detected, active, tx, ty)) {
            candidate = null;
            return active;
        }
        if (candidate == null || !close(detected, candidate, tx, ty)) {
            candidate = detected.clone();
            candidateSince = nowMs;
            return active;
        }
        boolean grows = detected[0] < active[0] - tx || detected[1] < active[1] - ty
                || detected[2] > active[2] + tx || detected[3] > active[3] + ty;
        if (nowMs - candidateSince >= (grows ? GROW_MS : SHRINK_MS)) {
            active = candidate.clone();
            candidate = null;
        }
        return active;
    }

    private static boolean close(int[] a, int[] b, int tx, int ty) {
        return Math.abs(a[0] - b[0]) <= tx && Math.abs(a[1] - b[1]) <= ty
                && Math.abs(a[2] - b[2]) <= tx && Math.abs(a[3] - b[3]) <= ty;
    }
}
