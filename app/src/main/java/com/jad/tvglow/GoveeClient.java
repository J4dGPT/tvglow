package com.jad.tvglow;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Sends Govee LAN commands to one device. All methods block; call off the main thread. */
final class GoveeClient implements Closeable {
    private final DatagramSocket socket;
    private final InetAddress address;

    GoveeClient(String ip) throws IOException {
        socket = new DatagramSocket();
        address = InetAddress.getByName(ip);
    }

    void send(String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(b, b.length, address, GoveeProtocol.CONTROL_PORT));
    }

    void turnOn() throws IOException {
        send(GoveeProtocol.turnJson(true));
    }

    void color(int rgb) throws IOException {
        send(GoveeProtocol.colorJson(rgb));
    }

    /** Segment colors via ptReal. Batches up to 12 frames per message, 50 ms apart. */
    void segments(int[] colors) throws IOException, InterruptedException {
        java.util.List<byte[]> frames = GoveeProtocol.segmentFrames(colors);
        for (int i = 0; i < frames.size(); i += 12) {
            if (i > 0) Thread.sleep(50);
            send(GoveeProtocol.ptRealJson(frames.subList(i, Math.min(frames.size(), i + 12))));
        }
    }

    void razer(boolean on) throws IOException {
        send(GoveeProtocol.razerJson(GoveeProtocol.razerEnable(on)));
    }

    void colors(int[] rgb, boolean stretch) throws IOException {
        send(GoveeProtocol.razerJson(GoveeProtocol.ledData(rgb, stretch)));
    }

    /** Planned segment commands, all in one ptReal packet. */
    void segmentCmds(java.util.List<SegmentScheduler.Cmd> cmds) throws IOException {
        java.util.List<byte[]> frames = new java.util.ArrayList<>();
        for (SegmentScheduler.Cmd c : cmds) frames.add(GoveeProtocol.segmentColorFrame(c.color, c.mask));
        send(GoveeProtocol.ptRealJson(frames));
    }

    @Override
    public void close() {
        socket.close();
    }

    static final class Found {
        final String ip;
        final String sku;

        Found(String ip, String sku) {
            this.ip = ip;
            this.sku = sku;
        }
    }

    /** Multicast scan. Devices must have LAN Control enabled in the Govee app. */
    static List<Found> discover(Context ctx, int timeoutMs) {
        List<Found> found = new ArrayList<>();
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        if (wm != null) {
            lock = wm.createMulticastLock("tvglow");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        try (DatagramSocket rx = new DatagramSocket(null);
             MulticastSocket tx = new MulticastSocket()) {
            rx.setReuseAddress(true);
            rx.bind(new InetSocketAddress(GoveeProtocol.LISTEN_PORT));
            rx.setSoTimeout(300);

            byte[] q = GoveeProtocol.SCAN_JSON.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName(GoveeProtocol.MULTICAST_ADDR);
            DatagramPacket query = new DatagramPacket(q, q.length, group, GoveeProtocol.SCAN_PORT);

            long end = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    tx.send(query);
                    nextSend = System.currentTimeMillis() + 800;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    rx.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                try {
                    JSONObject data = new JSONObject(s).getJSONObject("msg").getJSONObject("data");
                    String ip = data.optString("ip", p.getAddress().getHostAddress());
                    String sku = data.optString("sku", "?");
                    boolean dup = false;
                    for (Found f : found) if (f.ip.equals(ip)) dup = true;
                    if (!dup) found.add(new Found(ip, sku));
                } catch (Exception ignored) {
                    // not a scan response
                }
            }
        } catch (IOException ignored) {
            // fall through with whatever we found
        } finally {
            if (lock != null) lock.release();
        }
        return found;
    }

    /** Picks the saved IP, otherwise scans (preferring an H6167). Returns null if none. */
    static String resolveIp(Context ctx, Settings s) {
        if (s.ip != null && !s.ip.isEmpty()) return s.ip;
        List<Found> list = discover(ctx, 3000);
        if (list.isEmpty()) return null;
        Found pick = list.get(0);
        for (Found f : list) if ("H6167".equalsIgnoreCase(f.sku)) pick = f;
        s.ip = pick.ip;
        s.sku = pick.sku;
        s.save(ctx);
        return pick.ip;
    }
}
