package com.erdstudio.model;

public final class Palette {
    private Palette() {}

    public static final String DEFAULT_TABLE = "#6366f1";

    public static final String[] TABLE_COLORS = {
            "#6366f1", "#3b82f6", "#0ea5e9", "#14b8a6", "#22c55e", "#84cc16",
            "#eab308", "#f97316", "#ef4444", "#ec4899", "#a855f7", "#64748b"
    };

    public static final String[] NOTE_COLORS = {
            "#fef08a", "#bbf7d0", "#bfdbfe", "#fbcfe8", "#fed7aa", "#e9d5ff"
    };

    public static final String[] AREA_COLORS = {
            "#6366f1", "#0ea5e9", "#14b8a6", "#22c55e", "#eab308", "#f97316", "#ef4444", "#ec4899", "#a855f7", "#64748b"
    };

    public static String tableColor(int i) {
        return TABLE_COLORS[Math.floorMod(i, TABLE_COLORS.length)];
    }
}
