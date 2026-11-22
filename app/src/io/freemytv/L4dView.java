package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
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
    private static final float STRIP_X = 700, STRIP_Y = 265, TILE_W = 190, STEP = 235;
    private static final int STRIP_N = 5;
    private static final float TITLE_Y = 555, MENU_Y = 670, MENU_STEP = 50;
    private static final float TITLE_SIZE = 40, DESC_SIZE = 28, MENU_SIZE = 35, LABEL_SIZE = 22;
    private static final float CLOCK_RIGHT = 1790, CLOCK_BASE = 175, CLOCK_SIZE = 140, AMPM_SIZE = 56;
    private static final int GRID_COLS = 6, GRID_ROWS = 3;
    private static final float GRID_Y = 170, GRID_GAP = 45;
    private static final int ICON = 320;
    private static final int MENU_N = 6;

    private static final int SELECTED = Color.rgb(250, 250, 250), IDLE = Color.rgb(127, 127, 135), RED = Color.rgb(214, 49, 38);
    private static final int HOME = 0, EXTRAS = 1, GRID = 2;

    /** An app of the wheel, with the small differences each tile keeps (a tilt of the polaroid, a nudge of the tile). */
    private static final class Entry {
        final String pkg, label, upper;
        final float angle, dy, rot;
        String fit;
        float fitWidth;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
            this.upper = label.toUpperCase(Ui.locale());
            int h = pkg.hashCode();
            angle = (h & 0xff) % 7 - 3;
            dy = ((h >> 8) & 0xff) % 11 - 5;
            rot = (((h >> 16) & 0xff) % 5 - 2) * 0.5f;
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

    private final Theme.Frame white, black, plain;

    // The springs: the glow of the focused text, the strip's slide (in slots), the polaroid's pop and tilt, the grid's scroll (in rows).
    private final Spring glow = new Spring(0.30f, 1f, 1), slide = new Spring(0.22f, 0.9f, 0),
            pop = new Spring(0.34f, 0.55f, 0), tilt = new Spring(0.34f, 0.65f, 0), scroll = new Spring(0.26f, 1f, 0);
    private long last;

    private final Paint pBg = new Paint(), pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            pFill = new Paint(), pStroke = new Paint(Paint.ANTI_ALIAS_FLAG), pArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle, pMenu, pSub, pValue, pLabel, pClock, pColon, pAmPm;
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
        Typeface tTitle = Theme.font(a, "stubble", bold);
        Typeface tClock = Theme.font(a, "future", bold);
        Typeface tBody = Theme.font(a, "arial", Typeface.SANS_SERIF);
        pTitle = Ui.text(tTitle, TITLE_SIZE, SELECTED);
        pMenu = Ui.text(tTitle, MENU_SIZE, IDLE);
        pSub = Ui.text(tTitle, 32, IDLE);
        pValue = Ui.text(tTitle, 32, IDLE);
        pValue.setTextAlign(Paint.Align.RIGHT);
        pLabel = Ui.text(tTitle, LABEL_SIZE, SELECTED);
        pClock = Ui.text(tClock, CLOCK_SIZE, SELECTED);
        pColon = Ui.text(tClock, CLOCK_SIZE, RED);
        pAmPm = Ui.text(tClock, AMPM_SIZE, SELECTED);
        pDescription = Ui.text(tBody, DESC_SIZE, SELECTED);
        pArrow.setColor(Color.WHITE);
        pStroke.setStyle(Paint.Style.STROKE);
        names = Prefs.l4dNames();
        white = Theme.frame(a, Theme.WHITE_FILE, Theme.WHITE);
        black = Theme.frame(a, Theme.BLACK_FILE, Theme.BLACK);
        plain = Theme.frame(a, Theme.PLAIN_FILE, Theme.PLAIN);
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
        tilt.snap(apps.isEmpty() ? 0 : apps.get(sel).angle);
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
        boolean moving = glow.step(dt) | slide.step(dt) | pop.step(dt) | tilt.step(dt) | scroll.step(dt);
        c.drawRect(0, 0, getWidth(), getHeight(), pBg);
        if (mode == GRID) drawGrid(c);
        else {
            drawClock(c);
            if (!apps.isEmpty()) {
                drawStrip(c);
                drawPolaroid(c);
                drawTitle(c);
            }
            drawMenu(c);
            if (mode == EXTRAS) drawExtras(c);
        }
        if (moving) postInvalidateOnAnimation();
        else last = 0;
    }

    /** White glow behind the text (the shadow layer, drawn with it): it fades in when the text takes the focus. */
    private void glowOn(Paint p) {
        float g = Math.max(0f, Math.min(1f, glow.value));
        p.setShadowLayer(16 * k, 0, 0, Color.argb((int) (220 * g), 255, 255, 255));
    }

    private void drawClock(Canvas c) {
        float x = CLOCK_RIGHT * k - hhW - colonW - mmW - (ap.isEmpty() ? 0 : apW + 12 * k);
        float y = CLOCK_BASE * k;
        c.drawText(hh, x, y, pClock);
        c.drawText(":", x + hhW, y, pColon);
        c.drawText(mm, x + hhW + colonW, y, pClock);
        if (!ap.isEmpty()) c.drawText(ap, x + hhW + colonW + mmW + 12 * k, y - Ui.cap(pClock) + Ui.cap(pAmPm), pAmPm);
    }

    /** The other apps, turning like a wheel: the one that left the polaroid goes to the end. */
    private void drawStrip(Canvas c) {
        int n = apps.size();
        int s = Math.min(STRIP_N, n - 1);
        if (s <= 0) return;
        float v = slide.value;
        Theme.Frame f = names ? black : plain;
        for (int j = -1; j <= s; j++) {
            float alpha = 1;
            if (v > 0) {
                if (j == s) continue;
                if (j == -1) alpha = v;
                else if (j == s - 1) alpha = 1 - v;
            } else if (v < 0) {
                if (j == -1) continue;
                if (j == 0) alpha = 1 + v;
                else if (j == s) alpha = -v;
            } else if (j == -1 || j == s) continue;
            alpha = Math.max(0f, Math.min(1f, alpha));
            if (alpha < 0.01f) continue;
            Entry e = apps.get(Math.floorMod(sel + 1 + j, n));
            float x = (STRIP_X + (j + v) * STEP) * k;
            drawTile(c, e, x, STRIP_Y * k, TILE_W * k, f, alpha, names);
        }
    }

    /** An app in its black frame: the icon in the window, the frame over it, and the name on the band if there is one. */
    private void drawTile(Canvas c, Entry e, float x, float y, float w, Theme.Frame f, float alpha, boolean label) {
        float sc = w / f.w, h = f.h * sc;
        int a = (int) (255 * alpha);
        c.save();
        c.translate(x + w / 2, y + h / 2 + e.dy * k);
        c.rotate(e.rot);
        float l = -w / 2, t = -h / 2;
        drawIconIn(c, e.pkg, l, t, sc, f, a);
        if (label && !f.band.isEmpty()) {
            float bandH = f.band.height() * sc;
            pLabel.setTextSize(Math.min(LABEL_SIZE * k, bandH * 0.62f));
            pLabel.setAlpha(a);
            float base = t + f.band.top * sc + bandH / 2 + Ui.halfCap(pLabel);
            float pad = w * 0.07f;
            c.drawText(e.fit(pLabel, w - 2 * pad), l + pad, base, pLabel);
        }
        c.restore();
    }

    /** The icon in the frame's window and the frame on top, the frame's corner at (l, t) and scaled by sc. */
    private void drawIconIn(Canvas c, String pkg, float l, float t, float sc, Theme.Frame f, int a) {
        dst.set(l + f.window.left * sc, t + f.window.top * sc, l + f.window.right * sc, t + f.window.bottom * sc);
        pBmp.setAlpha(a);
        Bitmap ic = icon(pkg);
        if (ic != null) c.drawBitmap(ic, null, dst, pBmp);
        else {
            pFill.setColor(Color.rgb(40, 40, 44));
            pFill.setAlpha(a);
            c.drawRect(dst, pFill);
        }
        frameDst.set(l, t, l + f.w * sc, t + f.h * sc);
        c.drawBitmap(f.bitmap, null, frameDst, pBmp);
    }

    private float polaHeight() {
        return POLA_W * white.h / white.w;
    }

    private void drawPolaroid(Canvas c) {
        Entry e = apps.get(sel);
        float w = POLA_W * k * (1 + 0.12f * pop.value);
        float sc = w / white.w, h = white.h * sc;
        float cx = (POLA_X + POLA_W / 2f) * k, cy = (POLA_Y + polaHeight() / 2f) * k;
        c.save();
        c.translate(cx, cy);
        c.rotate(tilt.value);
        drawIconIn(c, e.pkg, -w / 2, -h / 2, sc, white, 255);
        c.restore();
        pArrow.setAlpha(focus == 0 && mode == HOME ? 235 : 110);
        float ay = (POLA_Y + polaHeight() - 70) * k;
        c.save();
        c.translate((POLA_X - 48) * k, ay);
        c.drawPath(arrowLeft, pArrow);
        c.restore();
        c.save();
        c.translate((POLA_X + POLA_W + 26) * k, ay);
        c.drawPath(arrowRight, pArrow);
        c.restore();
    }

    private void drawTitle(Canvas c) {
        boolean on = focus == 0 && mode == HOME;
        pTitle.setColor(SELECTED);
        if (on) glowOn(pTitle);
        c.drawText(title, POLA_X * k, TITLE_Y * k, pTitle);
        pTitle.clearShadowLayer();
    }

    private void drawMenu(Canvas c) {
        float x = POLA_X * k;
        for (int i = 0; i < MENU_N; i++) {
            boolean on = mode == HOME ? focus == i + 1 : mode == EXTRAS && i == 3;
            pMenu.setColor(on ? SELECTED : IDLE);
            if (on) glowOn(pMenu);
            c.drawText(menu[i], x, (MENU_Y + i * MENU_STEP) * k, pMenu);
            pMenu.clearShadowLayer();
        }
    }

    /** The little menu of Extras, beside it, like the game's: black with a red outline. */
    private void drawExtras(Canvas c) {
        float left = (POLA_X + 330) * k, top = (MENU_Y + 3 * MENU_STEP - 44) * k;
        float w = 700 * k, rowH = 56 * k, h = 3 * rowH + 28 * k;
        pFill.setColor(Color.argb(238, 0, 0, 0));
        c.drawRect(left, top, left + w, top + h, pFill);
        pStroke.setColor(RED);
        c.drawRect(left, top, left + w, top + h, pStroke);
        String[] rows = {Ui.tr("Names in the carousel"), Ui.tr("TV Settings"), Ui.tr("Original home screen")};
        for (int i = 0; i < rows.length; i++) {
            boolean on = sub == i;
            float base = top + 14 * k + rowH * i + rowH / 2 + Ui.halfCap(pSub);
            pSub.setColor(on ? SELECTED : IDLE);
            pValue.setColor(on ? SELECTED : IDLE);
            if (on) {
                glowOn(pSub);
                glowOn(pValue);
            }
            c.drawText(rows[i], left + 26 * k, base, pSub);
            if (i == 0) c.drawText(names ? Ui.tr("On") : Ui.tr("Off"), left + w - 26 * k, base, pValue);
            pSub.clearShadowLayer();
            pValue.clearShadowLayer();
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
        Theme.Frame f = names ? black : plain;
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
                // the focused one grows into a white frame
                float w = TILE_W * k * 1.12f, sc = w / white.w, h = white.h * sc;
                float cx = x + TILE_W * k / 2, cy = y + tileH * k / 2;
                c.save();
                c.translate(cx, cy);
                c.rotate(e.angle * 0.5f);
                drawIconIn(c, e.pkg, -w / 2, -h / 2, sc, white, 255);
                c.restore();
            } else {
                drawTile(c, e, x, y, TILE_W * k, f, 1f, names);
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
        sel = Math.floorMod(sel + dir, n);
        slide.snap(dir);
        slide.target = 0;
        pop.snap(1);
        pop.target = 0;
        tilt.target = apps.get(sel).angle;
        layoutTitle();
        invalidate();
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
                sub = Math.min(2, sub + 1);
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
                    names = !names;
                    Prefs.setL4dNames(names);
                } else if (sub == 1) {
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
