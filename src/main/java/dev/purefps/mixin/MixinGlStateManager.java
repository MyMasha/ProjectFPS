package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastCameraMatrices;
import dev.purefps.core.GlStateCache;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Cache d'état OpenGL zéro-allocation dans GlStateManager + miroir matrice caméra (uniquement actif
 * durant setupCameraTransform) :
 * - Déduplique glViewport, glMatrixMode et GL_FOG_COLOR (supprime des centaines d'appels JNI redondants).
 * - Alimente FastCameraMatrices uniquement quand FastCameraMatrices.recording == true (0 surcoût hors caméra).
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManager {

    /**
     * @author MashClient
     * @reason Déduplication glViewport + capture instantanée pour FastCameraMatrices
     */
    @Overwrite
    public static void viewport(int x, int y, int width, int height) {
        FastCameraMatrices.setViewport(x, y, width, height);
        if (PureFPSConfig.enabled
                && GlStateCache.vpX == x
                && GlStateCache.vpY == y
                && GlStateCache.vpW == width
                && GlStateCache.vpH == height) {
            return;
        }
        GlStateCache.vpX = x;
        GlStateCache.vpY = y;
        GlStateCache.vpW = width;
        GlStateCache.vpH = height;
        GL11.glViewport(x, y, width, height);
    }

    /**
     * @author MashClient
     * @reason Déduplication glMatrixMode + suivi du mode actif pour FastCameraMatrices
     */
    @Overwrite
    public static void matrixMode(int mode) {
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onMatrixMode(mode);
        }
        if (PureFPSConfig.enabled && GlStateCache.matrixModeKnown && GlStateCache.matrixMode == mode) {
            return;
        }
        GlStateCache.matrixMode = mode;
        GlStateCache.matrixModeKnown = true;
        GL11.glMatrixMode(mode);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation (actif uniquement dans setupCameraTransform)
     */
    @Overwrite
    public static void loadIdentity() {
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onLoadIdentity();
        }
        GL11.glLoadIdentity();
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void pushMatrix() {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onPushMatrix();
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onPushMatrix();
        }
        GL11.glPushMatrix();
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void popMatrix() {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onPopMatrix();
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onPopMatrix();
        }
        GL11.glPopMatrix();
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void rotate(float angle, float x, float y, float z) {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onRotateDegrees(angle, x, y, z);
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onRotate(angle, x, y, z);
        }
        GL11.glRotatef(angle, x, y, z);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void scale(float x, float y, float z) {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onScale(x, y, z);
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onScale(x, y, z);
        }
        GL11.glScalef(x, y, z);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void scale(double x, double y, double z) {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onScale((float) x, (float) y, (float) z);
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onScale((float) x, (float) y, (float) z);
        }
        GL11.glScaled(x, y, z);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void translate(float x, float y, float z) {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onTranslate(x, y, z);
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onTranslate(x, y, z);
        }
        GL11.glTranslatef(x, y, z);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation + capture matricielle CPU pour EntityModelBatcher
     */
    @Overwrite
    public static void translate(double x, double y, double z) {
        if (dev.purefps.core.EntityModelBatcher.capturing) {
            dev.purefps.core.EntityModelBatcher.onTranslate((float) x, (float) y, (float) z);
            return;
        }
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onTranslate((float) x, (float) y, (float) z);
        }
        GL11.glTranslated(x, y, z);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation
     */
    @Overwrite
    public static void multMatrix(FloatBuffer matrix) {
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onMultMatrix(matrix);
        }
        GL11.glMultMatrix(matrix);
    }

    /**
     * @author MashClient
     * @reason Miroir matrice caméra zéro-allocation
     */
    @Overwrite
    public static void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (FastCameraMatrices.recording) {
            FastCameraMatrices.onOrtho(left, right, bottom, top, zNear, zFar);
        }
        GL11.glOrtho(left, right, bottom, top, zNear, zFar);
    }

    @Inject(method = "popAttrib", at = @At("RETURN"))
    private static void purefps$invalidateOnPopAttrib(CallbackInfo ci) {
        GlStateCache.invalidateViewportAndMatrixMode();
    }
}
