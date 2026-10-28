package com.tvcast.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads the phone's screen-mirror stream (a sequence of JPEG frames) and hands out each frame. */
final class MjpegReader extends Thread {

    interface Listener {
        void onFrame(Bitmap frame);

        void onConnectionChanged(boolean connected);
    }

    private final String url;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile HttpURLConnection conn;

    MjpegReader(String url, Listener listener) {
        super("TVCast-mirror");
        this.url = url;
        this.listener = listener;
        setDaemon(true);
    }

    void shutdown() {
        running = false;
        HttpURLConnection c = conn;
        if (c != null) new Thread(c::disconnect).start(); // no network work on the UI thread
        interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                conn = c;
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                InputStream in = new BufferedInputStream(c.getInputStream(), 128 * 1024);
                listener.onConnectionChanged(true);
                int length = -1;
                while (running) {
                    String line = readLine(in);
                    if (line == null) break;
                    if (line.isEmpty()) {
                        if (length > 0) {
                            byte[] data = new byte[length];
                            readFully(in, data);
                            Bitmap b = BitmapFactory.decodeByteArray(data, 0, length);
                            if (b != null) listener.onFrame(b);
                            length = -1;
                        }
                    } else if (line.regionMatches(true, 0, "content-length:", 0, 15)) {
                        try {
                            length = Integer.parseInt(line.substring(15).trim());
                        } catch (NumberFormatException e) {
                            length = -1;
                        }
                    }
                }
            } catch (IOException e) {
                // reconnect below
            } finally {
                HttpURLConnection c = conn;
                if (c != null) c.disconnect();
            }
            if (!running) break;
            listener.onConnectionChanged(false);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 4096) sb.setLength(0);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("stream ended");
            off += n;
        }
    }
}
