package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EntityCullCache;
import dev.purefps.core.GpuUploadBudget;
import dev.purefps.core.OcclusionCuller;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RenderManager.class)
public abstract class MixinRenderManager {

    @Shadow
    public World worldObj;

    @Shadow
    public Entity livingPlayer;

    @Shadow
    public abstract <T extends Entity> Render<T> getEntityRenderObject(Entity entityIn);

    /**
     * @author PureFPS
     * @reason Culling d'entités zéro-allocation avec budget de raymarch borné par frame (zéro micro-freeze)
     */
    @Overwrite
    public boolean shouldRender(Entity entity, ICamera camera, double camX, double camY, double camZ) {
        boolean optimizable = PureFPSConfig.enabled && !OcclusionCuller.isCriticalEntity(entity, this.livingPlayer);
        if (optimizable) {
            if (PureFPSConfig.fastArmorStands && OcclusionCuller.isInvisibleEmptyArmorStand(entity)) {
                PureFPSConfig.frameEntitiesAccumulator++;
                return false;
            }
            if (PureFPSConfig.entityDistanceCulling
                    && OcclusionCuller.isBeyondEntityDistance(entity, camX, camY, camZ, PureFPSConfig.maxEntityRenderDistance)) {
                PureFPSConfig.frameEntitiesAccumulator++;
                return false;
            }
        }

        Render<Entity> render = this.getEntityRenderObject(entity);
        if (render == null || !render.shouldRender(entity, camera, camX, camY, camZ)) {
            return false;
        }

        if (optimizable && PureFPSConfig.entityOcclusionCulling && this.worldObj != null && this.livingPlayer != null) {
            if (entity instanceof EntityCullCache) {
                EntityCullCache cache = (EntityCullCache) entity;
                int tick = entity.ticksExisted;
                int cached = cache.purefps$getCachedOcclusionState(tick);
                boolean occluded;
                if (cached != -1) {
                    occluded = (cached == 1);
                } else if (GpuUploadBudget.tryAcquireRaymarch()) {
                    occluded = OcclusionCuller.isEntityOccluded(this.worldObj, this.livingPlayer, entity);
                    cache.purefps$setCulledResult(tick, occluded);
                } else {
                    occluded = cache.purefps$getLastKnownOccluded();
                }
                if (occluded) {
                    PureFPSConfig.frameEntitiesAccumulator++;
                    return false;
                }
            }
        }

        return true;
    }
}
