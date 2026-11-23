package com.erdstudio;

import java.io.InputStream;
import java.util.Properties;

/** Application identity, shared by the UI and the updater. */
public final class AppInfo {
    private AppInfo() {}

    public static final String NAME = "ERD Studio";
    /** GitHub repository that publishes releases (owner/name). */
    public static final String REPO = "eros-codes/ERD-Studio";
    private static final String FALLBACK_VERSION = "2.3.0";

    /** Version from the Maven build (pom.xml), so there is a single place to change it. */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/com/erdstudio/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty() && !v.contains("${")) return v;
            }
        } catch (Exception ignored) {
            // fall back below
        }
        return FALLBACK_VERSION;
    }

    /** True when running from the installed app (jpackage launcher), not from an IDE or a plain jar. */
    public static boolean isInstalled() {
        return System.getProperty("jpackage.app-path") != null;
    }
}
