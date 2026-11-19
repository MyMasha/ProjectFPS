package dev.purefps.core;

import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Régulateur léger de file GPU :
 * - En jeu normal (>= 62 FPS) ou sous Vulkan, laisse le pipeline 100 % libre sans jamais appeler
 *   LockSupport.parkNanos (qui dort 1 à 15.6 ms sous Windows et bride les FPS entre 60 et 285 FPS).
 * - Uniquement si le GPU est sévèrement saturé (< 62 FPS), sonde la fence N-2 sans flush bloquant
 *   et cède brièvement par Thread.yield() (<= 60 µs max).
 */
public final class GpuFramePacer {

    private static final long HIGH_FPS_BYPASS_NS = 16_000_000L; // >= ~62 FPS : pipeline libre

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
        } catch (Exception | LinkageError t) {
            syncBackend = -1;
        }
        return syncBackend;
    }

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
        if (vulkanActive || dt < HIGH_FPS_BYPASS_NS) {
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
                long deadline = System.nanoTime() + 60_000L;
                if (backend == 1) {
                    int status = GL32.glClientWaitSync(oldestFrameFence, 0, 0L);
                    while (status == GL32.GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline) {
                        Thread.yield();
                        status = GL32.glClientWaitSync(oldestFrameFence, 0, 0L);
                    }
                    GL32.glDeleteSync(oldestFrameFence);
                } else {
                    int status = ARBSync.glClientWaitSync(oldestFrameFence, 0, 0L);
                    while (status == ARBSync.GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline) {
                        Thread.yield();
                        status = ARBSync.glClientWaitSync(oldestFrameFence, 0, 0L);
                    }
                    ARBSync.glDeleteSync(oldestFrameFence);
                }
                oldestFrameFence = null;
            }

            oldestFrameFence = previousFrameFence;
            if (backend == 1) {
                previousFrameFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            } else {
                previousFrameFence = ARBSync.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        } catch (Exception | LinkageError t) {
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
        } catch (Exception | LinkageError ignored) {
        } finally {
            oldestFrameFence = null;
            previousFrameFence = null;
        }
    }
}
