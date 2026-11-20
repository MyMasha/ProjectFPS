package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.StandardModelContext;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepte les 15 appels à BlockPos.offset(EnumFacing) par quad dans AmbientOcclusionFace.updateVertexBrightness
 * et met en cache la référence vers l'anneau MutableBlockPos[32] du thread courant sur l'instance AmbientOcclusionFace
 * (1 seul ThreadLocal.get() par bloc au lieu de 15 par quad !).
 */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public class MixinAmbientOcclusionFace {

    @Unique
    private BlockPos.MutableBlockPos[] purefps$aoRing;

    @Unique
    private int purefps$aoRingIdx = 0;

    @Redirect(
            method = "updateVertexBrightness",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/BlockPos;offset(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;"
            )
    )
    private BlockPos purefps$pooledOffset(BlockPos base, EnumFacing facing) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.zeroAllocSmoothLighting) {
            return base.offset(facing);
        }
        BlockPos.MutableBlockPos[] ring = this.purefps$aoRing;
        if (ring == null) {
            ring = StandardModelContext.getAoFaceRing();
            this.purefps$aoRing = ring;
        }
        int idx = this.purefps$aoRingIdx;
        this.purefps$aoRingIdx = (idx + 1) & StandardModelContext.AO_RING_MASK;
        BlockPos.MutableBlockPos pos = ring[idx];
        pos.set(
                base.getX() + facing.getFrontOffsetX(),
                base.getY() + facing.getFrontOffsetY(),
                base.getZ() + facing.getFrontOffsetZ()
        );
        return pos;
    }
}
