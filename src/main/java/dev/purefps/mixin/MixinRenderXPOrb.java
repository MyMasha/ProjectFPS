package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.entity.RenderXPOrb;
import net.minecraft.entity.item.EntityXPOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Évite le rendu coûteux (Tessellator individuel + sinus/cosinus couleurs) des minuscules orbes d'XP lointaines.
 */
@Mixin(RenderXPOrb.class)
public class MixinRenderXPOrb {

    @Inject(
            method = "doRender(Lnet/minecraft/entity/item/EntityXPOrb;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void purefps$cullDistantXpOrbs(EntityXPOrb entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastDroppedItems) {
            double maxDist = Math.min(PureFPSConfig.maxEntityRenderDistance, 36);
            if (x * x + y * y + z * z > maxDist * maxDist) {
                PureFPSConfig.frameEntitiesAccumulator++;
                ci.cancel();
            }
        }
    }
}
