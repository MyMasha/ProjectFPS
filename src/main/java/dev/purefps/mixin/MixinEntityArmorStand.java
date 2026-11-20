package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Court-circuite moveEntity, handleWaterMovement et collideWithNearbyEntities sur les EntityArmorStand
 * invisibles côté client (hologrammes/marqueurs Hypixel), éliminant des centaines de requêtes AABB/eau par tick.
 */
@Mixin(EntityArmorStand.class)
public abstract class MixinEntityArmorStand extends EntityLivingBase {

    public MixinEntityArmorStand(World worldIn) {
        super(worldIn);
    }

    @Override
    public void moveEntity(double x, double y, double z) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastArmorStands
                && this.worldObj != null && this.worldObj.isRemote
                && x == 0.0D && y == 0.0D && z == 0.0D && this.isInvisible()) {
            return;
        }
        super.moveEntity(x, y, z);
    }

    @Override
    public boolean handleWaterMovement() {
        if (PureFPSConfig.enabled && PureFPSConfig.fastArmorStands
                && this.worldObj != null && this.worldObj.isRemote
                && this.isInvisible()) {
            return false;
        }
        return super.handleWaterMovement();
    }

    @Inject(method = "collideWithNearbyEntities", at = @At("HEAD"), cancellable = true)
    private void purefps$skipHologramCollisions(CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastArmorStands
                && this.worldObj != null && this.worldObj.isRemote
                && this.isInvisible()) {
            ci.cancel();
        }
    }
}
