package com.jad.tvglow;

/** sRGB, linear light and OKLab helpers for perceptual color differences. */
public final class ColorMath {
    private ColorMath() {}

    static final float[] LIN = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LIN[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** Linear light (0..1) back to an 8-bit sRGB channel. */
    static int encode(double lin) {
        if (lin <= 0) return 0;
        double c = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
        int v = (int) Math.round(c * 255);
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** Writes the OKLab coordinates of a packed 0xRRGGBB color into out[o..o+2]. */
    public static void oklab(int c, float[] out, int o) {
        float r = LIN[(c >> 16) & 0xFF], g = LIN[(c >> 8) & 0xFF], b = LIN[c & 0xFF];
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        out[o] = (float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s);
        out[o + 1] = (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s);
        out[o + 2] = (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
    }

    /** Squared OKLab distance between two stored colors. */
    public static float d2(float[] a, int ao, float[] b, int bo) {
        float dl = a[ao] - b[bo], da = a[ao + 1] - b[bo + 1], db = a[ao + 2] - b[bo + 2];
        return dl * dl + da * da + db * db;
    }

    /** Average (in linear light) of the colors whose bit is set in mask. */
    public static int mean(int[] colors, int mask) {
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int i = 0; i < colors.length && i < 31; i++) {
            if ((mask & (1 << i)) == 0) continue;
            int c = colors[i];
            r += LIN[(c >> 16) & 0xFF];
            g += LIN[(c >> 8) & 0xFF];
            b += LIN[c & 0xFF];
            n++;
        }
        if (n == 0) return 0;
        return encode(r / n) << 16 | encode(g / n) << 8 | encode(b / n);
    }
}
