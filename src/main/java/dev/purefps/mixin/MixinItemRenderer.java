package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Module PvP Low Fire : abaisse les flammes en vue subjective pour libérer le champ de vision et le fillrate GPU.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRenderer {

    private boolean purefps$pushedLowFire = false;

    @Inject(method = "renderFireInFirstPerson", at = @At("HEAD"))
    private void purefps$pushLowFireOffset(float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.lowFire) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0.0F, -0.25F, 0.0F);
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
}
