package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.chunk.Chunk;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Ordonnanceur de chunks "Bake Once + Freeze" (MashClient 4.4.2) :
 * 1. À la connexion, compile l'intégralité de la carte une seule fois (360°, y compris hors frustum
 *    dès que le chunk et ses voisins sont reçus du serveur) afin d'éliminer tout pop-in ou pic de lag
 *    en se retournant.
 * 2. Une fois compilé, un chunk est totalement GELÉ (0 recompilation CPU/GPU, 0 recalcul lumineux)
 *    SAUF dans deux cas :
 *    - Un joueur (local ou distant) se trouve dans ce chunk ou à 1 chunk adjacent (grille 3x3 autour
 *      de chaque joueur).
 *    - Une modification dynamique du monde s'y produit (Fireball, TNT, explosion S27, modification
 *      serveur S23/S22, piston, lit cassé/défendu, bloc posé/cassé).
 */
public final class SmartChunkUpdateScheduler {

    private static final int TABLE_SIZE = 2048;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final long EMPTY_KEY = Long.MIN_VALUE;

    private static final int COL_TABLE_SIZE = 1024;
    private static final int COL_TABLE_MASK = COL_TABLE_SIZE - 1;

    private static final long DYNAMIC_MOD_TTL_MS = 8_000L;
    private static final long BED_MOD_TTL_MS = 14_000L;
    private static final long EXPLOSION_TTL_MS = 10_000L;
    private static final long PROJECTILE_TTL_MS = 4_000L;
    private static final long INITIAL_COLUMN_GRACE_MS = 2_500L;
    private static final long STATIC_LIGHT_COOLDOWN_MS = 1_500L;
    private static final long IN_FLIGHT_BAKE_COOLDOWN_MS = 500L;
    private static final long PERIMETER_NEIGHBOR_WAIT_MS = 650L;

    private static final int INITIAL_SCAN_SLOTS_PER_FRAME = 128;
    private static final int INITIAL_DISPATCH_MAX_PER_FRAME = 6;
    private static final int INITIAL_DISPATCH_MIN_PER_FRAME = 2;

    private static final AtomicLongArray URGENT_KEYS = new AtomicLongArray(TABLE_SIZE);
    private static final AtomicLongArray URGENT_EXPIRY = new AtomicLongArray(TABLE_SIZE);

    private static final AtomicLongArray INITIAL_COL_KEYS = new AtomicLongArray(COL_TABLE_SIZE);
    private static final AtomicLongArray INITIAL_COL_EXPIRY = new AtomicLongArray(COL_TABLE_SIZE);

    private static final int BED_SIZE = 512;
    private static final int BED_MASK = BED_SIZE - 1;
    private static final AtomicLongArray BED_KEYS = new AtomicLongArray(BED_SIZE);

    private static final int LIGHT_SIZE = 512;
    private static final int LIGHT_MASK = LIGHT_SIZE - 1;
    private static final AtomicLongArray STATIC_LIGHT_KEYS = new AtomicLongArray(LIGHT_SIZE);
    private static final AtomicLongArray STATIC_LIGHT_LAST_MS = new AtomicLongArray(LIGHT_SIZE);

    // État accédé sur le thread principal de rendu (ou par les tests unitaires)
    private static final int IN_FLIGHT_SIZE = 512;
    private static final int IN_FLIGHT_MASK = IN_FLIGHT_SIZE - 1;
    private static final long[] IN_FLIGHT_BAKE_KEYS = new long[IN_FLIGHT_SIZE];
    private static final long[] IN_FLIGHT_BAKE_EXPIRY = new long[IN_FLIGHT_SIZE];

    private static int[] activePlayerChunkX = new int[32];
    private static int[] activePlayerChunkZ = new int[32];
    private static int activePlayerCount = 0;

    private static int initialBakeCursor = 0;
    private static int cleanSweepSlots = 0;
    private static volatile boolean initialBakePending = true;
    private static volatile long lastChunkLoadMs = 0L;
    private static volatile int terrainMutationRevision = 1;
    private static long lastEntitySnapshotMs = 0L;

    static {
        clear();
    }

    private SmartChunkUpdateScheduler() {
    }

    public static int getTerrainMutationRevision() {
        return terrainMutationRevision;
    }

    public static void clear() {
        for (int i = 0; i < TABLE_SIZE; i++) {
            URGENT_KEYS.set(i, EMPTY_KEY);
            URGENT_EXPIRY.set(i, 0L);
        }
        for (int i = 0; i < COL_TABLE_SIZE; i++) {
            INITIAL_COL_KEYS.set(i, EMPTY_KEY);
            INITIAL_COL_EXPIRY.set(i, 0L);
        }
        for (int i = 0; i < BED_SIZE; i++) {
            BED_KEYS.set(i, EMPTY_KEY);
        }
        for (int i = 0; i < LIGHT_SIZE; i++) {
            STATIC_LIGHT_KEYS.set(i, EMPTY_KEY);
            STATIC_LIGHT_LAST_MS.set(i, 0L);
        }
        for (int i = 0; i < IN_FLIGHT_SIZE; i++) {
            IN_FLIGHT_BAKE_KEYS[i] = EMPTY_KEY;
            IN_FLIGHT_BAKE_EXPIRY[i] = 0L;
        }
        activePlayerCount = 0;
        initialBakeCursor = 0;
        cleanSweepSlots = 0;
        initialBakePending = true;
        lastChunkLoadMs = System.currentTimeMillis();
        lastEntitySnapshotMs = 0L;
        terrainMutationRevision++;
    }

    public static void onViewFrustumShifted() {
        cleanSweepSlots = 0;
        initialBakePending = true;
    }

    public static boolean hasPendingInitialBake() {
        return initialBakePending;
    }

    public static int getDeferredStaticCount() {
        return 0;
    }

    public static long packSubchunkKey(int cx, int cy, int cz) {
        return (((long) cx & 0x3FFFFFL) << 42)
                | (((long) cz & 0x3FFFFFL) << 20)
                | ((long) cy & 0xFFFFFL);
    }

    public static long packColumnKey(int cx, int cz) {
        return (((long) cx & 0xFFFFFFFFL) << 32) | ((long) cz & 0xFFFFFFFFL);
    }

    /**
     * Appelé à la réception d'un chunk serveur (S21PacketChunkData / S26PacketMapChunkBulk) :
     * autorise la compilation initiale du chunk et la mise à jour unique de couture avec ses 4 voisins.
     */
    public static void markChunkColumnLoaded(int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        long expiry = now + INITIAL_COLUMN_GRACE_MS;
        lastChunkLoadMs = now;
        cleanSweepSlots = 0;
        initialBakePending = true;
        terrainMutationRevision++;

        putColumnGraceLockFree(packColumnKey(chunkX, chunkZ), expiry);
        putColumnGraceLockFree(packColumnKey(chunkX - 1, chunkZ), expiry);
        putColumnGraceLockFree(packColumnKey(chunkX + 1, chunkZ), expiry);
        putColumnGraceLockFree(packColumnKey(chunkX, chunkZ - 1), expiry);
        putColumnGraceLockFree(packColumnKey(chunkX, chunkZ + 1), expiry);
    }

    public static boolean isInitialColumnGraceActive(int cx, int cz, long nowMs) {
        long key = packColumnKey(cx, cz);
        int slot = mixHash(key) & COL_TABLE_MASK;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & COL_TABLE_MASK;
            long k = INITIAL_COL_KEYS.get(idx);
            if (k == EMPTY_KEY) {
                return false;
            }
            if (k == key) {
                return INITIAL_COL_EXPIRY.get(idx) >= nowMs;
            }
        }
        return false;
    }

    public static void markDynamicBlockChange(int blockX, int blockY, int blockZ, boolean isBed) {
        long now = System.currentTimeMillis();
        terrainMutationRevision++;
        int cx = blockX >> 4;
        int cy = blockY >> 4;
        int cz = blockZ >> 4;
        if (isBed) {
            registerBedSubchunk(cx, cy, cz);
        }
        long ttl = (isBed || containsBedLockFree(packSubchunkKey(cx, cy, cz))) ? BED_MOD_TTL_MS : DYNAMIC_MOD_TTL_MS;
        markUrgentWithNeighbors(cx, cy, cz, now + ttl);
    }

    /**
     * Dégèle immédiatement les chunks touchés par une explosion (Fireball, TNT, Creeper, Lit) même à 100+ blocs.
     */
    public static void markExplosion(double x, double y, double z, float strength, List<BlockPos> affectedPositions) {
        long expiry = System.currentTimeMillis() + EXPLOSION_TTL_MS;
        terrainMutationRevision++;
        int centerCx = MathHelper.floor_double(x) >> 4;
        int centerCy = MathHelper.floor_double(y) >> 4;
        int centerCz = MathHelper.floor_double(z) >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    putUrgentLockFree(packSubchunkKey(centerCx + dx, centerCy + dy, centerCz + dz), expiry);
                }
            }
        }

        if (affectedPositions != null && !affectedPositions.isEmpty()) {
            int lastCx = Integer.MIN_VALUE;
            int lastCy = Integer.MIN_VALUE;
            int lastCz = Integer.MIN_VALUE;
            for (int i = 0, n = affectedPositions.size(); i < n; i++) {
                BlockPos pos = affectedPositions.get(i);
                if (pos == null) {
                    continue;
                }
                int cx = pos.getX() >> 4;
                int cy = pos.getY() >> 4;
                int cz = pos.getZ() >> 4;
                if (cx == lastCx && cy == lastCy && cz == lastCz) {
                    continue;
                }
                lastCx = cx;
                lastCy = cy;
                lastCz = cz;
                markUrgentWithNeighbors(cx, cy, cz, expiry);
            }
        }
    }

    /**
     * Dégèle par anticipation les chunks traversés par une Fireball, TNT amorcée, bloc tombant ou Dragon.
     */
    public static void markDestructiveEntityActivity(double x, double y, double z) {
        long expiry = System.currentTimeMillis() + PROJECTILE_TTL_MS;
        int cx = MathHelper.floor_double(x) >> 4;
        int cy = MathHelper.floor_double(y) >> 4;
        int cz = MathHelper.floor_double(z) >> 4;
        markUrgentWithNeighbors(cx, cy, cz, expiry);
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

    /**
     * Retourne true si (cx, cz) contient un joueur ou est à <= 1 chunk d'un joueur (zone active 3x3).
     */
    public static boolean isWithinActivePlayerRadius(
            int cx,
            int cz,
            int[] playerChunkX,
            int[] playerChunkZ,
            int playerCount
    ) {
        for (int i = 0; i < playerCount; i++) {
            if (Math.abs(cx - playerChunkX[i]) <= 1 && Math.abs(cz - playerChunkZ[i]) <= 1) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWithinOneChunkOfAnyPlayer(int cx, int cz) {
        return isWithinActivePlayerRadius(cx, cz, activePlayerChunkX, activePlayerChunkZ, activePlayerCount);
    }

    static void setActivePlayersForTest(int[] chunkX, int[] chunkZ, int count) {
        ensurePlayerCapacity(count);
        for (int i = 0; i < count; i++) {
            activePlayerChunkX[i] = chunkX[i];
            activePlayerChunkZ[i] = chunkZ[i];
        }
        activePlayerCount = count;
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
     * Décide si un sous-chunk DÉJÀ compilé a le droit d'être recompilé :
     * - Joueur présent dans le chunk ou à 1 chunk adjacent (3x3), OU
     * - Modification dynamique (Fireball, TNT, explosion, changement de bloc), OU
     * - Couture initiale juste après réception réseau du chunk voisin.
     * Sinon, le chunk reste strictement gelé.
     */
    public static boolean shouldRebuildCompiledSubchunk(int cx, int cy, int cz, long nowMs) {
        if (isWithinOneChunkOfAnyPlayer(cx, cz)) {
            return true;
        }
        if (isUrgentSubchunk(cx, cy, cz, nowMs)) {
            return true;
        }
        return isInitialColumnGraceActive(cx, cz, nowMs);
    }

    /**
     * Décide si une notification purement lumineuse (notifyLightSet) doit invalider un chunk déjà compilé.
     * Autorise les mises à jour de couture lumineuse pendant la grâce initiale du chunk, puis gèle les zones statiques.
     */
    public static boolean shouldAllowLightRebuild(int blockX, int blockY, int blockZ, long nowMs) {
        int cx = blockX >> 4;
        int cy = blockY >> 4;
        int cz = blockZ >> 4;
        if (isUrgentSubchunk(cx, cy, cz, nowMs) || isInitialColumnGraceActive(cx, cz, nowMs)) {
            return true;
        }
        if (!isWithinOneChunkOfAnyPlayer(cx, cz)) {
            PureFPSConfig.throttledStaticUpdatesTotal++;
            return false;
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

    public static void dispatchPrioritizedUpdates(
            Set<RenderChunk> chunksToUpdate,
            ViewFrustum viewFrustum,
            WorldClient world,
            ChunkRenderDispatcher dispatcher,
            long finishTimeNano,
            Entity viewer,
            boolean menuDimmed
    ) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastEntitySnapshotMs >= 50L || activePlayerCount == 0) {
            lastEntitySnapshotMs = nowMs;
            snapshotWorldPlayersAndProjectiles(world, viewer, nowMs);
        }

        long autonomousDeadline = System.nanoTime() + 750_000L;
        long effectiveDeadline = (finishTimeNano != 0L && finishTimeNano < autonomousDeadline)
                ? finishTimeNano
                : autonomousDeadline;

        if (chunksToUpdate != null && !chunksToUpdate.isEmpty()) {
            Iterator<RenderChunk> it = chunksToUpdate.iterator();
            int compiledDispatched = 0;
            while (it.hasNext()) {
                RenderChunk rc = it.next();
                if (rc == null) {
                    it.remove();
                    continue;
                }
                BlockPos pos = rc.getPosition();
                if (pos == null) {
                    rc.setNeedsUpdate(false);
                    it.remove();
                    continue;
                }

                int cx = pos.getX() >> 4;
                int cy = pos.getY() >> 4;
                int cz = pos.getZ() >> 4;

                // Ne compile jamais un EmptyChunk pas encore arrivé du serveur (évite de figer un maillage vide)
                if (world != null && !isClientChunkLoaded(world, cx, cz)) {
                    rc.setNeedsUpdate(false);
                    it.remove();
                    continue;
                }

                boolean uncompiled = (rc.getCompiledChunk() == CompiledChunk.DUMMY);
                boolean allow = uncompiled || shouldRebuildCompiledSubchunk(cx, cy, cz, nowMs);
                if (!allow) {
                    rc.setNeedsUpdate(false);
                    it.remove();
                    PureFPSConfig.throttledStaticUpdatesTotal++;
                    continue;
                }

                if (!dispatcher.updateChunkLater(rc)) {
                    break;
                }
                if (uncompiled) {
                    markInFlightBake(packSubchunkKey(cx, cy, cz), nowMs + IN_FLIGHT_BAKE_COOLDOWN_MS);
                } else {
                    compiledDispatched++;
                }
                rc.setNeedsUpdate(false);
                it.remove();

                if (!uncompiled
                        && compiledDispatched >= 4
                        && (effectiveDeadline - System.nanoTime()) < 0L) {
                    break;
                }
            }
        }

        if (!menuDimmed && initialBakePending) {
            stepInitialFullMapBake(viewFrustum, world, dispatcher, effectiveDeadline, nowMs);
        }
    }

    /**
     * Pré-compile une seule fois les chunks hors frustum (360° autour du joueur) dès que leurs données
     * réseau sont présentes, en sautant immédiatement les sous-chunks d'air (ExtendedBlockStorage null/vide).
     */
    private static void stepInitialFullMapBake(
            ViewFrustum viewFrustum,
            WorldClient world,
            ChunkRenderDispatcher dispatcher,
            long effectiveDeadline,
            long nowMs
    ) {
        if (viewFrustum == null || world == null || dispatcher == null) {
            return;
        }
        RenderChunk[] allChunks = viewFrustum.renderChunks;
        if (allChunks == null || allChunks.length == 0) {
            return;
        }

        int total = allChunks.length;
        int maxScan = Math.min(total, INITIAL_SCAN_SLOTS_PER_FRAME);
        boolean perimeterSettled = (nowMs - lastChunkLoadMs) >= PERIMETER_NEIGHBOR_WAIT_MS;
        int dispatched = 0;

        int cachedCx = Integer.MIN_VALUE;
        int cachedCz = Integer.MIN_VALUE;
        Chunk cachedChunk = null;
        boolean cachedLoaded = false;

        for (int step = 0; step < maxScan; step++) {
            int idx = initialBakeCursor;
            initialBakeCursor = (idx + 1 < total) ? (idx + 1) : 0;

            RenderChunk rc = allChunks[idx];
            if (rc == null || rc.getCompiledChunk() != CompiledChunk.DUMMY) {
                cleanSweepSlots++;
                continue;
            }
            if (rc.isNeedsUpdate()) {
                // Déjà pris en charge par chunksToUpdate (Passe 1)
                cleanSweepSlots = 0;
                continue;
            }

            BlockPos pos = rc.getPosition();
            if (pos == null) {
                cleanSweepSlots++;
                continue;
            }

            int cx = pos.getX() >> 4;
            int cy = pos.getY() >> 4;
            int cz = pos.getZ() >> 4;

            if (cx != cachedCx || cz != cachedCz) {
                cachedCx = cx;
                cachedCz = cz;
                cachedChunk = world.getChunkFromChunkCoords(cx, cz);
                cachedLoaded = (cachedChunk != null && !cachedChunk.isEmpty());
            }

            if (!cachedLoaded) {
                // Au-delà des chunks envoyés par le serveur (vide hors carte BedWars/SkyWars)
                cleanSweepSlots++;
                continue;
            }

            // Saute instantanément les sous-chunks 100 % air sans envoyer de tâche worker inutile
            if (cy < 0 || cy >= 16) {
                cleanSweepSlots++;
                continue;
            }
            net.minecraft.world.chunk.storage.ExtendedBlockStorage[] storages = cachedChunk.getBlockStorageArray();
            if (storages == null || storages[cy] == null || storages[cy].isEmpty()) {
                cleanSweepSlots++;
                continue;
            }

            // Un chunk chargé non-vide encore DUMMY empêche l'endormissement du pré-bake 360°
            cleanSweepSlots = 0;

            if (!perimeterSettled && !areCardinalNeighborsLoaded(world, cx, cz)) {
                continue;
            }

            long subKey = packSubchunkKey(cx, cy, cz);
            if (isInFlightBake(subKey, nowMs)) {
                continue;
            }

            if (!dispatcher.updateChunkLater(rc)) {
                break;
            }
            markInFlightBake(subKey, nowMs + IN_FLIGHT_BAKE_COOLDOWN_MS);
            rc.setNeedsUpdate(false);
            dispatched++;

            if (dispatched >= INITIAL_DISPATCH_MAX_PER_FRAME) {
                break;
            }
            if (dispatched >= INITIAL_DISPATCH_MIN_PER_FRAME
                    && effectiveDeadline != 0L
                    && (effectiveDeadline - System.nanoTime()) < 0L) {
                break;
            }
        }

        if (cleanSweepSlots >= total && (nowMs - lastChunkLoadMs) >= 1_000L) {
            initialBakePending = false;
        }
    }

    private static void snapshotWorldPlayersAndProjectiles(WorldClient world, Entity viewer, long nowMs) {
        if (world == null) {
            if (viewer != null) {
                ensurePlayerCapacity(1);
                activePlayerChunkX[0] = MathHelper.floor_double(viewer.posX) >> 4;
                activePlayerChunkZ[0] = MathHelper.floor_double(viewer.posZ) >> 4;
                activePlayerCount = 1;
            } else {
                activePlayerCount = 0;
            }
            return;
        }

        List<EntityPlayer> players = world.playerEntities;
        int pSize = players != null ? players.size() : 0;
        ensurePlayerCapacity(pSize + 1);
        int count = 0;
        boolean viewerIncluded = (viewer == null);

        for (int i = 0; i < pSize; i++) {
            EntityPlayer p = players.get(i);
            if (p == null || p.isDead) {
                continue;
            }
            if (p == viewer) {
                viewerIncluded = true;
            }
            activePlayerChunkX[count] = MathHelper.floor_double(p.posX) >> 4;
            activePlayerChunkZ[count] = MathHelper.floor_double(p.posZ) >> 4;
            count++;
        }

        if (!viewerIncluded && viewer != null) {
            activePlayerChunkX[count] = MathHelper.floor_double(viewer.posX) >> 4;
            activePlayerChunkZ[count] = MathHelper.floor_double(viewer.posZ) >> 4;
            count++;
        }
        activePlayerCount = count;

        List<Entity> entities = world.loadedEntityList;
        if (entities != null) {
            for (int i = 0, n = entities.size(); i < n; i++) {
                Entity e = entities.get(i);
                if (e == null || e.isDead) {
                    continue;
                }
                if (e instanceof EntityWither && e.isInvisible()) {
                    // Ignore les Withers invisibles utilisés comme BossBar sur Hypixel
                    continue;
                }
                if (e instanceof EntityFireball
                        || e instanceof EntityTNTPrimed
                        || e instanceof EntityFallingBlock
                        || e instanceof EntityDragon
                        || e instanceof EntityWither) {
                    markDestructiveEntityActivity(e.posX, e.posY, e.posZ);
                }
            }
        }
    }

    private static void ensurePlayerCapacity(int needed) {
        if (needed > activePlayerChunkX.length) {
            int nextCap = Math.max(needed, activePlayerChunkX.length * 2);
            activePlayerChunkX = new int[nextCap];
            activePlayerChunkZ = new int[nextCap];
        }
    }

    private static boolean isClientChunkLoaded(WorldClient world, int cx, int cz) {
        Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
        return chunk != null && !chunk.isEmpty();
    }

    private static boolean areCardinalNeighborsLoaded(WorldClient world, int cx, int cz) {
        return isClientChunkLoaded(world, cx - 1, cz)
                && isClientChunkLoaded(world, cx + 1, cz)
                && isClientChunkLoaded(world, cx, cz - 1)
                && isClientChunkLoaded(world, cx, cz + 1);
    }

    private static boolean isInFlightBake(long key, long nowMs) {
        int idx = mixHash(key) & IN_FLIGHT_MASK;
        return IN_FLIGHT_BAKE_KEYS[idx] == key && IN_FLIGHT_BAKE_EXPIRY[idx] >= nowMs;
    }

    private static void markInFlightBake(long key, long expiryMs) {
        int idx = mixHash(key) & IN_FLIGHT_MASK;
        IN_FLIGHT_BAKE_KEYS[idx] = key;
        IN_FLIGHT_BAKE_EXPIRY[idx] = expiryMs;
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

    private static void putColumnGraceLockFree(long key, long expiryMs) {
        int slot = mixHash(key) & COL_TABLE_MASK;
        int oldestIdx = slot;
        long oldestExp = Long.MAX_VALUE;
        for (int probe = 0; probe < 8; probe++) {
            int idx = (slot + probe) & COL_TABLE_MASK;
            long k = INITIAL_COL_KEYS.get(idx);
            if (k == key) {
                long prev = INITIAL_COL_EXPIRY.get(idx);
                if (expiryMs > prev) {
                    INITIAL_COL_EXPIRY.set(idx, expiryMs);
                }
                return;
            }
            if (k == EMPTY_KEY) {
                if (INITIAL_COL_KEYS.compareAndSet(idx, EMPTY_KEY, key) || INITIAL_COL_KEYS.get(idx) == key) {
                    INITIAL_COL_EXPIRY.set(idx, expiryMs);
                    return;
                }
            }
            long exp = INITIAL_COL_EXPIRY.get(idx);
            if (exp < oldestExp) {
                oldestExp = exp;
                oldestIdx = idx;
            }
        }
        INITIAL_COL_EXPIRY.set(oldestIdx, expiryMs);
        INITIAL_COL_KEYS.set(oldestIdx, key);
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
