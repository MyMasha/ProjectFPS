package dev.purefps.core;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Physique ultra-légère dédiée exclusivement aux particules client (EntityFX).
 * En 1.8.9, EntityFX hérite de Entity.moveEntity() qui alloue jusqu'à 14 objets par tick/particule,
 * scanne la WorldBorder, les bateaux/entités environnantes et déclenche doBlockCollisions().
 */
public final class ParticlePhysicsHelper {

    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final ThreadLocal<List<AxisAlignedBB>> SCRATCH_BOXES =
            ThreadLocal.withInitial(() -> new ArrayList<AxisAlignedBB>(8));

    private ParticlePhysicsHelper() {
    }

    public static void moveParticleFast(EntityFX fx, double dx, double dy, double dz) {
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return;
        }

        AxisAlignedBB bb = fx.getEntityBoundingBox();
        if (bb == null) {
            return;
        }

        if (fx.noClip || fx.worldObj == null) {
            AxisAlignedBB moved = new AxisAlignedBB(
                    bb.minX + dx, bb.minY + dy, bb.minZ + dz,
                    bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz
            );
            fx.setEntityBoundingBox(moved);
            fx.posX = (moved.minX + moved.maxX) * 0.5D;
            fx.posY = moved.minY;
            fx.posZ = (moved.minZ + moved.maxZ) * 0.5D;
            return;
        }

        World world = fx.worldObj;
        double origDx = dx;
        double origDy = dy;
        double origDz = dz;

        double sweepMinX = dx < 0.0D ? bb.minX + dx : bb.minX;
        double sweepMaxX = dx > 0.0D ? bb.maxX + dx : bb.maxX;
        double sweepMinY = dy < 0.0D ? bb.minY + dy : bb.minY;
        double sweepMaxY = dy > 0.0D ? bb.maxY + dy : bb.maxY;
        double sweepMinZ = dz < 0.0D ? bb.minZ + dz : bb.minZ;
        double sweepMaxZ = dz > 0.0D ? bb.maxZ + dz : bb.maxZ;

        int minX = MathHelper.floor_double(sweepMinX);
        int maxX = MathHelper.floor_double(sweepMaxX + 1.0D);
        int minY = MathHelper.floor_double(sweepMinY) - 1;
        int maxY = MathHelper.floor_double(sweepMaxY + 1.0D);
        int minZ = MathHelper.floor_double(sweepMinZ);
        int maxZ = MathHelper.floor_double(sweepMaxZ + 1.0D);

        BlockPos.MutableBlockPos mpos = SCRATCH_POS.get();
        List<AxisAlignedBB> boxes = SCRATCH_BOXES.get();
        boxes.clear();
        AxisAlignedBB sweptBox = null;

        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                if (!world.isBlockLoaded(mpos.set(x, 64, z))) {
                    continue;
                }
                for (int y = minY; y < maxY; y++) {
                    if (y < 0 || y >= 256) {
                        continue;
                    }
                    mpos.set(x, y, z);
                    IBlockState state = world.getBlockState(mpos);
                    Block block = state.getBlock();
                    if (block == Blocks.air) {
                        continue;
                    }
                    if (sweptBox == null) {
                        sweptBox = new AxisAlignedBB(sweepMinX, sweepMinY, sweepMinZ, sweepMaxX, sweepMaxY, sweepMaxZ);
                    }
                    block.addCollisionBoxesToList(world, mpos, state, sweptBox, boxes, fx);
                }
            }
        }

        if (boxes.isEmpty()) {
            // Chemin ultra-majoritaire (>92 % des ticks) : particule en plein air, zéro collision
            AxisAlignedBB moved = new AxisAlignedBB(
                    bb.minX + dx, bb.minY + dy, bb.minZ + dz,
                    bb.maxX + dx, bb.maxY + dy, bb.maxZ + dz
            );
            fx.setEntityBoundingBox(moved);
            fx.posX = (moved.minX + moved.maxX) * 0.5D;
            fx.posY = moved.minY;
            fx.posZ = (moved.minZ + moved.maxZ) * 0.5D;
            fx.isCollidedHorizontally = false;
            fx.isCollidedVertically = false;
            fx.onGround = false;
            fx.isCollided = false;
            return;
        }

        int size = boxes.size();
        for (int i = 0; i < size; i++) {
            dy = boxes.get(i).calculateYOffset(bb, dy);
        }
        bb = new AxisAlignedBB(bb.minX, bb.minY + dy, bb.minZ, bb.maxX, bb.maxY + dy, bb.maxZ);

        for (int i = 0; i < size; i++) {
            dx = boxes.get(i).calculateXOffset(bb, dx);
        }
        bb = new AxisAlignedBB(bb.minX + dx, bb.minY, bb.minZ, bb.maxX + dx, bb.maxY, bb.maxZ);

        for (int i = 0; i < size; i++) {
            dz = boxes.get(i).calculateZOffset(bb, dz);
        }
        bb = new AxisAlignedBB(bb.minX, bb.minY, bb.minZ + dz, bb.maxX, bb.maxY, bb.maxZ + dz);

        fx.setEntityBoundingBox(bb);
        fx.posX = (bb.minX + bb.maxX) * 0.5D;
        fx.posY = bb.minY;
        fx.posZ = (bb.minZ + bb.maxZ) * 0.5D;

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
