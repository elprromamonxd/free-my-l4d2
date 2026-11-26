package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.io.File;
import java.util.Locale;

/**
 * The files of the L4D2 look, kept on the TV and never in the repository (fonts and art have their own licenses):
 * the app's own "theme" folder (Android/data/io.freemytv/files/theme). Whatever is missing is drawn or replaced here,
 * so the screen works with an empty folder.
 *
 * Fonts are found by a word in the file name (stubble, future, arial; .ttf or .otf). The frames are PNGs with a
 * transparent window: the window is where the icon goes, found from the transparency, so any size works.
 */
final class Theme {
    private Theme() {}

    static final int WHITE = 0, BLACK = 1, PLAIN = 2;

    static File dir(Context c) {
        File d = c.getExternalFilesDir("theme");
        if (d == null) d = new File(c.getFilesDir(), "theme");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Does the file name say it is the bold face of its family (name-bold.ttf, arialbd.ttf)? */
    private static boolean isBoldFile(String lowerName) {
        String base = lowerName.substring(0, lowerName.lastIndexOf('.') < 0 ? lowerName.length() : lowerName.lastIndexOf('.'));
        return base.contains("bold") || base.endsWith("bd");
    }

    /**
     * The font whose file name has `word` in it: the bold face when `bold`, the regular one when not. If only the other
     * one is there, that one (made bold when bold was asked); with no file at all, `fallback`.
     */
    static Typeface font(Context c, String word, Typeface fallback, boolean bold) {
        File[] files = dir(c).listFiles();
        File best = null;
        int bestScore = 0;
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!n.contains(word) || !(n.endsWith(".ttf") || n.endsWith(".otf"))) continue;
                int score = isBoldFile(n) == bold ? 2 : 1;
                if (score > bestScore) {
                    best = f;
                    bestScore = score;
                }
            }
        }
        if (best == null) return bold ? Typeface.create(fallback, Typeface.BOLD) : fallback;
        try {
            Typeface t = Typeface.createFromFile(best);
            return bold && !isBoldFile(best.getName().toLowerCase(Locale.ROOT)) ? Typeface.create(t, Typeface.BOLD) : t;
        } catch (RuntimeException e) {
            AppLog.w("theme font " + best.getName() + ": " + e.getMessage());
            return bold ? Typeface.create(fallback, Typeface.BOLD) : fallback;
        }
    }

    /** A picture of the theme folder (the blog's, the biohazard sign), or null. */
    static Bitmap image(Context c, String file) {
        File f = new File(dir(c), file);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /** What counts as the frame's own ink (opaque) when looking for the window. */
    private static final int SOLID = 230;

    /**
     * A frame: its picture, the window for the icon and, if it has one, the band under it for the name. The window is
     * what the middle of the picture reaches without crossing the frame's ink (the inside may be a soft shade, not clear).
     */
    static final class Frame {
        final Bitmap bitmap;
        final int w, h;
        final Rect window, band;

        Frame(Bitmap b) {
            bitmap = b;
            w = b.getWidth();
            h = b.getHeight();
            window = findWindow(b);
            band = new Rect();
            // A band is a bottom border much thicker than the side ones (a frame with no name has none).
            float side = Math.max(1f, (window.left + (w - window.right)) / 2f);
            if (h - window.bottom > 2f * side) band.set(0, window.bottom, w, h);
        }

        private Frame(Bitmap b, Rect window, Rect band) {
            bitmap = b;
            w = b.getWidth();
            h = b.getHeight();
            this.window = window;
            this.band = band;
        }

        /** The same frame at another width (taller or shorter in proportion), reduced in steps so its fine texture stays clean. */
        Frame scaledTo(int newW) {
            if (newW <= 0 || newW >= w) return this;
            int newH = Math.max(1, Math.round(h * (float) newW / w));
            Bitmap cur = bitmap;
            while (cur.getWidth() / 2 >= newW) {
                Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                if (cur != bitmap) cur.recycle();
                cur = half;
            }
            Bitmap out = Bitmap.createScaledBitmap(cur, newW, newH, true);
            if (cur != bitmap && cur != out) cur.recycle();
            float sx = (float) out.getWidth() / w, sy = (float) out.getHeight() / h;
            Rect win = new Rect(Math.round(window.left * sx), Math.round(window.top * sy), Math.round(window.right * sx), Math.round(window.bottom * sy));
            Rect bnd = new Rect();
            if (!band.isEmpty()) bnd.set(0, win.bottom, out.getWidth(), out.getHeight());
            return new Frame(out, win, bnd);
        }

        /** Spreads from the middle over every pixel that is not solid; the window is the box of what it reached. */
        private static Rect findWindow(Bitmap b) {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            int start = (h / 2) * w + w / 2;
            if (Color.alpha(px[start]) >= SOLID) return new Rect(w / 10, h / 10, w - w / 10, h - h / 6);
            boolean[] seen = new boolean[w * h];
            int[] stack = new int[w * h];
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            int minX = w, minY = h, maxX = 0, maxY = 0;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w, y = p / w;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int d = 0; d < 4; d++) {
                    int nx = d == 0 ? x - 1 : d == 1 ? x + 1 : x, ny = d == 2 ? y - 1 : d == 3 ? y + 1 : y;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int q = ny * w + nx;
                    if (seen[q] || Color.alpha(px[q]) >= SOLID) continue;
                    seen[q] = true;
                    stack[sp++] = q;
                }
            }
            return new Rect(minX, minY, maxX + 1, maxY + 1);
        }
    }

    /** The frame from the first of these files that is in the theme folder, or the plain one drawn here. */
    static Frame frame(Context c, int kind, String... files) {
        for (String file : files) {
            File f = new File(dir(c), file);
            if (!f.isFile()) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return new Frame(b);
            AppLog.w("theme frame " + file + " could not be read");
        }
        return new Frame(placeholder(kind));
    }

    /** The arrow beside the polaroid (pointing left; the right one is its mirror), or null. */
    static Bitmap arrow(Context c) {
        File f = new File(dir(c), "flecha.png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    /**
     * What is drawn when a frame's file is missing, all of them squares like the real ones. WHITE: a polaroid. BLACK: a thin
     * border with a band at the bottom for the name. PLAIN: a thin border only.
     */
    private static Bitmap placeholder(int kind) {
        int size = kind == WHITE ? 300 : 276;
        int border = kind == WHITE ? 22 : 10;
        int windowBottom = kind == BLACK ? size - 60 : size - border;
        int color = kind == WHITE ? Color.WHITE : Color.BLACK;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint();
        p.setColor(color);
        cv.drawRect(0, 0, size, size, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        cv.drawRect(border, border, size - border, windowBottom, p);
        return b;
    }
}
