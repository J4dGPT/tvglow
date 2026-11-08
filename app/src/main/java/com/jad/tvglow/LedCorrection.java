package com.jad.tvglow;

/**
 * Output correction so the strip's light matches the TV, using an RGB color cube (the
 * approach HyperHDR uses): the 8 corners of the cube (black, red, green, blue, cyan,
 * magenta, yellow, white) each map to a calibrated output, and every other color is
 * blended from the corners (trilinear interpolation).
 *
 * Why a cube and not per-channel curves: a curve that boosts red mid-tones also multiplies
 * tiny red values, so dark navy turns pink and grass turns orange. In the cube, black is
 * pinned, and each adjustment only moves colors near the corner being adjusted.
 *
 * Calibrated corners: white (warmth, tint), cyan, magenta, yellow. Each value -10..10.
 */
public final class LedCorrection {
    public static final LedCorrection NONE = new LedCorrection(0, 0, 0, 0, 0);

    /** corner[k] = output for the corner with r = bit 2, g = bit 1, b = bit 0 of k. */
    private final float[][] corner = new float[8][3];
    private final boolean identity;

    /**
     * @param warm    -10 cooler .. 10 warmer (white)
     * @param tint    -10 greener .. 10 pinker (white)
     * @param cyan    -10 bluer .. 10 greener
     * @param magenta -10 redder .. 10 more purple
     * @param yellow  -10 more orange .. 10 greener
     */
    public LedCorrection(int warm, int tint, int cyan, int magenta, int yellow) {
        for (int k = 0; k < 8; k++) {
            corner[k][0] = (k >> 2) & 1;
            corner[k][1] = (k >> 1) & 1;
            corner[k][2] = k & 1;
        }
        float w = clamp(warm), t = clamp(tint), c = clamp(cyan), m = clamp(magenta), y = clamp(yellow);
        float[] white = corner[7];
        if (w > 0) white[2] *= 1 - 0.5f * w; else white[0] *= 1 + 0.5f * w;
        if (t > 0) {
            white[1] *= 1 - 0.4f * t;
        } else {
            white[0] *= 1 + 0.4f * t;
            white[2] *= 1 + 0.4f * t;
        }
        float[] cy = corner[3]; // r0 g1 b1
        if (c > 0) cy[2] *= 1 - 0.5f * c; else cy[1] *= 1 + 0.5f * c;
        float[] mg = corner[5]; // r1 g0 b1
        if (m > 0) mg[0] *= 1 - 0.5f * m; else mg[2] *= 1 + 0.5f * m;
        float[] ye = corner[6]; // r1 g1 b0
        if (y > 0) ye[0] *= 1 - 0.5f * y; else ye[1] *= 1 + 0.5f * y;
        identity = warm == 0 && tint == 0 && cyan == 0 && magenta == 0 && yellow == 0;
    }

    private static float clamp(int v) {
        return Math.max(-1f, Math.min(1f, v / 10f));
    }

    public boolean isIdentity() {
        return identity;
    }

    public int apply(int c) {
        if (identity) return c;
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float or = 0, og = 0, ob = 0;
        for (int k = 1; k < 8; k++) { // corner 0 is black and contributes nothing
            float wt = (((k >> 2) & 1) == 1 ? r : 1 - r)
                    * (((k >> 1) & 1) == 1 ? g : 1 - g)
                    * ((k & 1) == 1 ? b : 1 - b);
            or += wt * corner[k][0];
            og += wt * corner[k][1];
            ob += wt * corner[k][2];
        }
        return to8(or) << 16 | to8(og) << 8 | to8(ob);
    }

    public int[] apply(int[] colors) {
        if (identity) return colors;
        int[] out = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out[i] = apply(colors[i]);
        return out;
    }

    private static int to8(float v) {
        int i = Math.round(v * 255);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
