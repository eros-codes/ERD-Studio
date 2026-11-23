package com.erdstudio.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;
import java.util.prefs.Preferences;

/** Light/dark theme switching, plus the few colours that canvases need in Java code. */
public final class Theme {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static final BooleanProperty DARK = new SimpleBooleanProperty(PREFS.getBoolean("dark", false));
    private static final String APP_CSS = css("app.css");
    private static final String LIGHT_CSS = css("theme-light.css");
    private static final String DARK_CSS = css("theme-dark.css");

    public static final String MONO = pickFont("JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Fira Code",
            "Consolas", "Menlo", "DejaVu Sans Mono", "Monospaced");

    private Theme() {}

    private static String css(String name) {
        var url = Theme.class.getResource("/com/erdstudio/" + name);
        return url == null ? null : url.toExternalForm();
    }

    private static String pickFont(String... families) {
        List<String> installed = Font.getFamilies();
        for (String f : families) if (installed.contains(f)) return f;
        return "Monospaced";
    }

    public static BooleanProperty darkProperty() { return DARK; }

    public static boolean isDark() { return DARK.get(); }

    public static void toggle() {
        DARK.set(!DARK.get());
        PREFS.putBoolean("dark", DARK.get());
    }

    public static void apply(Scene scene) {
        Runnable r = () -> scene.getStylesheets().setAll(isDark() ? DARK_CSS : LIGHT_CSS, APP_CSS);
        r.run();
        DARK.addListener((o, a, b) -> r.run());
    }

    public static void apply(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        pane.getStylesheets().setAll(isDark() ? DARK_CSS : LIGHT_CSS, APP_CSS);
        dialog.setResizable(true);
        dialog.showingProperty().addListener((o, a, b) -> { if (b) javafx.application.Platform.runLater(() -> fitToScreen(dialog)); });
    }

    /**
     * Keeps a dialog inside the visible screen area (small laptops, 150% scaling):
     * never larger than ~92% of the screen, re-centred on its owner, and scrollable
     * when its content cannot shrink any further.
     */
    public static void fitToScreen(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        if (pane.getScene() == null) return;
        javafx.stage.Window w = pane.getScene().getWindow();
        javafx.stage.Window owner = dialog.getOwner();
        double cx = owner != null ? owner.getX() + owner.getWidth() / 2 : w.getX() + w.getWidth() / 2;
        double cy = owner != null ? owner.getY() + owner.getHeight() / 2 : w.getY() + w.getHeight() / 2;
        var screens = javafx.stage.Screen.getScreensForRectangle(cx, cy, 1, 1);
        javafx.geometry.Rectangle2D vb = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.get(0)).getVisualBounds();
        double maxW = Math.floor(vb.getWidth() * 0.94), maxH = Math.floor(vb.getHeight() * 0.92);
        boolean changed = false;
        if (w.getHeight() > maxH && pane.getContent() != null
                && pane.minHeight(-1) > maxH - 10 && !(pane.getContent() instanceof javafx.scene.control.ScrollPane)) {
            javafx.scene.Node c = pane.getContent();
            javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(c);
            sp.setFitToWidth(true);
            sp.getStyleClass().add("dialog-scroll");
            pane.setContent(sp);
        }
        if (w.getWidth() > maxW) { w.setWidth(maxW); changed = true; }
        if (w.getHeight() > maxH) { w.setHeight(maxH); changed = true; }
        if (pane.getMinWidth() > maxW) pane.setMinWidth(maxW - 20);
        if (changed || w.getX() < vb.getMinX() || w.getY() < vb.getMinY()
                || w.getX() + w.getWidth() > vb.getMaxX() || w.getY() + w.getHeight() > vb.getMaxY()) {
            double x = cx - w.getWidth() / 2, y = cy - w.getHeight() / 2;
            w.setX(Math.max(vb.getMinX(), Math.min(x, vb.getMaxX() - w.getWidth())));
            w.setY(Math.max(vb.getMinY(), Math.min(y, vb.getMaxY() - w.getHeight())));
        }
    }

    // colours for canvas-drawn things (grid, minimap)
    public static Color canvasBackground() { return isDark() ? Color.web("#0f1115") : Color.web("#f6f7f9"); }

    public static Color gridDot() { return isDark() ? Color.web("#2a2f3a") : Color.web("#d3d7de"); }

    public static Color minimapBackground() { return isDark() ? Color.web("#171a21") : Color.web("#ffffff"); }

    public static Color minimapViewport() { return isDark() ? Color.web("#818cf8") : Color.web("#6366f1"); }

    public static Color noteOutline() { return isDark() ? Color.web("#3a4150") : Color.web("#e5e7eb"); }
}
