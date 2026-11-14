package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FboBlitHelper;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blit FBO matériel DMA direct en VRAM via GL30.glBlitFramebuffer :
 * - Conserve colorMask(true, true, true, true) afin que le moteur de copie DMA matériel du GPU
 *   effectue un transfert brut 32 bits par pixel (masquer le canal alpha forçait un fallback shader
 *   read-modify-write plein écran de 1.5 à 3 ms par frame !).
 */
@Mixin(Framebuffer.class)
public class MixinFramebuffer {

    @Shadow
    public int framebufferWidth;

    @Shadow
    public int framebufferHeight;

    @Shadow
    public int framebufferObject;

    @Inject(method = "framebufferRenderExt", at = @At("HEAD"), cancellable = true)
    private void purefps$hardwareBlitFramebuffer(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (!disableBlend
                || !PureFPSConfig.enabled
                || !PureFPSConfig.fastFboBlit
                || this.framebufferObject < 0
                || !OpenGlHelper.isFramebufferEnabled()
                || !FboBlitHelper.canHardwareBlit()) {
            return;
        }
        GlStateManager.colorMask(true, true, true, true);
        FboBlitHelper.blitToBackbuffer(
                this.framebufferObject,
                this.framebufferWidth,
                this.framebufferHeight,
                width,
                height
        );
        GlStateManager.depthMask(true);
        ci.cancel();
    }
}
