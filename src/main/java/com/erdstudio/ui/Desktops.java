package com.erdstudio.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Opens folders in the system file manager without ever blocking or crashing the UI. */
final class Desktops {
    private Desktops() {}

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    static void open(Path dir) {
        run(() -> {
            Files.createDirectories(dir);
            if (WINDOWS) new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
            else java.awt.Desktop.getDesktop().open(dir.toFile());
        });
    }

    /** Opens the containing folder with the file selected (Windows), or just the folder elsewhere. */
    static void reveal(Path file) {
        run(() -> {
            if (WINDOWS && Files.exists(file)) new ProcessBuilder("explorer.exe", "/select," + file.toAbsolutePath()).start();
            else {
                Path dir = file.toAbsolutePath().getParent();
                if (dir != null) java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        });
    }

    static void browse(String url) {
        run(() -> {
            if (WINDOWS) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        });
    }

    private interface Job { void run() throws Exception; }

    private static void run(Job job) {
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (Throwable ignored) {
                // no file manager available — nothing useful to do
            }
        }, "open-folder");
        t.setDaemon(true);
        t.start();
    }
}
