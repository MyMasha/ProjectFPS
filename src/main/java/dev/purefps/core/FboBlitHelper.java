package dev.purefps.core;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * Blit matériel VRAM-vers-Backbuffer via GL30.glBlitFramebuffer :
 * Élimine le quad plein écran Tessellator + glMatrixMode(GL_PROJECTION/MODELVIEW) + glOrtho
 * + bindTexture/unbindTexture exécutés à la fin de chaque frame par Framebuffer.framebufferRenderExt.
 */
public final class FboBlitHelper {

    private static int gl30State = 0; // 0 = inconnu, 1 = supporté, -1 = non supporté

    private FboBlitHelper() {
    }

    public static boolean canHardwareBlit() {
        if (gl30State == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                gl30State = (caps != null && caps.OpenGL30) ? 1 : -1;
            } catch (Throwable t) {
                gl30State = -1;
            }
        }
        return gl30State == 1;
    }

    public static void blitToBackbuffer(int fboId, int srcW, int srcH, int dstW, int dstH) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        int filter = (srcW == dstW && srcH == dstH) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH, GL11.GL_COLOR_BUFFER_BIT, filter);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
    }

    /**
     * Test P-Vertex mathématiquement exact équivalent au test des 8 sommets de ClippingHelper.isBoxInFrustum,
     * en 1 produit scalaire par plan au lieu de 8 (soit jusqu'à 8x moins de multiplications).
     */
    public static boolean isBoxInFrustumPVertex(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double f0x, double f0y, double f0z, double f0w,
            double f1x, double f1y, double f1z, double f1w,
            double f2x, double f2y, double f2z, double f2w,
            double f3x, double f3y, double f3z, double f3w,
            double f4x, double f4y, double f4z, double f4w,
            double f5x, double f5y, double f5z, double f5w
    ) {
        if (f0x * (f0x >= 0.0D ? maxX : minX) + f0y * (f0y >= 0.0D ? maxY : minY) + f0z * (f0z >= 0.0D ? maxZ : minZ) + f0w <= 0.0D) {
            return false;
        }
        if (f1x * (f1x >= 0.0D ? maxX : minX) + f1y * (f1y >= 0.0D ? maxY : minY) + f1z * (f1z >= 0.0D ? maxZ : minZ) + f1w <= 0.0D) {
            return false;
        }
        if (f2x * (f2x >= 0.0D ? maxX : minX) + f2y * (f2y >= 0.0D ? maxY : minY) + f2z * (f2z >= 0.0D ? maxZ : minZ) + f2w <= 0.0D) {
            return false;
        }
        if (f3x * (f3x >= 0.0D ? maxX : minX) + f3y * (f3y >= 0.0D ? maxY : minY) + f3z * (f3z >= 0.0D ? maxZ : minZ) + f3w <= 0.0D) {
            return false;
        }
        if (f4x * (f4x >= 0.0D ? maxX : minX) + f4y * (f4y >= 0.0D ? maxY : minY) + f4z * (f4z >= 0.0D ? maxZ : minZ) + f4w <= 0.0D) {
            return false;
        }
        return f5x * (f5x >= 0.0D ? maxX : minX) + f5y * (f5y >= 0.0D ? maxY : minY) + f5z * (f5z >= 0.0D ? maxZ : minZ) + f5w > 0.0D;
    }
}
