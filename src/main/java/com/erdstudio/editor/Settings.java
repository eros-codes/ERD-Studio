package com.erdstudio.editor;

import com.erdstudio.model.Dialect;

import java.util.prefs.Preferences;

/** User preferences (stored in the registry on Windows via java.util.prefs). */
public final class Settings {
    private static final Preferences P = Preferences.userRoot().node("com/erdstudio/settings");

    private Settings() {}

    public static Dialect defaultDialect() {
        try {
            return Dialect.valueOf(P.get("dialect", Dialect.POSTGRESQL.name()));
        } catch (IllegalArgumentException e) {
            return Dialect.POSTGRESQL;
        }
    }

    public static void setDefaultDialect(Dialect d) { P.put("dialect", d.name()); }

    /** Add created_at / updated_at to new tables. */
    public static boolean timestampColumns() { return P.getBoolean("timestamps", false); }

    public static void setTimestampColumns(boolean v) { P.putBoolean("timestamps", v); }

    /** Name for the id column of new tables. */
    public static String idColumnName() { return P.get("idName", "id"); }

    public static void setIdColumnName(String v) { P.put("idName", v == null || v.isBlank() ? "id" : v.trim()); }

    /** Pattern for new foreign key columns; {table} is replaced by the referenced table name. */
    public static String fkPattern() { return P.get("fkPattern", "{table}_id"); }

    public static void setFkPattern(String v) { P.put("fkPattern", v == null || !v.contains("{table}") ? "{table}_id" : v.trim()); }

    /** Seconds between crash-recovery backups (0 = off). */
    public static int autosaveSeconds() { return P.getInt("autosave", 20); }

    public static void setAutosaveSeconds(int v) { P.putInt("autosave", Math.max(0, v)); }

    public static boolean snapToGrid() { return P.getBoolean("snap", true); }

    public static void setSnapToGrid(boolean v) { P.putBoolean("snap", v); }

    public static boolean reopenLastSession() { return P.getBoolean("reopen", true); }

    public static void setReopenLastSession(boolean v) { P.putBoolean("reopen", v); }

    public static com.erdstudio.layout.AutoLayout.Style tidyStyle() {
        try {
            return com.erdstudio.layout.AutoLayout.Style.valueOf(P.get("tidyStyle", "LEFT_TO_RIGHT"));
        } catch (IllegalArgumentException e) {
            return com.erdstudio.layout.AutoLayout.Style.LEFT_TO_RIGHT;
        }
    }

    public static void setTidyStyle(com.erdstudio.layout.AutoLayout.Style s) { P.put("tidyStyle", s.name()); }

    /** How the plain mouse wheel behaves on the canvas. */
    public enum WheelMode { AUTO, ZOOM, PAN }

    public static WheelMode wheelMode() {
        try {
            return WheelMode.valueOf(P.get("wheel", "AUTO"));
        } catch (IllegalArgumentException e) {
            return WheelMode.AUTO;
        }
    }

    public static void setWheelMode(WheelMode m) { P.put("wheel", m.name()); }

    public static double panelWidth(String key, double def) { return P.getDouble("panel." + key, def); }

    public static void setPanelWidth(String key, double w) { P.putDouble("panel." + key, w); }

    /** Library projects are saved automatically a moment after every change. */
    public static boolean autosaveProjects() { return P.getBoolean("autosaveProjects", true); }

    public static void setAutosaveProjects(boolean v) { P.putBoolean("autosaveProjects", v); }

    /** Folder that holds the project library; empty = Documents\ERD Studio\Projects. */
    public static String libraryDir() { return P.get("libraryDir", ""); }

    public static void setLibraryDir(String v) { P.put("libraryDir", v == null ? "" : v.trim()); }

    /** Look for a new version on GitHub when the app starts. */
    public static boolean checkUpdates() { return P.getBoolean("checkUpdates", true); }

    public static void setCheckUpdates(boolean v) { P.putBoolean("checkUpdates", v); }

    public static String get(String key, String def) { return P.get(key, def); }

    public static void put(String key, String value) {
        if (value == null) P.remove(key);
        else P.put(key, value);
    }
}
