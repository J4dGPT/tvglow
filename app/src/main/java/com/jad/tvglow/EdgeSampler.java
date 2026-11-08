package com.jad.tvglow;

/**
 * Turns a small downscaled screen frame into a list of colors traced around the
 * screen edge. Pixels are packed 0xRRGGBB, row-major, width*height.
 *
 * Path order (index 0 first):
 *   3 sides: up the LEFT edge (bottom to top), across the TOP (left to right),
 *            down the RIGHT edge (top to bottom).
 *   4 sides: same, then back along the BOTTOM (right to left).
 * Letterbox / pillarbox bars are detected and skipped.
 */
public final class EdgeSampler {
    private EdgeSampler() {}

    private static final int DARK = 28;

    private static int maxCh(int c) {
        return Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF));
    }

    private static boolean darkRow(int[] px, int w, int y, int x0, int x1) {
        for (int x = x0; x < x1; x += 2) if (maxCh(px[y * w + x]) > DARK) return false;
        return true;
    }

    private static boolean darkCol(int[] px, int w, int x, int y0, int y1) {
        for (int y = y0; y < y1; y += 2) if (maxCh(px[y * w + x]) > DARK) return false;
        return true;
    }

    /** Returns {x0, y0, x1, y1} of the non-black content area. */
    public static int[] contentRect(int[] px, int w, int h) {
        int y0 = 0, y1 = h, x0 = 0, x1 = w;
        while (y0 < h / 4 && darkRow(px, w, y0, 0, w)) y0++;
        while (y1 > h - h / 4 && darkRow(px, w, y1 - 1, 0, w)) y1--;
        while (x0 < w / 4 && darkCol(px, w, x0, y0, y1)) x0++;
        while (x1 > w - w / 4 && darkCol(px, w, x1 - 1, y0, y1)) x1--;
        return new int[]{x0, y0, x1, y1};
    }

    /** True if the whole frame is (near) black, e.g. DRM-protected content. */
    public static boolean allDark(int[] px) {
        for (int i = 0; i < px.length; i += 3) if (maxCh(px[i]) > 8) return false;
        return true;
    }

    private static final float[] LIN = new float[256];

    static {
        for (int i = 0; i < 256; i++) LIN[i] = (float) Math.pow(i / 255.0, 2.2);
    }

    private static int toSrgb(float v) {
        return clamp((float) (Math.pow(Math.max(0f, Math.min(1f, v)), 1 / 2.2) * 255));
    }

    /**
     * Zone color: averaged in linear light, weighted toward bright and saturated pixels
     * so a zone reads as its dominant vivid color instead of a muddy mean.
     */
    private static int avg(int[] px, int w, int bx0, int by0, int bx1, int by1,
                           int[] r) {
        bx0 = Math.max(bx0, r[0]); by0 = Math.max(by0, r[1]);
        bx1 = Math.min(bx1, r[2]); by1 = Math.min(by1, r[3]);
        if (bx1 <= bx0) bx1 = Math.min(bx0 + 1, r[2]);
        if (bx1 <= bx0) bx0 = bx1 - 1;
        if (by1 <= by0) by1 = Math.min(by0 + 1, r[3]);
        if (by1 <= by0) by0 = by1 - 1;
        float sr = 0, sg = 0, sb = 0, sw = 0;
        for (int y = by0; y < by1; y++) {
            int row = y * w;
            for (int x = bx0; x < bx1; x++) {
                int c = px[row + x];
                int ri = (c >> 16) & 0xFF, gi = (c >> 8) & 0xFF, bi = c & 0xFF;
                int mx = Math.max(ri, Math.max(gi, bi)), mn = Math.min(ri, Math.min(gi, bi));
                float sat = mx == 0 ? 0 : (mx - mn) / (float) mx;
                float wt = 0.15f + (mx / 255f) * (1f + 3f * sat);
                sr += LIN[ri] * wt;
                sg += LIN[gi] * wt;
                sb += LIN[bi] * wt;
                sw += wt;
            }
        }
        if (sw == 0) return 0;
        return toSrgb(sr / sw) << 16 | toSrgb(sg / sw) << 8 | toSrgb(sb / sw);
    }

    /** Light blend with neighboring zones so the strip reads as one continuous glow. */
    public static int[] blendNeighbors(int[] c, boolean circular, float k) {
        int n = c.length;
        if (n < 3) return c;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int l = i > 0 ? c[i - 1] : (circular ? c[n - 1] : c[i]);
            int rr = i < n - 1 ? c[i + 1] : (circular ? c[0] : c[i]);
            float cr = 0, cg = 0, cb = 0;
            int[] src = {l, c[i], rr};
            float[] wt = {k, 1 - 2 * k, k};
            for (int j = 0; j < 3; j++) {
                cr += LIN[(src[j] >> 16) & 0xFF] * wt[j];
                cg += LIN[(src[j] >> 8) & 0xFF] * wt[j];
                cb += LIN[src[j] & 0xFF] * wt[j];
            }
            out[i] = toSrgb(cr) << 16 | toSrgb(cg) << 8 | toSrgb(cb);
        }
        return out;
    }

    public static float colorDistance(int a, int b) {
        float dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        float dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        float db = (a & 0xFF) - (b & 0xFF);
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    public static int[] sample(int[] px, int w, int h, int count, boolean fourSides,
                               float depthFrac) {
        int[] r = contentRect(px, w, h);
        int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
        int cw = x1 - x0, ch = y1 - y0;
        double len = fourSides ? 2.0 * (cw + ch) : 2.0 * ch + cw;
        int dx = Math.max(1, Math.round(cw * depthFrac));
        int dy = Math.max(1, Math.round(ch * depthFrac));
        double seg = len / count;
        int half = (int) Math.max(1, Math.ceil(seg / 2));
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            double t = (i + 0.5) * seg;
            if (t < ch) {
                int cy = (int) (y1 - t);
                out[i] = avg(px, w, x0, cy - half, x0 + dx, cy + half, r);
            } else if (t < ch + cw) {
                int cx = (int) (x0 + (t - ch));
                out[i] = avg(px, w, cx - half, y0, cx + half, y0 + dy, r);
            } else if (t < 2.0 * ch + cw) {
                int cy = (int) (y0 + (t - ch - cw));
                out[i] = avg(px, w, x1 - dx, cy - half, x1, cy + half, r);
            } else {
                int cx = (int) (x1 - (t - 2.0 * ch - cw));
                out[i] = avg(px, w, cx - half, y1 - dy, cx + half, y1, r);
            }
        }
        return out;
    }

    /** Applies strip direction (reverse) and start offset (shift). */
    public static int[] order(int[] c, boolean reverse, int shift) {
        int n = c.length;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int src = reverse ? n - 1 - i : i;
            int dst = Math.floorMod(i + shift, n);
            out[dst] = c[src];
        }
        return out;
    }

    /** Saturation boost around luma, then brightness scale. */
    public static int adjust(int c, float sat, float bright) {
        float r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        r = (l + (r - l) * sat) * bright;
        g = (l + (g - l) * sat) * bright;
        b = (l + (b - l) * sat) * bright;
        return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** Rounds each channel to a multiple of 4 so near-identical colors don't resend. */
    public static int quantize(int c) {
        int r = Math.min(255, (((c >> 16) & 0xFF) + 2) & ~3);
        int g = Math.min(255, (((c >> 8) & 0xFF) + 2) & ~3);
        int b = Math.min(255, ((c & 0xFF) + 2) & ~3);
        return r << 16 | g << 8 | b;
    }

    /** Average of a color list (for single-color mode). */
    public static int average(int[] c) {
        long r = 0, g = 0, b = 0;
        for (int x : c) { r += (x >> 16) & 0xFF; g += (x >> 8) & 0xFF; b += x & 0xFF; }
        int n = Math.max(1, c.length);
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    /** Red at logical start fading to blue at logical end, for direction testing. */
    public static int[] testPattern(int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            float t = count == 1 ? 0 : (float) i / (count - 1);
            int red = Math.round(255 * (1 - t)), blue = Math.round(255 * t);
            out[i] = red << 16 | blue;
        }
        return out;
    }
}
