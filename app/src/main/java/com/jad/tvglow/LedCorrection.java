package com.jad.tvglow;

/**
 * Output correction so the strip's light matches the TV: white balance (warmth and tint)
 * plus a response curve per channel. LED chips, the strip's firmware and the wall color
 * all shift colors; the Match colors wizard sets these values by eye. Built once into
 * lookup tables, so applying it costs three array reads per segment.
 */
public final class LedCorrection {
    public static final LedCorrection NONE = new LedCorrection(0, 0, 100, 100, 100);

    private final int[] r = new int[256], g = new int[256], b = new int[256];
    private final boolean identity;

    /**
     * @param warm  -10 (cooler) .. 10 (warmer)
     * @param tint  -10 (greener) .. 10 (pinker)
     * @param gammaR,gammaG,gammaB response per channel in percent; 100 = unchanged,
     *                             lower = stronger mid-tones for that channel
     */
    public LedCorrection(int warm, int tint, int gammaR, int gammaG, int gammaB) {
        float w = clamp(warm / 10f), t = clamp(tint / 10f);
        float gr = 1, gg = 1, gb = 1;
        if (w > 0) gb *= 1 - 0.6f * w; else gr *= 1 + 0.6f * w;
        if (t > 0) {
            gg *= 1 - 0.5f * t;
        } else {
            gr *= 1 + 0.5f * t;
            gb *= 1 + 0.5f * t;
        }
        fill(r, gr, gammaR / 100.0);
        fill(g, gg, gammaG / 100.0);
        fill(b, gb, gammaB / 100.0);
        identity = warm == 0 && tint == 0 && gammaR == 100 && gammaG == 100 && gammaB == 100;
    }

    private static void fill(int[] lut, float gain, double gamma) {
        gamma = Math.max(0.2, gamma);
        for (int v = 0; v < 256; v++) {
            long o = Math.round(255 * gain * Math.pow(v / 255.0, gamma));
            lut[v] = (int) Math.max(0, Math.min(255, o));
        }
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    public boolean isIdentity() {
        return identity;
    }

    public int apply(int c) {
        if (identity) return c;
        return r[(c >> 16) & 0xFF] << 16 | g[(c >> 8) & 0xFF] << 8 | b[c & 0xFF];
    }

    public int[] apply(int[] colors) {
        if (identity) return colors;
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = apply(colors[i]);
        return out;
    }
}
