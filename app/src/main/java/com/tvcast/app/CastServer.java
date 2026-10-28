package com.tvcast.app;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small HTTP server running on the phone.
 *   GET /             -> TV page (mirror view or full-screen video player, switches automatically)
 *   GET /status       -> JSON state polled by the TV page (also carries the TV's playback position)
 *   GET /stream       -> MJPEG screen-mirror stream
 *   GET /frame.jpg    -> single mirror frame (fallback)
 *   GET|HEAD /video/N -> the selected video file, with byte-range support (seeking) and DLNA headers
 * No Android APIs here, so it can be tested on a plain JVM.
 */
public class CastServer extends Thread {

    /** A video file the phone can serve. */
    public interface VideoSource {
        String title();
        String mimeType();
        /** Size in bytes, or -1 if unknown. */
        long length();
        InputStream openAt(long offset) throws IOException;
    }

    public static final String MODE_IDLE = "idle";
    public static final String MODE_MIRROR = "mirror";
    /** Video should play in the TV web browser page. */
    public static final String MODE_VIDEO = "video";
    /** Video is playing in the TV's own media player (DLNA); the browser page just shows a note. */
    public static final String MODE_EXTERNAL = "external";

    public static final String DLNA_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    private static final String BOUNDARY = "tvcastframe";

    private final int port;
    private volatile boolean alive = true;
    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> openSockets = Collections.synchronizedSet(new HashSet<>());

    // Mirror frames
    private final Object frameLock = new Object();
    private byte[] latestFrame;
    private long frameSeq;
    private final AtomicInteger mirrorViewers = new AtomicInteger();

    // Mode / video
    private volatile String mode = MODE_IDLE;
    private volatile VideoSource video;
    private volatile int videoId;

    // Commands for the TV browser player
    private final Object cmdLock = new Object();
    private int cmdSeq;
    private String cmd = "";

    // Playback state reported by the TV browser
    private volatile double tvPos = -1, tvDur = -1;
    private volatile boolean tvPaused = true;
    private volatile long tvReportAt;

    public CastServer(int port) {
        super("TVCast-HTTP");
        this.port = port;
        setDaemon(true);
    }

    // ---------- control API (called by the app) ----------

    public void setMode(String m) {
        mode = m;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    public String mode() {
        return mode;
    }

    public void setVideo(VideoSource v) {
        video = v;
        videoId++;
        tvPos = -1;
        tvDur = -1;
        tvPaused = true;
        tvReportAt = 0;
    }

    /** Path of the current video, e.g. /video/3.mp4 */
    public String videoPath() {
        VideoSource v = video;
        return "/video/" + videoId + extensionFor(v == null ? null : v.mimeType());
    }

    public void sendCommand(String c) {
        synchronized (cmdLock) {
            cmdSeq++;
            cmd = c;
        }
    }

    public int mirrorViewerCount() {
        return mirrorViewers.get();
    }

    public boolean tvConnected() {
        return System.currentTimeMillis() - tvReportAt < 4000;
    }

    public double tvPosition() {
        return tvPos;
    }

    public double tvDuration() {
        return tvDur;
    }

    public boolean tvPaused() {
        return tvPaused;
    }

    /** Called by the capture code each time a new mirror JPEG frame is ready. */
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
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            openSockets.clear();
        }
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        pool.shutdownNow();
    }

    // ---------- HTTP ----------

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            while (alive) {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(20000);
                openSockets.add(s);
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            // closed during shutdown, or port unavailable
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT),
                            line.substring(c + 1).trim());
                }
            }
            s.setSoTimeout(0);
            String[] parts = requestLine.split(" ");
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            boolean head = method.equals("HEAD");

            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            if (path.equals("/") || path.equals("/index.html")) {
                sendBytes(out, "200 OK", "text/html; charset=utf-8",
                        TV_PAGE.getBytes(StandardCharsets.UTF_8), head);
            } else if (path.equals("/status")) {
                handleReport(query);
                sendBytes(out, "200 OK", "application/json; charset=utf-8",
                        statusJson().getBytes(StandardCharsets.UTF_8), head);
            } else if (path.equals("/stream")) {
                streamMjpeg(out);
            } else if (path.equals("/frame.jpg")) {
                byte[] f = waitForFrame(3000);
                if (f == null) {
                    sendBytes(out, "503 Service Unavailable", "text/plain", "no frame yet".getBytes(), head);
                } else {
                    sendBytes(out, "200 OK", "image/jpeg", f, head);
                }
            } else if (path.startsWith("/video/")) {
                serveVideo(out, path, headers, head);
            } else {
                sendBytes(out, "404 Not Found", "text/plain", "not found".getBytes(), head);
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            openSockets.remove(socket);
        }
    }

    // ---------- status / report ----------

    private void handleReport(String query) {
        if (query.isEmpty() || !MODE_VIDEO.equals(mode)) return;
        Map<String, String> p = parseQuery(query);
        try {
            if (p.containsKey("pos")) tvPos = Double.parseDouble(p.get("pos"));
            if (p.containsKey("dur")) tvDur = Double.parseDouble(p.get("dur"));
            if (p.containsKey("paused")) tvPaused = "1".equals(p.get("paused"));
            if (p.containsKey("pos")) tvReportAt = System.currentTimeMillis();
        } catch (NumberFormatException ignored) {
        }
    }

    private String statusJson() {
        int seq;
        String c;
        synchronized (cmdLock) {
            seq = cmdSeq;
            c = cmd;
        }
        VideoSource v = video;
        return "{\"mode\":\"" + mode + "\""
                + ",\"videoId\":" + videoId
                + ",\"url\":" + jsonString(v != null ? videoPath() : "")
                + ",\"title\":" + jsonString(v != null ? v.title() : "")
                + ",\"cmdSeq\":" + seq
                + ",\"cmd\":" + jsonString(c)
                + "}";
    }

    // ---------- video with byte ranges ----------

    private void serveVideo(OutputStream out, String path, Map<String, String> headers, boolean head)
            throws IOException {
        VideoSource v = video;
        int id = parseVideoId(path);
        if (v == null || id != videoId) {
            sendBytes(out, "404 Not Found", "text/plain", "no video".getBytes(), head);
            return;
        }
        long len = v.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        String range = headers.get("range");
        if (range != null && len > 0 && range.toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            try {
                String r = range.substring(6).split(",")[0].trim();
                int dash = r.indexOf('-');
                String a = r.substring(0, dash).trim();
                String b = r.substring(dash + 1).trim();
                if (a.isEmpty()) {
                    start = Math.max(0, len - Long.parseLong(b));
                } else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(len - 1, Long.parseLong(b));
                }
                partial = true;
            } catch (RuntimeException e) {
                start = 0;
                end = len - 1;
                partial = false;
            }
            if (partial && (start >= len || start > end)) {
                String h = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + len + "\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return;
            }
        }

        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(v.mimeType()).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (len >= 0) h.append("Content-Length: ").append(end - start + 1).append("\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(len).append("\r\n");
        }
        h.append("transferMode.dlna.org: Streaming\r\n");
        h.append("contentFeatures.dlna.org: ").append(DLNA_FEATURES).append("\r\n");
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        if (head) return;

        try (InputStream in = v.openAt(start)) {
            byte[] buf = new byte[64 * 1024];
            long remaining = len >= 0 ? end - start + 1 : Long.MAX_VALUE;
            while (remaining > 0 && alive) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    private static int parseVideoId(String path) {
        String rest = path.substring("/video/".length());
        int dot = rest.indexOf('.');
        if (dot >= 0) rest = rest.substring(0, dot);
        try {
            return Integer.parseInt(rest);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String extensionFor(String mime) {
        if (mime == null) return ".mp4";
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "video/x-matroska":
            case "video/x-mkv":
                return ".mkv";
            case "video/webm":
                return ".webm";
            case "video/quicktime":
                return ".mov";
            case "video/3gpp":
                return ".3gp";
            case "video/x-msvideo":
            case "video/avi":
                return ".avi";
            case "video/mp2t":
                return ".ts";
            default:
                return ".mp4";
        }
    }

    // ---------- mirror ----------

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
        mirrorViewers.incrementAndGet();
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
            while (alive && MODE_MIRROR.equals(mode)) {
                byte[] frame;
                synchronized (frameLock) {
                    if (frameSeq == lastSeq || latestFrame == null) {
                        frameLock.wait(1000); // static screen: resend last frame every second
                    }
                    if (!alive || !MODE_MIRROR.equals(mode)) break;
                    frame = latestFrame;
                    lastSeq = frameSeq;
                }
                if (frame == null) continue;
                String partHeader = "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.length + "\r\n\r\n";
                out.write(partHeader.getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame);
                out.write(("\r\n--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } finally {
            mirrorViewers.decrementAndGet();
        }
    }

    // ---------- helpers ----------

    private static void sendBytes(OutputStream out, String status, String type, byte[] body, boolean head)
            throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        if (!head) out.write(body);
        out.flush();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                        URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>') {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    // ---------- the page the TV browser opens ----------

    static final String TV_PAGE = """
<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV Cast</title>
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden;cursor:none;color:#c9d1d9;font-family:sans-serif}
#img,#vid{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:contain;background:#000;display:none}
#msg{position:fixed;left:0;right:0;top:50%;transform:translateY(-50%);text-align:center;font-size:32px;line-height:1.5;padding:0 6%}
#tap{position:fixed;left:50%;bottom:10%;transform:translateX(-50%);background:#1f6feb;color:#fff;font-size:30px;padding:18px 36px;border-radius:14px;display:none}
#ttl{position:fixed;left:4%;top:5%;font-size:28px;color:#fff;text-shadow:0 2px 8px #000;display:none}
</style></head><body>
<img id="img" alt=""><video id="vid" playsinline preload="auto"></video>
<div id="msg">Connecting to your phone…</div>
<div id="tap">Press OK on the remote to start the video</div>
<div id="ttl"></div>
<script>
var img=document.getElementById('img'),vid=document.getElementById('vid'),msg=document.getElementById('msg'),
    tap=document.getElementById('tap'),ttl=document.getElementById('ttl');
var mode='',vidId=-1,lastSeq=-1,mjpegOk=false,polling=false,titleTimer=null,failures=0;
function show(el,on){el.style.display=on?'block':'none';}
function say(t){msg.innerHTML=t;show(msg,!!t);}

function stopMirror(){img.onload=null;img.onerror=null;img.removeAttribute('src');show(img,false);polling=false;}
function startMirror(){
  show(img,true);say('');mjpegOk=false;
  img.onload=function(){mjpegOk=true;};
  img.onerror=function(){if(mode==='mirror')startPolling();};
  img.src='/stream?t='+Date.now();
  setTimeout(function(){if(mode==='mirror'&&!mjpegOk)startPolling();},4000);
}
function startPolling(){
  if(polling)return;polling=true;img.onload=null;img.onerror=null;
  (function p(){
    if(!polling||mode!=='mirror'){polling=false;return;}
    var i=new Image();
    i.onload=function(){img.src=i.src;setTimeout(p,30);};
    i.onerror=function(){setTimeout(p,1000);};
    i.src='/frame.jpg?t='+Date.now();
  })();
}

function tryPlay(){
  var pr=vid.play();
  if(pr&&pr.then){pr.then(function(){show(tap,false);},function(){show(tap,true);});}
}
function flashTitle(t){
  ttl.textContent=t||'';show(ttl,!!t);clearTimeout(titleTimer);
  titleTimer=setTimeout(function(){show(ttl,false);},5000);
}
function startVideo(s){
  stopMirror();vidId=s.videoId;vid.src=s.url;show(vid,true);say('');flashTitle(s.title);tryPlay();
}
function stopVideo(){
  if(vidId<0)return;
  vid.pause();vid.removeAttribute('src');try{vid.load();}catch(e){}
  show(vid,false);show(tap,false);vidId=-1;
}
vid.onplaying=function(){show(tap,false);};
vid.onerror=function(){
  if(mode==='video'&&vid.getAttribute('src'))
    say('The TV browser can’t play this video format.<br>On your phone, choose the TV’s own player instead,<br>or use an MP4 video.');
};
function runCmd(c){
  if(c==='play')tryPlay();
  else if(c==='pause')vid.pause();
  else if(c.indexOf('seek:')===0){var t=parseFloat(c.substr(5));if(!isNaN(t))vid.currentTime=t;}
}

function goFull(){
  var e=document.documentElement,f=e.requestFullscreen||e.webkitRequestFullscreen;
  if(f&&!document.fullscreenElement&&!document.webkitFullscreenElement){try{f.call(e);}catch(x){}}
}
document.addEventListener('keydown',function(e){
  goFull();
  if(mode!=='video')return;
  var k=e.keyCode;
  if(k===13||k===415||k===10252){if(vid.paused)tryPlay();}
  else if(k===19){vid.pause();}
  else if(k===37||k===412){vid.currentTime=Math.max(0,vid.currentTime-10);}
  else if(k===39||k===417){vid.currentTime=vid.currentTime+10;}
});
document.addEventListener('click',function(){goFull();if(mode==='video'&&vid.paused)tryPlay();});

function apply(s){
  if(lastSeq<0)lastSeq=s.cmdSeq;
  var prev=mode;mode=s.mode;
  if(mode==='mirror'){
    stopVideo();
    if(prev!=='mirror')startMirror();else say('');
  }else if(mode==='video'){
    if(prev==='mirror')stopMirror();
    if(s.videoId!==vidId)startVideo(s);else if(msg.style.display!=='none'&&!vid.error)say('');
  }else{
    if(prev==='mirror')stopMirror();
    stopVideo();
    say(mode==='external'?'Playing on the TV’s media player…'
      :'Ready.<br>On your phone, open TV Cast and choose a video<br>or start mirroring.');
  }
  if(s.cmdSeq>lastSeq){lastSeq=s.cmdSeq;if(mode==='video')runCmd(s.cmd);}
}
function poll(){
  var q='';
  if(mode==='video'&&vidId>=0){
    q='?pos='+(vid.currentTime||0).toFixed(1)+'&dur='+(isFinite(vid.duration)?vid.duration.toFixed(1):'-1')
      +'&paused='+(vid.paused?1:0);
  }
  var x=new XMLHttpRequest();
  x.open('GET','/status'+q+(q?'&':'?')+'t='+Date.now(),true);
  x.timeout=4000;
  x.onload=function(){failures=0;try{apply(JSON.parse(x.responseText));}catch(e){}setTimeout(poll,700);};
  x.onerror=x.ontimeout=function(){
    failures++;
    if(failures>2&&mode!=='video'){if(mode==='mirror')stopMirror();mode='lost';
      say('Lost connection to the phone.<br>Make sure TV Cast is running and on the same Wi-Fi.');}
    setTimeout(poll,1500);
  };
  x.send();
}
poll();
</script></body></html>
""";
}
