package com.erdstudio;

import javafx.application.Application;

/**
 * Plain main class (does not extend Application) so the shaded jar / jpackage
 * launcher can start JavaFX from the classpath.
 */
public final class Launcher {
    public static void main(String[] args) {
        System.setProperty("prism.lcdtext", "false");
        // respect the system proxy / VPN settings for the update check
        System.setProperty("java.net.useSystemProxies", "true");
        // a second launch (e.g. double-clicking an .erd file) hands its files to the open window
        if (SingleInstance.handOff(args)) return;
        SingleInstance.listen();
        Application.launch(App.class, args);
    }
}
