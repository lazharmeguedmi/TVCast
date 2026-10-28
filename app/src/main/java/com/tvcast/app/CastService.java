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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;

/** Foreground service: captures the screen and serves it over Wi-Fi. */
public class CastService extends Service {

    public static final String ACTION_START = "com.tvcast.app.START";
    public static final String ACTION_STOP = "com.tvcast.app.STOP";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final int PORT = 8080;

    /** Longest side of the streamed image, in pixels. Lower = smoother on slow Wi-Fi. */
    private static final int MAX_LONG_SIDE = 1280;
    private static final int JPEG_QUALITY = 65;
    private static final long FRAME_INTERVAL_MS = 40;      // ~25 fps max while watched
    private static final long IDLE_FRAME_INTERVAL_MS = 1000; // keep a fresh frame when nobody watches

    private static final String CHANNEL_ID = "tvcast";
    private static final int NOTIFICATION_ID = 1;

    static volatile boolean running;
    static volatile String lastError;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private FrameServer server;
    private WifiManager.WifiLock wifiLock;

    private int width, height, densityDpi;
    private long lastFrameAt;
    private Bitmap scratchBitmap;
    private ByteBuffer paddedBuffer;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        // Must be in the foreground BEFORE obtaining the MediaProjection (Android 10+/14+ rule).
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }

        try {
            startCapture(code, data);
            lastError = null;
            running = true;
        } catch (Exception e) {
            lastError = "Could not start screen capture: " + e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int code, Intent data) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("permission not granted");

        captureThread = new HandlerThread("TVCast-Capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // Required on Android 14+ before creating the virtual display.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // User tapped "Stop sharing" in the system UI.
                stopSelf();
            }
        }, captureHandler);

        computeSize();
        imageReader = newImageReader();
        virtualDisplay = projection.createVirtualDisplay("TVCast",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        server = new FrameServer(PORT);
        server.start();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "TVCast");
            wifiLock.acquire();
        }
    }

    /** Works out the capture size from the real screen size, scaled down to MAX_LONG_SIDE. */
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

    /** When the phone rotates, resize the capture so the TV shows it full-size. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (captureHandler == null) return;
        captureHandler.post(() -> {
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
            if (image == null || server == null) return;

            long now = SystemClock.uptimeMillis();
            long interval = server.viewerCount() > 0 ? FRAME_INTERVAL_MS : IDLE_FRAME_INTERVAL_MS;
            if (now - lastFrameAt < interval) return;
            lastFrameAt = now;

            int imgW = image.getWidth(), imgH = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int bufferWidth = rowStride / pixelStride; // includes row padding

            if (scratchBitmap == null || scratchBitmap.getWidth() != bufferWidth
                    || scratchBitmap.getHeight() != imgH) {
                scratchBitmap = Bitmap.createBitmap(bufferWidth, imgH, Bitmap.Config.ARGB_8888);
            }

            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int needed = bufferWidth * imgH * 4;
            if (buffer.remaining() < needed) {
                // Last row is often not padded; copy into a full-size buffer.
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
            // Skip this frame.
        } finally {
            if (image != null) image.close();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Screen sharing",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        String url = serverUrl();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sharing screen to TV")
                .setContentText(url != null ? "Open " + url + " on your TV" : "Connect to Wi-Fi")
                .setContentIntent(open)
                .setOngoing(true)
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
        running = false;
        if (server != null) {
            server.shutdown();
            server = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            try { captureThread.join(500); } catch (InterruptedException ignored) { }
            captureThread = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
