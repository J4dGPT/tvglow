package com.jad.tvglow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans Govee segment commands for App segments mode.
 *
 * Each command paints one color onto any set of segments (a 16-bit mask), and the strip
 * can only process so many commands per second before a queue builds up (that queue is
 * what feels like delay). So instead of updating segments one by one, every tick this
 * picks, greedily, the commands that remove the most visible error (OKLab distance between
 * what the strip shows and what the screen wants), within a token-bucket budget.
 *
 * Result: a scene change becomes one or two big commands that repaint the whole strip at
 * once, and the remaining budget fills in detail on the following ticks. Calm scenes cost
 * almost nothing, which keeps the strip's queue empty and the reaction instant.
 */
public final class SegmentScheduler {
    public static final class Cmd {
        public final int mask, color;

        Cmd(int mask, int color) {
            this.mask = mask;
            this.color = color;
        }
    }

    /** A segment closer than this (squared OKLab, about 0.03) to its target counts as correct. */
    static final float MIN_GAIN = 0.0009f;
    /** Segments whose targets are within this (squared OKLab) are painted together. */
    private static final float CLUSTER_D2 = 0.004f;
    /** One segment gets re-sent this often, so a lost packet can never leave a stale zone. */
    private static final long REFRESH_MS = 150;

    private final int n;
    private final boolean circular;
    private final int[] windows;
    private final int[] shown;
    private final float[] shownLab;
    private double tokens;
    private long lastMs = Long.MIN_VALUE;
    private long lastRefreshMs;
    private int refreshIdx = -1;

    public SegmentScheduler(int n, boolean circular) {
        if (n < 1 || n > 16) throw new IllegalArgumentException("1 to 16 segments");
        this.n = n;
        this.circular = circular;
        this.windows = buildWindows(n, circular);
        shown = new int[n];
        Arrays.fill(shown, -1);
        shownLab = new float[n * 3];
    }

    public int size() {
        return n;
    }

    public boolean circular() {
        return circular;
    }

    /** What the scheduler believes the strip is showing (-1 = unknown). */
    public int[] shown() {
        return shown.clone();
    }

    /** Contiguous runs of segments (the strip's physical neighborhoods) used as candidates. */
    private static int[] buildWindows(int n, boolean circular) {
        Set<Integer> set = new HashSet<>();
        List<Integer> out = new ArrayList<>();
        int full = n == 32 ? -1 : (1 << n) - 1;
        int[] sizes = {2, 3, 4, 6, n / 2, n};
        for (int k : sizes) {
            if (k < 2 || k > n) continue;
            if (k == n) {
                if (set.add(full)) out.add(full);
                continue;
            }
            int starts = circular ? n : n - k + 1;
            for (int s = 0; s < starts; s++) {
                int m = 0;
                for (int j = 0; j < k; j++) m |= 1 << ((s + j) % n);
                if (set.add(m)) out.add(m);
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * @param target   wanted color per segment, in strip order (segment 1 first)
     * @param perSec   sustained command budget the strip can absorb
     * @param burst    commands that may go out at once after a quiet moment
     * @param maxCmds  hard cap per tick (one network packet)
     */
    public List<Cmd> plan(int[] target, long nowMs, double perSec, double burst, int maxCmds) {
        if (lastMs == Long.MIN_VALUE) {
            tokens = burst;
            lastMs = nowMs;
        }
        tokens = Math.min(burst, tokens + Math.max(0, nowMs - lastMs) * perSec / 1000.0);
        lastMs = nowMs;
        List<Cmd> out = new ArrayList<>();
        int budget = (int) Math.min(maxCmds, Math.floor(tokens));
        if (budget <= 0) return out;

        float[] tLab = new float[n * 3];
        for (int i = 0; i < n; i++) ColorMath.oklab(target[i], tLab, i * 3);
        float[] err = new float[n];
        for (int i = 0; i < n; i++) {
            err[i] = shown[i] < 0 ? 1f : ColorMath.d2(shownLab, i * 3, tLab, i * 3);
        }

        // Candidates: single segments, contiguous runs, and clusters of similar targets.
        List<Integer> cMask = new ArrayList<>();
        List<Integer> cColor = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < n; i++) addCandidate(cMask, cColor, seen, 1 << i, target[i]);
        for (int m : windows) addCandidate(cMask, cColor, seen, m, ColorMath.mean(target, m));
        for (int i = 0; i < n; i++) {
            int m = 0;
            for (int j = 0; j < n; j++) {
                if (ColorMath.d2(tLab, i * 3, tLab, j * 3) < CLUSTER_D2) m |= 1 << j;
            }
            if (Integer.bitCount(m) >= 2) addCandidate(cMask, cColor, seen, m, ColorMath.mean(target, m));
        }
        int k = cMask.size();
        float[] cLab = new float[k * 3];
        for (int c = 0; c < k; c++) ColorMath.oklab(cColor.get(c), cLab, c * 3);

        while (out.size() < budget) {
            int best = -1;
            float bestGain = MIN_GAIN;
            for (int c = 0; c < k; c++) {
                int m = cMask.get(c);
                float gain = 0;
                for (int i = 0; i < n; i++) {
                    if ((m & (1 << i)) == 0) continue;
                    gain += err[i] - ColorMath.d2(cLab, c * 3, tLab, i * 3);
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    best = c;
                }
            }
            if (best < 0) break;
            int m = cMask.get(best), color = cColor.get(best);
            for (int i = 0; i < n; i++) {
                if ((m & (1 << i)) == 0) continue;
                shown[i] = color;
                System.arraycopy(cLab, best * 3, shownLab, i * 3, 3);
                err[i] = ColorMath.d2(cLab, best * 3, tLab, i * 3);
            }
            out.add(new Cmd(m, color));
        }

        // Low-rate refresh of one segment, in rotation, with its exact target.
        if (out.size() < budget && nowMs - lastRefreshMs >= REFRESH_MS) {
            lastRefreshMs = nowMs;
            refreshIdx = (refreshIdx + 1) % n;
            int i = refreshIdx;
            shown[i] = target[i];
            System.arraycopy(tLab, i * 3, shownLab, i * 3, 3);
            out.add(new Cmd(1 << i, target[i]));
        }
        tokens -= out.size();
        return out;
    }

    private static void addCandidate(List<Integer> masks, List<Integer> colors, Set<Long> seen,
                                     int mask, int color) {
        if (seen.add(((long) mask << 32) | (color & 0xFFFFFFFFL))) {
            masks.add(mask);
            colors.add(color);
        }
    }
}
