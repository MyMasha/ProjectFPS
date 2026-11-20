package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.TileEntityLightCache;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optimise {@code TileEntity.getRenderBoundingBox()} (méthode Forge) sans dupliquer le corps Forge :
 * 1. Cache O(1) zéro-allocation pour les blocs Vanilla connus (coffres, crânes, pancartes, bannières, tables, balises).
 * 2. Donne une boîte finie aux pancartes et bannières afin qu'elles bénéficient du Frustum Culling hors champ.
 * 3. Laisse Forge calculer directement tous les autres blocs moddés lorsque le cache ne s'applique pas.
 * 4. Met en cache {@code getCombinedLight} (TTL 10 ticks) pour éviter les lookups lumière monde par frame.
 */
@Mixin(TileEntity.class)
public abstract class MixinTileEntity implements TileEntityLightCache {

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

    @Unique
    private int purefps$cachedCombinedLight;

    @Unique
    private long purefps$cachedLightTick = Long.MIN_VALUE;

    @Unique
    private World purefps$cachedLightWorld;

    @Override
    public int purefps$getCachedCombinedLight(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0;
        }
        long nowTick = world.getTotalWorldTime();
        if (world == this.purefps$cachedLightWorld
                && nowTick >= this.purefps$cachedLightTick
                && (nowTick - this.purefps$cachedLightTick) < 10L) {
            return this.purefps$cachedCombinedLight;
        }
        int combined = world.getCombinedLight(pos, 0);
        this.purefps$cachedLightWorld = world;
        this.purefps$cachedLightTick = nowTick;
        this.purefps$cachedCombinedLight = combined;
        return combined;
    }

    @Inject(method = "getRenderBoundingBox", at = @At("HEAD"), cancellable = true, remap = false)
    private void purefps$fastCachedRenderBB(CallbackInfoReturnable<AxisAlignedBB> cir) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.tileEntityFrustumCulling) {
            return;
        }
        BlockPos p = this.getPos();
        Block type = this.getBlockType();
        if (p == null || type == null) {
            return;
        }
        if (this.purefps$cachedRenderBB != null && p == this.purefps$cachedBBPos && type == this.purefps$cachedBBBlock) {
            cir.setReturnValue(this.purefps$cachedRenderBB);
            return;
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
            return; // Délègue directement à l'implémentation Forge pour tout bloc moddé inconnu
        }

        this.purefps$cachedBBPos = p;
        this.purefps$cachedBBBlock = type;
        this.purefps$cachedRenderBB = bb;
        cir.setReturnValue(bb);
    }
}
