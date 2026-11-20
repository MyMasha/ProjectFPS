package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastCameraMatrices;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Élimine les 3 appels synchrones glGetFloat(MODELVIEW), glGetFloat(PROJECTION) et glGetInteger(VIEWPORT)
 * par frame dans ActiveRenderInfo.updateRenderInfo() en lisant le miroir RAM FastCameraMatrices.
 */
@Mixin(ActiveRenderInfo.class)
public class MixinActiveRenderInfo {

    @Redirect(
            method = "updateRenderInfo",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;getFloat(ILjava/nio/FloatBuffer;)V")
    )
    private static void purefps$fastGetCameraMatrix(int pname, FloatBuffer params) {
        if (PureFPSConfig.enabled && FastCameraMatrices.isValid()) {
            if (pname == GL11.GL_MODELVIEW_MATRIX) {
                FastCameraMatrices.copyModelviewTo(params);
                return;
            }
            if (pname == GL11.GL_PROJECTION_MATRIX) {
                FastCameraMatrices.copyProjectionTo(params);
                return;
            }
        }
        GlStateManager.getFloat(pname, params);
    }

    @Redirect(
            method = "updateRenderInfo",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetInteger(ILjava/nio/IntBuffer;)V", remap = false)
    )
    private static void purefps$fastGetViewport(int pname, IntBuffer params) {
        if (PureFPSConfig.enabled && FastCameraMatrices.isValid() && pname == GL11.GL_VIEWPORT) {
            FastCameraMatrices.copyViewportTo(params);
            return;
        }
        GL11.glGetInteger(pname, params);
    }
}
