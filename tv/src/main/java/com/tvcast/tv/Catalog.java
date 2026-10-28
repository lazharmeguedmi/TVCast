package com.tvcast.tv;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Live TV channels and free movies/shows, downloaded from the project's GitHub and cached on the TV. */
final class Catalog {

    private static final String BASE = "https://raw.githubusercontent.com/lazharmeguedmi/TVCast/main/catalog/";
    private static final long MAX_AGE_MS = 12L * 60 * 60 * 1000;

    // ---------- live TV ----------
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
        final List<Country> countries = new ArrayList<>();
    }

    // ---------- movies / shows ----------
    static final class Title {
        String id, title, year, description, file;
        int seconds;
        /** For titles that play in another app (Tubi): the page to open and the poster. */
        String externalUrl, image, kind;

        String imageUrl() {
            if (image != null) return image;
            return "https://archive.org/services/img/" + id;
        }

        String videoUrl() {
            try {
                return "https://archive.org/download/" + id + "/"
                        + URLEncoder.encode(file, "UTF-8").replace("+", "%20").replace("%2F", "/");
            } catch (Exception e) {
                return "https://archive.org/download/" + id + "/" + file;
            }
        }
    }

    static final class Row {
        String name;
        int[] items;
    }

    static final class Section {
        String id, name;
        final List<Row> rows = new ArrayList<>();
        /** Titles the rows point into. */
        List<Title> pool = new ArrayList<>();
    }

    static volatile List<Channel> channels = new ArrayList<>();
    static volatile List<Language> languages = new ArrayList<>();
    static volatile List<Title> titles = new ArrayList<>();
    static volatile List<Section> sections = new ArrayList<>();
    static volatile String channelsUpdated = "", vodUpdated = "";

    // ---------- loading ----------

    static void loadChannels(Context ctx, boolean force) throws Exception {
        JSONObject root = new JSONObject(fetch(ctx, "channels.json", force));
        List<Channel> chs = new ArrayList<>();
        JSONArray a = root.getJSONArray("channels");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Channel c = new Channel();
            c.name = o.optString("n");
            c.country = o.optString("c");
            c.category = o.optString("g");
            c.quality = o.optString("q");
            c.geo = o.optBoolean("geo");
            JSONArray s = o.getJSONArray("s");
            c.urls = new String[s.length()];
            c.refs = new String[s.length()];
            c.uas = new String[s.length()];
            for (int j = 0; j < s.length(); j++) {
                JSONArray st = s.getJSONArray(j);
                c.urls[j] = st.optString(0);
                c.refs[j] = st.optString(1);
                c.uas[j] = st.optString(2);
            }
            chs.add(c);
        }
        List<Language> langs = new ArrayList<>();
        JSONArray la = root.getJSONArray("languages");
        for (int i = 0; i < la.length(); i++) {
            JSONObject o = la.getJSONObject(i);
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
            langs.add(l);
        }
        channels = chs;
        languages = langs;
        channelsUpdated = root.optString("updated");
    }

    static void loadVod(Context ctx, boolean force) throws Exception {
        JSONObject root = new JSONObject(fetch(ctx, "vod.json", force));
        List<Title> ts = new ArrayList<>();
        JSONArray a = root.getJSONArray("items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Title t = new Title();
            t.id = o.optString("id");
            t.title = o.optString("t");
            t.year = o.optString("y");
            t.description = o.optString("d");
            t.file = o.optString("f");
            t.seconds = o.optInt("s");
            ts.add(t);
        }
        List<Section> secs = new ArrayList<>();
        JSONArray sa = root.getJSONArray("sections");
        for (int i = 0; i < sa.length(); i++) {
            JSONObject o = sa.getJSONObject(i);
            Section s = new Section();
            s.id = o.optString("id");
            s.name = o.optString("name");
            JSONArray rows = o.getJSONArray("rows");
            for (int j = 0; j < rows.length(); j++) {
                JSONObject r = rows.getJSONObject(j);
                Row row = new Row();
                row.name = r.optString("name");
                JSONArray it = r.getJSONArray("items");
                row.items = new int[it.length()];
                for (int k = 0; k < it.length(); k++) row.items[k] = it.getInt(k);
                s.rows.add(row);
            }
            secs.add(s);
        }
        for (Section s : secs) s.pool = ts;
        titles = ts;
        List<Section> all = new ArrayList<>(secs);
        Section tubi = section("tubi");
        if (tubi != null) all.add(tubi);
        sections = all;
        vodUpdated = root.optString("updated");
    }

    /** "Free on Tubi": what's on Tubi now; plays in the Tubi app. */
    static void loadTubi(Context ctx, boolean force) throws Exception {
        JSONObject root = new JSONObject(fetch(ctx, "tubi.json", force));
        List<Title> ts = new ArrayList<>();
        JSONArray a = root.getJSONArray("items");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Title t = new Title();
            t.id = o.optString("id");
            t.title = o.optString("t");
            t.year = "";
            t.description = o.isNull("d") ? "" : o.optString("d");
            t.externalUrl = o.optString("u");
            t.image = o.isNull("i") ? null : o.optString("i");
            t.kind = o.optString("k");
            ts.add(t);
        }
        Section s = new Section();
        s.id = "tubi";
        s.name = "Free on Tubi";
        s.pool = ts;
        JSONArray rows = root.getJSONArray("rows");
        for (int j = 0; j < rows.length(); j++) {
            JSONObject r = rows.getJSONObject(j);
            Row row = new Row();
            row.name = r.optString("name");
            JSONArray it = r.getJSONArray("items");
            row.items = new int[it.length()];
            for (int k = 0; k < it.length(); k++) row.items[k] = it.getInt(k);
            s.rows.add(row);
        }
        List<Section> all = new ArrayList<>();
        for (Section x : sections) if (!x.id.equals("tubi")) all.add(x);
        all.add(s);
        sections = all;
        tubiTitles = ts;
    }

    static volatile List<Title> tubiTitles = new ArrayList<>();

    static Section section(String id) {
        for (Section s : sections) if (s.id.equals(id)) return s;
        return null;
    }

    /** Returns the cached file, downloading a fresh copy when old or forced (keeps the cache if offline). */
    private static String fetch(Context ctx, String name, boolean force) throws IOException {
        File file = new File(ctx.getFilesDir(), name);
        boolean stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS;
        if (force || stale) {
            try {
                download(BASE + name, file);
            } catch (IOException e) {
                if (!file.exists()) throw new IOException("Couldn't download the list. Check the internet connection.");
            }
        }
        return read(file);
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            try {
                new JSONObject(read(tmp));
            } catch (Exception e) {
                tmp.delete();
                throw new IOException("invalid list");
            }
            if (!tmp.renameTo(dest)) throw new IOException("could not save");
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
