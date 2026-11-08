package com.jad.tvglow;

import java.util.Base64;

/**
 * Govee LAN protocol (UDP JSON on port 4003), plus the undocumented "razer"
 * realtime protocol used by Govee Desktop / Razer Chroma for per-segment color.
 *
 * Razer binary frame: {0xBB, sizeHi, sizeLo, cmd, data..., xorChecksum}
 *   0xB1 enable:   data = {on ? 1 : 0}
 *   0xB0 LED data: data = {flag, count, r,g,b, r,g,b, ...}
 *     flag 1 = discrete zones (what devices expect by default), 0 = blend.
 *     Some docs describe this flag inverted; sending 0 makes devices ignore frames.
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
    public static byte[] ledData(int[] rgb, boolean gradient) {
        int n = Math.min(rgb.length, 255);
        byte[] data = new byte[2 + n * 3];
        data[0] = (byte) (gradient ? 0 : 1);
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
