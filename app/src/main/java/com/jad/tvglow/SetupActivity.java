package com.jad.tvglow;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-run setup, entirely with the remote:
 *  1. find the lights on the network
 *  2. segment count (known models skip this)
 *  3. pick the fastest mode that works, by asking "do you see red fading to blue?"
 *  4. placement from two plain questions: where the strip starts, which way it runs
 *  5. confirm with a side-color check (rotate if needed), pick a look, start syncing
 */
public class SetupActivity extends Activity {
    /** Set when setup ends with "Start syncing"; the main screen then starts sync. */
    static volatile boolean startRequested;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView title, body, note;

    private boolean wledPath;
    private String ip = "", sku = "", wledIp = "";
    private int ledCount = 120;
    private int segments = 12;
    private int mode = Settings.MODE_REALTIME;
    private boolean fourSides = true;
    private int startPos = Placement.BOTTOM_LEFT;
    private int reverse, shift;

    private volatile GoveeClient client;
    private volatile WledClient wled;
    private volatile int[] showing; // strip-order colors on the lights right now
    private volatile boolean alive = true;
    private Runnable chase;

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            int[] s = showing;
            if (s != null) push(s);
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startRequested = false;
        Settings s = Settings.load(this);
        wledIp = s.wledIp;
        ledCount = s.ledCount;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setPadding(Ui.dp(this, 72), Ui.dp(this, 40), Ui.dp(this, 72), Ui.dp(this, 32));
        scroll.addView(root);

        TextView label = Ui.text(root, "TV GLOW SETUP", 13, Ui.MUTED, true);
        label.setLetterSpacing(0.18f);
        title = Ui.text(root, "", 32, Ui.TEXT, true);
        title.setPadding(0, Ui.dp(this, 6), 0, 0);
        body = Ui.text(root, "", 19, Ui.TEXT, false);
        body.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 18));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content);
        note = Ui.text(root, "", 15, Ui.MUTED, false);
        note.setPadding(0, Ui.dp(this, 16), 0, 0);

        setContentView(scroll);
        showWelcome();
        ui.postDelayed(keepAlive, 1000);
    }

    // ---------- page helpers ----------

    private void page(String t, String b) {
        stopChase();
        title.setText(t);
        body.setText(b);
        note.setText("");
        content.removeAllViews();
    }

    private LinearLayout row() {
        return Ui.row(content);
    }

    private Button btn(LinearLayout r, String label, Runnable action, boolean primary) {
        return Ui.button(r, label, v -> action.run(), primary);
    }

    private int[] counts() {
        return EdgeSampler.autoSideCounts(segments, fourSides);
    }

    private int[] spot(int k) {
        int[] z = new int[segments];
        z[Math.floorMod(k, segments)] = 0xFFFFFF;
        return z;
    }

    private static int knownSegments(String model) {
        if ("H6167".equalsIgnoreCase(model)) return 12; // verified on a real H6167
        return 0;
    }

    // ---------- pages ----------

    private void showWelcome() {
        page("Let's set up your lights",
                "Takes about a minute, all with the remote.\n\n"
                        + "First, on your phone: open the Govee Home app, tap your light, tap the gear "
                        + "icon (top right) and turn on LAN Control. The TV and the lights need to be "
                        + "on the same Wi-Fi.");
        LinearLayout r = row();
        Button go = btn(r, "Find my lights", this::search, true);
        btn(r, "I use WLED", this::showWled, false);
        btn(r, "Not now", this::finish, false);
        go.requestFocus();
    }

    private void search() {
        wledPath = false;
        page("Looking for your lights...", "Searching your network. This takes a few seconds.");
        net.execute(() -> {
            List<GoveeClient.Found> list = GoveeClient.discover(this, 3500);
            ui.post(() -> {
                if (!alive) return;
                if (list.isEmpty()) showNoLights();
                else showDevices(list);
            });
        });
    }

    private void showNoLights() {
        page("No lights found",
                "Check these, then search again:\n\n"
                        + "•  LAN Control is on: Govee Home app → your light → gear icon → LAN Control.\n"
                        + "•  The lights are switched on.\n"
                        + "•  The TV and the lights are on the same Wi-Fi (not a guest network).");
        LinearLayout r = row();
        Button again = btn(r, "Search again", this::search, true);
        btn(r, "I use WLED", this::showWled, false);
        btn(r, "Exit", this::finish, false);
        again.requestFocus();
    }

    private void showDevices(List<GoveeClient.Found> list) {
        boolean one = list.size() == 1;
        page(one ? "Found your lights" : "Which lights are behind the TV?",
                one ? "Govee " + list.get(0).sku + " on your network." : "Pick the lights behind the TV.");
        LinearLayout r = row();
        Button first = null;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0 && i % 3 == 0) r = row();
            GoveeClient.Found f = list.get(i);
            Button b = btn(r, one ? "Continue" : f.sku + "  ·  " + f.ip, () -> choose(f), true);
            if (first == null) first = b;
        }
        if (first != null) first.requestFocus();
    }

    private void choose(GoveeClient.Found f) {
        ip = f.ip;
        sku = f.sku;
        net.execute(() -> {
            try {
                GoveeClient old = client;
                GoveeClient c = new GoveeClient(ip);
                c.turnOn();
                client = c;
                if (old != null) old.close();
            } catch (Exception e) {
                ui.post(() -> note.setText("Couldn't reach the lights: " + e.getMessage()));
            }
        });
        int known = knownSegments(sku);
        if (known > 0) {
            segments = known;
            page("Your " + sku + " has " + known + " segments",
                    "Segments are the sections of the strip that can each show their own color.");
            LinearLayout r = row();
            Button ok = btn(r, "Continue", this::showTurboTest, true);
            btn(r, "That's not right", this::showSegmentPicker, false);
            ok.requestFocus();
        } else {
            showSegmentPicker();
        }
    }

    private void showSegmentPicker() {
        page("How many segments?",
                "In the Govee Home app, open your light's color page and choose the Segment "
                        + "(or Subsection) tab. Count the sections shown and pick that number.");
        int[] options = {6, 8, 10, 12, 14, 15, 16};
        LinearLayout r = row();
        Button first = null;
        for (int i = 0; i < options.length; i++) {
            if (i == 4) r = row();
            final int n = options[i];
            Button b = btn(r, String.valueOf(n), () -> {
                segments = n;
                showTurboTest();
            }, n == 12);
            if (first == null) first = b;
        }
        btn(r, "Not sure", () -> {
            segments = 12;
            showTurboTest();
        }, false);
        if (first != null) first.requestFocus();
    }

    private void showTurboTest() {
        mode = Settings.MODE_REALTIME;
        page("Do you see red fading to blue?",
                "Your lights should now fade from red to blue along the strip. "
                        + "This checks the fastest sync mode.");
        LinearLayout r = row();
        Button yes = btn(r, "Yes, I see it", this::showSides, true);
        btn(r, "No, nothing changed", this::showSegmentsTest, false);
        yes.requestFocus();
        net.execute(() -> {
            try {
                GoveeClient c = client;
                if (c != null) {
                    c.razer(true);
                    Thread.sleep(150);
                }
            } catch (Exception ignored) {
            }
        });
        display(EdgeSampler.testPattern(segments));
    }

    private void showSegmentsTest() {
        mode = Settings.MODE_SEGMENTS;
        page("How about now?", "Trying the standard mode. Do you see red fading to blue now?");
        LinearLayout r = row();
        Button yes = btn(r, "Yes, I see it", this::showSides, true);
        btn(r, "Still nothing", this::showSingleTest, false);
        yes.requestFocus();
        net.execute(() -> {
            try {
                GoveeClient c = client;
                if (c != null) {
                    c.razer(false);
                    Thread.sleep(150);
                }
            } catch (Exception ignored) {
            }
        });
        display(EdgeSampler.testPattern(Math.min(16, segments)));
    }

    private void showSingleTest() {
        mode = Settings.MODE_SINGLE;
        page("Is the whole strip red?", "Last check: this mode colors the whole strip at once.");
        LinearLayout r = row();
        Button yes = btn(r, "Yes, it's red", () -> showLook(), true);
        btn(r, "No", this::showNoReach, false);
        yes.requestFocus();
        display(new int[]{0xFF0000});
    }

    private void showNoReach() {
        page("The TV can't reach your lights",
                "•  Check LAN Control is on (Govee Home → your light → gear icon).\n"
                        + "•  Make sure the TV and the lights are on the same Wi-Fi.\n"
                        + "•  Unplug the lights for 10 seconds, plug them back in, then search again.");
        LinearLayout r = row();
        Button again = btn(r, "Search again", this::search, true);
        btn(r, "Exit", this::finish, false);
        again.requestFocus();
    }

    private void showSides() {
        page("Where does the strip go?", "Does the strip also run along the bottom of the TV?");
        LinearLayout r = row();
        Button four = btn(r, "Yes, all 4 sides", () -> {
            fourSides = true;
            showStartPos();
        }, true);
        btn(r, "No, left, top and right only", () -> {
            fourSides = false;
            showStartPos();
        }, false);
        four.requestFocus();
    }

    private void showStartPos() {
        page("Find the start of the strip",
                "One spot on your lights is now lit. Where is it? Pick the closest spot around the TV.");
        int[][] grid = {
                {Placement.TOP_LEFT, Placement.TOP, Placement.TOP_RIGHT},
                {Placement.LEFT, -1, Placement.RIGHT},
                {Placement.BOTTOM_LEFT, Placement.BOTTOM, Placement.BOTTOM_RIGHT}};
        String[][] names = {{"Top-left", "Top", "Top-right"}, {"Left", "TV", "Right"},
                {"Bottom-left", "Bottom", "Bottom-right"}};
        Button first = null;
        for (int y = 0; y < 3; y++) {
            LinearLayout r = row();
            for (int x = 0; x < 3; x++) {
                final int pos = grid[y][x];
                if (pos < 0) {
                    Ui.slot(r, names[y][x]);
                    continue;
                }
                Button b = btn(r, names[y][x], () -> {
                    startPos = pos;
                    showDirection();
                }, false);
                if (first == null) first = b;
            }
        }
        if (first != null) first.requestFocus();
        display(spot(0));
    }

    private void showDirection() {
        String[] labels = Placement.directionLabels(startPos);
        page("Which way does it run?", "The light is now stepping along the strip. Which way is it moving?");
        LinearLayout r = row();
        Button a = btn(r, labels[0], () -> placed(true), false);
        btn(r, labels[1], () -> placed(false), false);
        a.requestFocus();
        startChase();
    }

    private void placed(boolean clockwise) {
        int[] rs = Placement.solve(startPos, clockwise, counts());
        reverse = rs[0];
        shift = rs[1];
        showConfirm();
    }

    private void showConfirm() {
        page("Check the colors",
                "Left side should be red, top green, right blue" + (fourSides ? ", bottom white." : ".")
                        + "\nIf the colors are shifted around the TV, press Rotate until each side is right.");
        LinearLayout r = row();
        Button ok = btn(r, "Looks right", this::showLook, true);
        btn(r, "◀  Rotate", () -> rotate(-1), false);
        btn(r, "Rotate  ▶", () -> rotate(1), false);
        btn(r, "Flip direction", this::flip, false);
        ok.requestFocus();
        showSidesPattern();
    }

    private void showSidesPattern() {
        display(EdgeSampler.order(EdgeSampler.sidePattern(counts()), reverse == 1, shift));
    }

    private void rotate(int d) {
        shift = Math.floorMod(shift + d, segments);
        showSidesPattern();
    }

    /** Reverse the direction while keeping the same zone on the first segment. */
    private void flip() {
        int z = reverse == 0 ? Math.floorMod(-shift, segments) : Math.floorMod(shift - 1, segments);
        reverse ^= 1;
        shift = reverse == 0 ? Math.floorMod(-z, segments) : Math.floorMod(z + 1, segments);
        showSidesPattern();
    }

    private void showLook() {
        page("Pick a look", "You can change this anytime from the main screen.");
        LinearLayout r = row();
        Button acc = btn(r, "Accurate\ntrue to the screen", () -> finishSetup(EdgeSampler.STYLE_ACCURATE), true);
        btn(r, "Cozy\ndim and warm", () -> finishSetup(EdgeSampler.STYLE_COZY), false);
        btn(r, "Vivid\nbright and punchy", () -> finishSetup(EdgeSampler.STYLE_VIVID), false);
        acc.requestFocus();
    }

    private void finishSetup(int style) {
        Settings s = Settings.load(this);
        if (wledPath) {
            s.mode = Settings.MODE_WLED;
            s.wledIp = wledIp;
            s.ledCount = ledCount;
            s.segments = segments;
        } else {
            s.mode = mode;
            s.ip = ip;
            s.sku = sku;
            if (mode != Settings.MODE_SINGLE) s.appSegments = segments;
        }
        s.fourSides = fourSides;
        s.sides = new int[]{0, 0, 0, 0};
        s.reverse = reverse == 1;
        s.shift = shift;
        s.style = style;
        s.smoothing = 3; // Instant: lowest delay
        s.detail = 0;
        s.speed = 1;
        s.capture = 2;
        s.gradient = false;
        s.setupDone = true;
        s.save(this);
        showDone();
    }

    private void showDone() {
        showing = null;
        page("All set",
                "Press Start syncing, then allow screen capture when the TV asks. After that, play "
                        + "anything: YouTube, Plex, games from the Play Store.\n\n"
                        + "Protected apps like Netflix and HDMI inputs (consoles, cable boxes) can't be captured.");
        LinearLayout r = row();
        Button go = btn(r, "Start syncing", () -> {
            startRequested = true;
            finish();
        }, true);
        btn(r, "Back to TV Glow", this::finish, false);
        go.requestFocus();
    }

    // ---------- WLED path ----------

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setTextColor(Ui.TEXT);
        e.setHint(hint);
        e.setHintTextColor(Ui.MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setText(value);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BTN);
        bg.setCornerRadius(Ui.dp(this, 14));
        bg.setStroke(Ui.dp(this, 1), Ui.BTN_EDGE);
        e.setBackground(bg);
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        content.addView(e, lp);
        return e;
    }

    private void showWled() {
        wledPath = true;
        mode = Settings.MODE_WLED;
        segments = 20;
        page("Connect to WLED", "Enter your WLED controller's address and how many LEDs the strip has.");
        EditText addr = field("Address, e.g. 192.168.1.60", wledIp, InputType.TYPE_CLASS_TEXT);
        EditText leds = field("Number of LEDs, e.g. 120", String.valueOf(ledCount), InputType.TYPE_CLASS_NUMBER);
        LinearLayout r = row();
        Button test = btn(r, "Test", () -> {
            wledIp = addr.getText().toString().trim();
            try {
                ledCount = Math.max(1, Integer.parseInt(leds.getText().toString().trim()));
            } catch (NumberFormatException e) {
                ledCount = 120;
            }
            if (wledIp.isEmpty()) {
                note.setText("Enter the address first.");
                return;
            }
            net.execute(() -> {
                try {
                    WledClient old = wled;
                    wled = new WledClient(wledIp);
                    if (old != null) old.close();
                } catch (Exception e) {
                    ui.post(() -> note.setText("Couldn't use that address: " + e.getMessage()));
                }
            });
            showWledTest();
        }, true);
        btn(r, "Back", this::showWelcome, false);
        addr.requestFocus();
    }

    private void showWledTest() {
        page("Do you see red fading to blue?", "Your WLED strip should now fade from red to blue.");
        LinearLayout r = row();
        Button yes = btn(r, "Yes, I see it", this::showSides, true);
        btn(r, "No", () -> {
            showWled();
            note.setText("No luck. Check the address in the WLED app and that WLED is on the same Wi-Fi.");
        }, false);
        yes.requestFocus();
        display(EdgeSampler.testPattern(segments));
    }

    // ---------- sending ----------

    private void display(int[] stripColors) {
        showing = stripColors;
        push(stripColors);
    }

    private void startChase() {
        final int[] k = {0};
        final int steps = Math.min(3, segments);
        chase = new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                display(spot(k[0] % steps));
                k[0]++;
                ui.postDelayed(this, 550);
            }
        };
        ui.post(chase);
    }

    private void stopChase() {
        if (chase != null) ui.removeCallbacks(chase);
        chase = null;
    }

    private static List<SegmentScheduler.Cmd> toCmds(int[] colors) {
        Map<Integer, Integer> masks = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(16, colors.length); i++) masks.merge(colors[i], 1 << i, (a, b) -> a | b);
        List<SegmentScheduler.Cmd> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : masks.entrySet()) out.add(new SegmentScheduler.Cmd(e.getValue(), e.getKey()));
        return out;
    }

    private void push(int[] colors) {
        final int m = mode;
        final boolean toWled = wledPath;
        final int leds = ledCount;
        final boolean loop = fourSides;
        try {
            net.execute(() -> {
                try {
                    if (toWled) {
                        WledClient w = wled;
                        if (w != null) w.send(WledProtocol.upsample(colors, Math.max(1, leds), loop));
                        return;
                    }
                    GoveeClient c = client;
                    if (c == null) return;
                    if (m == Settings.MODE_REALTIME) c.colors(colors, false);
                    else if (m == Settings.MODE_SEGMENTS) c.segmentCmds(toCmds(colors));
                    else c.color(colors.length > 0 ? colors[0] : 0);
                } catch (Exception ignored) {
                    // the keep-alive resends
                }
            });
        } catch (Exception ignored) {
            // executor already shut down
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        stopChase();
        ui.removeCallbacks(keepAlive);
        final GoveeClient c = client;
        final WledClient w = wled;
        final boolean realtime = mode == Settings.MODE_REALTIME;
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
}
