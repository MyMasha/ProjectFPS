package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Modules PvP Low Fire (hauteur réglable) & Viewmodel XYZ/Scale MashClient 4.0.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRenderer {

    @Unique
    private boolean purefps$pushedLowFire = false;

    @Inject(method = "renderFireInFirstPerson", at = @At("HEAD"))
    private void purefps$pushLowFireOffset(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.lowFire) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, MashFlags.lowFireOffset, 0.0F);
            this.purefps$pushedLowFire = true;
        }
    }

    @Inject(method = "renderFireInFirstPerson", at = @At("RETURN"))
    private void purefps$popLowFireOffset(float partialTicks, CallbackInfo ci) {
        if (this.purefps$pushedLowFire) {
            GlStateManager.popMatrix();
            this.purefps$pushedLowFire = false;
        }
    }

    @Inject(method = "transformFirstPersonItem", at = @At("HEAD"))
    private void purefps$applyCustomViewmodel(float equipProgress, float swingProgress, CallbackInfo ci) {
        if (PureFPSConfig.enabled && MashFlags.viewmodelEnabled) {
            GlStateManager.translate(MashFlags.viewmodelX, MashFlags.viewmodelY, MashFlags.viewmodelZ);
            float sc = MashFlags.viewmodelScale;
            if (Math.abs(sc - 1.0F) > 0.01F) {
                GlStateManager.scale(sc, sc, sc);
            }
        }
    }
}
