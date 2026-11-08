package com.jad.tvglow;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Match colors: the TV shows a reference color, the strip shows the same color through the
 * current correction, and the viewer nudges one setting per step with left/right on the
 * remote until the glow on the wall matches the screen.
 *
 * Steps: white (warmth, then tint), then the three mixed colors where LED strips drift
 * most: cyan, magenta and yellow. Each step moves one corner of the color cube (see
 * LedCorrection), so it only affects colors near that one.
 */
public class CalibrateActivity extends Activity {
    /** Message for the main screen after the wizard closes. */
    static volatile String result;

    private static final String[] TITLES = {"White", "White tint", "Cyan", "Magenta", "Yellow"};
    private static final String[] LEFT = {"cooler", "greener", "bluer", "redder", "more orange"};
    private static final String[] RIGHT = {"warmer", "pinker", "greener", "more purple", "greener"};
    private static final int[] SWATCH = {0xFFFFFF, 0xFFFFFF, 0x00FFFF, 0xFF00FF, 0xFFFF00};

    private static final int BG = 0xFF0B0B12, TEXT = 0xFFECECF4, MUTED = 0xFF8C8CA3;
    private static final int ACCENT_A = 0xFFFF4FD8, ACCENT_B = 0xFF7C4DFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private Settings settings;
    private final int[] vals = new int[5]; // warm, tint, cyan, magenta, yellow
    private int step;
    private boolean saved;

    private View swatch, spaceLeft, spaceRight;
    private TextView title, stepLabel, leftLabel, rightLabel, note;
    private volatile GoveeClient client;
    private volatile WledClient wled;
    private volatile boolean alive = true;

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            send();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        result = null;
        settings = Settings.load(this);
        vals[0] = settings.calWarm;
        vals[1] = settings.calTint;
        vals[2] = settings.calCyan;
        vals[3] = settings.calMag;
        vals[4] = settings.calYel;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        swatch = new View(this);
        root.addView(swatch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG);
        panel.setPadding(dp(56), dp(18), dp(56), dp(22));
        root.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout head = row(panel);
        title = text(head, "", 26, TEXT, true);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        stepLabel = text(head, "", 15, MUTED, false);
        note = text(panel, "Make the glow on the wall match the color on the screen.", 16, TEXT, false);
        note.setPadding(0, dp(4), 0, dp(12));

        // ◀ left label  [track with marker]  right label ▶
        LinearLayout bar = row(panel);
        leftLabel = text(bar, "", 16, TEXT, false);
        leftLabel.setPadding(0, 0, dp(16), 0);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFF2C2C40);
        trackBg.setCornerRadius(dp(7));
        track.setBackground(trackBg);
        bar.addView(track, new LinearLayout.LayoutParams(0, dp(14), 1f));
        spaceLeft = new View(this);
        track.addView(spaceLeft, new LinearLayout.LayoutParams(0, dp(14), 0.5f));
        View marker = new View(this);
        GradientDrawable dot = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_A, ACCENT_B});
        dot.setShape(GradientDrawable.OVAL);
        dot.setStroke(dp(2), Color.WHITE);
        marker.setBackground(dot);
        track.addView(marker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        spaceRight = new View(this);
        track.addView(spaceRight, new LinearLayout.LayoutParams(0, dp(14), 0.5f));
        rightLabel = text(bar, "", 16, TEXT, false);
        rightLabel.setPadding(dp(16), 0, 0, 0);

        TextView keys = text(panel, "◀ ▶ adjust   ·   OK next step   ·   BACK previous step", 14, MUTED, false);
        keys.setPadding(0, dp(14), 0, 0);

        setContentView(root);
        showStep();
        connect();
    }

    private void connect() {
        note.setText("Connecting to the strip...");
        net.execute(() -> {
            try {
                if (settings.mode == Settings.MODE_WLED) {
                    if (settings.wledIp == null || settings.wledIp.isEmpty()) {
                        msg("Enter the WLED address on the main screen first.");
                        return;
                    }
                    wled = new WledClient(settings.wledIp);
                } else {
                    String ip = GoveeClient.resolveIp(this, settings);
                    if (ip == null) {
                        msg("No strip found. Is LAN Control on in the Govee app?");
                        return;
                    }
                    GoveeClient c = new GoveeClient(ip);
                    c.turnOn();
                    if (settings.mode == Settings.MODE_REALTIME) {
                        c.razer(true);
                        Thread.sleep(120);
                    } else {
                        c.razer(false);
                    }
                    client = c;
                }
                msg("Make the glow on the wall match the color on the screen.");
                ui.post(keepAlive);
            } catch (Exception e) {
                msg("Could not reach the strip: " + e.getMessage());
            }
        });
    }

    private void msg(String s) {
        ui.post(() -> note.setText(s));
    }

    private LedCorrection current() {
        return new LedCorrection(vals[0], vals[1], vals[2], vals[3], vals[4]);
    }

    /** Shows the current step's swatch on the strip (latest values win). */
    private void send() {
        final int color = current().apply(SWATCH[step]);
        final Settings s = settings;
        try {
            net.execute(() -> {
                try {
                    GoveeClient c = client;
                    WledClient w = wled;
                    if (s.mode == Settings.MODE_WLED) {
                        if (w == null) return;
                        int[] leds = new int[Math.max(1, s.ledCount)];
                        Arrays.fill(leds, color);
                        w.send(leds);
                    } else if (c != null) {
                        if (s.mode == Settings.MODE_REALTIME) {
                            int[] zones = new int[Math.max(1, s.zoneCount())]; // must match the Govee app
                            Arrays.fill(zones, color);
                            c.colors(zones, false);
                        } else if (s.mode == Settings.MODE_SINGLE) {
                            c.color(color);
                        } else {
                            int n = Math.min(16, Math.max(1, s.zoneCount()));
                            c.segmentCmds(Collections.singletonList(new SegmentScheduler.Cmd((1 << n) - 1, color)));
                        }
                    }
                } catch (Exception ignored) {
                    // next keep-alive retries
                }
            });
        } catch (Exception ignored) {
            // executor already shut down
        }
    }

    private void showStep() {
        swatch.setBackgroundColor(0xFF000000 | SWATCH[step]);
        title.setText(TITLES[step]);
        stepLabel.setText("Step " + (step + 1) + " of " + TITLES.length);
        leftLabel.setText("◀  " + LEFT[step]);
        rightLabel.setText(RIGHT[step] + "  ▶");
        float pos = Math.max(0f, Math.min(1f, (vals[step] + 10) / 20f));
        spaceLeft.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), Math.max(0.001f, pos)));
        spaceRight.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), Math.max(0.001f, 1f - pos)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void adjust(int dir) {
        vals[step] = clamp(vals[step] + dir, -10, 10);
        showStep();
        send();
    }

    private void next() {
        if (step < TITLES.length - 1) {
            step++;
            showStep();
            send();
            return;
        }
        Settings s = Settings.load(this); // fresh copy, in case discovery saved the address
        s.calWarm = vals[0];
        s.calTint = vals[1];
        s.calCyan = vals[2];
        s.calMag = vals[3];
        s.calYel = vals[4];
        s.style = EdgeSampler.STYLE_ACCURATE;
        s.save(this);
        saved = true;
        result = "Colors matched and saved. Style set to Accurate. Press Start sync.";
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjust(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjust(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                next();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (step > 0) {
                    step--;
                    showStep();
                    send();
                } else {
                    result = "Color matching cancelled. Nothing was changed.";
                    finish();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        ui.removeCallbacks(keepAlive);
        if (!saved && result == null) result = "Color matching closed. Nothing was changed.";
        final GoveeClient c = client;
        final WledClient w = wled;
        final boolean realtime = settings.mode == Settings.MODE_REALTIME;
        net.execute(() -> {
            try {
                if (c != null) {
                    if (realtime) c.razer(false); // hand the strip back to its normal mode
                    c.close();
                }
                if (w != null) {
                    w.release();
                    w.close();
                }
            } catch (Exception ignored) {
            }
        });
        net.shutdown();
        super.onDestroy();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView text(LinearLayout parent, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        parent.addView(t);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
