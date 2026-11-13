package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlockPosPool;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepte les 15 appels à BlockPos.offset(EnumFacing) par quad dans AmbientOcclusionFace.updateVertexBrightness
 * et les redirige vers le pool annulaire ThreadLocal sans allocation (économise ~50 000 allocations BlockPos par chunk).
 */
@Mixin(targets = "net.minecraft.client.renderer.BlockModelRenderer$AmbientOcclusionFace")
public class MixinAmbientOcclusionFace {

    @Redirect(
            method = "updateVertexBrightness",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/BlockPos;offset(Lnet/minecraft/util/EnumFacing;)Lnet/minecraft/util/BlockPos;"
            )
    )
    private BlockPos purefps$pooledOffset(BlockPos base, EnumFacing facing) {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocSmoothLighting)
                ? BlockPosPool.offset(base, facing)
                : base.offset(facing);
    }
}
