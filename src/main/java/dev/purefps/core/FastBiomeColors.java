package dev.purefps.core;

import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Arrays;

/**
 * Remplacement zéro-allocation de BiomeColorHelper (gazon, feuillage, eau) :
 * - Supprime les 5 allocations par bloc (2x BlockPos.add + Iterable + AbstractIterator + MutableBlockPos).
 * - Cache direct 18x18 BiomeGenBase[324] centré sur le chunk courant (0 collision de hachage pour les
 *   9 voisins dx/dz in [-1..1] de chaque bloc du chunk), couplé à un cache de couleurs 16x16 par mode.
 * - Repli propre sur cache associatif 256 entrées pour les appels hors-chunk (items, particules, entités).
 */
public final class FastBiomeColors {

    public static final int MODE_GRASS = 0;
    public static final int MODE_FOLIAGE = 1;
    public static final int MODE_WATER = 2;

    private static final int GRID_DIM = 18;
    private static final int GRID_SIZE = GRID_DIM * GRID_DIM; // 324 entrées [-1..16] x [-1..16]
    private static final int CACHE_SIZE = 256;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final class ThreadState {
        final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

        // Cache direct 18x18 de BiomeGenBase sur le chunk en cours de compilation (0 collision)
        boolean chunkGridActive = false;
        int chunkBaseX = 0;
        int chunkBaseZ = 0;
        final BiomeGenBase[] biomeGrid = new BiomeGenBase[GRID_SIZE];

        // Cache direct 16x16 des couleurs moyennées 3x3 pour Y<=64 / Eau (3 modes x 256 colonnes = 768 ints)
        final int[] colColorCache = new int[3 * 256];

        // Fallback hors-chunk / Y > 64
        final long[] keys = new long[CACHE_SIZE];
        final int[] colors = new int[CACHE_SIZE];
        int lastAccessId = 0;

        final BiomeGenBase[] waterBiomes = new BiomeGenBase[8];
        final int[] waterColors = new int[8];

        ThreadState() {
            Arrays.fill(keys, Long.MIN_VALUE);
            Arrays.fill(colColorCache, -1);
        }

        void resetForChunk(int baseX, int baseZ) {
            this.chunkGridActive = true;
            this.chunkBaseX = baseX;
            this.chunkBaseZ = baseZ;
            this.lastAccessId = 0;
            Arrays.fill(this.biomeGrid, null);
            Arrays.fill(this.colColorCache, -1);
            Arrays.fill(this.keys, Long.MIN_VALUE);
            Arrays.fill(this.waterBiomes, null);
        }

        void ensureAccess(IBlockAccess access) {
            int id = System.identityHashCode(access);
            if (id != lastAccessId) {
                lastAccessId = id;
                Arrays.fill(keys, Long.MIN_VALUE);
                Arrays.fill(waterBiomes, null);
                if (chunkGridActive) {
                    Arrays.fill(biomeGrid, null);
                    Arrays.fill(colColorCache, -1);
                }
            }
        }

        BiomeGenBase getBiomeCached(IBlockAccess access, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
            if (chunkGridActive) {
                int gx = x - chunkBaseX + 1;
                int gz = z - chunkBaseZ + 1;
                if ((gx | gz) >= 0 && gx < GRID_DIM && gz < GRID_DIM) {
                    int gIdx = gz * GRID_DIM + gx;
                    BiomeGenBase b = biomeGrid[gIdx];
                    if (b == null) {
                        mpos.set(x, y, z);
                        b = access.getBiomeGenForCoords(mpos);
                        biomeGrid[gIdx] = b;
                    }
                    return b;
                }
            }
            mpos.set(x, y, z);
            return access.getBiomeGenForCoords(mpos);
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

    private static final ThreadLocal<ThreadState> STATE = new ThreadLocal<ThreadState>() {
        @Override
        protected ThreadState initialValue() {
            return new ThreadState();
        }
    };

    private FastBiomeColors() {
    }

    public static void onChunkRebuildStart() {
        ThreadState st = STATE.get();
        st.chunkGridActive = false;
        st.lastAccessId = 0;
    }

    public static void onChunkRebuildStart(int chunkBaseX, int chunkBaseZ) {
        STATE.get().resetForChunk(chunkBaseX, chunkBaseZ);
    }

    public static int getBlendedColor(IBlockAccess access, BlockPos pos, int mode) {
        ThreadState st = STATE.get();
        st.ensureAccess(access);

        final int cx = pos.getX();
        final int cy = pos.getY();
        final int cz = pos.getZ();

        // Chemin direct 0-collision dans la colonne 16x16 du chunk courant (Eau ou Y <= 64)
        final boolean flatY = (mode == MODE_WATER || cy <= 64);
        int directColSlot = -1;
        if (flatY && st.chunkGridActive) {
            int lx = cx - st.chunkBaseX;
            int lz = cz - st.chunkBaseZ;
            if ((lx | lz) >= 0 && lx < 16 && lz < 16) {
                directColSlot = (mode << 8) | (lz << 4) | lx;
                int cached = st.colColorCache[directColSlot];
                if (cached != -1) {
                    return cached;
                }
            }
        }

        final int effectiveY = flatY ? 0 : cy;
        long key = 0L;
        int hashIdx = 0;
        if (directColSlot < 0) {
            key = packKey(cx, effectiveY, cz, mode);
            hashIdx = mixHash(key) & CACHE_MASK;
            if (st.keys[hashIdx] == key) {
                return st.colors[hashIdx];
            }
        }

        BlockPos.MutableBlockPos mpos = st.scratchPos;
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;

        for (int dx = -1; dx <= 1; dx++) {
            int x = cx + dx;
            for (int dz = -1; dz <= 1; dz++) {
                int z = cz + dz;
                BiomeGenBase biome = st.getBiomeCached(access, mpos, x, cy, z);
                int c;
                if (mode == MODE_GRASS) {
                    mpos.set(x, cy, z);
                    c = biome.getGrassColorAtPos(mpos);
                } else if (mode == MODE_FOLIAGE) {
                    mpos.set(x, cy, z);
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
        if (directColSlot >= 0) {
            st.colColorCache[directColSlot] = blended;
        } else {
            st.keys[hashIdx] = key;
            st.colors[hashIdx] = blended;
        }
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
