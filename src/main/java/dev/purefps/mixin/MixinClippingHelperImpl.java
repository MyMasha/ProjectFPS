package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastCameraMatrices;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.FloatBuffer;

/**
 * Élimine les 2 appels synchrones dupliqués glGetFloat(PROJECTION) et glGetFloat(MODELVIEW) par frame
 * dans ClippingHelperImpl.init() en réutilisant directement FastCameraMatrices.
 */
@Mixin(ClippingHelperImpl.class)
public class MixinClippingHelperImpl {

    @Redirect(
            method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;getFloat(ILjava/nio/FloatBuffer;)V")
    )
    private void purefps$reuseCameraMatricesForFrustum(int pname, FloatBuffer params) {
        if (PureFPSConfig.enabled && FastCameraMatrices.isValid()) {
            if (pname == GL11.GL_PROJECTION_MATRIX) {
                FastCameraMatrices.copyProjectionTo(params);
                return;
            }
            if (pname == GL11.GL_MODELVIEW_MATRIX) {
                FastCameraMatrices.copyModelviewTo(params);
                return;
            }
        }
        GlStateManager.getFloat(pname, params);
    }
}
