package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBMultiDrawIndirect;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moteur GPU Ultra-Haute-Fréquence d'Arènes Régionales MultiDraw Indirect + VAO GL 3.0 (MashClient Wave 2) :
 * - Tier-1 (OpenGL 4.3 / ARB_multi_draw_indirect) : stocke 4 sommets/quad (-33 % VRAM, bande passante et VS)
 *   + EBO 16-bit partagé (QuadIndexBuffer) lié dans le VAO GL30 + GL43.glMultiDrawElementsIndirect (1 draw/région).
 * - Tier-2 (OpenGL 1.4-4.2) : repli 6 sommets/quad GL14.glMultiDrawArrays(GL_TRIANGLES).
 * - Uploads zéro-stall : VBO arène GL_STATIC_DRAW + staging VBO orphelin + GL31.glCopyBufferSubData + libération
 *   différée 3 frames (zéro hazard mémoire GPU).
 * - Élimine les 2 fuites VRAM (libération off-thread drainée sur le thread principal + destruction des arènes vides > 5 s).
 */
public final class ChunkRegionManager {

    public static final float ANTI_SEAM_SCALE = 1.000001F;
    private static final float ANTI_SEAM_BIAS = 8.0F - 8.0F * ANTI_SEAM_SCALE;
    public static final int INDIRECT_CMD_BYTES = 20;
    private static final int INDIRECT_RING_CAPACITY_CMDS = 16384;
    private static final int INDIRECT_RING_CAPACITY_BYTES = INDIRECT_RING_CAPACITY_CMDS * INDIRECT_CMD_BYTES;
    private static final int MAP_FLAGS_UNSYNC = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;

    public static final float[] STATIC_ANTI_SEAM_MATRIX = computeStaticAntiSeamMatrix();

    private static final Set<ByteBuffer> WORKER_BAKED_BUFFERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<ByteBuffer, Boolean>()));

    private static final Map<Long, RegionLayerArena> ARENAS = new HashMap<Long, RegionLayerArena>();
    private static final List<RegionLayerArena> ACTIVE_BATCH_ARENAS = new ArrayList<RegionLayerArena>(8);

    // Pool lock-free partitionné en 3 buckets (64 Ko / 256 Ko / 512 Ko) pour éliminer les rejets de taille
    private static final int BUCKET_SMALL_MAX = 64 * 1024;
    private static final int BUCKET_MED_MAX = 256 * 1024;
    private static final int BUCKET_LARGE_MAX = 512 * 1024;
    private static final int MAX_PER_BUCKET = 8;

    private static final ConcurrentLinkedQueue<ByteBuffer> POOL_SMALL = new ConcurrentLinkedQueue<ByteBuffer>();
    private static final AtomicInteger POOL_SMALL_COUNT = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<ByteBuffer> POOL_MED = new ConcurrentLinkedQueue<ByteBuffer>();
    private static final AtomicInteger POOL_MED_COUNT = new AtomicInteger(0);
    private static final ConcurrentLinkedQueue<ByteBuffer> POOL_LARGE = new ConcurrentLinkedQueue<ByteBuffer>();
    private static final AtomicInteger POOL_LARGE_COUNT = new AtomicInteger(0);

    // File thread-safe pour libérer sur le thread GL les couches devenues vides depuis un worker (Fuite VRAM #1)
    private static final ConcurrentLinkedQueue<RenderChunk> PENDING_EMPTY_LAYER_CHUNKS =
            new ConcurrentLinkedQueue<RenderChunk>();

    // File de libération différée 3 frames (zéro hazard mémoire si le GPU lit encore frames N-1 / N-2)
    public static final int DEFERRED_FREE_FRAMES = 3;
    private static final ArrayDeque<DeferredSlotFree> DEFERRED_FREES = new ArrayDeque<DeferredSlotFree>(64);
    private static long currentFrameIndex = 0L;
    private static long lastArenaPruneMs = 0L;
    private static final long EMPTY_ARENA_GRACE_MS = 5_000L;

    // Passage zéro-allocation entre ChunkUploadTask.run() et uploadChunkLayerToArena() sur le thread GL principal
    private static ByteBuffer pendingPrebakedBuf = null;
    private static int pendingPrebakedVerts = 0;
    private static boolean pendingPrebakedIndexed = false;
    private static int pendingPrebakedPosX = 0;
    private static int pendingPrebakedPosY = 0;
    private static int pendingPrebakedPosZ = 0;
    private static int[] pendingPrebakedFaceStarts = null;
    private static int[] pendingPrebakedFaceCounts = null;

    public static final int FACE_DOWN = 0;
    public static final int FACE_UP = 1;
    public static final int FACE_NORTH = 2;
    public static final int FACE_SOUTH = 3;
    public static final int FACE_WEST = 4;
    public static final int FACE_EAST = 5;
    public static final int FACE_UNASSIGNED = 6;
    public static final int FACE_BUCKET_COUNT = 7;

    private static final ThreadLocal<byte[]> WORKER_QUAD_BUCKETS = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[4096];
        }
    };

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
    private static ByteBuffer indirectCmdScratch = ByteBuffer.allocateDirect(4096 * INDIRECT_CMD_BYTES).order(ByteOrder.nativeOrder());
    private static ByteBuffer indirectMappedScratch = null;
    private static IntBuffer multiFirstBuf = BufferUtils.createIntBuffer(512);
    private static IntBuffer multiCountBuf = BufferUtils.createIntBuffer(512);

    private static int uploadStagingVboId = -1;
    private static int indirectRingVboId = -1;
    private static int indirectRingWriteCmd = 0;
    private static boolean indirectRingFailed = false;

    private static volatile boolean glCapsInitialized = false;
    private static volatile boolean hasGL15 = false;
    private static volatile boolean hasGL14 = false;
    private static volatile boolean hasGL30Vao = false;
    private static volatile boolean hasGL31Copy = false;
    private static volatile boolean hasGL43Indirect = false;
    private static volatile boolean hasArbIndirect = false;

    private static final class DeferredSlotFree {
        final RegionLayerArena arena;
        final int firstVertex;
        final int allocVertices;
        final long retireFrame;

        DeferredSlotFree(RegionLayerArena arena, int firstVertex, int allocVertices, long retireFrame) {
            this.arena = arena;
            this.firstVertex = firstVertex;
            this.allocVertices = allocVertices;
            this.retireFrame = retireFrame;
        }
    }

    private ChunkRegionManager() {
    }

    public static float[] computeStaticAntiSeamMatrix() {
        float f = ANTI_SEAM_SCALE;
        float t = 8.0F * f - 8.0F;
        return new float[]{
                f, 0.0F, 0.0F, 0.0F,
                0.0F, f, 0.0F, 0.0F,
                0.0F, 0.0F, f, 0.0F,
                t, t, t, 1.0F
        };
    }

    public static void fillStaticAntiSeamMatrix(FloatBuffer dst) {
        dst.clear();
        dst.put(STATIC_ANTI_SEAM_MATRIX, 0, 16);
        dst.flip();
    }

    /**
     * Initialise les capacités OpenGL dès la création du contexte (sans verrouiller OpenGlHelper.useVbo()).
     */
    public static void initCapabilitiesEarly() {
        if (glCapsInitialized) {
            return;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null) {
                return;
            }
            hasGL15 = caps.OpenGL15;
            hasGL14 = caps.OpenGL14;
            hasGL30Vao = caps.OpenGL30;
            hasGL31Copy = caps.OpenGL31;
            hasGL43Indirect = caps.OpenGL43;
            hasArbIndirect = caps.GL_ARB_multi_draw_indirect;
            glCapsInitialized = true;
        } catch (Exception | LinkageError ignored) {
        }
    }

    public static boolean isSupported() {
        if (!glCapsInitialized) {
            initCapabilitiesEarly();
        }
        return glCapsInitialized && hasGL15 && OpenGlHelper.useVbo();
    }

    public static boolean isMultiDrawSupported() {
        if (!glCapsInitialized) {
            initCapabilitiesEarly();
        }
        return glCapsInitialized && hasGL15 && hasGL14 && OpenGlHelper.useVbo();
    }

    public static boolean isIndirectDrawSupported() {
        if (!glCapsInitialized) {
            initCapabilitiesEarly();
        }
        return glCapsInitialized
                && (hasGL43Indirect || hasArbIndirect)
                && hasGL30Vao
                && !indirectRingFailed
                && PureFPSConfig.hardwareRegionVao
                && QuadIndexBuffer.isSupported()
                && OpenGlHelper.useVbo();
    }

    /**
     * Lecture thread-safe depuis les threads workers (ne touche jamais GLContext).
     */
    public static boolean isMultiDrawReadyForWorkers() {
        return glCapsInitialized && hasGL15 && hasGL14;
    }

    public static boolean isIndirectReadyForWorkers() {
        return glCapsInitialized
                && (hasGL43Indirect || hasArbIndirect)
                && hasGL30Vao
                && !indirectRingFailed
                && PureFPSConfig.hardwareRegionVao;
    }

    public static boolean isHardwareVaoEnabled() {
        return hasGL30Vao && PureFPSConfig.hardwareRegionVao;
    }

    public static boolean canSkipVao0ClientStates(EnumWorldBlockLayer layer) {
        return PureFPSConfig.enabled
                && PureFPSConfig.regionMultiDraw
                && layer != EnumWorldBlockLayer.TRANSLUCENT
                && hasGL30Vao
                && PureFPSConfig.hardwareRegionVao
                && isMultiDrawSupported();
    }

    public static void enableBlockClientStates() {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
    }

    public static void disableBlockClientStates() {
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.resetColor();
    }

    /**
     * Enqueue un RenderChunk depuis un worker thread afin de libérer ses couches devenues vides sur le thread GL.
     */
    public static void enqueueDeferredEmptyLayerCheck(RenderChunk chunk) {
        if (chunk != null) {
            PENDING_EMPTY_LAYER_CHUNKS.offer(chunk);
        }
    }

    /**
     * Draine sur le thread GL principal les libérations de couches vides signalées par les workers.
     */
    public static void drainPendingEmptyLayerChecks() {
        RenderChunk rc;
        EnumWorldBlockLayer[] layers = EnumCaches.BLOCK_LAYERS;
        while ((rc = PENDING_EMPTY_LAYER_CHUNKS.poll()) != null) {
            CompiledChunk compiled = rc.getCompiledChunk();
            for (int i = 0; i < 3; i++) {
                if (compiled == null || compiled == CompiledChunk.DUMMY || compiled.isLayerEmpty(layers[i])) {
                    VertexBuffer vb = rc.getVertexBufferByLayer(i);
                    if (vb != null) {
                        FastVertexBuffer fvb = (FastVertexBuffer) vb;
                        if (fvb.purefps$getArena() != null) {
                            fvb.purefps$releaseArenaSlot();
                            fvb.purefps$setVertexCount(0);
                        }
                    }
                }
            }
        }
    }

    public static void scheduleArenaSlotFree(RegionLayerArena arena, int firstVertex, int allocVertices) {
        if (arena == null || arena.vboId < 0 || firstVertex < 0 || allocVertices <= 0) {
            return;
        }
        DEFERRED_FREES.addLast(new DeferredSlotFree(arena, firstVertex, allocVertices, currentFrameIndex + DEFERRED_FREE_FRAMES));
    }

    public static void onFrameStart() {
        currentFrameIndex++;
        SmartAnimations.onFrameStart();
        drainDeferredFrees(currentFrameIndex, System.currentTimeMillis());
    }

    static void drainDeferredFrees(long frameIndex, long nowMs) {
        while (!DEFERRED_FREES.isEmpty()) {
            DeferredSlotFree head = DEFERRED_FREES.peekFirst();
            if (head.retireFrame > frameIndex) {
                break;
            }
            DEFERRED_FREES.pollFirst();
            if (head.arena != null && head.arena.vboId >= 0) {
                head.arena.allocator.free(head.firstVertex, head.allocVertices);
                head.arena.markFreeCheck(nowMs);
            }
        }

        if (!ARENAS.isEmpty() && (nowMs - lastArenaPruneMs) >= 1000L) {
            lastArenaPruneMs = nowMs;
            Iterator<Map.Entry<Long, RegionLayerArena>> it = ARENAS.entrySet().iterator();
            while (it.hasNext()) {
                RegionLayerArena arena = it.next().getValue();
                if (arena == null) {
                    it.remove();
                    continue;
                }
                if (arena.allocator.getLiveAllocations() == 0) {
                    if (arena.emptySinceMs == 0L) {
                        arena.emptySinceMs = nowMs;
                    } else if ((nowMs - arena.emptySinceMs) >= EMPTY_ARENA_GRACE_MS) {
                        arena.destroy();
                        it.remove();
                    }
                } else {
                    arena.emptySinceMs = 0L;
                }
            }
        }
    }

    // --- Helpers de tests unitaires ---
    static void scheduleDeferredFreeForTest(RegionLayerArena arena, int firstVert, int allocVerts, long currentFrame) {
        currentFrameIndex = currentFrame;
        DEFERRED_FREES.addLast(new DeferredSlotFree(arena, firstVert, allocVerts, currentFrame + DEFERRED_FREE_FRAMES));
    }

    static int getPendingDeferredFreeCountForTest() {
        return DEFERRED_FREES.size();
    }

    static void clearDeferredFreesForTest() {
        DEFERRED_FREES.clear();
        currentFrameIndex = 0L;
    }

    public static ByteBuffer acquireWorkerTriBuffer(int requiredBytes) {
        if (requiredBytes <= BUCKET_SMALL_MAX) {
            ByteBuffer b = pollBucket(POOL_SMALL, POOL_SMALL_COUNT, requiredBytes);
            if (b != null) return b;
            return ByteBuffer.allocateDirect(BUCKET_SMALL_MAX).order(ByteOrder.nativeOrder());
        }
        if (requiredBytes <= BUCKET_MED_MAX) {
            ByteBuffer b = pollBucket(POOL_MED, POOL_MED_COUNT, requiredBytes);
            if (b != null) return b;
            return ByteBuffer.allocateDirect(BUCKET_MED_MAX).order(ByteOrder.nativeOrder());
        }
        if (requiredBytes <= BUCKET_LARGE_MAX) {
            ByteBuffer b = pollBucket(POOL_LARGE, POOL_LARGE_COUNT, requiredBytes);
            if (b != null) return b;
            return ByteBuffer.allocateDirect(BUCKET_LARGE_MAX).order(ByteOrder.nativeOrder());
        }
        return ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer pollBucket(ConcurrentLinkedQueue<ByteBuffer> queue, AtomicInteger count, int requiredBytes) {
        ByteBuffer candidate = queue.poll();
        if (candidate != null) {
            count.decrementAndGet();
            if (candidate.capacity() >= requiredBytes) {
                candidate.clear();
                return candidate;
            }
        }
        return null;
    }

    public static void releaseWorkerTriBuffer(ByteBuffer buf) {
        if (buf == null) {
            return;
        }
        int cap = buf.capacity();
        if (cap > BUCKET_LARGE_MAX) {
            return;
        }
        buf.clear();
        if (cap <= BUCKET_SMALL_MAX) {
            offerBucket(POOL_SMALL, POOL_SMALL_COUNT, buf);
        } else if (cap <= BUCKET_MED_MAX) {
            offerBucket(POOL_MED, POOL_MED_COUNT, buf);
        } else {
            offerBucket(POOL_LARGE, POOL_LARGE_COUNT, buf);
        }
    }

    private static void offerBucket(ConcurrentLinkedQueue<ByteBuffer> queue, AtomicInteger count, ByteBuffer buf) {
        if (count.get() >= MAX_PER_BUCKET) {
            return;
        }
        if (count.incrementAndGet() <= MAX_PER_BUCKET) {
            queue.offer(buf);
        } else {
            count.decrementAndGet();
        }
    }

    public static final class PrebakedChunk {
        public final ByteBuffer buffer;
        public final int vertices;
        public final boolean indexedQuads;
        public final int[] faceStarts;
        public final int[] faceCounts;

        PrebakedChunk(ByteBuffer buffer, int vertices, boolean indexedQuads, int[] faceStarts, int[] faceCounts) {
            this.buffer = buffer;
            this.vertices = vertices;
            this.indexedQuads = indexedQuads;
            this.faceStarts = faceStarts;
            this.faceCounts = faceCounts;
        }
    }

    /**
     * Classifie un quad (4 sommets de 28 octets) dans l'un des 7 seaux de direction par sa normale
     * géométrique (v1 - v0) x (v2 - v0) :
     * 0=DOWN(-Y), 1=UP(+Y), 2=NORTH(-Z), 3=SOUTH(+Z), 4=WEST(-X), 5=EAST(+X), 6=UNASSIGNED (diagonales/pentes).
     */
    public static int classifyQuadFace(ByteBuffer srcBytes, int quadByteOffset) {
        float x0 = srcBytes.getFloat(quadByteOffset);
        float y0 = srcBytes.getFloat(quadByteOffset + 4);
        float z0 = srcBytes.getFloat(quadByteOffset + 8);

        float e1x = srcBytes.getFloat(quadByteOffset + 28) - x0;
        float e1y = srcBytes.getFloat(quadByteOffset + 32) - y0;
        float e1z = srcBytes.getFloat(quadByteOffset + 36) - z0;

        float e2x = srcBytes.getFloat(quadByteOffset + 56) - x0;
        float e2y = srcBytes.getFloat(quadByteOffset + 60) - y0;
        float e2z = srcBytes.getFloat(quadByteOffset + 64) - z0;

        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        float nx2 = nx * nx;
        float ny2 = ny * ny;
        float nz2 = nz * nz;
        float lenSq = nx2 + ny2 + nz2;
        if (lenSq < 1.0E-8F) {
            return FACE_UNASSIGNED;
        }
        float axisThreshold = lenSq * 0.998F;
        if (ny2 >= axisThreshold) {
            return ny < 0.0F ? FACE_DOWN : FACE_UP;
        }
        if (nz2 >= axisThreshold) {
            return nz < 0.0F ? FACE_NORTH : FACE_SOUTH;
        }
        if (nx2 >= axisThreshold) {
            return nx < 0.0F ? FACE_WEST : FACE_EAST;
        }
        return FACE_UNASSIGNED;
    }

    /**
     * Partitionne les quads en 7 plages contiguës (6 directions + UNASSIGNED) et applique la transformation
     * régionale anti-couture en 4 sommets/quad (Tier-1 glMultiDrawElementsIndirect).
     */
    public static void partitionAndTransformQuads(
            ByteBuffer srcBytes,
            int quadVertexCount,
            float localOffsetX,
            float localOffsetY,
            float localOffsetZ,
            ByteBuffer dstBytes,
            int[] outFaceStarts,
            int[] outFaceCounts
    ) {
        int quadCount = quadVertexCount >> 2;
        int requiredBytes = quadCount * (RegionLayerArena.VERTEX_STRIDE_BYTES * 4);

        byte[] buckets = WORKER_QUAD_BUCKETS.get();
        if (buckets.length < quadCount) {
            buckets = new byte[Math.max(quadCount, buckets.length << 1)];
            WORKER_QUAD_BUCKETS.set(buckets);
        }

        int[] quadCounts = new int[FACE_BUCKET_COUNT];
        for (int q = 0; q < quadCount; q++) {
            int b = classifyQuadFace(srcBytes, q * 112);
            buckets[q] = (byte) b;
            quadCounts[b]++;
        }

        int[] writeByteOff = new int[FACE_BUCKET_COUNT];
        int runningVert = 0;
        for (int b = 0; b < FACE_BUCKET_COUNT; b++) {
            int verts = quadCounts[b] << 2;
            outFaceStarts[b] = runningVert;
            outFaceCounts[b] = verts;
            writeByteOff[b] = runningVert * RegionLayerArena.VERTEX_STRIDE_BYTES;
            runningVert += verts;
        }

        dstBytes.clear();
        float biasX = 8.0F + localOffsetX - 8.0F * ANTI_SEAM_SCALE;
        float biasY = 8.0F + localOffsetY - 8.0F * ANTI_SEAM_SCALE;
        float biasZ = 8.0F + localOffsetZ - 8.0F * ANTI_SEAM_SCALE;

        int srcOff = 0;
        for (int q = 0; q < quadCount; q++) {
            int b = buckets[q] & 7;
            int dstOff = writeByteOff[b];
            for (int v = 0; v < 4; v++) {
                int so = srcOff + v * 28;
                int doff = dstOff + v * 28;
                dstBytes.putFloat(doff, srcBytes.getFloat(so) * ANTI_SEAM_SCALE + biasX);
                dstBytes.putFloat(doff + 4, srcBytes.getFloat(so + 4) * ANTI_SEAM_SCALE + biasY);
                dstBytes.putFloat(doff + 8, srcBytes.getFloat(so + 8) * ANTI_SEAM_SCALE + biasZ);
                dstBytes.putLong(doff + 12, srcBytes.getLong(so + 12));
                dstBytes.putLong(doff + 20, srcBytes.getLong(so + 20));
            }
            writeByteOff[b] = dstOff + 112;
            srcOff += 112;
        }

        dstBytes.position(0);
        dstBytes.limit(requiredBytes);
    }

    /**
     * Partitionne les quads en 7 plages contiguës (6 directions + UNASSIGNED) et les déplie en 6 sommets/quad
     * GL_TRIANGLES avec transformation régionale anti-couture (Tier-2 glMultiDrawArrays).
     */
    public static int partitionAndTransformQuadsToTriangles(
            ByteBuffer srcBytes,
            int quadVertexCount,
            float localOffsetX,
            float localOffsetY,
            float localOffsetZ,
            ByteBuffer dstBytes,
            int[] outFaceStarts,
            int[] outFaceCounts
    ) {
        int quadCount = quadVertexCount >> 2;
        int triVertexCount = quadCount * 6;
        int requiredDstBytes = triVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;

        byte[] buckets = WORKER_QUAD_BUCKETS.get();
        if (buckets.length < quadCount) {
            buckets = new byte[Math.max(quadCount, buckets.length << 1)];
            WORKER_QUAD_BUCKETS.set(buckets);
        }

        int[] quadCounts = new int[FACE_BUCKET_COUNT];
        for (int q = 0; q < quadCount; q++) {
            int b = classifyQuadFace(srcBytes, q * 112);
            buckets[q] = (byte) b;
            quadCounts[b]++;
        }

        int[] writeByteOff = new int[FACE_BUCKET_COUNT];
        int runningVert = 0;
        for (int b = 0; b < FACE_BUCKET_COUNT; b++) {
            int verts = quadCounts[b] * 6;
            outFaceStarts[b] = runningVert;
            outFaceCounts[b] = verts;
            writeByteOff[b] = runningVert * RegionLayerArena.VERTEX_STRIDE_BYTES;
            runningVert += verts;
        }

        dstBytes.clear();
        float biasX = 8.0F + localOffsetX - 8.0F * ANTI_SEAM_SCALE;
        float biasY = 8.0F + localOffsetY - 8.0F * ANTI_SEAM_SCALE;
        float biasZ = 8.0F + localOffsetZ - 8.0F * ANTI_SEAM_SCALE;

        int srcOff = 0;
        for (int q = 0; q < quadCount; q++) {
            int b = buckets[q] & 7;
            int dstOff = writeByteOff[b];

            float x0 = srcBytes.getFloat(srcOff) * ANTI_SEAM_SCALE + biasX;
            float y0 = srcBytes.getFloat(srcOff + 4) * ANTI_SEAM_SCALE + biasY;
            float z0 = srcBytes.getFloat(srcOff + 8) * ANTI_SEAM_SCALE + biasZ;
            long a0 = srcBytes.getLong(srcOff + 12);
            long b0 = srcBytes.getLong(srcOff + 20);

            float x1 = srcBytes.getFloat(srcOff + 28) * ANTI_SEAM_SCALE + biasX;
            float y1 = srcBytes.getFloat(srcOff + 32) * ANTI_SEAM_SCALE + biasY;
            float z1 = srcBytes.getFloat(srcOff + 36) * ANTI_SEAM_SCALE + biasZ;
            long a1 = srcBytes.getLong(srcOff + 40);
            long b1 = srcBytes.getLong(srcOff + 48);

            float x2 = srcBytes.getFloat(srcOff + 56) * ANTI_SEAM_SCALE + biasX;
            float y2 = srcBytes.getFloat(srcOff + 60) * ANTI_SEAM_SCALE + biasY;
            float z2 = srcBytes.getFloat(srcOff + 64) * ANTI_SEAM_SCALE + biasZ;
            long a2 = srcBytes.getLong(srcOff + 68);
            long b2 = srcBytes.getLong(srcOff + 76);

            float x3 = srcBytes.getFloat(srcOff + 84) * ANTI_SEAM_SCALE + biasX;
            float y3 = srcBytes.getFloat(srcOff + 88) * ANTI_SEAM_SCALE + biasY;
            float z3 = srcBytes.getFloat(srcOff + 92) * ANTI_SEAM_SCALE + biasZ;
            long a3 = srcBytes.getLong(srcOff + 96);
            long b3 = srcBytes.getLong(srcOff + 104);

            dstBytes.putFloat(dstOff, x0);       dstBytes.putFloat(dstOff + 4, y0);   dstBytes.putFloat(dstOff + 8, z0);   dstBytes.putLong(dstOff + 12, a0);  dstBytes.putLong(dstOff + 20, b0);
            dstBytes.putFloat(dstOff + 28, x1);  dstBytes.putFloat(dstOff + 32, y1);  dstBytes.putFloat(dstOff + 36, z1);  dstBytes.putLong(dstOff + 40, a1);  dstBytes.putLong(dstOff + 48, b1);
            dstBytes.putFloat(dstOff + 56, x2);  dstBytes.putFloat(dstOff + 60, y2);  dstBytes.putFloat(dstOff + 64, z2);  dstBytes.putLong(dstOff + 68, a2);  dstBytes.putLong(dstOff + 76, b2);
            dstBytes.putFloat(dstOff + 84, x2);  dstBytes.putFloat(dstOff + 88, y2);  dstBytes.putFloat(dstOff + 92, z2);  dstBytes.putLong(dstOff + 96, a2);  dstBytes.putLong(dstOff + 104, b2);
            dstBytes.putFloat(dstOff + 112, x3); dstBytes.putFloat(dstOff + 116, y3); dstBytes.putFloat(dstOff + 120, z3); dstBytes.putLong(dstOff + 124, a3); dstBytes.putLong(dstOff + 132, b3);
            dstBytes.putFloat(dstOff + 140, x0); dstBytes.putFloat(dstOff + 144, y0); dstBytes.putFloat(dstOff + 148, z0); dstBytes.putLong(dstOff + 152, a0); dstBytes.putLong(dstOff + 160, b0);

            writeByteOff[b] = dstOff + 168;
            srcOff += 112;
        }

        dstBytes.position(0);
        dstBytes.limit(requiredDstBytes);
        return triVertexCount;
    }

    /**
     * Exécuté sur ChunkRenderWorker : partitionne en 6-directions + UNASSIGNED et cuit hors thread OpenGL.
     */
    public static PrebakedChunk bakeChunkToRegionPartitioned(
            ByteBuffer srcBytes,
            int posX,
            int posY,
            int posZ,
            boolean indexedQuads
    ) {
        if (srcBytes == null) {
            return null;
        }
        int byteLimit = srcBytes.limit();
        int quadVertexCount = (byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES) & ~3;
        if (quadVertexCount <= 0) {
            return null;
        }
        int outVerts = indexedQuads ? quadVertexCount : ((quadVertexCount >> 2) * 6);
        int requiredBytes = outVerts * RegionLayerArena.VERTEX_STRIDE_BYTES;
        int originX = posX & ~255;
        int originZ = posZ & ~255;
        float localX = (float) (posX - originX);
        float localY = (float) posY;
        float localZ = (float) (posZ - originZ);

        ByteBuffer dst = acquireWorkerTriBuffer(requiredBytes);
        int[] faceStarts = new int[FACE_BUCKET_COUNT];
        int[] faceCounts = new int[FACE_BUCKET_COUNT];
        if (indexedQuads) {
            partitionAndTransformQuads(srcBytes, quadVertexCount, localX, localY, localZ, dst, faceStarts, faceCounts);
        } else {
            partitionAndTransformQuadsToTriangles(srcBytes, quadVertexCount, localX, localY, localZ, dst, faceStarts, faceCounts);
        }
        return new PrebakedChunk(dst, outVerts, indexedQuads, faceStarts, faceCounts);
    }

    /**
     * Exécuté sur ChunkRenderWorker (Tier-1 Indirect) : transforme 4 sommets/quad en coordonnées régionales (-33 % volume).
     */
    public static ByteBuffer bakeChunkToRegionQuads(ByteBuffer srcBytes, int posX, int posY, int posZ) {
        if (srcBytes == null) {
            return null;
        }
        int byteLimit = srcBytes.limit();
        int quadVertexCount = (byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES) & ~3;
        if (quadVertexCount <= 0) {
            return null;
        }
        int requiredBytes = quadVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        int originX = posX & ~255;
        int originZ = posZ & ~255;
        float localX = (float) (posX - originX);
        float localY = (float) posY;
        float localZ = (float) (posZ - originZ);

        ByteBuffer dst = acquireWorkerTriBuffer(requiredBytes);
        transformBlockVertices(srcBytes, quadVertexCount, localX, localY, localZ, dst);
        return dst;
    }

    /**
     * Exécuté sur ChunkRenderWorker (Tier-2 Fallback) : convertit les quads en triangles régionaux hors thread OpenGL.
     */
    public static ByteBuffer bakeChunkToRegionTriangles(ByteBuffer srcBytes, int posX, int posY, int posZ) {
        if (srcBytes == null) {
            return null;
        }
        int byteLimit = srcBytes.limit();
        int quadVertexCount = byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES;
        int triVertexCount = (quadVertexCount >> 2) * 6;
        if (triVertexCount <= 0) {
            return null;
        }
        int requiredBytes = triVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        int originX = posX & ~255;
        int originZ = posZ & ~255;
        float localX = (float) (posX - originX);
        float localY = (float) posY;
        float localZ = (float) (posZ - originZ);

        ByteBuffer dst = acquireWorkerTriBuffer(requiredBytes);
        transformBlockQuadsToTriangles(srcBytes, quadVertexCount, localX, localY, localZ, dst);
        return dst;
    }

    public static void setPendingPrebakedUpload(
            ByteBuffer buf,
            int verts,
            boolean indexedQuads,
            int posX,
            int posY,
            int posZ,
            int[] faceStarts,
            int[] faceCounts
    ) {
        pendingPrebakedBuf = buf;
        pendingPrebakedVerts = verts;
        pendingPrebakedIndexed = indexedQuads;
        pendingPrebakedPosX = posX;
        pendingPrebakedPosY = posY;
        pendingPrebakedPosZ = posZ;
        pendingPrebakedFaceStarts = faceStarts;
        pendingPrebakedFaceCounts = faceCounts;
    }

    public static void clearPendingPrebakedUpload() {
        pendingPrebakedBuf = null;
        pendingPrebakedVerts = 0;
        pendingPrebakedIndexed = false;
        pendingPrebakedFaceStarts = null;
        pendingPrebakedFaceCounts = null;
    }

    private static ByteBuffer consumePendingPrebakedUpload(
            int posX,
            int posY,
            int posZ,
            int verts,
            boolean wantIndexed,
            int[][] outFaceRanges
    ) {
        ByteBuffer buf = pendingPrebakedBuf;
        int[] fs = pendingPrebakedFaceStarts;
        int[] fc = pendingPrebakedFaceCounts;
        pendingPrebakedBuf = null;
        pendingPrebakedFaceStarts = null;
        pendingPrebakedFaceCounts = null;
        if (buf != null
                && pendingPrebakedIndexed == wantIndexed
                && pendingPrebakedVerts == verts
                && pendingPrebakedPosX == posX
                && pendingPrebakedPosY == posY
                && pendingPrebakedPosZ == posZ) {
            outFaceRanges[0] = fs;
            outFaceRanges[1] = fc;
            return buf;
        }
        return null;
    }

    public static long packRegionLayerKey(int blockX, int blockZ, int layerOrdinal) {
        int rx = blockX >> 8;
        int rz = blockZ >> 8;
        return (((long) rx & 0xFFFFFFFL) << 36) | (((long) rz & 0xFFFFFFFL) << 8) | ((long) layerOrdinal & 0xFFL);
    }

    public static void bakeAntiSeamInPlace(FloatBuffer floatBuf, ByteBuffer backingByteBuf, int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        if (backingByteBuf != null && !backingByteBuf.isReadOnly()) {
            int byteOff = 0;
            for (int v = 0; v < vertexCount; v++) {
                float vx = backingByteBuf.getFloat(byteOff);
                float vy = backingByteBuf.getFloat(byteOff + 4);
                float vz = backingByteBuf.getFloat(byteOff + 8);
                backingByteBuf.putFloat(byteOff, vx * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                backingByteBuf.putFloat(byteOff + 4, vy * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                backingByteBuf.putFloat(byteOff + 8, vz * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                byteOff += RegionLayerArena.VERTEX_STRIDE_BYTES;
            }
            WORKER_BAKED_BUFFERS.add(backingByteBuf);
        } else if (floatBuf != null && !floatBuf.isReadOnly()) {
            int floatOff = 0;
            for (int v = 0; v < vertexCount; v++) {
                float vx = floatBuf.get(floatOff);
                float vy = floatBuf.get(floatOff + 1);
                float vz = floatBuf.get(floatOff + 2);
                floatBuf.put(floatOff, vx * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                floatBuf.put(floatOff + 1, vy * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                floatBuf.put(floatOff + 2, vz * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
                floatOff += 7;
            }
        }
    }

    public static boolean consumeWorkerBakedFlag(ByteBuffer byteBuf) {
        return byteBuf != null && WORKER_BAKED_BUFFERS.remove(byteBuf);
    }

    private static void ensureStagingCapacity(int requiredBytes) {
        if (stagingBuffer.capacity() < requiredBytes) {
            int newCap = Math.max(stagingBuffer.capacity() << 1, requiredBytes);
            stagingBuffer = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        }
    }

    public static ByteBuffer prepareChunkAntiSeamBuffer(ByteBuffer srcBytes, int vertexCount) {
        int requiredBytes = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        ensureStagingCapacity(requiredBytes);
        transformBlockVertices(srcBytes, vertexCount, 0.0F, 0.0F, 0.0F, stagingBuffer);
        return stagingBuffer;
    }

    public static ByteBuffer prepareChunkTrianglesBuffer(ByteBuffer srcBytes, int quadVertexCount) {
        int triVertexCount = (quadVertexCount >> 2) * 6;
        int requiredBytes = triVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        ensureStagingCapacity(requiredBytes);
        transformBlockQuadsToTriangles(srcBytes, quadVertexCount, 0.0F, 0.0F, 0.0F, stagingBuffer);
        return stagingBuffer;
    }

    public static void translateBakedVertices(
            ByteBuffer srcBytes,
            int vertexCount,
            float localOffsetX,
            float localOffsetY,
            float localOffsetZ,
            ByteBuffer dstBytes
    ) {
        int requiredBytes = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        int prevPos = srcBytes.position();
        int prevLimit = srcBytes.limit();
        if (prevLimit < requiredBytes) {
            srcBytes.limit(requiredBytes);
        }
        srcBytes.position(0);

        dstBytes.clear();

        int byteOff = 0;
        for (int v = 0; v < vertexCount; v++) {
            dstBytes.putFloat(byteOff, srcBytes.getFloat(byteOff) + localOffsetX);
            dstBytes.putFloat(byteOff + 4, srcBytes.getFloat(byteOff + 4) + localOffsetY);
            dstBytes.putFloat(byteOff + 8, srcBytes.getFloat(byteOff + 8) + localOffsetZ);
            dstBytes.putLong(byteOff + 12, srcBytes.getLong(byteOff + 12));
            dstBytes.putLong(byteOff + 20, srcBytes.getLong(byteOff + 20));
            byteOff += RegionLayerArena.VERTEX_STRIDE_BYTES;
        }

        srcBytes.limit(prevLimit);
        srcBytes.position(prevPos);
        dstBytes.position(0);
        dstBytes.limit(requiredBytes);
    }

    public static void transformBlockVertices(
            ByteBuffer srcBytes,
            int vertexCount,
            float localOffsetX,
            float localOffsetY,
            float localOffsetZ,
            ByteBuffer dstBytes
    ) {
        int requiredBytes = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        int prevPos = srcBytes.position();
        int prevLimit = srcBytes.limit();
        if (prevLimit < requiredBytes) {
            srcBytes.limit(requiredBytes);
        }
        srcBytes.position(0);

        dstBytes.clear();

        float biasX = 8.0F + localOffsetX - 8.0F * ANTI_SEAM_SCALE;
        float biasY = 8.0F + localOffsetY - 8.0F * ANTI_SEAM_SCALE;
        float biasZ = 8.0F + localOffsetZ - 8.0F * ANTI_SEAM_SCALE;

        int byteOff = 0;
        for (int v = 0; v < vertexCount; v++) {
            float vx = srcBytes.getFloat(byteOff);
            float vy = srcBytes.getFloat(byteOff + 4);
            float vz = srcBytes.getFloat(byteOff + 8);

            dstBytes.putFloat(byteOff, vx * ANTI_SEAM_SCALE + biasX);
            dstBytes.putFloat(byteOff + 4, vy * ANTI_SEAM_SCALE + biasY);
            dstBytes.putFloat(byteOff + 8, vz * ANTI_SEAM_SCALE + biasZ);
            dstBytes.putLong(byteOff + 12, srcBytes.getLong(byteOff + 12));
            dstBytes.putLong(byteOff + 20, srcBytes.getLong(byteOff + 20));
            byteOff += RegionLayerArena.VERTEX_STRIDE_BYTES;
        }

        srcBytes.limit(prevLimit);
        srcBytes.position(prevPos);
        dstBytes.position(0);
        dstBytes.limit(requiredBytes);
    }

    public static int transformBlockQuadsToTriangles(
            ByteBuffer srcBytes,
            int quadVertexCount,
            float localOffsetX,
            float localOffsetY,
            float localOffsetZ,
            ByteBuffer dstBytes
    ) {
        int quadCount = quadVertexCount >> 2;
        int triVertexCount = quadCount * 6;
        int requiredSrcBytes = quadCount * (RegionLayerArena.VERTEX_STRIDE_BYTES * 4);
        int requiredDstBytes = triVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;

        int prevPos = srcBytes.position();
        int prevLimit = srcBytes.limit();
        if (prevLimit < requiredSrcBytes) {
            srcBytes.limit(requiredSrcBytes);
        }
        srcBytes.position(0);
        dstBytes.clear();

        float biasX = 8.0F + localOffsetX - 8.0F * ANTI_SEAM_SCALE;
        float biasY = 8.0F + localOffsetY - 8.0F * ANTI_SEAM_SCALE;
        float biasZ = 8.0F + localOffsetZ - 8.0F * ANTI_SEAM_SCALE;

        int srcOff = 0;
        int dstOff = 0;
        for (int q = 0; q < quadCount; q++) {
            float x0 = srcBytes.getFloat(srcOff) * ANTI_SEAM_SCALE + biasX;
            float y0 = srcBytes.getFloat(srcOff + 4) * ANTI_SEAM_SCALE + biasY;
            float z0 = srcBytes.getFloat(srcOff + 8) * ANTI_SEAM_SCALE + biasZ;
            long a0 = srcBytes.getLong(srcOff + 12);
            long b0 = srcBytes.getLong(srcOff + 20);

            float x1 = srcBytes.getFloat(srcOff + 28) * ANTI_SEAM_SCALE + biasX;
            float y1 = srcBytes.getFloat(srcOff + 32) * ANTI_SEAM_SCALE + biasY;
            float z1 = srcBytes.getFloat(srcOff + 36) * ANTI_SEAM_SCALE + biasZ;
            long a1 = srcBytes.getLong(srcOff + 40);
            long b1 = srcBytes.getLong(srcOff + 48);

            float x2 = srcBytes.getFloat(srcOff + 56) * ANTI_SEAM_SCALE + biasX;
            float y2 = srcBytes.getFloat(srcOff + 60) * ANTI_SEAM_SCALE + biasY;
            float z2 = srcBytes.getFloat(srcOff + 64) * ANTI_SEAM_SCALE + biasZ;
            long a2 = srcBytes.getLong(srcOff + 68);
            long b2 = srcBytes.getLong(srcOff + 76);

            float x3 = srcBytes.getFloat(srcOff + 84) * ANTI_SEAM_SCALE + biasX;
            float y3 = srcBytes.getFloat(srcOff + 88) * ANTI_SEAM_SCALE + biasY;
            float z3 = srcBytes.getFloat(srcOff + 92) * ANTI_SEAM_SCALE + biasZ;
            long a3 = srcBytes.getLong(srcOff + 96);
            long b3 = srcBytes.getLong(srcOff + 104);

            dstBytes.putFloat(dstOff, x0);
            dstBytes.putFloat(dstOff + 4, y0);
            dstBytes.putFloat(dstOff + 8, z0);
            dstBytes.putLong(dstOff + 12, a0);
            dstBytes.putLong(dstOff + 20, b0);

            dstBytes.putFloat(dstOff + 28, x1);
            dstBytes.putFloat(dstOff + 32, y1);
            dstBytes.putFloat(dstOff + 36, z1);
            dstBytes.putLong(dstOff + 40, a1);
            dstBytes.putLong(dstOff + 48, b1);

            dstBytes.putFloat(dstOff + 56, x2);
            dstBytes.putFloat(dstOff + 60, y2);
            dstBytes.putFloat(dstOff + 64, z2);
            dstBytes.putLong(dstOff + 68, a2);
            dstBytes.putLong(dstOff + 76, b2);

            dstBytes.putFloat(dstOff + 84, x2);
            dstBytes.putFloat(dstOff + 88, y2);
            dstBytes.putFloat(dstOff + 92, z2);
            dstBytes.putLong(dstOff + 96, a2);
            dstBytes.putLong(dstOff + 104, b2);

            dstBytes.putFloat(dstOff + 112, x3);
            dstBytes.putFloat(dstOff + 116, y3);
            dstBytes.putFloat(dstOff + 120, z3);
            dstBytes.putLong(dstOff + 124, a3);
            dstBytes.putLong(dstOff + 132, b3);

            dstBytes.putFloat(dstOff + 140, x0);
            dstBytes.putFloat(dstOff + 144, y0);
            dstBytes.putFloat(dstOff + 148, z0);
            dstBytes.putLong(dstOff + 152, a0);
            dstBytes.putLong(dstOff + 160, b0);

            srcOff += 112;
            dstOff += 168;
        }

        srcBytes.limit(prevLimit);
        srcBytes.position(prevPos);
        dstBytes.position(0);
        dstBytes.limit(requiredDstBytes);
        return triVertexCount;
    }

    /**
     * Encode les commandes DrawElementsIndirectCommand (20 octets chacune) pour Tier-1 glMultiDrawElementsIndirect.
     * Découpe proprement toute section > 65 536 sommets pour rester 100 % compatible avec QuadIndexBuffer uint16.
     */
    public static int encodeIndirectCommands(int[] firstVerts, int[] vertCounts, int batchCount, ByteBuffer dst) {
        dst.clear();
        int cap = dst.capacity();
        int emitted = 0;
        int off = 0;
        for (int i = 0; i < batchCount; i++) {
            int base = firstVerts[i];
            int rem = vertCounts[i] & ~3;
            while (rem > 0) {
                if (off + INDIRECT_CMD_BYTES > cap) {
                    break;
                }
                int sliceVerts = rem > QuadIndexBuffer.MAX_USHORT_VERTICES
                        ? QuadIndexBuffer.MAX_USHORT_VERTICES
                        : rem;
                int indexCount = (sliceVerts >> 2) * 6;
                dst.putInt(off, indexCount);      // count
                dst.putInt(off + 4, 1);           // instanceCount
                dst.putInt(off + 8, 0);           // firstIndex (toujours 0 dans QuadIndexBuffer)
                dst.putInt(off + 12, base);       // baseVertex
                dst.putInt(off + 16, 0);          // baseInstance
                off += INDIRECT_CMD_BYTES;
                emitted++;
                base += sliceVerts;
                rem -= sliceVerts;
            }
        }
        dst.position(0);
        dst.limit(off);
        return emitted;
    }

    private static void uploadToArenaZeroStall(int arenaVboId, long dstByteOffset, ByteBuffer uploadBuf) {
        int byteLen = uploadBuf.remaining();
        if (byteLen <= 0) {
            return;
        }
        if (hasGL31Copy) {
            if (uploadStagingVboId <= 0) {
                uploadStagingVboId = OpenGlHelper.glGenBuffers();
            }
            if (uploadStagingVboId > 0) {
                // Orphaning du staging VBO + copie GPU->GPU : zéro stall implicite sur arenaVboId !
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, uploadStagingVboId);
                GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, uploadBuf, GL15.GL_STREAM_DRAW);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, uploadStagingVboId);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, arenaVboId);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, dstByteOffset, byteLen);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
                return;
            }
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arenaVboId);
        GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, dstByteOffset, uploadBuf);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Loge la géométrie d'une section de chunk dans l'arène VBO contiguë de sa région 256x256 :
     * - Tier-1 (glMultiDrawElementsIndirect) : 4 sommets/quad (-33 % VRAM & bande passante).
     * - Tier-2 (glMultiDrawArrays) : 6 sommets/quad (triangles).
     * - Libération différée de 3 frames sur l'ancien créneau + copie GPU via staging VBO.
     */
    public static void uploadChunkLayerToArena(FastVertexBuffer fvb, RenderChunk rc, ByteBuffer srcBytes) {
        int byteLimit = srcBytes.limit();
        int quadVertexCount = (byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES) & ~3;
        if (quadVertexCount <= 0 || rc == null || rc.getPosition() == null) {
            fvb.purefps$setVertexCount(0);
            fvb.purefps$setIndexedArenaChunk(false);
            fvb.purefps$releaseArenaSlot();
            return;
        }

        BlockPos pos = rc.getPosition();
        int posX = pos.getX();
        int posY = pos.getY();
        int posZ = pos.getZ();
        int layerOrd = fvb.purefps$getLayerOrdinal();
        long key = packRegionLayerKey(posX, posZ, layerOrd);

        RegionLayerArena arena = ARENAS.get(key);
        boolean wantIndexed = isIndirectDrawSupported();
        if (arena == null) {
            int originX = posX & ~255;
            int originZ = posZ & ~255;
            arena = new RegionLayerArena(key, layerOrd, originX, originZ, wantIndexed);
            ARENAS.put(key, arena);
        }

        boolean indexedMode = arena.indexedQuads;
        int storedVertexCount = indexedMode ? quadVertexCount : ((quadVertexCount >> 2) * 6);
        fvb.purefps$setVertexCount(storedVertexCount);
        fvb.purefps$setIndexedArenaChunk(indexedMode);

        float localX = (float) (posX - arena.originX);
        float localY = (float) posY;
        float localZ = (float) (posZ - arena.originZ);

        int[][] consumedRanges = new int[2][];
        ByteBuffer uploadBuf = consumePendingPrebakedUpload(posX, posY, posZ, storedVertexCount, indexedMode, consumedRanges);
        int[] faceStarts = consumedRanges[0];
        int[] faceCounts = consumedRanges[1];
        if (uploadBuf == null) {
            int requiredBytes = storedVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
            ensureStagingCapacity(requiredBytes);
            faceStarts = new int[FACE_BUCKET_COUNT];
            faceCounts = new int[FACE_BUCKET_COUNT];
            if (indexedMode) {
                partitionAndTransformQuads(srcBytes, quadVertexCount, localX, localY, localZ, stagingBuffer, faceStarts, faceCounts);
            } else {
                partitionAndTransformQuadsToTriangles(srcBytes, quadVertexCount, localX, localY, localZ, stagingBuffer, faceStarts, faceCounts);
            }
            uploadBuf = stagingBuffer;
        }

        RegionLayerArena prevArena = fvb.purefps$getArena();
        int prevFirst = fvb.purefps$getArenaFirstVertex();
        int prevAlloc = fvb.purefps$getArenaAllocVertices();

        int prevHwm = arena.allocator.getHighWaterMark();
        int alignedVerts = FreeRangeAllocator.alignVertices(storedVertexCount);
        int newFirst = arena.allocator.allocate(alignedVerts);
        int requiredCap = arena.allocator.getHighWaterMark();
        boolean useVao = hasGL30Vao && PureFPSConfig.hardwareRegionVao;
        arena.ensureCapacity(requiredCap, prevHwm, hasGL31Copy, useVao);

        // Diffère la libération de l'ancien slot de 3 frames (zéro hazard mémoire GPU)
        if (prevArena != null && prevArena.vboId >= 0 && prevFirst >= 0 && prevAlloc > 0) {
            scheduleArenaSlotFree(prevArena, prevFirst, prevAlloc);
        }

        uploadToArenaZeroStall(
                arena.vboId,
                (long) newFirst * (long) RegionLayerArena.VERTEX_STRIDE_BYTES,
                uploadBuf
        );

        fvb.purefps$setArenaSlot(arena, newFirst, alignedVerts);
        fvb.purefps$setFaceRanges(faceStarts, faceCounts);
    }

    private static long uploadIndirectCommandsToRing(ByteBuffer cmdBytes, int cmdCount) {
        if (indirectRingVboId <= 0) {
            indirectRingVboId = OpenGlHelper.glGenBuffers();
            if (indirectRingVboId <= 0) {
                indirectRingFailed = true;
                return -1L;
            }
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectRingVboId);
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, INDIRECT_RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            indirectRingWriteCmd = 0;
        } else {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectRingVboId);
        }

        int byteLen = cmdCount * INDIRECT_CMD_BYTES;
        if (cmdCount > INDIRECT_RING_CAPACITY_CMDS) {
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, cmdBytes, GL15.GL_STREAM_DRAW);
            indirectRingWriteCmd = 0;
            return 0L;
        }
        if (indirectRingWriteCmd + cmdCount > INDIRECT_RING_CAPACITY_CMDS) {
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, INDIRECT_RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            indirectRingWriteCmd = 0;
        }
        long byteOffset = (long) indirectRingWriteCmd * INDIRECT_CMD_BYTES;
        indirectRingWriteCmd += cmdCount;

        if (hasGL30Vao) {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    GL40.GL_DRAW_INDIRECT_BUFFER,
                    byteOffset,
                    byteLen,
                    MAP_FLAGS_UNSYNC,
                    indirectMappedScratch
            );
            if (mapped != null) {
                indirectMappedScratch = mapped;
                mapped.clear();
                mapped.put(cmdBytes);
                GL15.glUnmapBuffer(GL40.GL_DRAW_INDIRECT_BUFFER);
                return byteOffset;
            }
        }
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, byteOffset, cmdBytes);
        return byteOffset;
    }

    public static void renderLayer(
            EnumWorldBlockLayer layer,
            List<RenderChunk> renderChunks,
            double viewX,
            double viewY,
            double viewZ
    ) {
        int count = renderChunks.size();
        if (count == 0) {
            return;
        }

        int layerOrd = layer.ordinal();
        boolean useMultiDraw = layerOrd < 3 && PureFPSConfig.regionMultiDraw && isMultiDrawSupported();
        boolean trackAnims = PureFPSConfig.enabled && PureFPSConfig.smartAnimations;
        boolean callerSkippedVao0 = canSkipVao0ClientStates(layer);
        boolean vao0StatesEnabledHere = false;

        int drawnSections = 0;
        boolean hasFallbackChunks = false;

        if (useMultiDraw) {
            ACTIVE_BATCH_ARENAS.clear();
            for (int i = 0; i < count; i++) {
                RenderChunk rc = renderChunks.get(i);
                if (trackAnims) {
                    SmartAnimations.markCompiledChunkVisible(rc.getCompiledChunk());
                }
                VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
                if (vb == null) {
                    continue;
                }
                FastVertexBuffer fvb = (FastVertexBuffer) vb;
                int vCount = fvb.purefps$getVertexCount();
                if (vCount <= 0) {
                    continue;
                }
                RegionLayerArena arena = fvb.purefps$getArena();
                int firstVert = fvb.purefps$getArenaFirstVertex();
                if (arena != null && arena.vboId >= 0 && firstVert >= 0) {
                    if (arena.batchSize == 0) {
                        ACTIVE_BATCH_ARENAS.add(arena);
                    }
                    // Wave 4.1 : 6-Direction Face-Plane Culling (-45 % sommets Vertex Shader sans perte visuelle)
                    int[] faceStarts = fvb.purefps$getFaceStarts();
                    int[] faceCounts = fvb.purefps$getFaceCounts();
                    if (faceStarts != null && faceCounts != null) {
                        BlockPos bp = rc.getPosition();
                        int minX = bp.getX(), minY = bp.getY(), minZ = bp.getZ();
                        // Marge 0.05D pour compenser l'échelle anti-couture 1.0005 autour du centre de section
                        if (faceCounts[0] > 0 && viewY < (double) (minY + 16) + 0.05D) arena.addDrawCommand(firstVert + faceStarts[0], faceCounts[0]);
                        if (faceCounts[1] > 0 && viewY > (double) minY - 0.05D)        arena.addDrawCommand(firstVert + faceStarts[1], faceCounts[1]);
                        if (faceCounts[2] > 0 && viewZ < (double) (minZ + 16) + 0.05D) arena.addDrawCommand(firstVert + faceStarts[2], faceCounts[2]);
                        if (faceCounts[3] > 0 && viewZ > (double) minZ - 0.05D)        arena.addDrawCommand(firstVert + faceStarts[3], faceCounts[3]);
                        if (faceCounts[4] > 0 && viewX < (double) (minX + 16) + 0.05D) arena.addDrawCommand(firstVert + faceStarts[4], faceCounts[4]);
                        if (faceCounts[5] > 0 && viewX > (double) minX - 0.05D)        arena.addDrawCommand(firstVert + faceStarts[5], faceCounts[5]);
                        if (faceCounts[6] > 0)                                         arena.addDrawCommand(firstVert + faceStarts[6], faceCounts[6]);
                    } else {
                        arena.addDrawCommand(firstVert, vCount);
                    }
                    drawnSections++;
                } else {
                    hasFallbackChunks = true;
                }
            }

            int arenaCount = ACTIVE_BATCH_ARENAS.size();
            if (arenaCount > 0) {
                boolean allowVao = hasGL30Vao && PureFPSConfig.hardwareRegionVao;
                boolean anyVaoBound = false;
                boolean vaoCurrentlyBound = false;
                boolean indirectBufferBound = false;
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

                GlStateManager.pushMatrix();
                float prevTx = 0.0F;
                float prevTy = 0.0F;
                float prevTz = 0.0F;

                for (int a = 0; a < arenaCount; a++) {
                    RegionLayerArena arena = ACTIVE_BATCH_ARENAS.get(a);
                    int n = arena.batchSize;
                    arena.batchSize = 0;
                    if (n <= 0 || arena.vboId < 0) {
                        continue;
                    }

                    float tx = (float) ((double) arena.originX - viewX);
                    float ty = (float) (-viewY);
                    float tz = (float) ((double) arena.originZ - viewZ);
                    float dx = tx - prevTx;
                    float dy = ty - prevTy;
                    float dz = tz - prevTz;
                    if (dx != 0.0F || dy != 0.0F || dz != 0.0F) {
                        GL11.glTranslatef(dx, dy, dz);
                        prevTx = tx;
                        prevTy = ty;
                        prevTz = tz;
                    }

                    if (allowVao && arena.vaoId > 0) {
                        GL30.glBindVertexArray(arena.vaoId);
                        vaoCurrentlyBound = true;
                        anyVaoBound = true;
                    } else {
                        if (vaoCurrentlyBound) {
                            GL30.glBindVertexArray(0);
                            vaoCurrentlyBound = false;
                        }
                        if (callerSkippedVao0 && !vao0StatesEnabledHere) {
                            enableBlockClientStates();
                            vao0StatesEnabledHere = true;
                        }
                        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
                        setupBlockArrayPointers();
                    }

                    if (arena.indexedQuads && vaoCurrentlyBound && (hasGL43Indirect || hasArbIndirect) && !indirectRingFailed) {
                        int neededCmds = 0;
                        for (int k = 0; k < n; k++) {
                            int vc = arena.batchCount[k] & ~3;
                            if (vc > 0) {
                                neededCmds += (vc + QuadIndexBuffer.MAX_USHORT_VERTICES - 1) / QuadIndexBuffer.MAX_USHORT_VERTICES;
                            }
                        }
                        int maxCmdBytes = Math.max(neededCmds, n) * INDIRECT_CMD_BYTES;
                        if (indirectCmdScratch.capacity() < maxCmdBytes) {
                            indirectCmdScratch = ByteBuffer.allocateDirect(Math.max(indirectCmdScratch.capacity() << 1, maxCmdBytes))
                                    .order(ByteOrder.nativeOrder());
                        }
                        int emitted = encodeIndirectCommands(arena.batchFirst, arena.batchCount, n, indirectCmdScratch);
                        if (emitted > 0) {
                            long byteOffset = uploadIndirectCommandsToRing(indirectCmdScratch, emitted);
                            if (byteOffset >= 0L) {
                                indirectBufferBound = true;
                                if (hasGL43Indirect) {
                                    GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_SHORT, byteOffset, emitted, 0);
                                } else {
                                    ARBMultiDrawIndirect.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_SHORT, byteOffset, emitted, 0);
                                }
                            }
                        }
                    } else if (arena.indexedQuads) {
                        // Repli sécurisé si VAO indisponible sur une arène indexée
                        int eboId = QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
                        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
                        for (int k = 0; k < n; k++) {
                            int base = arena.batchFirst[k];
                            int rem = arena.batchCount[k] & ~3;
                            while (rem > 0) {
                                int slice = Math.min(rem, QuadIndexBuffer.MAX_USHORT_VERTICES);
                                org.lwjgl.opengl.GL32.glDrawElementsBaseVertex(
                                        GL11.GL_TRIANGLES, (slice >> 2) * 6, GL11.GL_UNSIGNED_SHORT, 0L, base
                                );
                                base += slice;
                                rem -= slice;
                            }
                        }
                        if (!vaoCurrentlyBound) {
                            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
                        }
                    } else {
                        if (n == 1) {
                            GL11.glDrawArrays(GL11.GL_TRIANGLES, arena.batchFirst[0], arena.batchCount[0]);
                        } else {
                            if (multiFirstBuf.capacity() < n) {
                                int newCap = Math.max(multiFirstBuf.capacity() << 1, n);
                                multiFirstBuf = BufferUtils.createIntBuffer(newCap);
                                multiCountBuf = BufferUtils.createIntBuffer(newCap);
                            }
                            multiFirstBuf.clear();
                            multiFirstBuf.put(arena.batchFirst, 0, n);
                            multiFirstBuf.flip();

                            multiCountBuf.clear();
                            multiCountBuf.put(arena.batchCount, 0, n);
                            multiCountBuf.flip();

                            GL14.glMultiDrawArrays(GL11.GL_TRIANGLES, multiFirstBuf, multiCountBuf);
                        }
                    }

                    PureFPSConfig.frameMultiDrawsAccumulator++;
                }

                if (indirectBufferBound) {
                    GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
                }
                if (anyVaoBound) {
                    GL30.glBindVertexArray(0);
                }
                GlStateManager.popMatrix();
                ACTIVE_BATCH_ARENAS.clear();
            }

            if (!hasFallbackChunks) {
                if (vao0StatesEnabledHere) {
                    disableBlockClientStates();
                }
                if (drawnSections > 0) {
                    PureFPSConfig.frameChunksBatchedAccumulator += drawnSections;
                }
                return;
            }
        }

        if (callerSkippedVao0 && !vao0StatesEnabledHere) {
            enableBlockClientStates();
            vao0StatesEnabledHere = true;
        }

        boolean indexSupported = false;
        boolean indexChecked = false;
        int ushortEbo = -1;
        int boundEbo = -1;

        GlStateManager.pushMatrix();
        float prevX = 0.0F;
        float prevY = 0.0F;
        float prevZ = 0.0F;
        int fallbackDrawn = 0;
        boolean onLightmapUnit = false;
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

        for (int i = 0; i < count; i++) {
            RenderChunk rc = renderChunks.get(i);
            if (!useMultiDraw && trackAnims) {
                SmartAnimations.markCompiledChunkVisible(rc.getCompiledChunk());
            }
            VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
            if (vb == null) {
                continue;
            }
            FastVertexBuffer fvb = (FastVertexBuffer) vb;
            if (useMultiDraw && fvb.purefps$getArena() != null && fvb.purefps$getArena().vboId >= 0) {
                continue;
            }
            int vCount = fvb.purefps$getVertexCount();
            int glBuf = fvb.purefps$getGlBufferId();
            if (vCount <= 0 || glBuf < 0) {
                continue;
            }

            BlockPos pos = rc.getPosition();
            float targetX = (float) ((double) pos.getX() - viewX);
            float targetY = (float) ((double) pos.getY() - viewY);
            float targetZ = (float) ((double) pos.getZ() - viewZ);
            float dx = targetX - prevX;
            float dy = targetY - prevY;
            float dz = targetZ - prevZ;
            if (dx != 0.0F || dy != 0.0F || dz != 0.0F) {
                GL11.glTranslatef(dx, dy, dz);
                prevX = targetX;
                prevY = targetY;
                prevZ = targetZ;
            }

            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, glBuf);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
            if (!onLightmapUnit) {
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
                onLightmapUnit = true;
            } else {
                GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
                onLightmapUnit = false;
            }

            if (fvb.purefps$isTriangleBuffer()) {
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vCount);
            } else {
                int customEbo = fvb.purefps$getCustomIndexBufferId();
                if (customEbo > 0 && (vCount & 3) == 0) {
                    if (customEbo != boundEbo) {
                        boundEbo = customEbo;
                        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
                    }
                    GL11.glDrawElements(
                            GL11.GL_TRIANGLES,
                            QuadIndexBuffer.quadVertexCountToIndexCount(vCount),
                            GL11.GL_UNSIGNED_SHORT,
                            0L
                    );
                } else {
                    if (!indexChecked) {
                        indexSupported = QuadIndexBuffer.isSupported();
                        if (indexSupported) {
                            ushortEbo = QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
                        }
                        indexChecked = true;
                    }
                    if (indexSupported && (vCount & 3) == 0) {
                        int neededEbo = vCount <= QuadIndexBuffer.MAX_USHORT_VERTICES
                                ? ushortEbo
                                : QuadIndexBuffer.ensureIndexBufferReady(vCount);
                        if (neededEbo != boundEbo) {
                            boundEbo = neededEbo;
                            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
                        }
                        GL11.glDrawElements(
                                GL11.GL_TRIANGLES,
                                QuadIndexBuffer.quadVertexCountToIndexCount(vCount),
                                QuadIndexBuffer.resolveIndexType(vCount),
                                0L
                        );
                    } else {
                        GL11.glDrawArrays(GL11.GL_QUADS, 0, vCount);
                    }
                }
            }
            fallbackDrawn++;
        }

        if (boundEbo >= 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        if (onLightmapUnit) {
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        GlStateManager.popMatrix();
        if (vao0StatesEnabledHere) {
            disableBlockClientStates();
        }

        int totalDrawn = drawnSections + fallbackDrawn;
        if (totalDrawn > 0) {
            PureFPSConfig.frameChunksBatchedAccumulator += totalDrawn;
            if (fallbackDrawn > 0) {
                PureFPSConfig.frameMultiDrawsAccumulator += fallbackDrawn;
            }
        }
    }

    public static void setupBlockArrayPointers() {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    public static void clearAll() {
        clearPendingPrebakedUpload();
        PENDING_EMPTY_LAYER_CHUNKS.clear();
        DEFERRED_FREES.clear();
        ACTIVE_BATCH_ARENAS.clear();
        if (!ARENAS.isEmpty()) {
            for (RegionLayerArena arena : ARENAS.values()) {
                if (arena != null) {
                    arena.destroy();
                }
            }
            ARENAS.clear();
        }
        if (uploadStagingVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(uploadStagingVboId);
            } catch (Exception | LinkageError ignored) {
            }
            uploadStagingVboId = -1;
        }
        if (indirectRingVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(indirectRingVboId);
            } catch (Exception | LinkageError ignored) {
            }
            indirectRingVboId = -1;
            indirectMappedScratch = null;
            indirectRingWriteCmd = 0;
        }
    }
}
