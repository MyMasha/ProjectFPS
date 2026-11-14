package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Ordonnanceur sans verrou (lock-free) de mise à jour des chunks BedWars / SkyWars (MashClient 4.4) :
 * - Zéro contention synchronized entre le thread de rendu OpenGL et les threads ChunkRenderWorker.
 * - Vide immédiatement RenderGlobal.chunksToUpdate vers une file latérale dédiée afin que Vanilla
 *   RenderGlobal.setupTerrain ne recalcule plus le graphe BFS des ~2 000 chunks à chaque frame !
 * - Priorité Temps-Réel (0 ms de délai) :
 *   1. Blocs posés ou cassés par les joueurs / TNT / boules de feu.
 *   2. Lits (BlockBed) et leurs sections voisines.
 *   3. Générateurs actifs (minerais EntityItem et hologrammes ArmorStand).
 *   4. Chunks non encore compilés (CompiledChunk.DUMMY) ou proches (< 24 blocs).
 */
public final class SmartChunkUpdateScheduler {

    private static final int TABLE_SIZE = 1024;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final int MAX_DEFERRED_QUEUE = 1024;

    private static final long PLAYER_EDIT_TTL_MS = 15_000L;
    private static final long GENERATOR_TTL_MS = 30_000L;
    private static final long STATIC_LIGHT_COOLDOWN_MS = 4_000L;

    private static final AtomicLongArray URGENT_KEYS = new AtomicLongArray(TABLE_SIZE);
    private static final AtomicLongArray URGENT_EXPIRY = new AtomicLongArray(TABLE_SIZE);

    private static final int BED_SIZE = 512;
    private static final int BED_MASK = BED_SIZE - 1;
    private static final AtomicLongArray BED_KEYS = new AtomicLongArray(BED_SIZE);

    private static final int LIGHT_SIZE = 512;
    private static final int LIGHT_MASK = LIGHT_SIZE - 1;
    private static final AtomicLongArray STATIC_LIGHT_KEYS = new AtomicLongArray(LIGHT_SIZE);
    private static final AtomicLongArray STATIC_LIGHT_LAST_MS = new AtomicLongArray(LIGHT_SIZE);

    // Accédé exclusivement sur le thread principal de rendu (et nettoyé dans clear())
    private static final ArrayDeque<RenderChunk> DEFERRED_STATIC_QUEUE = new ArrayDeque<RenderChunk>(512);
    private static final Set<RenderChunk> DEFERRED_STATIC_SET =
            Collections.newSetFromMap(new IdentityHashMap<RenderChunk, Boolean>(512));

    private static int frameCounter = 0;

    static {
        clear();
    }

    private SmartChunkUpdateScheduler() {
    }

    public static void clear() {
        for (int i = 0; i < TABLE_SIZE; i++) {
            URGENT_KEYS.set(i, EMPTY_KEY);
            URGENT_EXPIRY.set(i, 0L);
        }
        for (int i = 0; i < BED_SIZE; i++) {
            BED_KEYS.set(i, EMPTY_KEY);
        }
        for (int i = 0; i < LIGHT_SIZE; i++) {
            STATIC_LIGHT_KEYS.set(i, EMPTY_KEY);
            STATIC_LIGHT_LAST_MS.set(i, 0L);
        }
        synchronized (DEFERRED_STATIC_QUEUE) {
            DEFERRED_STATIC_QUEUE.clear();
            DEFERRED_STATIC_SET.clear();
        }
        frameCounter = 0;
    }

    public static int getDeferredStaticCount() {
        synchronized (DEFERRED_STATIC_QUEUE) {
            return DEFERRED_STATIC_QUEUE.size();
        }
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
        putUrgentLockFree(packSubchunkKey(cx, cy, cz), expiry);
        putUrgentLockFree(packSubchunkKey(cx, cy - 1, cz), expiry);
        putUrgentLockFree(packSubchunkKey(cx, cy + 1, cz), expiry);
    }

    public static void registerBedSubchunk(int cx, int cy, int cz) {
        putBedLockFree(packSubchunkKey(cx, cy, cz));
        putBedLockFree(packSubchunkKey(cx - 1, cy, cz));
        putBedLockFree(packSubchunkKey(cx + 1, cy, cz));
        putBedLockFree(packSubchunkKey(cx, cy, cz - 1));
        putBedLockFree(packSubchunkKey(cx, cy, cz + 1));
        putBedLockFree(packSubchunkKey(cx, cy - 1, cz));
        putBedLockFree(packSubchunkKey(cx, cy + 1, cz));
    }

    private static void markUrgentWithNeighbors(int cx, int cy, int cz, long expiryMs) {
        putUrgentLockFree(packSubchunkKey(cx, cy, cz), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx - 1, cy, cz), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx + 1, cy, cz), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx, cy - 1, cz), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx, cy + 1, cz), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx, cy, cz - 1), expiryMs);
        putUrgentLockFree(packSubchunkKey(cx, cy, cz + 1), expiryMs);
    }

    public static boolean isUrgentSubchunk(int cx, int cy, int cz, long nowMs) {
        long key = packSubchunkKey(cx, cy, cz);
        if (containsBedLockFree(key)) {
            return true;
        }
        int slot = mixHash(key) & TABLE_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & TABLE_MASK;
            long k = URGENT_KEYS.get(idx);
            if (k == EMPTY_KEY) {
                return false;
            }
            if (k == key) {
                return URGENT_EXPIRY.get(idx) >= nowMs;
            }
        }
        return false;
    }

    /**
     * Décide si une notification purement lumineuse (notifyLightSet) doit invalider un chunk déjà compilé.
     */
    public static boolean shouldAllowLightRebuild(int blockX, int blockY, int blockZ, long nowMs) {
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
            long k = STATIC_LIGHT_KEYS.get(idx);
            if (k == key) {
                long prev = STATIC_LIGHT_LAST_MS.get(idx);
                if (nowMs - prev < STATIC_LIGHT_COOLDOWN_MS) {
                    PureFPSConfig.throttledStaticUpdatesTotal++;
                    return false;
                }
                STATIC_LIGHT_LAST_MS.set(idx, nowMs);
                return true;
            }
            if (k == EMPTY_KEY) {
                targetIdx = idx;
                break;
            }
        }
        STATIC_LIGHT_KEYS.set(targetIdx, key);
        STATIC_LIGHT_LAST_MS.set(targetIdx, nowMs);
        return true;
    }

    /**
     * Exécute l'ordonnancement à 2 vitesses dans RenderGlobal.updateChunks :
     * - Passe 1 : les chunks urgents partent immédiatement vers le ChunkRenderDispatcher ; les chunks
     *   statiques déjà compilés sont extraits hors de chunksToUpdate dans DEFERRED_STATIC_QUEUE afin que
     *   chunksToUpdate redevienne vide immédiatement (évitant le recalcul BFS complet de setupTerrain !).
     * - Passe 2 : écoule paisiblement 1 chunk statique toutes les 2–4 frames hors menu.
     */
    public static void dispatchPrioritizedUpdates(
            Set<RenderChunk> chunksToUpdate,
            ChunkRenderDispatcher dispatcher,
            long finishTimeNano,
            Entity viewer,
            boolean menuDimmed
    ) {
        frameCounter++;
        long nowMs = System.currentTimeMillis();
        double vx = viewer != null ? viewer.posX : 0.0D;
        double vy = viewer != null ? viewer.posY : 0.0D;
        double vz = viewer != null ? viewer.posZ : 0.0D;

        if (chunksToUpdate != null && !chunksToUpdate.isEmpty()) {
            Iterator<RenderChunk> it = chunksToUpdate.iterator();
            boolean budgetExhausted = false;
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
                    rc.setNeedsUpdate(false);
                    it.remove();
                    synchronized (DEFERRED_STATIC_QUEUE) {
                        if (DEFERRED_STATIC_QUEUE.size() < MAX_DEFERRED_QUEUE && DEFERRED_STATIC_SET.add(rc)) {
                            DEFERRED_STATIC_QUEUE.addLast(rc);
                        }
                    }
                    continue;
                }

                if (budgetExhausted) {
                    continue;
                }

                if (!dispatcher.updateChunkLater(rc)) {
                    budgetExhausted = true;
                    continue;
                }
                rc.setNeedsUpdate(false);
                it.remove();
                synchronized (DEFERRED_STATIC_QUEUE) {
                    DEFERRED_STATIC_SET.remove(rc);
                }

                if (finishTimeNano != 0L && !uncompiled && (finishTimeNano - System.nanoTime()) < 0L) {
                    budgetExhausted = true;
                }
            }
        }

        if (menuDimmed) {
            return;
        }
        int interval = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 4 : 2;
        if ((frameCounter % interval) != 0) {
            return;
        }
        if (finishTimeNano != 0L && (finishTimeNano - System.nanoTime()) < 0L) {
            return;
        }

        RenderChunk deferred = null;
        synchronized (DEFERRED_STATIC_QUEUE) {
            while (!DEFERRED_STATIC_QUEUE.isEmpty()) {
                RenderChunk candidate = DEFERRED_STATIC_QUEUE.pollFirst();
                if (candidate != null && DEFERRED_STATIC_SET.remove(candidate)) {
                    deferred = candidate;
                    break;
                }
            }
        }
        if (deferred != null) {
            if (!dispatcher.updateChunkLater(deferred)) {
                synchronized (DEFERRED_STATIC_QUEUE) {
                    if (DEFERRED_STATIC_SET.add(deferred)) {
                        DEFERRED_STATIC_QUEUE.addFirst(deferred);
                    }
                }
            }
        }
    }

    private static void putUrgentLockFree(long key, long expiryMs) {
        int slot = mixHash(key) & TABLE_MASK;
        int oldestIdx = slot;
        long oldestExp = Long.MAX_VALUE;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & TABLE_MASK;
            long k = URGENT_KEYS.get(idx);
            if (k == key) {
                long prev = URGENT_EXPIRY.get(idx);
                if (expiryMs > prev) {
                    URGENT_EXPIRY.set(idx, expiryMs);
                }
                return;
            }
            if (k == EMPTY_KEY) {
                if (URGENT_KEYS.compareAndSet(idx, EMPTY_KEY, key) || URGENT_KEYS.get(idx) == key) {
                    URGENT_EXPIRY.set(idx, expiryMs);
                    return;
                }
            }
            long exp = URGENT_EXPIRY.get(idx);
            if (exp < oldestExp) {
                oldestExp = exp;
                oldestIdx = idx;
            }
        }
        URGENT_EXPIRY.set(oldestIdx, expiryMs);
        URGENT_KEYS.set(oldestIdx, key);
    }

    private static void putBedLockFree(long key) {
        int slot = mixHash(key) & BED_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & BED_MASK;
            long k = BED_KEYS.get(idx);
            if (k == key) {
                return;
            }
            if (k == EMPTY_KEY) {
                if (BED_KEYS.compareAndSet(idx, EMPTY_KEY, key) || BED_KEYS.get(idx) == key) {
                    return;
                }
            }
        }
        BED_KEYS.set(slot, key);
    }

    private static boolean containsBedLockFree(long key) {
        int slot = mixHash(key) & BED_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & BED_MASK;
            long k = BED_KEYS.get(idx);
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
