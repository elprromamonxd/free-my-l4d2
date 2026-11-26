package io.freemytv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The blog post of the L4D2 look's Extras menu. Its words are not interface text: they come from blog.txt in the theme
 * folder (so they can be changed without a new app) or, without it, from the sample below.
 *
 * blog.txt: lines "date: ...", "title: ...", "caption: ..." (the text under the picture) and "link: ..." (what the caption
 * opens), then a line with three dashes (---), then the text: a blank line starts a new paragraph.
 */
final class Blog {
    final String date, title, caption, link;
    /** The text's paragraphs; an empty entry is the blank line between two of them. */
    final ArrayList<String> paragraphs;

    private Blog(String date, String title, String caption, String link, ArrayList<String> paragraphs) {
        this.date = date;
        this.title = title;
        this.caption = caption;
        this.link = link;
        this.paragraphs = paragraphs;
    }

    static Blog load(Context c) {
        File f = new File(Theme.dir(c), "blog.txt");
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                Blog b = parse(r);
                if (b != null) return b;
            } catch (IOException | RuntimeException e) {
                AppLog.w("blog.txt: " + e.getMessage());
            }
        }
        return sample();
    }

    private static Blog parse(BufferedReader r) throws IOException {
        String date = "", title = "", caption = "", link = "";
        String line = r.readLine();
        boolean header = true;
        ArrayList<String> paragraphs = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        boolean first = true;
        while (line != null) {
            if (first && line.startsWith("\uFEFF")) line = line.substring(1);
            first = false;
            if (header) {
                if (line.trim().equals("---")) header = false;
                else {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String value = line.substring(colon + 1).trim();
                        if (key.equals("date")) date = value;
                        else if (key.equals("title")) title = value;
                        else if (key.equals("caption")) caption = value;
                        else if (key.equals("link")) link = value;
                    }
                }
            } else if (line.trim().isEmpty()) {
                if (para.length() > 0) {
                    paragraphs.add(para.toString());
                    para.setLength(0);
                    paragraphs.add("");
                }
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(line.trim());
            }
            line = r.readLine();
        }
        if (header) return null;   // no line of three dashes: not a blog file
        if (para.length() > 0) paragraphs.add(para.toString());
        else if (!paragraphs.isEmpty()) paragraphs.remove(paragraphs.size() - 1);
        return new Blog(date, title, caption, link, paragraphs);
    }

    /** What shows until there is a blog.txt. */
    private static Blog sample() {
        ArrayList<String> p = new ArrayList<>();
        p.add("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Aliquam malesuada id velit nec convallis. Sed ex nibh, "
                + "faucibus tincidunt dapibus vel, fringilla quis eros. Suspendisse vulputate neque ut velit sodales consectetur. "
                + "Praesent eu mauris nisl. Curabitur a nunc suscipit, blandit massa in, egestas diam. Suspendisse non ex velit. "
                + "Aliquam quis est non orci fermentum suscipit. Nam sed tellus a augue luctus efficitur.");
        p.add("");
        p.add("Donec vitae sapien ut libero venenatis faucibus. Nullam quis ante. Etiam sit amet orci eget eros faucibus tincidunt. "
                + "Duis leo. Sed fringilla mauris sit amet nibh. Donec sodales sagittis magna. Sed consequat, leo eget bibendum "
                + "sodales, augue velit cursus nunc.");
        p.add("");
        p.add("Quisque ut nisi. Vestibulum ullamcorper mauris at ligula. Fusce a quam. Praesent adipiscing. Ut tincidunt tincidunt "
                + "erat. Cras ultricies mi eu turpis hendrerit fringilla. Vestibulum ante ipsum primis in faucibus orci luctus et "
                + "ultrices posuere cubilia curae.");
        return new Blog("2026/11/25", "¡Primera publicación del blog!", "(Link del github)",
                "https://github.com/elprromamonxd/free-my-l4d2", p);
    }
}
