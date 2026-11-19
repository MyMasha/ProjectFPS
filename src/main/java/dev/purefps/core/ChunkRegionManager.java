package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Moteur GPU Ultra-Haute-Fréquence d'Arènes Régionales MultiDraw + VAO GL 3.0 (MashClient 4.5.13) :
 * - Fusionne jusqu'à 256+ sections de chunks par région 256x256 blocs dans un unique tampon VRAM contigu
 *   (RegionLayerArena) avec VAO matériel OpenGL 3.0 (1 seul glBindVertexArray + 1 seul glMultiDrawArrays par région).
 * - Déporte la conversion quads -> triangles + translation régionale sur les threads workers ChunkRenderWorker
 *   pour libérer le thread OpenGL principal lors des uploads de chunks.
 * - Conserve le repli indexé QuadIndexBuffer GL_TRIANGLES (4 sommets/quad) pour la couche TRANSLUCENT afin de
 *   garantir un tri alpha arrière-vers-avant 100 % exact avec 33 % de bande passante VRAM en moins.
 */
public final class ChunkRegionManager {

    public static final float ANTI_SEAM_SCALE = 1.000001F;
    private static final float ANTI_SEAM_BIAS = 8.0F - 8.0F * ANTI_SEAM_SCALE;

    public static final float[] STATIC_ANTI_SEAM_MATRIX = computeStaticAntiSeamMatrix();

    private static final Set<ByteBuffer> WORKER_BAKED_BUFFERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<ByteBuffer, Boolean>()));

    private static final Map<Long, RegionLayerArena> ARENAS = new HashMap<Long, RegionLayerArena>();
    private static final List<RegionLayerArena> ACTIVE_BATCH_ARENAS = new ArrayList<RegionLayerArena>(8);

    // Pool lock-free de tampons directs pour le pré-calcul quad->triangles sur les threads ChunkRenderWorker
    private static final int MAX_POOLED_WORKER_BUFFERS = 16;
    private static final int MAX_POOLED_BUFFER_BYTES = 512 * 1024;
    private static final ConcurrentLinkedQueue<ByteBuffer> WORKER_TRI_POOL = new ConcurrentLinkedQueue<ByteBuffer>();
    private static final AtomicInteger WORKER_TRI_POOL_SIZE = new AtomicInteger(0);

    // Passage zéro-allocation entre ChunkUploadTask.run() et uploadChunkLayerToArena() sur le thread OpenGL principal
    private static ByteBuffer pendingPrebakedBuf = null;
    private static int pendingPrebakedTriVerts = 0;
    private static int pendingPrebakedPosX = 0;
    private static int pendingPrebakedPosY = 0;
    private static int pendingPrebakedPosZ = 0;

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
    private static IntBuffer multiFirstBuf = BufferUtils.createIntBuffer(512);
    private static IntBuffer multiCountBuf = BufferUtils.createIntBuffer(512);

    private static volatile int capabilityState = 0;
    private static volatile int multiDrawState = 0;
    private static boolean hasGL31Copy = false;
    private static boolean hasGL30Vao = false;

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

    public static boolean isSupported() {
        if (capabilityState == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                boolean ok = caps != null && caps.OpenGL15 && OpenGlHelper.useVbo();
                hasGL31Copy = caps != null && caps.OpenGL31;
                hasGL30Vao = caps != null && caps.OpenGL30;
                capabilityState = ok ? 1 : -1;
            } catch (Exception | LinkageError t) {
                capabilityState = -1;
            }
        }
        return capabilityState == 1;
    }

    public static boolean isMultiDrawSupported() {
        if (multiDrawState == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                boolean ok = caps != null && caps.OpenGL15 && caps.OpenGL14 && OpenGlHelper.useVbo();
                hasGL31Copy = caps != null && caps.OpenGL31;
                hasGL30Vao = caps != null && caps.OpenGL30;
                multiDrawState = ok ? 1 : -1;
            } catch (Exception | LinkageError t) {
                multiDrawState = -1;
            }
        }
        return multiDrawState == 1;
    }

    /**
     * Lecture thread-safe depuis les threads workers (ne touche jamais GLContext).
     */
    public static boolean isMultiDrawReadyForWorkers() {
        return multiDrawState == 1;
    }

    public static ByteBuffer acquireWorkerTriBuffer(int requiredBytes) {
        ByteBuffer candidate = WORKER_TRI_POOL.poll();
        if (candidate != null) {
            WORKER_TRI_POOL_SIZE.decrementAndGet();
            if (candidate.capacity() >= requiredBytes) {
                candidate.clear();
                return candidate;
            }
        }
        int alloc = Math.max(65536, requiredBytes);
        return ByteBuffer.allocateDirect(alloc).order(ByteOrder.nativeOrder());
    }

    public static void releaseWorkerTriBuffer(ByteBuffer buf) {
        if (buf == null || buf.capacity() > MAX_POOLED_BUFFER_BYTES) {
            return;
        }
        if (WORKER_TRI_POOL_SIZE.get() >= MAX_POOLED_WORKER_BUFFERS) {
            return;
        }
        buf.clear();
        if (WORKER_TRI_POOL_SIZE.incrementAndGet() <= MAX_POOLED_WORKER_BUFFERS) {
            WORKER_TRI_POOL.offer(buf);
        } else {
            WORKER_TRI_POOL_SIZE.decrementAndGet();
        }
    }

    /**
     * Exécuté sur les threads ChunkRenderWorker : convertit les quads en triangles régionaux hors thread OpenGL.
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

    public static void setPendingPrebakedUpload(ByteBuffer triBuf, int triVerts, int posX, int posY, int posZ) {
        pendingPrebakedBuf = triBuf;
        pendingPrebakedTriVerts = triVerts;
        pendingPrebakedPosX = posX;
        pendingPrebakedPosY = posY;
        pendingPrebakedPosZ = posZ;
    }

    public static void clearPendingPrebakedUpload() {
        pendingPrebakedBuf = null;
        pendingPrebakedTriVerts = 0;
    }

    private static ByteBuffer consumePendingPrebakedUpload(int posX, int posY, int posZ, int triVerts) {
        ByteBuffer buf = pendingPrebakedBuf;
        pendingPrebakedBuf = null;
        if (buf != null
                && pendingPrebakedTriVerts == triVerts
                && pendingPrebakedPosX == posX
                && pendingPrebakedPosY == posY
                && pendingPrebakedPosZ == posZ) {
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

    /**
     * Convertit une fois pour toutes les quads (4 sommets) en triangles matériels natifs (6 sommets : v0,v1,v2, v2,v3,v0)
     * lors de l'upload dans RegionLayerArena. Élimine 100 % de la conversion CPU u_primconvert / Geometry Shader
     * de Mesa3D Zink sur chaque appel GL14.glMultiDrawArrays(GL11.GL_TRIANGLES) en mode Vulkan !
     */
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

            // Triangle 1 : v0, v1, v2
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

            // Triangle 2 : v2, v3, v0
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
     * Loge la géométrie d'une section de chunk dans l'arène VBO contiguë de sa région 256x256 :
     * alloue d'abord le nouveau créneau AVANT de libérer l'ancien pour garantir zéro attente GPU implicite.
     */
    public static void uploadChunkLayerToArena(FastVertexBuffer fvb, RenderChunk rc, ByteBuffer srcBytes) {
        int byteLimit = srcBytes.limit();
        int quadVertexCount = byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES;
        int triVertexCount = (quadVertexCount >> 2) * 6;
        fvb.purefps$setVertexCount(triVertexCount);

        if (triVertexCount <= 0 || rc == null || rc.getPosition() == null) {
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
        if (arena == null) {
            int originX = posX & ~255;
            int originZ = posZ & ~255;
            arena = new RegionLayerArena(key, layerOrd, originX, originZ);
            ARENAS.put(key, arena);
        }

        float localX = (float) (posX - arena.originX);
        float localY = (float) posY;
        float localZ = (float) (posZ - arena.originZ);

        ByteBuffer uploadBuf = consumePendingPrebakedUpload(posX, posY, posZ, triVertexCount);
        if (uploadBuf == null) {
            int requiredBytes = triVertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
            ensureStagingCapacity(requiredBytes);
            transformBlockQuadsToTriangles(srcBytes, quadVertexCount, localX, localY, localZ, stagingBuffer);
            uploadBuf = stagingBuffer;
        }

        RegionLayerArena prevArena = fvb.purefps$getArena();
        int prevFirst = fvb.purefps$getArenaFirstVertex();
        int prevAlloc = fvb.purefps$getArenaAllocVertices();

        int prevHwm = arena.allocator.getHighWaterMark();
        int alignedVerts = FreeRangeAllocator.alignVertices(triVertexCount);
        int newFirst = arena.allocator.allocate(alignedVerts);
        int requiredCap = arena.allocator.getHighWaterMark();
        boolean useVao = hasGL30Vao && PureFPSConfig.hardwareRegionVao;
        arena.ensureCapacity(requiredCap, prevHwm, hasGL31Copy, useVao);

        if (prevArena != null && prevArena.vboId >= 0 && prevFirst >= 0 && prevAlloc > 0) {
            prevArena.allocator.free(prevFirst, prevAlloc);
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
        GL15.glBufferSubData(
                OpenGlHelper.GL_ARRAY_BUFFER,
                (long) newFirst * (long) RegionLayerArena.VERTEX_STRIDE_BYTES,
                uploadBuf
        );
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        fvb.purefps$setArenaSlot(arena, newFirst, alignedVerts);
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

        int drawnSections = 0;
        boolean hasFallbackChunks = false;

        if (useMultiDraw) {
            ACTIVE_BATCH_ARENAS.clear();
            for (int i = 0; i < count; i++) {
                RenderChunk rc = renderChunks.get(i);
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
                    arena.addDrawCommand(firstVert, vCount);
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
                        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
                        setupBlockArrayPointers();
                    }

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

                    PureFPSConfig.frameMultiDrawsAccumulator++;
                }

                if (anyVaoBound) {
                    GL30.glBindVertexArray(0);
                }
                GlStateManager.popMatrix();
                ACTIVE_BATCH_ARENAS.clear();
            }

            if (!hasFallbackChunks) {
                if (drawnSections > 0) {
                    PureFPSConfig.frameChunksBatchedAccumulator += drawnSections;
                }
                return;
            }
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
            fallbackDrawn++;
        }

        if (boundEbo >= 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        if (onLightmapUnit) {
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        }
        GlStateManager.popMatrix();

        int totalDrawn = drawnSections + fallbackDrawn;
        if (totalDrawn > 0) {
            PureFPSConfig.frameChunksBatchedAccumulator += totalDrawn;
            if (fallbackDrawn > 0) {
                PureFPSConfig.frameMultiDrawsAccumulator++;
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
        ACTIVE_BATCH_ARENAS.clear();
        if (!ARENAS.isEmpty()) {
            for (RegionLayerArena arena : ARENAS.values()) {
                if (arena != null) {
                    arena.destroy();
                }
            }
            ARENAS.clear();
        }
    }
}
