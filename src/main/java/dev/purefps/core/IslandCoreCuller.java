package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;

/**
 * Déchargeur haute vitesse du coeur des îles BedWars / SkyWars (MashClient 4.3) :
 * - Analyse en 1 passe zéro-allocation (long[64] ThreadLocal) chaque section 16x16x16 sur les threads workers.
 * - Si la section est 100% vide (vide inter-îles) : court-circuite immédiatement la boucle de 4 096 blocs (0 sommet).
 * - Si la section est 100% enfouie au coeur d'une île : décharge 100% de la géométrie (0 sommet, 0 VBO).
 * - Sinon, propage un BFS binaire depuis les bordures non-opaques : tout bloc ou cavité scellée au milieu
 *   d'une île (sans ouverture vers l'extérieur, y compris à travers verre/dalles/feuilles) est intégralement ignoré !
 */
public final class IslandCoreCuller {

    public static final class Context {
        public boolean active;
        public boolean emptySubchunk;
        public boolean fullyBuried;
        public boolean hasTileEntities;
        public int baseX;
        public int baseY;
        public int baseZ;

        public final long[] nonAirBits = new long[64];
        public final long[] opaqueBits = new long[64];
        public final long[] outwardOpenBoundaryBits = new long[64];
        public final long[] exteriorReachableBits = new long[64];
        public final long[] visibleBlockMask = new long[64];
        public final IBlockState[] cachedStates = new IBlockState[4096];
        final int[] bfsQueue = new int[4096];
        final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        public boolean isBlockVisibleFromExterior(int lx, int ly, int lz) {
            if (!this.active) {
                return true;
            }
            int idx = packLocal(lx, ly, lz);
            return (this.visibleBlockMask[idx >>> 6] & (1L << (idx & 63))) != 0L;
        }

        public boolean isNeighborExteriorReachable(int worldX, int worldY, int worldZ) {
            if (!this.active) {
                return true;
            }
            int lx = worldX - this.baseX;
            int ly = worldY - this.baseY;
            int lz = worldZ - this.baseZ;
            if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
                return true;
            }
            int idx = packLocal(lx, ly, lz);
            return (this.exteriorReachableBits[idx >>> 6] & (1L << (idx & 63))) != 0L;
        }

        public void reset() {
            this.active = false;
            this.emptySubchunk = false;
            this.fullyBuried = false;
            this.hasTileEntities = false;
        }
    }

    private static final ThreadLocal<Context> LOCAL_CTX = new ThreadLocal<Context>() {
        @Override
        protected Context initialValue() {
            return new Context();
        }
    };

    private static final SetVisibility ALL_OPAQUE_VISIBILITY = new SetVisibility();
    private static final SetVisibility ALL_OPEN_VISIBILITY;

    static {
        ALL_OPAQUE_VISIBILITY.setAllVisible(false);
        ALL_OPEN_VISIBILITY = new SetVisibility();
        ALL_OPEN_VISIBILITY.setAllVisible(true);
    }

    private IslandCoreCuller() {
    }

    public static Context currentContext() {
        return LOCAL_CTX.get();
    }

    public static void clearCurrentThread() {
        LOCAL_CTX.get().active = false;
    }

    public static SetVisibility allOpaqueVisibility() {
        return ALL_OPAQUE_VISIBILITY;
    }

    public static SetVisibility allOpenVisibility() {
        return ALL_OPEN_VISIBILITY;
    }

    public static int packLocal(int lx, int ly, int lz) {
        return lx | (lz << 4) | (ly << 8);
    }

    public static boolean isFaceVisible(int blockX, int blockY, int blockZ, EnumFacing face) {
        Context ctx = LOCAL_CTX.get();
        if (!ctx.active) {
            return true;
        }
        return ctx.isNeighborExteriorReachable(
                blockX + face.getFrontOffsetX(),
                blockY + face.getFrontOffsetY(),
                blockZ + face.getFrontOffsetZ()
        );
    }

    /**
     * Analyse complète d'une section 16x16x16 depuis le RegionRenderCache du thread worker.
     */
    public static Context analyzeWorldSubchunk(
            IBlockAccess regionCache,
            int baseX,
            int baseY,
            int baseZ,
            float camX,
            float camY,
            float camZ
    ) {
        Context ctx = LOCAL_CTX.get();
        ctx.reset();
        ctx.baseX = baseX;
        ctx.baseY = baseY;
        ctx.baseZ = baseZ;

        boolean cameraNear = camX >= (baseX - 2.0F) && camX <= (baseX + 18.0F)
                && camY >= (baseY - 2.0F) && camY <= (baseY + 18.0F)
                && camZ >= (baseZ - 2.0F) && camZ <= (baseZ + 18.0F);

        Arrays.fill(ctx.nonAirBits, 0L);
        Arrays.fill(ctx.opaqueBits, 0L);
        Arrays.fill(ctx.outwardOpenBoundaryBits, 0L);

        BlockPos.MutableBlockPos mpos = ctx.scanPos;
        int nonAirCount = 0;
        boolean anyTileEntity = false;
        boolean foundBed = false;

        for (int ly = 0; ly < 16; ly++) {
            int wy = baseY + ly;
            int yShift = ly << 8;
            for (int lz = 0; lz < 16; lz++) {
                int wz = baseZ + lz;
                int yzShift = yShift | (lz << 4);
                for (int lx = 0; lx < 16; lx++) {
                    int wx = baseX + lx;
                    mpos.set(wx, wy, wz);
                    IBlockState state = regionCache.getBlockState(mpos);
                    Block block = state.getBlock();
                    if (block.getRenderType() == -1 && !block.hasTileEntity(state)) {
                        continue;
                    }
                    nonAirCount++;
                    int idx = yzShift | lx;
                    ctx.cachedStates[idx] = state;
                    ctx.nonAirBits[idx >>> 6] |= (1L << (idx & 63));
                    if (block.isOpaqueCube()) {
                        ctx.opaqueBits[idx >>> 6] |= (1L << (idx & 63));
                    } else if (block instanceof BlockBed) {
                        foundBed = true;
                    }
                    if (block.hasTileEntity(state)) {
                        anyTileEntity = true;
                    }
                }
            }
        }

        if (foundBed) {
            SmartChunkUpdateScheduler.registerBedSubchunk(baseX >> 4, baseY >> 4, baseZ >> 4);
        }

        if (nonAirCount == 0) {
            ctx.emptySubchunk = true;
            return ctx;
        }

        // Vérifie les 6 faces extérieures (déjà pré-chargées dans le RegionRenderCache [-1..16])
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                if (!isWorldBlockOpaque(regionCache, mpos, baseX - 1, baseY + a, baseZ + b)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(0, a, b));
                }
                if (!isWorldBlockOpaque(regionCache, mpos, baseX + 16, baseY + a, baseZ + b)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(15, a, b));
                }
                if (baseY <= 0 || !isWorldBlockOpaque(regionCache, mpos, baseX + a, baseY - 1, baseZ + b)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(a, 0, b));
                }
                if (baseY + 16 >= 256 || !isWorldBlockOpaque(regionCache, mpos, baseX + a, baseY + 16, baseZ + b)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(a, 15, b));
                }
                if (!isWorldBlockOpaque(regionCache, mpos, baseX + a, baseY + b, baseZ - 1)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(a, b, 0));
                }
                if (!isWorldBlockOpaque(regionCache, mpos, baseX + a, baseY + b, baseZ + 16)) {
                    setBit(ctx.outwardOpenBoundaryBits, packLocal(a, b, 15));
                }
            }
        }

        analyzeBitsets(ctx.opaqueBits, ctx.outwardOpenBoundaryBits, cameraNear, anyTileEntity, ctx);
        if (ctx.fullyBuried) {
            PureFPSConfig.unloadedIslandCoresTotal++;
        }
        return ctx;
    }

    /**
     * Noyau algorithmique pur (100% testable hors Minecraft par JUnit) :
     * Calcule si la section est intégralement enfouie ou construit les masques de visibilité extérieure.
     */
    public static void analyzeBitsets(
            long[] opaqueBits,
            long[] outwardOpenBoundaryBits,
            boolean cameraNear,
            boolean hasTileEntities,
            Context out
    ) {
        out.hasTileEntities = hasTileEntities;
        if (out.opaqueBits != opaqueBits) {
            System.arraycopy(opaqueBits, 0, out.opaqueBits, 0, 64);
        }
        if (out.outwardOpenBoundaryBits != outwardOpenBoundaryBits) {
            System.arraycopy(outwardOpenBoundaryBits, 0, out.outwardOpenBoundaryBits, 0, 64);
        }
        Arrays.fill(out.exteriorReachableBits, 0L);
        Arrays.fill(out.visibleBlockMask, 0L);

        if (cameraNear) {
            out.active = false;
            out.fullyBuried = false;
            return;
        }

        int head = 0;
        int tail = 0;
        int[] queue = out.bfsQueue;
        int exposedBoundaryCells = 0;

        for (int ly = 0; ly < 16; ly++) {
            boolean yEdge = (ly == 0 || ly == 15);
            for (int lz = 0; lz < 16; lz++) {
                boolean zEdge = (lz == 0 || lz == 15);
                int lxStep = (yEdge || zEdge) ? 1 : 15;
                for (int lx = 0; lx < 16; lx += lxStep) {
                    int idx = packLocal(lx, ly, lz);
                    boolean selfOpaque = getBit(out.opaqueBits, idx);
                    boolean outwardOpen = getBit(out.outwardOpenBoundaryBits, idx);
                    if (!selfOpaque || outwardOpen) {
                        exposedBoundaryCells++;
                    }
                    if (!selfOpaque) {
                        setBit(out.exteriorReachableBits, idx);
                        queue[tail++] = idx;
                    } else if (outwardOpen) {
                        setBit(out.visibleBlockMask, idx);
                    }
                }
            }
        }

        if (exposedBoundaryCells == 0 && !hasTileEntities) {
            out.fullyBuried = true;
            out.active = true;
            return;
        }

        // Flood-fill BFS à travers toutes les cellules non-opaques reliées à l'extérieur
        while (head < tail) {
            int curr = queue[head++];
            int lx = curr & 15;
            int lz = (curr >>> 4) & 15;
            int ly = (curr >>> 8) & 15;

            if (lx > 0) tail = tryVisitNonOpaque(curr - 1, out, queue, tail);
            if (lx < 15) tail = tryVisitNonOpaque(curr + 1, out, queue, tail);
            if (lz > 0) tail = tryVisitNonOpaque(curr - 16, out, queue, tail);
            if (lz < 15) tail = tryVisitNonOpaque(curr + 16, out, queue, tail);
            if (ly > 0) tail = tryVisitNonOpaque(curr - 256, out, queue, tail);
            if (ly < 15) tail = tryVisitNonOpaque(curr + 256, out, queue, tail);
        }

        // Un bloc est potentiellement visible ssi lui-même ou l'un de ses 6 voisins est dans exteriorReachableBits
        for (int idx = 0; idx < 4096; idx++) {
            if (getBit(out.exteriorReachableBits, idx)) {
                setBit(out.visibleBlockMask, idx);
                int lx = idx & 15;
                int lz = (idx >>> 4) & 15;
                int ly = (idx >>> 8) & 15;
                if (lx > 0) setBit(out.visibleBlockMask, idx - 1);
                if (lx < 15) setBit(out.visibleBlockMask, idx + 1);
                if (lz > 0) setBit(out.visibleBlockMask, idx - 16);
                if (lz < 15) setBit(out.visibleBlockMask, idx + 16);
                if (ly > 0) setBit(out.visibleBlockMask, idx - 256);
                if (ly < 15) setBit(out.visibleBlockMask, idx + 256);
            }
        }

        out.fullyBuried = false;
        out.active = true;
    }

    private static int tryVisitNonOpaque(int neighborIdx, Context out, int[] queue, int tail) {
        int word = neighborIdx >>> 6;
        long mask = 1L << (neighborIdx & 63);
        if ((out.exteriorReachableBits[word] & mask) == 0L && (out.opaqueBits[word] & mask) == 0L) {
            out.exteriorReachableBits[word] |= mask;
            queue[tail++] = neighborIdx;
        }
        return tail;
    }

    private static boolean isWorldBlockOpaque(IBlockAccess access, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        return access.getBlockState(mpos).getBlock().isOpaqueCube();
    }

    private static boolean getBit(long[] bits, int idx) {
        return (bits[idx >>> 6] & (1L << (idx & 63))) != 0L;
    }

    private static void setBit(long[] bits, int idx) {
        bits[idx >>> 6] |= (1L << (idx & 63));
    }
}
