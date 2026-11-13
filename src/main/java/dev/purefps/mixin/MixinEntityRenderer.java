package dev.purefps.mixin;

import dev.purefps.client.ZoomController;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Modules caméra, météo & lumière PureClient 3.5 :
 * - Zoom fluide touche C + molette (sans OptiFine)
 * - Fullbright lossless (sans écraser options.txt sur disque)
 * - No HurtCam optionnel pour PvP
 * - Court-circuit immédiat de renderRainSnow et addRainParticles par temps clair
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Shadow
    private Minecraft mc;

    @Shadow
    private boolean lightmapUpdateNeeded;

    private float purefps$savedGamma = 1.0F;
    private boolean purefps$overrodeGamma = false;
    private boolean purefps$lastFullbrightState = false;

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void purefps$applySmoothZoom(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        if (!useFOVSetting) {
            return;
        }
        float baseFov = cir.getReturnValueF();
        float zoomed = ZoomController.applyFov(this.mc, baseFov);
        if (zoomed != baseFov) {
            cir.setReturnValue(zoomed);
        }
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void purefps$disableHurtCamIfConfigured(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.noHurtCam) {
            ci.cancel();
        }
    }

    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClearWeatherRainRender(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && (this.mc == null || this.mc.theWorld == null || this.mc.theWorld.getRainStrength(partialTicks) <= 0.0F)) {
            ci.cancel();
        }
    }

    @Inject(method = "addRainParticles", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClearWeatherRainTicks(CallbackInfo ci) {
        if (PureFPSConfig.enabled && (this.mc == null || this.mc.theWorld == null || this.mc.theWorld.getRainStrength(1.0F) <= 0.0F)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void purefps$syncFullbrightChange(float partialTicks, long nanoTime, CallbackInfo ci) {
        boolean fb = PureFPSConfig.enabled && PureFPSConfig.fullbright;
        if (fb != this.purefps$lastFullbrightState) {
            this.purefps$lastFullbrightState = fb;
            this.lightmapUpdateNeeded = true;
        }
    }

    @Inject(method = "updateLightmap", at = @At("HEAD"))
    private void purefps$beginFullbrightLightmap(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fullbright && this.mc != null && this.mc.gameSettings != null) {
            this.purefps$savedGamma = this.mc.gameSettings.gammaSetting;
            this.mc.gameSettings.gammaSetting = 100.0F;
            this.purefps$overrodeGamma = true;
        }
    }

    @Inject(method = "updateLightmap", at = @At("RETURN"))
    private void purefps$endFullbrightLightmap(float partialTicks, CallbackInfo ci) {
        if (this.purefps$overrodeGamma && this.mc != null && this.mc.gameSettings != null) {
            this.mc.gameSettings.gammaSetting = this.purefps$savedGamma;
            this.purefps$overrodeGamma = false;
        }
    }
}
