package com.jad.tvglow;

import android.content.Context;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Switching the lights on and off when sync isn't running (the TV turning off, the phone
 * remote's Lights switch). Works on the saved address first, then finds the strip with a quick
 * scan in case the router gave it a new one.
 */
final class StripPower {
    private StripPower() {}

    private static final String STATUS_JSON = "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}";

    /** Strip on or off, wherever it is now. Blocks for up to about 1.5 seconds. */
    static void set(Context ctx, boolean on) {
        Settings s = Settings.load(ctx);
        if (s.mode == Settings.MODE_WLED) {
            if (s.wledIp == null || s.wledIp.isEmpty()) return;
            try (WledClient w = new WledClient(s.wledIp)) {
                // Off: dark and kept dark. On: hand WLED back its own state (which may be off).
                if (on) w.release(); else w.blackout(s.ledCount);
            } catch (Exception ignored) {
            }
            return;
        }
        String ip = s.ip;
        if (ip == null || ip.isEmpty()) return; // never set up: don't touch anyone's lights
        send(ip, on);
        for (GoveeClient.Found f : GoveeClient.discover(ctx, 1200)) {
            if (f.ip.equals(ip) || !sameStrip(f, s)) continue;
            send(f.ip, on);
            Settings saved = Settings.load(ctx);
            saved.ip = f.ip;
            if (f.sku != null && !"?".equals(f.sku)) saved.sku = f.sku;
            saved.save(ctx);
            break;
        }
    }

    private static boolean sameStrip(GoveeClient.Found f, Settings s) {
        String want = s.sku == null || s.sku.isEmpty() || "?".equals(s.sku) ? "H6167" : s.sku;
        return want.equalsIgnoreCase(f.sku);
    }

    /** Sends the command twice: UDP can drop a packet and the TV may be about to sleep. */
    private static void send(String ip, boolean on) {
        try (GoveeClient c = new GoveeClient(ip)) {
            if (on) c.turnOn(); else c.turnOff();
            Thread.sleep(120);
            if (on) c.turnOn(); else c.turnOff();
        } catch (Exception ignored) {
        }
    }

    /** Asks the strip whether it's on. Null if it doesn't answer in time. */
    static Boolean isOn(Context ctx, int timeoutMs) {
        Settings s = Settings.load(ctx);
        if (s.mode == Settings.MODE_WLED || s.ip == null || s.ip.isEmpty()) return null;
        try (DatagramSocket rx = new DatagramSocket(null); DatagramSocket tx = new DatagramSocket()) {
            rx.setReuseAddress(true);
            rx.bind(new InetSocketAddress(GoveeProtocol.LISTEN_PORT));
            rx.setSoTimeout(timeoutMs);
            byte[] q = STATUS_JSON.getBytes(StandardCharsets.UTF_8);
            tx.send(new DatagramPacket(q, q.length, InetAddress.getByName(s.ip), GoveeProtocol.CONTROL_PORT));
            byte[] buf = new byte[1024];
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rx.receive(p);
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!reply.contains("devStatus") || !reply.contains("onOff")) continue;
                JSONObject data = new JSONObject(reply).getJSONObject("msg").getJSONObject("data");
                return data.optInt("onOff", 1) == 1;
            }
        } catch (Exception ignored) {
            // no answer: unknown
        }
        return null;
    }
}
