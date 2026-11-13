package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
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
 * Permet de désactiver optionnellement les recalculs lourds de Skylight côté client
 * (responsables des freezes majeurs quand des ponts tombent ou lors d'explosions en BedWars/SkyWars).
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
}
