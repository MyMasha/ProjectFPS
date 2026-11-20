package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartAnimations;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Signale sans verrou à SmartAnimations qu'un bloc liquide (eau ou lave) vient d'être compilé dans un chunk proche.
 */
@Mixin(BlockFluidRenderer.class)
public class MixinBlockFluidRenderer {

    @Inject(method = "renderFluid", at = @At("HEAD"))
    private void purefps$onRenderFluid(
            IBlockAccess blockAccess,
            IBlockState blockStateIn,
            BlockPos blockPosIn,
            WorldRenderer worldRendererIn,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations && blockStateIn != null) {
            SmartAnimations.markLiquidBlock(blockStateIn.getBlock());
        }
    }
}
