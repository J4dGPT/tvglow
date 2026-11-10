package com.jad.tvglow;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The phone remote: a small web server on the TV. The page (assets/remote.html) talks to a
 * tiny JSON API:
 *   GET  /api/state            what's going on, plus the live colors
 *   POST /api/sync   {"on":b}  pause or resume sync
 *   POST /api/lights {"on":b}  lights on or off
 *   POST /api/set    {"key":v} style, brightness, smoothing, speed, nightDim, followTv
 * Home network only; there's nothing here worth protecting beyond the lights themselves.
 */
final class RemoteServer {
    private static final int[] PORTS = {8080, 8081, 8765};
    private static final String MANIFEST = "{\"name\":\"TV Glow\",\"short_name\":\"TV Glow\",\"start_url\":\"/\","
            + "\"display\":\"standalone\",\"background_color\":\"#0b0b12\",\"theme_color\":\"#0b0b12\","
            + "\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"180x180\",\"type\":\"image/png\"}]}";

    private final Context ctx;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private volatile ServerSocket server;
    private volatile boolean open;
    private byte[] page, icon;

    RemoteServer(Context ctx) {
        this.ctx = ctx;
    }

    void start() {
        for (int p : PORTS) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                break;
            } catch (IOException ignored) {
                // taken: try the next port
            }
        }
        if (server == null) return;
        open = true;
        Thread t = new Thread(this::loop, "tvglow-remote");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        open = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    /** http://(the TV's address on the home network):(port), or "" when there's no network. */
    String url() {
        ServerSocket s = server;
        if (s == null) return "";
        String ip = lanAddress();
        return ip == null ? "" : "http://" + ip + ":" + s.getLocalPort();
    }

    static String lanAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void loop() {
        while (open) {
            try {
                final Socket s = server.accept();
                workers.execute(() -> handle(s));
            } catch (RejectedExecutionException e) {
                return;
            } catch (IOException e) {
                if (!open) return;
                try {
                    Thread.sleep(200); // network hiccup: don't spin
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            String request = readLine(in);
            if (request == null || request.isEmpty()) return;
            String[] parts = request.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int length = 0;
            for (String h = readLine(in); h != null && !h.isEmpty(); h = readLine(in)) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    length = Integer.parseInt(h.substring(c + 1).trim());
                }
            }
            byte[] body = new byte[Math.max(0, Math.min(length, 8192))];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            respond(s.getOutputStream(), route(method, path, new String(body, 0, got, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // a dropped phone connection is no reason to stop serving
        }
    }

    static final class Response {
        final int code;
        final String type;
        final byte[] body;

        Response(int code, String type, byte[] body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }

        static Response json(String s) {
            return new Response(200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        }
    }

    Response route(String method, String path, String body) {
        boolean post = "POST".equals(method);
        switch (path) {
            case "/":
            case "/index.html":
                return new Response(200, "text/html; charset=utf-8", page());
            case "/icon.png":
            case "/apple-touch-icon.png":
                return new Response(200, "image/png", icon());
            case "/manifest.webmanifest":
                return new Response(200, "application/manifest+json", MANIFEST.getBytes(StandardCharsets.UTF_8));
            case "/api/state":
                return Response.json(state(null));
            case "/api/sync": {
                if (!post) break;
                Boolean on = bool(body, "on");
                String note = null;
                if (on != null && !RemoteService.setSync(on)) {
                    note = "Sync starts on the TV: press Start sync there once (Android asks for permission). After that you can pause and resume it from here.";
                }
                settle();
                return Response.json(state(note));
            }
            case "/api/lights": {
                if (!post) break;
                Boolean on = bool(body, "on");
                if (on != null) RemoteService.setLights(ctx, on);
                settle();
                return Response.json(state(null));
            }
            case "/api/set":
                if (!post) break;
                applySettings(body);
                return Response.json(state(null));
            default:
                return new Response(404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
        }
        return new Response(405, "text/plain; charset=utf-8", "Use POST".getBytes(StandardCharsets.UTF_8));
    }

    /** Give a posted change a moment to land, so the reply already shows it. */
    private static void settle() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
    }

    private void applySettings(String body) {
        Settings s = Settings.load(ctx);
        Integer v;
        Boolean b;
        if ((v = integer(body, "style")) != null) s.style = clamp(v, 0, 2);
        if ((v = integer(body, "brightness")) != null) s.brightness = clamp(v, 10, 100);
        if ((v = integer(body, "smoothing")) != null) s.smoothing = clamp(v, 0, 3);
        if ((v = integer(body, "speed")) != null) s.speed = clamp(v, 0, Settings.SPEED_NAMES.length - 1);
        if ((b = bool(body, "nightDim")) != null) s.nightDim = b;
        if ((b = bool(body, "followTv")) != null) s.followTv = b;
        s.save(ctx);
    }

    /** Everything the phone page shows, as JSON. */
    String state(String note) {
        Settings s = Settings.load(ctx);
        boolean session = CaptureService.running;
        boolean syncing = session && !CaptureService.paused;
        boolean lights = RemoteService.lightsWanted && !(RemoteService.tvOff && RemoteService.darkForTv);
        String status;
        if (syncing) {
            status = CaptureService.status;
        } else if (RemoteService.tvOff) {
            status = lights ? "The TV is off. The lights are on as room lighting." : "The TV is off, so the lights are off.";
        } else if (!RemoteService.lightsWanted) {
            status = "The lights are off.";
        } else if (session) {
            status = "Sync is paused. The lights show their own scene, and the TV runs at full speed.";
        } else {
            status = "Sync is off. Press Start sync on the TV once, then control it from here.";
        }
        StringBuilder j = new StringBuilder(512);
        j.append('{');
        field(j, "session", session).append(',');
        field(j, "syncing", syncing).append(',');
        field(j, "lights", lights).append(',');
        field(j, "tvOff", RemoteService.tvOff).append(',');
        j.append("\"status\":").append(quote(status)).append(',');
        j.append("\"note\":").append(note == null ? "null" : quote(note)).append(',');
        int[] colors = syncing ? CaptureService.preview : null;
        int[] counts = CaptureService.previewCounts != null ? CaptureService.previewCounts : s.sideCounts();
        j.append("\"preview\":[");
        if (colors != null) {
            for (int i = 0; i < colors.length; i++) {
                if (i > 0) j.append(',');
                j.append(String.format(Locale.US, "\"#%06x\"", colors[i] & 0xFFFFFF));
            }
        }
        j.append("],\"counts\":[");
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) j.append(',');
            j.append(counts[i]);
        }
        j.append("],");
        j.append("\"style\":").append(s.style).append(',');
        j.append("\"brightness\":").append(s.brightness).append(',');
        j.append("\"smoothing\":").append(s.smoothing).append(',');
        j.append("\"speed\":").append(s.speedIdx()).append(',');
        field(j, "nightDim", s.nightDim).append(',');
        field(j, "followTv", s.followTv);
        j.append('}');
        return j.toString();
    }

    private static StringBuilder field(StringBuilder j, String name, boolean v) {
        return j.append('"').append(name).append("\":").append(v);
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** The raw value of "key" in a small flat JSON object, or null. */
    static String raw(String body, String key) {
        if (body == null) return null;
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int c = body.indexOf(':', k + key.length() + 2);
        if (c < 0) return null;
        int i = c + 1, j;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        for (j = i; j < body.length() && ",}".indexOf(body.charAt(j)) < 0; j++) ;
        return body.substring(i, j).trim();
    }

    static Boolean bool(String body, String key) {
        String v = raw(body, key);
        if ("true".equals(v)) return Boolean.TRUE;
        if ("false".equals(v)) return Boolean.FALSE;
        return null;
    }

    static Integer integer(String body, String key) {
        try {
            String v = raw(body, key);
            return v == null ? null : (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private synchronized byte[] page() {
        if (page == null) page = asset("remote.html");
        return page;
    }

    private synchronized byte[] icon() {
        if (icon == null) icon = asset("icon.png");
        return icon;
    }

    private byte[] asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("line too long");
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void respond(OutputStream out, Response r) throws IOException {
        String reason = r.code == 200 ? "OK" : r.code == 404 ? "Not Found" : "Method Not Allowed";
        String head = "HTTP/1.1 " + r.code + " " + reason + "\r\n"
                + "Content-Type: " + r.type + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }
}
