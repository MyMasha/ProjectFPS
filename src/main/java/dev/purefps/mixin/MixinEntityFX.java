package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ParticlePhysicsHelper;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Surcharge directe de moveEntity uniquement sur EntityFX (particules purement client) :
 * - Zéro impact ni overhead sur EntityPlayerSP / entités normales.
 * - Zéro allocation CallbackInfo.
 */
@Mixin(EntityFX.class)
public abstract class MixinEntityFX extends Entity {

    public MixinEntityFX(World worldIn) {
        super(worldIn);
    }

    @Override
    public void moveEntity(double x, double y, double z) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastParticlePhysics) {
            ParticlePhysicsHelper.moveParticleFast((EntityFX) (Object) this, x, y, z);
            return;
        }
        super.moveEntity(x, y, z);
    }
}
