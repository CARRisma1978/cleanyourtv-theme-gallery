package uk.home.themegallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the theme folders of one cleanyourtv commit from GitHub: one API call lists the whole themes folder,
 * everything else comes from raw.githubusercontent.com. All of it is cached per commit, so a second visit to the
 * same version needs no network at all.
 */
final class ThemeRepo {
    static final String REPO = "Gathin23/cleanyourtv";
    static final String API = "https://api.github.com/repos/" + REPO;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile("^\\|\\s*`([A-Za-z0-9_.-]+)/`\\s*\\|\\s*(.*?)\\s*\\|\\s*<img",
            Pattern.MULTILINE);
    private static final long LATEST_TTL_MS = 60L * 60L * 1000L;

    private final Context ctx;
    final File cacheRoot;

    ThemeRepo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        cacheRoot = new File(ctx.getCacheDir(), "refs");
    }

    static String raw(String ref, String path) {
        return "https://raw.githubusercontent.com/" + REPO + "/" + ref + "/" + path;
    }

    /** {sha, date} of the newest commit on main; asked of GitHub at most once an hour. */
    String[] latest() throws IOException {
        SharedPreferences p = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE);
        String sha = p.getString("latest_sha", null);
        String date = p.getString("latest_date", "");
        if (sha != null && System.currentTimeMillis() - p.getLong("latest_at", 0) < LATEST_TTL_MS) {
            return new String[] {sha, date};
        }
        try {
            byte[] b = Net.get(API + "/commits/main", "application/vnd.github+json");
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            String s = o.getString("sha");
            String d = o.getJSONObject("commit").getJSONObject("committer").optString("date", "");
            if (d.length() >= 10) d = d.substring(0, 10);
            if (!s.matches("[0-9a-f]{40}")) throw new IOException("unexpected answer from GitHub");
            p.edit().putString("latest_sha", s).putString("latest_date", d)
                    .putLong("latest_at", System.currentTimeMillis()).apply();
            return new String[] {s, d};
        } catch (Exception e) {
            if (sha != null) return new String[] {sha, date};
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    /** Keeps the cache to the two versions the gallery shows. */
    void pruneExcept(String... keep) {
        File[] dirs = cacheRoot.listFiles();
        if (dirs == null) return;
        outer:
        for (File d : dirs) {
            for (String k : keep) if (k != null && d.getName().equals(k)) continue outer;
            Net.deleteRecursive(d);
        }
    }

    File refDir(String ref) {
        return new File(cacheRoot, ref);
    }

    List<ThemeInfo> load(String ref) throws Exception {
        File dir = refDir(ref);
        byte[] treeBytes = Net.cached(new File(dir, "tree.json"),
                API + "/git/trees/" + ref + ":themes?recursive=1", "application/vnd.github+json");
        JSONArray tree = new JSONObject(new String(treeBytes, StandardCharsets.UTF_8)).getJSONArray("tree");
        Map<String, ThemeInfo> themes = new TreeMap<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            String path = e.getString("path");
            if ("tree".equals(e.optString("type")) && !path.contains("/") && NAME.matcher(path).matches()) {
                themes.put(path, new ThemeInfo(path, ref));
            }
        }
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            if (!"blob".equals(e.optString("type"))) continue;
            String path = e.getString("path");
            int slash = path.indexOf('/');
            if (slash < 0) continue;
            ThemeInfo t = themes.get(path.substring(0, slash));
            if (t == null) continue;
            String rest = path.substring(slash + 1);
            long size = e.optLong("size", 0);
            if (rest.equals("config.json")) {
                t.hasConfig = true;
                t.downloadBytes += size;
            } else if (rest.equals("screenshot.jpg")) {
                t.hasScreenshot = true;
            } else if (rest.equals("wallpaper.jpg")) {
                t.hasWallpaper = true;
                t.downloadBytes += size;
            } else if (rest.startsWith("wallpapers/")) {
                String n = rest.substring("wallpapers/".length());
                if (n.contains("/")) continue;
                if (n.equals("captions.txt")) {
                    t.hasCaptions = true;
                    t.downloadBytes += size;
                } else if (IMAGE.matcher(n).matches() && !n.contains("..")) {
                    t.wallpapers.add(n);
                    t.downloadBytes += size;
                }
            }
        }
        List<ThemeInfo> list = new ArrayList<>();
        for (ThemeInfo t : themes.values()) if (t.hasConfig) list.add(t);

        // Descriptions from the themes README table (written for people, so a missing one is not an error).
        try {
            byte[] readme = Net.cached(new File(dir, "README.md"), raw(ref, "themes/README.md"), null);
            Matcher m = ROW.matcher(new String(readme, StandardCharsets.UTF_8));
            Map<String, String> desc = new TreeMap<>();
            while (m.find()) desc.put(m.group(1), clean(m.group(2)));
            for (ThemeInfo t : list) {
                String d = desc.get(t.name);
                if (d != null) t.description = d;
            }
        } catch (IOException ignored) {
            // names and screenshots still work
        }

        // Each theme's config.json, for its category (small files, fetched four at a time).
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (ThemeInfo t : list) {
                jobs.add(pool.submit(() -> {
                    byte[] b = Net.cached(new File(dir, "config/" + t.name + ".json"),
                            raw(ref, "themes/" + t.name + "/config.json"), null);
                    t.setConfig(new String(b, StandardCharsets.UTF_8));
                    t.categorise();
                    return null;
                }));
            }
            for (Future<?> f : jobs) f.get();
        } finally {
            pool.shutdownNow();
        }
        return list;
    }

    private static String clean(String md) {
        String s = md.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replace("`", "").replace("**", "");
        return s.replaceAll("\\s+", " ").trim();
    }
}
