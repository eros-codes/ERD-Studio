package com.erdstudio.canvas;

import com.erdstudio.model.Area;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Note;
import com.erdstudio.model.Relation;
import com.erdstudio.model.Table;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a miniature of a diagram straight from the model (no live canvas needed), so the home
 * screen always shows an up-to-date preview of every project and follows the current theme.
 */
public final class Thumbnail {
    private Thumbnail() {}

    public static Canvas render(Diagram d, double w, double h, boolean dark) {
        Canvas c = new Canvas(w, h);
        draw(c.getGraphicsContext2D(), d, w, h, dark);
        return c;
    }

    public static void draw(GraphicsContext g, Diagram d, double w, double h, boolean dark) {
        Color bg = dark ? Color.web("#0f1420") : Color.web("#f6f7fb");
        Color dot = dark ? Color.web("#1f2738") : Color.web("#e3e6ee");
        Color card = dark ? Color.web("#1a2130") : Color.WHITE;
        Color border = dark ? Color.web("#2c3548") : Color.web("#dcdfe8");
        Color line = dark ? Color.web("#5b6780") : Color.web("#a8b0c2");
        Color text = dark ? Color.web("#e5e9f2") : Color.web("#1f2533");
        Color rowLine = dark ? Color.web("#2a3245") : Color.web("#eef0f5");

        g.setFill(bg);
        g.fillRect(0, 0, w, h);
        g.setFill(dot);
        for (double y = 8; y < h; y += 14) for (double x = 8; x < w; x += 14) g.fillOval(x, y, 1.4, 1.4);
        if (d == null) return;

        // world bounds
        Map<Table, double[]> size = new HashMap<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Table t : d.tables) {
            double tw = TableNode.computeWidth(t), th = TableNode.height(t);
            size.put(t, new double[]{tw, th});
            minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x + tw); maxY = Math.max(maxY, t.y + th);
        }
        for (Area a : d.areas) {
            minX = Math.min(minX, a.x); minY = Math.min(minY, a.y);
            maxX = Math.max(maxX, a.x + a.width); maxY = Math.max(maxY, a.y + a.height);
        }
        for (Note n : d.notes) {
            minX = Math.min(minX, n.x); minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + n.width); maxY = Math.max(maxY, n.y + n.height);
        }
        if (minX == Double.MAX_VALUE) return;
        double pad = 14;
        double s = Math.min((w - 2 * pad) / Math.max(1, maxX - minX), (h - 2 * pad) / Math.max(1, maxY - minY));
        s = Math.min(s, 0.55);
        double ox = (w - (maxX - minX) * s) / 2 - minX * s, oy = (h - (maxY - minY) * s) / 2 - minY * s;

        for (Area a : d.areas) {
            Color ac = color(a.color, Color.web("#6366f1"));
            g.setFill(ac.deriveColor(0, 1, 1, dark ? 0.12 : 0.09));
            g.setStroke(ac.deriveColor(0, 1, 1, 0.55));
            g.setLineWidth(1);
            double r = Math.max(2, 12 * s);
            g.fillRoundRect(ox + a.x * s, oy + a.y * s, a.width * s, a.height * s, r, r);
            g.strokeRoundRect(ox + a.x * s, oy + a.y * s, a.width * s, a.height * s, r, r);
        }

        g.setStroke(line);
        g.setLineWidth(Math.max(0.8, 1.6 * s));
        for (Relation rel : d.relations) {
            Table a = d.table(rel.fromTableId), b = d.table(rel.toTableId);
            if (a == null || b == null || a == b) continue;
            double[] sa = size.get(a), sb = size.get(b);
            double ax = a.x + sa[0] / 2, ay = a.y + Math.min(sa[1] / 2, 60), bx = b.x + sb[0] / 2, by = b.y + Math.min(sb[1] / 2, 60);
            // simple elbow so it reads like the real orthogonal routing
            double sx = ax < bx ? a.x + sa[0] : a.x, ex = ax < bx ? b.x : b.x + sb[0];
            double mx = (sx + ex) / 2;
            g.strokePolyline(new double[]{ox + sx * s, ox + mx * s, ox + mx * s, ox + ex * s},
                    new double[]{oy + ay * s, oy + ay * s, oy + by * s, oy + by * s}, 4);
        }

        for (Note n : d.notes) {
            g.setFill(color(n.color, Color.web("#fde68a")).deriveColor(0, 1, 1, dark ? 0.8 : 1));
            g.fillRoundRect(ox + n.x * s, oy + n.y * s, n.width * s, n.height * s, 4, 4);
        }

        boolean names = 13.5 * s >= 5.5;
        Font f = Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, Math.max(5, 13 * s));
        for (Table t : d.tables) {
            double[] sz = size.get(t);
            double x = ox + t.x * s, y = oy + t.y * s, tw = sz[0] * s, th = sz[1] * s;
            double r = Math.max(2, 10 * s);
            g.setFill(Color.rgb(0, 0, 0, dark ? 0.35 : 0.06));
            g.fillRoundRect(x, y + Math.max(1, 3 * s), tw, th, r, r);
            g.setFill(card);
            g.fillRoundRect(x, y, tw, th, r, r);
            g.setStroke(border);
            g.setLineWidth(0.8);
            g.strokeRoundRect(x, y, tw, th, r, r);
            g.setFill(color(t.color, Color.web("#6366f1")));
            g.fillRoundRect(x, y, tw, Math.max(1.5, 4 * s), r, r);
            double headerBottom = y + (4 + 38) * s;
            if (names && th > 14) {
                g.setFill(text);
                g.setFont(f);
                g.fillText(clip(t.name, tw / (7.5 * s)), x + 12 * s, y + (4 + 24) * s, tw - 16 * s);
            } else {
                g.setFill(text.deriveColor(0, 1, 1, 0.55));
                g.fillRoundRect(x + 10 * s, y + 18 * s, Math.min(tw * 0.55, Math.max(8, t.name.length() * 7 * s)), Math.max(1.5, 7 * s), 2, 2);
            }
            g.setStroke(rowLine);
            g.setLineWidth(0.7);
            if (28 * s >= 2.2) {
                for (int i = 0; i <= t.columns.size() && i < 60; i++) {
                    double ly = headerBottom + i * 28 * s;
                    if (ly > y + th - 2) break;
                    g.strokeLine(x + 1, ly, x + tw - 1, ly);
                }
            }
            if (28 * s >= 4) {
                g.setFill(text.deriveColor(0, 1, 1, 0.22));
                for (int i = 0; i < t.columns.size() && i < 60; i++) {
                    double ry = headerBottom + i * 28 * s + 11 * s;
                    double len = Math.min(tw * 0.5, Math.max(6, t.columns.get(i).name.length() * 6.5 * s));
                    g.fillRoundRect(x + 12 * s, ry, len, Math.max(1.2, 6 * s), 2, 2);
                }
            }
        }
    }

    private static String clip(String s, double maxChars) {
        if (s == null) return "";
        int m = (int) Math.max(3, maxChars);
        return s.length() <= m ? s : s.substring(0, Math.max(1, m - 1)) + "…";
    }

    private static Color color(String web, Color def) {
        try {
            return web == null ? def : Color.web(web);
        } catch (Exception e) {
            return def;
        }
    }
}
