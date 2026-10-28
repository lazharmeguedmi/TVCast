package com.tvcast.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;

/**
 * Sends phone↔TV traffic over the home Wi-Fi even when a VPN (e.g. "VPN by Google") is on.
 * A VPN normally routes everything — including replies meant for the TV — through the internet,
 * which breaks talking to devices at home.
 */
final class LanNetwork {

    private LanNetwork() {
    }

    /** The Wi-Fi (or Ethernet) network underneath any VPN. */
    static Network wifi(Context ctx) {
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

    /** Makes TvApps (discovery, commands, tests) use the home Wi-Fi directly. */
    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        TvApps.opener = new TvApps.Opener() {
            @Override
            public HttpURLConnection open(URL url) throws IOException {
                Network n = wifi(app);
                if (n != null) {
                    try {
                        return (HttpURLConnection) n.openConnection(url);
                    } catch (Exception ignored) {
                        // VPN doesn't allow bypassing: fall back to the normal route
                    }
                }
                return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            }

            @Override
            public void bind(DatagramSocket s) {
                Network n = wifi(app);
                if (n == null) return;
                try {
                    n.bindSocket(s);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void bind(Socket s) {
                Network n = wifi(app);
                if (n == null) return;
                try {
                    n.bindSocket(s);
                } catch (Exception ignored) {
                }
            }
        };
    }
}
