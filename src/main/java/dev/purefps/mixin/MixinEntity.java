package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlockPosPool;
import dev.purefps.core.EntityCullCache;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 1. Stocke le cache d'occlusion 3D clé par (position bloc caméra, position bloc entité, tick) avec
 *    TTL court si occulté et garde age < 0 (zéro pop-in en virage ou en bascule F5).
 * 2. ZÉRO hook sur Entity.moveEntity (100 % physique Vanilla pure pour EntityPlayerSP, zéro rollback anti-cheat).
 * 3. @Overwrite zéro-allocation avec lecture directe Chunk/ExtendedBlockStorage sur getBrightnessForRender / getBrightness.
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
    private long purefps$lastCullCamPos = Long.MIN_VALUE;

    @Unique
    private long purefps$lastCullEntPos = Long.MIN_VALUE;

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
    public int purefps$getCachedOcclusionState(int currentTick, long packedCamPos, long packedEntPos) {
        if (this.purefps$lastCullCheckTick < 0) {
            return -1;
        }
        int age = currentTick - this.purefps$lastCullCheckTick;
        if (age < 0) {
            return -1;
        }
        if (packedCamPos != this.purefps$lastCullCamPos || packedEntPos != this.purefps$lastCullEntPos) {
            return -1;
        }
        if (this.purefps$cachedOccluded) {
            if (age < 2) {
                return 1;
            }
            if (age < 4 && ((currentTick + this.getEntityId()) & 1) != 0) {
                return 1;
            }
            return -1;
        }
        if (age < 4) {
            return 0;
        }
        if (age < 8 && ((currentTick + this.getEntityId()) & 3) != 0) {
            return 0;
        }
        return -1;
    }

    @Override
    public boolean purefps$getLastKnownOccluded() {
        return this.purefps$lastCullCheckTick >= 0 && this.purefps$cachedOccluded;
    }

    @Override
    public void purefps$setCulledResult(int currentTick, long packedCamPos, long packedEntPos, boolean occluded) {
        this.purefps$lastCullCheckTick = currentTick;
        this.purefps$lastCullCamPos = packedCamPos;
        this.purefps$lastCullEntPos = packedEntPos;
        this.purefps$cachedOccluded = occluded;
    }

    /**
     * @author PureFPS
     * @reason Lecture directe Chunk/ExtendedBlockStorage (1 lookup chunk au lieu de 3) + cache intra-tick zéro-allocation
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
            int combined = purefps$readCombinedLightFast(world, bx, by, bz);
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
     * @reason Lecture directe Chunk/ExtendedBlockStorage (1 lookup chunk au lieu de 3) + cache intra-tick zéro-allocation
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
            float brightness = purefps$readBrightnessFast(world, bx, by, bz);
            this.purefps$brightnessTick = tick;
            this.purefps$brightnessPos = packed;
            this.purefps$cachedBrightness = brightness;
            return brightness;
        }

        BlockPos pos = new BlockPos(bx, by, bz);
        return world.isBlockLoaded(pos) ? world.getLightBrightness(pos) : 0.0F;
    }

    @Unique
    private static int purefps$readCombinedLightFast(World world, int bx, int by, int bz) {
        Chunk chunk = world.getChunkFromChunkCoords(bx >> 4, bz >> 4);
        if (chunk == null || chunk.isEmpty()) {
            return 0;
        }
        if (by < 0) {
            by = 0;
        }
        if (by >= 256) {
            int sky = world.provider.getHasNoSky() ? 0 : 15;
            return sky << 20;
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        ExtendedBlockStorage storage = (storages != null) ? storages[by >> 4] : null;
        if (storage == null) {
            int sky = world.provider.getHasNoSky() ? 0 : (chunk.canSeeSky(BlockPosPool.acquire(bx, by, bz)) ? 15 : 0);
            return sky << 20;
        }
        int lx = bx & 15;
        int ly = by & 15;
        int lz = bz & 15;
        char raw = storage.getData()[(ly << 8) | (lz << 4) | lx];
        if (raw == 0) {
            int sky = world.provider.getHasNoSky() ? 0 : storage.getExtSkylightValue(lx, ly, lz);
            int blk = storage.getExtBlocklightValue(lx, ly, lz);
            return (sky << 20) | (blk << 4);
        }
        return world.getCombinedLight(BlockPosPool.acquire(bx, by, bz), 0);
    }

    @Unique
    private static float purefps$readBrightnessFast(World world, int bx, int by, int bz) {
        Chunk chunk = world.getChunkFromChunkCoords(bx >> 4, bz >> 4);
        if (chunk == null || chunk.isEmpty()) {
            return 0.0F;
        }
        if (by < 0) {
            by = 0;
        }
        if (by >= 256) {
            int sky = world.provider.getHasNoSky() ? 0 : Math.max(0, 15 - world.getSkylightSubtracted());
            return world.provider.getLightBrightnessTable()[sky & 15];
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        ExtendedBlockStorage storage = (storages != null) ? storages[by >> 4] : null;
        if (storage == null) {
            int sky = (world.provider.getHasNoSky() || !chunk.canSeeSky(BlockPosPool.acquire(bx, by, bz)))
                    ? 0
                    : Math.max(0, 15 - world.getSkylightSubtracted());
            return world.provider.getLightBrightnessTable()[sky & 15];
        }
        int lx = bx & 15;
        int ly = by & 15;
        int lz = bz & 15;
        char raw = storage.getData()[(ly << 8) | (lz << 4) | lx];
        if (raw == 0) {
            int sky = world.provider.getHasNoSky() ? 0 : storage.getExtSkylightValue(lx, ly, lz);
            int effSky = sky - world.getSkylightSubtracted();
            int blk = storage.getExtBlocklightValue(lx, ly, lz);
            int light = effSky > blk ? effSky : blk;
            if (light < 0) light = 0;
            if (light > 15) light = 15;
            return world.provider.getLightBrightnessTable()[light];
        }
        return world.getLightBrightness(BlockPosPool.acquire(bx, by, bz));
    }
}
