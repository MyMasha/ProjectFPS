package dev.purefps.core;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Interface d'accès au cache de lumière combinée (TTL 10 ticks) sur chaque TileEntity,
 * éliminant les lookups world.getCombinedLight(pos, 0) à chaque frame sur les coffres, pancartes et crânes.
 */
public interface TileEntityLightCache {

    int purefps$getCachedCombinedLight(World world, BlockPos pos);
}
