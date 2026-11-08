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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mirrors the screen into a tiny buffer, samples the edges, and streams colors to the strip. */
public class CaptureService extends Service {
    static final String ACTION_STOP = "com.jad.tvglow.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    private int W = 96, H = 54;
    private static final String CHANNEL = "tvglow";

    static volatile boolean running = false;
    static volatile String status = "Idle";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler handler;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private volatile GoveeClient client;
    private volatile WledClient wled;

    private boolean connected() {
        return client != null || wled != null;
    }

    private Settings settings;
    private int[] pixels;
    private volatile int[] latestTarget;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            try {
                tick(now);
            } catch (Exception ignored) {
            }
            Settings s = settings;
            int ms = s == null ? 50 : 1000 / Math.max(1, Math.min(s.fps, 30));
            if (handler != null && running) handler.postDelayed(this, ms);
        }
    };
    private float[] smoothed;
    private long lastFrame, lastSettingsLoad, lastKeepAlive, darkSince, lastFull, lastSend, lastProcess;
    private int lastAvg, refreshIdx, frameCount, sendCount;
    private long statsSince;
    private final float[] fpsHistory = new float[10];
    private int fpsIdx;
    private int[] lastSentSeg;

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
        running = true;
        status = "Connecting to strip...";
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
        handler.postDelayed(ticker, 50); // strip updates run on their own clock
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

    private void connectStrip() {
        if (settings.mode == Settings.MODE_WLED) {
            net.execute(() -> {
                String ip = settings.wledIp;
                if (ip == null || ip.isEmpty()) {
                    status = "WLED mode needs the WLED controller's address. Enter it under Connection.";
                    stopSelf();
                    return;
                }
                try {
                    wled = new WledClient(ip);
                    status = "Syncing to WLED at " + ip;
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
                if (settings.mode == Settings.MODE_REALTIME) c.razer(true); else c.razer(false);
                client = c;
                lastKeepAlive = SystemClock.uptimeMillis();
                status = "Syncing to strip at " + ip;
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
            if (now - lastSettingsLoad > 1000) {
                Settings fresh = Settings.load(this);
                fresh.ip = settings.ip;
                settings = fresh;
                lastSettingsLoad = now;
            }
            if (now - lastFrame < 8) return; // take every frame the TV offers (up to ~120/s)
            lastFrame = now;
            frameCount++;
            if (statsSince == 0) statsSince = now;
            if (now - statsSince >= 3000) {
                float secs = (now - statsSince) / 1000f;
                fpsHistory[fpsIdx % fpsHistory.length] = frameCount / secs;
                fpsIdx++;
                float avg = 0, min = 999;
                int cnt = Math.min(fpsIdx, fpsHistory.length);
                for (int i = 0; i < cnt; i++) { avg += fpsHistory[i]; min = Math.min(min, fpsHistory[i]); }
                avg /= Math.max(1, cnt);
                if (darkSince == 0 && connected()) {
                    status = String.format(java.util.Locale.US,
                            "Syncing | screen: %.0f fps now, last 30s avg %.1f (lowest %.0f) | strip %.0f updates/s",
                            frameCount / secs, avg, min, sendCount / secs);
                }
                frameCount = 0;
                sendCount = 0;
                statsSince = now;
            }
            if (!connected()) return;

            copyPixels(img);
            latestTarget = computeTarget(now);
            tick(now); // send right away on a fresh frame instead of waiting for the next timer tick
        } finally {
            img.close();
        }
    }

    private void copyPixels(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer b = plane.getBuffer();
        int ps = plane.getPixelStride(), rs = plane.getRowStride();
        int w = Math.min(img.getWidth(), W), h = Math.min(img.getHeight(), H);
        for (int y = 0; y < h; y++) {
            int row = y * rs;
            for (int x = 0; x < w; x++) {
                int o = row + x * ps;
                pixels[y * W + x] = (b.get(o) & 0xFF) << 16
                        | (b.get(o + 1) & 0xFF) << 8
                        | (b.get(o + 2) & 0xFF);
            }
        }
    }

    /** New screen frame: sample the edges into styled zone colors. */
    private int[] computeTarget(long now) {
        Settings s = settings;
        int[] sideCounts = s.sideCounts();
        int n = sideCounts[0] + sideCounts[1] + sideCounts[2] + sideCounts[3];
        if (n == 0) return null;

        if (EdgeSampler.allDark(pixels)) {
            if (darkSince == 0) darkSince = now;
            if (now - darkSince > 3000) {
                status = "Screen reads black. Protected apps (Netflix etc.) block capture.";
            }
        } else if (darkSince != 0) {
            darkSince = 0;
            status = "Syncing to strip at " + s.ip;
        }

        // 1. Sample: vivid, linear-light weighted zones, then a light blend with neighbors.
        int[] target = EdgeSampler.sampleSides(pixels, W, H, sideCounts, 0.14f);
        target = EdgeSampler.blendNeighbors(target, sideCounts[3] > 0, 0.18f);
        float bright = s.brightness / 100f;
        for (int i = 0; i < n; i++) target[i] = EdgeSampler.style(target[i], s.style, bright);

        return target;
    }

    /**
     * Runs at the chosen Rate regardless of how often the TV delivers frames, gliding the
     * strip toward the latest captured colors so slow capture still looks continuous.
     */
    private void tick(long now) {
        int[] target = latestTarget;
        if (target == null || !connected()) return;
        Settings s = settings;
        int n = target.length;

        // 2. Adaptive temporal smoothing: small drifts glide, big changes snap.
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
        boolean sceneCut = EdgeSampler.colorDistance(EdgeSampler.average(target), lastAvg) > 110;
        lastAvg = EdgeSampler.average(target);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int t = target[i];
            float tr = (t >> 16) & 0xFF, tg = (t >> 8) & 0xFF, tb = t & 0xFF;
            float dr = tr - smoothed[i * 3], dg = tg - smoothed[i * 3 + 1], db = tb - smoothed[i * 3 + 2];
            float dist = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            float a = sceneCut ? 1f : base + (1f - base) * Math.min(1f, dist / 90f); // real changes snap sooner
            a = 1f - (float) Math.pow(1f - a, dt / 33f); // frame-rate independent
            smoothed[i * 3] += a * dr;
            smoothed[i * 3 + 1] += a * dg;
            smoothed[i * 3 + 2] += a * db;
            int c = Math.round(smoothed[i * 3]) << 16
                    | Math.round(smoothed[i * 3 + 1]) << 8
                    | Math.round(smoothed[i * 3 + 2]);
            out[i] = c;
        }
        int[] ordered = EdgeSampler.order(out, s.reverse, s.shift);

        // 3. Send at the chosen rate (single color is capped at 10/s; it fades in firmware).
        final int mode = s.mode;
        int sendMs = mode == Settings.MODE_SINGLE ? 100 : 1000 / Math.min(s.fps, mode == Settings.MODE_SEGMENTS ? 20 : mode == Settings.MODE_WLED ? 60 : 30);
        if (now - lastSend < sendMs * 0.7f) return; // tolerate timer jitter

        int[] seg = null;
        if (mode == Settings.MODE_SEGMENTS) {
            // Command budget: the strip processes each segment command in turn, so flooding
            // it builds a queue (delay) that then drains in bursts (choppiness). Send only the
            // segments that changed most, plus one rotating refresh.
            if (lastSentSeg == null || lastSentSeg.length != n) {
                lastSentSeg = new int[n];
                java.util.Arrays.fill(lastSentSeg, -2);
            }
            int budget = 4; // Rate 10/15/20 => ~40/60/80 segment commands per second
            seg = new int[n];
            java.util.Arrays.fill(seg, -1);
            // Detail: neighboring segments share one color (one command per group).
            int g = Settings.DETAIL_GROUP[Math.max(0, Math.min(2, s.detail))];
            int groups = (n + g - 1) / g;
            int[] gc = new int[groups];
            for (int k = 0; k < groups; k++) {
                int a = k * g, b = Math.min(n, a + g);
                gc[k] = EdgeSampler.average(java.util.Arrays.copyOfRange(ordered, a, b));
            }
            float[] dist = new float[groups];
            for (int k = 0; k < groups; k++) {
                int prev = lastSentSeg[k * g];
                dist[k] = prev < 0 ? 999f : EdgeSampler.colorDistance(gc[k], prev);
            }
            boolean[] pick = new boolean[groups];
            int picked = 0;
            for (int it = 0; it < budget; it++) {
                int best = -1;
                float bestD = 6f; // ignore changes too small to see
                for (int k = 0; k < groups; k++) {
                    if (!pick[k] && dist[k] > bestD) { bestD = dist[k]; best = k; }
                }
                if (best < 0) break;
                pick[best] = true;
                picked++;
            }
            refreshIdx = (refreshIdx + 1) % groups; // keeps every zone fresh if a packet was lost
            if (!pick[refreshIdx]) { pick[refreshIdx] = true; picked++; }
            if (picked == 0) return;
            for (int k = 0; k < groups; k++) {
                if (!pick[k]) continue;
                for (int i = k * g; i < Math.min(n, k * g + g); i++) seg[i] = gc[k];
            }
        }
        if (!sending.compareAndSet(false, true)) return; // drop frame if network is busy
        lastSend = now;
        sendCount++;
        if (seg != null) {
            for (int i = 0; i < n; i++) if (seg[i] >= 0) lastSentSeg[i] = seg[i];
        }
        boolean keepAlive = now - lastKeepAlive > 10000;
        if (keepAlive) lastKeepAlive = now;

        final int[] segColors = seg;
        final int[] ledColors = mode == Settings.MODE_WLED
                ? WledProtocol.upsample(ordered, Math.max(1, s.ledCount), s.sideCounts()[3] > 0)
                : null;
        final int avg = EdgeSampler.average(out);
        final boolean gradient = s.gradient;
        try {
            net.execute(() -> {
                try {
                    if (mode == Settings.MODE_WLED) {
                        WledClient w = wled;
                        if (w != null) w.send(ledColors);
                        return;
                    }
                    GoveeClient c = client;
                    if (c == null) return;
                    if (mode == Settings.MODE_SEGMENTS) {
                        c.segments(segColors);
                    } else if (mode == Settings.MODE_SINGLE) {
                        c.color(avg);
                    } else {
                        if (keepAlive) c.razer(true);
                        c.colors(ordered, gradient);
                    }
                } catch (Exception e) {
                    status = "Send failed: " + e.getMessage();
                } finally {
                    sending.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            sending.set(false); // service is shutting down
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (!status.startsWith("No strip") && !status.startsWith("Could not")
                && !status.startsWith("Screen capture") && !status.startsWith("Missing")) {
            status = "Stopped";
        }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (handler != null) handler.removeCallbacks(ticker);
        if (captureThread != null) captureThread.quitSafely();
        final GoveeClient c = client;
        final WledClient w = wled;
        client = null;
        wled = null;
        net.execute(() -> {
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
