package com.erdstudio.canvas;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.Locale;

/** Orthogonal edge routing with rounded corners and crow's-foot markers, all as SVG path data. */
final class Routing {

    /** A point on a table's side; dir = +1 when the edge leaves to the right, -1 to the left. */
    record Anchor(double x, double y, int dir) {}

    enum End { MANY, ONE, ONE_REQUIRED, ONE_OPTIONAL }

    static final double STUB = 28;

    private Routing() {}

    /**
     * @param s source anchor side choice is done by the caller
     * @param laneOffset spreads parallel "same side" edges so they don't overlap
     */
    static List<Point2D> route(Anchor s, Anchor e, double laneOffset) {
        List<Point2D> pts = new ArrayList<>();
        pts.add(new Point2D(s.x(), s.y()));
        if (s.dir() != e.dir()) {
            // opposite sides facing each other
            double midX = (s.x() + e.x()) / 2 + laneOffset;
            if (Math.abs(s.y() - e.y()) < 0.5) {
                pts.add(new Point2D(e.x(), e.y()));
                return pts;
            }
            pts.add(new Point2D(midX, s.y()));
            pts.add(new Point2D(midX, e.y()));
        } else {
            // both on the same side: loop around
            double x = s.dir() > 0 ? Math.max(s.x(), e.x()) + STUB + laneOffset : Math.min(s.x(), e.x()) - STUB - laneOffset;
            pts.add(new Point2D(x, s.y()));
            pts.add(new Point2D(x, e.y()));
        }
        pts.add(new Point2D(e.x(), e.y()));
        return pts;
    }

    /**
     * Like {@link #route} but tries to keep lines out of tables: first a 3-segment route with the
     * vertical part in a free gap, then a 5-segment route through a free horizontal corridor.
     */
    static List<Point2D> routeAvoiding(Anchor s, Anchor e, double lane, List<Rectangle2D> obstacles) {
        if (s.dir() == e.dir() || obstacles.isEmpty()) return route(s, e, lane);
        // normalise so that 'a' exits to the right and 'b' exits to the left (a.x < b.x)
        boolean flipped = s.dir() < 0;
        Anchor a = flipped ? e : s, b = flipped ? s : e;
        double ax = a.x(), bx = b.x();
        if (bx - ax < STUB) return route(s, e, lane);
        double minY = Math.min(a.y(), b.y()), maxY = Math.max(a.y(), b.y());

        List<Rectangle2D> near = new ArrayList<>();
        for (Rectangle2D r : obstacles) {
            if (r.getMaxX() > ax + 1 && r.getMinX() < bx - 1) near.add(r);
        }
        double mid = (ax + bx) / 2 + lane;
        if (near.isEmpty()) return finish(simple(a, b, mid), flipped);

        // candidate x positions for vertical segments
        TreeSet<Double> xs = new TreeSet<>();
        xs.add(mid);
        xs.add(ax + STUB * 0.8 + lane);
        xs.add(bx - STUB * 0.8 + lane);
        for (Rectangle2D r : near) {
            xs.add(r.getMinX() - 22 + lane);
            xs.add(r.getMaxX() + 22 + lane);
        }
        List<Double> xList = new ArrayList<>();
        for (double x : xs) if (x > ax + 10 && x < bx - 10) xList.add(x);
        xList.sort(Comparator.comparingDouble(x -> Math.abs(x - mid)));
        if (xList.size() > 10) xList = xList.subList(0, 10);

        List<Point2D> best = null;
        double bestCost = Double.MAX_VALUE;
        for (double x : xList) {
            List<Point2D> p = simple(a, b, x);
            double c = cost(p, near);
            if (c < bestCost) { bestCost = c; best = p; }
        }
        if (bestCost < 10000 || xList.size() < 2) return finish(best == null ? simple(a, b, mid) : best, flipped);

        // 5-segment: a -> (x1, ay) -> (x1, y) -> (x2, y) -> (x2, by) -> b
        TreeSet<Double> ys = new TreeSet<>();
        ys.add(a.y());
        ys.add(b.y());
        for (Rectangle2D r : near) {
            ys.add(r.getMinY() - 20);
            ys.add(r.getMaxY() + 20);
        }
        double cy = (minY + maxY) / 2;
        List<Double> yList = new ArrayList<>(ys);
        yList.sort(Comparator.comparingDouble(y -> Math.abs(y - cy)));
        if (yList.size() > 14) yList = yList.subList(0, 14);
        List<Double> xSorted = new ArrayList<>(xList);
        xSorted.sort(Comparator.naturalOrder());
        for (int i = 0; i < xSorted.size(); i++) {
            for (int j = i; j < xSorted.size(); j++) {
                double x1 = xSorted.get(i), x2 = xSorted.get(j);
                for (double y : yList) {
                    List<Point2D> p = new ArrayList<>(6);
                    p.add(new Point2D(ax, a.y()));
                    p.add(new Point2D(x1, a.y()));
                    p.add(new Point2D(x1, y));
                    p.add(new Point2D(x2, y));
                    p.add(new Point2D(x2, b.y()));
                    p.add(new Point2D(bx, b.y()));
                    double c = cost(p, near) + 40;
                    if (c < bestCost) { bestCost = c; best = p; }
                }
            }
        }
        return finish(best, flipped);
    }

    private static List<Point2D> simple(Anchor a, Anchor b, double x) {
        List<Point2D> p = new ArrayList<>(4);
        p.add(new Point2D(a.x(), a.y()));
        if (Math.abs(a.y() - b.y()) < 0.5) {
            p.add(new Point2D(b.x(), b.y()));
            return p;
        }
        p.add(new Point2D(x, a.y()));
        p.add(new Point2D(x, b.y()));
        p.add(new Point2D(b.x(), b.y()));
        return p;
    }

    private static List<Point2D> finish(List<Point2D> p, boolean flipped) {
        List<Point2D> out = new ArrayList<>(p);
        // drop zero-length segments
        for (int i = out.size() - 2; i > 0; i--) {
            if (out.get(i).distance(out.get(i + 1)) < 0.5) out.remove(i);
        }
        if (flipped) java.util.Collections.reverse(out);
        return out;
    }

    /** 10000 per table crossed + path length + a little per bend. */
    private static double cost(List<Point2D> p, List<Rectangle2D> obstacles) {
        double c = 0;
        for (int i = 0; i < p.size() - 1; i++) {
            Point2D u = p.get(i), v = p.get(i + 1);
            c += u.distance(v);
            double x1 = Math.min(u.getX(), v.getX()), x2 = Math.max(u.getX(), v.getX());
            double y1 = Math.min(u.getY(), v.getY()), y2 = Math.max(u.getY(), v.getY());
            for (Rectangle2D r : obstacles) {
                if (x2 > r.getMinX() + 2 && x1 < r.getMaxX() - 2 && y2 > r.getMinY() + 2 && y1 < r.getMaxY() - 2) c += 10000;
            }
        }
        return c + (p.size() - 2) * 12;
    }

    static String path(List<Point2D> pts, double radius) {
        StringBuilder sb = new StringBuilder();
        Point2D p0 = pts.get(0);
        sb.append('M').append(f(p0.getX())).append(' ').append(f(p0.getY()));
        for (int i = 1; i < pts.size() - 1; i++) {
            Point2D a = pts.get(i - 1), p = pts.get(i), b = pts.get(i + 1);
            double r = Math.min(radius, Math.min(a.distance(p), p.distance(b)) / 2);
            if (r < 0.5) {
                sb.append(" L").append(f(p.getX())).append(' ').append(f(p.getY()));
                continue;
            }
            Point2D before = toward(p, a, r), after = toward(p, b, r);
            sb.append(" L").append(f(before.getX())).append(' ').append(f(before.getY()));
            sb.append(" Q").append(f(p.getX())).append(' ').append(f(p.getY())).append(' ')
                    .append(f(after.getX())).append(' ').append(f(after.getY()));
        }
        Point2D last = pts.get(pts.size() - 1);
        sb.append(" L").append(f(last.getX())).append(' ').append(f(last.getY()));
        return sb.toString();
    }

    private static Point2D toward(Point2D from, Point2D to, double dist) {
        double d = from.distance(to);
        if (d == 0) return from;
        return new Point2D(from.getX() + (to.getX() - from.getX()) * dist / d, from.getY() + (to.getY() - from.getY()) * dist / d);
    }

    /** Line parts of a marker (no fill). */
    static String markerLines(Anchor a, End end) {
        double x = a.x(), y = a.y(), d = a.dir();
        StringBuilder sb = new StringBuilder();
        switch (end) {
            case MANY -> {
                double tip = x + d * 14;
                sb.append(moveLine(tip, y, x, y - 7)).append(moveLine(tip, y, x, y + 7)).append(moveLine(tip, y, x, y));
            }
            case ONE -> sb.append(bar(x + d * 10, y));
            case ONE_REQUIRED -> sb.append(bar(x + d * 9, y)).append(bar(x + d * 14, y));
            case ONE_OPTIONAL -> sb.append(bar(x + d * 9, y));
        }
        return sb.toString().trim();
    }

    /** Circle parts of a marker (filled with the canvas colour so the line doesn't show through). */
    static String markerCircles(Anchor a, End end) {
        if (end != End.ONE_OPTIONAL) return "";
        double cx = a.x() + a.dir() * 18, cy = a.y(), r = 4;
        return "M" + f(cx - r) + " " + f(cy) + " a" + f(r) + " " + f(r) + " 0 1 0 " + f(2 * r) + " 0 a" + f(r) + " " + f(r)
                + " 0 1 0 " + f(-2 * r) + " 0z";
    }

    private static String bar(double x, double y) {
        return moveLine(x, y - 6, x, y + 6);
    }

    private static String moveLine(double x1, double y1, double x2, double y2) {
        return "M" + f(x1) + " " + f(y1) + " L" + f(x2) + " " + f(y2) + " ";
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
