package com.jad.tvglow;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static final int[] SEGMENT_OPTIONS = {10, 15, 20, 30, 40};
    static final int[] APP_SEGMENT_OPTIONS = {8, 10, 12, 14, 15, 16};
    static final String[] STYLE_NAMES = {"Cozy", "Vivid", "Accurate"};
    static final String[] MODE_NAMES = {"App segments", "Turbo realtime", "Single color", "WLED"};
    static final int MODE_SEGMENTS = 0, MODE_REALTIME = 1, MODE_SINGLE = 2, MODE_WLED = 3;
    static final int[] LED_OPTIONS = {30, 60, 90, 120, 150, 180, 240, 300};
    static final int[] FPS_OPTIONS = {10, 15, 20, 30, 60};
    static final String[] SPEED_NAMES = {"Safe", "Fast", "Turbo", "Max"};
    static final int[] SEG_CMDS = {60, 100, 150, 220};  // App segments: commands per second
    static final int[] RT_FPS = {20, 30, 45, 60};       // Turbo realtime: packets per second
    static final int[] WLED_FPS = {30, 40, 50, 60};     // WLED: frames per second
    static final int[] DETAIL_GROUP = {1, 2, 3};
    static final String[] DETAIL_NAMES = {"Max", "Balanced", "Smoothest"};
    static final int[] BRIGHTNESS_OPTIONS = {40, 60, 80, 100};
    static final String[] SMOOTH_NAMES = {"Fast", "Balanced", "Smooth", "Instant"};
    static final float[] SMOOTH_ALPHA = {0.85f, 0.55f, 0.3f, 1f}; // original speeds + no smoothing

    int segments = 20;
    boolean fourSides = false;
    boolean reverse = false;
    int shift = 0;
    int brightness = 100;
    int fps = 20;
    int speed = 1; // index into SPEED_NAMES
    int capture = 0; // 0 small, 1 medium, 2 large (applies on next Start)
    int detail = 1; // index into DETAIL_GROUP; 2 segments per zone by default
    int smoothing = 1;
    boolean gradient = false;
    int mode = MODE_SEGMENTS;
    int style = EdgeSampler.STYLE_COZY;
    int appSegments = 15;
    int[] sides = {0, 0, 0, 0}; // custom left/top/right/bottom counts; all 0 = auto
    float saturation = 1.35f;
    String ip = "";
    String wledIp = "";
    int ledCount = 120;

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
        s.speed = p.getInt("speed", s.speed);
        s.smoothing = p.getInt("smoothing", s.smoothing);
        s.gradient = p.getBoolean("blend", s.gradient);
        s.mode = p.getInt("mode", s.mode);
        s.detail = p.getInt("detail", s.detail);
        s.capture = p.getInt("capture", s.capture);
        s.style = p.getInt("style", s.style);
        s.appSegments = p.getInt("appSegments", s.appSegments);
        for (int i = 0; i < 4; i++) s.sides[i] = p.getInt("side" + i, 0);
        s.ip = p.getString("ip", s.ip);
        s.wledIp = p.getString("wledIp", s.wledIp);
        s.ledCount = p.getInt("ledCount", s.ledCount);
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
                .putInt("speed", speed)
                .putInt("smoothing", smoothing)
                .putBoolean("blend", gradient)
                .putInt("mode", mode)
                .putInt("detail", detail)
                .putInt("capture", capture)
                .putInt("style", style)
                .putInt("appSegments", appSegments)
                .putString("ip", ip)
                .putString("wledIp", wledIp)
                .putInt("ledCount", ledCount)
                .apply();
    }

    /** Number of color zones the current mode drives. */
    int zoneCount() {
        if (mode == MODE_SEGMENTS || mode == MODE_REALTIME) return appSegments; // must match the Govee app
        if (mode == MODE_SINGLE) return 12;
        return segments;
    }

    int speedIdx() {
        return Math.max(0, Math.min(SPEED_NAMES.length - 1, speed));
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
