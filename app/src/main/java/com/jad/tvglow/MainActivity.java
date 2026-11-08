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
    private Button captureBtn, detailBtn, styleBtn, modeBtn, sidesBtn, reverseBtn, shiftBtn, segBtn, brightBtn, fpsBtn, smoothBtn, gradBtn;
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
        button(r, "Live preset", v -> {
            settings.smoothing = 3;  // Instant
            settings.detail = 2;     // 4 zones: every zone updates every tick
            settings.fps = 30;
            settings.capture = 2;    // Large
            changed();
            local("Live preset on: Instant motion, 4 zones, 30/s. Restart sync to apply. If it starts lagging, set Rate to 20.");
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
        ipField.setText(settings.ip);
        ipField.setBackground(fieldBg());
        ipField.setPadding(dp(16), dp(12), dp(16), dp(12));
        conn.addView(ipField, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        r = row(conn);
        button(r, "Save", v -> {
            settings.ip = ipField.getText().toString().trim();
            changed();
            local(settings.ip.isEmpty() ? "Address cleared, will auto-find" : "Saved " + settings.ip);
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
        modeBtn = button(r, "", v -> { settings.mode = (settings.mode + 1) % 3; settings.shift = 0; changed(); }, false);
        r = row(cal);
        sidesBtn = button(r, "", v -> { settings.fourSides = !settings.fourSides; changed(); }, false);
        reverseBtn = button(r, "", v -> { settings.reverse = !settings.reverse; changed(); }, false);
        shiftBtn = button(r, "", v -> { settings.shift = (settings.shift + 1) % Math.max(1, total(settings.sideCounts())); changed(); }, false);
        segBtn = button(r, "", v -> {
            if (settings.mode == Settings.MODE_SEGMENTS) {
                settings.appSegments = Settings.next(Settings.APP_SEGMENT_OPTIONS, settings.appSegments);
            } else {
                settings.segments = Settings.next(Settings.SEGMENT_OPTIONS, settings.segments);
            }
            settings.shift = 0;
            changed();
        }, false);
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
        fpsBtn = button(r, "", v -> { settings.fps = Settings.next(Settings.FPS_OPTIONS, settings.fps); changed(); }, false);
        detailBtn = button(r, "", v -> { settings.detail = (settings.detail + 1) % 3; changed(); }, false);
        captureBtn = button(r, "", v -> {
            settings.capture = (settings.capture + 1) % 3;
            changed();
            if (CaptureService.running) local("Capture size applies next time you press Start sync.");
        }, false);
        gradBtn = button(r, "", v -> { settings.gradient = !settings.gradient; changed(); }, false);

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

    private static int total(int[] c) {
        return c[0] + c[1] + c[2] + c[3];
    }

    private void testPattern(boolean sides) {
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
                    for (int i = 0; i < 3; i++) {
                        c.colors(colors, false);
                        Thread.sleep(150);
                    }
                } else {
                    c.razer(false);
                    Thread.sleep(150);
                    c.segments(colors);
                }
                ui.post(() -> {
                    ipField.setText(ip);
                    local(sides
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
        captureBtn.setText("Capture\n" + new String[]{"Small", "Medium", "Large"}[settings.capture]);
        detailBtn.setText("Detail\n" + Settings.DETAIL_NAMES[settings.detail]);
        styleBtn.setText("Style\n" + Settings.STYLE_NAMES[settings.style]);
        modeBtn.setText("Mode\n" + Settings.MODE_NAMES[settings.mode]);
        sidesBtn.setText(settings.fourSides ? "Loop\n4 sides" : "Loop\n3 sides");
        reverseBtn.setText("Reverse\n" + (settings.reverse ? "On" : "Off"));
        shiftBtn.setText("Shift\n" + settings.shift);
        segBtn.setText((settings.mode == Settings.MODE_SEGMENTS ? "Segments\n" : "Zones\n") + settings.zoneCount());
        brightBtn.setText("Brightness\n" + settings.brightness + "%");
        smoothBtn.setText("Motion\n" + Settings.SMOOTH_NAMES[settings.smoothing]);
        fpsBtn.setText("Rate\n" + settings.fps + "/s");
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
