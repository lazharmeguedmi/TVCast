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
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean("choice_v2", false)) {
            // older versions could get stuck on the web browser; start fresh on the TV app
            p.edit().putString(PREF_CHOICE, "auto").putBoolean("choice_v2", true).apply();
        }
        String choice = p.getString(PREF_CHOICE, "auto");
        Target chosen = choice.equals("auto") ? null : fromSpec(choice);
        // The TV app always wins when it's found
        List<TvApps.Tv> list = tvs;
        if (!list.isEmpty()) {
            if (chosen != null && chosen.type == APP) {
                for (TvApps.Tv tv : list) if (tv.host.equals(chosen.tv.host)) return app(tv);
            }
            if (chosen == null || chosen.type == APP) return app(list.get(0));
        }
        // No TV app: use an explicitly chosen fallback (TV media player / web browser)
        if (chosen != null && chosen.type != APP) return chosen;
        return null;
    }

    /** The fallback (TV media player / web browser) the user picked, if any. */
    static Target fallback(Context ctx) {
        String choice = prefs(ctx).getString(PREF_CHOICE, "auto");
        Target t = choice.equals("auto") ? null : fromSpec(choice);
        return t != null && t.type != APP ? t : null;
    }

    static void choose(Context ctx, Target t) {
        prefs(ctx).edit().putString(PREF_CHOICE, t == null ? "auto" : t.spec()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Connect by typing the TV's IP address (for Wi-Fi routers that block automatic discovery). */
    static void enterAddress(Activity a, Runnable changed) {
        final android.widget.EditText input = new android.widget.EditText(a);
        input.setHint("e.g. 192.168.1.154");
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        Target last = fromSpec(prefs(a).getString(PREF_LAST_TV, null));
        if (last != null && last.type == APP) input.setText(last.tv.host);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(a)
                .setTitle("TV address")
                .setMessage("Open Lazhar TV Cast on the TV — its address is shown under the menu "
                        + "(📱 Phone / Mirror page).")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> {
                    final String host = input.getText().toString().trim();
                    final Context app = a.getApplicationContext();
                    prefs(app).edit().putString("typed_host", host).apply();
                    new Thread(() -> {
                        TvApps.Tv tv = TvApps.probe(host, TvApps.HTTP_PORT);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (tv == null) {
                                android.widget.Toast.makeText(app, "No Lazhar TV Cast at " + host
                                        + ". Check the address and that the TV app is open.",
                                        android.widget.Toast.LENGTH_LONG).show();
                                return;
                            }
                            List<TvApps.Tv> l = new ArrayList<>(tvs);
                            l.removeIf(x -> x.host.equals(tv.host));
                            l.add(0, tv);
                            tvs = l;
                            prefs(app).edit().putString(PREF_LAST_TV, app(tv).spec()).apply();
                            choose(app, app(tv));
                            android.widget.Toast.makeText(app, "Found " + tv.name,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            if (changed != null) changed.run();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =====================================================================
    // Connection test: tells apart "different network" from "TV app not running"
    // =====================================================================

    static void testConnection(Activity a) {
        final android.widget.EditText input = new android.widget.EditText(a);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        Target last = fromSpec(prefs(a).getString(PREF_LAST_TV, null));
        String host = last != null && last.type == APP ? last.tv.host : prefs(a).getString("typed_host", "");
        input.setText(host);
        input.setHint("TV address, e.g. 192.168.1.154");
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(a)
                .setTitle("Test connection")
                .setMessage("Enter the TV's address (shown on the TV app's 📱 Phone / Mirror page).")
                .setView(box)
                .setPositiveButton("Run test", (d, w) -> {
                    String h = input.getText().toString().trim();
                    prefs(a).edit().putString("typed_host", h).apply();
                    android.app.ProgressDialog pd = android.app.ProgressDialog.show(a, null, "Testing…", true);
                    new Thread(() -> {
                        String report = runTest(a.getApplicationContext(), h);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            pd.dismiss();
                            if (a.isFinishing()) return;
                            new AlertDialog.Builder(a).setTitle("Connection test").setMessage(report)
                                    .setPositiveButton("OK", null).show();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String tcp(String host, int port) {
        try (java.net.Socket s = new java.net.Socket(java.net.Proxy.NO_PROXY)) {
            TvApps.opener.bind(s);
            s.connect(new java.net.InetSocketAddress(host, port), 2500);
            return "open";
        } catch (java.net.ConnectException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (m.contains("refused")) return "refused";
            if (m.contains("unreachable") || m.contains("no route")) return "unreachable";
            return "failed";
        } catch (java.net.SocketTimeoutException e) {
            return "timeout";
        } catch (Exception e) {
            return "error";
        }
    }

    static String runTest(Context ctx, String host) {
        StringBuilder r = new StringBuilder();
        String phoneUrl = CastService.serverUrl();
        String phoneIp = phoneUrl == null ? null : phoneUrl.replace("http://", "").replace(":" + CastService.PORT, "");
        r.append("This phone: ").append(phoneIp == null ? "NOT on Wi-Fi" : phoneIp).append('\n');
        r.append("TV: ").append(host).append('\n');
        boolean vpn = LanNetwork.vpnActive(ctx);
        r.append("VPN on this phone: ").append(vpn ? "YES" : "no").append("\n\n");
        if (phoneIp == null) {
            return r.append("➜ Turn on Wi-Fi on the phone and join the same network as the TV.").toString();
        }
        String[] a = phoneIp.split("\\."), b = host.split("\\.");
        boolean sameNet = a.length == 4 && b.length == 4 && a[0].equals(b[0]) && a[1].equals(b[1]) && a[2].equals(b[2]);
        r.append("Same network range: ").append(sameNet ? "yes" : "NO").append('\n');
        String app = tcp(host, TvApps.HTTP_PORT);
        r.append("TV app receiver (port 8090): ").append(app).append('\n');
        boolean tvSeen = !app.equals("timeout") && !app.equals("unreachable") && !app.equals("error");
        if (!app.equals("open")) {
            for (int port : new int[]{8009, 8008, 5555, 80}) {
                String res = tcp(host, port);
                r.append("TV other port ").append(port).append(": ").append(res).append('\n');
                if (res.equals("open") || res.equals("refused")) tvSeen = true;
            }
        }
        r.append('\n');
        if (app.equals("open")) {
            TvApps.Tv tv = TvApps.probe(host, TvApps.HTTP_PORT);
            if (tv == null) {
                r.append("➜ Something answers on port 8090 but it isn't Lazhar TV Cast.");
            } else {
                try {
                    String reply = TvApps.hello(tv, Connection.phoneName(ctx), CastService.PORT, true);
                    boolean reverse = "true".equals(TvApps.jsonField(reply, "reverse"));
                    r.append("Phone → TV: OK (").append(tv.name).append(")\n");
                    r.append("TV → phone: ").append(reverse ? "OK" : "BLOCKED").append("\n\n");
                    r.append(reverse ? "➜ Everything works. Tap Connect."
                            : "➜ The TV can't reach the phone. Turn off any VPN on the phone and check the router "
                            + "doesn't isolate devices.");
                    List<TvApps.Tv> l = new ArrayList<>(tvs);
                    l.removeIf(x -> x.host.equals(tv.host));
                    l.add(0, tv);
                    tvs = l;
                    prefs(ctx).edit().putString(PREF_LAST_TV, app(tv).spec()).apply();
                } catch (Exception e) {
                    r.append("➜ Reached the TV app, but it didn't reply (").append(e.getClass().getSimpleName()).append(").");
                }
            }
        } else if (tvSeen) {
            r.append("➜ The TV is reachable, but the Lazhar TV Cast receiver isn't running.\n"
                    + "Open Lazhar TV Cast on the TV, go to 📱 Phone / Mirror and check the \"Receiver\" line.");
        } else if (vpn) {
            r.append("➜ A VPN is on and is blocking your TV. Turn it off: Settings → Network & internet → VPN "
                    + "(on Pixel, also turn off \"VPN by Google\"), or allow \"local network / LAN\" in your VPN app.");
        } else if (!sameNet) {
            r.append("➜ The phone and TV are on DIFFERENT networks. Connect both to the same Wi-Fi name "
                    + "(not a guest network). Check the TV under Settings → Network.");
        } else {
            r.append("➜ The phone can't reach the TV at all. Check the TV address, and that both use the same "
                    + "Wi-Fi name. Some routers have \"AP isolation\" / \"client isolation\" or a separate "
                    + "2.4 GHz / 5 GHz / mesh \"IoT\" network that blocks devices from seeing each other — "
                    + "turn that off or put both on the same network.");
        }
        return r.toString();
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
        options.add(null);
        labels.add("🩺  Test connection");
        options.add(null);
        labels.add("⌨  Enter the TV's address (shown in the TV app)");

        new AlertDialog.Builder(a)
                .setTitle("Play on")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Target t = options.get(which);
                    if (t == null && which == labels.size() - 1) {
                        enterAddress(a, changed);
                        return;
                    }
                    if (t == null && which == labels.size() - 2) {
                        testConnection(a);
                        return;
                    }
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
