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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Single screen: shows the address to open on the TV and a Start/Stop button. */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView, urlView, stepsView;
    private Button toggleButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
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
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void onToggle() {
        if (CastService.isRunning()) {
            startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
            ui.postDelayed(this::refresh, 300);
        } else {
            if (CastService.serverUrl() == null) {
                statusView.setText("Connect your phone to the same Wi-Fi as the TV first.");
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
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            statusView.setText("Screen sharing was cancelled.");
            return;
        }
        Intent svc = new Intent(this, CastService.class)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_CODE, resultCode)
                .putExtra(CastService.EXTRA_DATA, data);
        startForegroundService(svc);
        ui.postDelayed(this::refresh, 500);
    }

    private void refresh() {
        boolean on = CastService.isRunning();
        String url = CastService.serverUrl();
        if (on) {
            statusView.setText("● Sharing your screen");
            statusView.setTextColor(Color.parseColor("#3FB950"));
            urlView.setText(url != null ? url : "No Wi-Fi connection");
            toggleButton.setText("Stop sharing");
            tint(toggleButton, "#DA3633");
        } else {
            String err = CastService.lastError;
            statusView.setText(err != null ? err : "Not sharing");
            statusView.setTextColor(Color.parseColor(err != null ? "#F85149" : "#8B949E"));
            urlView.setText(url != null ? url : "Connect to Wi-Fi");
            toggleButton.setText("Start sharing");
            tint(toggleButton, "#1F6FEB");
        }
    }

    // ---- UI built in code (no extra libraries needed) ----

    private ViewGroup buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0D1117"));
        scroll.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, dp(40), pad, pad);
        scroll.addView(col);

        TextView title = text("TV Cast", 30, "#FFFFFF");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = text("Mirror your phone to your Samsung TV over Wi-Fi", 15, "#8B949E");
        sub.setPadding(0, dp(4), 0, dp(32));
        col.addView(sub);

        statusView = text("", 16, "#8B949E");
        col.addView(statusView);

        TextView label = text("On your TV, open the Internet browser and go to:", 15, "#C9D1D9");
        label.setPadding(0, dp(24), 0, dp(8));
        col.addView(label);

        urlView = text("", 26, "#FFFFFF");
        urlView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.parseColor("#161B22"));
        box.setCornerRadius(dp(12));
        box.setStroke(dp(1), Color.parseColor("#30363D"));
        urlView.setBackground(box);
        urlView.setGravity(Gravity.CENTER);
        col.addView(urlView);

        toggleButton = new Button(this);
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toggleButton.setAllCaps(false);
        toggleButton.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(28);
        col.addView(toggleButton, lp);

        stepsView = text(
                "How to use\n\n"
                        + "1. Make sure the phone and TV are on the same Wi-Fi.\n"
                        + "2. Tap Start sharing and choose \"Entire screen\".\n"
                        + "3. On the TV remote press Home → Internet (Samsung browser).\n"
                        + "4. Type the address above and press Enter. Bookmark it for next time.\n"
                        + "5. Press the TV browser's full-screen button for the best view.\n\n"
                        + "Tip: sound stays on the phone. Turn the phone sideways for videos.",
                14, "#8B949E");
        stepsView.setPadding(0, dp(32), 0, 0);
        stepsView.setLineSpacing(0, 1.25f);
        col.addView(stepsView);

        refreshLater();
        return scroll;
    }

    private void refreshLater() {
        ui.post(this::refresh);
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private void tint(Button b, String color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
