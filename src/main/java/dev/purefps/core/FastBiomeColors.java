package dev.purefps.core;

import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Arrays;

/**
 * Remplacement zéro-allocation de BiomeColorHelper (gazon, feuillage, eau) :
 * - Supprime les 5 allocations par bloc (2x BlockPos.add + Iterable + AbstractIterator + MutableBlockPos).
 * - Cache spatial ThreadLocal par colonne/tranche (accélère massivement les arbres, hautes herbes et océans).
 * - Cache local du multiplicateur d'eau par BiomeGenBase durant un rebuild de chunk (évite 35 000 BiomeEvent Forge par chunk d'océan).
 */
public final class FastBiomeColors {

    public static final int MODE_GRASS = 0;
    public static final int MODE_FOLIAGE = 1;
    public static final int MODE_WATER = 2;

    private static final int CACHE_SIZE = 256;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final class ThreadState {
        final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
        final long[] keys = new long[CACHE_SIZE];
        final int[] colors = new int[CACHE_SIZE];
        int lastAccessId = 0;

        // Petit cache associatif de 8 entrées pour BiomeGenBase.getWaterColorMultiplier() sur le chunk courant
        final BiomeGenBase[] waterBiomes = new BiomeGenBase[8];
        final int[] waterColors = new int[8];

        ThreadState() {
            Arrays.fill(keys, Long.MIN_VALUE);
        }

        void ensureAccess(IBlockAccess access) {
            int id = System.identityHashCode(access);
            if (id != lastAccessId) {
                lastAccessId = id;
                Arrays.fill(keys, Long.MIN_VALUE);
                Arrays.fill(waterBiomes, null);
            }
        }

        int resolveWaterMultiplier(BiomeGenBase biome) {
            int slot = biome.biomeID & 7;
            if (waterBiomes[slot] == biome) {
                return waterColors[slot];
            }
            int col = biome.getWaterColorMultiplier();
            waterBiomes[slot] = biome;
            waterColors[slot] = col;
            return col;
        }
    }

    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    private FastBiomeColors() {
    }

    public static void onChunkRebuildStart() {
        ThreadState st = STATE.get();
        st.lastAccessId = 0;
    }

    public static int getBlendedColor(IBlockAccess access, BlockPos pos, int mode) {
        ThreadState st = STATE.get();
        st.ensureAccess(access);

        final int cx = pos.getX();
        final int cy = pos.getY();
        final int cz = pos.getZ();

        // Pour l'eau, la couleur ne dépend pas de Y ; pour herbe/feuillage, BiomeGenBase.getFloatTemperature ne varie qu'au-dessus de Y=64
        final int effectiveY = (mode == MODE_WATER || cy <= 64) ? 0 : cy;
        final long key = packKey(cx, effectiveY, cz, mode);
        final int idx = mixHash(key) & CACHE_MASK;

        if (st.keys[idx] == key) {
            return st.colors[idx];
        }

        BlockPos.MutableBlockPos mpos = st.scratchPos;
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;

        for (int dx = -1; dx <= 1; dx++) {
            int x = cx + dx;
            for (int dz = -1; dz <= 1; dz++) {
                mpos.set(x, cy, cz + dz);
                BiomeGenBase biome = access.getBiomeGenForCoords(mpos);
                int c;
                if (mode == MODE_GRASS) {
                    c = biome.getGrassColorAtPos(mpos);
                } else if (mode == MODE_FOLIAGE) {
                    c = biome.getFoliageColorAtPos(mpos);
                } else {
                    c = st.resolveWaterMultiplier(biome);
                }
                rSum += (c & 0xFF0000) >> 16;
                gSum += (c & 0x00FF00) >> 8;
                bSum += (c & 0x0000FF);
            }
        }

        int blended = ((rSum / 9 & 0xFF) << 16) | ((gSum / 9 & 0xFF) << 8) | (bSum / 9 & 0xFF);
        st.keys[idx] = key;
        st.colors[idx] = blended;
        return blended;
    }

    public static int getUnbufferedColor(IBlockAccess access, BlockPos pos, int mode) {
        BlockPos.MutableBlockPos mpos = STATE.get().scratchPos;
        int cx = pos.getX();
        int cy = pos.getY();
        int cz = pos.getZ();
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                mpos.set(cx + dx, cy, cz + dz);
                BiomeGenBase biome = access.getBiomeGenForCoords(mpos);
                int c = (mode == MODE_GRASS)
                        ? biome.getGrassColorAtPos(mpos)
                        : (mode == MODE_FOLIAGE ? biome.getFoliageColorAtPos(mpos) : biome.getWaterColorMultiplier());
                rSum += (c & 0xFF0000) >> 16;
                gSum += (c & 0x00FF00) >> 8;
                bSum += (c & 0x0000FF);
            }
        }
        return ((rSum / 9 & 0xFF) << 16) | ((gSum / 9 & 0xFF) << 8) | (bSum / 9 & 0xFF);
    }

    static long packKey(int x, int y, int z, int mode) {
        long lx = ((long) x & 0x3FFFFFFL) << 38;
        long lz = ((long) z & 0x3FFFFFFL) << 12;
        long ly = ((long) y & 0x3FFL) << 2;
        return lx | lz | ly | (mode & 3L);
    }

    private static int mixHash(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return (int) k;
    }
}
