package com.jad.tvglow;

import java.util.ArrayList;
import java.util.List;

/** Pure packet building and zone-to-LED upsampling for WLED (testable without Android). */
public final class WledProtocol {
    private WledProtocol() {}

    static final int DRGB = 2, DNRGB = 4;
    static final int DRGB_MAX = 490, DNRGB_CHUNK = 489;

    public static List<byte[]> packets(int[] leds, int timeoutS) {
        List<byte[]> out = new ArrayList<>();
        int n = leds.length;
        if (n <= DRGB_MAX) {
            byte[] p = new byte[2 + n * 3];
            p[0] = DRGB;
            p[1] = (byte) timeoutS;
            for (int i = 0; i < n; i++) put(p, 2 + i * 3, leds[i]);
            out.add(p);
            return out;
        }
        for (int start = 0; start < n; start += DNRGB_CHUNK) {
            int m = Math.min(DNRGB_CHUNK, n - start);
            byte[] p = new byte[4 + m * 3];
            p[0] = DNRGB;
            p[1] = (byte) timeoutS;
            p[2] = (byte) ((start >> 8) & 0xFF);
            p[3] = (byte) (start & 0xFF);
            for (int i = 0; i < m; i++) put(p, 4 + i * 3, leds[start + i]);
            out.add(p);
        }
        return out;
    }

    private static void put(byte[] p, int o, int c) {
        p[o] = (byte) ((c >> 16) & 0xFF);
        p[o + 1] = (byte) ((c >> 8) & 0xFF);
        p[o + 2] = (byte) (c & 0xFF);
    }

    /**
     * Spreads zone colors across ledCount LEDs with linear blending between zone
     * centers, so the strip shows smooth gradients instead of hard blocks.
     */
    public static int[] upsample(int[] zones, int ledCount, boolean circular) {
        int z = zones.length;
        int[] out = new int[ledCount];
        if (z == 0) return out;
        for (int i = 0; i < ledCount; i++) {
            double pos = (i + 0.5) * z / ledCount - 0.5; // position in zone-center units
            int a = (int) Math.floor(pos);
            double t = pos - a;
            int ia, ib;
            if (circular) {
                ia = Math.floorMod(a, z);
                ib = Math.floorMod(a + 1, z);
            } else {
                ia = Math.max(0, Math.min(z - 1, a));
                ib = Math.max(0, Math.min(z - 1, a + 1));
            }
            out[i] = mix(zones[ia], zones[ib], t);
        }
        return out;
    }

    private static int mix(int x, int y, double t) {
        int r = (int) Math.round(((x >> 16) & 0xFF) * (1 - t) + ((y >> 16) & 0xFF) * t);
        int g = (int) Math.round(((x >> 8) & 0xFF) * (1 - t) + ((y >> 8) & 0xFF) * t);
        int b = (int) Math.round((x & 0xFF) * (1 - t) + (y & 0xFF) * t);
        return r << 16 | g << 8 | b;
    }
}
