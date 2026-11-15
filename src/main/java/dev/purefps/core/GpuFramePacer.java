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

    private static final long MAX_PARK_BUDGET_NS = 6_000_000L;
    private static final long PARK_SLICE_NS = 350_000L;
    private static final long HIGH_FPS_BYPASS_NS = 4_000_000L; // > 250 FPS : zéro appel JNI/fence, pipeline libre 2000+ FPS

    // 0 = non testé, 1 = OpenGL 3.2 natif, 2 = GL_ARB_sync, -1 = indisponible
    private static int syncBackend = 0;
    private static GLSync previousFrameFence = null;
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
     * - En mode Vulkan (Zink gère nativement ses files VkSwapchain) ou au-delà de 250 FPS (< 4 ms/frame),
     *   court-circuite intégralement glFenceSync / glClientWaitSync / parkNanos pour libérer 2000+ FPS.
     * - N'intervient que lorsque le GPU OpenGL sature réellement (< 250 FPS) pour protéger DWM/Discord.
     */
    public static void onFramePresented() {
        if (!Display.isCreated()) {
            previousFrameFence = null;
            lastPresentNs = 0L;
            return;
        }
        long now = System.nanoTime();
        long dt = lastPresentNs == 0L ? 0L : (now - lastPresentNs);
        lastPresentNs = now;

        if (VulkanBackendHelper.isVulkanActive() || dt < HIGH_FPS_BYPASS_NS) {
            if (previousFrameFence != null) {
                release();
            }
            return;
        }

        int backend = resolveBackend();
        if (backend <= 0) {
            return;
        }
        try {
            if (previousFrameFence != null) {
                if (backend == 1) {
                    int status = GL32.glClientWaitSync(previousFrameFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (status == GL32.GL_TIMEOUT_EXPIRED) {
                        long deadline = System.nanoTime() + MAX_PARK_BUDGET_NS;
                        while (status == GL32.GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline) {
                            LockSupport.parkNanos(PARK_SLICE_NS);
                            status = GL32.glClientWaitSync(previousFrameFence, 0, 0L);
                        }
                    }
                    GL32.glDeleteSync(previousFrameFence);
                } else {
                    int status = ARBSync.glClientWaitSync(previousFrameFence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                    if (status == ARBSync.GL_TIMEOUT_EXPIRED) {
                        long deadline = System.nanoTime() + MAX_PARK_BUDGET_NS;
                        while (status == ARBSync.GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline) {
                            LockSupport.parkNanos(PARK_SLICE_NS);
                            status = ARBSync.glClientWaitSync(previousFrameFence, 0, 0L);
                        }
                    }
                    ARBSync.glDeleteSync(previousFrameFence);
                }
                previousFrameFence = null;
            }

            if (backend == 1) {
                previousFrameFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            } else {
                previousFrameFence = ARBSync.glFenceSync(ARBSync.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        } catch (Throwable t) {
            previousFrameFence = null;
            syncBackend = -1;
        }
    }

    public static void release() {
        if (previousFrameFence == null) {
            return;
        }
        try {
            if (syncBackend == 1) {
                GL32.glDeleteSync(previousFrameFence);
            } else if (syncBackend == 2) {
                ARBSync.glDeleteSync(previousFrameFence);
            }
        } catch (Throwable ignored) {
        } finally {
            previousFrameFence = null;
        }
    }
}
