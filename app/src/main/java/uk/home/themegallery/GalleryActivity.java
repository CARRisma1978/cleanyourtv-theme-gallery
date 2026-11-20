package uk.home.themegallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The gallery: two tabs (your launcher's version, latest on GitHub), themes grouped by category, details on the right. */
public class GalleryActivity extends Activity {
    private static final int BG = 0xFF0A0D18;
    private static final int PANEL = 0xFF141A2C;
    private static final int CARD = 0xFF1C2338;
    private static final int ACCENT = 0xFF4FD1C5;
    private static final int TEXT = 0xFFEDEFF5;
    private static final int DIM = 0xFF9AA3B8;
    private static final int WARN = 0xFFF6AD55;
    private static final int COLUMNS = 3;
    /** Light, moderate, heavier. */
    private static final int[] LOAD_COLORS = {0xFF68D391, 0xFFF6E05E, 0xFFFC8181};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ThemeRepo repo;
    private Button tabInstalled;
    private Button tabLatest;
    private TextView status;
    private LinearLayout list;
    private ScrollView scroll;
    private ImageView detailImage;
    private TextView detailName;
    private TextView detailBadge;
    private TextView detailMeta;
    private TextView detailCost;
    private TextView detailDesc;
    private TextView detailHint;

    private boolean latestTab;
    private int loadToken;
    private String latestSha;
    private String latestDate = "";
    private String currentCanonical;
    private final Set<String> installedNames = new HashSet<>();
    private List<ThemeInfo> shown = new ArrayList<>();
    private ThemeInfo focused;
    private boolean busy;
    private AlertDialog progressDialog;
    private TextView progressText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new ThemeRepo(this);
        setContentView(buildLayout());
        showTab(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(3), stroke);
        return g;
    }

    private StateListDrawable focusable(int fill, int focusedFill) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, rounded(focusedFill, ACCENT, 10));
        s.addState(new int[] {}, rounded(fill, 0, 10));
        return s;
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(40), dp(24), dp(40), dp(24));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));

        TextView title = text(26, TEXT, true);
        title.setText("Theme Gallery");
        left.addView(title);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(10), 0, dp(6));
        tabInstalled = tab("Your version");
        tabLatest = tab("Latest on GitHub");
        tabInstalled.setOnClickListener(v -> showTab(false));
        tabLatest.setOnClickListener(v -> showTab(true));
        tabs.addView(tabInstalled);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(12);
        tabs.addView(tabLatest, gap);
        left.addView(tabs);

        status = text(12, DIM, false);
        status.setPadding(0, dp(2), 0, dp(8));
        left.addView(status);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), 0, dp(6), dp(40));
        scroll.addView(list);
        left.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackground(rounded(PANEL, 0, 16));
        right.setPadding(dp(16), dp(16), dp(16), dp(14));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f);
        rp.leftMargin = dp(24);
        root.addView(right, rp);

        detailImage = new ImageView(this);
        detailImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        detailImage.setBackground(rounded(CARD, 0, 10));
        detailImage.setClipToOutline(true);
        right.addView(detailImage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        detailName = text(18, TEXT, true);
        detailName.setPadding(0, dp(10), 0, 0);
        right.addView(detailName);
        detailBadge = text(11, ACCENT, true);
        right.addView(detailBadge);
        detailMeta = text(11, DIM, false);
        detailMeta.setPadding(0, dp(3), 0, dp(6));
        right.addView(detailMeta);
        detailCost = text(11, TEXT, false);
        detailCost.setLineSpacing(dp(1), 1f);
        detailCost.setPadding(0, 0, 0, dp(6));
        right.addView(detailCost);
        detailDesc = text(11, DIM, false);
        detailDesc.setLineSpacing(dp(2), 1f);
        detailDesc.setMaxLines(9);
        detailDesc.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(detailDesc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        detailHint = text(10, DIM, false);
        detailHint.setText("Press OK on a theme to apply it. Back closes the gallery.");
        right.addView(detailHint);
        return root;
    }

    private Button tab(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(TEXT);
        b.setPadding(dp(18), dp(6), dp(18), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(focusable(CARD, 0xFF26304C));
        b.setFocusable(true);
        return b;
    }

    private void markTabs() {
        tabInstalled.setTextColor(latestTab ? DIM : ACCENT);
        tabLatest.setTextColor(latestTab ? ACCENT : DIM);
    }

    // ---------------------------------------------------------------- loading

    private void showTab(boolean latest) {
        latestTab = latest;
        markTabs();
        final int token = ++loadToken;
        list.removeAllViews();
        clearDetail();
        status.setTextColor(DIM);
        status.setText(latest ? "Checking GitHub for the latest themes…" : "Loading the themes for your launcher…");
        io.execute(() -> {
            try {
                String ref;
                String note;
                if (latest) {
                    String[] l = repo.latest();
                    latestSha = l[0];
                    latestDate = l[1];
                    ref = latestSha;
                    if (installedNames.isEmpty()) {
                        for (ThemeInfo t : repo.load(BuildConfig.INSTALLED_REF)) installedNames.add(t.name);
                    }
                    note = latestSha.equals(BuildConfig.INSTALLED_REF)
                            ? "GitHub has nothing newer than your launcher: same themes as your version."
                            : "Newest themes on GitHub" + (latestDate.isEmpty() ? "" : " (" + latestDate + ")")
                            + ". NEW ones aren't in your launcher's version and may not fully work with it.";
                } else {
                    ref = BuildConfig.INSTALLED_REF;
                    note = "Themes made for your launcher: " + BuildConfig.INSTALLED_LABEL + ".";
                }
                repo.pruneExcept(BuildConfig.INSTALLED_REF, latestSha);
                List<ThemeInfo> themes = repo.load(ref);
                if (!latest) for (ThemeInfo t : themes) installedNames.add(t.name);
                String warn = !ThemeApplier.launcherInstalled(this)
                        ? "  The cleanyourtv launcher isn't installed, so themes can't be applied."
                        : !ThemeApplier.permitted(this)
                        ? "  Applying needs a one-time permission (see the message when you press OK)." : "";
                ui.post(() -> {
                    if (token != loadToken) return;
                    status.setTextColor(warn.isEmpty() ? DIM : WARN);
                    status.setText(note + warn);
                    render(themes);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (token != loadToken) return;
                    status.setTextColor(WARN);
                    status.setText("Couldn't load the themes: " + e.getMessage()
                            + "\nCheck the TV's internet connection, then press OK on a tab to try again.");
                    (latest ? tabLatest : tabInstalled).requestFocus();
                });
            }
        });
    }

    private void refreshCurrent() {
        io.execute(() -> {
            String c = ThemeInfo.canonical(ThemeApplier.currentConfig(this));
            ui.post(() -> {
                boolean changed = c == null ? currentCanonical != null : !c.equals(currentCanonical);
                currentCanonical = c;
                if (changed && !shown.isEmpty()) render(shown);
            });
        });
    }

    private ThemeInfo.Cost cost(ThemeInfo t) {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return t.cost(Math.max(m.widthPixels, m.heightPixels), Math.min(m.widthPixels, m.heightPixels));
    }

    private boolean isCurrent(ThemeInfo t) {
        return currentCanonical != null && currentCanonical.equals(t.canonical);
    }

    private boolean isNew(ThemeInfo t) {
        return latestTab && !installedNames.isEmpty() && !installedNames.contains(t.name);
    }

    // ---------------------------------------------------------------- rendering

    private void render(List<ThemeInfo> themes) {
        shown = themes;
        String keepFocus = focused != null ? focused.name : null;
        list.removeAllViews();
        View first = null;
        View current = null;
        View again = null;
        for (ThemeInfo.Category cat : ThemeInfo.Category.values()) {
            List<ThemeInfo> in = new ArrayList<>();
            for (ThemeInfo t : themes) if (t.category == cat) in.add(t);
            if (in.isEmpty()) continue;
            in.sort(Comparator.comparing(t -> t.name));
            TextView header = text(17, ACCENT, true);
            header.setText(cat.title + "  (" + in.size() + ")");
            header.setPadding(0, dp(14), 0, dp(8));
            list.addView(header);
            LinearLayout row = null;
            for (int i = 0; i < in.size(); i++) {
                if (i % COLUMNS == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    list.addView(row);
                }
                ThemeInfo t = in.get(i);
                View card = card(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(card, lp);
                if (first == null) first = card;
                if (current == null && isCurrent(t)) current = card;
                if (t.name.equals(keepFocus)) again = card;
            }
            if (row != null) {
                for (int pad = in.size() % COLUMNS; pad != 0 && pad < COLUMNS; pad++) {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
        }
        if (themes.isEmpty()) {
            TextView none = text(15, DIM, false);
            none.setText("No themes found in this version.");
            list.addView(none);
        }
        View target = again != null ? again : current != null ? current : first;
        if (target != null) target.requestFocus();
    }

    private View card(ThemeInfo t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setFocusable(true);
        card.setBackground(focusable(CARD, 0xFF26304C));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(rounded(0xFF0F1322, 0, 6));
        img.setClipToOutline(true);
        card.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));

        TextView name = text(13, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(6), dp(2), 0);
        name.setText(t.name);
        card.addView(name);

        String tag = isCurrent(t) ? "● In use" : isNew(t) ? "NEW" : t.seasonOver ? "Season over" : "";
        LinearLayout tags = new LinearLayout(this);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        TextView badge = text(11, isCurrent(t) ? ACCENT : isNew(t) ? WARN : DIM, true);
        badge.setPadding(dp(2), 0, dp(2), 0);
        badge.setText(tag.isEmpty() ? " " : tag);
        tags.addView(badge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ThemeInfo.Cost cost = cost(t);
        TextView load = text(11, LOAD_COLORS[cost.ratingLevel], true);
        load.setPadding(dp(2), 0, dp(2), 0);
        load.setText(cost.rating);
        tags.addView(load);
        card.addView(tags);

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
            if (has) showDetail(t);
        });
        card.setOnClickListener(v -> confirmApply(t));
        loadImage(img, t, 360);
        return card;
    }

    private void clearDetail() {
        focused = null;
        detailImage.setImageDrawable(null);
        detailName.setText("");
        detailBadge.setText("");
        detailMeta.setText("");
        detailCost.setText("");
        detailDesc.setText("");
    }

    private void showDetail(ThemeInfo t) {
        focused = t;
        detailName.setText(t.name);
        String badge = t.category.title;
        if (isCurrent(t)) badge = "In use now  ·  " + badge;
        else if (isNew(t)) badge = "NEW, not in your launcher's version  ·  " + badge;
        detailBadge.setTextColor(isNew(t) ? WARN : ACCENT);
        detailBadge.setText(badge);
        detailMeta.setText(t.summary());
        detailCost.setText(ThemeInfo.describe(cost(t)));
        detailDesc.setText(t.description.isEmpty() ? "No description for this theme." : t.description);
        loadImage(detailImage, t, 720);
    }

    // ---------------------------------------------------------------- images

    private void loadImage(ImageView view, ThemeInfo t, int targetWidth) {
        String key = t.ref + "/" + t.name + "@" + targetWidth;
        view.setTag(key);
        Bitmap hit = images.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        if (!t.hasScreenshot) return;
        io.execute(() -> {
            try {
                File f = new File(repo.refDir(t.ref), "shots/" + t.name + ".jpg");
                byte[] b = Net.cached(f, ThemeRepo.raw(t.ref, t.screenshotPath()), null);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(b, 0, b.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                d.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, d);
                if (bmp == null) return;
                images.put(key, bmp);
                ui.post(() -> {
                    if (key.equals(view.getTag())) view.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // the card keeps its empty frame
            }
        });
    }

    // ---------------------------------------------------------------- applying

    private void confirmApply(ThemeInfo t) {
        if (busy) return;
        if (!ThemeApplier.launcherInstalled(this)) {
            message("Launcher not installed", "The cleanyourtv launcher (com.gathin.tvhome) isn't on this TV, "
                    + "so there is nothing to apply a theme to.");
            return;
        }
        if (!ThemeApplier.permitted(this)) {
            message("One-time permission needed", "The launcher only accepts themes from apps holding the "
                    + "WRITE_SECURE_SETTINGS permission. With USB debugging on, run this once on the laptop:\n\n"
                    + "pm grant uk.home.themegallery android.permission.WRITE_SECURE_SETTINGS");
            return;
        }
        if (isCurrent(t)) {
            Toast.makeText(this, t.name + " is already in use", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Your current theme will be replaced and its files deleted (it isn't saved).\n\n")
                .append("Choices you made in Launcher settings (tiles per row, wallpaper pick, dock opacity, clock ")
                .append("format) go back to this theme's defaults.\n\n")
                .append("Download: ").append(ThemeInfo.size(t.downloadBytes)).append('.');
        if (isNew(t)) {
            msg.append("\n\nThis theme is newer than your launcher, so some of its settings may be ignored.");
        }
        if (t.seasonOver) msg.append("\n\nIts festival season is over, so its festive extras won't show.");
        new AlertDialog.Builder(this)
                .setTitle("Apply “" + t.name + "”?")
                .setMessage(msg.toString())
                .setPositiveButton("Apply", (d, w) -> apply(t))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void apply(ThemeInfo t) {
        busy = true;
        progressText = text(16, Color.WHITE, false);
        progressText.setPadding(dp(24), dp(20), dp(24), dp(20));
        progressText.setGravity(Gravity.CENTER_VERTICAL);
        progressText.setText("Preparing…");
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Applying " + t.name)
                .setView(progressText)
                .setCancelable(false)
                .show();
        io.execute(() -> {
            try {
                ThemeApplier.apply(this, t, m -> ui.post(() -> progressText.setText(m)));
                String now = ThemeInfo.canonical(ThemeApplier.currentConfig(this));
                ui.post(() -> {
                    busy = false;
                    progressDialog.dismiss();
                    currentCanonical = now;
                    render(shown);
                    Toast.makeText(this, t.name + " applied", Toast.LENGTH_SHORT).show();
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(home);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    progressDialog.dismiss();
                    message("Couldn't apply " + t.name, String.valueOf(e.getMessage())
                            + "\n\nIf the failure came after downloading, the launcher may be showing part of the "
                            + "new theme: apply it (or another theme) again.");
                    refreshCurrent();
                });
            }
        });
    }

    private void message(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }
}
