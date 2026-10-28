package com.tvcast.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The phone's media server (port 8080), shared by the whole app. It starts when the app opens so
 * the TV can check it can reach the phone ("two-way connected") before anything is sent.
 */
final class PhoneServer {

    private static CastServer server;

    private PhoneServer() {
    }

    static synchronized CastServer get(Context ctx) {
        if (server == null) {
            server = new CastServer(CastService.PORT);
            try (InputStream in = ctx.getApplicationContext().getAssets().open("hls.min.js")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[32 * 1024];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                server.setHlsJs(bo.toByteArray());
            } catch (IOException ignored) {
            }
            server.start();
        }
        return server;
    }
}
