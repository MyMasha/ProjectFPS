package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartChunkUpdateScheduler;
import dev.purefps.module.MashFlags;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1. Permet de désactiver optionnellement les recalculs lourds de Skylight côté client.
 * 2. Module Time Changer (vis_time_changer) : surcharge l'heure visuelle du ciel uniquement côté client (0 allocation).
 * 3. Signale à SmartChunkUpdateScheduler toute modification réelle de bloc côté client
 *    (Explosion.doExplosionB, pistons S24, pose/casse locale ou monde solo).
 */
@Mixin(World.class)
public class MixinWorld {

    @Shadow
    @Final
    public boolean isRemote;

    @Shadow
    protected WorldInfo worldInfo;

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClientSkylight(EnumSkyBlock lightType, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (this.isRemote
                && PureFPSConfig.enabled
                && PureFPSConfig.skipClientSkylightUpdates
                && lightType == EnumSkyBlock.SKY) {
            cir.setReturnValue(false);
        }
    }

    /**
     * @author MashClient
     * @reason Élimine l'allocation CallbackInfoReturnable<Long> sur chaque appel chaud World.getWorldTime()
     */
    @Overwrite
    public long getWorldTime() {
        if (this.isRemote && PureFPSConfig.enabled && MashFlags.timeChangerEnabled) {
            return MashFlags.customWorldTime;
        }
        return this.worldInfo.getWorldTime();
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD")
    )
    private void purefps$markClientBlockStateChangeUrgent(
            BlockPos pos,
            IBlockState newState,
            int flags,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!this.isRemote || !PureFPSConfig.enabled || !PureFPSConfig.smartChunkUpdates || pos == null) {
            return;
        }
        World self = (World) (Object) this;
        if (!self.isBlockLoaded(pos)) {
            return;
        }
        IBlockState prevState = self.getBlockState(pos);
        if (prevState != null && prevState.equals(newState)) {
            return;
        }
        Block nextBlock = newState != null ? newState.getBlock() : null;
        Block prevBlock = prevState != null ? prevState.getBlock() : null;
        boolean isBed = (nextBlock instanceof BlockBed) || (prevBlock instanceof BlockBed);
        SmartChunkUpdateScheduler.markDynamicBlockChange(pos.getX(), pos.getY(), pos.getZ(), isBed);
    }
}
