package uk.home.themegallery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plain HTTPS GETs and a small write-once disk cache. Only GitHub hosts are ever contacted. */
final class Net {
    static final String USER_AGENT = "ThemeGallery/1.0 (Android TV)";

    private Net() {}

    static byte[] get(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (accept != null) c.setRequestProperty("Accept", accept);
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429) {
                throw new IOException("GitHub is limiting requests right now (HTTP " + code
                        + "). Try again in an hour.");
            }
            if (code != 200) throw new IOException("GitHub answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** The cached copy of url in file, downloading it the first time. Files are keyed by commit, so never stale. */
    static byte[] cached(File file, String url, String accept) throws IOException {
        if (file.isFile() && file.length() > 0) return readFile(file);
        byte[] b = get(url, accept);
        writeFile(file, b);
        return b;
    }

    /** Downloads url straight into file (for wallpapers, to keep them out of memory). Returns the size. */
    static long download(String url, File file) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub answered HTTP " + code + " for " + file.getName());
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            long n = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    n += r;
                }
            }
            return n;
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static void writeFile(File f, byte[] b) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("could not save " + f.getName());
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
