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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Always-on companion, started with the TV:
 *  - serves the phone remote on the home network (RemoteServer)
 *  - switches the lights off when the TV turns off, and back on with it, even when sync isn't
 *    running (while sync runs, CaptureService does this itself, with a fade)
 * It sleeps on a socket the rest of the time, so it costs the TV no processor time.
 */
public class RemoteService extends Service {
    private static final String CHANNEL = "tvglow-remote";

    /** Where the phone remote is, for the TV app to show (empty until the server is up). */
    static volatile String url = "";
    /** The phone remote's Lights switch. */
    static volatile boolean lightsWanted = true;
    /** The strip was switched off because the TV turned off, so it comes back with the TV. */
    static volatile boolean darkForTv;
    /** The TV is in standby. */
    static volatile boolean tvOff;
    /** Lights switched off from the phone while syncing: switching them on resumes sync. */
    private static volatile boolean resumeSyncWithLights;

    private static final ExecutorService STRIP = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private RemoteServer server;
    private boolean watching;

    /** Starts the companion if it isn't running (safe to call any time). */
    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, RemoteService.class));
        } catch (Exception ignored) {
            // not allowed right now (rare); opening the app starts it again
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        server = new RemoteServer(getApplicationContext());
        server.start();
        url = server.url();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(power, f);
        watching = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null) url = server.url(); // the TV's address may have changed
        return START_STICKY;
    }

    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            final Context app = c.getApplicationContext();
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                tvOff = false;
                if (server != null) url = server.url();
                if (CaptureService.running || !darkForTv) return; // sync brings its lights back itself
                darkForTv = false;
                if (!lightsWanted) return; // switched off from the phone: stay off
                // Wi-Fi can take a few seconds to come back after standby, so try a few times.
                for (long delay : new long[]{0, 3000, 8000}) {
                    main.postDelayed(() -> {
                        if (!tvOff && !CaptureService.running && lightsWanted) STRIP.execute(() -> StripPower.set(app, true));
                    }, delay);
                }
                return;
            }
            tvOff = true;
            if (CaptureService.running) return; // sync fades its lights out itself
            if (!Settings.load(app).followTv) return;
            final Awake awake = Awake.hold(app, 6000);
            final PendingResult pending = goAsync();
            STRIP.execute(() -> {
                try {
                    // Only bring back what we switch off: if the strip was already off, leave it.
                    Boolean wasOn = StripPower.isOn(app, 400);
                    StripPower.set(app, false);
                    darkForTv = wasOn == null || wasOn;
                } finally {
                    awake.release();
                    pending.finish();
                }
            });
        }
    };

    /** Phone remote: the Lights switch. */
    static void setLights(Context ctx, boolean on) {
        final Context app = ctx.getApplicationContext();
        lightsWanted = on;
        darkForTv = false;
        if (CaptureService.running) {
            if (!on) {
                resumeSyncWithLights = !CaptureService.paused;
                CaptureService.setPaused(true, true);
            } else if (resumeSyncWithLights) {
                CaptureService.setPaused(false, false);
            } else {
                CaptureService.setPaused(true, false); // on, showing the strip's own scene
            }
            return;
        }
        STRIP.execute(() -> StripPower.set(app, on));
    }

    /** Phone remote: the Sync switch. False if sync has to be started on the TV first. */
    static boolean setSync(boolean on) {
        if (!CaptureService.running) return false;
        if (on) {
            lightsWanted = true;
            resumeSyncWithLights = false;
        }
        return CaptureService.setPaused(!on, false);
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TV Glow remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("TV Glow")
                .setContentText("Phone remote and TV power watch")
                .setSmallIcon(android.R.drawable.presence_online)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(2, n);
        }
    }

    @Override
    public void onDestroy() {
        if (watching) {
            try {
                unregisterReceiver(power);
            } catch (IllegalArgumentException ignored) {
            }
            watching = false;
        }
        if (server != null) server.stop();
        url = "";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Keeps the processor and Wi-Fi awake for a moment, so the off command gets out before standby. */
    static final class Awake {
        private PowerManager.WakeLock cpu;
        private WifiManager.WifiLock wifi;

        static Awake hold(Context c, long ms) {
            Awake a = new Awake();
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                a.cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvglow:tv-off");
                a.cpu.setReferenceCounted(false);
                a.cpu.acquire(ms);
            } catch (Throwable ignored) {
            }
            try {
                WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
                a.wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvglow:tv-off");
                a.wifi.setReferenceCounted(false);
                a.wifi.acquire();
            } catch (Throwable ignored) {
            }
            return a;
        }

        void release() {
            try {
                if (wifi != null && wifi.isHeld()) wifi.release();
            } catch (Throwable ignored) {
            }
            try {
                if (cpu != null && cpu.isHeld()) cpu.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
