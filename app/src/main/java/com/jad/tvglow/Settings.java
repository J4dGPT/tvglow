package com.jad.tvglow;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static final int[] SEGMENT_OPTIONS = {10, 15, 20, 30, 40};
    static final int[] FPS_OPTIONS = {10, 15, 20, 30};
    static final int[] BRIGHTNESS_OPTIONS = {40, 60, 80, 100};
    static final String[] SMOOTH_NAMES = {"Fast", "Balanced", "Smooth"};
    static final float[] SMOOTH_ALPHA = {0.85f, 0.5f, 0.25f};

    int segments = 20;
    boolean fourSides = false;
    boolean reverse = false;
    int shift = 0;
    int brightness = 100;
    int fps = 20;
    int smoothing = 1;
    boolean gradient = false;
    boolean single = false;
    float saturation = 1.25f;
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
        s.fps = p.getInt("fps", s.fps);
        s.smoothing = p.getInt("smoothing", s.smoothing);
        s.gradient = p.getBoolean("blend", s.gradient);
        s.single = p.getBoolean("single", s.single);
        s.ip = p.getString("ip", s.ip);
        return s;
    }

    void save(Context c) {
        prefs(c).edit()
                .putInt("segments", segments)
                .putBoolean("fourSides", fourSides)
                .putBoolean("reverse", reverse)
                .putInt("shift", shift)
                .putInt("brightness", brightness)
                .putInt("fps", fps)
                .putInt("smoothing", smoothing)
                .putBoolean("blend", gradient)
                .putBoolean("single", single)
                .putString("ip", ip)
                .apply();
    }

    static int next(int[] options, int current) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) return options[(i + 1) % options.length];
        }
        return options[0];
    }
}
