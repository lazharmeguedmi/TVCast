package com.tvcast.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives commands from the phone app.
 *  - TCP 8090: tiny HTTP API  (/info /play /pause /resume /seek /stop /status), JSON replies
 *  - UDP 39888: answers "TVCAST_DISCOVER" broadcasts so the phone finds this TV automatically
 * No Android APIs here, so it can be tested on a plain JVM.
 */
public class CommandServer {

    public static final int HTTP_PORT = 8090;
    public static final int DISCOVERY_PORT = 39888;
    public static final String DISCOVER_MSG = "TVCAST_DISCOVER";
    public static final String REPLY_MSG = "TVCAST_TV";

    public interface Handler {
        /** Returns a JSON reply. Called on a background thread. */
        String handle(String command, Map<String, String> params);
    }

    private final Handler handler;
    private final String name;
    private final int httpPort, udpPort;
    private volatile boolean alive = true;
    private ServerSocket serverSocket;
    private DatagramSocket udp;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public CommandServer(String name, Handler handler) {
        this(name, handler, HTTP_PORT, DISCOVERY_PORT);
    }

    public CommandServer(String name, Handler handler, int httpPort, int udpPort) {
        this.name = name;
        this.handler = handler;
        this.httpPort = httpPort;
        this.udpPort = udpPort;
    }

    public void start() {
        Thread http = new Thread(this::runHttp, "TVCast-cmd");
        http.setDaemon(true);
        http.start();
        Thread disc = new Thread(this::runDiscovery, "TVCast-discovery");
        disc.setDaemon(true);
        disc.start();
    }

    public void stop() {
        alive = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (udp != null) udp.close();
        pool.shutdownNow();
    }

    private void runHttp() {
        while (alive) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(httpPort));
                while (alive) {
                    Socket s = serverSocket.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                if (!alive) return;
                sleep(2000); // port busy (e.g. old instance closing) - retry
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String req = in.readLine();
            if (req == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // ignore headers
            }
            String[] parts = req.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            String cmd = path.startsWith("/") ? path.substring(1) : path;
            String body;
            if (cmd.equals("info")) {
                body = "{\"app\":\"tvcast-tv\",\"name\":" + json(name) + ",\"version\":1}";
            } else {
                body = handler.handle(cmd, parseQuery(query));
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nAccess-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(b);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void runDiscovery() {
        while (alive) {
            try {
                udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(udpPort));
                byte[] buf = new byte[512];
                while (alive) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udp.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    if (!msg.startsWith(DISCOVER_MSG)) continue;
                    byte[] reply = (REPLY_MSG + "\n" + name + "\n" + httpPort).getBytes(StandardCharsets.UTF_8);
                    udp.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            } catch (IOException e) {
                if (!alive) return;
                if (udp != null) udp.close();
                sleep(2000);
            }
        }
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) {
                    m.put(URLDecoder.decode(kv, "UTF-8"), "");
                } else {
                    m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    public static String json(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
