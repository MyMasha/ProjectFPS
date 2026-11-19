package dev.purefps.core;

import net.minecraft.util.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pont de session éphémère MashClient 4.5.12 :
 * Lit le jeton d'accès Minecraft depuis un fichier temporaire privé (permissions restreintes)
 * désigné par la variable d'environnement {@code MASHCLIENT_SESSION_TOKEN_FILE}, puis écrase et
 * supprime immédiatement ce fichier afin d'éviter l'exposition du {@code --accessToken} dans la
 * ligne de commande JVM visible par d'autres processus locaux.
 */
public final class EphemeralSessionBridge {

    public static final String ENV_TOKEN_FILE = "MASHCLIENT_SESSION_TOKEN_FILE";
    public static final String PLACEHOLDER_TOKEN = "__MASHCLIENT_EPHEMERAL__";
    private static final int MAX_TOKEN_FILE_BYTES = 16 * 1024;

    private EphemeralSessionBridge() {
    }

    public static Session resolveSession(Session current) {
        if (current == null) {
            return null;
        }
        String tokenPath = System.getenv(ENV_TOKEN_FILE);
        if (tokenPath == null || tokenPath.trim().isEmpty()) {
            return current;
        }
        String realToken = readAndBurnTokenFile(new File(tokenPath.trim()));
        if (realToken == null || realToken.isEmpty()) {
            return current;
        }
        String sessionType = current.getSessionType() == Session.Type.LEGACY ? "legacy" : "mojang";
        return new Session(current.getUsername(), current.getPlayerID(), realToken, sessionType);
    }

    public static String readAndBurnTokenFile(File tokenFile) {
        if (tokenFile == null || !tokenFile.isFile()) {
            return null;
        }
        long len = tokenFile.length();
        if (len <= 0L || len > MAX_TOKEN_FILE_BYTES) {
            burnFile(tokenFile, len);
            return null;
        }
        try {
            byte[] raw = readAllBytesBounded(tokenFile, MAX_TOKEN_FILE_BYTES);
            String token = new String(raw, StandardCharsets.UTF_8).trim();
            Arrays.fill(raw, (byte) 0);
            return token.isEmpty() ? null : token;
        } catch (IOException ex) {
            return null;
        } finally {
            burnFile(tokenFile, len);
        }
    }

    private static byte[] readAllBytesBounded(File file, int maxBytes) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total + n > maxBytes) {
                    throw new IOException("Token file exceeds max allowed size");
                }
                baos.write(buf, 0, n);
                total += n;
            }
            Arrays.fill(buf, (byte) 0);
            return baos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void burnFile(File file, long knownLen) {
        if (file == null || !file.exists()) {
            return;
        }
        int overwriteLen = (int) Math.min(Math.max(knownLen, 64L), MAX_TOKEN_FILE_BYTES);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.write(new byte[overwriteLen]);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
