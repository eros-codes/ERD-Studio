package com.erdstudio.canvas;

import com.erdstudio.model.Column;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import javafx.css.PseudoClass;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.shape.SVGPath;

import java.util.List;

/** One relation line: orthogonal path + crow's-foot markers, with a fat invisible path for easy clicking. */
public final class RelationEdge extends Group {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass HIGHLIGHT = PseudoClass.getPseudoClass("highlight");
    private static final PseudoClass DIMMED = PseudoClass.getPseudoClass("dimmed");

    private Relation relation;
    private final SVGPath hit = new SVGPath();
    private final SVGPath line = new SVGPath();
    private final SVGPath markers = new SVGPath();
    private final SVGPath circles = new SVGPath();

    RelationEdge(Relation r) {
        this.relation = r;
        getStyleClass().add("relation-edge");
        hit.getStyleClass().add("edge-hit");
        line.getStyleClass().add("edge-line");
        markers.getStyleClass().add("edge-marker");
        circles.getStyleClass().add("edge-circle");
        getChildren().addAll(hit, line, markers, circles);
    }

    public Relation relation() { return relation; }

    void bind(Relation r) { this.relation = r; }

    void update(TableNode from, TableNode to, double laneOffset, List<Rectangle2D> obstacles) {
        Table ft = from.table(), tt = to.table();
        int fi = ft.columns.indexOf(ft.column(relation.fromColumnId));
        int ti = tt.columns.indexOf(tt.column(relation.toColumnId));
        if (fi < 0 || ti < 0) { setVisible(false); return; }
        setVisible(true);

        double fx1 = ft.x, fx2 = ft.x + from.nodeWidth();
        double tx1 = tt.x, tx2 = tt.x + to.nodeWidth();
        double fy = from.rowCenterY(fi), ty = to.rowCenterY(ti);
        int fd, td;
        double gap = Routing.STUB * 2;
        if (ft.id.equals(tt.id)) {
            fd = 1; td = 1;
        } else if (fx2 + gap <= tx1) {
            fd = 1; td = -1;
        } else if (tx2 + gap <= fx1) {
            fd = -1; td = 1;
        } else {
            double right = Math.abs(fx2 - tx2), left = Math.abs(fx1 - tx1);
            fd = td = right <= left ? 1 : -1;
        }
        Routing.Anchor a = new Routing.Anchor(fd > 0 ? fx2 : fx1, fy, fd);
        Routing.Anchor b = new Routing.Anchor(td > 0 ? tx2 : tx1, ty, td);
        if (ft.id.equals(tt.id)) laneOffset += 8;

        List<Point2D> pts = Routing.routeAvoiding(a, b, laneOffset, obstacles);
        String d = Routing.path(pts, 10);
        line.setContent(d);
        hit.setContent(d);

        Column fc = ft.column(relation.fromColumnId);
        Routing.End fromEnd = relation.type == RelationType.ONE_TO_ONE ? Routing.End.ONE : Routing.End.MANY;
        Routing.End toEnd = fc != null && fc.isRequired() ? Routing.End.ONE_REQUIRED : Routing.End.ONE_OPTIONAL;
        markers.setContent((Routing.markerLines(a, fromEnd) + " " + Routing.markerLines(b, toEnd)).trim());
        String c = (Routing.markerCircles(a, fromEnd) + " " + Routing.markerCircles(b, toEnd)).trim();
        circles.setContent(c);
        circles.setVisible(!c.isEmpty());
    }

    void setSelected(boolean v) { pseudoClassStateChanged(SELECTED, v); }

    void setHighlighted(boolean v) { pseudoClassStateChanged(HIGHLIGHT, v); }

    void setDimmed(boolean v) { pseudoClassStateChanged(DIMMED, v); }

    String lineData() { return line.getContent(); }

    String markerData() { return markers.getContent(); }

    String circleData() { return circles.isVisible() ? circles.getContent() : ""; }
}
