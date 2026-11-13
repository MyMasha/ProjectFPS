package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation RenderItem pour les packs PvP 32x/64x :
 * - Supprime le clonage EnumFacing.values() à chaque rendu d'item (inventaire, main, armure, objets au sol).
 * - Évite la double passe matricielle d'enchantement (renderEffect) sur les items au sol distants (> 16 blocs).
 */
@Mixin(RenderItem.class)
public class MixinRenderItem {

    @Redirect(
            method = "renderModel(Lnet/minecraft/client/resources/model/IBakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$cachedItemFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.fastItemRender)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }

    @Inject(method = "renderEffect", at = @At("HEAD"), cancellable = true)
    private void purefps$skipDistantDroppedItemGlint(IBakedModel model, CallbackInfo ci) {
        if (RenderItemContext.skipGlintForCurrentItem && PureFPSConfig.enabled && PureFPSConfig.limitDistantGlint) {
            ci.cancel();
        }
    }
}
