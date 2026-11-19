package dev.purefps.core;

import org.lwjgl.opengl.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watchdog léger embarqué dans la JVM 1.8.9 (MashClient 4.5.13) :
 * - Surveille en arrière-plan le `Client thread` de Minecraft sans aucun impact FPS (simple écriture volatile).
 * - Pompe `Display.processMessages()` aux jalons de démarrage pour éviter les faux « Not Responding » Windows 10/11.
 * - Si le `Client thread` se fige (>= 8 s au démarrage ou >= 6 s en jeu), capture instantanément sa pile d'appels
 *   complète (classe, méthode, fichier, ligne exacte) + l'état de tous les threads JVM et l'écrit dans
 *   `config/.purefps_hang_dump.txt` afin que le Launcher affiche précisément où le blocage s'est produit.
 */
public final class ClientHangWatchdog {

    public static final String HANG_DUMP_FILE_NAME = ".purefps_hang_dump.txt";
    public static final String WATCHDOG_STDOUT_PREFIX = "[MashClient-Watchdog]";

    private static final long STARTUP_HANG_THRESHOLD_SECS = 8L;
    private static final long RUNTIME_HANG_THRESHOLD_SECS = 6L;
    private static final long POLL_INTERVAL_MS = 1000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile boolean shutdownRequested = false;
    private static volatile boolean inStartup = true;
    private static volatile String currentPhase = "boot:init";
    private static volatile long lastHeartbeatNanos = System.nanoTime();
    private static volatile Thread watchedClientThread = null;
    private static volatile File dumpFile = null;

    private ClientHangWatchdog() {
    }

    public static void start(File mcDataDir) {
        watchedClientThread = Thread.currentThread();
        lastHeartbeatNanos = System.nanoTime();
        inStartup = true;
        shutdownRequested = false;

        if (mcDataDir != null) {
            File cfg = new File(mcDataDir, "config");
            if (!cfg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cfg.mkdirs();
            }
            File target = new File(cfg, HANG_DUMP_FILE_NAME);
            dumpFile = target;
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }

        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread daemon = new Thread(new WatchdogLoop(), "MashClient-HangWatchdog");
        daemon.setDaemon(true);
        daemon.setPriority(Thread.NORM_PRIORITY);
        daemon.start();
    }

    public static void markPhase(String phase, boolean pumpDisplayMessages) {
        if (phase != null && !phase.isEmpty()) {
            currentPhase = phase;
        }
        lastHeartbeatNanos = System.nanoTime();
        if (pumpDisplayMessages && Thread.currentThread() == watchedClientThread) {
            try {
                if (Display.isCreated()) {
                    Display.processMessages();
                }
            } catch (Exception | LinkageError ignored) {
            }
        }
    }

    public static void markStartupComplete() {
        inStartup = false;
        currentPhase = "menu:active";
        lastHeartbeatNanos = System.nanoTime();
    }

    public static void heartbeat() {
        lastHeartbeatNanos = System.nanoTime();
    }

    public static void shutdown() {
        shutdownRequested = true;
        clearDumpFileQuietly();
    }

    /**
     * Identifie la frame la plus pertinente de la pile d'appels du `Client thread`
     * (privilégie les classes Minecraft/Forge/Mods/LWJGL plutôt que les frames internes JDK).
     */
    public static String findPinpointFrame(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return "Pile d'appels vide (thread en appel natif ou arrêté)";
        }
        for (StackTraceElement el : stack) {
            if (el == null) {
                continue;
            }
            String cls = el.getClassName();
            if (cls == null) {
                continue;
            }
            if (!cls.startsWith("java.")
                    && !cls.startsWith("javax.")
                    && !cls.startsWith("sun.")
                    && !cls.startsWith("com.sun.")
                    && !cls.startsWith("dev.purefps.core.ClientHangWatchdog")) {
                return el.toString();
            }
        }
        return stack[0].toString();
    }

    /**
     * Formate un rapport de blocage structuré, lisible directement par l'utilisateur et parsé
     * par `launcher/src/diagnostics.rs` (`parse_in_mod_hang_dump`).
     */
    public static String formatHangDump(
            String phase,
            long frozenSecs,
            Thread.State state,
            StackTraceElement[] clientStack,
            Map<Thread, StackTraceElement[]> allStacks,
            long usedMemMb,
            long maxMemMb
    ) {
        String safePhase = (phase == null || phase.isEmpty()) ? "unknown" : phase;
        String stateStr = state != null ? state.name() : "UNKNOWN";
        String pinpoint = findPinpointFrame(clientStack);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("Phase: ").append(safePhase).append('\n');
        sb.append("FrozenSeconds: ").append(frozenSecs).append('\n');
        sb.append("ThreadState: ").append(stateStr).append('\n');
        sb.append("PinpointLocation: ").append(pinpoint).append('\n');
        sb.append("Memory: Used ").append(usedMemMb).append(" MB / Max ").append(maxMemMb).append(" MB\n");
        sb.append("OS: ")
                .append(System.getProperty("os.name", "Windows"))
                .append(" ")
                .append(System.getProperty("os.version", ""))
                .append(" | Java ")
                .append(System.getProperty("java.version", "1.8"))
                .append('\n');
        sb.append("--- Client Thread Stack ---\n");
        if (clientStack != null && clientStack.length > 0) {
            int limit = Math.min(clientStack.length, 48);
            for (int i = 0; i < limit; i++) {
                sb.append("\tat ").append(clientStack[i]).append('\n');
            }
        } else {
            sb.append("\t(aucune frame Java disponible — blocage probable dans une DLL native / pilote GPU)\n");
        }

        if (allStacks != null && !allStacks.isEmpty()) {
            sb.append("--- Other Active Threads ---\n");
            int addedThreads = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
                Thread t = entry.getKey();
                if (t == null || t == watchedClientThread) {
                    continue;
                }
                String name = t.getName();
                if ("MashClient-HangWatchdog".equals(name)
                        || "Reference Handler".equals(name)
                        || "Finalizer".equals(name)
                        || "Signal Dispatcher".equals(name)
                        || "Attach Listener".equals(name)) {
                    continue;
                }
                StackTraceElement[] st = entry.getValue();
                if (st == null || st.length == 0) {
                    continue;
                }
                sb.append('"').append(name).append("\" [").append(t.getState()).append("]\n");
                int frameLimit = Math.min(st.length, 8);
                for (int i = 0; i < frameLimit; i++) {
                    sb.append("\tat ").append(st[i]).append('\n');
                }
                addedThreads++;
                if (addedThreads >= 10) {
                    break;
                }
            }
        }

        return sb.toString();
    }

    private static void writeDumpAtomically(File target, String content) {
        if (target == null || content == null) {
            return;
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static void clearDumpFileQuietly() {
        File target = dumpFile;
        if (target != null && target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static final class WatchdogLoop implements Runnable {
        @Override
        public void run() {
            long lastReportedBucket = -1L;
            boolean hadActiveDump = false;

            while (!shutdownRequested) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (shutdownRequested) {
                    return;
                }

                Thread client = watchedClientThread;
                if (client == null || !client.isAlive()) {
                    continue;
                }

                long elapsedSecs = Math.max(0L, (System.nanoTime() - lastHeartbeatNanos) / 1_000_000_000L);
                long threshold = inStartup ? STARTUP_HANG_THRESHOLD_SECS : RUNTIME_HANG_THRESHOLD_SECS;

                if (elapsedSecs < threshold) {
                    if (hadActiveDump) {
                        clearDumpFileQuietly();
                        hadActiveDump = false;
                        lastReportedBucket = -1L;
                    }
                    continue;
                }

                long bucket = elapsedSecs < 16L ? 1L : (elapsedSecs < 30L ? 2L : 3L);
                if (bucket == lastReportedBucket) {
                    continue;
                }
                lastReportedBucket = bucket;

                try {
                    StackTraceElement[] clientStack = client.getStackTrace();
                    Thread.State state = client.getState();
                    Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = Math.max(0L, (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
                    long maxMb = Math.max(1L, rt.maxMemory() / (1024L * 1024L));
                    String phase = currentPhase;
                    String pinpoint = findPinpointFrame(clientStack);

                    String formatted = formatHangDump(
                            phase,
                            elapsedSecs,
                            state,
                            clientStack,
                            allStacks,
                            usedMb,
                            maxMb
                    );
                    writeDumpAtomically(dumpFile, formatted);
                    hadActiveDump = true;

                    System.out.println(
                            WATCHDOG_STDOUT_PREFIX
                                    + " NOT_RESPONDING phase=" + phase
                                    + " frozen=" + elapsedSecs + "s"
                                    + " state=" + state
                                    + " location=" + pinpoint
                    );
                } catch (Exception | LinkageError ignored) {
                }
            }
        }
    }
}
