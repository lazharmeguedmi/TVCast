package com.tvcast.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;


/**
 * Phone ↔ TV link. "Connected" means BOTH directions work:
 *   phone → TV  (the phone can send commands to Lazhar TV Cast on the TV), and
 *   TV → phone  (the TV can fetch the video from the phone).
 */
final class Connection {

    static final int UNKNOWN = 0, CHECKING = 1, CONNECTED = 2, ONE_WAY = 3, NOT_FOUND = 4, OTHER_TARGET = 5;

    static volatile int state = UNKNOWN;
    static volatile String tvName = "";
    static volatile String detail = "";
    private static volatile boolean busy;

    private Connection() {
    }

    /** Connected to the TV app in both directions (green). */
    static boolean isConnected() {
        return state == CONNECTED;
    }

    /** Something can play: the TV app, or a fallback the user chose (orange). */
    static boolean canSend() {
        return state == CONNECTED || state == OTHER_TARGET;
    }

    /**
     * @param search   look for the TV on the Wi-Fi first (otherwise only if no TV is known)
     * @param announce show "phone connected" on the TV
     */
    static void check(Context ctx, boolean search, boolean announce, Runnable done) {
        if (busy) return;
        busy = true;
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        if (search || state == UNKNOWN || state == NOT_FOUND) {
            state = CHECKING;
            if (done != null) main.post(done);
        }
        new Thread(() -> {
            try {
                PhoneServer.get(app); // the TV needs to reach this
                // Always look for the TV app first
                Target t = Target.current(app);
                if (t == null || t.type != Target.APP || search) {
                    Target.findAppsNow(app);
                    t = Target.current(app);
                }
                if (t == null || t.type != Target.APP) {
                    Target fb = Target.fallback(app);
                    if (fb != null) {
                        state = OTHER_TARGET;
                        tvName = fb.label();
                        detail = "Lazhar TV Cast app not found on the TV, so the " + fb.label()
                                + " is used. Open the TV app and tap Connect to use it instead.";
                    } else {
                        state = NOT_FOUND;
                        detail = "Open Lazhar TV Cast on the TV (same Wi-Fi as this phone), then tap Connect.";
                    }
                    return;
                }
                String reply;
                try {
                    reply = TvApps.hello(t.tv, phoneName(app), CastService.PORT, announce);
                } catch (Exception first) {
                    // TV may have a new address: search again once
                    Target.findAppsNow(app);
                    t = Target.current(app);
                    if (t == null || t.type != Target.APP) throw first;
                    reply = TvApps.hello(t.tv, phoneName(app), CastService.PORT, announce);
                }
                tvName = t.tv.name;
                if ("true".equals(TvApps.jsonField(reply, "reverse"))) {
                    state = CONNECTED;
                    detail = "";
                } else {
                    state = ONE_WAY;
                    detail = "The TV can't reach this phone, so videos can't play. Turn off any VPN on the phone "
                            + "and make sure the phone and TV are on the same Wi-Fi (not a guest network).";
                }
            } catch (Exception e) {
                state = NOT_FOUND;
                detail = "Can't reach Lazhar TV Cast on the TV. Make sure the TV is on and the app is open.";
            } finally {
                if (state == NOT_FOUND && LanNetwork.vpnActive(app)) {
                    detail = "A VPN is on and blocks your TV. Turn it off in Settings → Network & internet → VPN "
                            + "(on Pixel also \"VPN by Google\"), then tap Connect.";
                }
                busy = false;
                if (done != null) main.post(done);
            }
        }).start();
    }

    static String phoneName(Context ctx) {
        String n = null;
        try {
            n = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n;
    }

}
