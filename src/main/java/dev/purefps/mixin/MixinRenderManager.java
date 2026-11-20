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
     * @reason Culling précoce (hologrammes, ItemFrames, XP orbs, distance) + DDA 3D depuis la vraie caméra F5/Freelook
     */
    @Overwrite
    public boolean shouldRender(Entity entity, ICamera camera, double camX, double camY, double camZ) {
        boolean optimizable = PureFPSConfig.enabled && !OcclusionCuller.isCriticalEntity(entity, this.livingPlayer);
        if (optimizable) {
            if (PureFPSConfig.fastArmorStands) {
                if (OcclusionCuller.isInvisibleEmptyArmorStand(entity)
                        || OcclusionCuller.isDistantHologramArmorStand(entity, camX, camY, camZ)) {
                    PureFPSConfig.frameEntitiesAccumulator++;
                    return false;
                }
            }
            if (OcclusionCuller.isDistantSpecialEntity(entity, camX, camY, camZ)) {
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
                long packedCam = OcclusionCuller.packBlockPos(camX, camY, camZ);
                long packedEnt = OcclusionCuller.packBlockPos(entity.posX, entity.posY + entity.height * 0.5D, entity.posZ);
                int cached = cache.purefps$getCachedOcclusionState(tick, packedCam, packedEnt);
                boolean occluded;
                if (cached != -1) {
                    occluded = (cached == 1);
                } else if (GpuUploadBudget.tryAcquireRaymarch()) {
                    occluded = OcclusionCuller.isEntityOccluded(this.worldObj, camX, camY, camZ, entity);
                    cache.purefps$setCulledResult(tick, packedCam, packedEnt, occluded);
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
