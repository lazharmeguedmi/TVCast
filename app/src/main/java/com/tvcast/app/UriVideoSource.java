package com.tvcast.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** A video picked from the phone (Files, Downloads, Photos...) served by CastServer. */
final class UriVideoSource implements CastServer.VideoSource {

    private final ContentResolver resolver;
    private final Uri uri;
    private final String title;
    private final String mime;
    private final long length;

    UriVideoSource(ContentResolver resolver, Uri uri) throws IOException {
        this.resolver = resolver;
        this.uri = uri;
        String name = null;
        long size = -1;
        try (Cursor c = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) name = c.getString(i);
                int j = c.getColumnIndex(OpenableColumns.SIZE);
                if (j >= 0 && !c.isNull(j)) size = c.getLong(j);
            }
        } catch (Exception ignored) {
        }
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new FileNotFoundException("cannot open video");
            long s = pfd.getStatSize();
            if (s > 0) size = s;
        }
        String m = resolver.getType(uri);
        if (m == null || !m.startsWith("video/")) m = guessMime(name);
        this.mime = m;
        this.length = size;
        this.title = name != null ? stripExtension(name) : "Video";
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String mimeType() {
        return mime;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public InputStream openAt(long offset) throws IOException {
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
        if (pfd == null) throw new FileNotFoundException("cannot open video");
        FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        if (offset > 0) {
            try {
                in.getChannel().position(offset);
            } catch (IOException e) {
                long left = offset; // not seekable (e.g. streamed from the cloud): skip forward
                while (left > 0) {
                    long k = in.skip(left);
                    if (k <= 0) {
                        in.close();
                        throw new IOException("cannot seek in video");
                    }
                    left -= k;
                }
            }
        }
        return in;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String guessMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }
}
