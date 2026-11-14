package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/**
 * Ordonnanceur intelligent de mise à jour des chunks BedWars / SkyWars (MashClient 4.3) :
 * - Priorité Temps-Réel (0 ms de délai) :
 *   1. Nouveaux blocs posés ou cassés par les joueurs / TNT / boules de feu (S23/S22).
 *   2. Lits (BlockBed) et leurs sections voisines (toujours prioritaires).
 *   3. Générateurs actifs (apparition de lingots/diamants/émeraudes et hologrammes ArmorStand).
 *   4. Chunks non encore compilés (CompiledChunk.DUMMY) pour zéro trou visuel.
 * - Économie maximale sur le décor statique :
 *   - Filtre les propagations lumineuses inutiles (notifyLightSet) sur les îles statiques déjà compilées.
 *   - Échelonne les recompilations non-urgentes du décor statique à <= 1 chunk toutes les 4 frames.
 */
public final class SmartChunkUpdateScheduler {

    private static final int TABLE_SIZE = 1024;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final long EMPTY_KEY = Long.MIN_VALUE;

    private static final long PLAYER_EDIT_TTL_MS = 15_000L;
    private static final long GENERATOR_TTL_MS = 30_000L;
    private static final long STATIC_LIGHT_COOLDOWN_MS = 4_000L;

    private static final long[] URGENT_KEYS = new long[TABLE_SIZE];
    private static final long[] URGENT_EXPIRY = new long[TABLE_SIZE];

    private static final long[] BED_KEYS = new long[512];
    private static final int BED_MASK = BED_KEYS.length - 1;

    private static final long[] STATIC_LIGHT_KEYS = new long[512];
    private static final long[] STATIC_LIGHT_LAST_MS = new long[512];
    private static final int LIGHT_MASK = STATIC_LIGHT_KEYS.length - 1;

    private static int frameCounter = 0;

    static {
        clear();
    }

    private SmartChunkUpdateScheduler() {
    }

    public static synchronized void clear() {
        Arrays.fill(URGENT_KEYS, EMPTY_KEY);
        Arrays.fill(URGENT_EXPIRY, 0L);
        Arrays.fill(BED_KEYS, EMPTY_KEY);
        Arrays.fill(STATIC_LIGHT_KEYS, EMPTY_KEY);
        Arrays.fill(STATIC_LIGHT_LAST_MS, 0L);
        frameCounter = 0;
    }

    public static long packSubchunkKey(int cx, int cy, int cz) {
        return (((long) cx & 0x3FFFFFL) << 42)
                | (((long) cz & 0x3FFFFFL) << 20)
                | ((long) cy & 0xFFFFFL);
    }

    public static void markDynamicBlockChange(int blockX, int blockY, int blockZ, boolean isBed) {
        long now = System.currentTimeMillis();
        int cx = blockX >> 4;
        int cy = blockY >> 4;
        int cz = blockZ >> 4;
        if (isBed) {
            registerBedSubchunk(cx, cy, cz);
        }
        markUrgentWithNeighbors(cx, cy, cz, now + PLAYER_EDIT_TTL_MS);
    }

    public static void markGeneratorActivity(double x, double y, double z) {
        long expiry = System.currentTimeMillis() + GENERATOR_TTL_MS;
        int cx = ((int) Math.floor(x)) >> 4;
        int cy = ((int) Math.floor(y)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        synchronized (SmartChunkUpdateScheduler.class) {
            putUrgentLocked(packSubchunkKey(cx, cy, cz), expiry);
            putUrgentLocked(packSubchunkKey(cx, cy - 1, cz), expiry);
            putUrgentLocked(packSubchunkKey(cx, cy + 1, cz), expiry);
        }
    }

    public static synchronized void registerBedSubchunk(int cx, int cy, int cz) {
        putBedLocked(packSubchunkKey(cx, cy, cz));
        putBedLocked(packSubchunkKey(cx - 1, cy, cz));
        putBedLocked(packSubchunkKey(cx + 1, cy, cz));
        putBedLocked(packSubchunkKey(cx, cy, cz - 1));
        putBedLocked(packSubchunkKey(cx, cy, cz + 1));
        putBedLocked(packSubchunkKey(cx, cy - 1, cz));
        putBedLocked(packSubchunkKey(cx, cy + 1, cz));
    }

    private static synchronized void markUrgentWithNeighbors(int cx, int cy, int cz, long expiryMs) {
        putUrgentLocked(packSubchunkKey(cx, cy, cz), expiryMs);
        putUrgentLocked(packSubchunkKey(cx - 1, cy, cz), expiryMs);
        putUrgentLocked(packSubchunkKey(cx + 1, cy, cz), expiryMs);
        putUrgentLocked(packSubchunkKey(cx, cy - 1, cz), expiryMs);
        putUrgentLocked(packSubchunkKey(cx, cy + 1, cz), expiryMs);
        putUrgentLocked(packSubchunkKey(cx, cy, cz - 1), expiryMs);
        putUrgentLocked(packSubchunkKey(cx, cy, cz + 1), expiryMs);
    }

    public static synchronized boolean isUrgentSubchunk(int cx, int cy, int cz, long nowMs) {
        long key = packSubchunkKey(cx, cy, cz);
        if (containsBedLocked(key)) {
            return true;
        }
        int slot = mixHash(key) & TABLE_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & TABLE_MASK;
            long k = URGENT_KEYS[idx];
            if (k == EMPTY_KEY) {
                return false;
            }
            if (k == key) {
                return URGENT_EXPIRY[idx] >= nowMs;
            }
        }
        return false;
    }

    /**
     * Décide si une notification purement lumineuse (notifyLightSet) doit invalider un chunk déjà compilé.
     */
    public static synchronized boolean shouldAllowLightRebuild(int blockX, int blockY, int blockZ, long nowMs) {
        int cx = blockX >> 4;
        int cy = blockY >> 4;
        int cz = blockZ >> 4;
        if (isUrgentSubchunk(cx, cy, cz, nowMs)) {
            return true;
        }
        if (PureFPSConfig.activeProfile == PureFPSProfile.FPS) {
            PureFPSConfig.throttledStaticUpdatesTotal++;
            return false;
        }

        long key = packSubchunkKey(cx, cy, cz);
        int slot = mixHash(key) & LIGHT_MASK;
        int targetIdx = slot;
        for (int probe = 0; probe < 4; probe++) {
            int idx = (slot + probe) & LIGHT_MASK;
            long k = STATIC_LIGHT_KEYS[idx];
            if (k == key) {
                if (nowMs - STATIC_LIGHT_LAST_MS[idx] < STATIC_LIGHT_COOLDOWN_MS) {
                    PureFPSConfig.throttledStaticUpdatesTotal++;
                    return false;
                }
                STATIC_LIGHT_LAST_MS[idx] = nowMs;
                return true;
            }
            if (k == EMPTY_KEY) {
                targetIdx = idx;
                break;
            }
        }
        STATIC_LIGHT_KEYS[targetIdx] = key;
        STATIC_LIGHT_LAST_MS[targetIdx] = nowMs;
        return true;
    }

    /**
     * Exécute l'ordonnancement à 2 vitesses dans RenderGlobal.updateChunks :
     * - Passe 1 : tous les chunks urgents (blocs joueurs, lits, générateurs, proches ou non compilés) partent immédiatement.
     * - Passe 2 : les chunks statiques lointains sont raréfiés (0 pendant l'ouverture d'un menu, <= 1 toutes les 2-4 frames sinon).
     */
    public static void dispatchPrioritizedUpdates(
            Set<RenderChunk> chunksToUpdate,
            ChunkRenderDispatcher dispatcher,
            long finishTimeNano,
            Entity viewer,
            boolean menuDimmed
    ) {
        if (chunksToUpdate == null || chunksToUpdate.isEmpty()) {
            return;
        }
        frameCounter++;
        long nowMs = System.currentTimeMillis();
        double vx = viewer != null ? viewer.posX : 0.0D;
        double vy = viewer != null ? viewer.posY : 0.0D;
        double vz = viewer != null ? viewer.posZ : 0.0D;

        // Passe 1 : Chunks urgents (blocs posés/cassés, lits, générateurs, DUMMY non compilés, proximité immédiate)
        Iterator<RenderChunk> it = chunksToUpdate.iterator();
        while (it.hasNext()) {
            RenderChunk rc = it.next();
            if (rc == null) {
                it.remove();
                continue;
            }
            BlockPos pos = rc.getPosition();
            boolean uncompiled = (rc.getCompiledChunk() == CompiledChunk.DUMMY);
            boolean urgent = uncompiled;
            if (!urgent && pos != null) {
                int cx = pos.getX() >> 4;
                int cy = pos.getY() >> 4;
                int cz = pos.getZ() >> 4;
                if (isUrgentSubchunk(cx, cy, cz, nowMs)) {
                    urgent = true;
                } else if (viewer != null) {
                    double dx = (pos.getX() + 8.0D) - vx;
                    double dy = (pos.getY() + 8.0D) - vy;
                    double dz = (pos.getZ() + 8.0D) - vz;
                    if (dx * dx + dy * dy + dz * dz <= 576.0D) {
                        urgent = true;
                    }
                }
            }

            if (!urgent) {
                continue;
            }

            if (!dispatcher.updateChunkLater(rc)) {
                return;
            }
            rc.setNeedsUpdate(false);
            it.remove();

            if (finishTimeNano != 0L && !uncompiled && (finishTimeNano - System.nanoTime()) < 0L) {
                return;
            }
        }

        // Passe 2 : Décor statique déjà compilé -> suspendu dans les menus, sinon raréfié
        if (menuDimmed || chunksToUpdate.isEmpty()) {
            return;
        }
        int interval = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 4 : 2;
        if ((frameCounter % interval) != 0) {
            return;
        }
        if (finishTimeNano != 0L && (finishTimeNano - System.nanoTime()) < 0L) {
            return;
        }

        Iterator<RenderChunk> staticIt = chunksToUpdate.iterator();
        if (staticIt.hasNext()) {
            RenderChunk rc = staticIt.next();
            if (rc != null && dispatcher.updateChunkLater(rc)) {
                rc.setNeedsUpdate(false);
                staticIt.remove();
            }
        }
    }

    private static void putUrgentLocked(long key, long expiryMs) {
        int slot = mixHash(key) & TABLE_MASK;
        int oldestIdx = slot;
        long oldestExp = Long.MAX_VALUE;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & TABLE_MASK;
            long k = URGENT_KEYS[idx];
            if (k == EMPTY_KEY || k == key) {
                URGENT_KEYS[idx] = key;
                if (expiryMs > URGENT_EXPIRY[idx]) {
                    URGENT_EXPIRY[idx] = expiryMs;
                }
                return;
            }
            if (URGENT_EXPIRY[idx] < oldestExp) {
                oldestExp = URGENT_EXPIRY[idx];
                oldestIdx = idx;
            }
        }
        URGENT_KEYS[oldestIdx] = key;
        URGENT_EXPIRY[oldestIdx] = expiryMs;
    }

    private static void putBedLocked(long key) {
        int slot = mixHash(key) & BED_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & BED_MASK;
            long k = BED_KEYS[idx];
            if (k == EMPTY_KEY || k == key) {
                BED_KEYS[idx] = key;
                return;
            }
        }
        BED_KEYS[slot] = key;
    }

    private static boolean containsBedLocked(long key) {
        int slot = mixHash(key) & BED_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & BED_MASK;
            long k = BED_KEYS[idx];
            if (k == EMPTY_KEY) {
                return false;
            }
            if (k == key) {
                return true;
            }
        }
        return false;
    }

    private static int mixHash(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (z ^ (z >>> 33));
    }
}
