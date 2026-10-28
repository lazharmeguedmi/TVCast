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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that keeps working while you use other apps.
 * Two modes:
 *  - Video: serves a video file from the phone; the TV plays it (TV media player via DLNA,
 *    or the TV web browser). The phone screen is NOT involved, so you can use the phone freely.
 *  - Mirror: captures the phone screen and streams it to the TV browser.
 */
public class CastService extends Service {

    public static final String ACTION_MIRROR = "com.tvcast.app.MIRROR";
    public static final String ACTION_STOP_MIRROR = "com.tvcast.app.STOP_MIRROR";
    public static final String ACTION_VIDEO = "com.tvcast.app.VIDEO";
    public static final String ACTION_STOP_VIDEO = "com.tvcast.app.STOP_VIDEO";
    public static final String ACTION_STOP = "com.tvcast.app.STOP";
    public static final String ACTION_LIVE = "com.tvcast.app.LIVE";
    public static final String ACTION_STOP_LIVE = "com.tvcast.app.STOP_LIVE";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_REFS = "refs";
    public static final String EXTRA_UAS = "uas";
    /** Location URL of the TV media player to use; absent = TV web browser. */
    public static final String EXTRA_RENDERER = "renderer";
    public static final int PORT = 8080;

    // Mirror quality
    private static final int MAX_LONG_SIDE = 1280;
    private static final int JPEG_QUALITY = 65;
    private static final long FRAME_INTERVAL_MS = 40;
    private static final long IDLE_FRAME_INTERVAL_MS = 1000;

    private static final String CHANNEL_ID = "tvcast";
    private static final int NOTIFICATION_ID = 1;

    // ---- state read by the screen ----
    static volatile CastService instance;
    static volatile String lastError;
    static volatile boolean mirroring;
    static volatile boolean videoActive;
    static volatile String videoTitle = "";
    /** Where the video plays: a TV media player, or null = TV web browser. */
    static volatile Dlna.Renderer target;
    static volatile boolean targetChosen;
    static volatile List<Dlna.Renderer> renderers = Collections.emptyList();
    static volatile boolean searching;
    static volatile String videoStatus = "";
    static volatile int positionSec = -1, durationSec = -1;
    static volatile boolean paused = true;
    // live TV
    static volatile boolean liveActive;
    static volatile String liveTitle = "";
    static volatile String liveStatus = "";
    static volatile Dlna.Renderer liveTarget;
    private long liveStartedAt;
    private boolean liveWasPlaying;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CastServer server;
    private ScheduledExecutorService worker;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private UriVideoSource source;
    private boolean wasPlaying;

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

    public static boolean isMirroring() {
        return mirroring;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        server = new CastServer(PORT);
        try (java.io.InputStream in = getAssets().open("hls.min.js")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[32 * 1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            server.setHlsJs(bo.toByteArray());
        } catch (IOException ignored) {
        }
        server.start();
        worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(this::pollPlayback, 1, 1, TimeUnit.SECONDS);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "TVCast");
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVCast:serve");
        wakeLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (action == null) action = ACTION_STOP;

        switch (action) {
            case ACTION_LIVE: {
                if (mirroring) stopCapture();
                if (videoActive) stopVideoNow();
                goForeground(false);
                startLive(intent);
                break;
            }
            case ACTION_STOP_LIVE:
                stopLiveNow();
                break;
            case ACTION_MIRROR: {
                if (videoActive) stopVideoNow();
                if (liveActive) stopLiveNow();
                goForeground(true); // must happen before getMediaProjection()
                int code = intent.getIntExtra(EXTRA_CODE, 0);
                Intent data = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                        : intent.getParcelableExtra(EXTRA_DATA);
                try {
                    startCapture(code, data);
                    mirroring = true;
                    lastError = null;
                    server.setMode(CastServer.MODE_MIRROR);
                } catch (Exception e) {
                    lastError = "Could not start screen mirroring: " + e.getMessage();
                    stopCapture();
                }
                break;
            }
            case ACTION_VIDEO: {
                Uri uri = intent.getData();
                if (mirroring) stopCapture();
                if (liveActive) stopLiveNow();
                goForeground(false);
                if (videoActive) stopVideoNow();
                try {
                    source = new UriVideoSource(getContentResolver(), uri);
                    server.setVideo(source);
                    server.setMode(CastServer.MODE_IDLE);
                    videoTitle = source.title();
                    videoActive = true;
                    target = null;
                    targetChosen = false;
                    positionSec = -1;
                    durationSec = -1;
                    paused = true;
                    wasPlaying = false;
                    videoStatus = "Choose where to play it";
                    lastError = null;
                    discover();
                } catch (Exception e) {
                    lastError = "Couldn't open that video: " + e.getMessage();
                    videoActive = false;
                }
                break;
            }
            case ACTION_STOP_MIRROR:
                stopCapture();
                break;
            case ACTION_STOP_VIDEO:
                stopVideoNow();
                break;
            default:
                stopSelf();
                return START_NOT_STICKY;
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void stopIfIdle() {
        if (!mirroring && !videoActive && !liveActive) {
            stopSelf();
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

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

    // =====================================================================
    // Video mode (called from the screen, on the main thread)
    // =====================================================================

    void discover() {
        if (searching) return;
        searching = true;
        worker.execute(() -> {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("TVCast-ssdp") : null;
            try {
                if (lock != null) lock.acquire();
                renderers = Dlna.discover(3500);
            } catch (Exception ignored) {
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
                searching = false;
            }
        });
    }

    /** r = a TV media player, or null for the TV web browser. */
    void playOn(Dlna.Renderer r) {
        if (!videoActive || source == null) return;
        final Dlna.Renderer old = target;
        target = r;
        targetChosen = true;
        positionSec = -1;
        durationSec = -1;
        wasPlaying = false;
        final UriVideoSource src = source;
        final String path = server.videoPath();
        if (r == null) {
            server.setMode(CastServer.MODE_VIDEO);
            server.sendCommand("play");
            paused = false;
            videoStatus = "Open " + serverUrl() + " in the TV's Internet browser";
            if (old != null) worker.execute(() -> quietStop(old));
        } else {
            server.setMode(CastServer.MODE_EXTERNAL);
            paused = false;
            videoStatus = "Sending to " + r.name + "… (if the TV asks, choose Allow)";
            worker.execute(() -> {
                if (old != null && old != r) quietStop(old);
                String base = serverUrl();
                if (base == null) {
                    videoStatus = "Not connected to Wi-Fi";
                    return;
                }
                try {
                    Dlna.load(r, base + path, src.title(), src.mimeType(), src.length());
                    if (target == r) videoStatus = "Playing on " + r.name;
                } catch (IOException e) {
                    if (target == r) {
                        videoStatus = "The TV didn't start the video (" + e.getMessage()
                                + "). Try again, or use the TV web browser option.";
                    }
                }
            });
        }
        updateNotification();
    }

    void togglePause() {
        if (!targetChosen) return;
        final boolean resume = paused;
        final Dlna.Renderer r = target;
        if (r == null) {
            server.sendCommand(resume ? "play" : "pause");
            paused = !resume;
        } else {
            paused = !resume;
            worker.execute(() -> {
                try {
                    if (resume) Dlna.play(r);
                    else Dlna.pause(r);
                } catch (IOException e) {
                    videoStatus = "The TV didn't respond (" + e.getMessage() + ")";
                }
            });
        }
    }

    void seekTo(int sec) {
        if (!targetChosen) return;
        if (durationSec > 0) sec = Math.min(sec, durationSec - 2);
        final int s = Math.max(0, sec);
        positionSec = s;
        final Dlna.Renderer r = target;
        if (r == null) {
            server.sendCommand("seek:" + s);
        } else {
            worker.execute(() -> {
                try {
                    Dlna.seek(r, s);
                } catch (IOException e) {
                    videoStatus = "This TV doesn't support skipping (" + e.getMessage() + ")";
                }
            });
        }
    }

    void seekBy(int delta) {
        if (positionSec >= 0) seekTo(positionSec + delta);
    }

    private void stopVideoNow() {
        final Dlna.Renderer r = target;
        if (r != null) worker.execute(() -> quietStop(r));
        videoActive = false;
        target = null;
        targetChosen = false;
        source = null;
        server.setVideo(null);
        server.setMode(mirroring ? CastServer.MODE_MIRROR : CastServer.MODE_IDLE);
        videoStatus = "";
    }

    private static void quietStop(Dlna.Renderer r) {
        try {
            Dlna.stop(r);
        } catch (IOException ignored) {
        }
    }

    // =====================================================================
    // Live TV channels
    // =====================================================================

    private void startLive(Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        String[] urls = intent.getStringArrayExtra(EXTRA_URLS);
        String[] refs = intent.getStringArrayExtra(EXTRA_REFS);
        String[] uas = intent.getStringArrayExtra(EXTRA_UAS);
        if (urls == null || urls.length == 0) return;
        java.util.List<LiveProxy.Stream> streams = new java.util.ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            streams.add(new LiveProxy.Stream(urls[i],
                    refs != null && i < refs.length ? refs[i] : "",
                    uas != null && i < uas.length ? uas[i] : ""));
        }
        final Dlna.Renderer old = liveTarget;
        Dlna.Renderer r = null;
        String loc = intent.getStringExtra(EXTRA_RENDERER);
        if (loc != null) {
            for (Dlna.Renderer x : renderers) if (x.location.equals(loc)) r = x;
        }
        String token = Long.toHexString(Double.doubleToLongBits(Math.random()))
                + Long.toHexString(System.nanoTime());
        server.setLive(new LiveProxy.Channel(title == null ? "Live TV" : title, streams, token));
        liveActive = true;
        liveTitle = title == null ? "Live TV" : title;
        liveTarget = r;
        liveStartedAt = SystemClock.uptimeMillis();
        liveWasPlaying = false;
        lastError = null;

        if (r == null) {
            server.setMode(CastServer.MODE_LIVE);
            liveStatus = "Loading on the TV…";
            if (old != null) worker.execute(() -> quietStop(old));
        } else {
            server.setMode(CastServer.MODE_EXTERNAL);
            liveStatus = "Sending to " + r.name + "…";
            final Dlna.Renderer tv = r;
            final String path = server.livePath();
            final String t = liveTitle;
            worker.execute(() -> {
                if (old != null && old != tv) quietStop(old);
                String base = serverUrl();
                if (base == null) {
                    liveStatus = "Not connected to Wi-Fi";
                    return;
                }
                try {
                    Dlna.load(tv, base + path, t, "application/vnd.apple.mpegurl", -1);
                } catch (IOException e) {
                    if (liveTarget == tv) {
                        liveStatus = "The TV player didn't accept this channel. "
                                + "Choose \"TV web browser\" under Play on, then try again.";
                    }
                }
            });
        }
        updateNotification();
    }

    private void stopLiveNow() {
        final Dlna.Renderer r = liveTarget;
        if (r != null) worker.execute(() -> quietStop(r));
        liveActive = false;
        liveTarget = null;
        liveStatus = "";
        server.setLive(null);
        if (CastServer.MODE_LIVE.equals(server.mode()) || CastServer.MODE_EXTERNAL.equals(server.mode())) {
            server.setMode(CastServer.MODE_IDLE);
        }
    }

    private void pollLive() {
        LiveProxy.Channel ch = server.live();
        if (ch == null) return;
        Dlna.Renderer r = liveTarget;
        if (r == null) {
            if (server.liveErrorReported()) {
                if (ch.next()) {
                    server.reloadLive();
                    liveStatus = "That source didn't work — trying another (" + (ch.index() + 1)
                            + " of " + ch.streams.size() + ")…";
                } else if (!liveStatus.startsWith("Not available")) {
                    server.setLiveFailed(true);
                    liveStatus = "Not available right now (it may be off-air or blocked in your country). "
                            + "Try another channel.";
                }
            } else if (server.livePlaying()) {
                liveStatus = "Playing on the TV ✓";
            } else if (!server.tvConnected()) {
                liveStatus = "Waiting for the TV — open " + serverUrl() + " in the TV's Internet browser";
            }
            return;
        }
        try {
            String state = Dlna.transportState(r);
            if (liveTarget != r) return;
            if (state.equals("PLAYING")) {
                liveWasPlaying = true;
                liveStatus = "Playing on " + r.name + " ✓";
            } else if ((state.equals("STOPPED") || state.equals("NO_MEDIA_PRESENT"))
                    && (liveWasPlaying || SystemClock.uptimeMillis() - liveStartedAt > 20000)) {
                liveStatus = liveWasPlaying ? "Stopped on the TV."
                        : "The TV player couldn't open this channel. Under Play on, choose "
                        + "\"TV web browser\" and try again.";
            }
        } catch (IOException ignored) {
        }
    }

    /** Runs every second on the worker thread: reads the position from the TV. */
    private void pollPlayback() {
        if (liveActive) {
            pollLive();
            return;
        }
        if (!videoActive || !targetChosen) return;
        Dlna.Renderer r = target;
        if (r == null) {
            if (server.tvConnected()) {
                positionSec = (int) server.tvPosition();
                durationSec = (int) server.tvDuration();
                paused = server.tvPaused();
                videoStatus = "Playing in the TV web browser";
            } else {
                videoStatus = "Waiting for the TV… open " + serverUrl() + " in the TV's Internet browser";
            }
            return;
        }
        try {
            int[] pd = Dlna.position(r);
            if (target != r) return;
            if (pd[0] >= 0) positionSec = pd[0];
            if (pd[1] > 0) durationSec = pd[1];
            String state = Dlna.transportState(r);
            if (target != r) return;
            if (state.equals("PLAYING")) {
                paused = false;
                wasPlaying = true;
                videoStatus = "Playing on " + r.name;
            } else if (state.equals("PAUSED_PLAYBACK")) {
                paused = true;
                videoStatus = "Paused on " + r.name;
            } else if (state.equals("STOPPED") && wasPlaying) {
                paused = true;
                videoStatus = "Stopped on the TV. Tap ▶ to play again.";
            }
        } catch (IOException ignored) {
            // TV busy; try again next second
        }
    }

    // =====================================================================
    // Mirror mode
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
                    if (mirroring) {
                        stopCapture();
                        stopIfIdle();
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
    }

    private void stopCapture() {
        mirroring = false;
        if (server != null && CastServer.MODE_MIRROR.equals(server.mode())) {
            server.setMode(CastServer.MODE_IDLE);
        }
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
            if (image == null || server == null || !mirroring) return;

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
                new Intent(this, CastService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        String title, text;
        if (liveActive) {
            title = "Live TV: " + liveTitle;
            Dlna.Renderer r = liveTarget;
            text = r != null ? r.name : "TV web browser";
        } else if (videoActive) {
            title = "Playing on TV: " + videoTitle;
            Dlna.Renderer r = target;
            text = r != null ? r.name : (targetChosen ? "TV web browser" : "Tap to choose the TV");
        } else if (mirroring) {
            title = "Mirroring screen to TV";
            String url = serverUrl();
            text = url != null ? "Open " + url + " on your TV" : "Connect to Wi-Fi";
        } else {
            title = "TV Cast";
            text = "Starting…";
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
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
        final Dlna.Renderer r = target;
        if (videoActive && r != null) worker.execute(() -> quietStop(r));
        final Dlna.Renderer lr = liveTarget;
        if (liveActive && lr != null) worker.execute(() -> quietStop(lr));
        liveActive = false;
        liveTarget = null;
        videoActive = false;
        target = null;
        targetChosen = false;
        stopCapture();
        worker.shutdown();
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
