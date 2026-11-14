package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation des objets tenus par les autres joueurs (LayerHeldItem) :
 * 1. Réutilise une instance statique ItemStack pour la canne à pêche lancée (élimine `new ItemStack` à chaque frame).
 * 2. Désactive la double passe d'enchantement (glint) sur l'objet tenu par les joueurs distants (> 18m FPS / > 28m Équilibré).
 */
@Mixin(LayerHeldItem.class)
public class MixinLayerHeldItem {

    @Unique
    private static ItemStack purefps$castRodStack;

    @Unique
    private boolean purefps$prevSkipGlint = false;

    @Redirect(
            method = "doRenderLayer",
            at = @At(value = "NEW", target = "(Lnet/minecraft/item/Item;I)Lnet/minecraft/item/ItemStack;")
    )
    private ItemStack purefps$reusedFishingRodStack(Item item, int amount) {
        if (PureFPSConfig.enabled && item == Items.fishing_rod) {
            if (purefps$castRodStack == null) {
                purefps$castRodStack = new ItemStack(Items.fishing_rod, 0);
            }
            return purefps$castRodStack;
        }
        return new ItemStack(item, amount);
    }

    @Inject(method = "doRenderLayer", at = @At("HEAD"))
    private void purefps$beginHeldItemGlintLod(
            EntityLivingBase entity,
            float f,
            float g,
            float partialTicks,
            float h,
            float i,
            float j,
            float scale,
            CallbackInfo ci
    ) {
        this.purefps$prevSkipGlint = RenderItemContext.skipGlintForCurrentItem;
        if (!PureFPSConfig.enabled || !PureFPSConfig.limitDistantGlint || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc != null ? mc.getRenderViewEntity() : null;
        if (viewer != null && viewer != entity) {
            double cutoffSq = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 324.0D : 784.0D;
            if (viewer.getDistanceSqToEntity(entity) > cutoffSq) {
                RenderItemContext.skipGlintForCurrentItem = true;
            }
        }
    }

    @Inject(method = "doRenderLayer", at = @At("RETURN"))
    private void purefps$endHeldItemGlintLod(
            EntityLivingBase entity,
            float f,
            float g,
            float partialTicks,
            float h,
            float i,
            float j,
            float scale,
            CallbackInfo ci
    ) {
        RenderItemContext.skipGlintForCurrentItem = this.purefps$prevSkipGlint;
    }
}
