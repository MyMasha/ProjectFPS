package dev.purefps.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Pont de connexion rapide aux serveurs depuis le launcher autonome MashClient :
 * Consomme le fichier éphémère {@code config/.purefps_quick_server} une seule fois à l'ouverture du menu principal.
 */
public final class QuickServerLaunchBridge {

    public static final String QUICK_SERVER_FILE_NAME = ".purefps_quick_server";
    private static final long EXPIRY_MS = 180_000L;
    private static final long MAX_FUTURE_SKEW_MS = 5_000L;
    private static final long MAX_BRIDGE_FILE_BYTES = 4096L;
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

    public static boolean isValidServerHostPort(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 255 || s.startsWith("-") || s.startsWith(".") || s.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7F || c == '/' || c == '\\') {
                return false;
            }
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
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
        if (age >= -MAX_FUTURE_SKEW_MS && age <= EXPIRY_MS && file.length() <= MAX_BRIDGE_FILE_BYTES) {
            parsed = readTargetServer(file);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return parsed;
    }

    public static TargetServer readTargetServer(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > MAX_BRIDGE_FILE_BYTES) {
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
            if (!isValidServerHostPort(host)) {
                return null;
            }
            String cleanName = nameLine != null ? nameLine.replaceAll("[\\p{Cntrl}]", "").trim() : "";
            if (cleanName.length() > 64) {
                cleanName = cleanName.substring(0, 64);
            }
            String name = !cleanName.isEmpty() ? cleanName : host;
            return new TargetServer(name, host);
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }
}
