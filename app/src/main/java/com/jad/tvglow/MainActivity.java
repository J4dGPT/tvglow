package com.jad.tvglow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Settings settings;
    private TextView statusView;
    private String localStatus = null;

    private Button modeBtn, sidesBtn, reverseBtn, shiftBtn, segBtn, brightBtn, fpsBtn, smoothBtn, gradBtn;
    private EditText ipField;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String s = localStatus != null ? localStatus : CaptureService.status;
            statusView.setText((CaptureService.running ? "RUNNING  |  " : "") + s);
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = Settings.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(14, 14, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(36);
        root.setPadding(pad, dp(24), pad, dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("TV Glow");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusView.setTextColor(Color.rgb(255, 120, 220));
        statusView.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusView);

        LinearLayout row1 = row(root);
        Button start = button(row1, "Start sync", v -> requestCapture());
        button(row1, "Stop", v -> stopAll());
        button(row1, "Find strip", v -> findStrip());
        button(row1, "Test LAN (red)", v -> testLan());
        button(row1, "Test zones", v -> testPattern());

        section(root, "Strip layout");
        LinearLayout row2 = row(root);
        modeBtn = button(row2, "", v -> { settings.single = !settings.single; changed(); });
        sidesBtn = button(row2, "", v -> { settings.fourSides = !settings.fourSides; changed(); });
        reverseBtn = button(row2, "", v -> { settings.reverse = !settings.reverse; changed(); });
        shiftBtn = button(row2, "", v -> { settings.shift = (settings.shift + 1) % settings.segments; changed(); });
        segBtn = button(row2, "", v -> {
            settings.segments = Settings.next(Settings.SEGMENT_OPTIONS, settings.segments);
            settings.shift = 0;
            changed();
        });

        section(root, "Look");
        LinearLayout row3 = row(root);
        brightBtn = button(row3, "", v -> { settings.brightness = Settings.next(Settings.BRIGHTNESS_OPTIONS, settings.brightness); changed(); });
        smoothBtn = button(row3, "", v -> { settings.smoothing = (settings.smoothing + 1) % 3; changed(); });
        fpsBtn = button(row3, "", v -> { settings.fps = Settings.next(Settings.FPS_OPTIONS, settings.fps); changed(); });
        gradBtn = button(row3, "", v -> { settings.gradient = !settings.gradient; changed(); });

        section(root, "Strip address (leave blank to auto-find)");
        LinearLayout row4 = row(root);
        ipField = new EditText(this);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setTextColor(Color.WHITE);
        ipField.setHint("e.g. 192.168.1.50");
        ipField.setHintTextColor(Color.GRAY);
        ipField.setMinWidth(dp(260));
        ipField.setText(settings.ip);
        row4.addView(ipField);
        button(row4, "Save address", v -> {
            settings.ip = ipField.getText().toString().trim();
            changed();
            local(settings.ip.isEmpty() ? "Address cleared, will auto-find" : "Saved " + settings.ip);
        });

        TextView help = new TextView(this);
        help.setTextColor(Color.LTGRAY);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setPadding(0, dp(20), 0, 0);
        help.setText("Start sync, allow screen capture, then press Home and play something.\n"
                + "Test LAN turns the whole strip red (checks the connection). Test zones: red at the bottom of the LEFT side, fading to blue. "
                + "If red is on the right, toggle Reverse. Use Shift to nudge where the colors start.\n"
                + "Netflix, Disney+ and HDMI inputs can't be captured; YouTube, Plex, MX Player and games work.");
        root.addView(help);

        setContentView(scroll);
        refreshLabels();
        start.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    private void requestCapture() {
        localStatus = null;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            local("This TV has no screen-capture permission screen. See README: grant it with adb.");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            local("Screen capture permission was denied");
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, result)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        localStatus = null;
    }

    private void stopAll() {
        localStatus = null;
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        } else {
            // end a test pattern, if one is showing
            final String ip = settings.ip;
            if (ip.isEmpty()) return;
            new Thread(() -> {
                try (GoveeClient c = new GoveeClient(ip)) {
                    c.razer(false);
                } catch (Exception ignored) {
                }
            }).start();
            local("Stopped");
        }
    }

    private void findStrip() {
        local("Searching the network...");
        new Thread(() -> {
            List<GoveeClient.Found> list = GoveeClient.discover(this, 3000);
            ui.post(() -> {
                if (list.isEmpty()) {
                    local("No strip found. Turn on LAN Control in the Govee app and check the TV is on the same network.");
                    return;
                }
                GoveeClient.Found pick = list.get(0);
                for (GoveeClient.Found f : list) if ("H6167".equalsIgnoreCase(f.sku)) pick = f;
                settings.ip = pick.ip;
                ipField.setText(pick.ip);
                changed();
                local("Found " + pick.sku + " at " + pick.ip + (list.size() > 1 ? " (" + list.size() + " devices total)" : ""));
            });
        }).start();
    }

    private void testLan() {
        if (CaptureService.running) {
            local("Stop sync first, then run the test");
            return;
        }
        local("Sending red to the whole strip...");
        final Settings s = settings;
        new Thread(() -> {
            String ip = GoveeClient.resolveIp(this, s);
            if (ip == null) {
                ui.post(() -> local("No strip found. Turn on LAN Control in the Govee app."));
                return;
            }
            try (GoveeClient c = new GoveeClient(ip)) {
                c.razer(false);
                Thread.sleep(150);
                c.turnOn();
                Thread.sleep(150);
                for (int i = 0; i < 2; i++) {
                    c.color(0xFF0000);
                    Thread.sleep(200);
                }
                ui.post(() -> {
                    ipField.setText(ip);
                    local("Sent red to " + ip + ". Whole strip red = network works. Nothing = wrong address or LAN Control off.");
                });
            } catch (Exception e) {
                ui.post(() -> local("Test failed: " + e.getMessage()));
            }
        }).start();
    }

    private void testPattern() {
        if (CaptureService.running) {
            local("Stop sync first, then run the test");
            return;
        }
        local("Sending test pattern...");
        final Settings s = settings;
        new Thread(() -> {
            String ip = GoveeClient.resolveIp(this, s);
            if (ip == null) {
                ui.post(() -> local("No strip found. Turn on LAN Control in the Govee app."));
                return;
            }
            try (GoveeClient c = new GoveeClient(ip)) {
                c.turnOn();
                c.razer(true);
                int[] colors = EdgeSampler.order(EdgeSampler.testPattern(s.segments), s.reverse, s.shift);
                for (int i = 0; i < 3; i++) {
                    c.colors(colors, false);
                    Thread.sleep(150);
                }
                ui.post(() -> {
                    ipField.setText(ip);
                    local("Sent zone test to " + ip + ". Red should start at the bottom of the left side. Strip dark = this strip doesn't support zones; switch Mode to Single color.");
                });
            } catch (Exception e) {
                ui.post(() -> local("Test failed: " + e.getMessage()));
            }
        }).start();
    }

    private void changed() {
        settings.save(this);
        refreshLabels();
    }

    private void refreshLabels() {
        modeBtn.setText("Mode: " + (settings.single ? "Single color" : "Zones"));
        sidesBtn.setText(settings.fourSides ? "Sides: 4 (full loop)" : "Sides: 3 (left, top, right)");
        reverseBtn.setText("Reverse: " + (settings.reverse ? "on" : "off"));
        shiftBtn.setText("Shift: " + settings.shift);
        segBtn.setText("Zones: " + settings.segments);
        brightBtn.setText("Brightness: " + settings.brightness + "%");
        smoothBtn.setText("Motion: " + Settings.SMOOTH_NAMES[settings.smoothing]);
        fpsBtn.setText("Rate: " + settings.fps + "/s");
        gradBtn.setText("Blend zones: " + (settings.gradient ? "on" : "off"));
    }

    private void local(String s) {
        localStatus = s;
        statusView.setText(s);
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r);
        return r;
    }

    private void section(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.GRAY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(18), 0, dp(4));
        parent.addView(t);
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        parent.addView(btn, lp);
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
