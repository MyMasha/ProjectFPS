package dev.purefps.core;

import org.lwjgl.opengl.ARBSync;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

/**
 * Régulateur matériel de file d'attente GPU (1-Frame-In-Flight GLSync) :
 * - Empêche le thread de rendu OpenGL d'empiler 3 à 5 frames d'avance dans l'ordonnanceur Windows WDDM
 *   lorsque les FPS sont élevés ou illimités.
 * - Élimine la famine GPU du compositeur DWM et du décodage/encodage vidéo matériel (streams Discord,
 *   navigateurs, OBS) tout en réduisant la latence souris-écran sans brider le débit CPU/GPU parallèle.
 */
public final class GpuFramePacer {

    private static final long MAX_WAIT_NS = 8_000_000L;

    // 0 = non testé, 1 = OpenGL 3.2 natif, 2 = GL_ARB_sync, -1 = indisponible
    private static int syncBackend = 0;
    private static GLSync previousFrameFence = null;

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
     * attend la fin GPU de la frame N-1 avant d'autoriser la soumission de la frame N+1 (max 1 frame en vol).
     */
    public static void onFramePresented() {
        if (!Display.isCreated()) {
            previousFrameFence = null;
            return;
        }
        int backend = resolveBackend();
        if (backend <= 0) {
            return;
        }
        try {
            if (previousFrameFence != null) {
                if (backend == 1) {
                    GL32.glClientWaitSync(previousFrameFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, MAX_WAIT_NS);
                    GL32.glDeleteSync(previousFrameFence);
                } else {
                    ARBSync.glClientWaitSync(previousFrameFence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, MAX_WAIT_NS);
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
