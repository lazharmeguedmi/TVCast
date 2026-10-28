package com.tvcast.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;

/** The TV screen: waits for the phone, then plays videos, live channels or the phone's screen full-screen. */
public class TvActivity extends Activity implements ReceiverService.Player {

    private final Handler main = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private ExoPlayer player;
    private ImageView mirrorView;
    private MjpegReader mirror;
    private LinearLayout idleView;
    private TextView idleName, overlayTitle, messageView;

    // Status reported to the phone (read from another thread)
    private volatile String sId = "", sKind = "", sState = "idle", sError = "";
    private volatile long sPos = -1, sDur = -1;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && sKind.length() > 0 && !sKind.equals("mirror")) {
                sPos = player.getCurrentPosition();
                long d = player.getDuration();
                sDur = d > 0 ? d : -1;
            }
            main.postDelayed(this, 500);
        }
    };

    private final Runnable hideTitle = () -> overlayTitle.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        ReceiverService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReceiverService.player = this;
        idleName.setText(ReceiverService.deviceName(this) + "   ·   " + ipAddress());
        main.post(ticker);
        String cmd = ReceiverService.pendingCmd;
        Map<String, String> p = ReceiverService.pendingParams;
        ReceiverService.pendingCmd = null;
        ReceiverService.pendingParams = null;
        if (cmd != null && p != null) onCommand(cmd, p);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Left the app (Home button): stop playing and let the phone know.
        if (ReceiverService.player == this) ReceiverService.player = null;
        main.removeCallbacks(ticker);
        stopAll();
        showIdle();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ---------------- commands from the phone ----------------

    @Override
    public void onCommand(String cmd, Map<String, String> p) {
        switch (cmd) {
            case "play":
                play(p.get("kind"), p.get("url"), p.get("title"), p.get("id"));
                break;
            case "pause":
                if (player != null) player.pause();
                break;
            case "resume":
                if (player != null) player.play();
                break;
            case "seek":
                if (player != null) {
                    try {
                        player.seekTo(Long.parseLong(p.get("ms")));
                    } catch (Exception ignored) {
                    }
                }
                break;
            case "stop":
                stopAll();
                showIdle();
                break;
            case "message":
                showMessage(p.get("text"));
                break;
            default:
                break;
        }
    }

    @Override
    public String status() {
        return "{\"visible\":true"
                + ",\"id\":" + CommandServer.json(sId)
                + ",\"kind\":" + CommandServer.json(sKind)
                + ",\"state\":" + CommandServer.json(sState)
                + ",\"pos\":" + sPos
                + ",\"dur\":" + sDur
                + ",\"error\":" + CommandServer.json(sError)
                + "}";
    }

    private void play(String kind, String url, String title, String id) {
        if (url == null) return;
        if (kind == null) kind = "video";
        stopAll();
        sId = id == null ? "" : id;
        sKind = kind;
        sError = "";
        sPos = -1;
        sDur = -1;
        idleView.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
        showTitle(title);

        if (kind.equals("mirror")) {
            sState = "buffering";
            mirrorView.setImageDrawable(null);
            mirrorView.setVisibility(View.VISIBLE);
            mirror = new MjpegReader(url, new MjpegReader.Listener() {
                @Override
                public void onFrame(Bitmap frame) {
                    main.post(() -> {
                        if (mirror == null) return;
                        mirrorView.setImageBitmap(frame);
                        sState = "playing";
                    });
                }

                @Override
                public void onConnectionChanged(boolean connected) {
                    if (!connected) sState = "buffering";
                }
            });
            mirror.start();
            return;
        }

        sState = "buffering";
        ensurePlayer();
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        if (kind.equals("live")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(true);
        playerView.setVisibility(View.VISIBLE);
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                updateState();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updateState();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                sState = "error";
                sError = error.getErrorCodeName();
                if (sKind.equals("live")) {
                    showMessage("Connecting… trying another source");
                } else {
                    showMessage("This video can't be played on this TV.\n(" + error.getErrorCodeName() + ")");
                }
            }
        });
    }

    private void updateState() {
        if (player == null || sState.equals("error")) return;
        int s = player.getPlaybackState();
        if (s == Player.STATE_BUFFERING) {
            sState = "buffering";
        } else if (s == Player.STATE_ENDED) {
            sState = "ended";
        } else if (s == Player.STATE_READY) {
            sState = player.getPlayWhenReady() ? "playing" : "paused";
            messageView.setVisibility(View.GONE);
        } else {
            sState = "idle";
        }
    }

    private void stopAll() {
        if (mirror != null) {
            mirror.shutdown();
            mirror = null;
        }
        mirrorView.setImageDrawable(null);
        mirrorView.setVisibility(View.GONE);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        playerView.setVisibility(View.GONE);
        sKind = "";
        sState = "idle";
        sPos = -1;
        sDur = -1;
    }

    private void showIdle() {
        idleView.setVisibility(View.VISIBLE);
        overlayTitle.setVisibility(View.GONE);
        messageView.setVisibility(View.GONE);
    }

    private void showTitle(String title) {
        if (title == null || title.isEmpty()) return;
        overlayTitle.setText(title);
        overlayTitle.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideTitle);
        main.postDelayed(hideTitle, 5000);
    }

    private void showMessage(String text) {
        if (text == null || text.isEmpty()) {
            messageView.setVisibility(View.GONE);
            return;
        }
        messageView.setText(text);
        messageView.setVisibility(View.VISIBLE);
    }

    // ---------------- remote control ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean active = sKind.length() > 0;
        boolean video = sKind.equals("video");
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null && active && !sKind.equals("mirror")) {
                    if (player.getPlayWhenReady()) player.pause();
                    else player.play();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null) player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) player.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (player != null && video) {
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (player != null && video) {
                    player.seekTo(player.getCurrentPosition() + 10000);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (active || messageView.getVisibility() == View.VISIBLE) {
                    stopAll();
                    showIdle();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- layout ----------------

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams fill = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setVisibility(View.GONE);
        root.addView(playerView, fill);

        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorView.setVisibility(View.GONE);
        root.addView(mirrorView, new FrameLayout.LayoutParams(fill));

        idleView = new LinearLayout(this);
        idleView.setOrientation(LinearLayout.VERTICAL);
        idleView.setGravity(Gravity.CENTER);
        idleView.setBackgroundColor(Color.parseColor("#0D1117"));
        TextView logo = text("📺", 64, "#FFFFFF");
        logo.setGravity(Gravity.CENTER);
        idleView.addView(logo);
        TextView title = text("TV Cast is ready", 40, "#FFFFFF");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(12), 0, dp(12));
        idleView.addView(title);
        TextView hint = text("On your phone, open TV Cast, choose a video and tap “Mirror to my TV”.\n"
                + "Live TV channels and screen mirroring will also appear here automatically.", 20, "#8B949E");
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.3f);
        idleView.addView(hint);
        idleName = text("", 16, "#58A6FF");
        idleName.setGravity(Gravity.CENTER);
        idleName.setPadding(0, dp(28), 0, 0);
        idleView.addView(idleName);
        root.addView(idleView, new FrameLayout.LayoutParams(fill));

        overlayTitle = text("", 26, "#FFFFFF");
        overlayTitle.setTypeface(Typeface.DEFAULT_BOLD);
        overlayTitle.setShadowLayer(8, 0, 2, Color.BLACK);
        overlayTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(48);
        tp.topMargin = dp(36);
        root.addView(overlayTitle, tp);

        messageView = text("", 24, "#FFFFFF");
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(dp(32), dp(20), dp(32), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC161B22"));
        bg.setCornerRadius(dp(16));
        messageView.setBackground(bg);
        messageView.setVisibility(View.GONE);
        root.addView(messageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        return root;
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private static String ipAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "not on Wi-Fi";
    }
}
