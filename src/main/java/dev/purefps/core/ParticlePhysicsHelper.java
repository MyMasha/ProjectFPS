package dev.purefps.core;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Physique ultra-légère zéro-allocation dédiée exclusivement aux particules client (EntityFX) :
 * - Met en cache (chunkX, chunkZ, ExtendedBlockStorage[]) dans la boucle XYZ et court-circuite
 *   immédiatement data[idx] == 0 (air) : -90 % lookups LongHashMap et getBlockState.
 * - Mute l'AxisAlignedBB privée de la particule en place via FastMutableAABB (0 new AxisAlignedBB par tick).
 */
public final class ParticlePhysicsHelper {

    private static final class Scratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>(8);
        final AxisAlignedBB sweptBox = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private ParticlePhysicsHelper() {
    }

    private static void setOrMutateParticleBB(
            EntityFX fx,
            AxisAlignedBB bb,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ
    ) {
        // Ne mute jamais Entity.ZERO_AABB (partagée) : alloue une seule fois si la boîte est dégénérée à l'origine
        boolean maybeSharedZero = (bb.minX == 0.0D && bb.minY == 0.0D && bb.minZ == 0.0D
                && bb.maxX == 0.0D && bb.maxY == 0.0D && bb.maxZ == 0.0D);
        if (!maybeSharedZero && bb instanceof FastMutableAABB) {
            ((FastMutableAABB) bb).purefps$setBounds(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            fx.setEntityBoundingBox(new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
        }
        fx.posX = (minX + maxX) * 0.5D;
        fx.posY = minY;
        fx.posZ = (minZ + maxZ) * 0.5D;
    }

    public static void moveParticleFast(EntityFX fx, double dx, double dy, double dz) {
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return;
        }

        AxisAlignedBB bb = fx.getEntityBoundingBox();
        if (bb == null) {
            return;
        }

        double curMinX = bb.minX;
        double curMinY = bb.minY;
        double curMinZ = bb.minZ;
        double curMaxX = bb.maxX;
        double curMaxY = bb.maxY;
        double curMaxZ = bb.maxZ;

        if (fx.noClip || fx.worldObj == null) {
            setOrMutateParticleBB(fx, bb, curMinX + dx, curMinY + dy, curMinZ + dz, curMaxX + dx, curMaxY + dy, curMaxZ + dz);
            return;
        }

        World world = fx.worldObj;
        double origDx = dx;
        double origDy = dy;
        double origDz = dz;

        double sweepMinX = dx < 0.0D ? curMinX + dx : curMinX;
        double sweepMaxX = dx > 0.0D ? curMaxX + dx : curMaxX;
        double sweepMinY = dy < 0.0D ? curMinY + dy : curMinY;
        double sweepMaxY = dy > 0.0D ? curMaxY + dy : curMaxY;
        double sweepMinZ = dz < 0.0D ? curMinZ + dz : curMinZ;
        double sweepMaxZ = dz > 0.0D ? curMaxZ + dz : curMaxZ;

        int minX = MathHelper.floor_double(sweepMinX);
        int maxX = MathHelper.floor_double(sweepMaxX + 1.0D);
        int minY = MathHelper.floor_double(sweepMinY) - 1;
        int maxY = MathHelper.floor_double(sweepMaxY + 1.0D);
        int minZ = MathHelper.floor_double(sweepMinZ);
        int maxZ = MathHelper.floor_double(sweepMaxZ + 1.0D);

        Scratch sc = SCRATCH.get();
        BlockPos.MutableBlockPos mpos = sc.pos;
        List<AxisAlignedBB> boxes = sc.boxes;
        boxes.clear();

        AxisAlignedBB sweptBox = sc.sweptBox;
        boolean sweptReady = false;
        boolean isClient = world.isRemote;

        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;
        ExtendedBlockStorage[] cachedStorages = null;
        Chunk cachedChunk = null;

        int yStart = Math.max(0, minY);
        int yEnd = Math.min(256, maxY);

        for (int x = minX; x < maxX; x++) {
            int cx = x >> 4;
            int lx = x & 15;
            for (int z = minZ; z < maxZ; z++) {
                int cz = z >> 4;
                int lz = z & 15;
                if (cx != cachedChunkX || cz != cachedChunkZ) {
                    cachedChunkX = cx;
                    cachedChunkZ = cz;
                    if (isClient) {
                        cachedChunk = world.getChunkFromChunkCoords(cx, cz);
                        cachedStorages = (cachedChunk != null && !cachedChunk.isEmpty())
                                ? cachedChunk.getBlockStorageArray()
                                : null;
                    } else {
                        if (world.isBlockLoaded(mpos.set(x, 64, z))) {
                            cachedChunk = world.getChunkFromChunkCoords(cx, cz);
                            cachedStorages = cachedChunk != null ? cachedChunk.getBlockStorageArray() : null;
                        } else {
                            cachedChunk = null;
                            cachedStorages = null;
                        }
                    }
                }
                if (cachedStorages == null) {
                    continue;
                }

                int cachedSubY = -1;
                char[] subData = null;
                int xzOff = (lz << 4) | lx;

                for (int y = yStart; y < yEnd; y++) {
                    int sy = y >> 4;
                    if (sy != cachedSubY) {
                        cachedSubY = sy;
                        ExtendedBlockStorage st = cachedStorages[sy];
                        subData = (st != null && !st.isEmpty()) ? st.getData() : null;
                    }
                    if (subData == null) {
                        // Saute immédiatement à la prochaine tranche Y de 16 blocs !
                        y = (sy << 4) + 15;
                        continue;
                    }
                    char raw = subData[((y & 15) << 8) | xzOff];
                    if (raw == 0) {
                        continue;
                    }
                    IBlockState state = (IBlockState) Block.BLOCK_STATE_IDS.getByValue(raw);
                    if (state == null) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (block == Blocks.air) {
                        continue;
                    }
                    if (!sweptReady) {
                        if (sweptBox instanceof FastMutableAABB) {
                            ((FastMutableAABB) sweptBox).purefps$setBounds(sweepMinX, sweepMinY, sweepMinZ, sweepMaxX, sweepMaxY, sweepMaxZ);
                        } else {
                            sweptBox = new AxisAlignedBB(sweepMinX, sweepMinY, sweepMinZ, sweepMaxX, sweepMaxY, sweepMaxZ);
                        }
                        sweptReady = true;
                    }
                    mpos.set(x, y, z);
                    block.addCollisionBoxesToList(world, mpos, state, sweptBox, boxes, fx);
                }
            }
        }

        if (boxes.isEmpty()) {
            // Chemin ultra-majoritaire (>92 % des ticks) : particule en plein air, zéro allocation
            setOrMutateParticleBB(fx, bb, curMinX + dx, curMinY + dy, curMinZ + dz, curMaxX + dx, curMaxY + dy, curMaxZ + dz);
            fx.isCollidedHorizontally = false;
            fx.isCollidedVertically = false;
            fx.onGround = false;
            fx.isCollided = false;
            return;
        }

        // Utilise sc.sweptBox comme boîte de travail temporaire pour éviter 3 new AxisAlignedBB intermédiaires
        AxisAlignedBB work = sweptBox;
        boolean canMutateWork = (work instanceof FastMutableAABB);
        if (canMutateWork) {
            ((FastMutableAABB) work).purefps$setBounds(curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);
        } else {
            work = bb;
        }

        int size = boxes.size();
        for (int i = 0; i < size; i++) {
            dy = boxes.get(i).calculateYOffset(work, dy);
        }
        curMinY += dy;
        curMaxY += dy;
        if (canMutateWork) {
            ((FastMutableAABB) work).purefps$setBounds(curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);
        } else {
            work = new AxisAlignedBB(curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);
        }

        for (int i = 0; i < size; i++) {
            dx = boxes.get(i).calculateXOffset(work, dx);
        }
        curMinX += dx;
        curMaxX += dx;
        if (canMutateWork) {
            ((FastMutableAABB) work).purefps$setBounds(curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);
        } else {
            work = new AxisAlignedBB(curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);
        }

        for (int i = 0; i < size; i++) {
            dz = boxes.get(i).calculateZOffset(work, dz);
        }
        curMinZ += dz;
        curMaxZ += dz;

        setOrMutateParticleBB(fx, bb, curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ);

        fx.isCollidedHorizontally = (origDx != dx) || (origDz != dz);
        fx.isCollidedVertically = (origDy != dy);
        fx.onGround = fx.isCollidedVertically && origDy < 0.0D;
        fx.isCollided = fx.isCollidedHorizontally || fx.isCollidedVertically;

        if (origDx != dx) {
            fx.motionX = 0.0D;
        }
        if (origDz != dz) {
            fx.motionZ = 0.0D;
        }
        if (origDy != dy) {
            fx.motionY = 0.0D;
        }
        boxes.clear();
    }
}
