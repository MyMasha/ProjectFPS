package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Corrige deux énormes goulots d'étranglement de Forge 1.8.9 dans TileEntity.getRenderBoundingBox() :
 * 1. Forge alloue 2 BlockPos + 1 AxisAlignedBB ou appelle world.getBlockState(pos) sur CHAQUE coffre/crâne/pancarte à CHAQUE frame.
 * 2. Forge renvoie INFINITE_EXTENT_AABB pour toutes les pancartes et bannières (getCollisionBoundingBox == null),
 *    empêchant tout Frustum Culling des pancartes/bannières dans le dos du joueur !
 */
@Mixin(TileEntity.class)
public abstract class MixinTileEntity {

    @Shadow
    protected World worldObj;

    @Shadow
    public abstract BlockPos getPos();

    @Shadow
    public abstract Block getBlockType();

    @Unique
    private AxisAlignedBB purefps$cachedRenderBB;

    @Unique
    private BlockPos purefps$cachedBBPos;

    @Unique
    private Block purefps$cachedBBBlock;

    /**
     * @author PureFPS
     * @reason Cache O(1) zéro-allocation de la boîte de rendu et vrai Frustum Culling des pancartes/bannières
     */
    @Overwrite(remap = false)
    public AxisAlignedBB getRenderBoundingBox() {
        Block type = this.getBlockType();
        BlockPos p = this.getPos();
        if (!PureFPSConfig.enabled || !PureFPSConfig.tileEntityFrustumCulling || p == null) {
            return this.purefps$computeVanillaRenderBB(type, p);
        }

        if (this.purefps$cachedRenderBB != null && p == this.purefps$cachedBBPos && type == this.purefps$cachedBBBlock) {
            return this.purefps$cachedRenderBB;
        }

        int x = p.getX();
        int y = p.getY();
        int z = p.getZ();
        AxisAlignedBB bb;
        if (type == Blocks.beacon) {
            bb = TileEntity.INFINITE_EXTENT_AABB;
        } else if (type == Blocks.chest || type == Blocks.trapped_chest) {
            bb = new AxisAlignedBB(x - 1, y, z - 1, x + 2, y + 2, z + 2);
        } else if (type == Blocks.standing_banner || type == Blocks.wall_banner) {
            bb = new AxisAlignedBB(x, y - 1, z, x + 1, y + 2, z + 1);
        } else if (type == Blocks.enchanting_table
                || type == Blocks.skull
                || type == Blocks.standing_sign
                || type == Blocks.wall_sign
                || type == Blocks.ender_chest) {
            bb = new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1);
        } else {
            bb = this.purefps$computeVanillaRenderBB(type, p);
        }

        if (type != null) {
            this.purefps$cachedBBPos = p;
            this.purefps$cachedBBBlock = type;
            this.purefps$cachedRenderBB = bb;
        }
        return bb;
    }

    @Unique
    private AxisAlignedBB purefps$computeVanillaRenderBB(Block type, BlockPos p) {
        AxisAlignedBB bb = TileEntity.INFINITE_EXTENT_AABB;
        if (p == null) {
            return bb;
        }
        if (type == Blocks.enchanting_table) {
            bb = new AxisAlignedBB(p, p.add(1, 1, 1));
        } else if (type == Blocks.chest || type == Blocks.trapped_chest) {
            bb = new AxisAlignedBB(p.add(-1, 0, -1), p.add(2, 2, 2));
        } else if (type != null && type != Blocks.beacon) {
            AxisAlignedBB cbb = null;
            try {
                cbb = type.getCollisionBoundingBox(this.worldObj, p, this.worldObj.getBlockState(p));
            } catch (Exception ignored) {
                cbb = new AxisAlignedBB(p.add(-1, 0, -1), p.add(1, 1, 1));
            }
            if (cbb != null) {
                bb = cbb;
            }
        }
        return bb;
    }
}
