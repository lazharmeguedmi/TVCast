package com.tvcast.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds and controls the "TV Cast" app installed on the TV.
 * Discovery: UDP broadcast on port 39888. Control: small HTTP API on port 8090.
 * No Android APIs here, so it can be tested on a plain JVM.
 */
public final class TvApps {

    public static final int DISCOVERY_PORT = 39888;
    public static final int HTTP_PORT = 8090;

    public static final class Tv {
        public final String name, host;
        public final int port;

        public Tv(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        String base() {
            return "http://" + host + ":" + port + "/";
        }
    }

    private TvApps() {
    }

    /** How connections to the TV are opened (the Android app routes them over the home Wi-Fi). */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;

        void bind(DatagramSocket s);

        void bind(java.net.Socket s);
    }

    public static volatile Opener opener = new Opener() {
        @Override
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        }

        @Override
        public void bind(DatagramSocket s) {
        }

        @Override
        public void bind(java.net.Socket s) {
        }
    };

    /** Broadcasts on the Wi-Fi and collects TVs that answer. Also checks {@code knownHosts} directly. */
    public static List<Tv> find(int timeoutMs, List<String> knownHosts) {
        Map<String, Tv> found = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            opener.bind(sock);
            sock.setBroadcast(true);
            sock.setSoTimeout(200);
            byte[] msg = "TVCAST_DISCOVER".getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = broadcastAddresses();
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            int sends = 0;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    for (InetAddress a : targets) {
                        try {
                            sock.send(new DatagramPacket(msg, msg.length, a, DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    sends++;
                    nextSend = System.currentTimeMillis() + 400;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String[] lines = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\n");
                if (lines.length >= 3 && lines[0].trim().equals("TVCAST_TV")) {
                    String host = p.getAddress().getHostAddress();
                    int port = HTTP_PORT;
                    try {
                        port = Integer.parseInt(lines[2].trim());
                    } catch (NumberFormatException ignored) {
                    }
                    found.put(host, new Tv(lines[1].trim(), host, port));
                }
            }
        } catch (IOException ignored) {
        }
        // Broadcast can be blocked on some routers: try the TVs we've seen before directly.
        if (knownHosts != null) {
            for (String h : knownHosts) {
                if (h == null || h.isEmpty() || found.containsKey(h)) continue;
                Tv tv = probe(h, HTTP_PORT);
                if (tv != null) found.put(h, tv);
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Checks whether TV Cast is running at host:port. */
    public static Tv probe(String host, int port) {
        try {
            String info = get("http://" + host + ":" + port + "/info", 1500);
            if (!info.contains("tvcast-tv")) return null;
            String name = jsonField(info, "name");
            return new Tv(name == null || name.isEmpty() ? host : name, host, port);
        } catch (IOException e) {
            return null;
        }
    }

    public static void play(Tv tv, String kind, String url, String title, String id) throws IOException {
        get(tv.base() + "play?kind=" + enc(kind) + "&url=" + enc(url) + "&title=" + enc(title)
                + "&id=" + enc(id), 4000);
    }

    /**
     * Says hello to the TV. The TV records this phone as connected and checks it can reach the
     * phone back on {@code phonePort}. Returns the TV's JSON reply ({"ok":true,"reverse":true|false,...}).
     */
    public static String hello(Tv tv, String phoneName, int phonePort, boolean announce) throws IOException {
        return get(tv.base() + "hello?name=" + enc(phoneName) + "&port=" + phonePort
                + (announce ? "&announce=1" : ""), 6000);
    }

    public static void command(Tv tv, String cmd) throws IOException {
        get(tv.base() + cmd, 4000);
    }

    public static void message(Tv tv, String text) throws IOException {
        get(tv.base() + "message?text=" + enc(text), 4000);
    }

    /** Raw JSON status from the TV. */
    public static String status(Tv tv) throws IOException {
        return get(tv.base() + "status", 2500);
    }

    // ---------- helpers ----------

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            InetAddress all = InetAddress.getByName("255.255.255.255");
            if (!out.contains(all)) out.add(all);
        } catch (Exception ignored) {
        }
        return out;
    }

    static String get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Tiny JSON field reader for the TV's flat replies. */
    public static String jsonField(String json, String key) {
        String k = "\"" + key + "\":";
        int i = json.indexOf(k);
        if (i < 0) return null;
        i += k.length();
        if (i < json.length() && json.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int j = i + 1; j < json.length(); j++) {
                char ch = json.charAt(j);
                if (ch == '\\' && j + 1 < json.length()) {
                    char nx = json.charAt(++j);
                    sb.append(nx == 'n' ? '\n' : nx);
                } else if (ch == '"') {
                    return sb.toString();
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        int end = i;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }

    public static long jsonLong(String json, String key, long def) {
        try {
            return Long.parseLong(jsonField(json, key));
        } catch (Exception e) {
            return def;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
