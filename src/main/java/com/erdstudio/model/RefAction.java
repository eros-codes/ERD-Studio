package com.erdstudio.model;

public enum RefAction {
    NO_ACTION("NO ACTION"),
    CASCADE("CASCADE"),
    SET_NULL("SET NULL"),
    SET_DEFAULT("SET DEFAULT"),
    RESTRICT("RESTRICT");

    public final String sql;

    RefAction(String sql) {
        this.sql = sql;
    }

    public static RefAction parse(String text) {
        if (text == null) return NO_ACTION;
        String t = text.trim().toUpperCase().replaceAll("\\s+", " ");
        for (RefAction a : values()) if (a.sql.equals(t)) return a;
        return NO_ACTION;
    }

    @Override
    public String toString() {
        return sql;
    }
}
