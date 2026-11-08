package com.jad.tvglow;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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

    private final Button[] sideBtns = new Button[4];
    private Button ledBtn, captureBtn, detailBtn, styleBtn, modeBtn, sidesBtn, reverseBtn, shiftBtn, segBtn, brightBtn, fpsBtn, smoothBtn, gradBtn;
    private EditText ipField;

    // Palette: deep night background, pink-to-purple glow accent (matches the wall).
    private static final int BG = 0xFF0B0B12, CARD = 0xFF15151F, BTN = 0xFF1F1F2E, BTN_EDGE = 0xFF2C2C40;
    private static final int TEXT = 0xFFECECF4, MUTED = 0xFF8C8CA3, ACCENT_A = 0xFFFF4FD8, ACCENT_B = 0xFF7C4DFF;
    private static final int LIVE = 0xFF3BE38B, IDLE = 0xFF5A5A70;

    private TextView dotView, statusTitle;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean on = CaptureService.running;
            String s = localStatus != null ? localStatus : CaptureService.status;
            statusTitle.setText(on ? "Syncing" : "Not syncing");
            ((GradientDrawable) dotView.getBackground()).setColor(on ? LIVE : IDLE);
            statusView.setText(s);
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = Settings.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setClipChildren(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(dp(40), dp(28), dp(40), dp(28));
        page.setClipChildren(false);
        scroll.addView(page);

        LinearLayout left = column(page, 1f);
        spacer(page, dp(24));
        LinearLayout right = column(page, 1.35f);

        // Header + status
        TextView title = text(left, "TV Glow", 34, TEXT, true);
        title.setLetterSpacing(0.02f);
        text(left, "Your wall, synced to your screen", 15, MUTED, false).setPadding(0, 0, 0, dp(18));

        LinearLayout statusCard = card(left);
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.addView(statusRow);
        dotView = new TextView(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(IDLE);
        dotView.setBackground(dot);
        statusRow.addView(dotView, new LinearLayout.LayoutParams(dp(12), dp(12)));
        spacer(statusRow, dp(10));
        statusTitle = text(statusRow, "Not syncing", 20, TEXT, true);
        statusView = text(statusCard, "", 14, MUTED, false);
        statusView.setPadding(0, dp(8), 0, 0);

        // Sync
        LinearLayout sync = card(left);
        heading(sync, "SYNC");
        LinearLayout r = row(sync);
        Button start = button(r, "Start sync", v -> requestCapture(), true);
        button(r, "Stop", v -> stopAll(), false);
        r = row(sync);
        button(r, "Try Turbo", v -> {
            settings.mode = Settings.MODE_REALTIME;
            settings.gradient = false; // one color per segment
            ipField.setText(addr());
            changed();
            if (CaptureService.running) {
                local("Switched to Turbo while syncing. If the strip goes dark, press Mode until it says App segments.");
                return;
            }
            testPattern(false, "Turbo test sent. Red at the bottom-left fading to blue around the TV? Turbo works: "
                    + "press Start sync. Strip stayed dark? Press Stop, then Mode until it says App segments.");
        }, false);
        button(r, "Live preset", v -> {
            settings.smoothing = 3;  // Instant: no smoothing delay
            settings.detail = 0;     // full detail; the scheduler repaints big areas first
            settings.speed = 1;      // Fast
            settings.capture = 2;    // Large
            changed();
            local("Live preset on: Instant motion, full detail, Fast. Restart sync to apply.");
        }, false);

        // Connection
        LinearLayout conn = card(left);
        heading(conn, "CONNECTION");
        ipField = new EditText(this);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setTextColor(TEXT);
        ipField.setHint("Strip address, blank = auto-find");
        ipField.setHintTextColor(MUTED);
        ipField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ipField.setText(addr());
        ipField.setBackground(fieldBg());
        ipField.setPadding(dp(16), dp(12), dp(16), dp(12));
        conn.addView(ipField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        r = row(conn);
        button(r, "Save", v -> {
            String a = ipField.getText().toString().trim();
            if (settings.mode == Settings.MODE_WLED) settings.wledIp = a; else settings.ip = a;
            changed();
            local(a.isEmpty() ? "Address cleared" : "Saved " + a);
        }, false);
        button(r, "Find strip", v -> findStrip(), false);
        button(r, "Test LAN", v -> testLan(), false);

        TextView help = text(left, "Netflix, Disney+ and HDMI inputs can't be captured. YouTube, Plex, TiviMate and games work.", 13, MUTED, false);
        help.setPadding(dp(4), dp(14), dp(4), 0);

        // Calibrate
        LinearLayout cal = card(right);
        heading(cal, "CALIBRATE");
        r = row(cal);
        button(r, "Test zones", v -> testPattern(false), false);
        button(r, "Test sides", v -> testPattern(true), false);
        modeBtn = button(r, "", v -> { settings.mode = (settings.mode + 1) % 4; settings.shift = 0; ipField.setText(addr()); changed(); }, false);
        r = row(cal);
        sidesBtn = button(r, "", v -> { settings.fourSides = !settings.fourSides; changed(); }, false);
        reverseBtn = button(r, "", v -> { settings.reverse = !settings.reverse; changed(); }, false);
        shiftBtn = button(r, "", v -> { settings.shift = (settings.shift + 1) % Math.max(1, total(settings.sideCounts())); changed(); }, false);
        segBtn = button(r, "", v -> {
            if (settings.mode == Settings.MODE_SEGMENTS || settings.mode == Settings.MODE_REALTIME) {
                settings.appSegments = Settings.next(Settings.APP_SEGMENT_OPTIONS, settings.appSegments);
            } else {
                settings.segments = Settings.next(Settings.SEGMENT_OPTIONS, settings.segments);
            }
            settings.shift = 0;
            changed();
        }, false);
        ledBtn = button(r, "", v -> { settings.ledCount = Settings.next(Settings.LED_OPTIONS, settings.ledCount); changed(); }, false);
        text(cal, "Segments per side (* = auto)  ·  test: left red, top green, right blue, bottom white", 13, MUTED, false)
                .setPadding(dp(2), dp(14), 0, dp(2));
        r = row(cal);
        String[] sideNames = {"Left", "Top", "Right", "Bottom"};
        for (int i = 0; i < 4; i++) {
            final int side = i;
            sideBtns[i] = button(r, sideNames[i], v -> {
                if (!settings.customSides()) settings.sides = settings.sideCounts();
                settings.sides[side] = (settings.sides[side] + 1) % 9;
                changed();
            }, false);
        }
        button(r, "Auto", v -> { settings.sides = new int[]{0, 0, 0, 0}; changed(); }, false);

        // Look
        LinearLayout look = card(right);
        heading(look, "LOOK");
        r = row(look);
        styleBtn = button(r, "", v -> { settings.style = (settings.style + 1) % 3; changed(); }, false);
        brightBtn = button(r, "", v -> { settings.brightness = Settings.next(Settings.BRIGHTNESS_OPTIONS, settings.brightness); changed(); }, false);
        smoothBtn = button(r, "", v -> { settings.smoothing = (settings.smoothing + 1) % 4; changed(); }, false);
        r = row(look);
        fpsBtn = button(r, "", v -> { settings.speed = (settings.speedIdx() + 1) % Settings.SPEED_NAMES.length; changed(); }, false);
        detailBtn = button(r, "", v -> { settings.detail = (settings.detail + 1) % 3; changed(); }, false);
        captureBtn = button(r, "", v -> {
            settings.capture = (settings.capture + 1) % 3;
            changed();
            if (CaptureService.running) local("Capture size applies next time you press Start sync.");
        }, false);
        gradBtn = button(r, "", v -> { settings.gradient = !settings.gradient; changed(); }, false);
        r = row(look);
        button(r, "Match colors", v -> {
            if (CaptureService.running) {
                local("Stop sync first, then press Match colors.");
                return;
            }
            startActivity(new Intent(this, CalibrateActivity.class));
        }, false);
        button(r, "Reset colors", v -> {
            settings.calWarm = 0;
            settings.calTint = 0;
            settings.calCyan = 0;
            settings.calMag = 0;
            settings.calYel = 0;
            changed();
            local("Color matching reset to neutral.");
        }, false);

        setContentView(scroll);
        refreshLabels();
        start.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings = Settings.load(this); // the color wizard may have saved new values
        refreshLabels();
        String done = CalibrateActivity.result;
        if (done != null) {
            CalibrateActivity.result = null;
            local(done);
        }
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

    private String addr() {
        return settings.mode == Settings.MODE_WLED ? settings.wledIp : settings.ip;
    }

    /** WLED tests: send logical zone colors spread across the LEDs a few times. */
    private void wledTest(int[] logical, String done) {
        final Settings s = settings;
        final String ip = s.wledIp;
        if (ip == null || ip.isEmpty()) {
            local("Enter the WLED controller's address under Connection first.");
            return;
        }
        new Thread(() -> {
            try (WledClient w = new WledClient(ip)) {
                int[] zones = EdgeSampler.order(logical, s.reverse, s.shift);
                int[] leds = WledProtocol.upsample(zones, s.ledCount, s.sideCounts()[3] > 0);
                for (int i = 0; i < 3; i++) {
                    w.send(leds);
                    Thread.sleep(100);
                }
                ui.post(() -> local(done));
            } catch (Exception e) {
                ui.post(() -> local("WLED test failed: " + e.getMessage()));
            }
        }).start();
    }

    private void testLan() {
        if (settings.mode == Settings.MODE_WLED && !CaptureService.running) {
            int[] red = new int[Math.max(1, settings.zoneCount())];
            java.util.Arrays.fill(red, 0xFF0000);
            wledTest(red, "Sent red to WLED. Whole strip red for 2 seconds = connection works.");
            return;
        }
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

    private static int total(int[] c) {
        return c[0] + c[1] + c[2] + c[3];
    }

    private void testPattern(boolean sides) {
        testPattern(sides, null);
    }

    private void testPattern(boolean sides, String doneMsg) {
        if (settings.mode == Settings.MODE_WLED && !CaptureService.running) {
            int[] counts = settings.sideCounts();
            wledTest(sides ? EdgeSampler.sidePattern(counts) : EdgeSampler.testPattern(total(counts)),
                    sides ? "Sides test sent (shows ~2 s): left red, top green, right blue, bottom white."
                          : "Zone test sent (shows ~2 s): red should start at the bottom of the left side.");
            return;
        }
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
                int[] counts = s.sideCounts();
                int[] logical = sides ? EdgeSampler.sidePattern(counts) : EdgeSampler.testPattern(total(counts));
                int[] colors = EdgeSampler.order(logical, s.reverse, s.shift);
                if (s.mode == Settings.MODE_REALTIME) {
                    c.razer(true);
                    Thread.sleep(120); // let the strip switch into realtime mode
                    for (int i = 0; i < 6; i++) {
                        c.colors(colors, s.gradient);
                        Thread.sleep(100);
                    }
                } else {
                    c.razer(false);
                    Thread.sleep(150);
                    c.segments(colors);
                }
                ui.post(() -> {
                    ipField.setText(ip);
                    local(doneMsg != null ? doneMsg : sides
                            ? "Sides test sent. Each color should cover exactly one side of the TV. Adjust the per-side counts until it does."
                            : "Sent zone test to " + ip + ". Red should start at the bottom of the left side.");
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
        int[] sc = settings.sideCounts();
        String[] sn = {"Left", "Top", "Right", "Bottom"};
        String tag = settings.customSides() ? "" : "*";
        for (int i = 0; i < 4; i++) sideBtns[i].setText(sn[i] + " " + sc[i] + tag);
        if (settings.customSides() && total(sc) != settings.zoneCount()) {
            local("Per-side counts add up to " + total(sc) + " but Segments is " + settings.zoneCount() + ". Make them match.");
        }
        ledBtn.setText("WLED LEDs\n" + settings.ledCount);
        ledBtn.setVisibility(settings.mode == Settings.MODE_WLED ? View.VISIBLE : View.GONE);
        captureBtn.setText("Capture\n" + new String[]{"Small", "Medium", "Large"}[settings.capture]);
        detailBtn.setText("Detail\n" + Settings.DETAIL_NAMES[settings.detail]);
        styleBtn.setText("Style\n" + Settings.STYLE_NAMES[settings.style]);
        modeBtn.setText("Mode\n" + Settings.MODE_NAMES[settings.mode]);
        sidesBtn.setText(settings.fourSides ? "Loop\n4 sides" : "Loop\n3 sides");
        reverseBtn.setText("Reverse\n" + (settings.reverse ? "On" : "Off"));
        shiftBtn.setText("Shift\n" + settings.shift);
        boolean govee = settings.mode == Settings.MODE_SEGMENTS || settings.mode == Settings.MODE_REALTIME;
        segBtn.setText((govee ? "Segments\n" : "Zones\n") + settings.zoneCount());
        brightBtn.setText("Brightness\n" + settings.brightness + "%");
        smoothBtn.setText("Motion\n" + Settings.SMOOTH_NAMES[settings.smoothing]);
        fpsBtn.setText("Speed\n" + Settings.SPEED_NAMES[settings.speedIdx()]);
        gradBtn.setText("Blend\n" + (settings.gradient ? "On" : "Off"));
    }

    private void local(String s) {
        localStatus = s;
        statusView.setText(s);
    }

    private LinearLayout column(LinearLayout parent, float weight) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        parent.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight));
        return c;
    }

    private void spacer(LinearLayout parent, int px) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(px, px));
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        c.setClipToPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(20));
        c.setBackground(bg);
        c.setPadding(dp(20), dp(16), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        parent.addView(c, lp);
        return c;
    }

    private void heading(LinearLayout card, String label) {
        TextView t = text(card, label, 12, MUTED, true);
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(2), 0, 0, dp(6));
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

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(6), 0, dp(6));
        parent.addView(r);
        return r;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(14));
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        return d;
    }

    private GradientDrawable fieldBg() {
        return rounded(BTN, BTN_EDGE, dp(1));
    }

    /** TV button: unmistakable focus (glow fill, white outline, slight zoom). */
    private Button button(LinearLayout parent, String text, View.OnClickListener l, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setOnClickListener(l);
        btn.setPadding(dp(14), dp(12), dp(14), dp(12));
        btn.setMinHeight(dp(52));
        btn.setStateListAnimator(null);

        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT_A, ACCENT_B});
        focused.setCornerRadius(dp(14));
        focused.setStroke(dp(3), Color.WHITE);
        GradientDrawable normal = primary
                ? rounded(0xFF3A1F4F, 0xFF7C4DFF, dp(1))
                : rounded(BTN, BTN_EDGE, dp(1));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{}, normal);
        btn.setBackground(states);
        btn.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, primary ? 0xFFF1D9FF : TEXT}));
        btn.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(120).start();
            v.setElevation(has ? dp(10) : 0);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(10));
        parent.addView(btn, lp);
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
