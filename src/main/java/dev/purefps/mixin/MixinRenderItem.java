package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastItemFrameRenderer;
import dev.purefps.core.FastItemModelCache;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation profonde des Matériaux / Items (RenderItem) pour les packs PvP 16x/32x/64x/128x :
 * 1. Cache de Display Lists GPU (FastItemModelCache) : transforme les 64 à 256 appels LightUtil.renderQuadColor
 *    par épée/outil/bloc en UN SEUL GlStateManager.callList(listId) (y compris pour les passes d'enchantement !).
 * 2. Supprime les allocations new ModelResourceLocation("bow_pulling_*") / "fishing_rod_cast" sur chaque joueur.
 * 3. Coupe la double passe matricielle d'enchantement (renderEffect) sur les items distants.
 */
@Mixin(RenderItem.class)
public abstract class MixinRenderItem {

    @Shadow
    @Final
    private ItemModelMesher itemModelMesher;

    @Shadow
    protected abstract void renderItemModelTransform(
            ItemStack stack,
            IBakedModel model,
            ItemCameraTransforms.TransformType cameraTransformType
    );

    @org.spongepowered.asm.mixin.Unique
    private IBakedModel purefps$cachedCastRodModel;
    @org.spongepowered.asm.mixin.Unique
    private IBakedModel purefps$cachedBowPull0;
    @org.spongepowered.asm.mixin.Unique
    private IBakedModel purefps$cachedBowPull1;
    @org.spongepowered.asm.mixin.Unique
    private IBakedModel purefps$cachedBowPull2;

    @Inject(
            method = "renderModel(Lnet/minecraft/client/resources/model/IBakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void purefps$renderCachedBakedModel(IBakedModel model, int color, ItemStack stack, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastBakedItemModels) {
            if (FastItemModelCache.tryRenderCached(model, color)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "renderItemModelForEntity", at = @At("HEAD"), cancellable = true)
    private void purefps$zeroAllocEntityItemModel(
            ItemStack stack,
            EntityLivingBase entityToRenderFor,
            ItemCameraTransforms.TransformType cameraTransformType,
            CallbackInfo ci
    ) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastItemRender) {
            return;
        }
        if (stack != null && entityToRenderFor != null) {
            IBakedModel model = this.itemModelMesher.getItemModel(stack);
            if (entityToRenderFor instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entityToRenderFor;
                Item item = stack.getItem();
                if (item == Items.fishing_rod && player.fishEntity != null) {
                    if (this.purefps$cachedCastRodModel == null) {
                        this.purefps$cachedCastRodModel = this.itemModelMesher.getModelManager().getModel(FastItemModelCache.CAST_ROD_LOC);
                    }
                    model = this.purefps$cachedCastRodModel;
                } else if (item == Items.bow && player.getItemInUse() != null) {
                    int pullTicks = stack.getMaxItemUseDuration() - player.getItemInUseCount();
                    if (pullTicks >= 18) {
                        if (this.purefps$cachedBowPull2 == null) {
                            this.purefps$cachedBowPull2 = this.itemModelMesher.getModelManager().getModel(FastItemModelCache.BOW_PULL_2_LOC);
                        }
                        model = this.purefps$cachedBowPull2;
                    } else if (pullTicks > 13) {
                        if (this.purefps$cachedBowPull1 == null) {
                            this.purefps$cachedBowPull1 = this.itemModelMesher.getModelManager().getModel(FastItemModelCache.BOW_PULL_1_LOC);
                        }
                        model = this.purefps$cachedBowPull1;
                    } else if (pullTicks > 0) {
                        if (this.purefps$cachedBowPull0 == null) {
                            this.purefps$cachedBowPull0 = this.itemModelMesher.getModelManager().getModel(FastItemModelCache.BOW_PULL_0_LOC);
                        }
                        model = this.purefps$cachedBowPull0;
                    }
                } else if (item != null) {
                    ModelResourceLocation loc = item.getModel(stack, player, player.getItemInUseCount());
                    if (loc != null) {
                        model = this.itemModelMesher.getModelManager().getModel(loc);
                    }
                }
            }
            this.renderItemModelTransform(stack, model, cameraTransformType);
        }
        ci.cancel();
    }

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

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void purefps$clearBakedModelCachesOnReload(IResourceManager resourceManager, CallbackInfo ci) {
        this.purefps$cachedCastRodModel = null;
        this.purefps$cachedBowPull0 = null;
        this.purefps$cachedBowPull1 = null;
        this.purefps$cachedBowPull2 = null;
        FastItemModelCache.clear();
        FastItemFrameRenderer.clear();
    }
}
