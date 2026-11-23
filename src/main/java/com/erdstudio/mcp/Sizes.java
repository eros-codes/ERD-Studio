package com.erdstudio.mcp;

import com.erdstudio.layout.AutoLayout;
import com.erdstudio.model.Column;
import com.erdstudio.model.Table;

/**
 * Table size estimate that needs no JavaFX toolkit (used when ERD Studio is closed).
 * Height matches the canvas exactly; width is a close character-based estimate.
 */
public final class Sizes {
    private Sizes() {}

    public static double height(Table t) {
        return 4 + 38 + Math.max(1, t.columns.size()) * 28 + 6;
    }

    public static double width(Table t) {
        double w = t.name.length() * 8.6 + 56;
        for (Column c : t.columns) {
            String type = c.type == null || c.type.isBlank() ? "?" : c.type;
            double row = 14 + 18 + c.name.length() * 7.4 + 24 + type.length() * 6.6 + 16;
            if (c.unique && !c.primaryKey) row += 26;
            if (c.autoIncrement) row += 24;
            w = Math.max(w, row);
        }
        return Math.ceil(Math.min(460, Math.max(210, w)));
    }

    public static AutoLayout.Size of(Table t) {
        return new AutoLayout.Size(width(t), height(t));
    }
}
