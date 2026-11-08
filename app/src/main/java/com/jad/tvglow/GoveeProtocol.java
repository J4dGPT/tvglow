package com.jad.tvglow;

import java.util.Base64;

/**
 * Govee LAN protocol (UDP JSON on port 4003), plus the undocumented "razer"
 * realtime protocol used by Govee Desktop / Razer Chroma for per-segment color.
 *
 * Razer binary frame: {0xBB, sizeHi, sizeLo, cmd, data..., xorChecksum}
 *   0xB1 enable:   data = {on ? 1 : 0}
 *   0xB0 LED data: data = {stretch, count, r,g,b, r,g,b, ...}
 *     stretch 0 = one color per segment, 1 = stretch the colors across all LEDs.
 *     count must equal the segment count shown in the Govee app, or the device
 *     silently drops the packet (H6167: 12, confirmed working in LedFx).
 * The device drops out of razer mode if no LED data arrives for ~60s.
 */
public final class GoveeProtocol {
    private GoveeProtocol() {}

    public static final String MULTICAST_ADDR = "239.255.255.250";
    public static final int SCAN_PORT = 4001;
    public static final int LISTEN_PORT = 4002;
    public static final int CONTROL_PORT = 4003;

    public static final String SCAN_JSON =
            "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}";

    public static String turnJson(boolean on) {
        return "{\"msg\":{\"cmd\":\"turn\",\"data\":{\"value\":" + (on ? 1 : 0) + "}}}";
    }

    /** Official LAN command: whole strip one color. */
    public static String colorJson(int rgb) {
        return "{\"msg\":{\"cmd\":\"colorwc\",\"data\":{\"color\":{\"r\":" + ((rgb >> 16) & 0xFF)
                + ",\"g\":" + ((rgb >> 8) & 0xFF) + ",\"b\":" + (rgb & 0xFF)
                + "},\"colorTemInKelvin\":0}}}";
    }

    /**
     * App-style segment color command (what the Govee app sends over Bluetooth),
     * tunneled over LAN with ptReal. 20 bytes: 33 05 15 01 R G B 00x5 maskLo maskHi,
     * zero padded, last byte = XOR of the first 19. Bit n of mask = segment n+1.
     */
    public static byte[] segmentColorFrame(int rgb, int mask) {
        byte[] f = new byte[20];
        f[0] = 0x33;
        f[1] = 0x05;
        f[2] = 0x15;
        f[3] = 0x01;
        f[4] = (byte) ((rgb >> 16) & 0xFF);
        f[5] = (byte) ((rgb >> 8) & 0xFF);
        f[6] = (byte) (rgb & 0xFF);
        f[12] = (byte) (mask & 0xFF);
        f[13] = (byte) ((mask >> 8) & 0xFF);
        byte x = 0;
        for (int i = 0; i < 19; i++) x ^= f[i];
        f[19] = x;
        return f;
    }

    public static String ptRealJson(java.util.List<byte[]> frames) {
        StringBuilder sb = new StringBuilder("{\"msg\":{\"cmd\":\"ptReal\",\"data\":{\"command\":[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Base64.getEncoder().encodeToString(frames.get(i))).append('"');
        }
        return sb.append("]}}}").toString();
    }

    /**
     * Groups segments that share a color into one frame each. colors[i] is segment i+1.
     * Entries equal to -1 are skipped (unchanged). Max 16 segments.
     */
    public static java.util.List<byte[]> segmentFrames(int[] colors) {
        java.util.LinkedHashMap<Integer, Integer> masks = new java.util.LinkedHashMap<>();
        for (int i = 0; i < Math.min(16, colors.length); i++) {
            if (colors[i] < 0) continue;
            Integer m = masks.get(colors[i]);
            masks.put(colors[i], (m == null ? 0 : m) | (1 << i));
        }
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : masks.entrySet()) {
            out.add(segmentColorFrame(e.getKey(), e.getValue()));
        }
        return out;
    }

    static byte[] frame(int cmd, byte[] data) {
        int len = data.length;
        byte[] out = new byte[4 + len + 1];
        out[0] = (byte) 0xBB;
        out[1] = (byte) ((len >> 8) & 0xFF);
        out[2] = (byte) (len & 0xFF);
        out[3] = (byte) cmd;
        System.arraycopy(data, 0, out, 4, len);
        byte x = 0;
        for (int i = 0; i < out.length - 1; i++) x ^= out[i];
        out[out.length - 1] = x;
        return out;
    }

    public static byte[] razerEnable(boolean on) {
        return frame(0xB1, new byte[]{(byte) (on ? 1 : 0)});
    }

    /** rgb entries are packed 0xRRGGBB. Max 255 colors. */
    public static byte[] ledData(int[] rgb, boolean stretch) {
        int n = Math.min(rgb.length, 255);
        byte[] data = new byte[2 + n * 3];
        data[0] = (byte) (stretch ? 1 : 0);
        data[1] = (byte) n;
        for (int i = 0; i < n; i++) {
            int c = rgb[i];
            data[2 + i * 3] = (byte) ((c >> 16) & 0xFF);
            data[3 + i * 3] = (byte) ((c >> 8) & 0xFF);
            data[4 + i * 3] = (byte) (c & 0xFF);
        }
        return frame(0xB0, data);
    }

    public static String razerJson(byte[] packet) {
        String b64 = Base64.getEncoder().encodeToString(packet);
        return "{\"msg\":{\"cmd\":\"razer\",\"data\":{\"pt\":\"" + b64 + "\"}}}";
    }
}
