package com.jad.tvglow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Low-latency pipeline: mirror the screen into a small buffer, sample the edges, and push
 * colors to the strip straight from the capture thread the moment each frame lands.
 *
 * Latency work in here:
 *  - frames are processed and sent on arrival (no queue, no hop to another thread)
 *  - the capture thread runs at display priority
 *  - Wi-Fi power saving is switched off while syncing (low-latency Wi-Fi lock)
 *  - App segments mode uses SegmentScheduler (whole-strip repaints first, detail after)
 *  - Turbo realtime sends all segments in one packet per frame
 *  - letterbox detection is stabilized so dark scenes don't make zones jump
 */
public class CaptureService extends Service {
    static final String ACTION_STOP = "com.jad.tvglow.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "tvglow";

    static volatile boolean running = false;
    static volatile String status = "Idle";

    private int W = 96, H = 54;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler handler;
    private final ExecutorService net = Executors.newSingleThreadExecutor(); // connect + cleanup only
    private volatile GoveeClient client;
    private volatile WledClient wled;
    private volatile int activeMode = -1;
    private volatile StripMonitor monitor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long connectedAt, lastProbe, lastScan, scanCheckAt;
    private boolean stripWarned, darkWarned;
    private volatile String stripNote = "";
    private WifiManager.WifiLock perfLock, lowLatencyLock;

    private Settings settings;
    private int[] pixels, rowBuf;
    private final StableRect stableRect = new StableRect();
    private int[] latestTarget;
    private float[] smoothed;
    private SegmentScheduler scheduler;
    private LedCorrection correction = LedCorrection.NONE;
    private String correctionKey = "";
    private int[] lastSentColors;
    private long lastSettingsLoad, lastKeepAlive, darkSince, lastSend, lastProcess;
    private int lastAvg;

    // Stats for the status line
    private long statsSince;
    private int frameCount, sendCount, cmdCount, lagN;
    private double lagSum;
    private final float[] fpsHistory = new float[10];
    private int fpsIdx;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            try {
                tick(now, false);
                checkStrip(now);
            } catch (Exception ignored) {
            }
            if (handler != null && running) handler.postDelayed(this, tickMs());
        }
    };

    private boolean connected() {
        return client != null || wled != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (projection != null) return START_NOT_STICKY; // already running

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            status = "Missing capture permission";
            stopSelf();
            return START_NOT_STICKY;
        }

        settings = Settings.load(this);
        updateCorrection();
        running = true;
        status = "Connecting to strip...";
        acquireWifiLocks();
        connectStrip();

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            status = "Screen capture was refused";
            stopSelf();
            return START_NOT_STICKY;
        }

        captureThread = new HandlerThread("tvglow-capture");
        captureThread.start();
        handler = new Handler(captureThread.getLooper());
        handler.post(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Throwable ignored) {
                // keep default priority if the system refuses
            }
        });

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, handler);

        int[][] sizes = {{96, 54}, {192, 108}, {384, 216}};
        int[] sz = sizes[Math.max(0, Math.min(2, settings.capture))];
        W = sz[0];
        H = sz[1];
        pixels = new int[W * H];
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, handler);
        display = projection.createVirtualDisplay("tvglow", W, H, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        handler.postDelayed(ticker, 50);
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "TV Glow", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("TV Glow")
                .setContentText("Syncing lights to the screen")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    /** Keeps the TV's Wi-Fi radio out of power save so packets leave immediately. */
    private void acquireWifiLocks() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            perfLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvglow:perf");
            perfLock.setReferenceCounted(false);
            perfLock.acquire();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tvglow:lowlatency");
                lowLatencyLock.setReferenceCounted(false);
                lowLatencyLock.acquire();
            }
        } catch (Throwable ignored) {
            // locks are a bonus; sync works without them
        }
    }

    private void releaseWifiLocks() {
        try {
            if (perfLock != null && perfLock.isHeld()) perfLock.release();
            if (lowLatencyLock != null && lowLatencyLock.isHeld()) lowLatencyLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void connectStrip() {
        final int mode = settings.mode;
        if (mode == Settings.MODE_WLED) {
            net.execute(() -> {
                String ip = settings.wledIp;
                if (ip == null || ip.isEmpty()) {
                    status = "WLED mode needs the WLED controller's address. Enter it under Connection.";
                    stopSelf();
                    return;
                }
                try {
                    wled = new WledClient(ip);
                    activeMode = mode;
                    status = "Syncing to WLED at " + ip;
                    toast("TV Glow is syncing");
                } catch (Exception e) {
                    status = "Could not reach WLED at " + ip + ": " + e.getMessage();
                    stopSelf();
                }
            });
            return;
        }
        net.execute(() -> {
            String ip = GoveeClient.resolveIp(this, settings);
            if (ip == null) {
                status = "No strip found. Is LAN Control on, and the TV on the same network?";
                stopSelf();
                return;
            }
            try {
                GoveeClient c = new GoveeClient(ip);
                c.turnOn();
                if (mode == Settings.MODE_REALTIME) c.razer(true); else c.razer(false);
                lastKeepAlive = SystemClock.uptimeMillis();
                activeMode = mode;
                try {
                    StripMonitor m = new StripMonitor(ip);
                    m.sku = settings.sku;
                    m.probe();
                    monitor = m;
                } catch (Exception e) {
                    monitor = null; // health checks are a bonus; sync still works
                }
                connectedAt = SystemClock.uptimeMillis();
                client = c;
                status = "Syncing to strip at " + ip;
                toast("TV Glow is syncing");
            } catch (Exception e) {
                status = "Could not reach strip at " + ip + ": " + e.getMessage();
                stopSelf();
            }
        });
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            reloadSettings(now);
            long lagNs = System.nanoTime() - img.getTimestamp();
            if (lagNs > 0 && lagNs < 1_000_000_000L) {
                lagSum += lagNs / 1e6;
                lagN++;
            }
            frameCount++;
            updateStats(now);
            if (!connected()) return;
            Image.Plane plane = img.getPlanes()[0];
            rowBuf = FrameCopier.copy(plane.getBuffer(), plane.getPixelStride(), plane.getRowStride(),
                    Math.min(img.getWidth(), W), Math.min(img.getHeight(), H), pixels, W, rowBuf);
            latestTarget = computeTarget(now);
        } finally {
            img.close(); // hand the buffer back before sending so the next frame is never blocked
        }
        tick(SystemClock.uptimeMillis(), true);
    }

    private void reloadSettings(long now) {
        if (now - lastSettingsLoad < 1000) return;
        lastSettingsLoad = now;
        Settings fresh = Settings.load(this);
        fresh.ip = settings.ip; // keep the resolved address
        settings = fresh;
        updateCorrection();
        if (activeMode != -1 && fresh.mode != activeMode) switchMode(fresh.mode);
    }

    private void updateCorrection() {
        String key = settings.correctionKey();
        if (!key.equals(correctionKey)) {
            correction = settings.correction();
            correctionKey = key;
        }
    }

    /** Mode changed in the app while syncing: switch the strip over without a restart. */
    private void switchMode(int mode) {
        GoveeClient c = client;
        if (c != null && mode != Settings.MODE_WLED) {
            try {
                if (mode == Settings.MODE_REALTIME) {
                    c.razer(true);
                    lastKeepAlive = SystemClock.uptimeMillis();
                } else if (activeMode == Settings.MODE_REALTIME) {
                    c.razer(false);
                }
            } catch (Exception ignored) {
            }
        }
        if ((mode == Settings.MODE_WLED) != (activeMode == Settings.MODE_WLED)) {
            status = "Restart sync to switch between WLED and the Govee strip.";
        }
        scheduler = null;
        lastSentColors = null;
        activeMode = mode;
    }

    private void updateStats(long now) {
        if (statsSince == 0) statsSince = now;
        if (now - statsSince < 3000) return;
        float secs = (now - statsSince) / 1000f;
        fpsHistory[fpsIdx % fpsHistory.length] = frameCount / secs;
        fpsIdx++;
        float avg = 0;
        int cnt = Math.min(fpsIdx, fpsHistory.length);
        for (int i = 0; i < cnt; i++) avg += fpsHistory[i];
        avg /= Math.max(1, cnt);
        if (darkSince == 0 && connected()) {
            Settings s = settings;
            String strip = s.mode == Settings.MODE_SEGMENTS
                    ? String.format(Locale.US, "%.0f cmds/s", cmdCount / secs)
                    : String.format(Locale.US, "%.0f updates/s", sendCount / secs);
            String lag = lagN > 0 ? String.format(Locale.US, " · capture lag %.0f ms", lagSum / lagN) : "";
            String health = monitor == null ? "" : stripNote.isEmpty() ? " · strip answering ✓" : stripNote;
            status = String.format(Locale.US, "%s · screen %.0f fps (30s avg %.1f)%s · strip %s%s",
                    Settings.MODE_NAMES[Math.max(0, Math.min(3, s.mode))], frameCount / secs, avg, lag, strip, health);
        }
        frameCount = 0;
        sendCount = 0;
        cmdCount = 0;
        lagSum = 0;
        lagN = 0;
        statsSince = now;
    }

    /** New screen frame: sample the edges into styled zone colors. */
    private int[] computeTarget(long now) {
        Settings s = settings;
        int[] sideCounts = s.sideCounts();
        int n = sideCounts[0] + sideCounts[1] + sideCounts[2] + sideCounts[3];
        if (n == 0) return null;

        boolean dark = EdgeSampler.allDark(pixels);
        if (dark) {
            if (darkSince == 0) darkSince = now;
            if (now - darkSince > 3000) {
                status = "Screen reads black: protected apps (Netflix etc.) and HDMI inputs (PS5) can't be captured.";
                if (!darkWarned) {
                    darkWarned = true;
                    toast("TV Glow can't see this picture. Protected apps and HDMI inputs show up black.");
                }
            }
        } else if (darkSince != 0) {
            darkSince = 0;
            darkWarned = false;
        }

        int[] rect = stableRect.update(EdgeSampler.contentRect(pixels, W, H), W, H, dark, now);
        int[] target = EdgeSampler.sampleSides(pixels, W, H, sideCounts, 0.14f, rect);
        float blend = s.style == EdgeSampler.STYLE_ACCURATE ? 0.08f : 0.18f; // Accurate keeps zones distinct
        target = EdgeSampler.blendNeighbors(target, sideCounts[3] > 0, blend);
        float bright = s.brightness / 100f;
        for (int i = 0; i < n; i++) target[i] = EdgeSampler.style(target[i], s.style, bright);
        return target;
    }

    /**
     * Strip health: probe every 3 s. If nothing comes back for 7 s, scan the network; if the
     * strip answers from a new address, reconnect there. After 12 s of silence, say so.
     */
    private void checkStrip(long now) {
        StripMonitor m = monitor;
        if (m == null || client == null) return;
        if (now - lastProbe >= 3000) {
            lastProbe = now;
            m.probe();
        }
        long silentFor = now - Math.max(m.lastHeard, connectedAt);
        if (silentFor < 7000) {
            stripNote = "";
            stripWarned = false;
            return;
        }
        if (scanCheckAt == 0 && now - lastScan >= 8000) {
            lastScan = now;
            scanCheckAt = now + 2500;
            m.scan();
            return;
        }
        if (scanCheckAt != 0 && now >= scanCheckAt) {
            scanCheckAt = 0;
            java.util.Map<String, String> found = m.found();
            if (found.containsKey(m.ip())) {
                m.lastHeard = now; // it's there; it just doesn't answer status requests
                return;
            }
            String moved = StripMonitor.pick(found, m.sku);
            if (moved != null) {
                reconnect(moved, found.get(moved));
                return;
            }
        }
        if (silentFor > 12000) {
            stripNote = " · strip not answering";
            if (!stripWarned) {
                stripWarned = true;
                toast("TV Glow: the strip isn't answering. Check it's powered and LAN Control is on in the Govee app.");
            }
        }
    }

    private void reconnect(String newIp, String sku) {
        try {
            GoveeClient nc = new GoveeClient(newIp);
            nc.turnOn();
            if (settings.mode == Settings.MODE_REALTIME) nc.razer(true);
            GoveeClient old = client;
            client = nc;
            if (old != null) old.close();
            StripMonitor m = monitor;
            if (m != null) {
                m.setIp(newIp);
                m.lastHeard = SystemClock.uptimeMillis();
                if (sku != null && !"?".equals(sku)) m.sku = sku;
            }
            lastKeepAlive = SystemClock.uptimeMillis();
            scheduler = null;
            lastSentColors = null;
            settings.ip = newIp;
            Settings saved = Settings.load(this);
            saved.ip = newIp;
            if (sku != null && !"?".equals(sku)) saved.sku = sku;
            saved.save(this);
            stripNote = "";
            toast("TV Glow: the strip moved to a new address. Reconnected.");
        } catch (Exception e) {
            stripNote = " · reconnect failed";
        }
    }

    private void toast(String msg) {
        main.post(() -> {
            try {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private long tickMs() {
        Settings s = settings;
        if (s == null) return 33;
        switch (s.mode) {
            case Settings.MODE_SINGLE:
                return 100;
            case Settings.MODE_SEGMENTS:
                return 33;
            case Settings.MODE_WLED:
                return 1000 / Settings.WLED_FPS[s.speedIdx()];
            default:
                return 1000 / Settings.RT_FPS[s.speedIdx()];
        }
    }

    /**
     * Runs on every fresh frame and on a timer. The timer keeps smoothing glides moving and
     * lets the segment scheduler keep refining detail between frames.
     */
    private void tick(long now, boolean fresh) {
        int[] target = latestTarget;
        Settings s = settings;
        if (target == null || s == null) return;
        int mode = s.mode;
        GoveeClient gc = client;
        WledClient wc = wled;
        if (mode == Settings.MODE_WLED ? wc == null : gc == null) return;
        int n = target.length;

        // Adaptive temporal smoothing: small drifts glide, big changes snap. Instant = none.
        float dt = lastProcess == 0 ? 33f : Math.min(200f, now - lastProcess);
        lastProcess = now;
        if (smoothed == null || smoothed.length != n * 3) {
            smoothed = new float[n * 3];
            for (int i = 0; i < n; i++) {
                smoothed[i * 3] = (target[i] >> 16) & 0xFF;
                smoothed[i * 3 + 1] = (target[i] >> 8) & 0xFF;
                smoothed[i * 3 + 2] = target[i] & 0xFF;
            }
        }
        float base = Settings.SMOOTH_ALPHA[Math.max(0, Math.min(3, s.smoothing))];
        int avgNow = EdgeSampler.average(target);
        boolean sceneCut = EdgeSampler.colorDistance(avgNow, lastAvg) > 110;
        lastAvg = avgNow;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int t = target[i];
            float dr = ((t >> 16) & 0xFF) - smoothed[i * 3];
            float dg = ((t >> 8) & 0xFF) - smoothed[i * 3 + 1];
            float db = (t & 0xFF) - smoothed[i * 3 + 2];
            float dist = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            float a = sceneCut ? 1f : base + (1f - base) * Math.min(1f, dist / 90f);
            a = 1f - (float) Math.pow(1f - a, dt / 33f); // frame-rate independent
            smoothed[i * 3] += a * dr;
            smoothed[i * 3 + 1] += a * dg;
            smoothed[i * 3 + 2] += a * db;
            out[i] = Math.round(smoothed[i * 3]) << 16
                    | Math.round(smoothed[i * 3 + 1]) << 8
                    | Math.round(smoothed[i * 3 + 2]);
        }
        int[] ordered = correction.apply(EdgeSampler.order(out, s.reverse, s.shift));

        try {
            if (mode == Settings.MODE_SEGMENTS) {
                sendSegments(gc, ordered, s, now);
            } else if (mode == Settings.MODE_SINGLE) {
                if (now - lastSend >= 90) {
                    gc.color(correction.apply(EdgeSampler.average(out)));
                    markSent(now, 1);
                }
            } else {
                sendFrame(gc, wc, ordered, s, now, fresh, mode == Settings.MODE_WLED);
            }
        } catch (Exception e) {
            if (running) status = "Send failed: " + e.getMessage();
        }
    }

    /** Turbo realtime and WLED: the whole strip in one packet. Fresh frames go out first. */
    private void sendFrame(GoveeClient gc, WledClient wc, int[] ordered, Settings s, long now,
                           boolean fresh, boolean toWled) throws Exception {
        int rate = toWled ? Settings.WLED_FPS[s.speedIdx()] : Settings.RT_FPS[s.speedIdx()];
        float interval = 1000f / rate;
        long gap = now - lastSend;
        if (gap < (fresh ? interval * 0.5f : interval)) return;
        boolean keepAlive = !toWled && now - lastKeepAlive > 10000;
        if (Arrays.equals(ordered, lastSentColors) && gap < 500 && !keepAlive) return; // nothing new
        if (toWled) {
            wc.send(WledProtocol.upsample(ordered, Math.max(1, s.ledCount), s.sideCounts()[3] > 0));
        } else {
            if (keepAlive) {
                gc.razer(true);
                lastKeepAlive = now;
            }
            gc.colors(ordered, s.gradient);
        }
        lastSentColors = ordered;
        markSent(now, 1);
    }

    /** App segments: let the scheduler spend the command budget where it matters most. */
    private void sendSegments(GoveeClient gc, int[] ordered, Settings s, long now) throws Exception {
        int n = Math.min(16, ordered.length);
        int g = Settings.DETAIL_GROUP[Math.max(0, Math.min(2, s.detail))];
        int[] tgt = new int[n];
        for (int k = 0; k < n; k += g) {
            int b = Math.min(n, k + g);
            int mask = 0;
            for (int i = k; i < b; i++) mask |= 1 << i;
            int c = g == 1 ? ordered[k] : ColorMath.mean(ordered, mask);
            for (int i = k; i < b; i++) tgt[i] = c;
        }
        boolean circular = s.sideCounts()[3] > 0;
        if (scheduler == null || scheduler.size() != n || scheduler.circular() != circular) {
            scheduler = new SegmentScheduler(n, circular);
        }
        double perSec = Settings.SEG_CMDS[s.speedIdx()];
        List<SegmentScheduler.Cmd> cmds = scheduler.plan(tgt, now, perSec, Math.max(3, perSec * 0.08), 12);
        if (cmds.isEmpty()) return;
        gc.segmentCmds(cmds);
        markSent(now, cmds.size());
    }

    private void markSent(long now, int cmds) {
        lastSend = now;
        sendCount++;
        cmdCount += cmds;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (!status.startsWith("No strip") && !status.startsWith("Could not")
                && !status.startsWith("Screen capture") && !status.startsWith("Missing")
                && !status.startsWith("WLED mode needs")) {
            status = "Stopped";
        }
        if (handler != null) handler.removeCallbacks(ticker);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
        releaseWifiLocks();
        final GoveeClient c = client;
        final WledClient w = wled;
        final StripMonitor m = monitor;
        client = null;
        wled = null;
        monitor = null;
        net.execute(() -> {
            if (m != null) m.close();
            if (w != null) {
                try {
                    w.release();
                } catch (Exception ignored) {
                }
                w.close();
            }
            if (c != null) {
                try {
                    c.razer(false);
                } catch (Exception ignored) {
                }
                c.close();
            }
        });
        net.shutdown();
        super.onDestroy();
    }
}
