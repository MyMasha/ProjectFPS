package dev.purefps.mixin;

import dev.purefps.core.TntTimerRenderer;
import net.minecraft.client.renderer.entity.RenderTNTPrimed;
import net.minecraft.entity.item.EntityTNTPrimed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Affiche le compte à rebours 3D au-dessus de chaque TNT amorcée (module vis_tnt_timer).
 */
@Mixin(RenderTNTPrimed.class)
public abstract class MixinRenderTNTPrimed {

    @Inject(
            method = "doRender(Lnet/minecraft/entity/item/EntityTNTPrimed;DDDFF)V",
            at = @At("RETURN")
    )
    private void purefps$renderTntTimerTag(
            EntityTNTPrimed entity,
            double x,
            double y,
            double z,
            float entityYaw,
            float partialTicks,
            CallbackInfo ci
    ) {
        TntTimerRenderer.render(entity, x, y, z, partialTicks);
    }
}
