package com.tvcast.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.Map;

/**
 * Keeps the TV listening for the phone (even when the TV Cast screen isn't showing),
 * and forwards commands to the full-screen player.
 */
public class ReceiverService extends Service implements CommandServer.Handler {

    /** Implemented by the full-screen player while it is on screen. */
    interface Player {
        void onCommand(String cmd, Map<String, String> params);

        String status();
    }

    static volatile Player player;
    static volatile String pendingCmd;
    static volatile Map<String, String> pendingParams;
    static volatile String lastPlayId = "";
    /** The menus are on screen (the app is open and ready). */
    static volatile boolean homeVisible;

    // The phone that's connected (it says hello every few seconds)
    static volatile String phoneName = "";
    static volatile String phoneIp = "";
    static volatile long phoneSeenAt;
    static volatile boolean phoneReachable;

    static boolean phoneConnected() {
        return System.currentTimeMillis() - phoneSeenAt < 12000;
    }

    private static final String CHANNEL = "tvcast-tv";
    private final Handler main = new Handler(Looper.getMainLooper());
    private CommandServer server;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, ReceiverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TV Cast",
                    NotificationManager.IMPORTANCE_MIN));
            n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Lazhar TV Cast is ready")
                    .setContentText("Waiting for your phone")
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Lazhar TV Cast is ready")
                    .build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, n);
            }
        } catch (Exception ignored) {
            // Some TVs refuse; we still run while the app is open.
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("TVCast-tv");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TVCast-tv");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        server = new CommandServer(deviceName(this), this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public String handle(String cmd, Map<String, String> params) {
        Player p = player;
        if (cmd.equals("status")) {
            if (p != null) return p.status();
            if (homeVisible) {
                return "{\"visible\":true,\"state\":\"idle\",\"id\":" + CommandServer.json(lastPlayId) + "}";
            }
            return "{\"visible\":false,\"state\":\"idle\",\"id\":" + CommandServer.json(lastPlayId) + "}";
        }
        if (cmd.equals("hello")) {
            return hello(params);
        }
        if (cmd.equals("message") && p == null) {
            final String text = params.get("text");
            if (text != null && homeVisible) {
                main.post(() -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show());
            }
            return "{\"ok\":true}";
        }
        switch (cmd) {
            case "play":
            case "pause":
            case "resume":
            case "seek":
            case "stop":
            case "message":
                if ("play".equals(cmd)) lastPlayId = params.containsKey("id") ? params.get("id") : "";
                if (p != null) {
                    main.post(() -> {
                        Player now = player;
                        if (now != null) now.onCommand(cmd, params);
                    });
                } else if (cmd.equals("play")) {
                    // Player isn't on screen: open it, it will pick this up.
                    pendingCmd = cmd;
                    pendingParams = params;
                    main.post(this::openPlayer);
                }
                return "{\"ok\":true}";
            default:
                return "{\"ok\":false,\"error\":\"unknown command\"}";
        }
    }

    /** Phone says hello: remember it and check the TV can reach the phone back (needed to play its videos). */
    private String hello(Map<String, String> params) {
        String ip = params.get("_ip");
        String name = params.get("name");
        int port = 8080;
        try {
            port = Integer.parseInt(params.get("port"));
        } catch (Exception ignored) {
        }
        boolean reverse = false;
        String reverseError = "";
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://" + ip + ":" + port + "/ping").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            reverse = c.getResponseCode() == 200;
            c.disconnect();
        } catch (Exception e) {
            reverseError = e.getClass().getSimpleName();
        }
        boolean isNew = !phoneConnected() || !String.valueOf(ip).equals(phoneIp);
        phoneName = name == null || name.isEmpty() ? "Phone" : name;
        phoneIp = ip == null ? "" : ip;
        phoneReachable = reverse;
        phoneSeenAt = System.currentTimeMillis();
        if (isNew && homeVisible && "1".equals(params.get("announce"))) {
            final String n = phoneName;
            main.post(() -> android.widget.Toast.makeText(this, "📱 " + n + " connected",
                    android.widget.Toast.LENGTH_SHORT).show());
        }
        return "{\"ok\":true,\"name\":" + CommandServer.json(deviceName(this))
                + ",\"reverse\":" + reverse
                + ",\"reverseError\":" + CommandServer.json(reverseError)
                + ",\"visible\":" + (homeVisible || player != null)
                + ",\"yourIp\":" + CommandServer.json(phoneIp) + "}";
    }

    private void openPlayer() {
        try {
            Intent i = new Intent(this, TvActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    static String deviceName(Context ctx) {
        String n = null;
        try {
            n = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }
}
