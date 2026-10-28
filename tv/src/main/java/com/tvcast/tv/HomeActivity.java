package com.tvcast.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lazhar TV Cast home screen on the TV: Live TV, Movies, TV Shows, Documentaries, Cartoons, Search, Phone. */
public class HomeActivity extends Activity {

    static final String APP_NAME = "Lazhar TV Cast";

    private static final String C_BG = "#0B0F14", C_SIDE = "#11161D", C_CARD = "#1A2029",
            C_TEXT = "#E6EDF3", C_MUTED = "#8B949E", C_BLUE = "#1F6FEB", C_ACCENT = "#58A6FF";

    private static final String[][] NAV = {
            {"live", "📡   Live TV"},
            {"tubi", "🍿   Free on Tubi"},
            {"movies", "🎬   Movies"},
            {"shows", "📺   TV Shows"},
            {"docs", "🌍   Documentaries"},
            {"kids", "🧸   Cartoons"},
            {"search", "🔍   Search"},
            {"phone", "📱   From my phone"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<TextView> navViews = new ArrayList<>();
    private String current = "live";

    private TextView headTitle, headSub, emptyView;
    private HorizontalScrollView chipsScroll;
    private LinearLayout chips;
    private ListView list;
    private GridView grid;
    private EditText searchBox;
    private LinearLayout phonePanel;
    private TextView phoneInfo;

    // live TV navigation
    private Catalog.Language openLang;
    private Catalog.Country openCountry;
    private final Map<String, Catalog.Country> countryInfo = new HashMap<>();

    private final RowAdapter listAdapter = new RowAdapter();
    private final PosterAdapter gridAdapter = new PosterAdapter();
    private boolean channelsLoaded, vodLoaded;
    private long lastBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        ReceiverService.start(this);
        loadData(false);
        select("live", false);
        navViews.get(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReceiverService.homeVisible = true;
        // A command from the phone arrived while we were away: open the player.
        if (ReceiverService.pendingCmd != null) {
            startActivity(new Intent(this, TvActivity.class));
        }
        if (current.equals("phone")) showPhone();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ReceiverService.homeVisible = false;
    }

    // ---------------- data ----------------

    private void loadData(boolean force) {
        new Thread(() -> {
            String err = null;
            try {
                Catalog.loadChannels(getApplicationContext(), force);
                ui.post(() -> {
                    channelsLoaded = true;
                    countryInfo.clear();
                    for (Catalog.Language l : Catalog.languages) {
                        for (Catalog.Country c : l.countries) countryInfo.put(c.code, c);
                    }
                    if (isVisibleSection()) refreshCurrent();
                });
            } catch (Exception e) {
                err = e.getMessage();
            }
            try {
                Catalog.loadTubi(getApplicationContext(), force);
            } catch (Exception ignored) {
                // optional list
            }
            try {
                Catalog.loadVod(getApplicationContext(), force);
                ui.post(() -> {
                    vodLoaded = true;
                    if (isVisibleSection()) refreshCurrent();
                });
            } catch (Exception e) {
                if (err == null) err = e.getMessage();
                ui.post(() -> {
                    vodLoaded = true;
                    if (isVisibleSection()) refreshCurrent();
                });
            }
            final String msg = err;
            if (msg != null) ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    private boolean isVisibleSection() {
        return !current.equals("phone");
    }

    private void refreshCurrent() {
        select(current, false);
    }

    // ---------------- sections ----------------

    private void select(String id, boolean focusContent) {
        current = id;
        for (TextView v : navViews) v.setSelected(id.equals(v.getTag()));
        chipsScroll.setVisibility(View.GONE);
        searchBox.setVisibility(View.GONE);
        phonePanel.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);

        switch (id) {
            case "live":
                showLive();
                if (focusContent) list.requestFocus();
                break;
            case "search":
                headTitle.setText("Search");
                headSub.setText("Find a channel, movie, show or country");
                searchBox.setVisibility(View.VISIBLE);
                runSearch();
                if (focusContent) searchBox.requestFocus();
                break;
            case "phone":
                showPhone();
                break;
            default:
                showVod(id, 0);
                if (focusContent) {
                    if (chips.getChildCount() > 0) chips.getChildAt(0).requestFocus();
                    else grid.requestFocus();
                }
                break;
        }
    }

    // ----- Live TV: Language → Country → Channels -----

    private void showLive() {
        list.setVisibility(View.VISIBLE);
        if (!channelsLoaded) {
            headTitle.setText("Live TV");
            headSub.setText("Loading channels…");
            listAdapter.set(new ArrayList<>());
            return;
        }
        List<Row> rows = new ArrayList<>();
        if (openLang == null) {
            headTitle.setText("Live TV");
            headSub.setText(Catalog.languages.size() + " languages · " + Catalog.channels.size()
                    + " free channels · choose a language");
            List<Catalog.Language> langs = new ArrayList<>(Catalog.languages);
            Collections.sort(langs, (a, b) -> b.count - a.count);
            for (Catalog.Language l : langs) {
                rows.add(new Row("📁   " + l.name, l.countries.size()
                        + (l.countries.size() == 1 ? " country" : " countries"), l.count + " ▸", () -> {
                    openLang = l;
                    showLive();
                    list.setSelection(0);
                }));
            }
        } else if (openCountry == null) {
            headTitle.setText(openLang.name);
            headSub.setText("Live TV  ›  " + openLang.name + "  ·  choose a country  (Back to go up)");
            for (Catalog.Country c : openLang.countries) {
                rows.add(new Row(c.flag + "   " + c.name, "", c.channels.length + " ▸", () -> {
                    openCountry = c;
                    showLive();
                    list.setSelection(0);
                }));
            }
        } else {
            headTitle.setText(openCountry.flag + "  " + openCountry.name);
            headSub.setText("Live TV  ›  " + openLang.name + "  ›  " + openCountry.name
                    + "  ·  in the player, ▲▼ change channel");
            final List<Catalog.Channel> chs = new ArrayList<>();
            for (int i : openCountry.channels) chs.add(Catalog.channels.get(i));
            for (int i = 0; i < chs.size(); i++) {
                final int pos = i;
                rows.add(channelRow(chs.get(i), false, () -> playChannels(chs, pos)));
            }
        }
        listAdapter.set(rows);
    }

    private Row channelRow(Catalog.Channel ch, boolean withCountry, Runnable click) {
        StringBuilder sub = new StringBuilder();
        if (withCountry) {
            Catalog.Country c = countryInfo.get(ch.country);
            if (c != null) sub.append(c.flag).append(' ').append(c.name);
        }
        if (!TextUtils.isEmpty(ch.category)) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(ch.category);
        }
        if (!TextUtils.isEmpty(ch.quality)) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(ch.quality);
        }
        if (ch.geo) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append("may only work in its country");
        }
        return new Row("📺   " + ch.name, sub.toString(), "▶", click);
    }

    private void playChannels(List<Catalog.Channel> queue, int pos) {
        TvActivity.queue = queue;
        TvActivity.queuePos = pos;
        startActivity(new Intent(this, TvActivity.class).putExtra(TvActivity.EXTRA_LOCAL, "live"));
    }

    // ----- Movies / Shows / Docs / Cartoons -----

    private void showVod(String sectionId, int rowIndex) {
        Catalog.Section s = Catalog.section(sectionId);
        String name = sectionName(sectionId);
        headTitle.setText(name);
        if (!vodLoaded) {
            headSub.setText("Loading…");
            emptyView.setText("Loading…");
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        boolean movies = sectionId.equals("movies");
        if ((s == null || s.rows.isEmpty()) && !movies) {
            headSub.setText("");
            emptyView.setText("This list isn't available yet. Check back in a few minutes (or press ⟳ in Search).");
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        chipsScroll.setVisibility(View.VISIBLE);
        chips.removeAllViews();
        List<Catalog.Row> rows = s == null ? new ArrayList<>() : s.rows;
        int total = rows.size() + (movies ? 1 : 0);
        if (rowIndex >= total) rowIndex = 0;
        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            Button chip = chip(rows.get(i).name, i == rowIndex);
            chip.setOnClickListener(v -> {
                showVod(sectionId, idx);
                grid.requestFocus();
            });
            chips.addView(chip);
        }
        if (movies) {
            final int idx = rows.size();
            Button chip = chip("📡 Live movie channels", rowIndex == idx);
            chip.setOnClickListener(v -> {
                showVod(sectionId, idx);
                list.requestFocus();
            });
            chips.addView(chip);
        }

        if (movies && rowIndex == rows.size()) {
            headSub.setText("Free 24/7 movie channels");
            List<Row> out = new ArrayList<>();
            final List<Catalog.Channel> chs = new ArrayList<>();
            for (Catalog.Channel c : Catalog.channels) {
                if ("Movies".equalsIgnoreCase(c.category) && !c.geo) chs.add(c);
            }
            for (int i = 0; i < chs.size(); i++) {
                final int pos = i;
                out.add(channelRow(chs.get(i), true, () -> playChannels(chs, pos)));
            }
            listAdapter.set(out);
            list.setVisibility(View.VISIBLE);
            return;
        }
        Catalog.Row row = rows.get(rowIndex);
        boolean tubi = sectionId.equals("tubi");
        headSub.setText(row.name + "  ·  " + row.items.length + " titles  ·  "
                + (tubi ? "press OK to play · Back returns here" : "free from the Internet Archive"));
        List<Catalog.Title> items = new ArrayList<>();
        for (int i : row.items) items.add(s.pool.get(i));
        gridAdapter.set(items);
        grid.setVisibility(View.VISIBLE);
        grid.setSelection(0);
    }

    private static String sectionName(String id) {
        switch (id) {
            case "movies": return "Movies";
            case "shows": return "TV Shows";
            case "docs": return "Documentaries";
            case "kids": return "Cartoons";
            case "tubi": return "Free on Tubi";
            default: return "";
        }
    }

    private void showTitle(Catalog.Title t) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setPadding(dp(24), dp(24), dp(24), dp(8));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.load(img, t.imageUrl(), 400);
        v.addView(img, new LinearLayout.LayoutParams(dp(240), dp(180)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, 0, 0);
        TextView title = text(t.title, 24, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        String meta = t.externalUrl != null ? ("series".equals(t.kind) ? "TV series" : "Movie")
                + "  ·  Free on Tubi (with ads)" : (t.year.isEmpty() ? "" : t.year) + (t.seconds > 0 ? (t.year.isEmpty() ? "" : "  ·  ")
                + (t.seconds / 60) + " min" : "");
        TextView m = text(meta, 16, C_MUTED);
        m.setPadding(0, dp(6), 0, dp(10));
        col.addView(m);
        TextView d = text(t.description.isEmpty() ? "No description." : t.description, 16, C_TEXT);
        d.setLineSpacing(0, 1.2f);
        col.addView(d);
        v.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(v)
                .setPositiveButton(t.externalUrl != null ? "▶  Watch on Tubi" : "▶  Play", (di, w) -> {
                    if (t.externalUrl != null) {
                        openInTubi(t);
                        return;
                    }
                    startActivity(new Intent(this, TvActivity.class)
                            .putExtra(TvActivity.EXTRA_LOCAL, "vod")
                            .putExtra(TvActivity.EXTRA_TITLE, t.title)
                            .putExtra(TvActivity.EXTRA_URL, t.videoUrl()));
                })
                .setNegativeButton("Back", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus());
        dlg.show();
    }

    // ----- Tubi -----

    private static final String TUBI_PKG = "com.tubitv";

    /** Opens the title in the Tubi app (Tubi plays it, with its ads). */
    private void openInTubi(Catalog.Title t) {
        String type = "series".equals(t.kind) ? "series" : "movie";
        Intent[] tries = {
                new Intent(Intent.ACTION_VIEW, Uri.parse(t.externalUrl)).setPackage(TUBI_PKG),
                new Intent(Intent.ACTION_VIEW, Uri.parse("tubitv://media-details?contentId=" + t.id
                        + "&contentType=" + type)).setPackage(TUBI_PKG),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(TUBI_PKG);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(TUBI_PKG);
        if (launch != null) {
            Toast.makeText(this, "Opening Tubi — search for “" + t.title + "”", Toast.LENGTH_LONG).show();
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Tubi isn't installed")
                .setMessage("Tubi is free. Install it once from the app store, then come back and pick a title.")
                .setPositiveButton("Install Tubi", (d, w) -> {
                    String[] stores = {"amzn://apps/android?p=" + TUBI_PKG, "market://details?id=" + TUBI_PKG};
                    for (String u : stores) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            return;
                        } catch (ActivityNotFoundException ignored) {
                        }
                    }
                    Toast.makeText(this, "Search for “Tubi” in the app store", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Back", null)
                .show();
    }

    // ----- Search -----

    private void runSearch() {
        list.setVisibility(View.VISIBLE);
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        if (q.length() < 2) {
            out.add(new Row("Type at least 2 letters", "Search works for channel names, countries and movie titles",
                    null, null));
            out.add(new Row("⟳   Refresh channel and movie lists", "Downloads the newest lists", null, () -> {
                Toast.makeText(this, "Updating lists…", Toast.LENGTH_SHORT).show();
                loadData(true);
            }));
            listAdapter.set(out);
            return;
        }
        // countries
        for (Catalog.Country c : countryInfo.values()) {
            if (c.name.toLowerCase(Locale.ROOT).contains(q)) {
                final String code = c.code;
                out.add(new Row(c.flag + "   " + c.name + " — all channels", "Country", "▸", () -> {
                    List<Catalog.Channel> chs = new ArrayList<>();
                    for (Catalog.Channel ch : Catalog.channels) if (code.equals(ch.country)) chs.add(ch);
                    showChannelList(c.flag + "  " + c.name, chs);
                }));
            }
        }
        // channels
        List<Catalog.Channel> found = new ArrayList<>();
        for (Catalog.Channel ch : Catalog.channels) {
            if (ch.name.toLowerCase(Locale.ROOT).contains(q)) found.add(ch);
            if (found.size() >= 150) break;
        }
        for (int i = 0; i < found.size(); i++) {
            final int pos = i;
            out.add(channelRow(found.get(i), true, () -> playChannels(found, pos)));
        }
        // titles
        int n = 0;
        for (Catalog.Title t : Catalog.titles) {
            if (t.title.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(new Row("🎬   " + t.title, (t.year.isEmpty() ? "" : t.year + "  ·  ") + "Free movie / show",
                        "▸", () -> showTitle(t)));
                if (++n >= 100) break;
            }
        }
        for (Catalog.Title t : Catalog.tubiTitles) {
            if (t.title.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(new Row("🍿   " + t.title, "Free on Tubi", "▶", () -> openInTubi(t)));
            }
        }
        if (out.isEmpty()) out.add(new Row("Nothing found for “" + q + "”", "Try another name", null, null));
        listAdapter.set(out);
    }

    private void showChannelList(String title, List<Catalog.Channel> chs) {
        searchBox.setVisibility(View.GONE);
        headTitle.setText(title);
        headSub.setText(chs.size() + " channels");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < chs.size(); i++) {
            final int pos = i;
            rows.add(channelRow(chs.get(i), false, () -> playChannels(chs, pos)));
        }
        listAdapter.set(rows);
        list.setSelection(0);
        list.requestFocus();
    }

    // ----- Phone -----

    private void showPhone() {
        headTitle.setText("From my phone");
        headSub.setText("Send videos, live TV or your screen from the phone app");
        phonePanel.setVisibility(View.VISIBLE);
        phoneInfo.setText("✓  Ready — this TV is visible to your phone\n\n"
                + "TV name:  " + ReceiverService.deviceName(this) + "\n"
                + "Address:  " + ipAddress() + "\n\n"
                + "On your phone, open Lazhar TV Cast, choose a video and tap “Mirror to my TV”.\n"
                + "Anything you send plays here automatically — even while you're browsing channels.\n\n"
                + "Phone app download:\nhttps://github.com/lazharmeguedmi/TVCast/releases/latest/download/TVCast.apk");
    }

    // ---------------- remote: Back ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            View f = getCurrentFocus();
            boolean inSidebar = f != null && navViews.contains(f);
            if (!inSidebar) {
                if (current.equals("live") && openCountry != null) {
                    openCountry = null;
                    showLive();
                    list.requestFocus();
                    return true;
                }
                if (current.equals("live") && openLang != null) {
                    openLang = null;
                    showLive();
                    list.requestFocus();
                    return true;
                }
                if (current.equals("search") && searchBox.getVisibility() == View.GONE) {
                    select("search", true);
                    return true;
                }
                focusNav();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBack > 2500) {
                lastBack = now;
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void focusNav() {
        for (TextView v : navViews) {
            if (current.equals(v.getTag())) {
                v.requestFocus();
                return;
            }
        }
    }

    // ---------------- layout ----------------

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.parseColor(C_BG));

        // Sidebar
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(Color.parseColor(C_SIDE));
        side.setPadding(dp(16), dp(28), dp(16), dp(16));
        TextView brand = text("Lazhar\nTV Cast", 26, C_TEXT);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setPadding(dp(12), 0, 0, dp(24));
        side.addView(brand);
        for (String[] n : NAV) {
            TextView item = text(n[1], 18, C_TEXT);
            item.setTag(n[0]);
            item.setFocusable(true);
            item.setClickable(true);
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackground(navBackground());
            item.setOnClickListener(v -> select(n[0], true));
            item.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (!current.equals(n[0])) select(n[0], true);
                    else focusContent();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            side.addView(item, lp);
            navViews.add(item);
        }
        root.addView(side, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));

        // Content
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(32), dp(28), dp(32), dp(16));
        headTitle = text("", 30, C_TEXT);
        headTitle.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(headTitle);
        headSub = text("", 15, C_MUTED);
        headSub.setPadding(0, dp(4), 0, dp(14));
        content.addView(headSub);

        searchBox = new EditText(this);
        searchBox.setHint("Type a channel, movie or country…");
        searchBox.setHintTextColor(Color.parseColor(C_MUTED));
        searchBox.setTextColor(Color.parseColor(C_TEXT));
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setBackground(chipBackground());
        searchBox.setPadding(dp(16), dp(12), dp(16), dp(12));
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
                ui.postDelayed(searchRunnable, 300);
            }
        });
        searchBox.setOnEditorActionListener((v, a, e) -> {
            runSearch();
            list.requestFocus();
            return true;
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp.bottomMargin = dp(12);
        content.addView(searchBox, sbp);

        chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chips);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = dp(12);
        content.addView(chipsScroll, cp);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(4));
        list.setSelector(focusFill());
        list.setDrawSelectorOnTop(false);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Row r = listAdapter.rows.get(pos);
            if (r.click != null) r.click.run();
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(16));
        grid.setVerticalSpacing(dp(18));
        grid.setSelector(focusBorder());
        grid.setDrawSelectorOnTop(true);
        grid.setAdapter(gridAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Catalog.Title t = gridAdapter.items.get(pos);
            if (t.externalUrl != null) openInTubi(t); // one click: straight into the movie
            else showTitle(t);
        });
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setClipToPadding(false);
        body.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = text("", 20, C_MUTED);
        emptyView.setGravity(Gravity.CENTER);
        body.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        phonePanel = new LinearLayout(this);
        phonePanel.setOrientation(LinearLayout.VERTICAL);
        phoneInfo = text("", 20, C_TEXT);
        phoneInfo.setLineSpacing(0, 1.3f);
        phonePanel.addView(phoneInfo);
        body.addView(phonePanel);

        content.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return root;
    }

    private final Runnable searchRunnable = this::runSearch;

    private void focusContent() {
        if (searchBox.getVisibility() == View.VISIBLE) searchBox.requestFocus();
        else if (chipsScroll.getVisibility() == View.VISIBLE && chips.getChildCount() > 0) {
            for (int i = 0; i < chips.getChildCount(); i++) {
                if (chips.getChildAt(i).isSelected()) {
                    chips.getChildAt(i).requestFocus();
                    return;
                }
            }
            chips.getChildAt(0).requestFocus();
        } else if (list.getVisibility() == View.VISIBLE) list.requestFocus();
        else if (grid.getVisibility() == View.VISIBLE) grid.requestFocus();
    }

    private Button chip(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.parseColor(C_TEXT));
        b.setBackground(chipBackground());
        b.setSelected(selected);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- drawables ----------------

    private Drawable navBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(C_BLUE, 12, null));
        s.addState(new int[]{android.R.attr.state_selected}, rounded("#243042", 12, null));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private Drawable chipBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(C_BLUE, 22, null));
        s.addState(new int[]{android.R.attr.state_selected}, rounded("#243042", 22, C_ACCENT));
        s.addState(new int[]{}, rounded(C_CARD, 22, null));
        return s;
    }

    private Drawable focusFill() {
        return rounded("#2A1F6FEB", 12, C_ACCENT);
    }

    private Drawable focusBorder() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(4), Color.parseColor(C_ACCENT));
        g.setCornerRadius(dp(10));
        return g;
    }

    private GradientDrawable rounded(String fill, int r, String stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(r));
        if (stroke != null) g.setStroke(dp(2), Color.parseColor(stroke));
        return g;
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

    static String ipAddress() {
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

    // ---------------- adapters ----------------

    private static final class Row {
        final String title, subtitle, badge;
        final Runnable click;

        Row(String title, String subtitle, String badge, Runnable click) {
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
                v = new LinearLayout(HomeActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(dp(18), dp(12), dp(18), dp(12));
                LinearLayout texts = new LinearLayout(HomeActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = text("", 20, C_TEXT);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                TextView s = text("", 14, C_MUTED);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(t);
                texts.addView(s);
                v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView badge = text("", 16, C_MUTED);
                badge.setPadding(dp(14), 0, 0, 0);
                v.addView(badge);
            }
            Row r = rows.get(pos);
            LinearLayout texts = (LinearLayout) v.getChildAt(0);
            ((TextView) texts.getChildAt(0)).setText(r.title);
            TextView sub = (TextView) texts.getChildAt(1);
            sub.setText(r.subtitle);
            sub.setVisibility(TextUtils.isEmpty(r.subtitle) ? View.GONE : View.VISIBLE);
            ((TextView) v.getChildAt(1)).setText(r.badge == null ? "" : r.badge);
            return v;
        }
    }

    private final class PosterAdapter extends BaseAdapter {
        List<Catalog.Title> items = new ArrayList<>();

        void set(List<Catalog.Title> t) {
            items = t;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
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
                v = new LinearLayout(HomeActivity.this);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setPadding(dp(6), dp(6), dp(6), dp(6));
                ImageView img = new ImageView(HomeActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(rounded(C_CARD, 8, null));
                img.setClipToOutline(true);
                v.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));
                TextView t = text("", 15, C_TEXT);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, dp(6), 0, 0);
                v.addView(t);
                TextView y = text("", 13, C_MUTED);
                v.addView(y);
            }
            Catalog.Title t = items.get(pos);
            ImageLoader.load((ImageView) v.getChildAt(0), t.imageUrl(), 360);
            ((TextView) v.getChildAt(1)).setText(t.title);
            ((TextView) v.getChildAt(2)).setText(t.year + (t.seconds > 0 ? (t.year.isEmpty() ? "" : " · ")
                    + (t.seconds / 60) + " min" : ""));
            return v;
        }
    }
}
