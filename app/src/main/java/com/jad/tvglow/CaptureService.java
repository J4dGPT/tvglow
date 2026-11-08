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

    private static final int W = 96, H = 54;
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

    private Settings settings;
    private int[] pixels = new int[W * H];
    private float[] smoothed;
    private long lastFrame, lastSettingsLoad, lastKeepAlive, darkSince;

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

        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        display = projection.createVirtualDisplay("tvglow", W, H, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
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
                c.razer(true);
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
            if (now - lastFrame < 1000 / settings.fps) return;
            lastFrame = now;
            if (client == null) return;

            copyPixels(img);
            process(now);
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

    private void process(long now) {
        Settings s = settings;
        int n = s.segments;

        if (EdgeSampler.allDark(pixels)) {
            if (darkSince == 0) darkSince = now;
            if (now - darkSince > 3000) {
                status = "Screen reads black. Protected apps (Netflix etc.) block capture.";
            }
        } else if (darkSince != 0) {
            darkSince = 0;
            status = "Syncing to strip at " + s.ip;
        }

        int[] target = EdgeSampler.sample(pixels, W, H, n, s.fourSides, 0.12f);
        if (smoothed == null || smoothed.length != n * 3) {
            smoothed = new float[n * 3];
            for (int i = 0; i < n; i++) {
                smoothed[i * 3] = (target[i] >> 16) & 0xFF;
                smoothed[i * 3 + 1] = (target[i] >> 8) & 0xFF;
                smoothed[i * 3 + 2] = target[i] & 0xFF;
            }
        }
        float a = Settings.SMOOTH_ALPHA[Math.max(0, Math.min(2, s.smoothing))];
        float bright = s.brightness / 100f;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int t = target[i];
            smoothed[i * 3] += a * (((t >> 16) & 0xFF) - smoothed[i * 3]);
            smoothed[i * 3 + 1] += a * (((t >> 8) & 0xFF) - smoothed[i * 3 + 1]);
            smoothed[i * 3 + 2] += a * ((t & 0xFF) - smoothed[i * 3 + 2]);
            int c = Math.round(smoothed[i * 3]) << 16
                    | Math.round(smoothed[i * 3 + 1]) << 8
                    | Math.round(smoothed[i * 3 + 2]);
            out[i] = EdgeSampler.adjust(c, s.saturation, bright);
        }
        int[] ordered = EdgeSampler.order(out, s.reverse, s.shift);
        boolean keepAlive = now - lastKeepAlive > 10000;
        if (keepAlive) lastKeepAlive = now;

        if (!sending.compareAndSet(false, true)) return; // drop frame if network is busy
        final boolean gradient = s.gradient;
        try {
            net.execute(() -> {
                try {
                    GoveeClient c = client;
                    if (c == null) return;
                    if (keepAlive) c.razer(true);
                    c.colors(ordered, gradient);
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
        if (captureThread != null) captureThread.quitSafely();
        final GoveeClient c = client;
        client = null;
        net.execute(() -> {
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
