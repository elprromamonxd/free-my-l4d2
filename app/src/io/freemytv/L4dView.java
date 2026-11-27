package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen in the look of Left 4 Dead 2's main menu: a polaroid with the app in focus, the other apps in a
 * strip that turns like a wheel, the clock, and a menu on the left. It answers HomeActivity like HomeView does, and
 * is chosen with Prefs.l4d2(): the original home screen stays as it was.
 *
 * Everything is measured on a 1920x1080 screen and multiplied by k = width / 1920 (HomeView's way). Nothing is
 * allocated while drawing. The fonts and frames come from the theme folder (Theme) and are drawn here when missing.
 */
final class L4dView extends View {
    // MARK: - Layout (1920x1080)

    private static final float POLA_X = 215, POLA_Y = 100, POLA_W = 350;
    private static final float STRIP_X = 681, STRIP_Y = 240, TILE_W = 192, STEP = 207;
    private static final int STRIP_N = 5;
    private static final float MENU_STEP = 50;
    /** The polaroid's turn: one angle for every app, to the left (negative = counterclockwise), as in the game. */
    private static final float POLA_ANGLE = -3f;
    /**
     * The turn of the wheel, measured on the game's own menu: it lasts 200 ms and everything moves at a steady pace (no easing, no
     * bounce). The app that goes between the strip and the polaroid flies in the black frame for the first ANIM_FLIGHT of it and turns
     * from 0 to the polaroid's angle on the way; the title changes when it ends.
     */
    private static final float ANIM_MS = 200, ANIM_FLIGHT = 0.93f;
    /** The clock is turned like the game's logo it stands in for (to the left, negative). */
    private static final float CLOCK_ANGLE = -3f;
    /** How far each place of the strip sits above (-) or below (+) the line, from the place before the first to the one after the last: the game's tiles are not lined up. */
    private static final float[] JITTER = {0, 0, 0, 0, 0, 0, 0};   // all in one line, like the game's
    private static final float TITLE_SIZE = 40, DESC_SIZE = 28, MENU_SIZE = 35, LABEL_SIZE = 22;
    private static final float CLOCK_RIGHT = 1800, CLOCK_BASE = 200, CLOCK_SIZE = 150, AMPM_SIZE = 60;
    /** The clock is the menu's own gray at 70 % (the colon keeps its red, also at 70 %). */
    private static final int CLOCK_ALPHA = 179;
    // The glow of what is selected breathes: it grows and shrinks (radius in 1920x1080 pixels, one breath every PULSE_PERIOD seconds).
    private static final boolean PULSE = true;
    private static final float GLOW_MIN = 10, GLOW_MAX = 24, PULSE_PERIOD = 1.6f;
    private static final int GRID_COLS = 6, GRID_ROWS = 3;
    private static final float GRID_Y = 170, GRID_GAP = 45;
    private static final int ICON = 320;
    private static final int MENU_N = 6;

    private static final int SELECTED = Color.rgb(250, 250, 250), IDLE = Color.rgb(127, 127, 135), RED = Color.rgb(214, 49, 38);
    private static final int HOME = 0, EXTRAS = 1, GRID = 2, BLOG = 3;

    // The blog page (1920x1080): the post's date and title in red, the text on the left, the picture and its link on the right.
    private static final int BLOG_RED = Color.rgb(255, 20, 20);
    private static final float BLOG_X = 58, BLOG_DATE_Y = 95, BLOG_TITLE_Y = 152, BLOG_HEAD_SIZE = 48;
    private static final float BLOG_BODY_X = 84, BLOG_BODY_Y = 215, BLOG_BODY_W = 860, BLOG_LINE = 55, BLOG_SIZE = 44;
    private static final int BLOG_LINES = 10;
    private static final float BLOG_IMG_X = 1230, BLOG_IMG_Y = 190, BLOG_IMG_W = 512, BLOG_IMG_H = 256;
    private static final float BLOG_BACK_X = 95, BLOG_BACK_Y = 765;

    /** An app of the wheel. */
    private static final class Entry {
        final String pkg, label, upper;
        String fit;
        float fitWidth;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
            this.upper = label.toUpperCase(Ui.locale());
        }

        /** The name cut to fit `maxWidth` with the paint as it is now. */
        String fit(Paint p, float maxWidth) {
            if (fit == null || Math.abs(fitWidth - maxWidth) > 0.5f) {
                fitWidth = maxWidth;
                if (p.measureText(upper) <= maxWidth) fit = upper;
                else {
                    int n = p.breakText(upper, true, Math.max(1, maxWidth - p.measureText("…")), null);
                    fit = upper.substring(0, Math.max(1, n)) + "…";
                }
            }
            return fit;
        }
    }

    private final Activity act;
    private final PackageManager pm;
    private float k = 1;

    private final ArrayList<Entry> apps = new ArrayList<>();
    private final String[] menu = new String[MENU_N];
    private int sel, focus, mode, sub, gridSel;
    private boolean names;
    private String title = "";

    private final Theme.Frame rawWhite, rawName, rawPlain;   // as they were read
    private Theme.Frame white, tileName, tilePlain;          // at the size they are drawn
    private final Bitmap arrow, arrowL, arrowR;
    private boolean animating;
    private int animDir, animFrom;                            // which way the wheel turns, and which app was the polaroid
    private long animStart;
    private float animP;                                      // how far the turn is, 0 to 1
    private Theme.Frame polaPlain;                            // the polaroid's black frame, at its size
    private final Blog blog;
    private final Bitmap blogImage, biohazard;
    private final ArrayList<String> blogLines = new ArrayList<>();
    private int blogFocus, blogTop;                          // what is selected (0 text, 1 link, 2 back); the first line shown
    private final RectF arrowDst = new RectF();
    private final Rect src = new Rect();

    // The springs: the glow of the focused text, the grid's scroll (in rows).
    private final Spring glow = new Spring(0.30f, 1f, 1), polaOn = new Spring(0.25f, 1f, 1), blogScroll = new Spring(0.2f, 1f, 0), scroll = new Spring(0.26f, 1f, 0);
    private long last;

    private final Paint pBg = new Paint(), pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            pFill = new Paint(), pStroke = new Paint(Paint.ANTI_ALIAS_FLAG), pArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle, pMenu, pSub, pValue, pLabel, pClock, pColon, pAmPm, pBody, pHead, pBack, pTint;
    private final Path arrowLeft = new Path(), arrowRight = new Path();
    private final RectF dst = new RectF(), frameDst = new RectF();

    // The clock: hour, minutes, "PM", and their widths (measured when the text or the size changes).
    private String hh = "", mm = "", ap = "";
    private float hhW, colonW, mmW, apW;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            invalidate();
            postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    // The icons: square, rendered off the main thread, a few at a time.
    private final LruCache<String, Bitmap> icons = new LruCache<>(28);
    private final HashSet<String> loading = new HashSet<>(), failed = new HashSet<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "l4d-icons");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    L4dView(Activity a) {
        super(a);
        act = a;
        pm = a.getPackageManager();
        Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        Typeface tTitle = Theme.font(a, "stubble", bold, true);
        Typeface tClock = Theme.font(a, "future", bold, false);
        Typeface tBody = Theme.font(a, "arial", Typeface.SANS_SERIF, false);
        Typeface tBodyBold = Theme.font(a, "arial", Typeface.SANS_SERIF, true);
        pTitle = Ui.text(tTitle, TITLE_SIZE, SELECTED);
        pMenu = Ui.text(tTitle, MENU_SIZE, IDLE);
        pSub = Ui.text(tTitle, 32, IDLE);
        pValue = Ui.text(tTitle, 32, IDLE);
        pValue.setTextAlign(Paint.Align.RIGHT);
        pLabel = Ui.text(tTitle, LABEL_SIZE, SELECTED);
        pClock = Ui.text(tClock, CLOCK_SIZE, IDLE);
        pColon = Ui.text(tClock, CLOCK_SIZE, IDLE);
        pAmPm = Ui.text(tClock, AMPM_SIZE, IDLE);
        pClock.setAlpha(CLOCK_ALPHA);
        pColon.setAlpha(CLOCK_ALPHA);
        pAmPm.setAlpha(CLOCK_ALPHA);
        pDescription = Ui.text(tBody, DESC_SIZE, SELECTED);
        pBody = Ui.text(tBody, BLOG_SIZE, SELECTED);
        pHead = Ui.text(tBodyBold, BLOG_HEAD_SIZE, BLOG_RED);
        pBack = Ui.text(tTitle, BLOG_SIZE, IDLE);
        pTint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        pTint.setColorFilter(new PorterDuffColorFilter(BLOG_RED, PorterDuff.Mode.SRC_IN));
        pArrow.setColor(Color.WHITE);
        pStroke.setStyle(Paint.Style.STROKE);
        names = Prefs.l4dNames();
        rawWhite = Theme.frame(a, Theme.WHITE, "marco_blanco.png", "marcoblanco.png");
        Theme.Frame one = Theme.frame(a, Theme.BLACK, "marco_negro.png", "marconegro.png");
        Theme.Frame two = Theme.frame(a, Theme.PLAIN, "marco_negro_2.png", "marconegro2.png");
        // Told apart by what each has, not by its file name: the one with a band under its window carries the app's name.
        if (one.band.isEmpty() && !two.band.isEmpty()) {
            rawName = two;
            rawPlain = one;
        } else {
            rawName = one;
            rawPlain = two;
        }
        white = rawWhite;
        tileName = rawName;
        tilePlain = rawPlain;
        arrow = Theme.arrow(a);
        arrowL = Theme.image(a, "arrow_left.png");
        arrowR = Theme.image(a, "arrow_right.png");
        blog = Blog.load(a);
        blogImage = Theme.image(a, "blog.png");
        biohazard = Theme.image(a, "biohazard.png");
        menu[0] = Ui.tr("Applications");
        menu[1] = Ui.tr("Settings");
        menu[2] = Ui.tr("App Store");
        menu[3] = Ui.tr("Extras");
        menu[4] = Ui.tr("Sources");
        menu[5] = Ui.tr("Power off");
        for (int i = 0; i < MENU_N; i++) menu[i] = menu[i].toUpperCase(Ui.locale());
        reload();
        updateClock();
    }

    /** The description under the title (what each app's own text will say; empty until it can be edited). */
    private final Paint pDescription;

    // MARK: - The HomeActivity API (the same calls HomeView answers)

    void resume() {
        reload();
        removeCallbacks(tick);
        post(tick);
        invalidate();
    }

    void pause() {
        removeCallbacks(tick);
    }

    void destroy() {
        removeCallbacks(tick);
        loader.shutdownNow();
        icons.evictAll();
    }

    /** Home pressed again: back to the first app, menus closed. */
    void reset(boolean fromHome) {
        mode = HOME;
        sub = 0;
        setFocus(0);
    }

    void showAllApps() {
        mode = GRID;
        gridSel = Math.min(sel, Math.max(0, apps.size() - 1));
        scroll.snap(Math.max(0, gridSel / GRID_COLS - (GRID_ROWS - 1)));
        invalidate();
    }

    void showInputs() {
        if (!HomeApps.openSources(act)) toast(Ui.trf("Couldn't open {app}.", "app", Ui.tr("Sources")));
    }

    void showRecents() {
        // The recent apps of this look come later.
    }

    // MARK: - Data

    /** The apps with a launcher entry: the shelf's first (the order chosen in Free My TV), then the rest by name. */
    private void reload() {
        String keep = apps.isEmpty() ? null : apps.get(sel).pkg;
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        for (String[] a : HomeApps.launchable(act)) {
            if (a[0].equals(act.getPackageName()) || HomeApps.isHidden(a[0])) continue;
            all.put(a[0], a[1]);
        }
        ArrayList<Entry> list = new ArrayList<>();
        for (String pkg : HomeApps.shelfPackages(act)) {
            String label = all.remove(pkg);
            if (label != null) list.add(new Entry(pkg, label));
        }
        ArrayList<Entry> rest = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : all.entrySet()) rest.add(new Entry(e.getKey(), e.getValue()));
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        list.addAll(rest);
        apps.clear();
        apps.addAll(list);
        sel = 0;
        if (keep != null) for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(keep)) sel = i;
        gridSel = Math.min(gridSel, Math.max(0, apps.size() - 1));
        layoutTitle();
    }

    private void layoutTitle() {
        if (apps.isEmpty()) {
            title = "";
            return;
        }
        String t = (Ui.tr("Open") + " " + apps.get(sel).label).toUpperCase(Ui.locale());
        float max = 1150 * k;
        if (pTitle.measureText(t) > max) {
            int n = pTitle.breakText(t, true, Math.max(1, max - pTitle.measureText("…")), null);
            t = t.substring(0, Math.max(1, n)) + "…";
        }
        title = t;
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY), m = now.get(Calendar.MINUTE);
        boolean h24 = Ui.h24();
        int shown = h24 ? h : (h % 12 == 0 ? 12 : h % 12);
        hh = h24 ? String.format(Locale.ROOT, "%02d", shown) : String.valueOf(shown);
        mm = String.format(Locale.ROOT, "%02d", m);
        ap = h24 ? "" : new DateFormatSymbols(Ui.locale()).getAmPmStrings()[h < 12 ? 0 : 1].toUpperCase(Ui.locale());
        hhW = pClock.measureText(hh);
        colonW = pColon.measureText(":");
        mmW = pClock.measureText(mm);
        apW = ap.isEmpty() ? 0 : pAmPm.measureText(ap);
    }

    // MARK: - Icons

    private Bitmap icon(String pkg) {
        Bitmap b = icons.get(pkg);
        if (b != null) return b;
        if (failed.contains(pkg) || !loading.add(pkg)) return null;
        loader.execute(() -> {
            Bitmap made = render(pkg);
            post(() -> {
                loading.remove(pkg);
                if (made != null) icons.put(pkg, made);
                else failed.add(pkg);
                invalidate();
            });
        });
        return null;
    }

    /** The app's icon as a full square: an adaptive icon's two layers with no mask, an old one over its own average color. */
    private Bitmap render(String pkg) {
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            int s = ICON;
            Bitmap raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(raw);
            if (d instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) d;
                int o = s / 4;   // the layers are 108 dp and the mask shows the middle 72: a quarter more each side
                Drawable back = ad.getBackground(), front = ad.getForeground();
                if (back != null) {
                    back.setBounds(-o, -o, s + o, s + o);
                    back.draw(rc);
                }
                if (front != null) {
                    front.setBounds(-o, -o, s + o, s + o);
                    front.draw(rc);
                }
            } else {
                d.setBounds(0, 0, s, s);
                d.draw(rc);
            }
            Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(out);
            oc.drawColor(average(raw));
            oc.drawBitmap(raw, 0, 0, null);
            raw.recycle();
            return out;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            AppLog.w("l4d icon " + pkg + ": " + e.getMessage());
            return null;
        }
    }

    /** The average color of the opaque pixels (a sample of them): fills what an icon leaves empty. */
    private static int average(Bitmap b) {
        long r = 0, g = 0, bl = 0, n = 0;
        int step = Math.max(1, b.getWidth() / 24);
        for (int y = 0; y < b.getHeight(); y += step) {
            for (int x = 0; x < b.getWidth(); x += step) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        return n == 0 ? Color.rgb(40, 40, 44) : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    // MARK: - Size

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        k = w / 1920f;
        white = rawWhite.scaledTo(Math.round(POLA_W * k));
        tileName = rawName.scaledTo(Math.round(TILE_W * k));
        tilePlain = rawPlain.scaledTo(Math.round(TILE_W * k));
        polaPlain = rawPlain.scaledTo(Math.round(POLA_W * k));
        pBg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(36, 32, 30), Color.rgb(7, 7, 7), Shader.TileMode.CLAMP));
        pTitle.setTextSize(TITLE_SIZE * k);
        pMenu.setTextSize(MENU_SIZE * k);
        pSub.setTextSize(32 * k);
        pValue.setTextSize(32 * k);
        pLabel.setTextSize(LABEL_SIZE * k);
        pClock.setTextSize(CLOCK_SIZE * k);
        pColon.setTextSize(CLOCK_SIZE * k);
        pAmPm.setTextSize(AMPM_SIZE * k);
        pDescription.setTextSize(DESC_SIZE * k);
        pBody.setTextSize(BLOG_SIZE * k);
        pHead.setTextSize(BLOG_HEAD_SIZE * k);
        pBack.setTextSize(BLOG_SIZE * k);
        pStroke.setStrokeWidth(3 * k);
        float aw = 22 * k, ah = 32 * k;
        arrowLeft.reset();
        arrowLeft.moveTo(0, ah / 2);
        arrowLeft.lineTo(aw, 0);
        arrowLeft.lineTo(aw, ah);
        arrowLeft.close();
        arrowRight.reset();
        arrowRight.moveTo(aw, ah / 2);
        arrowRight.lineTo(0, 0);
        arrowRight.lineTo(0, ah);
        arrowRight.close();
        for (Entry e : apps) e.fit = null;
        updateClock();
        layoutTitle();
        layoutBlog();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min((now - last) / 1e9f, 0.05f);
        last = now;
        polaOn.target = focus == 0 && mode == HOME ? 1 : 0;
        if (animating) {
            animP = (now - animStart) / (ANIM_MS * 1e6f);
            if (animP >= 1f) finishTurn();
        }
        boolean moving = animating | glow.step(dt) | scroll.step(dt) | polaOn.step(dt) | blogScroll.step(dt);
        c.drawRect(0, 0, getWidth(), getHeight(), pBg);
        if (mode == GRID) drawGrid(c);
        else if (mode == BLOG) drawBlog(c);
        else {
            drawClock(c);
            if (!apps.isEmpty()) {
                if (animating) drawTurn(c);
                else {
                    drawStrip(c);
                    drawPolaroid(c);
                }
                drawTitle(c);
            }
            drawMenu(c);
            if (mode == EXTRAS) drawExtras(c);
        }
        if (moving) postInvalidateOnAnimation();
        else {
            last = 0;
            if (PULSE) postInvalidateDelayed(40);   // the glow keeps breathing: about 25 frames a second
        }
    }

    /** White glow behind the text (the shadow layer, drawn with it): it fades in when the text takes the focus. */
    private void glowOn(Paint p) {
        float g = Math.max(0f, Math.min(1f, glow.value));
        float breath = PULSE ? 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1e9 * 2 * Math.PI / PULSE_PERIOD) : 0.5f;
        p.setShadowLayer((GLOW_MIN + (GLOW_MAX - GLOW_MIN) * breath) * k, 0, 0, Color.argb((int) (220 * g), 255, 255, 255));
    }

    private void drawClock(Canvas c) {
        float total = hhW + colonW + mmW + (ap.isEmpty() ? 0 : apW + 12 * k);
        float x = CLOCK_RIGHT * k - total;
        float y = CLOCK_BASE * k;
        c.save();
        c.rotate(CLOCK_ANGLE, x + total / 2, y - Ui.cap(pClock) / 2);
        c.drawText(hh, x, y, pClock);
        c.drawText(":", x + hhW, y, pColon);
        c.drawText(mm, x + hhW + colonW, y, pClock);
        if (!ap.isEmpty()) c.drawText(ap, x + hhW + colonW + mmW + 12 * k, y - Ui.cap(pClock) + Ui.cap(pAmPm), pAmPm);
        c.restore();
    }

    /** The other apps in their line, as they stand when the wheel is still. */
    private void drawStrip(Canvas c) {
        int n = apps.size();
        int s = Math.min(STRIP_N, n - 1);
        for (int j = 0; j < s; j++) {
            Entry e = apps.get(Math.floorMod(sel + 1 + j, n));
            drawTile(c, e, (STRIP_X + j * STEP) * k, (STRIP_Y + jitter(j)) * k, TILE_W * k, 1f, names);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** An app at a pose: its middle and width (in 1920x1080 pixels) and its turn, in the frame `skin`, with `alpha`. */
    private void drawPose(Canvas c, Entry e, float cx, float cy, float w, float angle, Theme.Frame skin, float alpha) {
        float wp = w * k, h = wp * skin.h / skin.w;
        c.save();
        c.translate(cx * k, cy * k);
        c.rotate(angle);
        drawIconIn(c, e.pkg, -wp / 2, -h / 2, wp, h, skin, skin, (int) (255 * Math.max(0f, Math.min(1f, alpha))));
        c.restore();
    }

    /**
     * The wheel turning, as in the game's menu. Right: the strip's first app flies to the polaroid (growing, turning, in the black
     * frame, which becomes the white one on arrival) while the old polaroid slips away to the left, fading, and the strip moves one
     * place left with a new app coming in at its end. Left: the same backwards: the polaroid shrinks into the strip's first place
     * and a new one comes in from the left.
     */
    private void drawTurn(Canvas c) {
        int n = apps.size();
        int s = Math.min(STRIP_N, n - 1);
        boolean right = animDir > 0;
        float p = animP, t = Math.min(1f, p / ANIM_FLIGHT);
        for (int j = right ? 1 : 0; j < s + (right ? 1 : 0); j++) {
            float pos = right ? j - p : j + p;
            float alpha = 1;
            if (right && j == s) alpha = p;                 // the one that comes in at the end
            else if (!right && j == s - 1) alpha = 1 - p;   // the last one goes out
            drawTile(c, apps.get(Math.floorMod(animFrom + 1 + j, n)), (STRIP_X + pos * STEP) * k, STRIP_Y * k, TILE_W * k, alpha, names);
        }
        float pcx = POLA_X + POLA_W / 2f, pcy = POLA_Y + polaHeight() / 2f;
        float tileH = TILE_W * polaPlain.h / polaPlain.w;
        float scx = STRIP_X + TILE_W / 2f, scy = STRIP_Y + tileH / 2f;
        if (right) {
            drawPose(c, apps.get(animFrom), pcx - POLA_W * 1.1f * p, pcy, POLA_W * (1 - 0.15f * p), POLA_ANGLE, white, 1 - p);
            drawPose(c, apps.get(sel), lerp(scx, pcx, t), lerp(scy, pcy, t), lerp(TILE_W, POLA_W, t), POLA_ANGLE * t,
                    p < ANIM_FLIGHT ? polaPlain : white, 1);
        } else {
            drawPose(c, apps.get(sel), pcx - POLA_W * 1.1f * (1 - p), pcy, POLA_W * (0.85f + 0.15f * p), POLA_ANGLE, white, p);
            drawPose(c, apps.get(animFrom), lerp(pcx, scx, t), lerp(pcy, scy, t), lerp(POLA_W, TILE_W, t), POLA_ANGLE * (1 - t), polaPlain, 1);
        }
        drawArrows(c);
    }

    /** The line's offset at a (fractional) place of the strip: the places keep their own, the tiles move through them. */
    private static float jitter(float pos) {
        float p = Math.max(0f, Math.min(JITTER.length - 1.001f, pos + 1));
        int i = (int) p;
        float t = p - i;
        return JITTER[i] * (1 - t) + JITTER[i + 1] * t;
    }

    /**
     * An app in its black frame: the icon fills the whole square and the frame goes over it. With names on, the frame
     * is the one with the band, which covers the bottom of the icon, and the name is written on that band.
     */
    private void drawTile(Canvas c, Entry e, float x, float y, float w, float alpha, boolean label) {
        Theme.Frame skin = label ? tileName : tilePlain;
        float h = w * skin.h / skin.w;
        int a = (int) (255 * alpha);
        drawIconIn(c, e.pkg, x, y, w, h, tilePlain, skin, a);
        if (label && !skin.band.isEmpty()) {
            float sc = w / skin.w;
            float bandTop = y + skin.band.top * sc, bandH = h - skin.band.top * sc;
            pLabel.setTextSize(Math.min(LABEL_SIZE * k, bandH * 0.62f));
            pLabel.setAlpha(a);
            float base = bandTop + bandH / 2 + Ui.halfCap(pLabel);
            float pad = w * 0.07f;
            c.drawText(e.fit(pLabel, w - 2 * pad), x + pad, base, pLabel);
        }
    }

    /**
     * The icon in `place`'s window and `skin` over it, both drawn in the box (x, y, w, h). The icon is a square cut to the
     * window's shape from its middle, so it is never stretched.
     */
    private void drawIconIn(Canvas c, String pkg, float x, float y, float w, float h, Theme.Frame place, Theme.Frame skin, int a) {
        dst.set(x + place.window.left * w / place.w, y + place.window.top * h / place.h,
                x + place.window.right * w / place.w, y + place.window.bottom * h / place.h);
        pBmp.setAlpha(a);
        Bitmap ic = icon(pkg);
        if (ic != null) {
            int side = ic.getWidth(), cut;
            float ratio = dst.width() / dst.height();
            if (ratio >= 1) {   // wider than tall: keep the full width, the middle of the height
                cut = Math.round(side / ratio);
                src.set(0, (side - cut) / 2, side, (side - cut) / 2 + cut);
            } else {
                cut = Math.round(side * ratio);
                src.set((side - cut) / 2, 0, (side - cut) / 2 + cut, side);
            }
            c.drawBitmap(ic, src, dst, pBmp);
        } else {
            pFill.setColor(Color.rgb(40, 40, 44));
            pFill.setAlpha(a);
            c.drawRect(dst, pFill);
        }
        frameDst.set(x, y, x + w, y + h);
        c.drawBitmap(skin.bitmap, null, frameDst, pBmp);
    }

    private float polaHeight() {
        return POLA_W * white.h / white.w;
    }

    /** Where the title sits: under the polaroid, whatever its height; the menu goes under the title. */
    private float titleY() {
        return POLA_Y + polaHeight() + 85;
    }

    private float menuY() {
        return titleY() + 115;
    }

    private void drawPolaroid(Canvas c) {
        Entry e = apps.get(sel);
        float w = POLA_W * k;
        float h = w * white.h / white.w;
        float cx = (POLA_X + POLA_W / 2f) * k, cy = (POLA_Y + polaHeight() / 2f) * k;
        c.save();
        c.translate(cx, cy);
        c.rotate(POLA_ANGLE);
        // The black frame while the focus is on the menu below; the white one fades in over it when the apps have it.
        drawIconIn(c, e.pkg, -w / 2, -h / 2, w, h, white, polaPlain, 255);
        float on = Math.max(0f, Math.min(1f, polaOn.value));
        if (on > 0.01f) {
            frameDst.set(-w / 2, -h / 2, w / 2, h / 2);
            pBmp.setAlpha((int) (255 * on));
            c.drawBitmap(white.bitmap, null, frameDst, pBmp);
        }
        c.restore();
        drawArrows(c);
    }

    private void drawArrows(Canvas c) {
        pArrow.setAlpha(focus == 0 && mode == HOME ? 235 : 110);
        float cy = (POLA_Y + polaHeight() - 48) * k;   // their middle line
        drawArrow(c, (POLA_X - 52) * k, cy, false);
        drawArrow(c, (POLA_X + POLA_W + 20) * k, cy, true);
    }

    /** An arrow: the theme folder's own left or right picture (arrow_left.png, arrow_right.png), else the old single one mirrored, else a triangle. */
    private void drawArrow(Canvas c, float x, float cy, boolean right) {
        Bitmap own = right ? arrowR : arrowL;
        Bitmap bmp = own != null ? own : arrow;
        if (bmp == null) {
            c.save();
            c.translate(x, cy - 16 * k);
            c.drawPath(right ? arrowRight : arrowLeft, pArrow);
            c.restore();
            return;
        }
        float aw = bmp.getWidth() * k, ah = bmp.getHeight() * k;
        c.save();
        if (right && own == null) {   // the single old arrow points left: mirror it
            c.translate(x + aw, cy - ah / 2);
            c.scale(-1, 1);
        } else {
            c.translate(x, cy - ah / 2);
        }
        arrowDst.set(0, 0, aw, ah);
        c.drawBitmap(bmp, null, arrowDst, pArrow);
        c.restore();
    }

    private void drawTitle(Canvas c) {
        boolean on = focus == 0 && mode == HOME;
        pTitle.setColor(on ? SELECTED : IDLE);
        if (on) glowOn(pTitle);
        c.drawText(title, POLA_X * k, titleY() * k, pTitle);
        pTitle.clearShadowLayer();
    }

    private void drawMenu(Canvas c) {
        float x = POLA_X * k;
        for (int i = 0; i < MENU_N; i++) {
            boolean on = mode == HOME ? focus == i + 1 : mode == EXTRAS && i == 3;
            pMenu.setColor(on ? SELECTED : IDLE);
            if (on) glowOn(pMenu);
            c.drawText(menu[i], x, (menuY() + i * MENU_STEP) * k, pMenu);
            pMenu.clearShadowLayer();
        }
    }

    /** The little menu of Extras, beside it, like the game's: black with a red outline. */
    private void drawExtras(Canvas c) {
        float left = (POLA_X + 330) * k, top = (menuY() + 3 * MENU_STEP - 44) * k;
        float w = 700 * k, rowH = 56 * k, h = 4 * rowH + 28 * k;
        pFill.setColor(Color.argb(238, 0, 0, 0));
        c.drawRect(left, top, left + w, top + h, pFill);
        pStroke.setColor(RED);
        c.drawRect(left, top, left + w, top + h, pStroke);
        String[] rows = {Ui.tr("Blog Post"), Ui.tr("Names in the carousel"), Ui.tr("TV Settings"), Ui.tr("Original home screen")};
        for (int i = 0; i < rows.length; i++) rows[i] = rows[i].toUpperCase(Ui.locale());
        for (int i = 0; i < rows.length; i++) {
            boolean on = sub == i;
            float mid = top + 14 * k + rowH * i + rowH / 2;
            float base = mid + Ui.halfCap(pSub);
            pSub.setColor(on ? SELECTED : IDLE);
            pValue.setColor(on ? SELECTED : IDLE);
            if (on) {
                glowOn(pSub);
                glowOn(pValue);
            }
            c.drawText(rows[i], left + 26 * k, base, pSub);
            if (i == 1) c.drawText((names ? Ui.tr("On") : Ui.tr("Off")).toUpperCase(Ui.locale()), left + w - 26 * k, base, pValue);
            pSub.clearShadowLayer();
            pValue.clearShadowLayer();
            if (i == 0 && on && biohazard != null) {   // the game marks its blog entry with this sign, on the left
                float s = 36 * k;
                arrowDst.set(left - 52 * k, mid - s / 2, left - 52 * k + s, mid + s / 2);
                pBmp.setAlpha((int) (255 * Math.max(0f, Math.min(1f, glow.value))));
                c.drawBitmap(biohazard, null, arrowDst, pBmp);
            }
        }
    }

    // MARK: - The blog post

    private int blogMaxTop() {
        return Math.max(0, blogLines.size() - BLOG_LINES);
    }

    /** The post's text broken into lines that fit its column. */
    private void layoutBlog() {
        blogLines.clear();
        float max = BLOG_BODY_W * k;
        for (String para : blog.paragraphs) {
            if (para.isEmpty()) {
                blogLines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (pBody.measureText(tryLine) <= max) {
                    line.setLength(0);
                    line.append(tryLine);
                    continue;
                }
                if (line.length() > 0) {
                    blogLines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (pBody.measureText(rest) > max) {   // a word longer than the column is cut
                    int n = Math.max(1, pBody.breakText(rest, true, max, null));
                    blogLines.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                line.append(rest);
            }
            if (line.length() > 0) blogLines.add(line.toString());
        }
        blogTop = Math.min(blogTop, blogMaxTop());
        blogScroll.snap(blogTop);
    }

    private void openBlog() {
        mode = BLOG;
        blogFocus = 0;
        blogTop = 0;
        layoutBlog();
        glow.snap(0);
        glow.target = 1;
    }

    private void drawBlog(Canvas c) {
        c.drawText(blog.date, BLOG_X * k, BLOG_DATE_Y * k, pHead);
        c.drawText(blog.title, BLOG_X * k, BLOG_TITLE_Y * k, pHead);
        // the text: white when it is the one selected, as dim as the menu's options when not
        pBody.setColor(blogFocus == 0 ? SELECTED : IDLE);
        c.save();
        c.clipRect(0, (BLOG_BODY_Y - BLOG_SIZE) * k, 1000 * k, (BLOG_BODY_Y + (BLOG_LINES - 1) * BLOG_LINE + 14) * k);
        int first = Math.max(0, (int) Math.floor(blogScroll.value) - 1);
        for (int i = first; i < blogLines.size() && i <= first + BLOG_LINES + 2; i++) {
            c.drawText(blogLines.get(i), BLOG_BODY_X * k, (BLOG_BODY_Y + (i - blogScroll.value) * BLOG_LINE) * k, pBody);
        }
        c.restore();
        // the picture, and under it the link
        if (blogImage != null) {
            dst.set(BLOG_IMG_X * k, BLOG_IMG_Y * k, (BLOG_IMG_X + BLOG_IMG_W) * k, (BLOG_IMG_Y + BLOG_IMG_H) * k);
            pBmp.setAlpha(255);
            c.drawBitmap(blogImage, null, dst, pBmp);
        }
        float cx = (BLOG_IMG_X + BLOG_IMG_W / 2) * k;
        pBody.setTextAlign(Paint.Align.CENTER);
        pBody.setColor(blogFocus == 1 ? SELECTED : IDLE);
        if (blogFocus == 1) glowOn(pBody);
        c.drawText(blog.caption, cx, (BLOG_IMG_Y + BLOG_IMG_H + 62) * k, pBody);
        pBody.clearShadowLayer();
        pBody.setTextAlign(Paint.Align.LEFT);
        // Back, with the theme's arrow in red
        Bitmap backArrow = arrowL != null ? arrowL : arrow;
        float ah = 56 * k, aw = backArrow == null ? 40 * k : ah * backArrow.getWidth() / backArrow.getHeight();
        float ay = BLOG_BACK_Y * k;
        if (backArrow != null) {
            arrowDst.set(BLOG_BACK_X * k, ay, BLOG_BACK_X * k + aw, ay + ah);
            c.drawBitmap(backArrow, null, arrowDst, pTint);
        } else {
            pArrow.setColor(BLOG_RED);
            c.save();
            c.translate(BLOG_BACK_X * k, ay + (ah - 32 * k) / 2);
            c.drawPath(arrowLeft, pArrow);
            c.restore();
            pArrow.setColor(Color.WHITE);
        }
        pBack.setColor(blogFocus == 2 ? SELECTED : IDLE);
        if (blogFocus == 2) glowOn(pBack);
        c.drawText(Ui.tr("Back").toUpperCase(Ui.locale()), BLOG_BACK_X * k + aw + 34 * k, ay + ah / 2 + Ui.halfCap(pBack), pBack);
        pBack.clearShadowLayer();
    }

    private void blogScrollBy(int lines) {
        blogTop = Math.max(0, Math.min(blogMaxTop(), blogTop + lines));
        blogScroll.target = blogTop;
    }

    private void blogKey(int code, boolean ok) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (blogFocus == 0) blogScrollBy(-1);
                else if (blogFocus == 2) blogFocus = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (blogFocus == 0) {
                    if (blogTop < blogMaxTop()) blogScrollBy(1);
                    else blogFocus = 2;
                } else if (blogFocus == 1) {
                    blogFocus = 2;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (blogFocus == 1) blogFocus = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (blogFocus != 1) blogFocus = 1;
                break;
            case KeyEvent.KEYCODE_BACK:
                mode = EXTRAS;
                break;
            default:
                if (!ok) break;
                if (blogFocus == 1) openLink();
                else if (blogFocus == 2) mode = EXTRAS;
                else if (blogTop < blogMaxTop()) blogScrollBy(BLOG_LINES - 1);
                else blogFocus = 2;
        }
        glow.snap(0);
        glow.target = 1;
        invalidate();
    }

    /** The link under the picture, in the TV's default browser. */
    private void openLink() {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(blog.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("blog link: " + e.getMessage());
            toast(Ui.trf("Couldn't open {app}.", "app", blog.link));
        }
    }

    // MARK: - All Apps (the grid)

    private float gridLeft() {
        return (1920 - (GRID_COLS * TILE_W + (GRID_COLS - 1) * GRID_GAP)) / 2f;
    }

    private void drawGrid(Canvas c) {
        pTitle.setColor(SELECTED);
        c.drawText(menu[0], POLA_X * k, 110 * k, pTitle);
        int n = apps.size();
        Theme.Frame f = names ? tileName : tilePlain;
        float tileH = TILE_W * f.h / f.w, rowH = tileH + 52;
        c.save();
        c.clipRect(0, 130 * k, getWidth(), getHeight());
        for (int i = 0; i < n; i++) {
            int row = i / GRID_COLS, col = i % GRID_COLS;
            float y = (GRID_Y + (row - scroll.value) * rowH) * k;
            if (y + tileH * k < 120 * k || y > getHeight()) continue;
            float x = (gridLeft() + col * (TILE_W + GRID_GAP)) * k;
            Entry e = apps.get(i);
            if (i == gridSel) {
                // the focused one grows into a white frame, turned like the polaroid
                float w = TILE_W * k * 1.12f, h = w * white.h / white.w;
                c.save();
                c.translate(x + TILE_W * k / 2, y + tileH * k / 2);
                c.rotate(POLA_ANGLE);
                drawIconIn(c, e.pkg, -w / 2, -h / 2, w, h, white, white, 255);
                c.restore();
            } else {
                drawTile(c, e, x, y, TILE_W * k, 1f, names);
            }
        }
        c.restore();
        if (n > 0) {
            pMenu.setColor(SELECTED);
            glowOn(pMenu);
            c.drawText(apps.get(gridSel).upper, POLA_X * k, 1040 * k, pMenu);
            pMenu.clearShadowLayer();
        }
    }

    // MARK: - Keys

    private void setFocus(int f) {
        focus = f;
        glow.snap(0);
        glow.target = 1;
        invalidate();
    }

    /** One step of the wheel: +1 brings the next app to the polaroid, -1 the one before. */
    private void turn(int dir) {
        int n = apps.size();
        if (n < 2) return;
        if (animating) finishTurn();   // a key pressed in the middle of a turn: that one ends at once
        animFrom = sel;
        animDir = dir;
        sel = Math.floorMod(sel + dir, n);
        animating = true;
        animStart = System.nanoTime();
        animP = 0;
        invalidate();
    }

    /** The turn is over: the title changes now, not before. */
    private void finishTurn() {
        animating = false;
        layoutTitle();
    }

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK;
        if (!ours) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (mode == GRID) gridKey(code, ok);
        else if (mode == EXTRAS) extrasKey(code, ok);
        else if (mode == BLOG) blogKey(code, ok);
        else homeKey(code, ok);
        return true;
    }

    private void homeKey(int code, boolean ok) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focus < MENU_N) setFocus(focus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus > 0) setFocus(focus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focus == 0) turn(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focus == 0) turn(1);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (focus != 0) setFocus(0);
                break;
            default:
                if (ok) activate();
        }
    }

    private void activate() {
        switch (focus) {
            case 0:
                if (!apps.isEmpty()) openApp(apps.get(sel), (int) (POLA_X * k), (int) (POLA_Y * k), (int) (POLA_W * k), (int) (polaHeight() * k));
                break;
            case 1:
                showAllApps();
                break;
            case 2:
                HomeApps.open(act, HomeApps.settings(), null);
                break;
            case 3:
                openStore();
                break;
            case 4:
                mode = EXTRAS;
                sub = 0;
                glow.snap(0);
                glow.target = 1;
                invalidate();
                break;
            case 5:
                showInputs();
                break;
            default:
                powerOff();
        }
    }

    private void extrasKey(int code, boolean ok) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sub = Math.min(3, sub + 1);
                glow.snap(0);
                glow.target = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                sub = Math.max(0, sub - 1);
                glow.snap(0);
                glow.target = 1;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
                mode = HOME;
                glow.snap(0);
                glow.target = 1;
                break;
            default:
                if (!ok) break;
                if (sub == 0) {
                    openBlog();
                } else if (sub == 1) {
                    names = !names;
                    Prefs.setL4dNames(names);
                } else if (sub == 2) {
                    if (!HomeApps.open(act, HomeApps.tvSettings(), null)) toast(Ui.tr("Couldn't open TV Settings"));
                } else {
                    Prefs.setL4d2(false);
                    act.recreate();
                    return;
                }
        }
        invalidate();
    }

    private void gridKey(int code, boolean ok) {
        int n = apps.size();
        if (n == 0) {
            if (code == KeyEvent.KEYCODE_BACK) mode = HOME;
            invalidate();
            return;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                gridSel = Math.max(0, gridSel - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                gridSel = Math.min(n - 1, gridSel + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridSel >= GRID_COLS) gridSel -= GRID_COLS;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                gridSel = Math.min(n - 1, gridSel + GRID_COLS);
                break;
            case KeyEvent.KEYCODE_BACK:
                mode = HOME;
                break;
            default:
                if (ok) {
                    openApp(apps.get(gridSel), getWidth() / 2, getHeight() / 2, (int) (TILE_W * k), (int) (TILE_W * k));
                    return;
                }
        }
        int row = gridSel / GRID_COLS;
        if (row < scroll.target) scroll.target = row;
        else if (row > scroll.target + GRID_ROWS - 1) scroll.target = row - (GRID_ROWS - 1);
        invalidate();
    }

    // MARK: - Actions

    private void openApp(Entry e, int x, int y, int w, int h) {
        if (!HomeApps.open(act, HomeApps.app(e.pkg, e.label), this, x, y, w, h)) toast(Ui.trf("Couldn't open {app}.", "app", e.label));
    }

    /** The TV's own app store first (this TV's is com.appstore.cloud), then Google Play. */
    private void openStore() {
        String label = Ui.tr("App Store");
        if (HomeApps.open(act, HomeApps.app("com.appstore.cloud", label), null)) return;
        String pkg = HomeApps.storePackage(act);
        if (pkg != null && HomeApps.open(act, HomeApps.app(pkg, label), null)) return;
        toast(Ui.tr("This TV has no supported app store to open."));
    }

    /** The TV to standby, through its own ADB (the sleep timer's way). */
    private void powerOff() {
        Context ctx = act.getApplicationContext();
        new Thread(() -> {
            try (Adb adb = Adb.connect(ctx, 10_000, null)) {
                adb.shell("input keyevent KEYCODE_SLEEP");
            } catch (IOException e) {
                AppLog.w("power off: " + e.getMessage());
            }
        }, "l4d-power").start();
    }

    private void toast(String text) {
        Toast.makeText(act, text, Toast.LENGTH_SHORT).show();
    }
}
