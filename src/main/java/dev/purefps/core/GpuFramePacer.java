package dev.purefps.core;

import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

import java.util.concurrent.locks.LockSupport;

/**
 * Régulateur matériel de file d'attente GPU sans attente active (1-Frame-In-Flight GLSync non-bloquant) :
 * - Empêche le thread de rendu OpenGL d'empiler 3 à 5 frames d'avance dans l'ordonnanceur Windows WDDM.
 * - N'appelle jamais glClientWaitSync avec un timeout > 0 (qui provoque un busy-spin CPU à 100 % dans
 *   nvoglv64.dll et gèle les streams Discord / DWM) : sonde en 0 ns puis cède proprement le cœur CPU
 *   par tranches LockSupport.parkNanos uniquement lorsque le GPU est réellement saturé.
 */
public final class GpuFramePacer {

    private static final long MAX_PARK_BUDGET_NS = 4_000_000L;
    private static final long SPIN_YIELD_BUDGET_NS = 120_000L;
    private static final long PARK_SLICE_NS = 150_000L;
    private static final long HIGH_FPS_BYPASS_NS = 3_500_000L; // > 285 FPS : pipeline libre

    // 0 = non testé, 1 = OpenGL 3.2 natif, 2 = GL_ARB_sync, -1 = indisponible
    private static int syncBackend = 0;
    private static GLSync oldestFrameFence = null;   // Frame N-2
    private static GLSync previousFrameFence = null; // Frame N-1
    private static long lastPresentNs = 0L;

    private GpuFramePacer() {
    }

    private static int resolveBackend() {
        if (syncBackend != 0) {
            return syncBackend;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null) {
                return 0;
            }
            if (caps.OpenGL32) {
                syncBackend = 1;
            } else if (caps.GL_ARB_sync) {
                syncBackend = 2;
            } else {
                syncBackend = -1;
            }
        } catch (Throwable t) {
            syncBackend = -1;
        }
        return syncBackend;
    }

    /**
     * Appelé juste après Display.update() à la fin de chaque frame :
     * - Anneau 2-Frames-In-Flight (attend la clôture de N-2 pendant que N-1 s'exécute en parallèle sur le GPU)
     *   afin d'assurer 100 % de parallélisme CPU/GPU sous Vulkan (Zink) et OpenGL tout en empêchant l'accumulation
     *   de frames d'avance (zéro délai clavier/souris).
     */
    public static void onFramePresented() {
        if (!Display.isCreated()) {
            oldestFrameFence = null;
            previousFrameFence = null;
            lastPresentNs = 0L;
            return;
        }
        long now = System.nanoTime();
        long dt = lastPresentNs == 0L ? 0L : (now - lastPresentNs);
        lastPresentNs = now;

        boolean vulkanActive = VulkanBackendHelper.isVulkanActive();
        if (!vulkanActive && dt < HIGH_FPS_BYPASS_NS) {
            if (oldestFrameFence != null || previousFrameFence != null) {
                release();
            }
            return;
        }

        int backend = resolveBackend();
        if (backend <= 0) {
            return;
        }
        try {
            if (oldestFrameFence != null) {
                if (backend == 1) {
                    int status = GL32.glClientWaitSync(oldestFrameFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (status == GL32.GL_TIMEOUT_EXPIRED) {
                        long startWait = System.nanoTime();
                        long spinDeadline = startWait + SPIN_YIELD_BUDGET_NS;
                        long deadline = startWait + MAX_PARK_BUDGET_NS;
                        while (status == GL32.GL_TIMEOUT_EXPIRED) {
                            long t = System.nanoTime();
                            if (t >= deadline) {
                                break;
                            }
                            if (t < spinDeadline) {
                                Thread.yield();
                            } else {
                                LockSupport.parkNanos(PARK_SLICE_NS);
                            }
                            status = GL32.glClientWaitSync(oldestFrameFence, 0, 0L);
                        }
                    }
                    GL32.glDeleteSync(oldestFrameFence);
                } else {
                    int status = ARBSync.glClientWaitSync(oldestFrameFence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (status == ARBSync.GL_TIMEOUT_EXPIRED) {
                        long startWait = System.nanoTime();
                        long spinDeadline = startWait + SPIN_YIELD_BUDGET_NS;
                        long deadline = startWait + MAX_PARK_BUDGET_NS;
                        while (status == ARBSync.GL_TIMEOUT_EXPIRED) {
                            long t = System.nanoTime();
                            if (t >= deadline) {
                                break;
                            }
                            if (t < spinDeadline) {
                                Thread.yield();
                            } else {
                                LockSupport.parkNanos(PARK_SLICE_NS);
                            }
                            status = ARBSync.glClientWaitSync(oldestFrameFence, 0, 0L);
                        }
                    }
                    ARBSync.glDeleteSync(oldestFrameFence);
                }
                oldestFrameFence = null;
            }

            oldestFrameFence = previousFrameFence;
            if (backend == 1) {
                previousFrameFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            } else {
                previousFrameFence = ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        } catch (Throwable t) {
            oldestFrameFence = null;
            previousFrameFence = null;
            syncBackend = -1;
        }
    }

    public static void release() {
        try {
            if (oldestFrameFence != null) {
                if (syncBackend == 1) {
                    GL32.glDeleteSync(oldestFrameFence);
                } else if (syncBackend == 2) {
                    ARBSync.glDeleteSync(oldestFrameFence);
                }
            }
            if (previousFrameFence != null) {
                if (syncBackend == 1) {
                    GL32.glDeleteSync(previousFrameFence);
                } else if (syncBackend == 2) {
                    ARBSync.glDeleteSync(previousFrameFence);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            oldestFrameFence = null;
            previousFrameFence = null;
        }
    }
}
