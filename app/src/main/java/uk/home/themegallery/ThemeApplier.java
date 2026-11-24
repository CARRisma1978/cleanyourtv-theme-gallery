package uk.home.themegallery;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Applies a theme through the launcher's ThemeProvider, in the same order as cleanyourtv's tools/theme.ps1:
 * everything is downloaded and checked first, then the old theme's files are replaced or deleted. Nothing of the
 * old theme is kept.
 */
final class ThemeApplier {
    static final String LAUNCHER = "com.gathin.tvhome";
    /**
     * Settings the owner added to the theme on the TV that are about their apps, not the look: kept when a new
     * theme is applied (library_apps puts non-TV apps such as a sideloaded phone app on Home; app_banners,
     * app_labels and launch_actions give a tile its picture, its name and where it goes, e.g. a Live TV tile
     * that opens the aerial tuner directly).
     */
    static final java.util.Set<String> CARRIED_OVER = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "library_apps", "app_banners", "app_labels", "launch_actions")));
    private static final String AUTHORITY = "content://" + LAUNCHER + ".theme/";

    interface Progress {
        void update(String message);
    }

    private ThemeApplier() {}

    static boolean launcherInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(LAUNCHER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static Uri uri(String path) {
        return Uri.parse(AUTHORITY + path);
    }

    /** The launcher's current config.json, or null when there is none (built-in look) or it cannot be read. */
    static String currentConfig(Context c) {
        try (InputStream in = c.getContentResolver().openInputStream(uri("config.json"))) {
            if (in == null) return null;
            return new String(Net.readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void apply(Context ctx, ThemeInfo t, Progress p) throws Exception {
        if (t.configText == null) throw new IOException("this theme's settings could not be read");
        new JSONObject(t.configText); // must be a JSON object, or the launcher would drop it
        for (String n : t.wallpapers) {
            if (!ThemeRepo.IMAGE.matcher(n).matches() || n.contains("..")) {
                throw new IOException("unsupported wallpaper name " + n);
            }
        }

        // 1. Download everything before touching the TV's current theme.
        File work = new File(ctx.getCacheDir(), "apply");
        Net.deleteRecursive(work);
        work.mkdirs();
        try {
            String base = "themes/" + t.name + "/";
            File wall = null;
            if (t.hasWallpaper) {
                p.update("Downloading wallpaper…");
                wall = new File(work, "wallpaper.jpg");
                Net.download(ThemeRepo.raw(t.ref, base + "wallpaper.jpg"), wall);
            }
            int i = 0;
            for (String n : t.wallpapers) {
                i++;
                p.update("Downloading wallpaper " + i + " of " + t.wallpapers.size() + "…");
                Net.download(ThemeRepo.raw(t.ref, base + "wallpapers/" + n), new File(work, "w-" + n));
            }
            File captions = null;
            if (t.hasCaptions) {
                captions = new File(work, "captions.txt");
                Net.download(ThemeRepo.raw(t.ref, base + "wallpapers/captions.txt"), captions);
            }

            // 2. Replace the old theme (same order as theme.ps1).
            p.update("Applying " + t.name + "…");
            ContentResolver cr = ctx.getContentResolver();
            write(cr, "config.json", withCarriedOver(ctx, t.configText).getBytes(StandardCharsets.UTF_8));
            if (wall != null) write(cr, "wallpaper.jpg", wall);
            else cr.delete(uri("wallpaper.jpg"), null, null);
            cr.delete(uri("wallpapers"), null, null); // every image of the old collection
            for (String n : t.wallpapers) write(cr, "wallpapers/" + n, new File(work, "w-" + n));
            if (captions != null) write(cr, "wallpapers/captions.txt", captions);
            else cr.delete(uri("wallpapers/captions.txt"), null, null);
            write(cr, "theme_revision",
                    UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        } finally {
            Net.deleteRecursive(work);
        }
    }

    /** The new theme's config plus the CARRIED_OVER settings of the config on the TV, when the theme has none. */
    static String withCarriedOver(Context ctx, String themeConfig) throws Exception {
        String current = currentConfig(ctx);
        if (current == null) return themeConfig;
        JSONObject now;
        try {
            now = new JSONObject(current);
        } catch (Exception e) {
            return themeConfig;
        }
        JSONObject next = new JSONObject(themeConfig);
        boolean changed = false;
        for (String key : CARRIED_OVER) {
            if (now.has(key) && !next.has(key)) {
                next.put(key, now.get(key));
                changed = true;
            }
        }
        return changed ? next.toString(2) : themeConfig;
    }

    private static void write(ContentResolver cr, String path, byte[] data) throws IOException {
        try (OutputStream out = cr.openOutputStream(uri(path), "w")) {
            if (out == null) throw new IOException("the launcher refused " + path);
            out.write(data);
        }
        verify(cr, path, data.length);
    }

    private static void write(ContentResolver cr, String path, File src) throws IOException {
        try (OutputStream out = cr.openOutputStream(uri(path), "w"); InputStream in = new FileInputStream(src)) {
            if (out == null) throw new IOException("the launcher refused " + path);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        verify(cr, path, src.length());
    }

    /** Reads the file back through the provider: the launcher must hold exactly what was sent. */
    private static void verify(ContentResolver cr, String path, long expected) throws IOException {
        long n = 0;
        try (InputStream in = cr.openInputStream(uri(path))) {
            if (in == null) throw new IOException(path + " was not written");
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
        }
        if (n != expected) throw new IOException(path + " was not fully written");
    }
}
