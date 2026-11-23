package com.erdstudio;

import javafx.application.Application;

/**
 * Plain main class (does not extend Application) so the shaded jar / jpackage
 * launcher can start JavaFX from the classpath.
 */
public final class Launcher {
    public static void main(String[] args) {
        for (String a : args) {
            if ("--mcp".equals(a)) { // MCP server for AI assistants (stdio); no window
                try {
                    com.erdstudio.mcp.McpServer.run();
                } catch (Exception e) {
                    System.err.println("ERD Studio MCP server stopped: " + e);
                }
                return;
            }
        }
        System.setProperty("prism.lcdtext", "false");
        // respect the system proxy / VPN settings for the update check
        System.setProperty("java.net.useSystemProxies", "true");
        // a second launch (e.g. double-clicking an .erd file) hands its files to the open window
        if (SingleInstance.handOff(args)) return;
        SingleInstance.listen();
        Application.launch(App.class, args);
    }
}
