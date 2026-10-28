package com.tvcast.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs in the background (you can use other apps) and sends one thing at a time to the TV:
 * a video from the phone, a live channel, or the phone's screen.
 * The phone serves the media on port 8080; the TV (TV Cast app, TV media player or browser) plays it.
 */
public class CastService extends Service {

    public static final String ACTION_VIDEO = "com.tvcast.app.VIDEO";
    public static final String ACTION_LIVE = "com.tvcast.app.LIVE";
    public static final String ACTION_MIRROR = "com.tvcast.app.MIRROR";
    public static final String ACTION_STOP = "com.tvcast.app.STOP";

    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_REFS = "refs";
    public static final String EXTRA_UAS = "uas";

    public static final int PORT = 8080;

    public static final String KIND_VIDEO = "video", KIND_LIVE = "live", KIND_MIRROR = "mirror";

    // Mirror quality
    private static final int MAX_LONG_SIDE = 1280;
    private static final int JPEG_QUALITY = 65;
    private static final long FRAME_INTERVAL_MS = 40;
    private static final long IDLE_FRAME_INTERVAL_MS = 1000;

    private static final String CHANNEL_ID = "tvcast";
    private static final int NOTIFICATION_ID = 1;

    // ---- state shown on the phone screens ----
    static volatile CastService instance;
    static volatile String kind = "";          // what is playing: video / live / mirror / ""
    static volatile String title = "";
    static volatile String status = "";
    static volatile String targetLabel = "";
    static volatile boolean ok;                // status is a good one (green)
    static volatile int positionSec = -1, durationSec = -1;
    static volatile boolean paused;
    static volatile String lastError;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CastServer server;
    private ScheduledExecutorService worker;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private volatile Target target;
    private volatile String playId = "";
    private int playCounter;
    private long startedAt;
    private long lastDeliverAt;
    private boolean wasPlaying;
    private int appFailures;
    private boolean liveGaveUp;

    // mirror
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private int width, height, densityDpi;
    private long lastFrameAt;
    private Bitmap scratchBitmap;
    private ByteBuffer paddedBuffer;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);
    private volatile boolean capturing;

    static boolean isActive() {
        return instance != null && !kind.isEmpty();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        server = PhoneServer.get(this);
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVCast");
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVCast:serve");
        wakeLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (action == null || ACTION_STOP.equals(action)) {
            stopEverything(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Target t = Target.fromSpec(intent.getStringExtra(EXTRA_TARGET));
        if (t == null) t = Target.browser();
        if (t.type == Target.DLNA && ACTION_MIRROR.equals(action)) t = Target.browser();

        // Stop whatever was playing (but don't tell the TV app to go idle; the new item replaces it).
        Target old = target;
        boolean sameTv = old != null && old.type == Target.APP && t.type == Target.APP
                && old.tv.host.equals(t.tv.host);
        if (old != null && !sameTv) stopOnTarget(old);
        clearMedia();

        target = t;
        targetLabel = t.label();
        lastError = null;
        startedAt = SystemClock.uptimeMillis();
        wasPlaying = false;
        appFailures = 0;
        liveGaveUp = false;
        positionSec = -1;
        durationSec = -1;
        paused = false;
        ok = false;

        try {
            switch (action) {
                case ACTION_VIDEO: {
                    goForeground(false);
                    Uri uri = intent.getData();
                    UriVideoSource src = new UriVideoSource(getContentResolver(), uri);
                    server.setVideo(src);
                    kind = KIND_VIDEO;
                    title = src.title();
                    deliver();
                    break;
                }
                case ACTION_LIVE: {
                    goForeground(false);
                    String[] urls = intent.getStringArrayExtra(EXTRA_URLS);
                    String[] refs = intent.getStringArrayExtra(EXTRA_REFS);
                    String[] uas = intent.getStringArrayExtra(EXTRA_UAS);
                    if (urls == null || urls.length == 0) throw new IOException("no stream");
                    List<LiveProxy.Stream> streams = new ArrayList<>();
                    for (int i = 0; i < urls.length; i++) {
                        streams.add(new LiveProxy.Stream(urls[i],
                                refs != null && i < refs.length ? refs[i] : "",
                                uas != null && i < uas.length ? uas[i] : ""));
                    }
                    String name = intent.getStringExtra(EXTRA_TITLE);
                    if (name == null) name = "Live TV";
                    String token = Long.toHexString(Double.doubleToLongBits(Math.random()))
                            + Long.toHexString(System.nanoTime());
                    server.setLive(new LiveProxy.Channel(name, streams, token));
                    kind = KIND_LIVE;
                    title = name;
                    deliver();
                    break;
                }
                case ACTION_MIRROR: {
                    goForeground(true); // must be before getMediaProjection()
                    int code = intent.getIntExtra(EXTRA_CODE, 0);
                    Intent data = Build.VERSION.SDK_INT >= 33
                            ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                            : intent.getParcelableExtra(EXTRA_DATA);
                    startCapture(code, data);
                    kind = KIND_MIRROR;
                    title = "Your phone screen";
                    server.setMode(CastServer.MODE_MIRROR);
                    deliver();
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            lastError = "Couldn't start: " + e.getMessage();
            clearMedia();
            kind = "";
            stopSelf();
            return START_NOT_STICKY;
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    /** Sends the current item to the current target. */
    private void deliver() {
        final Target t = target;
        final String k = kind;
        final String name = title;
        final String id = String.valueOf(++playCounter);
        playId = id;
        lastDeliverAt = SystemClock.uptimeMillis();
        String base = serverUrl();
        if (base == null) {
            setStatus("Your phone isn't on Wi-Fi", false);
            return;
        }
        final String url;
        final String mime;
        switch (k) {
            case KIND_VIDEO:
                url = base + server.videoPath();
                mime = server.videoMime();
                break;
            case KIND_LIVE:
                url = base + server.livePath() + "?s=" + id; // fresh URL each source switch
                mime = "application/vnd.apple.mpegurl";
                break;
            default:
                url = base + "/stream";
                mime = "";
                break;
        }

        if (t.type == Target.APP) {
            if (!KIND_MIRROR.equals(k)) server.setMode(CastServer.MODE_IDLE);
            setStatus("Sending to " + t.tv.name + "…", false);
            worker.execute(() -> {
                try {
                    TvApps.play(t.tv, k, url, name, id);
                } catch (IOException e) {
                    if (target == t) {
                        setStatus("Can't reach TV Cast on " + t.tv.name
                                + ". Make sure the TV is on and the TV Cast app is open.", false);
                    }
                }
            });
        } else if (t.type == Target.DLNA) {
            server.setMode(CastServer.MODE_EXTERNAL);
            setStatus("Sending to " + t.renderer.name + "… (if the TV asks, choose Allow)", false);
            final long size = KIND_VIDEO.equals(k) ? server.videoLength() : -1;
            worker.execute(() -> {
                try {
                    Dlna.load(t.renderer, url, name, mime, size);
                } catch (IOException e) {
                    if (target == t) {
                        setStatus("The TV player didn't accept it (" + e.getMessage()
                                + "). Tap “Play on” and choose another option.", false);
                    }
                }
            });
        } else {
            if (KIND_VIDEO.equals(k)) {
                server.setMode(CastServer.MODE_VIDEO);
                server.sendCommand("play");
            } else if (KIND_LIVE.equals(k)) {
                server.setMode(CastServer.MODE_LIVE);
            } else {
                server.setMode(CastServer.MODE_MIRROR);
            }
            setStatus("Open " + base + " in the TV's web browser", false);
        }
    }

    // =====================================================================
    // Remote control (called from the screens)
    // =====================================================================

    void togglePause() {
        final Target t = target;
        if (t == null || kind.isEmpty() || KIND_MIRROR.equals(kind)) return;
        final boolean resume = paused;
        paused = !resume;
        if (t.type == Target.APP) {
            worker.execute(() -> quiet(() -> TvApps.command(t.tv, resume ? "resume" : "pause")));
        } else if (t.type == Target.DLNA) {
            worker.execute(() -> quiet(() -> {
                if (resume) Dlna.play(t.renderer);
                else Dlna.pause(t.renderer);
            }));
        } else {
            server.sendCommand(resume ? "play" : "pause");
        }
    }

    void seekTo(int sec) {
        final Target t = target;
        if (t == null || !KIND_VIDEO.equals(kind)) return;
        if (durationSec > 0) sec = Math.min(sec, durationSec - 2);
        final int s = Math.max(0, sec);
        positionSec = s;
        if (t.type == Target.APP) {
            worker.execute(() -> quiet(() -> TvApps.command(t.tv, "seek?ms=" + (s * 1000L))));
        } else if (t.type == Target.DLNA) {
            worker.execute(() -> quiet(() -> Dlna.seek(t.renderer, s)));
        } else {
            server.sendCommand("seek:" + s);
        }
    }

    void seekBy(int delta) {
        if (positionSec >= 0) seekTo(positionSec + delta);
    }

    // =====================================================================
    // Status polling (worker thread, every second)
    // =====================================================================

    private void poll() {
        Target t = target;
        if (t == null || kind.isEmpty()) return;
        try {
            if (t.type == Target.APP) pollApp(t);
            else if (t.type == Target.DLNA) pollDlna(t);
            else pollBrowser();
        } catch (Exception ignored) {
        }
    }

    private int helloTick;

    private void pollApp(Target t) {
        if (++helloTick % 5 == 0) {
            // keep the TV showing "phone connected" while something is playing
            try {
                TvApps.hello(t.tv, Connection.phoneName(this), PORT, false);
            } catch (IOException ignored) {
            }
        }
        String st;
        try {
            st = TvApps.status(t.tv);
            appFailures = 0;
        } catch (IOException e) {
            if (++appFailures >= 4) {
                setStatus("Lost connection to " + t.tv.name + ". Is the TV on and TV Cast open?", false);
            }
            return;
        }
        if (target != t) return;
        String visible = TvApps.jsonField(st, "visible");
        String id = TvApps.jsonField(st, "id");
        String state = TvApps.jsonField(st, "state");
        if ("false".equals(visible)) {
            if (SystemClock.uptimeMillis() - startedAt > 4000) {
                setStatus("Open the TV Cast app on your TV — it will start playing right away.", false);
                // the TV app may have been closed: re-send so it plays as soon as it opens
                if (!playId.equals(id) && SystemClock.uptimeMillis() - lastDeliverAt > 5000) {
                    main.post(this::deliver);
                }
            }
            return;
        }
        if (!playId.equals(id)) return; // TV hasn't received the latest item yet
        long pos = TvApps.jsonLong(st, "pos", -1), dur = TvApps.jsonLong(st, "dur", -1);
        positionSec = pos >= 0 ? (int) (pos / 1000) : -1;
        durationSec = dur > 0 ? (int) (dur / 1000) : -1;
        if (state == null) state = "";
        switch (state) {
            case "playing":
                wasPlaying = true;
                paused = false;
                setStatus("Playing on " + t.tv.name + " ✓", true);
                break;
            case "paused":
                paused = true;
                setStatus("Paused", true);
                break;
            case "buffering":
                setStatus("Loading on " + t.tv.name + "…", false);
                break;
            case "ended":
                paused = true;
                setStatus("Finished", true);
                break;
            case "error":
                if (KIND_LIVE.equals(kind)) {
                    nextLiveSource(t);
                } else {
                    String code = String.valueOf(TvApps.jsonField(st, "error"));
                    setStatus(code.contains("NETWORK") || code.contains("IO_")
                            ? "The TV couldn't download the video from this phone (" + code + "). Tap Reconnect, "
                            + "and turn off any VPN on the phone."
                            : "The TV couldn't play this video (" + code + ")", false);
                }
                break;
            case "idle":
                if (wasPlaying) {
                    setStatus("Stopped on the TV", false);
                }
                break;
            default:
                break;
        }
    }

    private void nextLiveSource(Target t) {
        LiveProxy.Channel ch = server.live();
        if (ch == null || liveGaveUp) return;
        if (ch.next()) {
            setStatus("That source didn't work — trying another (" + (ch.index() + 1) + " of "
                    + ch.streams.size() + ")…", false);
            main.post(this::deliver);
        } else {
            liveGaveUp = true;
            setStatus("Not available right now (off-air or blocked in your country). Try another channel.", false);
            final String name = title;
            worker.execute(() -> quiet(() -> TvApps.message(t.tv,
                    "“" + name + "” isn't available right now.\nChoose another channel on your phone.")));
        }
    }

    private void pollDlna(Target t) throws IOException {
        Dlna.Renderer r = t.renderer;
        if (!KIND_LIVE.equals(kind)) {
            int[] pd = Dlna.position(r);
            if (target != t) return;
            if (pd[0] >= 0) positionSec = pd[0];
            if (pd[1] > 0) durationSec = pd[1];
        }
        String state = Dlna.transportState(r);
        if (target != t) return;
        if (state.equals("PLAYING")) {
            paused = false;
            wasPlaying = true;
            setStatus("Playing on " + r.name + " ✓", true);
        } else if (state.equals("PAUSED_PLAYBACK")) {
            paused = true;
            setStatus("Paused", true);
        } else if ((state.equals("STOPPED") || state.equals("NO_MEDIA_PRESENT"))
                && (wasPlaying || SystemClock.uptimeMillis() - startedAt > 20000)) {
            paused = true;
            setStatus(wasPlaying ? "Stopped on the TV" : "The TV player couldn't open it. "
                    + "Tap “Play on” and choose another option.", false);
        }
    }

    private void pollBrowser() {
        String base = serverUrl();
        if (KIND_LIVE.equals(kind)) {
            LiveProxy.Channel ch = server.live();
            if (ch == null) return;
            if (server.liveErrorReported()) {
                if (ch.next()) {
                    server.reloadLive();
                    setStatus("That source didn't work — trying another…", false);
                } else if (!liveGaveUp) {
                    liveGaveUp = true;
                    server.setLiveFailed(true);
                    setStatus("Not available right now. Try another channel.", false);
                }
            } else if (server.livePlaying()) {
                setStatus("Playing in the TV browser ✓", true);
            } else if (!server.tvConnected()) {
                setStatus("Open " + base + " in the TV's web browser", false);
            }
        } else if (KIND_VIDEO.equals(kind)) {
            if (server.tvConnected()) {
                positionSec = (int) server.tvPosition();
                durationSec = (int) server.tvDuration();
                paused = server.tvPaused();
                setStatus("Playing in the TV browser ✓", true);
            } else {
                setStatus("Open " + base + " in the TV's web browser", false);
            }
        } else {
            setStatus(server.mirrorViewerCount() > 0 ? "Mirroring to the TV browser ✓"
                    : "Open " + base + " in the TV's web browser", server.mirrorViewerCount() > 0);
        }
    }

    private static void setStatus(String s, boolean good) {
        status = s;
        ok = good;
    }

    // =====================================================================
    // Stopping
    // =====================================================================

    private void stopOnTarget(Target t) {
        if (t.type == Target.APP) {
            worker.execute(() -> quiet(() -> TvApps.command(t.tv, "stop")));
        } else if (t.type == Target.DLNA) {
            worker.execute(() -> quiet(() -> Dlna.stop(t.renderer)));
        }
    }

    private void clearMedia() {
        if (capturing) stopCapture();
        server.setVideo(null);
        server.setLive(null);
        server.setMode(CastServer.MODE_IDLE);
    }

    private void stopEverything(boolean tellTv) {
        Target t = target;
        if (tellTv && t != null) stopOnTarget(t);
        clearMedia();
        kind = "";
        title = "";
        status = "";
        target = null;
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static void quiet(IoAction a) {
        try {
            a.run();
        } catch (IOException ignored) {
        }
    }

    // =====================================================================
    // Screen capture (mirror)
    // =====================================================================

    private void startCapture(int code, Intent data) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("permission not granted");

        captureThread = new HandlerThread("TVCast-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // User tapped "Stop sharing" in the system UI.
                main.post(() -> {
                    if (capturing && KIND_MIRROR.equals(kind)) {
                        stopEverything(true);
                        stopSelf();
                    }
                });
            }
        }, captureHandler);

        computeSize();
        imageReader = newImageReader();
        virtualDisplay = projection.createVirtualDisplay("TVCast",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        capturing = true;
    }

    private void stopCapture() {
        capturing = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            try {
                captureThread.join(500);
            } catch (InterruptedException ignored) {
            }
            captureThread = null;
            captureHandler = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
    }

    private void computeSize() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int w = metrics.widthPixels, h = metrics.heightPixels;
        float scale = Math.min(1f, MAX_LONG_SIDE / (float) Math.max(w, h));
        width = Math.max(2, Math.round(w * scale) & ~1);
        height = Math.max(2, Math.round(h * scale) & ~1);
        densityDpi = Math.max(1, Math.round(metrics.densityDpi * scale));
    }

    private ImageReader newImageReader() {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        return reader;
    }

    /** When the phone rotates, the mirror follows so landscape fills the TV. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Handler h = captureHandler;
        if (h == null) return;
        h.post(() -> {
            if (virtualDisplay == null) return;
            int oldW = width, oldH = height;
            computeSize();
            if (oldW == width && oldH == height) return;
            ImageReader old = imageReader;
            imageReader = newImageReader();
            virtualDisplay.resize(width, height, densityDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
            old.setOnImageAvailableListener(null, null);
            old.close();
        });
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || server == null || !capturing) return;
            long now = SystemClock.uptimeMillis();
            long interval = server.mirrorViewerCount() > 0 ? FRAME_INTERVAL_MS : IDLE_FRAME_INTERVAL_MS;
            if (now - lastFrameAt < interval) return;
            lastFrameAt = now;

            int imgW = image.getWidth(), imgH = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int bufferWidth = rowStride / pixelStride;
            if (scratchBitmap == null || scratchBitmap.getWidth() != bufferWidth
                    || scratchBitmap.getHeight() != imgH) {
                scratchBitmap = Bitmap.createBitmap(bufferWidth, imgH, Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int needed = bufferWidth * imgH * 4;
            if (buffer.remaining() < needed) {
                if (paddedBuffer == null || paddedBuffer.capacity() < needed) {
                    paddedBuffer = ByteBuffer.allocateDirect(needed);
                }
                paddedBuffer.clear();
                paddedBuffer.put(buffer);
                paddedBuffer.rewind();
                buffer = paddedBuffer;
            }
            scratchBitmap.copyPixelsFromBuffer(buffer);
            Bitmap frame = scratchBitmap;
            boolean cropped = false;
            if (bufferWidth != imgW) {
                frame = Bitmap.createBitmap(scratchBitmap, 0, 0, imgW, imgH);
                cropped = true;
            }
            jpegOut.reset();
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegOut);
            if (cropped) frame.recycle();
            server.publish(jpegOut.toByteArray());
        } catch (Exception e) {
            // skip this frame
        } finally {
            if (image != null) image.close();
        }
    }

    // =====================================================================
    // Notification / network helpers
    // =====================================================================

    private void goForeground(boolean forMirror) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, forMirror
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "TV sharing",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String head = kind.isEmpty() ? "TV Cast" : "On TV: " + title;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(head)
                .setContentText(targetLabel.isEmpty() ? "Starting…" : targetLabel)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    /** http://<phone Wi-Fi IP>:8080, or null if not on a local network. */
    public static String serverUrl() {
        String ip = localIpAddress();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    private static String localIpAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String name = ni.getName();
                        if (name != null && (name.startsWith("wlan") || name.startsWith("swlan"))) {
                            return addr.getHostAddress();
                        }
                        if (fallback == null) fallback = addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (!kind.isEmpty()) stopEverything(true);
        kind = "";
        worker.shutdown();
        if (server != null) {
            server.setMode(CastServer.MODE_IDLE); // the shared server keeps running for the app
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
