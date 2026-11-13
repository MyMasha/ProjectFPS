package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlockPosPool;
import dev.purefps.core.EntityCullCache;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 1. Stocke l'état de cache d'occlusion 3D directement dans Entity (accès O(1), étalé par getEntityId()).
 * 2. ZÉRO hook sur Entity.moveEntity (100 % physique Vanilla pure pour EntityPlayerSP, zéro rollback anti-cheat).
 * 3. @Overwrite zéro-allocation avec cache 1-tick sur getBrightnessForRender / getBrightness côté client.
 */
@Mixin(Entity.class)
public abstract class MixinEntity implements EntityCullCache {

    @Shadow
    public World worldObj;

    @Shadow
    public double posX;

    @Shadow
    public double posY;

    @Shadow
    public double posZ;

    @Shadow
    public int ticksExisted;

    @Shadow
    public abstract float getEyeHeight();

    @Shadow
    public abstract int getEntityId();

    @Unique
    private int purefps$lastCullCheckTick = -999;

    @Unique
    private boolean purefps$cachedOccluded = false;

    @Unique
    private int purefps$combinedLightTick = -999;

    @Unique
    private long purefps$combinedLightPos = Long.MIN_VALUE;

    @Unique
    private int purefps$cachedCombinedLight = 0;

    @Unique
    private int purefps$brightnessTick = -999;

    @Unique
    private long purefps$brightnessPos = Long.MIN_VALUE;

    @Unique
    private float purefps$cachedBrightness = 0.0F;

    @Override
    public int purefps$getCachedOcclusionState(int currentTick) {
        if (this.purefps$lastCullCheckTick < 0) {
            return -1;
        }
        int age = currentTick - this.purefps$lastCullCheckTick;
        if (age < 4) {
            return this.purefps$cachedOccluded ? 1 : 0;
        }
        // Étalement par entityId pour éviter que 80 entités d'un lobby recalculent sur le même tick
        if (age < 8 && ((currentTick + this.getEntityId()) & 3) != 0) {
            return this.purefps$cachedOccluded ? 1 : 0;
        }
        return -1;
    }

    @Override
    public boolean purefps$getLastKnownOccluded() {
        return this.purefps$lastCullCheckTick >= 0 && this.purefps$cachedOccluded;
    }

    @Override
    public void purefps$setCulledResult(int currentTick, boolean occluded) {
        this.purefps$lastCullCheckTick = currentTick;
        this.purefps$cachedOccluded = occluded;
    }

    /**
     * @author PureFPS
     * @reason Supprime new BlockPos(DDD) sur chaque particule/entité à chaque frame + cache intra-tick
     */
    @Overwrite
    public int getBrightnessForRender(float partialTicks) {
        World world = this.worldObj;
        if (world == null) {
            return 0;
        }
        int bx = MathHelper.floor_double(this.posX);
        int by = MathHelper.floor_double(this.posY + (double) this.getEyeHeight());
        int bz = MathHelper.floor_double(this.posZ);

        if (PureFPSConfig.enabled && world.isRemote) {
            int tick = this.ticksExisted;
            long packed = ((long) (bx & 0x3FFFFFF) << 38) | ((long) (by & 0xFFF) << 26) | (long) (bz & 0x3FFFFFF);
            if (tick == this.purefps$combinedLightTick && packed == this.purefps$combinedLightPos) {
                return this.purefps$cachedCombinedLight;
            }
            BlockPos pos = BlockPosPool.acquire(bx, by, bz);
            int combined = world.isBlockLoaded(pos) ? world.getCombinedLight(pos, 0) : 0;
            this.purefps$combinedLightTick = tick;
            this.purefps$combinedLightPos = packed;
            this.purefps$cachedCombinedLight = combined;
            return combined;
        }

        BlockPos pos = new BlockPos(bx, by, bz);
        return world.isBlockLoaded(pos) ? world.getCombinedLight(pos, 0) : 0;
    }

    /**
     * @author PureFPS
     * @reason Supprime les 6-7 new BlockPos(DDD) par frame dans RendererLivingEntity.setBrightness (LayerRenderers)
     */
    @Overwrite
    public float getBrightness(float partialTicks) {
        World world = this.worldObj;
        if (world == null) {
            return 0.0F;
        }
        int bx = MathHelper.floor_double(this.posX);
        int by = MathHelper.floor_double(this.posY + (double) this.getEyeHeight());
        int bz = MathHelper.floor_double(this.posZ);

        if (PureFPSConfig.enabled && world.isRemote) {
            int tick = this.ticksExisted;
            long packed = ((long) (bx & 0x3FFFFFF) << 38) | ((long) (by & 0xFFF) << 26) | (long) (bz & 0x3FFFFFF);
            if (tick == this.purefps$brightnessTick && packed == this.purefps$brightnessPos) {
                return this.purefps$cachedBrightness;
            }
            BlockPos pos = BlockPosPool.acquire(bx, by, bz);
            float brightness = world.isBlockLoaded(pos) ? world.getLightBrightness(pos) : 0.0F;
            this.purefps$brightnessTick = tick;
            this.purefps$brightnessPos = packed;
            this.purefps$cachedBrightness = brightness;
            return brightness;
        }

        BlockPos pos = new BlockPos(bx, by, bz);
        return world.isBlockLoaded(pos) ? world.getLightBrightness(pos) : 0.0F;
    }
}
