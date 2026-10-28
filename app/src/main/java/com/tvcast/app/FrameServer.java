package com.tvcast.app;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP server that serves the latest JPEG frame of the phone screen.
 *   GET /           -> full-screen viewer page for the TV browser
 *   GET /stream     -> MJPEG stream (multipart/x-mixed-replace)
 *   GET /frame.jpg  -> single latest frame (fallback for browsers without MJPEG)
 * Kept free of Android APIs so it can be tested on any JVM.
 */
public class FrameServer extends Thread {

    private static final String BOUNDARY = "tvcastframe";

    private final int port;
    private volatile boolean alive = true;
    private ServerSocket serverSocket;

    private final Object frameLock = new Object();
    private byte[] latestFrame;
    private long frameSeq;

    private final AtomicInteger viewers = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> openSockets = Collections.synchronizedSet(new HashSet<>());

    public FrameServer(int port) {
        super("TVCast-HTTP");
        this.port = port;
        setDaemon(true);
    }

    /** Number of TV browsers currently watching the stream. */
    public int viewerCount() {
        return viewers.get();
    }

    /** Called by the capture code each time a new JPEG frame is ready. */
    public void publish(byte[] jpeg) {
        synchronized (frameLock) {
            latestFrame = jpeg;
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    public void shutdown() {
        alive = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        synchronized (openSockets) {
            for (Socket s : openSockets) {
                try { s.close(); } catch (IOException ignored) { }
            }
            openSockets.clear();
        }
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            while (alive) {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                openSockets.add(s);
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            // Socket closed during shutdown, or port unavailable.
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // ignore request headers
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            switch (path) {
                case "/":
                case "/index.html":
                    sendBytes(out, "200 OK", "text/html; charset=utf-8",
                            VIEWER_PAGE.getBytes(StandardCharsets.UTF_8));
                    break;
                case "/stream":
                    streamMjpeg(out);
                    break;
                case "/frame.jpg":
                    byte[] f = waitForFrame(3000);
                    if (f == null) {
                        sendBytes(out, "503 Service Unavailable", "text/plain", "no frame yet".getBytes());
                    } else {
                        sendBytes(out, "200 OK", "image/jpeg", f);
                    }
                    break;
                default:
                    sendBytes(out, "404 Not Found", "text/plain", "not found".getBytes());
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            openSockets.remove(socket);
        }
    }

    private byte[] waitForFrame(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (alive && latestFrame == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                frameLock.wait(left);
            }
            return latestFrame;
        }
    }

    private void streamMjpeg(OutputStream out) throws IOException, InterruptedException {
        viewers.incrementAndGet();
        try {
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n"
                    + "--" + BOUNDARY + "\r\n";
            out.write(header.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            long lastSeq = -1;
            while (alive) {
                byte[] frame;
                synchronized (frameLock) {
                    if (frameSeq == lastSeq || latestFrame == null) {
                        // Wait for a new frame; if the screen is static, resend the
                        // last one every second to keep the connection alive.
                        frameLock.wait(1000);
                    }
                    if (!alive) break;
                    frame = latestFrame;
                    lastSeq = frameSeq;
                }
                if (frame == null) continue;
                String partHeader = "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.length + "\r\n\r\n";
                out.write(partHeader.getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame);
                // Boundary right after the frame so browsers render it immediately.
                out.write(("\r\n--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            viewers.decrementAndGet();
        }
    }

    private static void sendBytes(OutputStream out, String status, String type, byte[] body)
            throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static final String VIEWER_PAGE = "<!doctype html>\n"
            + "<html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>TV Cast</title><style>"
            + "html,body{margin:0;height:100%;background:#000;overflow:hidden;cursor:none}"
            + "img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;display:block}"
            + "#msg{position:fixed;left:0;right:0;bottom:8%;text-align:center;color:#9aa;"
            + "font:28px/1.4 sans-serif}"
            + "</style></head><body>"
            + "<img id=\"v\" alt=\"\"><div id=\"msg\">Connecting to phone…</div>"
            + "<script>\n"
            + "var v=document.getElementById('v'),msg=document.getElementById('msg');\n"
            + "var mode='mjpeg',got=false;\n"
            + "function show(t){msg.textContent=t;msg.style.display=t?'block':'none';}\n"
            + "function poll(){var i=new Image();\n"
            + " i.onload=function(){v.src=i.src;show('');setTimeout(poll,30);};\n"
            + " i.onerror=function(){show('Waiting for phone… (is TV Cast running?)');setTimeout(poll,1500);};\n"
            + " i.src='/frame.jpg?t='+Date.now();}\n"
            + "function toPoll(){if(mode!=='poll'){mode='poll';v.onerror=null;poll();}}\n"
            + "v.onload=function(){got=true;show('');};\n"
            + "v.onerror=function(){toPoll();};\n"
            + "v.src='/stream?t='+Date.now();\n"
            + "setTimeout(function(){if(!got)toPoll();},4000);\n"
            + "</script></body></html>";
}
