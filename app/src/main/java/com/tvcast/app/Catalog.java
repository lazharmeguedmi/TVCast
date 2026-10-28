package com.tvcast.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of free TV channels, grouped Language -> Country -> Channels.
 * Built daily on GitHub from the open iptv-org database and cached on the phone.
 */
final class Catalog {

    static final String URL =
            "https://raw.githubusercontent.com/lazharmeguedmi/TVCast/main/catalog/channels.json";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    static final class Channel {
        String name, country, category, quality;
        boolean geo;
        String[] urls, refs, uas;
    }

    static final class Country {
        String code, name, flag;
        int[] channels;
    }

    static final class Language {
        String code, name;
        int count;
        List<Country> countries = new ArrayList<>();
    }

    String updated = "";
    final List<Channel> channels = new ArrayList<>();
    final List<Language> languages = new ArrayList<>();

    static volatile Catalog current;

    /** Loads the cached list, downloading a fresh copy if it's older than a day (or if forced). */
    static Catalog load(Context ctx, boolean forceDownload) throws Exception {
        File file = new File(ctx.getFilesDir(), "channels.json");
        boolean stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS;
        if (forceDownload || stale) {
            try {
                download(file);
            } catch (IOException e) {
                if (!file.exists()) throw new IOException("Couldn't download the channel list. Check your internet.");
            }
        }
        Catalog c = parse(readFile(file));
        current = c;
        return c;
    }

    private static void download(File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            try {
                parse(readFile(tmp)); // make sure it's valid before replacing the cache
            } catch (Exception e) {
                tmp.delete();
                throw new IOException("downloaded list was invalid");
            }
            if (!tmp.renameTo(dest)) throw new IOException("could not save");
        } finally {
            c.disconnect();
        }
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Catalog parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Catalog cat = new Catalog();
        cat.updated = root.optString("updated");
        JSONArray chs = root.getJSONArray("channels");
        for (int i = 0; i < chs.length(); i++) {
            JSONObject o = chs.getJSONObject(i);
            Channel ch = new Channel();
            ch.name = o.optString("n");
            ch.country = o.optString("c");
            ch.category = o.optString("g");
            ch.quality = o.optString("q");
            ch.geo = o.optBoolean("geo");
            JSONArray s = o.getJSONArray("s");
            ch.urls = new String[s.length()];
            ch.refs = new String[s.length()];
            ch.uas = new String[s.length()];
            for (int j = 0; j < s.length(); j++) {
                JSONArray st = s.getJSONArray(j);
                ch.urls[j] = st.optString(0);
                ch.refs[j] = st.optString(1);
                ch.uas[j] = st.optString(2);
            }
            cat.channels.add(ch);
        }
        JSONArray langs = root.getJSONArray("languages");
        for (int i = 0; i < langs.length(); i++) {
            JSONObject o = langs.getJSONObject(i);
            Language l = new Language();
            l.code = o.optString("code");
            l.name = o.optString("name");
            l.count = o.optInt("count");
            JSONArray cs = o.getJSONArray("countries");
            for (int j = 0; j < cs.length(); j++) {
                JSONObject co = cs.getJSONObject(j);
                Country c = new Country();
                c.code = co.optString("code");
                c.name = co.optString("name");
                c.flag = co.optString("flag");
                JSONArray idx = co.getJSONArray("ch");
                c.channels = new int[idx.length()];
                for (int k = 0; k < idx.length(); k++) c.channels[k] = idx.getInt(k);
                l.countries.add(c);
            }
            cat.languages.add(l);
        }
        return cat;
    }
}
