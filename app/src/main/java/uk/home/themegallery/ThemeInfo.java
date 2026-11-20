package uk.home.themegallery;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** One theme folder at one commit: its files, description and category. */
final class ThemeInfo {

    enum Category {
        ROTATING("Rotating wallpapers"),
        TIME_OF_DAY("Changes with the time of day"),
        FIXED("One fixed wallpaper"),
        NONE("No wallpaper (lightest)"),
        FESTIVAL("Festival themes");

        final String title;

        Category(String title) {
            this.title = title;
        }
    }

    final String name;
    final String ref;
    boolean hasConfig;
    boolean hasScreenshot;
    boolean hasWallpaper;
    boolean hasCaptions;
    final List<String> wallpapers = new ArrayList<>();
    long downloadBytes;
    String description = "";
    String configText;
    JSONObject config;
    String canonical;
    Category category = Category.NONE;
    String festivalUntil;
    boolean seasonOver;
    int rotationMinutes;

    ThemeInfo(String name, String ref) {
        this.name = name;
        this.ref = ref;
    }

    void setConfig(String text) {
        configText = text;
        try {
            config = new JSONObject(text);
            canonical = canonical(text);
        } catch (Exception e) {
            config = null;
            canonical = null;
        }
    }

    /** Works the category out from the theme's own settings, so new themes sort themselves. */
    void categorise() {
        JSONObject c = config != null ? config : new JSONObject();
        boolean festival = c.has("festival") || c.has("festival_until") || c.has("seasonal_skin")
                || name.toLowerCase(Locale.ROOT).contains("christmas");
        festivalUntil = c.has("festival_until") ? c.optString("festival_until") : null;
        if (festivalUntil != null) {
            try {
                seasonOver = LocalDate.parse(festivalUntil).isBefore(LocalDate.now());
            } catch (Exception ignored) {
                seasonOver = false;
            }
        }
        Object moodsValue = c.opt("wallpaper_moods");
        boolean moods = moodsValue != null && !Boolean.FALSE.equals(moodsValue);
        boolean wallpaperOn = c.optBoolean("wallpaper", true);
        int n = wallpapers.size();
        rotationMinutes = c.optInt("wallpaper_rotation_minutes", 0);
        if (festival) category = Category.FESTIVAL;
        else if (!wallpaperOn || (n == 0 && !hasWallpaper)) category = Category.NONE;
        else if (moods && n >= 2) category = Category.TIME_OF_DAY;
        else if (n >= 2) category = Category.ROTATING;
        else category = Category.FIXED;
    }

    String screenshotPath() {
        return "themes/" + name + "/screenshot.jpg";
    }

    /** One line about the wallpapers and the download size. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        int n = wallpapers.size();
        if (n >= 2) {
            sb.append(n).append(" wallpapers");
            if (category == Category.TIME_OF_DAY) sb.append(", matched to the time of day");
            else if (rotationMinutes > 0) sb.append(", a new one every ").append(minutes(rotationMinutes));
        } else if (hasWallpaper || n == 1) {
            sb.append("One wallpaper");
        } else {
            sb.append("No wallpaper");
        }
        sb.append("  ·  ").append(size(downloadBytes)).append(" to download");
        if (festivalUntil != null) {
            sb.append(seasonOver ? "  ·  season ended " : "  ·  switches itself off after ").append(festivalUntil);
        }
        return sb.toString();
    }

    private static String minutes(int m) {
        if (m % 60 == 0) return m == 60 ? "hour" : (m / 60) + " hours";
        return m + " min";
    }

    /** An estimate of what the launcher has to do for this theme, from its settings (not a measurement). */
    static final class Cost {
        double wallpaperMb;
        double peakMb;
        double swapsPerHour;
        boolean moodSwaps;
        final List<String> effects = new ArrayList<>();
        double score;
        String rating;
        int ratingLevel; // 0 light, 1 moderate, 2 heavier
    }

    /**
     * How the launcher spends memory and processor time on a theme (see its WallpaperPlayer): one wallpaper is
     * decoded at screen size into graphics memory (RGB_565, or ARGB_8888 with wallpaper_true_color), the old one is
     * released just after a swap, each swap is one decode, and the colour glow repaints the screen while it fades.
     */
    Cost cost(int screenW, int screenH) {
        Cost c = new Cost();
        JSONObject cfg = config != null ? config : new JSONObject();
        boolean wallpaperOn = cfg.optBoolean("wallpaper", true) && (hasWallpaper || !wallpapers.isEmpty());
        if (wallpaperOn) {
            int bytesPerPixel = cfg.optBoolean("wallpaper_true_color", false) ? 4 : 2;
            c.wallpaperMb = (double) screenW * screenH * bytesPerPixel / (1024.0 * 1024.0);
            c.peakMb = c.wallpaperMb;
            if (wallpapers.size() >= 2) {
                c.peakMb = c.wallpaperMb * 2;
                if (category == Category.TIME_OF_DAY) {
                    c.moodSwaps = true;
                    if (rotationMinutes > 0) c.swapsPerHour = 60.0 / rotationMinutes;
                } else if (rotationMinutes > 0) {
                    c.swapsPerHour = 60.0 / rotationMinutes;
                }
            }
        }
        boolean reduced = cfg.optBoolean("reduced_motion", false);
        if (cfg.optBoolean("ambient_glow", true) && !cfg.optBoolean("glow_snap", false)) {
            c.effects.add("colour glow fades behind the focused app");
        }
        if (cfg.optBoolean("advanced_effects", false)) c.effects.add("advanced effects (tile lift, icon zoom)");
        if (cfg.has("seasonal_skin") || cfg.has("festival")) c.effects.add("festival decorations and animation");
        if (cfg.optBoolean("fade_rows", false)) c.effects.add("faded rows");

        double score = c.wallpaperMb / 4.0;                       // ~0, 1 (normal) or 2 (true colour) at 1080p
        if (c.swapsPerHour >= 6) score += 1.5;
        else if (c.swapsPerHour >= 1) score += 1;
        else if (c.swapsPerHour > 0 || c.moodSwaps) score += 0.5;
        if (c.peakMb > c.wallpaperMb) score += 0.5;               // two pictures briefly during a swap
        for (String e : c.effects) score += e.startsWith("faded") ? 0.25 : 1;
        if (reduced) score -= 0.75;
        c.score = Math.max(0, score);
        c.ratingLevel = c.score <= 1.5 ? 0 : c.score <= 3.25 ? 1 : 2;
        c.rating = c.ratingLevel == 0 ? "Light" : c.ratingLevel == 1 ? "Moderate" : "Heavier";
        return c;
    }

    /** The estimate in words, for the details panel. */
    static String describe(Cost c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Estimated load: ").append(c.rating).append('\n');
        if (c.wallpaperMb > 0) {
            sb.append("•  Memory: about ").append(Math.round(c.wallpaperMb)).append(" MB of graphics memory for the wallpaper");
            if (c.peakMb > c.wallpaperMb) sb.append(" (").append(Math.round(c.peakMb)).append(" MB for a moment while swapping)");
            sb.append('\n');
        } else {
            sb.append("•  Memory: no wallpaper to hold\n");
        }
        if (c.swapsPerHour > 0) {
            sb.append("•  Processor: one picture decode ");
            if (c.swapsPerHour >= 1) sb.append(Math.round(c.swapsPerHour)).append("× an hour");
            else sb.append("every ").append(Math.round(60 / c.swapsPerHour)).append(" min");
            sb.append(c.moodSwaps ? ", within the time-of-day set\n" : "\n");
        } else if (c.moodSwaps) {
            sb.append("•  Processor: a picture decode when morning, evening or night begins\n");
        } else {
            sb.append("•  Processor: no picture swaps\n");
        }
        if (!c.effects.isEmpty()) sb.append("•  Effects: ").append(String.join(", ", c.effects)).append('\n');
        sb.append("(Worked out from the theme's settings and your screen size, not measured.)");
        return sb.toString();
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.UK, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** The config's top-level keys in a fixed order, so two copies of the same theme compare equal. */
    static String canonical(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            TreeMap<String, String> m = new TreeMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (ThemeApplier.CARRIED_OVER.contains(k)) continue; // the owner's own additions, not the theme's
                m.put(k, String.valueOf(o.opt(k)));
            }
            return m.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
