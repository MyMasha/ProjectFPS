package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastBiomeColors;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Remplace l'itérateur de boîte 3x3 de BiomeColorHelper en @Overwrite zéro-allocation
 * (supprime 5 objets par bloc coloré + supprime CallbackInfoReturnable/Integer.valueOf).
 */
@Mixin(BiomeColorHelper.class)
public class MixinBiomeColorHelper {

    /**
     * @author PureFPS
     * @reason Boucle 3x3 déroulée + cache spatial par colonne sans allocation
     */
    @Overwrite
    public static int getGrassColorAtPos(IBlockAccess access, BlockPos pos) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors)
                ? FastBiomeColors.getBlendedColor(access, pos, FastBiomeColors.MODE_GRASS)
                : FastBiomeColors.getUnbufferedColor(access, pos, FastBiomeColors.MODE_GRASS);
    }

    /**
     * @author PureFPS
     * @reason Boucle 3x3 déroulée + cache spatial par colonne sans allocation
     */
    @Overwrite
    public static int getFoliageColorAtPos(IBlockAccess access, BlockPos pos) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors)
                ? FastBiomeColors.getBlendedColor(access, pos, FastBiomeColors.MODE_FOLIAGE)
                : FastBiomeColors.getUnbufferedColor(access, pos, FastBiomeColors.MODE_FOLIAGE);
    }

    /**
     * @author PureFPS
     * @reason Boucle 3x3 déroulée + cache spatial par colonne sans allocation
     */
    @Overwrite
    public static int getWaterColorAtPos(IBlockAccess access, BlockPos pos) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors)
                ? FastBiomeColors.getBlendedColor(access, pos, FastBiomeColors.MODE_WATER)
                : FastBiomeColors.getUnbufferedColor(access, pos, FastBiomeColors.MODE_WATER);
    }
}
