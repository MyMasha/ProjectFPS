package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.layers.LayerArrow;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Évite l'instanciation new Random(entityId) + new EntityArrow(world) et les boucles GL par flèche plantée
 * sur les entités distantes (> 16m FPS / > 28m Balanced) ou lorsqu'aucune flèche n'est plantée.
 */
@Mixin(LayerArrow.class)
public class MixinLayerArrow {

    @Inject(method = "doRenderLayer", at = @At("HEAD"), cancellable = true)
    private void purefps$cullDistantStuckArrows(
            EntityLivingBase entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale,
            CallbackInfo ci
    ) {
        if (entity == null || entity.getArrowCountInEntity() <= 0) {
            ci.cancel();
            return;
        }
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastPlayerRendering) {
            return;
        }
        if (PureFPSConfig.activeProfile == PureFPSProfile.QUALITY) {
            return;
        }
        double distSq = dev.purefps.core.RenderItemContext.getViewerDistSqForLayer(entity);
        if (distSq >= 0.0D) {
            double maxSq = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 256.0D : 784.0D;
            if (distSq > maxSq) {
                ci.cancel();
            }
        }
    }
}
