package dev.purefps.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Pont de connexion rapide aux serveurs depuis MashClient.exe :
 * Fonctionne aussi bien en lancement Direct qu'en pont PrismLauncher grâce au fichier éphémère
 * {@code config/.purefps_quick_server} consommé une seule fois à l'ouverture du menu principal.
 */
public final class QuickServerLaunchBridge {

    public static final String QUICK_SERVER_FILE_NAME = ".purefps_quick_server";
    private static final long EXPIRY_MS = 180_000L;
    private static boolean consumedThisSession = false;

    public static final class TargetServer {
        public final String name;
        public final String hostPort;

        public TargetServer(String name, String hostPort) {
            this.name = name;
            this.hostPort = hostPort;
        }
    }

    private QuickServerLaunchBridge() {
    }

    public static void resetForTest() {
        consumedThisSession = false;
    }

    public static TargetServer consumePendingServer(File mcDataDir, long nowMs) {
        if (consumedThisSession || mcDataDir == null) {
            return null;
        }
        consumedThisSession = true;

        File file = new File(new File(mcDataDir, "config"), QUICK_SERVER_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        long age = nowMs - file.lastModified();
        TargetServer parsed = null;
        if (age <= EXPIRY_MS) {
            parsed = readTargetServer(file);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return parsed;
    }

    public static TargetServer readTargetServer(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String hostLine = reader.readLine();
            String nameLine = reader.readLine();
            if (hostLine == null) {
                return null;
            }
            String host = hostLine.trim();
            if (host.isEmpty()) {
                return null;
            }
            String name = (nameLine != null && !nameLine.trim().isEmpty()) ? nameLine.trim() : host;
            return new TargetServer(name, host);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
