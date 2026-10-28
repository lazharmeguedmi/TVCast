package com.tvcast.tv;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen player. Plays:
 *  - live channels picked on the TV (▲▼ = previous/next channel, automatic backup sources)
 *  - free movies/shows picked on the TV
 *  - anything sent from the phone (video, live channel, screen mirror)
 * Back returns to the menus.
 */
public class TvActivity extends Activity implements ReceiverService.Player {

    static final String EXTRA_LOCAL = "local";   // "live" (uses queue) or "vod"
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_URL = "url";

    /** Channel list for ▲▼ zapping when a live channel is picked on the TV. */
    static List<Catalog.Channel> queue;
    static int queuePos;

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Handler main = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private ExoPlayer player;
    private ImageView mirrorView;
    private MjpegReader mirror;
    private TextView overlayTitle, overlayHint, messageView;

    // What's playing
    private boolean localLive;            // a channel chosen on the TV
    private int streamIndex;              // backup source being tried
    private boolean fromPhone;

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

    private final Runnable hideOverlay = () -> {
        overlayTitle.setVisibility(View.GONE);
        overlayHint.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        ReceiverService.start(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String local = i == null ? null : i.getStringExtra(EXTRA_LOCAL);
        if (local == null) return;
        i.removeExtra(EXTRA_LOCAL);
        fromPhone = false;
        LanNet.useWifiForPhone(this, false);
        if (local.equals("live")) {
            playChannel(queuePos);
        } else {
            localLive = false;
            startPlayer("video", i.getStringExtra(EXTRA_URL), i.getStringExtra(EXTRA_TITLE), "", "", "");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReceiverService.player = this;
        main.post(ticker);
        String cmd = ReceiverService.pendingCmd;
        Map<String, String> p = ReceiverService.pendingParams;
        ReceiverService.pendingCmd = null;
        ReceiverService.pendingParams = null;
        if (cmd != null && p != null) onCommand(cmd, p);
        else if (sKind.isEmpty()) {
            // nothing to play
            main.postDelayed(() -> {
                if (sKind.isEmpty()) finish();
            }, 1500);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (ReceiverService.player == this) ReceiverService.player = null;
        main.removeCallbacks(ticker);
        stopAll();
        LanNet.useWifiForPhone(this, false);
        finish(); // pressing Home ends playback; Back to menus next time
    }

    @Override
    protected void onDestroy() {
        if (ReceiverService.player == this) ReceiverService.player = null;
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ---------------- local live TV ----------------

    private void playChannel(int pos) {
        List<Catalog.Channel> q = queue;
        if (q == null || q.isEmpty()) return;
        pos = (pos % q.size() + q.size()) % q.size();
        queuePos = pos;
        localLive = true;
        streamIndex = 0;
        Catalog.Channel ch = q.get(pos);
        overlayHint.setText((pos + 1) + " / " + q.size() + "   ·   ▲▼ change channel   ·   Back for menu");
        startStream(ch);
    }

    private void startStream(Catalog.Channel ch) {
        int i = Math.min(streamIndex, ch.urls.length - 1);
        startPlayer("live", ch.urls[i], ch.name, ch.refs[i], ch.uas[i], "");
        if (streamIndex > 0) showMessage("Trying backup source " + (streamIndex + 1) + " of " + ch.urls.length + "…");
    }

    private boolean tryNextSource() {
        if (!localLive || queue == null) return false;
        Catalog.Channel ch = queue.get(queuePos);
        if (streamIndex + 1 >= ch.urls.length) return false;
        streamIndex++;
        startStream(ch);
        return true;
    }

    // ---------------- commands from the phone ----------------

    @Override
    public void onCommand(String cmd, Map<String, String> p) {
        switch (cmd) {
            case "play":
                fromPhone = true;
                localLive = false;
                LanNet.useWifiForPhone(this, true);
                sId = p.get("id") == null ? "" : p.get("id");
                startPlayer(p.get("kind"), p.get("url"), p.get("title"), "", "", sId);
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
                finish();
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
                + ",\"kind\":" + CommandServer.json(fromPhone ? sKind : "")
                + ",\"state\":" + CommandServer.json(fromPhone ? sState : "idle")
                + ",\"pos\":" + sPos
                + ",\"dur\":" + sDur
                + ",\"error\":" + CommandServer.json(sError)
                + "}";
    }

    // ---------------- playback ----------------

    private void startPlayer(String kind, String url, String title, String referrer, String ua, String id) {
        if (url == null) return;
        if (kind == null) kind = "video";
        stopAll();
        sKind = kind;
        sError = "";
        sPos = -1;
        sDur = -1;
        messageView.setVisibility(View.GONE);
        showOverlay(title);

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
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua == null || ua.isEmpty() ? DEFAULT_UA : ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        if (referrer != null && !referrer.isEmpty()) {
            Map<String, String> h = new HashMap<>();
            h.put("Referer", referrer);
            h.put("Origin", origin(referrer));
            http.setDefaultRequestProperties(h);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase(Locale.ROOT);
        if (kind.equals("live") && (lower.contains(".m3u8") || lower.contains("/live/") || !lower.contains(".mp4"))) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        MediaSource source = new DefaultMediaSourceFactory(http).createMediaSource(item.build());
        player.setMediaSource(source);
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
                sError = error.getErrorCodeName();
                if (tryNextSource()) return;
                sState = "error";
                if (localLive) {
                    showMessage("This channel isn't available right now.\n"
                            + "It may be off-air or only viewable in its own country.\n▲▼ try another channel");
                } else if (fromPhone && sKind.equals("live")) {
                    showMessage("Connecting… trying another source");
                } else {
                    showMessage("This video can't be played right now.\n(" + error.getErrorCodeName() + ")");
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
        if (mirrorView != null) {
            mirrorView.setImageDrawable(null);
            mirrorView.setVisibility(View.GONE);
        }
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        sKind = "";
        sState = "idle";
        sPos = -1;
        sDur = -1;
    }

    private void showOverlay(String title) {
        if (title == null || title.isEmpty()) return;
        overlayTitle.setText(title);
        overlayTitle.setVisibility(View.VISIBLE);
        overlayHint.setVisibility(localLive ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideOverlay);
        main.postDelayed(hideOverlay, 5000);
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
        boolean seekable = player != null && (sKind.equals("video"));
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (localLive) {
                    playChannel(queuePos - 1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (localLive) {
                    playChannel(queuePos + 1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player != null && !sKind.equals("mirror") && !sKind.isEmpty()) {
                    if (player.getPlayWhenReady()) player.pause();
                    else player.play();
                    showOverlay(overlayTitle.getText().toString());
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
                if (seekable) {
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (seekable) {
                    player.seekTo(player.getCurrentPosition() + 10000);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                stopAll();
                finish();
                return true;
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
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(playerView, fill);

        mirrorView = new ImageView(this);
        mirrorView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorView.setVisibility(View.GONE);
        root.addView(mirrorView, new FrameLayout.LayoutParams(fill));

        overlayTitle = text("", 28, "#FFFFFF");
        overlayTitle.setTypeface(Typeface.DEFAULT_BOLD);
        overlayTitle.setShadowLayer(8, 0, 2, Color.BLACK);
        overlayTitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(48);
        tp.topMargin = dp(36);
        root.addView(overlayTitle, tp);

        overlayHint = text("", 16, "#C9D1D9");
        overlayHint.setShadowLayer(6, 0, 2, Color.BLACK);
        overlayHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hp.leftMargin = dp(48);
        hp.topMargin = dp(80);
        root.addView(overlayHint, hp);

        messageView = text("", 22, "#FFFFFF");
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

    private static String origin(String url) {
        try {
            Uri u = Uri.parse(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
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
}
