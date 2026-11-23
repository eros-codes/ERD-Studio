package com.erdstudio.canvas;

import com.erdstudio.model.Note;
import com.erdstudio.model.Table;
import com.erdstudio.ui.Theme;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

/** Overview of the whole diagram; click or drag to move the viewport. */
public final class Minimap extends StackPane {
    private static final double W = 200, H = 130, PAD = 8;

    private final DiagramCanvas canvas;
    private final Canvas surface = new Canvas(W, H);
    private boolean scheduled;
    private double sc = 1, ox, oy;

    public Minimap(DiagramCanvas canvas) {
        this.canvas = canvas;
        getStyleClass().add("minimap");
        setMinSize(W, H);
        setMaxSize(W, H);
        getChildren().add(surface);
        canvas.addViewListener(this::schedule);
        Theme.darkProperty().addListener(o -> schedule());
        surface.addEventHandler(MouseEvent.MOUSE_PRESSED, this::jump);
        surface.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::jump);
        visibleProperty().addListener(o -> schedule());
    }

    private void jump(MouseEvent e) {
        double wx = (e.getX() - ox) / sc, wy = (e.getY() - oy) / sc;
        canvas.centerOnWorld(wx, wy, false);
        e.consume();
    }

    private void schedule() {
        if (scheduled || !isVisible()) return;
        scheduled = true;
        Platform.runLater(() -> {
            scheduled = false;
            draw();
        });
    }

    private void draw() {
        GraphicsContext g = surface.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);
        g.setFill(Theme.minimapBackground());
        g.fillRoundRect(0, 0, W, H, 14, 14);

        Rectangle2D view = canvas.viewportWorld();
        Bounds content = canvas.contentBounds();
        double minX = view.getMinX(), minY = view.getMinY(), maxX = view.getMaxX(), maxY = view.getMaxY();
        if (content != null) {
            minX = Math.min(minX, content.getMinX());
            minY = Math.min(minY, content.getMinY());
            maxX = Math.max(maxX, content.getMaxX());
            maxY = Math.max(maxY, content.getMaxY());
        }
        double w = Math.max(1, maxX - minX), h = Math.max(1, maxY - minY);
        sc = Math.min((W - 2 * PAD) / w, (H - 2 * PAD) / h);
        ox = PAD + ((W - 2 * PAD) - w * sc) / 2 - minX * sc;
        oy = PAD + ((H - 2 * PAD) - h * sc) / 2 - minY * sc;

        for (AreaNode n : canvas.areaNodeList()) {
            com.erdstudio.model.Area a = n.area();
            Color c = Color.web(a.color);
            g.setFill(c.deriveColor(0, 1, 1, 0.10));
            g.fillRoundRect(ox + a.x * sc, oy + a.y * sc, a.width * sc, a.height * sc, 5, 5);
            g.setStroke(c.deriveColor(0, 1, 1, 0.6));
            g.setLineWidth(1);
            g.strokeRoundRect(ox + a.x * sc, oy + a.y * sc, a.width * sc, a.height * sc, 5, 5);
        }
        for (NoteNode n : canvas.noteNodeList()) {
            Note note = n.note();
            g.setFill(Color.web(note.color, 0.9));
            g.fillRect(ox + note.x * sc, oy + note.y * sc, Math.max(2, note.width * sc), Math.max(2, note.height * sc));
        }
        for (TableNode n : canvas.tableNodeList()) {
            Table t = n.table();
            double x = ox + t.x * sc, y = oy + t.y * sc;
            double tw = Math.max(3, n.nodeWidth() * sc), th = Math.max(3, n.nodeHeight() * sc);
            Color c = Color.web(t.color);
            g.setFill(c.deriveColor(0, 1, 1, Theme.isDark() ? 0.45 : 0.28));
            g.fillRoundRect(x, y, tw, th, 3, 3);
            g.setFill(c);
            g.fillRect(x, y, tw, Math.max(1.5, 4 * sc + 1));
        }
        Color vc = Theme.minimapViewport();
        double vx = ox + view.getMinX() * sc, vy = oy + view.getMinY() * sc;
        g.setFill(vc.deriveColor(0, 1, 1, 0.08));
        g.fillRoundRect(vx, vy, view.getWidth() * sc, view.getHeight() * sc, 4, 4);
        g.setStroke(vc);
        g.setLineWidth(1.5);
        g.strokeRoundRect(vx, vy, view.getWidth() * sc, view.getHeight() * sc, 4, 4);
    }
}
