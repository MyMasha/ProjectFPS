package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlockPosPool;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastItemFrameRenderer;
import dev.purefps.core.StandardModelContext;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.BitSet;
import java.util.List;

/**
 * 1. @Overwrite zéro-allocation de renderModelStandard (utilisé pour 100 % des blocs en profil FPS ao:0).
 * 2. @Overwrite de renderModelBrightnessColor : fusionne les 22 à 26 appels begin()+draw() par cadre d'item
 *    (et blocs portés / pistons / sable tombant) en UN SEUL Tessellator.draw() !
 * 3. Pool d'offsets sans allocation pour renderModelAmbientOcclusion (profils Balanced / Quality).
 */
@Mixin(BlockModelRenderer.class)
public abstract class MixinBlockModelRenderer {

    @Shadow
    private void renderModelStandardQuads(
            IBlockAccess blockAccess,
            Block block,
            BlockPos pos,
            EnumFacing face,
            int brightness,
            boolean ownBrightness,
            WorldRenderer worldRenderer,
            List<BakedQuad> quads,
            BitSet boundsFlags
    ) {
    }

    @Shadow
    private void renderModelBrightnessColorQuads(
            float brightness,
            float red,
            float green,
            float blue,
            List<BakedQuad> listQuads
    ) {
    }

    /**
     * @author PureFPS
     * @reason Fusionne les 22-26 draw calls individuels par quad de Vanilla en UN SEUL Tessellator.draw()
     */
    @Overwrite
    public void renderModelBrightnessColor(IBakedModel bakedModel, float brightness, float red, float green, float blue) {
        if (PureFPSConfig.enabled) {
            FastItemFrameRenderer.renderModelBrightnessColorSingleDraw(bakedModel, brightness, red, green, blue);
            return;
        }
        for (EnumFacing enumfacing : EnumFacing.values()) {
            this.renderModelBrightnessColorQuads(brightness, red, green, blue, bakedModel.getFaceQuads(enumfacing));
        }
        this.renderModelBrightnessColorQuads(brightness, red, green, blue, bakedModel.getGeneralQuads());
    }

    /**
     * @author PureFPS
     * @reason Supprime 100 % des allocations par bloc (BitSet, BlockPos.offset, CallbackInfoReturnable) en rendu sans AO
     */
    @Overwrite
    public boolean renderModelStandard(
            IBlockAccess blockAccessIn,
            IBakedModel modelIn,
            Block blockIn,
            BlockPos blockPosIn,
            WorldRenderer worldRendererIn,
            boolean checkSides
    ) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.zeroAllocEnumCache) {
            boolean flag = false;
            BitSet bitset = new BitSet(3);
            for (EnumFacing enumfacing : EnumFacing.values()) {
                List<BakedQuad> list = modelIn.getFaceQuads(enumfacing);
                if (!list.isEmpty()) {
                    BlockPos blockpos = blockPosIn.offset(enumfacing);
                    if (!checkSides || blockIn.shouldSideBeRendered(blockAccessIn, blockpos, enumfacing)) {
                        int i = blockIn.getMixedBrightnessForBlock(blockAccessIn, blockpos);
                        this.renderModelStandardQuads(blockAccessIn, blockIn, blockPosIn, enumfacing, i, false, worldRendererIn, list, bitset);
                        flag = true;
                    }
                }
            }
            List<BakedQuad> list1 = modelIn.getGeneralQuads();
            if (list1.size() > 0) {
                this.renderModelStandardQuads(blockAccessIn, blockIn, blockPosIn, null, -1, true, worldRendererIn, list1, bitset);
                flag = true;
            }
            return flag;
        }

        boolean renderedAny = false;
        StandardModelContext ctx = StandardModelContext.get();
        BitSet bitSet = ctx.boundsFlags;
        BlockPos.MutableBlockPos offsetPos = ctx.offsetPos;
        int px = blockPosIn.getX();
        int py = blockPosIn.getY();
        int pz = blockPosIn.getZ();

        EnumFacing[] facings = EnumCaches.FACINGS;
        for (int i = 0; i < facings.length; i++) {
            EnumFacing facing = facings[i];
            List<BakedQuad> faceQuads = modelIn.getFaceQuads(facing);
            if (!faceQuads.isEmpty()) {
                offsetPos.set(
                        px + facing.getFrontOffsetX(),
                        py + facing.getFrontOffsetY(),
                        pz + facing.getFrontOffsetZ()
                );
                if (!checkSides || blockIn.shouldSideBeRendered(blockAccessIn, offsetPos, facing)) {
                    int brightness = blockIn.getMixedBrightnessForBlock(blockAccessIn, offsetPos);
                    if (PureFPSConfig.smartAnimations) {
                        dev.purefps.core.SmartAnimations.markQuadsVisible(faceQuads);
                    }
                    this.renderModelStandardQuads(blockAccessIn, blockIn, blockPosIn, facing, brightness, false, worldRendererIn, faceQuads, bitSet);
                    renderedAny = true;
                }
            }
        }

        List<BakedQuad> generalQuads = modelIn.getGeneralQuads();
        if (!generalQuads.isEmpty()) {
            if (PureFPSConfig.smartAnimations) {
                dev.purefps.core.SmartAnimations.markQuadsVisible(generalQuads);
            }
            this.renderModelStandardQuads(blockAccessIn, blockIn, blockPosIn, null, -1, true, worldRendererIn, generalQuads, bitSet);
            renderedAny = true;
        }

        return renderedAny;
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
                ? BlockPosPool.offset(base, facing)
                : base.offset(facing);
    }
}
