package com.tvcast.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Minimal UPnP/DLNA "Digital Media Controller": finds TVs on the Wi-Fi (SSDP) and tells them
 * to play a URL in their own media player (AVTransport). Samsung Smart TVs support this.
 * No Android APIs here, so it can be tested on a plain JVM.
 */
public final class Dlna {

    private Dlna() {
    }

    public static final class Renderer {
        public final String name;
        public final String location;
        public final String avControlUrl;
        public final String avServiceType;

        Renderer(String name, String location, String avControlUrl, String avServiceType) {
            this.name = name;
            this.location = location;
            this.avControlUrl = avControlUrl;
            this.avServiceType = avServiceType;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final String SSDP_ADDR = "239.255.255.250";
    static final int SSDP_PORT = 1900;

    // ---------- discovery ----------

    public static List<Renderer> discover(int timeoutMs) {
        Set<String> locations = new LinkedHashSet<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(250);
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
            String[] targets = {
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
            };
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            int rounds = 0;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                if (rounds < 3 && System.currentTimeMillis() >= nextSend) {
                    for (String st : targets) {
                        byte[] m = mSearch(st).getBytes(StandardCharsets.ISO_8859_1);
                        try {
                            sock.send(new DatagramPacket(m, m.length, group));
                        } catch (IOException ignored) {
                        }
                    }
                    rounds++;
                    nextSend = System.currentTimeMillis() + 900;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String loc = header(new String(p.getData(), 0, p.getLength(), StandardCharsets.ISO_8859_1),
                        "location");
                if (loc != null && !loc.isEmpty()) locations.add(loc);
            }
        } catch (IOException ignored) {
        }

        List<Renderer> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String loc : locations) {
            try {
                Renderer r = parseDescription(loc, httpGet(loc, 3000));
                if (r != null && seen.add(r.avControlUrl)) out.add(r);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static String mSearch(String st) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n"
                + "USER-AGENT: Android/1.0 UPnP/1.0 TVCast/1.0\r\n\r\n";
    }

    static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    /** Reads a device description XML; returns null if it has no AVTransport service. */
    static Renderer parseDescription(String location, String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        try {
            f.setExpandEntityReferences(false);
        } catch (Exception ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        String base = location;
        String urlBase = firstText(doc.getDocumentElement(), "URLBase");
        if (urlBase != null && !urlBase.isEmpty()) base = urlBase;

        String name = firstText(doc.getDocumentElement(), "friendlyName");
        if (name == null || name.isEmpty()) name = new URL(location).getHost();

        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String type = firstText(s, "serviceType");
            String control = firstText(s, "controlURL");
            if (type != null && control != null && type.contains(":AVTransport:")) {
                String controlUrl = new URL(new URL(base), control.trim()).toString();
                return new Renderer(name.trim(), location, controlUrl, type.trim());
            }
        }
        return null;
    }

    private static String firstText(Element root, String tag) {
        NodeList nl = root.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return n.getTextContent();
    }

    // ---------- control ----------

    /** Tells the TV to open the URL in its media player and start playing. */
    public static void load(Renderer r, String url, String title, String mime, long size) throws IOException {
        try {
            stop(r);
        } catch (IOException ignored) {
            // nothing was playing
        }
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""
                + " xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + xml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + xml(mime) + ":" + CastServer.DLNA_FEATURES + "\""
                + (size > 0 ? " size=\"" + size + "\"" : "") + ">"
                + xml(url) + "</res></item></DIDL-Lite>";
        soap(r, "SetAVTransportURI", "<InstanceID>0</InstanceID>"
                + "<CurrentURI>" + xml(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + xml(didl) + "</CurrentURIMetaData>");

        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            sleep(attempt == 0 ? 400 : 900);
            try {
                play(r);
                return;
            } catch (IOException e) {
                last = e; // TV still loading ("transition not available"); retry
            }
        }
        throw last;
    }

    public static void play(Renderer r) throws IOException {
        soap(r, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static void pause(Renderer r) throws IOException {
        soap(r, "Pause", "<InstanceID>0</InstanceID>");
    }

    public static void stop(Renderer r) throws IOException {
        soap(r, "Stop", "<InstanceID>0</InstanceID>");
    }

    public static void seek(Renderer r, int seconds) throws IOException {
        soap(r, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>"
                + hms(seconds) + "</Target>");
    }

    /** Returns {position, duration} in seconds (-1 when unknown). */
    public static int[] position(Renderer r) throws IOException {
        String resp = soap(r, "GetPositionInfo", "<InstanceID>0</InstanceID>");
        return new int[]{parseHms(tag(resp, "RelTime")), parseHms(tag(resp, "TrackDuration"))};
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT ... */
    public static String transportState(Renderer r) throws IOException {
        String s = tag(soap(r, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState");
        return s == null ? "" : s.trim();
    }

    static String soap(Renderer r, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + r.avServiceType + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(r.avControlUrl).openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + r.avServiceType + "#" + action + "\"");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(bytes);
            }
            int code = c.getResponseCode();
            String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String desc = tag(resp, "errorDescription");
                String err = tag(resp, "errorCode");
                throw new IOException(action + " error " + (err != null ? err : code)
                        + (desc != null ? " " + desc : ""));
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }

    // ---------- helpers ----------

    static String httpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + name + "(?:\\s[^>]*)?>([^<]*)</", Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    static String hms(int sec) {
        sec = Math.max(0, sec);
        return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    static int parseHms(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty() || s.startsWith("NOT")) return -1;
        try {
            String[] p = s.split(":");
            double total = 0;
            for (String part : p) total = total * 60 + Double.parseDouble(part);
            return (int) total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
