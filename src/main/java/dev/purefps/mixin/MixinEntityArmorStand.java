package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Court-circuite moveEntity uniquement sur les EntityArmorStand invisibles immobiles côté client
 * (hologrammes/marqueurs Hypixel), sans toucher à Entity.moveEntity ni allouer de CallbackInfo.
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
}
