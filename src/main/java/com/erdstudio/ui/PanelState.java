package com.erdstudio.ui;

import com.erdstudio.editor.Settings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * Open/closed state and width of the two side panels, shared by all tabs.
 * Panels the window closed automatically (because it got narrow) reopen when there is room again;
 * panels the user closed stay closed.
 */
public final class PanelState {
    public static final double LEFT_MIN = 210, LEFT_MAX = 560, RIGHT_MIN = 280, RIGHT_MAX = 640;
    private static final double RIGHT_COLLAPSE_BELOW = 1180, LEFT_COLLAPSE_BELOW = 900, HYSTERESIS = 60;

    public final BooleanProperty leftOpen = new SimpleBooleanProperty(Boolean.parseBoolean(Settings.get("panel.leftOpen", "true")));
    public final BooleanProperty rightOpen = new SimpleBooleanProperty(Boolean.parseBoolean(Settings.get("panel.rightOpen", "true")));
    public final DoubleProperty leftWidth = new SimpleDoubleProperty(clamp(Settings.panelWidth("left", 264), LEFT_MIN, LEFT_MAX));
    public final DoubleProperty rightWidth = new SimpleDoubleProperty(clamp(Settings.panelWidth("right", 362), RIGHT_MIN, RIGHT_MAX));

    private boolean autoLeft, autoRight, changingAutomatically;

    public PanelState() {
        leftOpen.addListener((o, a, b) -> {
            if (!changingAutomatically) { autoLeft = false; Settings.put("panel.leftOpen", String.valueOf(b)); }
        });
        rightOpen.addListener((o, a, b) -> {
            if (!changingAutomatically) { autoRight = false; Settings.put("panel.rightOpen", String.valueOf(b)); }
        });
    }

    /** Called whenever the window width changes. */
    public void onWindowWidth(double w) {
        changingAutomatically = true;
        try {
            if (w < RIGHT_COLLAPSE_BELOW) {
                if (rightOpen.get()) { rightOpen.set(false); autoRight = true; }
            } else if (w > RIGHT_COLLAPSE_BELOW + HYSTERESIS && autoRight) {
                rightOpen.set(true);
                autoRight = false;
            }
            if (w < LEFT_COLLAPSE_BELOW) {
                if (leftOpen.get()) { leftOpen.set(false); autoLeft = true; }
            } else if (w > LEFT_COLLAPSE_BELOW + HYSTERESIS && autoLeft) {
                leftOpen.set(true);
                autoLeft = false;
            }
        } finally {
            changingAutomatically = false;
        }
    }

    public void saveWidths() {
        Settings.setPanelWidth("left", leftWidth.get());
        Settings.setPanelWidth("right", rightWidth.get());
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
