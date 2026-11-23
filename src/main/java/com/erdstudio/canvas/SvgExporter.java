package com.erdstudio.canvas;

import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Note;
import com.erdstudio.model.Relation;
import com.erdstudio.model.Table;
import com.erdstudio.ui.Icons;
import com.erdstudio.ui.Theme;
import javafx.geometry.Bounds;

import java.util.HashSet;
import java.util.Set;

import static com.erdstudio.canvas.Routing.f;

/** Vector export that mirrors the on-screen look (light or dark, whichever is active). */
final class SvgExporter {
    private SvgExporter() {}

    static String export(DiagramCanvas canvas, Diagram d) {
        Bounds b = canvas.contentBounds();
        if (b == null) return null;
        boolean dark = Theme.isDark();
        String bg = dark ? "#0f1115" : "#f6f7f9";
        String card = dark ? "#1a1d24" : "#ffffff";
        String border = dark ? "#2c313c" : "#e3e6eb";
        String text = dark ? "#e6e8ee" : "#111827";
        String muted = dark ? "#8b93a7" : "#8a94a6";
        String edge = dark ? "#5b6476" : "#9aa5b8";
        String rowLine = dark ? "#23272f" : "#f1f3f6";

        double pad = 40;
        double x0 = b.getMinX() - pad, y0 = b.getMinY() - pad, w = b.getWidth() + 2 * pad, h = b.getHeight() + 2 * pad;
        StringBuilder s = new StringBuilder();
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(f(w)).append("\" height=\"").append(f(h))
                .append("\" viewBox=\"").append(f(x0)).append(' ').append(f(y0)).append(' ').append(f(w)).append(' ').append(f(h))
                .append("\" font-family=\"Segoe UI, Inter, Helvetica, Arial, sans-serif\">\n");
        s.append("<defs><filter id=\"sh\" x=\"-10%\" y=\"-10%\" width=\"120%\" height=\"130%\">")
                .append("<feDropShadow dx=\"0\" dy=\"3\" stdDeviation=\"5\" flood-color=\"#0f172a\" flood-opacity=\"")
                .append(dark ? "0.45" : "0.08").append("\"/></filter></defs>\n");
        s.append("<rect x=\"").append(f(x0)).append("\" y=\"").append(f(y0)).append("\" width=\"").append(f(w))
                .append("\" height=\"").append(f(h)).append("\" fill=\"").append(bg).append("\"/>\n");

        for (AreaNode an : canvas.areaNodeList()) {
            com.erdstudio.model.Area a = an.area();
            javafx.scene.paint.Color ac = javafx.scene.paint.Color.web(a.color);
            s.append("<g><rect x=\"").append(f(a.x)).append("\" y=\"").append(f(a.y)).append("\" width=\"").append(f(a.width))
                    .append("\" height=\"").append(f(a.height)).append("\" rx=\"14\" fill=\"").append(AreaNode.rgba(ac, 0.06))
                    .append("\" stroke=\"").append(AreaNode.rgba(ac, 0.55)).append("\" stroke-width=\"1.5\"/>");
            double tw = Math.min(a.width - 20, 18 + a.name.length() * 7.4);
            s.append("<rect x=\"").append(f(a.x + 10)).append("\" y=\"").append(f(a.y + 10)).append("\" width=\"").append(f(tw))
                    .append("\" height=\"24\" rx=\"7\" fill=\"").append(AreaNode.rgba(ac, 0.16)).append("\"/>");
            s.append("<text x=\"").append(f(a.x + 20)).append("\" y=\"").append(f(a.y + 26.5)).append("\" font-size=\"12.5\" font-weight=\"700\" fill=\"")
                    .append(a.color).append("\">").append(esc(a.name)).append("</text></g>\n");
        }
        for (NoteNode n : canvas.noteNodeList()) {
            Note note = n.note();
            s.append("<g><rect x=\"").append(f(note.x)).append("\" y=\"").append(f(note.y)).append("\" width=\"").append(f(note.width))
                    .append("\" height=\"").append(f(note.height)).append("\" rx=\"8\" fill=\"").append(note.color)
                    .append("\" filter=\"url(#sh)\"/>");
            double ty = note.y + 26;
            for (String line : note.text.split("\\R")) {
                if (ty > note.y + note.height - 8) break;
                s.append("<text x=\"").append(f(note.x + 14)).append("\" y=\"").append(f(ty))
                        .append("\" font-size=\"13\" fill=\"#3f3a1d\">").append(esc(line)).append("</text>");
                ty += 18;
            }
            s.append("</g>\n");
        }

        for (RelationEdge e : canvas.edgeList()) {
            if (!e.isVisible()) continue;
            s.append("<path d=\"").append(e.lineData()).append("\" fill=\"none\" stroke=\"").append(edge)
                    .append("\" stroke-width=\"1.6\"/>");
            s.append("<path d=\"").append(e.markerData()).append("\" fill=\"none\" stroke=\"").append(edge)
                    .append("\" stroke-width=\"1.6\" stroke-linecap=\"round\"/>");
            if (!e.circleData().isEmpty()) {
                s.append("<path d=\"").append(e.circleData()).append("\" fill=\"").append(bg).append("\" stroke=\"")
                        .append(edge).append("\" stroke-width=\"1.6\"/>");
            }
            s.append('\n');
        }

        Set<String> fk = new HashSet<>();
        for (Relation r : d.relations) fk.add(r.fromColumnId);
        for (TableNode n : canvas.tableNodeList()) {
            Table t = n.table();
            double tw = n.nodeWidth(), th = n.nodeHeight();
            s.append("<g>");
            s.append("<rect x=\"").append(f(t.x)).append("\" y=\"").append(f(t.y)).append("\" width=\"").append(f(tw))
                    .append("\" height=\"").append(f(th)).append("\" rx=\"10\" fill=\"").append(card).append("\" stroke=\"")
                    .append(border).append("\" filter=\"url(#sh)\"/>");
            String clipId = "c" + t.id;
            s.append("<clipPath id=\"").append(clipId).append("\"><rect x=\"").append(f(t.x)).append("\" y=\"").append(f(t.y))
                    .append("\" width=\"").append(f(tw)).append("\" height=\"").append(f(th)).append("\" rx=\"10\"/></clipPath>");
            s.append("<rect x=\"").append(f(t.x)).append("\" y=\"").append(f(t.y)).append("\" width=\"").append(f(tw))
                    .append("\" height=\"").append(f(TableNode.STRIPE)).append("\" fill=\"").append(t.color)
                    .append("\" clip-path=\"url(#").append(clipId).append(")\"/>");
            s.append("<text x=\"").append(f(t.x + 14)).append("\" y=\"").append(f(t.y + TableNode.STRIPE + 24))
                    .append("\" font-size=\"13.5\" font-weight=\"700\" fill=\"").append(text).append("\">")
                    .append(esc(t.name)).append("</text>");
            double headerBottom = t.y + TableNode.STRIPE + TableNode.HEADER;
            s.append("<line x1=\"").append(f(t.x)).append("\" y1=\"").append(f(headerBottom)).append("\" x2=\"")
                    .append(f(t.x + tw)).append("\" y2=\"").append(f(headerBottom)).append("\" stroke=\"").append(border).append("\"/>");
            for (int i = 0; i < t.columns.size(); i++) {
                Column c = t.columns.get(i);
                double cy = n.rowCenterY(i) + 4.5;
                if (i > 0) {
                    double ly = headerBottom + i * TableNode.ROW;
                    s.append("<line x1=\"").append(f(t.x + 1)).append("\" y1=\"").append(f(ly)).append("\" x2=\"")
                            .append(f(t.x + tw - 1)).append("\" y2=\"").append(f(ly)).append("\" stroke=\"").append(rowLine).append("\"/>");
                }
                String icon = c.primaryKey ? Icons.KEY : fk.contains(c.id) ? Icons.LINK : null;
                if (icon != null) {
                    double iy = n.rowCenterY(i) - 6;
                    s.append("<path d=\"").append(icon).append("\" transform=\"translate(").append(f(t.x + 12)).append(' ')
                            .append(f(iy)).append(") scale(0.5)\" fill=\"none\" stroke-width=\"2.6\" stroke-linecap=\"round\"")
                            .append(" stroke-linejoin=\"round\" stroke=\"").append(c.primaryKey ? "#f59e0b" : "#6366f1").append("\"/>");
                }
                s.append("<text x=\"").append(f(t.x + 32)).append("\" y=\"").append(f(cy)).append("\" font-size=\"12.5\" fill=\"")
                        .append(text).append("\"").append(c.primaryKey ? " font-weight=\"600\"" : "").append(">")
                        .append(esc(c.name)).append("</text>");
                String type = (c.type == null ? "" : c.type.toLowerCase()) + (c.isRequired() ? "" : "?");
                s.append("<text x=\"").append(f(t.x + tw - 14)).append("\" y=\"").append(f(cy))
                        .append("\" text-anchor=\"end\" font-size=\"11.5\" font-family=\"JetBrains Mono, Consolas, monospace\" fill=\"")
                        .append(muted).append("\">").append(esc(type)).append("</text>");
            }
            s.append("</g>\n");
        }
        s.append("</svg>\n");
        return s.toString();
    }

    private static String esc(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
