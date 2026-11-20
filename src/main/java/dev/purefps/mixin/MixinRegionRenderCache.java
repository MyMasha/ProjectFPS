package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.RegionCachePool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.RegionRenderCache;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Réutilise les tableaux int[8000] et IBlockState[8000] de RegionRenderCache via RegionCachePool
 * pendant RenderChunk.rebuildChunk (supprime 64 Ko d'allocation jetable par section compilée).
 */
@Mixin(RegionRenderCache.class)
public class MixinRegionRenderCache {

    @Shadow
    private int[] combinedLights;

    @Shadow
    private IBlockState[] blockStates;

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 8000))
    private int purefps$skipInitialArrayAllocation(int originalSize) {
        if (PureFPSConfig.enabled && RegionCachePool.isPoolActiveOnCurrentThread()) {
            return 0;
        }
        return originalSize;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void purefps$attachPooledArrays(World worldIn, BlockPos posFromIn, BlockPos posToIn, int subIn, CallbackInfo ci) {
        if (this.combinedLights != null && this.combinedLights.length == 0) {
            this.combinedLights = RegionCachePool.acquireLights();
            this.blockStates = RegionCachePool.acquireStates();
        }
    }
}
