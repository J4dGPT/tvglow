package com.jad.tvglow;

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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens on the Govee reply port (4002) so the app knows whether its packets actually
 * arrive. UDP never reports failure: without this, a strip that got a new address or had
 * LAN Control switched off looks exactly like one that works.
 *
 * probe() asks the strip for its status; scan() asks every Govee device on the network
 * to announce itself, which is how a strip that moved to a new address is found again.
 */
final class StripMonitor implements Closeable {
    private final DatagramSocket rx;
    private final MulticastSocket tx;
    private volatile boolean open = true;
    private volatile String ip;
    /** Last time (uptime ms) anything arrived from the strip's current address; 0 = never. */
    volatile long lastHeard;
    /** Device model learned from the strip's own scan answer. */
    volatile String sku;
    private final Map<String, String> found = new ConcurrentHashMap<>();

    StripMonitor(String ip) throws IOException {
        this.ip = ip;
        rx = new DatagramSocket(null);
        rx.setReuseAddress(true);
        rx.bind(new InetSocketAddress(GoveeProtocol.LISTEN_PORT));
        rx.setSoTimeout(500);
        tx = new MulticastSocket();
        Thread t = new Thread(this::loop, "tvglow-monitor");
        t.setDaemon(true);
        t.start();
    }

    String ip() {
        return ip;
    }

    void setIp(String ip) {
        this.ip = ip;
    }

    private void loop() {
        byte[] buf = new byte[2048];
        while (open) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                rx.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!open) return;
                try {
                    Thread.sleep(200); // a broken socket fails instantly; don't spin the processor
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            String from = p.getAddress().getHostAddress();
            String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            long now = android.os.SystemClock.uptimeMillis();
            String dip = from, dsku = null;
            if (s.contains("\"scan\"")) {
                try {
                    JSONObject data = new JSONObject(s).getJSONObject("msg").getJSONObject("data");
                    dip = data.optString("ip", from);
                    dsku = data.optString("sku", "?");
                } catch (Exception ignored) {
                    // keep the packet's source address
                }
                found.put(dip, dsku == null ? "?" : dsku);
            }
            if (dip.equals(ip) || from.equals(ip)) {
                lastHeard = now;
                if (dsku != null) sku = dsku;
            }
        }
    }

    /** Ask the strip for its status; it answers on 4002 if our packets reach it. */
    void probe() {
        send("{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}", ip, GoveeProtocol.CONTROL_PORT);
    }

    /** Ask all Govee devices to announce themselves (answers land in found()). */
    void scan() {
        found.clear();
        send(GoveeProtocol.SCAN_JSON, GoveeProtocol.MULTICAST_ADDR, GoveeProtocol.SCAN_PORT);
    }

    /** Devices that answered the last scan: address to model. */
    Map<String, String> found() {
        return new HashMap<>(found);
    }

    private void send(String json, String host, int port) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            tx.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), port));
        } catch (IOException ignored) {
            // next probe retries
        }
    }

    /**
     * Picks the strip among scan answers: the known model if we have one, otherwise an
     * H6167, otherwise the only device that answered. Null when it can't tell.
     */
    static String pick(Map<String, String> found, String knownSku) {
        if (knownSku != null && !knownSku.isEmpty() && !"?".equals(knownSku)) {
            for (Map.Entry<String, String> e : found.entrySet()) {
                if (knownSku.equalsIgnoreCase(e.getValue())) return e.getKey();
            }
            return null;
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            if ("H6167".equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        return found.size() == 1 ? found.keySet().iterator().next() : null;
    }

    @Override
    public void close() {
        open = false;
        rx.close();
        tx.close();
    }
}
