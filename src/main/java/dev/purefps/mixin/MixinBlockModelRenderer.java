package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastItemFrameRenderer;
import dev.purefps.core.IslandCoreCuller;
import dev.purefps.core.SmartAnimations;
import dev.purefps.core.StandardModelContext;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

/**
 * Optimisations zéro-allocation de BlockModelRenderer sans @Overwrite :
 * 1. @Inject sur renderModelBrightnessColor : fusionne les 22-26 draw calls par quad en 1 seul draw.
 * 2. @Redirect zéro-allocation sur renderModelStandard & renderModelStandardQuads (BitSet, EnumFacing, BlockPos,
 *    IslandCoreCuller et SmartAnimations).
 * 3. @Redirect zéro-allocation + suivi SmartAnimations sur renderModelAmbientOcclusion (profil BALANCED par défaut).
 */
@Mixin(BlockModelRenderer.class)
public abstract class MixinBlockModelRenderer {

    @Inject(method = "renderModelBrightnessColor", at = @At("HEAD"), cancellable = true)
    private void purefps$singleDrawBrightnessColor(
            IBakedModel bakedModel,
            float brightness,
            float red,
            float green,
            float blue,
            CallbackInfo ci
    ) {
        if (PureFPSConfig.enabled) {
            FastItemFrameRenderer.renderModelBrightnessColorSingleDraw(bakedModel, brightness, red, green, blue);
            ci.cancel();
        }
    }

    @Redirect(
            method = "renderModelStandard",
            at = @At(value = "NEW", target = "(I)Ljava/util/BitSet;", remap = false)
    )
    private BitSet purefps$stdPooledBitSet(int nbits) {
        if (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache) {
            return StandardModelContext.getBoundsFlags();
        }
        return new BitSet(nbits);
    }

    @Redirect(
            method = "renderModelStandard",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$stdCachedFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }

    @Redirect(
            method = "renderModelStandard",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/BlockPos;offset(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;"
            )
    )
    private BlockPos purefps$stdPooledOffset(BlockPos base, EnumFacing facing) {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? StandardModelContext.offsetStandardPos(base, facing)
                : base.offset(facing);
    }

    @Redirect(
            method = "renderModelStandard",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;shouldSideBeRendered(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z"
            )
    )
    private boolean purefps$stdSideAndIslandCoreVisible(Block block, IBlockAccess access, BlockPos offsetPos, EnumFacing side) {
        if (!block.shouldSideBeRendered(access, offsetPos, side)) {
            return false;
        }
        if (!PureFPSConfig.enabled) {
            return true;
        }
        IslandCoreCuller.Context ctx = IslandCoreCuller.currentContext();
        return !ctx.active || ctx.isNeighborExteriorReachable(offsetPos.getX(), offsetPos.getY(), offsetPos.getZ());
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "renderModelStandardQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                    remap = false
            )
    )
    private Iterator<BakedQuad> purefps$stdMarkSmartAnimations(List<BakedQuad> listQuads) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations) {
            SmartAnimations.markQuadsVisible(listQuads);
        }
        return listQuads.iterator();
    }

    @Redirect(
            method = "renderModelStandardQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/BlockPos;offset(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;"
            )
    )
    private BlockPos purefps$stdQuadsPooledOffset(BlockPos base, EnumFacing facing) {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? StandardModelContext.offsetStandardPos(base, facing)
                : base.offset(facing);
    }

    @Redirect(
            method = "renderModelAmbientOcclusion",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$aoCachedFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }

    @Redirect(
            method = "renderModelAmbientOcclusion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/BlockPos;offset(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;"
            )
    )
    private BlockPos purefps$aoPooledOffset(BlockPos base, EnumFacing facing) {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocSmoothLighting)
                ? StandardModelContext.offsetAoPos(base, facing)
                : base.offset(facing);
    }

    @Redirect(
            method = "renderModelAmbientOcclusion",
            at = @At(value = "NEW", target = "(I)Ljava/util/BitSet;", remap = false)
    )
    private BitSet purefps$aoPooledBitSet(int nbits) {
        if (PureFPSConfig.enabled && PureFPSConfig.zeroAllocSmoothLighting) {
            return StandardModelContext.getAoBoundsFlags();
        }
        return new BitSet(nbits);
    }

    @Redirect(
            method = "renderModelAmbientOcclusion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;shouldSideBeRendered(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)Z"
            )
    )
    private boolean purefps$aoSideAndIslandCoreVisible(Block block, IBlockAccess access, BlockPos offsetPos, EnumFacing side) {
        if (!block.shouldSideBeRendered(access, offsetPos, side)) {
            return false;
        }
        if (!PureFPSConfig.enabled) {
            return true;
        }
        IslandCoreCuller.Context ctx = IslandCoreCuller.currentContext();
        return !ctx.active || ctx.isNeighborExteriorReachable(offsetPos.getX(), offsetPos.getY(), offsetPos.getZ());
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "renderModelAmbientOcclusionQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;",
                    remap = false
            )
    )
    private Iterator<BakedQuad> purefps$aoMarkSmartAnimations(List<BakedQuad> listQuads) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations) {
            SmartAnimations.markQuadsVisible(listQuads);
        }
        return listQuads.iterator();
    }
}
