package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FboBlitHelper;
import dev.purefps.core.VulkanBackendHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Présentation FBO Opaque Haute-Fréquence (MashClient 4.5.1) :
 * - Force framebufferColor[3] = 1.0F sur le Framebuffer principal (Minecraft.startGame l'initialisait à 0.0F !).
 * - Présente framebufferTexture vers le Backbuffer 0 via un unique triangle plein écran GLSL forçant Alpha = 1.0,
 *   éliminant 100 % de la transparence DWM / DirectComposition sous Vulkan (Mesa3D Zink) et OpenGL.
 */
@Mixin(Framebuffer.class)
public class MixinFramebuffer {

    @Shadow
    public int framebufferTextureWidth;

    @Shadow
    public int framebufferTextureHeight;

    @Shadow
    public int framebufferWidth;

    @Shadow
    public int framebufferHeight;

    @Shadow
    public int framebufferObject;

    @Shadow
    public int framebufferTexture;

    @Shadow
    public float[] framebufferColor;

    @Inject(method = "framebufferClear", at = @At("HEAD"))
    private void purefps$forceOpaqueMainFramebufferClear(CallbackInfo ci) {
        if ((PureFPSConfig.enabled || VulkanBackendHelper.isVulkanActive())
                && this.framebufferColor != null
                && this.framebufferColor.length >= 4) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && (Object) this == mc.getFramebuffer()) {
                this.framebufferColor[3] = 1.0F;
            }
        }
    }

    @Inject(method = "framebufferRenderExt", at = @At("HEAD"), cancellable = true)
    private void purefps$hardwareBlitFramebuffer(int width, int height, boolean disableBlend, CallbackInfo ci) {
        if (!disableBlend
                || (!(PureFPSConfig.enabled && PureFPSConfig.fastFboBlit) && !VulkanBackendHelper.isVulkanActive())
                || this.framebufferObject < 0
                || this.framebufferTexture <= 0
                || !OpenGlHelper.isFramebufferEnabled()) {
            return;
        }
        if (FboBlitHelper.blitOpaqueFullscreen(
                this.framebufferTexture,
                this.framebufferWidth,
                this.framebufferHeight,
                this.framebufferTextureWidth,
                this.framebufferTextureHeight,
                width,
                height
        )) {
            ci.cancel();
        }
    }
}
