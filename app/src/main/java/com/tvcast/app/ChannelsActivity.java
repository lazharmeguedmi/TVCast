package com.tvcast.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live TV menu: Language folders -> Country folders -> Channels. Tap a channel to play it on the TV. */
public class ChannelsActivity extends Activity {

    private static final String C_BG = "#0D1117", C_CARD = "#161B22", C_LINE = "#30363D",
            C_TEXT = "#E6EDF3", C_MUTED = "#8B949E", C_BLUE = "#1F6FEB", C_RED = "#DA3633",
            C_GREEN = "#3FB950";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Catalog catalog;
    private Catalog.Language openLang;
    private Catalog.Country openCountry;
    private final Map<String, Catalog.Country> countryInfo = new HashMap<>();

    private TextView titleView, crumbView, infoView;
    private Button backBtn, targetBtn;
    private EditText searchBox;
    private ListView list;
    private ProgressBar progress;
    private LinearLayout nowBar;
    private TextView nowTitle, nowStatus;
    private final RowAdapter adapter = new RowAdapter();

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshNowPlaying();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        loadCatalog(false);
        discoverTvs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTargetButton();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    @Override
    public void onBackPressed() {
        if (searchBox.getText().length() > 0) {
            searchBox.setText("");
        } else if (openCountry != null) {
            openCountry = null;
            show();
        } else if (openLang != null) {
            openLang = null;
            show();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------- data ----------------

    private void loadCatalog(boolean force) {
        progress.setVisibility(View.VISIBLE);
        infoView.setText(force ? "Updating the channel list…" : "Loading channels…");
        infoView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                Catalog c = Catalog.load(getApplicationContext(), force);
                ui.post(() -> {
                    catalog = c;
                    countryInfo.clear();
                    for (Catalog.Language l : c.languages) {
                        for (Catalog.Country co : l.countries) countryInfo.put(co.code, co);
                    }
                    progress.setVisibility(View.GONE);
                    infoView.setVisibility(View.GONE);
                    show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    progress.setVisibility(View.GONE);
                    infoView.setText(e.getMessage() + "\n\nTap here to try again.");
                    infoView.setOnClickListener(v -> loadCatalog(true));
                });
            }
        }).start();
    }

    private void discoverTvs() {
        Target.discover(this, this::updateTargetButton);
    }

    // ---------------- navigation ----------------

    private void show() {
        if (catalog == null) return;
        String q = searchBox.getText().toString().trim();
        List<Row> rows = new ArrayList<>();
        backBtn.setVisibility(openLang != null || !q.isEmpty() ? View.VISIBLE : View.INVISIBLE);

        if (!q.isEmpty()) {
            titleView.setText("Search");
            crumbView.setText("Channels matching “" + q + "”");
            String ql = q.toLowerCase(Locale.ROOT);
            int max = 300;
            for (int i = 0; i < catalog.channels.size() && rows.size() < max; i++) {
                Catalog.Channel ch = catalog.channels.get(i);
                if (ch.name.toLowerCase(Locale.ROOT).contains(ql)) rows.add(channelRow(ch, true));
            }
            if (rows.isEmpty()) rows.add(new Row("No channels found", "Try a different name", null, null));
        } else if (openLang == null) {
            titleView.setText("Live TV");
            crumbView.setText(catalog.languages.size() + " languages · " + catalog.channels.size()
                    + " free channels · updated " + catalog.updated);
            for (Catalog.Language l : catalog.languages) {
                Catalog.Language lang = l;
                rows.add(new Row("📁  " + l.name, l.countries.size() + (l.countries.size() == 1 ? " country · " : " countries · ")
                        + l.count + " channels", String.valueOf(l.count), v -> {
                    openLang = lang;
                    show();
                }));
            }
        } else if (openCountry == null) {
            titleView.setText(openLang.name);
            crumbView.setText("Live TV › " + openLang.name);
            for (Catalog.Country c : openLang.countries) {
                Catalog.Country country = c;
                rows.add(new Row(c.flag + "  " + c.name, c.channels.length
                        + (c.channels.length == 1 ? " channel" : " channels"),
                        String.valueOf(c.channels.length), v -> {
                    openCountry = country;
                    show();
                }));
            }
        } else {
            titleView.setText(openCountry.flag + " " + openCountry.name);
            crumbView.setText("Live TV › " + openLang.name + " › " + openCountry.name);
            for (int idx : openCountry.channels) rows.add(channelRow(catalog.channels.get(idx), false));
        }
        adapter.set(rows);
        list.setSelection(0);
    }

    private Row channelRow(Catalog.Channel ch, boolean withCountry) {
        StringBuilder sub = new StringBuilder();
        if (withCountry) {
            Catalog.Country c = countryInfo.get(ch.country);
            if (c != null) sub.append(c.flag).append(' ').append(c.name);
        }
        if (!TextUtils.isEmpty(ch.category)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(ch.category);
        }
        if (!TextUtils.isEmpty(ch.quality)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(ch.quality);
        }
        if (ch.geo) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append("may only work in its country");
        }
        return new Row("📺  " + ch.name, sub.toString(), "▶", v -> play(ch));
    }

    private void play(Catalog.Channel ch) {
        Target t = Target.current(this);
        if (t == null) {
            new AlertDialog.Builder(this)
                    .setTitle("TV not found")
                    .setMessage("Open the Lazhar TV Cast app on your TV (same Wi-Fi as this phone), then try again.\n\n"
                            + "Don't have it on the TV yet? Open Downloader on the TV and enter:\n"
                            + MainActivity.TV_APP_LINK)
                    .setPositiveButton("Search again", (d, w) -> discoverTvs())
                    .setNeutralButton("Other ways", (d, w) -> Target.showPicker(this, this::updateTargetButton))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        Intent i = new Intent(this, CastService.class)
                .setAction(CastService.ACTION_LIVE)
                .putExtra(CastService.EXTRA_TITLE, ch.name)
                .putExtra(CastService.EXTRA_URLS, ch.urls)
                .putExtra(CastService.EXTRA_REFS, ch.refs)
                .putExtra(CastService.EXTRA_UAS, ch.uas)
                .putExtra(CastService.EXTRA_TARGET, t.spec());
        CastService.kind = CastService.KIND_LIVE;
        CastService.title = ch.name;
        CastService.status = "Sending to " + t.label() + "…";
        CastService.ok = false;
        startForegroundService(i);
        refreshNowPlaying();
    }

    // ---------------- where to play ----------------

    private void updateTargetButton() {
        Target t = Target.current(this);
        String label = t != null ? t.label()
                : (Target.searching || !Target.searchedOnce ? "looking for your TV…" : "TV not found");
        targetBtn.setText("Play on:  📺 " + label + "  ▾");
    }

    private void chooseTarget() {
        Target.showPicker(this, this::updateTargetButton);
    }

    private void refreshNowPlaying() {
        if (CastService.KIND_LIVE.equals(CastService.kind)) {
            nowBar.setVisibility(View.VISIBLE);
            nowTitle.setText("▶  " + CastService.title);
            nowStatus.setText(CastService.status);
            nowStatus.setTextColor(Color.parseColor(CastService.ok ? C_GREEN
                    : CastService.status.startsWith("Not available") ? "#F85149" : C_MUTED));
        } else {
            nowBar.setVisibility(View.GONE);
        }
        updateTargetButton();
    }

    // ---------------- layout ----------------

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(C_BG));
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(12), dp(16), 0);
        backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        backBtn.setTextColor(Color.parseColor(C_TEXT));
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setOnClickListener(v -> onBackPressed());
        top.addView(backBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));
        titleView = text("Live TV", 24, C_TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = new Button(this);
        refresh.setText("⟳");
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        refresh.setTextColor(Color.parseColor(C_MUTED));
        refresh.setBackgroundColor(Color.TRANSPARENT);
        refresh.setOnClickListener(v -> loadCatalog(true));
        top.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top);

        crumbView = text("", 13, C_MUTED);
        crumbView.setPadding(dp(20), 0, dp(20), dp(10));
        root.addView(crumbView);

        searchBox = new EditText(this);
        searchBox.setHint("🔍  Search any channel");
        searchBox.setHintTextColor(Color.parseColor(C_MUTED));
        searchBox.setTextColor(Color.parseColor(C_TEXT));
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchBox.setBackground(rounded(C_CARD, 12, C_LINE));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(searchRunnable);
                ui.postDelayed(searchRunnable, 250);
            }
        });
        searchBox.setOnEditorActionListener((v, id, ev) -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(16);
        sp.rightMargin = dp(16);
        root.addView(searchBox, sp);

        targetBtn = new Button(this);
        targetBtn.setAllCaps(false);
        targetBtn.setTextColor(Color.parseColor("#58A6FF"));
        targetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        targetBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        targetBtn.setBackgroundColor(Color.TRANSPARENT);
        targetBtn.setStateListAnimator(null);
        targetBtn.setOnClickListener(v -> chooseTarget());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        tp.leftMargin = dp(12);
        root.addView(targetBtn, tp);

        progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        infoView = text("", 15, C_MUTED);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(dp(24), dp(12), dp(24), dp(12));
        root.addView(infoView);

        list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Row r = adapter.rows.get(pos);
            if (r.click != null) r.click.onClick(v);
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Now playing bar
        nowBar = new LinearLayout(this);
        nowBar.setOrientation(LinearLayout.HORIZONTAL);
        nowBar.setGravity(Gravity.CENTER_VERTICAL);
        nowBar.setPadding(dp(16), dp(10), dp(12), dp(10));
        nowBar.setBackgroundColor(Color.parseColor(C_CARD));
        LinearLayout nowText = new LinearLayout(this);
        nowText.setOrientation(LinearLayout.VERTICAL);
        nowTitle = text("", 15, C_TEXT);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowStatus = text("", 12, C_MUTED);
        nowStatus.setMaxLines(3);
        nowText.addView(nowTitle);
        nowText.addView(nowStatus);
        nowBar.addView(nowText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setAllCaps(false);
        stop.setTextColor(Color.WHITE);
        stop.setBackground(rounded(C_RED, 10, null));
        stop.setOnClickListener(v -> {
            startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP));
            CastService.kind = "";
            nowBar.setVisibility(View.GONE);
        });
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(dp(76), dp(44));
        stp.leftMargin = dp(10);
        nowBar.addView(stop, stp);
        nowBar.setVisibility(View.GONE);
        root.addView(nowBar);

        return root;
    }

    private final Runnable searchRunnable = this::show;

    // ---------------- list rows ----------------

    private static final class Row {
        final String title, subtitle, badge;
        final View.OnClickListener click;

        Row(String title, String subtitle, String badge, View.OnClickListener click) {
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.click = click;
        }
    }

    private final class RowAdapter extends BaseAdapter {
        List<Row> rows = new ArrayList<>();

        void set(List<Row> r) {
            rows = r;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int p) {
            return rows.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout v;
            if (convert instanceof LinearLayout) {
                v = (LinearLayout) convert;
            } else {
                v = new LinearLayout(ChannelsActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(20), dp(12), dp(20), dp(12));
                LinearLayout texts = new LinearLayout(ChannelsActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = text("", 16, C_TEXT);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                TextView s = text("", 12, C_MUTED);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(t);
                texts.addView(s);
                v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView badge = text("", 13, C_MUTED);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(dp(10), dp(4), dp(10), dp(4));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.leftMargin = dp(10);
                v.addView(badge, bp);
            }
            Row r = rows.get(pos);
            LinearLayout texts = (LinearLayout) v.getChildAt(0);
            ((TextView) texts.getChildAt(0)).setText(r.title);
            TextView sub = (TextView) texts.getChildAt(1);
            sub.setText(r.subtitle);
            sub.setVisibility(TextUtils.isEmpty(r.subtitle) ? View.GONE : View.VISIBLE);
            TextView badge = (TextView) v.getChildAt(1);
            badge.setText(r.badge == null ? "" : r.badge);
            badge.setVisibility(r.badge == null ? View.GONE : View.VISIBLE);
            boolean play = "▶".equals(r.badge);
            badge.setTextColor(Color.parseColor(play ? "#FFFFFF" : C_MUTED));
            badge.setBackground(play ? rounded(C_BLUE, 16, null) : rounded(C_CARD, 16, C_LINE));
            return v;
        }
    }

    // ---------------- helpers ----------------

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        return t;
    }

    private GradientDrawable rounded(String fill, int radiusDp, String stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(radiusDp));
        if (stroke != null) g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
