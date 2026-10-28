package com.tvcast.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.net.HttpURLConnection;
import java.net.URL;

/** Talks to the phone over the home Wi-Fi even if a VPN app is running on the Fire TV. */
final class LanNet {

    private LanNet() {
    }

    static Network wifi(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return n;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean vpnActive(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static HttpURLConnection open(Context ctx, URL url) throws java.io.IOException {
        Network n = wifi(ctx);
        if (n != null) {
            try {
                return (HttpURLConnection) n.openConnection(url);
            } catch (Exception ignored) {
            }
        }
        return (HttpURLConnection) url.openConnection();
    }

    /** While playing something from the phone, send all of this app's traffic over the home Wi-Fi. */
    static void useWifiForPhone(Context ctx, boolean on) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.bindProcessToNetwork(on ? wifi(ctx) : null);
        } catch (Exception ignored) {
        }
    }
}
