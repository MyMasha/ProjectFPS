package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1. Permet de désactiver optionnellement les recalculs lourds de Skylight côté client.
 * 2. Module Time Changer (vis_time_changer) : surcharge l'heure visuelle du ciel uniquement côté client.
 */
@Mixin(World.class)
public class MixinWorld {

    @Shadow
    @Final
    public boolean isRemote;

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void purefps$skipClientSkylight(EnumSkyBlock lightType, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (this.isRemote
                && PureFPSConfig.enabled
                && PureFPSConfig.skipClientSkylightUpdates
                && lightType == EnumSkyBlock.SKY) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getWorldTime", at = @At("HEAD"), cancellable = true)
    private void purefps$applyClientTimeChanger(CallbackInfoReturnable<Long> cir) {
        if (this.isRemote && PureFPSConfig.enabled && MashFlags.timeChangerEnabled) {
            cir.setReturnValue(MashFlags.customWorldTime);
        }
    }
}
