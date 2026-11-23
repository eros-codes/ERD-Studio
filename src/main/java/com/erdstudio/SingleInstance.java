package com.erdstudio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps one ERD Studio window per user: double-clicking an .erd file while the app is already
 * running hands the file to the running instance (over a loopback socket protected by a random
 * token) instead of starting a second copy. Any failure simply falls back to a normal start.
 */
public final class SingleInstance {
    private SingleInstance() {}

    private static ServerSocket server;
    private static volatile Consumer<List<String>> handler;
    private static final List<List<String>> pending = new ArrayList<>();

    static Path portFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData, "ERD Studio")
                : Path.of(System.getProperty("user.home"), ".erd-studio");
        return base.resolve(AppInfo.isInstalled() ? "instance.port" : "instance-dev.port");
    }

    /** Returns true when a running instance accepted the arguments (this process should exit). */
    public static boolean handOff(String[] args) {
        Path f = portFile();
        try {
            if (!Files.exists(f)) return false;
            String[] parts = Files.readString(f).trim().split("\\s+");
            if (parts.length < 2) return false;
            int port = Integer.parseInt(parts[0]);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
                s.setSoTimeout(2500);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out.println(parts[1]);
                for (String a : args) out.println(Path.of(a).toAbsolutePath());
                out.println("::end");
                return "OK".equals(in.readLine());
            }
        } catch (Exception e) {
            return false; // stale file or nothing listening: start normally
        }
    }

    /** Starts listening; files received before the window exists are queued. */
    public static synchronized void listen() {
        if (server != null) return;
        try {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            String token = HexFormat.of().formatHex(b);
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Path f = portFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, server.getLocalPort() + " " + token);
            Thread t = new Thread(() -> acceptLoop(token), "single-instance");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            server = null; // no single-instance support; harmless
        }
    }

    private static void acceptLoop(String token) {
        while (server != null && !server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(3000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                if (!token.equals(in.readLine())) continue;
                List<String> files = new ArrayList<>();
                for (String line; (line = in.readLine()) != null && !line.equals("::end"); ) {
                    if (files.size() < 64) files.add(line);
                }
                out.println("OK");
                deliver(files);
            } catch (IOException ignored) {
                // next client
            }
        }
    }

    private static synchronized void deliver(List<String> files) {
        if (handler == null) pending.add(files);
        else handler.accept(files);
    }

    /** The handler runs on the listener thread; it should hop to the FX thread itself. */
    public static synchronized void onFiles(Consumer<List<String>> h) {
        handler = h;
        for (List<String> p : pending) h.accept(p);
        pending.clear();
    }

    public static synchronized void stop() {
        try {
            if (server != null) server.close();
            Files.deleteIfExists(portFile());
        } catch (IOException ignored) {
            // nothing to clean up
        }
        server = null;
    }
}
