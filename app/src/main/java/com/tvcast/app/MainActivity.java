package com.tvcast.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Main screen: choose a video → "Mirror to my TV". Also Live TV and full-screen mirroring. */
public class MainActivity extends Activity {

    static final String TV_APP_LINK = "https://github.com/lazharmeguedmi/TVCast/releases/latest/download/tv.apk";

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_VIDEO = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private static final String C_BG = "#0D1117", C_CARD = "#161B22", C_LINE = "#30363D",
            C_TEXT = "#E6EDF3", C_MUTED = "#8B949E", C_BLUE = "#1F6FEB", C_RED = "#DA3633",
            C_GREEN = "#3FB950", C_GREY = "#30363D";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private static Uri pendingVideo;
    private static String pendingName;
    private boolean waitingToSend;

    private TextView tvStatus, errorView;
    private Button changeTvBtn;
    // video picker
    private LinearLayout pickedBox;
    private ImageView thumb;
    private TextView pickedName;
    private Button chooseBtn;
    // now playing
    private LinearLayout nowCard, seekRow, controlsRow;
    private TextView nowTitle, nowStatus, timeView;
    private SeekBar seekBar;
    private Button playPauseBtn, backBtn, fwdBtn;
    private boolean dragging;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Target.discover(this, this::onTvsFound);
        ui.post(refresher);
        showPicked();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    /** "Share → TV Cast" from Photos, Files, etc. */
    private void handleShare(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri != null) setPicked(uri);
    }

    // ---------------- actions ----------------

    private void chooseVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        startActivityForResult(i, REQ_VIDEO);
    }

    private void setPicked(Uri uri) {
        pendingVideo = uri;
        pendingName = displayName(uri);
        showPicked();
    }

    private void showPicked() {
        if (pickedBox == null) return;
        boolean has = pendingVideo != null;
        pickedBox.setVisibility(has ? View.VISIBLE : View.GONE);
        chooseBtn.setText(has ? "Choose a different video" : "🎬  Choose a video");
        style(chooseBtn, has ? C_GREY : C_BLUE);
        if (!has) return;
        pickedName.setText(pendingName);
        thumb.setImageDrawable(null);
        thumb.setVisibility(View.GONE);
        final Uri u = pendingVideo;
        if (Build.VERSION.SDK_INT >= 29) {
            new Thread(() -> {
                try {
                    Bitmap b = getContentResolver().loadThumbnail(u, new Size(640, 360), null);
                    ui.post(() -> {
                        if (u.equals(pendingVideo)) {
                            thumb.setImageBitmap(b);
                            thumb.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (Exception ignored) {
                }
            }).start();
        }
    }

    /** The big button. */
    private void mirrorToTv() {
        if (pendingVideo == null) {
            chooseVideo();
            return;
        }
        Target t = Target.current(this);
        if (t == null) {
            if (Target.searching || !Target.searchedOnce) {
                waitingToSend = true;
                Toast.makeText(this, "Looking for your TV…", Toast.LENGTH_SHORT).show();
                return;
            }
            showNoTvDialog();
            return;
        }
        startForegroundService(new Intent(this, CastService.class)
                .setAction(CastService.ACTION_VIDEO)
                .setData(pendingVideo)
                .putExtra(CastService.EXTRA_TARGET, t.spec())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        CastService.kind = CastService.KIND_VIDEO;
        CastService.title = pendingName;
        CastService.status = "Sending to " + t.label() + "…";
        CastService.ok = false;
        refresh();
    }

    private void onTvsFound() {
        refresh();
        if (waitingToSend && !Target.searching) {
            waitingToSend = false;
            mirrorToTv();
        } else if (waitingToSend && Target.current(this) != null) {
            waitingToSend = false;
            mirrorToTv();
        }
    }

    private void showNoTvDialog() {
        new AlertDialog.Builder(this)
                .setTitle("TV not found")
                .setMessage("Open the Lazhar TV Cast app on your TV (same Wi-Fi as this phone), then tap Search again.\n\n"
                        + "Don't have it on the TV yet? Open Downloader on the TV and enter:\n" + TV_APP_LINK)
                .setPositiveButton("Search again", (d, w) -> {
                    waitingToSend = true;
                    Target.discover(this, this::onTvsFound);
                })
                .setNeutralButton("Other ways", (d, w) -> Target.showPicker(this, this::refresh))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void mirrorScreen() {
        Target t = Target.current(this);
        if (t == null) {
            showNoTvDialog();
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void stopAll() {
        startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
        CastService.kind = "";
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_VIDEO && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            setPicked(uri);
        } else if (requestCode == REQ_CAPTURE) {
            Target t = Target.current(this);
            if (t == null) return;
            startForegroundService(new Intent(this, CastService.class)
                    .setAction(CastService.ACTION_MIRROR)
                    .putExtra(CastService.EXTRA_CODE, resultCode)
                    .putExtra(CastService.EXTRA_DATA, data)
                    .putExtra(CastService.EXTRA_TARGET, t.spec()));
        }
    }

    // ---------------- refresh ----------------

    private void refresh() {
        Target t = Target.current(this);
        if (t != null) {
            tvStatus.setText("📺  " + t.label());
            tvStatus.setTextColor(Color.parseColor(t.type == Target.APP ? C_GREEN : C_TEXT));
        } else if (Target.searching || !Target.searchedOnce) {
            tvStatus.setText("Looking for your TV…");
            tvStatus.setTextColor(Color.parseColor(C_MUTED));
        } else {
            tvStatus.setText("TV not found — open TV Cast on your TV");
            tvStatus.setTextColor(Color.parseColor("#F0883E"));
        }

        String err = CastService.lastError;
        errorView.setText(err == null ? "" : err);
        errorView.setVisibility(err == null ? View.GONE : View.VISIBLE);

        boolean active = !CastService.kind.isEmpty() && (CastService.instance != null
                || CastService.status.startsWith("Sending"));
        nowCard.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;

        String k = CastService.kind;
        String icon = CastService.KIND_LIVE.equals(k) ? "📡  " : CastService.KIND_MIRROR.equals(k) ? "📱  " : "🎬  ";
        nowTitle.setText(icon + CastService.title);
        nowStatus.setText(CastService.status);
        nowStatus.setTextColor(Color.parseColor(CastService.ok ? C_GREEN : C_MUTED));

        boolean video = CastService.KIND_VIDEO.equals(k);
        boolean mirror = CastService.KIND_MIRROR.equals(k);
        seekRow.setVisibility(video ? View.VISIBLE : View.GONE);
        controlsRow.setVisibility(mirror ? View.GONE : View.VISIBLE);
        backBtn.setVisibility(video ? View.VISIBLE : View.GONE);
        fwdBtn.setVisibility(video ? View.VISIBLE : View.GONE);
        playPauseBtn.setText(CastService.paused ? "▶  Play" : "❚❚  Pause");
        if (video) {
            int pos = CastService.positionSec, dur = CastService.durationSec;
            if (dur > 0) {
                seekBar.setEnabled(true);
                seekBar.setMax(dur);
                if (!dragging && pos >= 0) seekBar.setProgress(pos);
            } else {
                seekBar.setEnabled(false);
                seekBar.setProgress(0);
            }
            if (!dragging) timeView.setText(fmt(pos) + " / " + fmt(dur));
        }
    }

    // ---------------- layout ----------------

    private ViewGroup buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(C_BG));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(col);

        TextView title = text("Lazhar TV Cast", 30, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        // TV status row
        LinearLayout tvRow = new LinearLayout(this);
        tvRow.setOrientation(LinearLayout.HORIZONTAL);
        tvRow.setGravity(Gravity.CENTER_VERTICAL);
        tvRow.setPadding(dp(14), dp(6), dp(6), dp(6));
        tvRow.setBackground(rounded(C_CARD, 12, C_LINE));
        tvStatus = text("Looking for your TV…", 15, C_MUTED);
        tvRow.addView(tvStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        changeTvBtn = linkButton("Change");
        changeTvBtn.setOnClickListener(v -> Target.showPicker(this, this::refresh));
        tvRow.addView(changeTvBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams trp = full();
        trp.topMargin = dp(14);
        col.addView(tvRow, trp);

        errorView = text("", 14, "#F85149");
        errorView.setPadding(0, dp(10), 0, 0);
        errorView.setVisibility(View.GONE);
        col.addView(errorView);

        // Video card
        LinearLayout vcard = card();
        LinearLayout.LayoutParams vcp = full();
        vcp.topMargin = dp(18);
        col.addView(vcard, vcp);

        pickedBox = new LinearLayout(this);
        pickedBox.setOrientation(LinearLayout.VERTICAL);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(rounded("#000000", 12, null));
        thumb.setClipToOutline(true);
        pickedBox.addView(thumb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        pickedName = text("", 17, C_TEXT);
        pickedName.setTypeface(Typeface.DEFAULT_BOLD);
        pickedName.setPadding(0, dp(12), 0, dp(12));
        pickedBox.addView(pickedName);
        Button mirrorBtn = button("📺   Mirror to my TV", C_BLUE);
        mirrorBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        mirrorBtn.setOnClickListener(v -> mirrorToTv());
        pickedBox.addView(mirrorBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        pickedBox.setVisibility(View.GONE);
        vcard.addView(pickedBox);

        chooseBtn = button("🎬  Choose a video", C_BLUE);
        chooseBtn.setOnClickListener(v -> chooseVideo());
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        cbp.topMargin = dp(10);
        vcard.addView(chooseBtn, cbp);

        // Now playing card
        nowCard = card();
        LinearLayout.LayoutParams ncp = full();
        ncp.topMargin = dp(18);
        col.addView(nowCard, ncp);
        TextView onTv = text("NOW ON TV", 12, C_MUTED);
        onTv.setTypeface(Typeface.DEFAULT_BOLD);
        nowCard.addView(onTv);
        nowTitle = text("", 17, C_TEXT);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setPadding(0, dp(6), 0, dp(2));
        nowCard.addView(nowTitle);
        nowStatus = text("", 14, C_MUTED);
        nowCard.addView(nowStatus);

        seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.VERTICAL);
        seekRow.setPadding(0, dp(10), 0, 0);
        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeView.setText(fmt(p) + " / " + fmt(CastService.durationSec));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                CastService svc = CastService.instance;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });
        seekRow.addView(seekBar);
        timeView = text("", 13, C_MUTED);
        timeView.setGravity(Gravity.END);
        seekRow.addView(timeView);
        nowCard.addView(seekRow);

        controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        backBtn = button("⟲ 10s", C_GREY);
        playPauseBtn = button("❚❚  Pause", C_BLUE);
        fwdBtn = button("10s ⟳", C_GREY);
        backBtn.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.seekBy(-10);
        });
        fwdBtn.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.seekBy(10);
        });
        playPauseBtn.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.togglePause();
        });
        controlsRow.addView(backBtn, weight(1));
        controlsRow.addView(playPauseBtn, weight(1.4f));
        controlsRow.addView(fwdBtn, weight(1));
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        crp.topMargin = dp(10);
        nowCard.addView(controlsRow, crp);

        Button stop = button("Stop", C_RED);
        stop.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sp.topMargin = dp(10);
        nowCard.addView(stop, sp);
        nowCard.setVisibility(View.GONE);

        // More
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        Button live = button("📡  Live TV", C_GREY);
        live.setOnClickListener(v -> startActivity(new Intent(this, ChannelsActivity.class)));
        Button screen = button("📱  Mirror my screen", C_GREY);
        screen.setOnClickListener(v -> mirrorScreen());
        more.addView(live, weight(1));
        more.addView(screen, weight(1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        mp.topMargin = dp(18);
        col.addView(more, mp);

        TextView help = text("Get Lazhar TV Cast on your TV: open Downloader on the TV and enter\n" + TV_APP_LINK,
                12, C_MUTED);
        help.setTextIsSelectable(true);
        help.setPadding(dp(4), dp(24), dp(4), 0);
        col.addView(help);

        return scroll;
    }

    // ---------------- helpers ----------------

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String n = c.getString(0);
                int dot = n.lastIndexOf('.');
                return dot > 0 ? n.substring(0, dot) : n;
            }
        } catch (Exception ignored) {
        }
        return "Video";
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(rounded(C_CARD, 16, C_LINE));
        return c;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private Button button(String label, String color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setStateListAnimator(null);
        style(b, color);
        return b;
    }

    private Button linkButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#58A6FF"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setStateListAnimator(null);
        return b;
    }

    private void style(Button b, String color) {
        b.setBackground(rounded(color, 12, null));
    }

    private GradientDrawable rounded(String fill, int radiusDp, String stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(radiusDp));
        if (stroke != null) g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private static String fmt(int sec) {
        if (sec < 0) return "--:--";
        int h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
