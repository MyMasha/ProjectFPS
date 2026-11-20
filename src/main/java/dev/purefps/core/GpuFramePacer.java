package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Régulateur optionnel (opt-in via PureFPSConfig.gpuFramePacer) de file GPU :
 * - Désactivé par défaut afin de laisser le pipeline 100 % libre (500+ FPS OpenGL / 600+ FPS Vulkan).
 * - Lorsqu'activé, exige 8 frames consécutives < 60 FPS (hystérésis anti-oscillation) avant de sonder
 *   la fence N-2 avec GL_SYNC_FLUSH_COMMANDS_BIT.
 */
public final class GpuFramePacer {

    private static final long SLOW_FRAME_THRESHOLD_NS = 16_666_667L; // < 60 FPS
    private static final int HYSTERESIS_FRAMES = 8;

    private static int syncBackend = 0;
    private static GLSync oldestFrameFence = null;
    private static GLSync previousFrameFence = null;
    private static long lastPresentNs = 0L;
    private static int slowConsecutiveFrames = 0;

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
        if (!PureFPSConfig.gpuFramePacer || !BorderlessWindowHelper.isCachedDisplayCreated()) {
            if (oldestFrameFence != null || previousFrameFence != null) {
                release();
            }
            slowConsecutiveFrames = 0;
            lastPresentNs = 0L;
            return;
        }
        long now = System.nanoTime();
        long dt = lastPresentNs == 0L ? 0L : (now - lastPresentNs);
        lastPresentNs = now;

        if (VulkanBackendHelper.isVulkanActive() || dt < SLOW_FRAME_THRESHOLD_NS) {
            slowConsecutiveFrames = 0;
            if (oldestFrameFence != null || previousFrameFence != null) {
                release();
            }
            return;
        }

        if (++slowConsecutiveFrames < HYSTERESIS_FRAMES) {
            return;
        }

        int backend = resolveBackend();
        if (backend <= 0) {
            return;
        }
        try {
            if (oldestFrameFence != null) {
                if (backend == 1) {
                    GL32.glClientWaitSync(oldestFrameFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 60_000L);
                    GL32.glDeleteSync(oldestFrameFence);
                } else {
                    ARBSync.glClientWaitSync(oldestFrameFence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, 60_000L);
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
