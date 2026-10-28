package com.tvcast.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relays a live TV stream (HLS) from the internet to the TV through the phone.
 * Playlists are rewritten so every segment also goes through the phone. This lets us send the
 * Referer / User-Agent some channels require, and avoids browser cross-origin blocks on the TV.
 * No Android APIs here, so it can be tested on a plain JVM.
 */
public final class LiveProxy {

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0 Mobile Safari/537.36";

    /** One possible source for a channel. */
    public static final class Stream {
        public final String url, referrer, userAgent;

        public Stream(String url, String referrer, String userAgent) {
            this.url = url;
            this.referrer = referrer == null ? "" : referrer;
            this.userAgent = userAgent == null ? "" : userAgent;
        }
    }

    /** A live channel being played; may have several sources to fall back on. */
    public static final class Channel {
        public final String title;
        public final List<Stream> streams;
        public final String token;
        private volatile int index;

        public Channel(String title, List<Stream> streams, String token) {
            this.title = title;
            this.streams = streams;
            this.token = token;
        }

        public Stream current() {
            return streams.get(Math.min(index, streams.size() - 1));
        }

        public int index() {
            return index;
        }

        /** Switches to the next source; false if there is none. */
        public boolean next() {
            if (index + 1 >= streams.size()) return false;
            index++;
            return true;
        }
    }

    private LiveProxy() {
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    /**
     * Serves one proxied request.
     * @param upstreamUrl absolute URL to fetch
     * @param prefix      path prefix for rewritten URLs, e.g. "/live/TOKEN/p?u="
     */
    public static void serve(OutputStream out, Stream s, String upstreamUrl, String prefix,
                             String range, boolean head) throws IOException {
        HttpURLConnection c = null;
        try {
            String url = upstreamUrl;
            int code = 0;
            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", s.userAgent.isEmpty() ? DEFAULT_UA : s.userAgent);
                if (!s.referrer.isEmpty()) {
                    c.setRequestProperty("Referer", s.referrer);
                    c.setRequestProperty("Origin", origin(s.referrer));
                }
                c.setRequestProperty("Accept", "*/*");
                if (range != null) c.setRequestProperty("Range", range);
                code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    c = null;
                    continue;
                }
                break;
            }
            if (c == null || code >= 400 || code == 0) {
                error(out, 502, "upstream " + code);
                return;
            }

            String type = c.getContentType();
            InputStream raw = c.getInputStream();
            BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024);
            in.mark(16);
            byte[] peek = new byte[7];
            int got = 0;
            while (got < peek.length) {
                int n = in.read(peek, got, peek.length - got);
                if (n < 0) break;
                got += n;
            }
            in.reset();
            boolean playlist = (got == 7 && new String(peek, StandardCharsets.ISO_8859_1).equals("#EXTM3U"))
                    || (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl"));

            if (playlist) {
                String text = readText(in, 8 * 1024 * 1024);
                byte[] body = rewrite(text, url, prefix).getBytes(StandardCharsets.UTF_8);
                String h = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/vnd.apple.mpegurl\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(h.getBytes(StandardCharsets.ISO_8859_1));
                if (!head) out.write(body);
                out.flush();
                return;
            }

            StringBuilder h = new StringBuilder("HTTP/1.1 ")
                    .append(code == 206 ? "206 Partial Content" : "200 OK").append("\r\n");
            h.append("Content-Type: ").append(type != null ? type : "video/mp2t").append("\r\n");
            long len = c.getContentLengthLong();
            if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!head) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            error(out, 502, "upstream error");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Rewrites every URL in an HLS playlist so it goes through the phone. */
    static String rewrite(String text, String baseUrl, String prefix) {
        StringBuilder sb = new StringBuilder(text.length() + 1024);
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
            } else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(t);
                StringBuffer r = new StringBuffer();
                while (m.find()) {
                    m.appendReplacement(r, Matcher.quoteReplacement(
                            "URI=\"" + prefix + enc(resolve(baseUrl, m.group(1))) + "\""));
                }
                m.appendTail(r);
                sb.append(r).append('\n');
            } else {
                sb.append(prefix).append(enc(resolve(baseUrl, t))).append('\n');
            }
        }
        return sb.toString();
    }

    static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static String origin(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readText(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > max) throw new IOException("playlist too large");
        }
        return bo.toString("UTF-8");
    }

    private static void error(OutputStream out, int code, String msg) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            String h = "HTTP/1.1 " + code + " Error\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: " + b.length + "\r\nAccess-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.ISO_8859_1));
            out.write(b);
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
