package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.FastArmorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimisations majeures du rendu d'armures sur les joueurs et entités (LayerArmorBase) :
 * 1. Table O(1) FastArmorCache : élimine les 4 à 8 appels String.format() + HashMap.get() par joueur équipé à chaque frame.
 * 2. LOD du reflet d'enchantement d'armure (skipRenderGlint) au-delà de 18m (FPS) / 32m (Équilibré).
 */
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBase {

    @Shadow
    private boolean skipRenderGlint;

    @Unique
    private boolean purefps$prevSkipGlint = false;
    @Unique
    private boolean purefps$overrodeSkipGlint = false;

    @Inject(
            method = "getArmorResource(Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;ILjava/lang/String;)Lnet/minecraft/util/ResourceLocation;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void purefps$fastVanillaArmorResource(
            Entity entity,
            ItemStack stack,
            int slot,
            String type,
            CallbackInfoReturnable<ResourceLocation> cir
    ) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastItemRender) {
            ResourceLocation cached = FastArmorCache.lookupVanillaArmor(stack, slot, type);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(method = "doRenderLayer", at = @At("HEAD"))
    private void purefps$beginDistantArmorGlintLod(
            EntityLivingBase entity,
            float p_177141_2_,
            float p_177141_3_,
            float partialTicks,
            float p_177141_5_,
            float p_177141_6_,
            float p_177141_7_,
            float scale,
            CallbackInfo ci
    ) {
        this.purefps$overrodeSkipGlint = false;
        if (!PureFPSConfig.enabled || !PureFPSConfig.limitDistantGlint || this.skipRenderGlint || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc != null ? mc.getRenderViewEntity() : null;
        if (viewer == null || viewer == entity) {
            return;
        }
        double maxDistSq = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 324.0D : 1024.0D;
        if (viewer.getDistanceSqToEntity(entity) > maxDistSq) {
            this.purefps$prevSkipGlint = this.skipRenderGlint;
            this.skipRenderGlint = true;
            this.purefps$overrodeSkipGlint = true;
        }
    }

    @Inject(method = "doRenderLayer", at = @At("RETURN"))
    private void purefps$endDistantArmorGlintLod(
            EntityLivingBase entity,
            float p_177141_2_,
            float p_177141_3_,
            float partialTicks,
            float p_177141_5_,
            float p_177141_6_,
            float p_177141_7_,
            float scale,
            CallbackInfo ci
    ) {
        if (this.purefps$overrodeSkipGlint) {
            this.skipRenderGlint = this.purefps$prevSkipGlint;
            this.purefps$overrodeSkipGlint = false;
        }
    }
}
