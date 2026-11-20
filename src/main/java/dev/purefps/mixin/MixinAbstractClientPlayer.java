package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.world.World;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Module FOV Statique PvP : stabilise la visée en sprint/Speed II tout en conservant le retour visuel de l'arc.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer extends EntityPlayer {

    protected MixinAbstractClientPlayer(World worldIn, GameProfile gameProfileIn) {
        super(worldIn, gameProfileIn);
    }

    @Inject(method = "getFovModifier", at = @At("HEAD"), cancellable = true)
    private void purefps$applyStaticFov(CallbackInfoReturnable<Float> cir) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.staticFov) {
            return;
        }
        float fov = 1.0F;
        if (this.isUsingItem() && this.getItemInUse() != null && this.getItemInUse().getItem() == Items.bow) {
            int ticks = this.getItemInUseDuration();
            float charge = (float) ticks / 20.0F;
            charge = charge > 1.0F ? 1.0F : (charge * charge);
            fov *= 1.0F - charge * 0.15F;
        }
        cir.setReturnValue(fov);
    }
}
