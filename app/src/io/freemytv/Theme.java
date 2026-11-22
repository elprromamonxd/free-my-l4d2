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

    static final String WHITE_FILE = "marco_blanco.png", BLACK_FILE = "marco_negro.png", PLAIN_FILE = "marco_negro_2.png";
    static final int WHITE = 0, BLACK = 1, PLAIN = 2;

    static File dir(Context c) {
        File d = c.getExternalFilesDir("theme");
        if (d == null) d = new File(c.getFilesDir(), "theme");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The font whose file name has `word` in it (the bold one when there are several), or `fallback`. */
    static Typeface font(Context c, String word, Typeface fallback) {
        File[] files = dir(c).listFiles();
        if (files == null) return fallback;
        File best = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.contains(word) || !(n.endsWith(".ttf") || n.endsWith(".otf"))) continue;
            if (best == null || n.contains("bold") && !best.getName().toLowerCase(Locale.ROOT).contains("bold")) best = f;
        }
        if (best == null) return fallback;
        try {
            return Typeface.createFromFile(best);
        } catch (RuntimeException e) {
            AppLog.w("theme font " + best.getName() + ": " + e.getMessage());
            return fallback;
        }
    }

    /** A frame: its picture, the transparent window for the icon and, if it has one, the band under it for the name. */
    static final class Frame {
        final Bitmap bitmap;
        final int w, h;
        final Rect window = new Rect();
        final Rect band = new Rect();

        Frame(Bitmap b) {
            bitmap = b;
            w = b.getWidth();
            h = b.getHeight();
            findWindow();
            // A band is a bottom border much thicker than the top one (the plain black frame has none).
            if (window.top > 0 && h - window.bottom > window.top * 2.5f) band.set(0, window.bottom, w, h);
        }

        /** The transparent rectangle around the middle: the longest transparent run down the center, then across it. */
        private void findWindow() {
            int[] col = new int[h];
            bitmap.getPixels(col, 0, 1, w / 2, 0, 1, h);
            int[] run = longestClear(col, h);
            if (run == null) {   // no window found: the middle of the picture
                window.set(w / 10, h / 10, w - w / 10, h - h / 6);
                return;
            }
            int[] row = new int[w];
            bitmap.getPixels(row, 0, w, 0, (run[0] + run[1]) / 2, w, 1);
            int[] across = longestClear(row, w);
            if (across == null) across = new int[] {w / 10, w - w / 10};
            window.set(across[0], run[0], across[1], run[1]);
        }

        /** [start, end) of the longest run of (almost) transparent pixels, or null. */
        private static int[] longestClear(int[] px, int n) {
            int bestStart = -1, bestLen = 0, start = -1;
            for (int i = 0; i <= n; i++) {
                boolean clear = i < n && Color.alpha(px[i]) < 16;
                if (clear && start < 0) start = i;
                if (!clear && start >= 0) {
                    if (i - start > bestLen) {
                        bestLen = i - start;
                        bestStart = start;
                    }
                    start = -1;
                }
            }
            return bestLen < 8 ? null : new int[] {bestStart, bestStart + bestLen};
        }
    }

    /** The frame of that file in the theme folder, or the plain one drawn here. */
    static Frame frame(Context c, String file, int kind) {
        File f = new File(dir(c), file);
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return new Frame(b);
            AppLog.w("theme frame " + file + " could not be read");
        }
        return new Frame(placeholder(kind));
    }

    /** WHITE: a polaroid (thicker base). BLACK: a thin border with the band for the name. PLAIN: a thin border only. */
    private static Bitmap placeholder(int kind) {
        int w, h, border, bottom;
        int color;
        switch (kind) {
            case WHITE: border = 22; bottom = 52; color = Color.WHITE; break;
            case BLACK: border = 10; bottom = 50; color = Color.BLACK; break;
            default: border = 10; bottom = 10; color = Color.BLACK; break;
        }
        w = 256 + 2 * border;
        h = border + 256 + bottom;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint();
        p.setColor(color);
        cv.drawRect(0, 0, w, h, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        cv.drawRect(border, border, border + 256, border + 256, p);
        return b;
    }
}
