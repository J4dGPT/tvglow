package com.jad.tvglow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen sync pipeline: mirror the screen into a small buffer, sample the edges, and push
 * colors to the strip straight from the capture thread.
 *
 * The video comes first. Budget TVs struggle to decode video and capture the screen at the
 * same time, so the pipeline stays light:
 *  - frames are read at the strip's update rate (Speed), not the video's: a 60 fps video at
 *    Fast is read about 30 times a second, and the frame read is always the newest one
 *  - the capture thread runs at normal priority, so video playback wins when the TV is busy
 *  - packets only go out when a color visibly changed
 *
 * Latency work in here:
 *  - a frame that is read is processed and sent at once (no queue, no hop to another thread)
 *  - Wi-Fi power saving is switched off while syncing
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
    /** Sync paused from the phone: the screen copy is off but the session stays, so resuming needs no prompt. */
    static volatile boolean paused;
    private static volatile CaptureService instance;

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
    private long connectedAt, lastProbe, lastScan, scanCheckAt;
    private boolean stripWarned;
    private volatile String stripNote = "";
    private WifiManager.WifiLock perfLock;

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

    // Frame pacing: the newest frame waits here until the read gap is up (see readGapMs)
    private Image held;
    private long lastReadAt;
    private boolean drainPosted;

    // TV power: the lights fade out and switch off with the TV, and come back with it
    private volatile boolean tvOff;
    private volatile long fadeInAt; // uptime when the strip last switched on; 0 = no fade
    private static final long FADE_IN_MS = 700;
    private boolean powerWatch;

    // TV memory, for the status line
    private long memLowestMb = Long.MAX_VALUE;

    // Rest glow: when the picture stays black (protected apps like Netflix, HDMI inputs, a black
    // screen), ease into a soft light instead of going dark, and come straight back after.
    private static final long REST_AFTER_MS = 2500;
    private static final float REST_IN_MS = 1500f, REST_OUT_MS = 300f;
    private float restLevel;
    private boolean restShown;

    // Night dimming (checked twice a minute)
    private long nightCheckedAt;
    private float nightCached = 1f;

    /** What the strip shows right now, in screen order, for the live preview in the app. */
    static volatile int[] preview;
    static volatile int[] previewCounts;
    /** The numbers behind the status line, shown small under it. */
    static volatile String details = "";

    // Stats for the status line
    private long statsSince;
    private int frameCount, readCount, sendCount, cmdCount, lagN;
    private double lagSum;
    private final float[] fpsHistory = new float[10];
    private int fpsIdx;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            try {
                reloadSettings(now); // so changes apply even while the picture is still
                if (!tvOff && !paused) {
                    tick(now, false);
                    checkStrip(now);
                }
            } catch (Exception ignored) {
            }
            if (handler != null && running) handler.postDelayed(this, tickMs());
        }
    };

    /** Screen off (standby) or shutdown: lights off. Screen on: lights back on. */
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            Handler h = handler;
            if (h == null || a == null) return;
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                h.post(CaptureService.this::lightsOn);
                return;
            }
            Settings s = settings;
            if (s != null && !s.followTv) return; // lights stay on as room lighting
            holdAwake(4000); // the TV may suspend right away; finish the fade first
            final PendingResult pending = goAsync();
            boolean posted = h.post(() -> {
                try {
                    lightsOff();
                } finally {
                    pending.finish();
                }
            });
            if (!posted) pending.finish();
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
        paused = false;
        instance = this;
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

        // Normal priority on purpose: when the TV is short on processor time, video playback
        // has to win over the lights, or the picture stutters.
        captureThread = new HandlerThread("tvglow-capture", Process.THREAD_PRIORITY_DEFAULT);
        captureThread.start();
        handler = new Handler(captureThread.getLooper());

        IntentFilter power = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        power.addAction(Intent.ACTION_SCREEN_ON);
        power.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(powerReceiver, power);
        powerWatch = true;

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
            // No low-latency lock: Android only honors it while TV Glow itself is on screen,
            // which is never the case while you're watching something.
        } catch (Throwable ignored) {
            // the lock is a bonus; sync works without it
        }
    }

    private void releaseWifiLocks() {
        try {
            if (perfLock != null && perfLock.isHeld()) perfLock.release();
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
                    fadeInAt = SystemClock.uptimeMillis();
                    activeMode = mode;
                    status = "Lights are following the picture.";
                    details = "WLED at " + ip;
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
                fadeInAt = connectedAt;
                client = c;
                status = "Lights are following the picture.";
                details = "Strip at " + ip;
            } catch (Exception e) {
                status = "Could not reach strip at " + ip + ": " + e.getMessage();
                stopSelf();
            }
        });
    }

    /**
     * A screen frame landed. Keep it (dropping any older one still waiting) and read it as soon
     * as the read gap is up. Reading every frame of a 60 fps video costs a budget TV processor
     * time it needs for the video itself.
     */
    private void onImage(ImageReader r) {
        if (held != null) {
            held.close(); // superseded by the frame that just landed
            held = null;
        }
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return; // the reader is shutting down
        }
        if (img == null) return;
        held = img;
        long now = SystemClock.uptimeMillis();
        frameCount++;
        long lagNs = System.nanoTime() - img.getTimestamp();
        if (lagNs > 0 && lagNs < 1_000_000_000L) {
            lagSum += lagNs / 1e6;
            lagN++;
        }
        reloadSettings(now);
        updateStats(now);
        long wait = readGapMs() - (now - lastReadAt);
        if (wait > 0) {
            if (!drainPosted) {
                drainPosted = true;
                handler.postDelayed(drain, wait);
            }
            return;
        }
        readHeld(now);
    }

    /** Reads the newest waiting frame once the gap is up, so the last change is never missed. */
    private final Runnable drain = () -> {
        drainPosted = false;
        if (running && held != null) readHeld(SystemClock.uptimeMillis());
    };

    /** Samples the waiting frame and sends the result right away. */
    private void readHeld(long now) {
        Image img = held;
        held = null;
        if (img == null) return;
        lastReadAt = now;
        readCount++;
        try {
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

    /**
     * Shortest time between frames we read: just under the strip's update interval for the
     * chosen Speed, so a 30 fps video at Fast is still read frame by frame.
     */
    private long readGapMs() {
        Settings s = settings;
        if (s == null) return 30;
        int fps;
        switch (s.mode) {
            case Settings.MODE_SINGLE:
                fps = 12;
                break;
            case Settings.MODE_WLED:
                fps = Settings.WLED_FPS[s.speedIdx()];
                break;
            default:
                fps = Settings.RT_FPS[s.speedIdx()];
                break;
        }
        return Math.round(900f / fps);
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
        if (connected() && !tvOff) {
            Settings s = settings;
            String strip = s.mode == Settings.MODE_SEGMENTS
                    ? String.format(Locale.US, "%.0f cmds/s", cmdCount / secs)
                    : String.format(Locale.US, "%.0f updates/s", sendCount / secs);
            String lag = lagN > 0 ? String.format(Locale.US, " · capture lag %.0f ms", lagSum / lagN) : "";
            String health = monitor == null ? "" : stripNote.isEmpty() ? " · strip answering ✓" : stripNote;
            TvHealth.Memory m = TvHealth.memory(this);
            String mem = "";
            if (m != null) {
                memLowestMb = Math.min(memLowestMb, m.freeMb);
                mem = String.format(Locale.US, " · TV memory %d MB free (lowest %d)%s",
                        m.freeMb, memLowestMb, m.low ? ", running low" : "");
            }
            details = String.format(Locale.US, "%s · screen %.0f fps (30s avg %.1f) · reading %.0f/s%s · strip %s%s%s",
                    Settings.MODE_NAMES[Math.max(0, Math.min(3, s.mode))], frameCount / secs, avg,
                    readCount / secs, lag, strip, health, mem);
            if (!restShown) status = friendlyStatus(now);
        }
        frameCount = 0;
        readCount = 0;
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

        // A black picture for a while switches the lights to the rest glow (see tick)
        boolean dark = EdgeSampler.allDark(pixels);
        if (!dark) darkSince = 0;
        else if (darkSince == 0) darkSince = now;

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
                if (!restShown) status = friendlyStatus(now);
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
            status = "Your strip moved to a new address, so TV Glow reconnected to it.";
        } catch (Exception e) {
            stripNote = " · reconnect failed";
        }
    }

    /**
     * The TV is going to standby or shutting down: fade the strip out and switch it off.
     * Runs on the capture thread; frames and timer ticks send nothing from here on.
     */
    private void lightsOff() {
        if (tvOff) return;
        tvOff = true;
        if (held != null) {
            held.close();
            held = null;
        }
        Settings s = settings;
        GoveeClient gc = client;
        WledClient wc = wled;
        int[] last = lastSentColors;
        try {
            boolean frames = s != null && (s.mode == Settings.MODE_REALTIME || s.mode == Settings.MODE_WLED);
            if (frames && last != null) {
                for (int step = 5; step >= 0; step--) {
                    int[] c = scale(last, step / 6f);
                    if (s.mode == Settings.MODE_WLED) {
                        if (wc != null) wc.send(WledProtocol.upsample(c, Math.max(1, s.ledCount), s.sideCounts()[3] > 0));
                    } else if (gc != null) {
                        gc.colors(c, s.gradient);
                    }
                    Thread.sleep(60);
                }
            }
            if (gc != null) {
                gc.turnOff();
                Thread.sleep(120);
                gc.turnOff(); // UDP can drop a packet; the second one makes sure
            }
            if (wc != null && s != null) wc.blackout(s.ledCount);
        } catch (Exception ignored) {
            // best effort: the TV is going to sleep either way
        }
        lastSentColors = null;
        preview = null;
        RemoteService.darkForTv = true;
        status = "TV is off, so the lights are off. They come back on with the TV.";
    }

    /** The TV is back on: switch the strip on and fade the picture back in. */
    private void lightsOn() {
        if (!tvOff) return;
        long now = SystemClock.uptimeMillis();
        scheduler = null;
        lastSentColors = null;
        connectedAt = now; // fresh grace period: the strip wasn't asked anything while off
        fadeInAt = now;
        tvOff = false;
        RemoteService.darkForTv = false;
        if (!RemoteService.lightsWanted) {
            status = "Lights are off from your phone.";
            return; // switched off from the phone: stay off
        }
        switchOn();
        // Wi-Fi can take a few seconds to come back after standby: repeat to be sure.
        handler.postDelayed(retryOn, 2500);
        handler.postDelayed(retryOn, 6000);
        status = paused ? "Sync is paused from your phone." : "TV is on: lights coming back...";
    }

    /** Strip on, and back in realtime mode unless sync is paused. */
    private void switchOn() {
        Settings s = settings;
        GoveeClient gc = client;
        try {
            if (gc != null && s != null && s.mode != Settings.MODE_WLED) {
                gc.turnOn();
                if (s.mode == Settings.MODE_REALTIME && !paused) gc.razer(true);
                lastKeepAlive = SystemClock.uptimeMillis();
            }
        } catch (Exception ignored) {
            // the retries and keep-alives try again
        }
    }

    private final Runnable retryOn = () -> {
        if (running && !tvOff && RemoteService.lightsWanted) switchOn();
    };

    /**
     * Phone remote: stop or resume following the picture without ending the session. Pausing
     * turns the screen copy off completely (no load on the TV while paused); resuming starts a
     * new one from the same permission, so Android doesn't ask again.
     *
     * @param lightsOff when pausing, switch the strip off instead of handing it back its own scene
     * @return false if sync isn't running (it has to be started on the TV once)
     */
    static boolean setPaused(boolean pause, boolean lightsOff) {
        CaptureService s = instance;
        Handler h = s == null ? null : s.handler;
        if (h == null || !running) return false;
        return h.post(() -> s.applyPause(pause, lightsOff));
    }

    private void applyPause(boolean pause, boolean lightsOff) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        Settings s = settings;
        GoveeClient gc = client;
        WledClient wc = wled;
        if (pause) {
            if (!paused) {
                paused = true;
                if (held != null) {
                    held.close();
                    held = null;
                }
                if (display != null) {
                    display.release(); // the screen copy stops: the TV gets all its power back
                    display = null;
                }
                latestTarget = null;
                preview = null;
            }
            try {
                if (lightsOff) {
                    if (gc != null) {
                        gc.turnOff();
                        Thread.sleep(120);
                        gc.turnOff();
                    }
                    if (wc != null && s != null) wc.blackout(s.ledCount);
                } else {
                    if (gc != null && s != null) {
                        gc.turnOn();
                        if (s.mode == Settings.MODE_REALTIME) gc.razer(false); // back to its own scene
                    }
                    if (wc != null) wc.release();
                }
            } catch (Exception ignored) {
            }
            lastSentColors = null;
            status = lightsOff ? "Lights are off from your phone."
                    : "Sync is paused from your phone. The lights show their own scene, and the TV runs at full speed.";
            return;
        }
        if (!paused) return;
        try {
            display = projection.createVirtualDisplay("tvglow", W, H, 160,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (RuntimeException e) {
            // Newer Android versions allow one screen copy per permission
            status = "Press Start sync on the TV to resume: Android needs your OK again.";
            stopSelf();
            return;
        }
        paused = false;
        scheduler = null;
        lastSentColors = null;
        connectedAt = now;
        fadeInAt = now;
        darkSince = 0;
        restLevel = 0f;
        restShown = false;
        if (!tvOff) switchOn();
        status = "Lights are following the picture.";
    }

    /** The everyday status line: what the lights are doing, in plain words. */
    private String friendlyStatus(long now) {
        if (!stripNote.isEmpty()) {
            return "Your strip isn't answering. TV Glow keeps looking for it and reconnects by itself.";
        }
        Settings s = settings;
        if (s != null && s.nightDim && nightLevel(now) < 1f) {
            return "Lights are following the picture, dimmed for the night.";
        }
        return "Lights are following the picture.";
    }

    /** Soft light while the picture can't be seen: an ember for Cozy, warm white otherwise. */
    static int restColor(Settings s) {
        boolean cozy = s.style == EdgeSampler.STYLE_COZY;
        return scale(cozy ? 0xFF7A33 : 0xFFC48A, (cozy ? 0.20f : 0.16f) * s.brightness / 100f);
    }

    /** Night dimming level for now: 1 by day, 0.6 overnight (see nightCurve). */
    private float nightLevel(long now) {
        if (nightCheckedAt == 0 || now - nightCheckedAt > 30000) {
            nightCheckedAt = now;
            java.util.Calendar c = java.util.Calendar.getInstance();
            nightCached = nightCurve(c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE));
        }
        return nightCached;
    }

    /** Eases from full to 60% between 10 and 11 pm, holds overnight, eases back up 6 to 7 am. */
    static float nightCurve(int minuteOfDay) {
        int m = Math.floorMod(minuteOfDay, 24 * 60);
        if (m >= 22 * 60 && m < 23 * 60) return 1f - 0.4f * (m - 22 * 60) / 60f;
        if (m >= 23 * 60 || m < 6 * 60) return 0.6f;
        if (m < 7 * 60) return 0.6f + 0.4f * (m - 6 * 60) / 60f;
        return 1f;
    }

    /** Blend from a to b by t (0 to 1), per channel. */
    static int mix(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** Keeps the processor awake for a moment: the TV may suspend right after the screen goes off. */
    private void holdAwake(long ms) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvglow:lights-off");
            wl.setReferenceCounted(false);
            wl.acquire(ms);
        } catch (Throwable ignored) {
        }
    }

    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Brightness right after the strip switches on: a smooth rise from 0 to 1. */
    private float fadeLevel(long now) {
        long since = now - fadeInAt;
        if (fadeInAt == 0 || since >= FADE_IN_MS) return 1f;
        float x = Math.max(0f, since / (float) FADE_IN_MS);
        return x * x * (3f - 2f * x);
    }

    /** Colors dimmed to a fraction of their brightness. */
    static int[] scale(int[] c, float f) {
        int[] out = new int[c.length];
        for (int i = 0; i < c.length; i++) out[i] = scale(c[i], f);
        return out;
    }

    static int scale(int c, float f) {
        if (f >= 1f) return c;
        float k = Math.max(0f, f);
        return Math.round(((c >> 16) & 0xFF) * k) << 16
                | Math.round(((c >> 8) & 0xFF) * k) << 8
                | Math.round((c & 0xFF) * k);
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
        if (target == null || s == null || tvOff || paused) return;
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

        // Rest glow: ease in after the picture has been black for a moment, snap back after.
        boolean resting = darkSince != 0 && now - darkSince > REST_AFTER_MS;
        restLevel = resting ? Math.min(1f, restLevel + dt / REST_IN_MS) : Math.max(0f, restLevel - dt / REST_OUT_MS);
        if (restLevel > 0f) {
            int glow = restColor(s);
            for (int i = 0; i < n; i++) out[i] = mix(out[i], glow, restLevel);
        }
        if (resting != restShown) {
            restShown = resting;
            status = resting
                    ? "Resting: the picture is dark or hidden from capture (like Netflix or HDMI inputs), so the lights glow softly until it's back."
                    : friendlyStatus(now);
        }
        if (s.nightDim) {
            float night = nightLevel(now);
            if (night < 1f) for (int i = 0; i < n; i++) out[i] = scale(out[i], night);
        }
        preview = out;
        previewCounts = s.sideCounts();

        int[] ordered = correction.apply(EdgeSampler.order(out, s.reverse, s.shift));
        float level = fadeLevel(now);
        if (level < 1f) ordered = scale(ordered, level);

        try {
            if (mode == Settings.MODE_SEGMENTS) {
                sendSegments(gc, ordered, s, now, fresh);
            } else if (mode == Settings.MODE_SINGLE) {
                if (now - lastSend >= 90) {
                    gc.color(scale(correction.apply(EdgeSampler.average(out)), level));
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
        // A fresh frame may go out a little early; timer ticks keep the plain interval.
        if (gap < (fresh ? interval * 0.8f : interval)) return;
        boolean keepAlive = !toWled && now - lastKeepAlive > 10000;
        // Nothing visibly new: skip the packet (less Wi-Fi traffic next to the video stream).
        if (nearlySame(ordered, lastSentColors) && gap < 500 && !keepAlive) return;
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

    /**
     * True when no zone moved more than a step or two since the last packet (one step in dark
     * colors, where a single step is easier to see).
     */
    static boolean nearlySame(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            for (int sh = 0; sh <= 16; sh += 8) {
                int x = (a[i] >> sh) & 0xFF, y = (b[i] >> sh) & 0xFF;
                if (Math.abs(x - y) > (Math.max(x, y) < 48 ? 1 : 2)) return false;
            }
        }
        return true;
    }

    /** App segments: let the scheduler spend the command budget where it matters most. */
    private void sendSegments(GoveeClient gc, int[] ordered, Settings s, long now, boolean fresh) throws Exception {
        // Same packet pacing as Turbo; the scheduler packs more commands into each packet instead.
        float interval = 1000f / Settings.RT_FPS[s.speedIdx()];
        if (now - lastSend < (fresh ? interval * 0.8f : interval)) return;
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
            status = "Sync is off.";
        }
        preview = null;
        details = "";
        instance = null;
        paused = false;
        releaseWifiLocks();
        // Frames are only ever touched on the capture thread, so tear capture down there too:
        // a frame being read can't race with the reader closing under it.
        final Handler h = handler;
        if (h != null) {
            h.removeCallbacks(ticker);
            h.removeCallbacks(drain);
            h.removeCallbacks(retryOn);
            h.post(this::releaseCapture);
        } else {
            releaseCapture();
        }
        if (powerWatch) {
            try {
                unregisterReceiver(powerReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            powerWatch = false;
        }
        // With the TV off (or going off), leave the strip dark instead of handing it back to its
        // old scene, which would light the room up with the TV off.
        Settings st = settings;
        // Also dark if the lights were switched off from the phone.
        final boolean leaveDark = tvOff || (!screenOn() && (st == null || st.followTv)) || !RemoteService.lightsWanted;
        if (tvOff || !screenOn()) RemoteService.darkForTv = true; // the companion brings them back with the TV
        final int leds = st == null ? 1 : Math.max(1, st.ledCount);
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
                    if (leaveDark) w.blackout(leds); else w.release();
                } catch (Exception ignored) {
                }
                w.close();
            }
            if (c != null) {
                try {
                    c.razer(false);
                    if (leaveDark) c.turnOff();
                } catch (Exception ignored) {
                }
                c.close();
            }
        });
        net.shutdown();
        super.onDestroy();
    }

    private void releaseCapture() {
        try {
            if (held != null) held.close();
        } catch (Throwable ignored) {
        }
        held = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (captureThread != null) captureThread.quitSafely();
    }
}
