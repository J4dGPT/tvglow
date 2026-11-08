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

    /**
     * A row counts as letterbox bar when its outer 15% on both sides is black and at most
     * a quarter of the middle is lit. Centered subtitles in the bar don't break detection.
     */
    private static boolean darkRow(int[] px, int w, int y, int x0, int x1) {
        int band = Math.max(1, w * 15 / 100);
        for (int x = 0; x < band; x++) if (maxCh(px[y * w + x]) > DARK) return false;
        for (int x = w - band; x < w; x++) if (maxCh(px[y * w + x]) > DARK) return false;
        int bright = 0, total = 0;
        for (int x = band; x < w - band; x += 2) {
            total++;
            if (maxCh(px[y * w + x]) > DARK) bright++;
        }
        return bright * 4 <= total; // at most 25% of the middle may be lit (subtitles)
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
     * Zone color: the plain average of the zone. Weighting toward saturated pixels was
     * dropped because it dragged hues toward the nearest primary (pink read as red,
     * cyan as blue). Styles decide the look afterwards; sampling stays truthful.
     */
    private static int avg(int[] px, int w, int bx0, int by0, int bx1, int by1,
                           int[] r) {
        bx0 = Math.max(bx0, r[0]); by0 = Math.max(by0, r[1]);
        bx1 = Math.min(bx1, r[2]); by1 = Math.min(by1, r[3]);
        if (bx1 <= bx0) bx1 = Math.min(bx0 + 1, r[2]);
        if (bx1 <= bx0) bx0 = bx1 - 1;
        if (by1 <= by0) by1 = Math.min(by0 + 1, r[3]);
        if (by1 <= by0) by0 = by1 - 1;
        long sr = 0, sg = 0, sb = 0;
        int cnt = 0;
        for (int y = by0; y < by1; y++) {
            int row = y * w;
            for (int x = bx0; x < bx1; x++) {
                int c = px[row + x];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
                cnt++;
            }
        }
        if (cnt == 0) return 0;
        return (int) ((sr + cnt / 2) / cnt) << 16 | (int) ((sg + cnt / 2) / cnt) << 8 | (int) ((sb + cnt / 2) / cnt);
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

    /**
     * Splits n zones across sides {left, top, right, bottom} in proportion to a 16:9
     * screen. Used when no custom per-side counts are set.
     */
    public static int[] autoSideCounts(int n, boolean fourSides) {
        double[] len = fourSides ? new double[]{9, 16, 9, 16} : new double[]{9, 16, 9, 0};
        double total = 0;
        for (double l : len) total += l;
        int[] c = new int[4];
        int used = 0;
        for (int i = 0; i < 4; i++) { c[i] = (int) Math.floor(n * len[i] / total); used += c[i]; }
        while (used < n) { // hand leftovers to the longest sides first
            int best = 0;
            double bestRem = -1;
            for (int i = 0; i < 4; i++) {
                double rem = n * len[i] / total - c[i];
                if (len[i] > 0 && rem > bestRem) { bestRem = rem; best = i; }
            }
            c[best]++;
            used++;
        }
        return c;
    }

    /**
     * Samples with explicit zone counts per side, in path order: left (bottom to top),
     * top (left to right), right (top to bottom), bottom (right to left).
     */
    public static int[] sampleSides(int[] px, int w, int h, int[] counts, float depthFrac) {
        return sampleSides(px, w, h, counts, depthFrac, contentRect(px, w, h));
    }

    /** Same, sampling inside a given content rectangle {x0, y0, x1, y1}. */
    public static int[] sampleSides(int[] px, int w, int h, int[] counts, float depthFrac, int[] r) {
        int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
        int cw = x1 - x0, ch = y1 - y0;
        int dx = Math.max(1, Math.round(cw * depthFrac));
        int dy = Math.max(1, Math.round(ch * depthFrac));
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        int[] out = new int[total];
        int k = 0;
        for (int side = 0; side < 4; side++) {
            int m = counts[side];
            for (int j = 0; j < m; j++) {
                double a = (double) j / m, b = (double) (j + 1) / m;
                switch (side) {
                    case 0: { // left, bottom to top
                        int ya = (int) Math.round(y1 - b * ch), yb = (int) Math.round(y1 - a * ch);
                        out[k++] = avg(px, w, x0, ya, x0 + dx, yb, r);
                        break;
                    }
                    case 1: { // top, left to right
                        int xa = (int) Math.round(x0 + a * cw), xb = (int) Math.round(x0 + b * cw);
                        out[k++] = avg(px, w, xa, y0, xb, y0 + dy, r);
                        break;
                    }
                    case 2: { // right, top to bottom
                        int ya = (int) Math.round(y0 + a * ch), yb = (int) Math.round(y0 + b * ch);
                        out[k++] = avg(px, w, x1 - dx, ya, x1, yb, r);
                        break;
                    }
                    default: { // bottom, right to left
                        int xa = (int) Math.round(x1 - b * cw), xb = (int) Math.round(x1 - a * cw);
                        out[k++] = avg(px, w, xa, y1 - dy, xb, y1, r);
                    }
                }
            }
        }
        return out;
    }

    /** Left red, top green, right blue, bottom white: for checking the side mapping. */
    public static int[] sidePattern(int[] counts) {
        int[] colors = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        int[] out = new int[total];
        int k = 0;
        for (int side = 0; side < 4; side++) for (int j = 0; j < counts[side]; j++) out[k++] = colors[side];
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

    public static final int STYLE_COZY = 0, STYLE_VIVID = 1, STYLE_ACCURATE = 2;

    /**
     * Final look. Cozy: deep saturated color, darker tone curve, whites become warm
     * amber, and dark scenes keep a faint ember glow instead of going black.
     */
    public static int style(int c, int style, float bright) {
        if (style == STYLE_ACCURATE) return adjust(c, 1.15f, bright); // slight lift: a wall dulls colored light
        float[] hsv = toHsv(c);
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (style == STYLE_VIVID) {
            if (s > 0.08f) s = Math.min(1f, s * 1.5f + 0.05f);
            v = (float) Math.pow(v, 1.15);
        } else {
            // All transitions are soft blends: no thresholds, so nothing flickers.
            float vc = (float) Math.pow(v, 1.7) * 0.8f * bright;          // darker curve
            int colorful = fromHsv(h, Math.min(1f, s * 1.7f + 0.15f), Math.min(1f, vc));
            int amber = fromHsv(28f, 0.75f, Math.min(1f, vc * 0.55f));  // whites go warm
            float amberMix = Math.max(0f, Math.min(1f, (0.3f - s) / 0.25f));
            int out = lerp(colorful, amber, amberMix);
            float lum = Math.max(vc * (1 - amberMix * 0.45f), 0f);
            int ember = fromHsv(22f, 0.9f, 0.05f * bright);              // dark-scene glow
            float emberMix = Math.max(0f, Math.min(1f, (0.07f - lum) / 0.07f));
            return lerp(out, ember, emberMix);
        }
        return fromHsv(h, s, Math.min(1f, v * bright));
    }

    static int lerp(int a, int b, float t) {
        float r = ((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t;
        float g = ((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t;
        float bl = (a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t;
        return clamp(r) << 16 | clamp(g) << 8 | clamp(bl);
    }

    static float[] toHsv(int c) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        float h;
        if (d == 0) h = 0;
        else if (mx == r) h = 60f * (((g - b) / d) % 6f);
        else if (mx == g) h = 60f * ((b - r) / d + 2f);
        else h = 60f * ((r - g) / d + 4f);
        if (h < 0) h += 360f;
        return new float[]{h, mx == 0 ? 0 : d / mx, mx};
    }

    static int fromHsv(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return clamp((r + m) * 255) << 16 | clamp((g + m) * 255) << 8 | clamp((b + m) * 255);
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
