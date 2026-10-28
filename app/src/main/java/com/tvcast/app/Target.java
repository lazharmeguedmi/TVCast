package com.tvcast.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where things play:
 *  APP     - the TV Cast app on the TV (best: automatic, full-screen, no browser)
 *  DLNA    - the TV's built-in media player
 *  BROWSER - the TV's web browser page (fallback)
 */
final class Target {

    static final int APP = 0, DLNA = 1, BROWSER = 2;

    final int type;
    final TvApps.Tv tv;
    final Dlna.Renderer renderer;

    private Target(int type, TvApps.Tv tv, Dlna.Renderer renderer) {
        this.type = type;
        this.tv = tv;
        this.renderer = renderer;
    }

    static Target app(TvApps.Tv tv) {
        return new Target(APP, tv, null);
    }

    static Target dlna(Dlna.Renderer r) {
        return new Target(DLNA, null, r);
    }

    static Target browser() {
        return new Target(BROWSER, null, null);
    }

    String label() {
        switch (type) {
            case APP:
                return tv.name;
            case DLNA:
                return renderer.name + " (TV player)";
            default:
                return "TV web browser";
        }
    }

    String spec() {
        switch (type) {
            case APP:
                return "app|" + tv.host + "|" + tv.port + "|" + tv.name;
            case DLNA:
                return "dlna|" + renderer.location;
            default:
                return "browser";
        }
    }

    static Target fromSpec(String spec) {
        if (spec == null) return null;
        String[] p = spec.split("\\|", 4);
        if (p[0].equals("app") && p.length == 4) {
            try {
                return app(new TvApps.Tv(p[3], p[1], Integer.parseInt(p[2])));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (p[0].equals("dlna") && p.length >= 2) {
            for (Dlna.Renderer r : renderers) if (r.location.equals(p[1])) return dlna(r);
            return null;
        }
        if (p[0].equals("browser")) return browser();
        return null;
    }

    // =====================================================================
    // Discovery + the user's choice
    // =====================================================================

    static volatile List<TvApps.Tv> tvs = Collections.emptyList();
    static volatile List<Dlna.Renderer> renderers = Collections.emptyList();
    static volatile boolean searching;
    static volatile boolean searchedOnce;

    private static final String PREFS = "tvcast";
    private static final String PREF_CHOICE = "target";      // "auto" | spec
    private static final String PREF_LAST_TV = "last_tv";    // spec of last TV app seen

    /** Searches the Wi-Fi for TV Cast on the TV (fast) and TV media players. */
    static void discover(Context ctx, Runnable done) {
        if (searching) return;
        searching = true;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("TVCast-find") : null;
            try {
                if (lock != null) lock.acquire();
                List<String> known = new ArrayList<>();
                Target last = fromSpec(prefs(app).getString(PREF_LAST_TV, null));
                if (last != null && last.type == APP) known.add(last.tv.host);
                List<TvApps.Tv> found = TvApps.find(1500, known);
                tvs = found;
                if (!found.isEmpty()) prefs(app).edit().putString(PREF_LAST_TV, app(found.get(0)).spec()).apply();
                searchedOnce = true;
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
                List<Dlna.Renderer> r = Dlna.discover(3000);
                if (!r.isEmpty() || renderers.isEmpty()) renderers = r;
            } catch (Exception ignored) {
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
                searching = false;
                searchedOnce = true;
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
            }
        }).start();
    }

    /** Looks for Lazhar TV Cast on the Wi-Fi right now (blocking, ~1.5 s). */
    static List<TvApps.Tv> findAppsNow(Context ctx) {
        Context app = ctx.getApplicationContext();
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("TVCast-find-now") : null;
        try {
            if (lock != null) lock.acquire();
            List<String> known = new ArrayList<>();
            Target last = fromSpec(prefs(app).getString(PREF_LAST_TV, null));
            if (last != null && last.type == APP) known.add(last.tv.host);
            List<TvApps.Tv> found = TvApps.find(1500, known);
            tvs = found;
            if (!found.isEmpty()) prefs(app).edit().putString(PREF_LAST_TV, app(found.get(0)).spec()).apply();
            searchedOnce = true;
            return found;
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    /** The target to use now, or null if no TV was found yet. */
    static Target current(Context ctx) {
        String choice = prefs(ctx).getString(PREF_CHOICE, "auto");
        if (!choice.equals("auto")) {
            Target t = fromSpec(choice);
            if (t != null && t.type == APP) {
                for (TvApps.Tv tv : tvs) if (tv.host.equals(t.tv.host)) return app(tv);
            }
            if (t != null) return t;
        }
        List<TvApps.Tv> list = tvs;
        if (!list.isEmpty()) return app(list.get(0));
        return null;
    }

    static void choose(Context ctx, Target t) {
        prefs(ctx).edit().putString(PREF_CHOICE, t == null ? "auto" : t.spec()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "Play on" picker shared by all screens. */
    static void showPicker(Activity a, Runnable changed) {
        final List<Target> options = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (TvApps.Tv tv : tvs) {
            options.add(app(tv));
            labels.add("📺  " + tv.name + "  (Lazhar TV Cast app — recommended)");
        }
        for (Dlna.Renderer r : renderers) {
            options.add(dlna(r));
            labels.add("🎞  " + r.name + "  (TV's built-in player)");
        }
        options.add(browser());
        String url = CastService.serverUrl();
        labels.add("🌐  TV web browser" + (url != null ? "  (open " + url + ")" : ""));
        options.add(null);
        labels.add("🔄  Search again");

        new AlertDialog.Builder(a)
                .setTitle("Play on")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Target t = options.get(which);
                    if (t == null) {
                        discover(a, changed);
                        return;
                    }
                    choose(a, t);
                    if (changed != null) changed.run();
                })
                .show();
    }
}
