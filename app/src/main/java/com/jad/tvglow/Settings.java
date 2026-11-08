package com.jad.tvglow;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static final int[] SEGMENT_OPTIONS = {10, 15, 20, 30, 40};
    static final int[] APP_SEGMENT_OPTIONS = {8, 10, 12, 14, 15, 16};
    static final String[] STYLE_NAMES = {"Cozy", "Vivid", "Accurate"};
    static final String[] MODE_NAMES = {"App segments", "Realtime zones", "Single color"};
    static final int MODE_SEGMENTS = 0, MODE_REALTIME = 1, MODE_SINGLE = 2;
    static final int[] FPS_OPTIONS = {10, 15, 20};
    static final int[] BRIGHTNESS_OPTIONS = {40, 60, 80, 100};
    static final String[] SMOOTH_NAMES = {"Fast", "Balanced", "Smooth"};
    static final float[] SMOOTH_ALPHA = {0.85f, 0.55f, 0.3f}; // original speeds

    int segments = 20;
    boolean fourSides = false;
    boolean reverse = false;
    int shift = 0;
    int brightness = 100;
    int fps = 20;
    int smoothing = 1;
    boolean gradient = false;
    int mode = MODE_SEGMENTS;
    int style = EdgeSampler.STYLE_COZY;
    int appSegments = 15;
    int[] sides = {0, 0, 0, 0}; // custom left/top/right/bottom counts; all 0 = auto
    float saturation = 1.35f;
    String ip = "";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tvglow", Context.MODE_PRIVATE);
    }

    static Settings load(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.segments = p.getInt("segments", s.segments);
        s.fourSides = p.getBoolean("fourSides", s.fourSides);
        s.reverse = p.getBoolean("reverse", s.reverse);
        s.shift = p.getInt("shift", s.shift);
        s.brightness = p.getInt("brightness", s.brightness);
        s.fps = p.getInt("rate4", s.fps);
        s.smoothing = p.getInt("smoothing", s.smoothing);
        s.gradient = p.getBoolean("blend", s.gradient);
        s.mode = p.getInt("mode", s.mode);
        s.style = p.getInt("style", s.style);
        s.appSegments = p.getInt("appSegments", s.appSegments);
        for (int i = 0; i < 4; i++) s.sides[i] = p.getInt("side" + i, 0);
        s.ip = p.getString("ip", s.ip);
        return s;
    }

    void save(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < 4; i++) e.putInt("side" + i, sides[i]);
        e
                .putInt("segments", segments)
                .putBoolean("fourSides", fourSides)
                .putBoolean("reverse", reverse)
                .putInt("shift", shift)
                .putInt("brightness", brightness)
                .putInt("rate4", fps)
                .putInt("smoothing", smoothing)
                .putBoolean("blend", gradient)
                .putInt("mode", mode)
                .putInt("style", style)
                .putInt("appSegments", appSegments)
                .putString("ip", ip)
                .apply();
    }

    /** Number of color zones the current mode drives. */
    int zoneCount() {
        if (mode == MODE_SEGMENTS) return appSegments;
        if (mode == MODE_SINGLE) return 12;
        return segments;
    }

    boolean customSides() {
        return sides[0] + sides[1] + sides[2] + sides[3] > 0;
    }

    /** Per-side zone counts actually in use. */
    int[] sideCounts() {
        if (customSides()) return sides.clone();
        return EdgeSampler.autoSideCounts(zoneCount(), fourSides);
    }

    static int next(int[] options, int current) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) return options[(i + 1) % options.length];
        }
        return options[0];
    }
}
