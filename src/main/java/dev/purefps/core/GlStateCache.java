package dev.purefps.core;

import org.lwjgl.opengl.GL11;

/**
 * Cache d'état OpenGL centralisé (zéro allocation) partagé entre MixinOpenGlHelper, MixinGlStateManager,
 * FboBlitHelper et les moteurs VBO/VAO pour supprimer les transitions JNI redondantes sans désynchronisation.
 */
public final class GlStateCache {

    public static int boundArrayBuffer = -1;
    public static int clientActiveTexture = -1;
    public static int boundFramebuffer = -1;

    public static int matrixMode = GL11.GL_MODELVIEW;
    public static boolean matrixModeKnown = false;

    public static int vpX = Integer.MIN_VALUE;
    public static int vpY = Integer.MIN_VALUE;
    public static int vpW = -1;
    public static int vpH = -1;

    public static float fogR = Float.NaN;
    public static float fogG = Float.NaN;
    public static float fogB = Float.NaN;
    public static float fogA = Float.NaN;

    private GlStateCache() {
    }

    public static void invalidateArrayBuffer() {
        boundArrayBuffer = -1;
    }

    public static void onBufferDeleted(int bufferId) {
        if (bufferId >= 0 && boundArrayBuffer == bufferId) {
            boundArrayBuffer = -1;
        }
    }

    public static void invalidateFramebuffer() {
        boundFramebuffer = -1;
    }

    public static void invalidateViewportAndMatrixMode() {
        vpX = Integer.MIN_VALUE;
        vpY = Integer.MIN_VALUE;
        vpW = -1;
        vpH = -1;
        matrixModeKnown = false;
    }

    public static void invalidateAll() {
        boundArrayBuffer = -1;
        clientActiveTexture = -1;
        boundFramebuffer = -1;
        matrixModeKnown = false;
        vpX = Integer.MIN_VALUE;
        vpY = Integer.MIN_VALUE;
        vpW = -1;
        vpH = -1;
        fogR = Float.NaN;
        fogG = Float.NaN;
        fogB = Float.NaN;
        fogA = Float.NaN;
    }
}
