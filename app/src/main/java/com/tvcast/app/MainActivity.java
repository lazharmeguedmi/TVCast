package com.tvcast.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** The app's single screen. Everything runs in CastService, so you can leave this screen anytime. */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_VIDEO = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private static final String C_BG = "#0D1117", C_CARD = "#161B22", C_LINE = "#30363D",
            C_TEXT = "#E6EDF3", C_MUTED = "#8B949E", C_BLUE = "#1F6FEB", C_RED = "#DA3633",
            C_GREEN = "#3FB950", C_GREY = "#30363D";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView errorView, addressView, liveNowView;
    // video
    private Button chooseVideoBtn;
    private LinearLayout videoPanel, targetsBox, controlsBox;
    private TextView videoTitleView, videoStatusView, timeView, searchingView;
    private SeekBar seekBar;
    private Button playPauseBtn;
    private boolean dragging;
    private List<Dlna.Renderer> shownRenderers;
    private Object shownTarget = "";
    private boolean shownTargetChosen, shownSearching;
    // mirror
    private TextView mirrorStatus;
    private Button mirrorBtn;

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownRenderers = null; // force rebuild
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    // ---------------- actions ----------------

    private void chooseVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*");
        startActivityForResult(i, REQ_VIDEO);
    }

    private void toggleMirror() {
        if (CastService.isMirroring()) {
            startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP_MIRROR));
        } else {
            if (CastService.serverUrl() == null) {
                CastService.lastError = "Connect your phone to the same Wi-Fi as the TV first.";
                return;
            }
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_CAPTURE) {
            startForegroundService(new Intent(this, CastService.class)
                    .setAction(CastService.ACTION_MIRROR)
                    .putExtra(CastService.EXTRA_CODE, resultCode)
                    .putExtra(CastService.EXTRA_DATA, data));
        } else if (requestCode == REQ_VIDEO && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            startForegroundService(new Intent(this, CastService.class)
                    .setAction(CastService.ACTION_VIDEO)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
    }

    // ---------------- refresh ----------------

    private void refresh() {
        CastService svc = CastService.instance;
        String url = CastService.serverUrl();
        addressView.setText(url != null ? url : "Not on Wi-Fi");

        String err = CastService.lastError;
        errorView.setText(err == null ? "" : err);
        errorView.setVisibility(err == null ? View.GONE : View.VISIBLE);

        // --- live ---
        if (svc != null && CastService.liveActive) {
            liveNowView.setVisibility(View.VISIBLE);
            liveNowView.setText("▶ " + CastService.liveTitle + " — " + CastService.liveStatus);
        } else {
            liveNowView.setVisibility(View.GONE);
        }

        // --- video ---
        boolean video = svc != null && CastService.videoActive;
        videoPanel.setVisibility(video ? View.VISIBLE : View.GONE);
        chooseVideoBtn.setText(video ? "Choose a different video" : "Choose a video");
        if (video) {
            videoTitleView.setText(CastService.videoTitle);
            videoStatusView.setText(CastService.videoStatus);
            rebuildTargetsIfNeeded();
            boolean chosen = CastService.targetChosen;
            controlsBox.setVisibility(chosen ? View.VISIBLE : View.GONE);
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
            playPauseBtn.setText(CastService.paused ? "▶  Play" : "❚❚  Pause");
        }

        // --- mirror ---
        boolean mirror = CastService.isMirroring();
        if (mirror) {
            mirrorStatus.setText("● Mirroring your screen — open the address above in the TV browser");
            mirrorStatus.setTextColor(Color.parseColor(C_GREEN));
            mirrorBtn.setText("Stop mirroring");
            style(mirrorBtn, C_RED);
        } else {
            mirrorStatus.setText("Shows exactly what's on your phone. Turn the phone sideways for landscape. "
                    + "Videos from streaming apps (Netflix etc.) show black — use those apps' own Cast button.");
            mirrorStatus.setTextColor(Color.parseColor(C_MUTED));
            mirrorBtn.setText("Start mirroring");
            style(mirrorBtn, C_GREY);
        }
    }

    private void rebuildTargetsIfNeeded() {
        List<Dlna.Renderer> list = CastService.renderers;
        Object tgt = CastService.target;
        boolean chosen = CastService.targetChosen;
        boolean searching = CastService.searching;
        if (list == shownRenderers && tgt == shownTarget && chosen == shownTargetChosen
                && searching == shownSearching) {
            return;
        }
        shownRenderers = list;
        shownTarget = tgt;
        shownTargetChosen = chosen;
        shownSearching = searching;

        targetsBox.removeAllViews();
        for (Dlna.Renderer r : list) {
            boolean sel = chosen && tgt == r;
            Button b = button("📺  " + r.name + (sel ? "  ✓" : ""), sel ? C_BLUE : C_GREY);
            b.setOnClickListener(v -> {
                CastService s = CastService.instance;
                if (s != null) s.playOn(r);
            });
            targetsBox.addView(b, buttonParams(8));
        }
        boolean browserSel = chosen && tgt == null;
        Button web = button("🌐  TV web browser" + (browserSel ? "  ✓" : ""), browserSel ? C_BLUE : C_GREY);
        web.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.playOn(null);
        });
        targetsBox.addView(web, buttonParams(8));

        if (searching) {
            searchingView.setText("Looking for your TV on the Wi-Fi…");
        } else if (list.isEmpty()) {
            searchingView.setText("No TV found automatically. Make sure the TV is on, then tap Search again "
                    + "— or use the TV web browser option.");
        } else {
            searchingView.setText("Tap your TV to play the video on it (recommended).");
        }
    }

    // ---------------- layout ----------------

    private ViewGroup buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor(C_BG));
        scroll.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(col);

        TextView title = text("TV Cast", 30, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = text("Send videos or your screen to your Samsung TV", 15, C_MUTED);
        sub.setPadding(0, dp(2), 0, dp(16));
        col.addView(sub);

        errorView = text("", 14, "#F85149");
        errorView.setPadding(0, 0, 0, dp(12));
        col.addView(errorView);

        // ---- Live TV card ----
        LinearLayout lcard = card();
        col.addView(lcard, cardParams());
        lcard.addView(heading("📡  Live TV channels"));
        TextView ldesc = text("Thousands of free channels from around the world, sorted by language "
                + "and country. Tap one and it plays full-screen on the TV.", 14, C_MUTED);
        ldesc.setPadding(0, dp(4), 0, dp(12));
        lcard.addView(ldesc);
        Button browse = button("Browse channels", C_BLUE);
        browse.setOnClickListener(v -> startActivity(new Intent(this, ChannelsActivity.class)));
        lcard.addView(browse, buttonParams(0));
        liveNowView = text("", 14, C_GREEN);
        liveNowView.setPadding(0, dp(10), 0, 0);
        liveNowView.setVisibility(View.GONE);
        lcard.addView(liveNowView);

        // ---- Video card ----
        LinearLayout vcard = card();
        LinearLayout.LayoutParams vp = cardParams();
        vp.topMargin = dp(20);
        col.addView(vcard, vp);
        vcard.addView(heading("🎬  Play a video on the TV"));
        TextView vdesc = text("Best for movies: full-screen on the TV with TV sound. Keep using your phone "
                + "while it plays — this app is your remote.", 14, C_MUTED);
        vdesc.setPadding(0, dp(4), 0, dp(12));
        vcard.addView(vdesc);
        chooseVideoBtn = button("Choose a video", C_BLUE);
        chooseVideoBtn.setOnClickListener(v -> chooseVideo());
        vcard.addView(chooseVideoBtn, buttonParams(0));

        videoPanel = new LinearLayout(this);
        videoPanel.setOrientation(LinearLayout.VERTICAL);
        videoPanel.setVisibility(View.GONE);
        vcard.addView(videoPanel);

        videoTitleView = text("", 17, C_TEXT);
        videoTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        videoTitleView.setPadding(0, dp(16), 0, dp(4));
        videoPanel.addView(videoTitleView);

        TextView playOn = text("Play on:", 14, C_MUTED);
        playOn.setPadding(0, dp(6), 0, 0);
        videoPanel.addView(playOn);
        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        videoPanel.addView(targetsBox);
        searchingView = text("", 13, C_MUTED);
        searchingView.setPadding(0, dp(8), 0, 0);
        videoPanel.addView(searchingView);
        Button searchAgain = smallButton("Search again");
        searchAgain.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.discover();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        sp.topMargin = dp(6);
        videoPanel.addView(searchAgain, sp);

        controlsBox = new LinearLayout(this);
        controlsBox.setOrientation(LinearLayout.VERTICAL);
        controlsBox.setVisibility(View.GONE);
        videoPanel.addView(controlsBox);

        videoStatusView = text("", 14, C_GREEN);
        videoStatusView.setPadding(0, dp(14), 0, dp(4));
        controlsBox.addView(videoStatusView);

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
        controlsBox.addView(seekBar);
        timeView = text("", 13, C_MUTED);
        timeView.setGravity(Gravity.END);
        controlsBox.addView(timeView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("⟲ 10s", C_GREY);
        playPauseBtn = button("❚❚  Pause", C_BLUE);
        Button fwd = button("10s ⟳", C_GREY);
        back.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.seekBy(-10);
        });
        fwd.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.seekBy(10);
        });
        playPauseBtn.setOnClickListener(v -> {
            CastService s = CastService.instance;
            if (s != null) s.togglePause();
        });
        row.addView(back, weight(1));
        row.addView(playPauseBtn, weight(1.4f));
        row.addView(fwd, weight(1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        rp.topMargin = dp(8);
        controlsBox.addView(row, rp);

        Button stopVideo = button("Stop video", C_RED);
        stopVideo.setOnClickListener(v ->
                startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP_VIDEO)));
        controlsBox.addView(stopVideo, buttonParams(10));

        // ---- Address ----
        TextView addrLabel = text("TV browser address (for the web browser option and mirroring):", 13, C_MUTED);
        addrLabel.setPadding(dp(4), dp(20), dp(4), dp(6));
        col.addView(addrLabel);
        addressView = text("", 22, C_TEXT);
        addressView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addressView.setTextIsSelectable(true);
        addressView.setGravity(Gravity.CENTER);
        addressView.setPadding(dp(12), dp(12), dp(12), dp(12));
        addressView.setBackground(rounded(C_CARD, 12, C_LINE));
        col.addView(addressView);

        // ---- Mirror card ----
        LinearLayout mcard = card();
        LinearLayout.LayoutParams mp = cardParams();
        mp.topMargin = dp(20);
        col.addView(mcard, mp);
        mcard.addView(heading("📱  Mirror your screen"));
        mirrorStatus = text("", 14, C_MUTED);
        mirrorStatus.setPadding(0, dp(4), 0, dp(12));
        mcard.addView(mirrorStatus);
        mirrorBtn = button("Start mirroring", C_GREY);
        mirrorBtn.setOnClickListener(v -> toggleMirror());
        mcard.addView(mirrorBtn, buttonParams(0));

        // ---- Help ----
        TextView help = text("Tips\n"
                + "• Phone and TV must be on the same Wi-Fi.\n"
                + "• The first time, the TV may ask to allow this phone — choose Allow.\n"
                + "• Works with videos saved on your phone (MP4 works everywhere; the TV player also "
                + "handles MKV and more).\n"
                + "• Web browser option: on the TV press Home → Internet, type the address above, "
                + "and bookmark it.", 13, C_MUTED);
        help.setLineSpacing(0, 1.25f);
        help.setPadding(dp(4), dp(20), dp(4), 0);
        col.addView(help);

        return scroll;
    }

    // ---------------- small UI helpers ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(rounded(C_CARD, 16, C_LINE));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView heading(String s) {
        TextView t = text(s, 19, C_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
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

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#58A6FF"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
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

    private LinearLayout.LayoutParams buttonParams(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(topMarginDp);
        return lp;
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
