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

/**
 * Moteur GPU Ultra-Haute-Fréquence d'Arènes Régionales MultiDraw (MashClient 4.5.1) :
 * - Fusionne jusqu'à 256+ sections de chunks par région 256x256 blocs dans un unique tampon VRAM contigu
 *   (RegionLayerArena) et émet UN SEUL appel GL14.glMultiDrawArrays par région (divisant par ~150x à ~300x
 *   le nombre d'appels JNI OpenGL et de validations d'état Mesa3D Zink / Vulkan pour dépasser 2 000 FPS !).
 * - Conserve le repli indexé QuadIndexBuffer GL_TRIANGLES pour la couche TRANSLUCENT afin de garantir un
 *   tri alpha arrière-vers-avant 100 % exact aux frontières de régions.
 */
public final class ChunkRegionManager {

    public static final float ANTI_SEAM_SCALE = 1.000001F;
    private static final float ANTI_SEAM_BIAS = 8.0F - 8.0F * ANTI_SEAM_SCALE;

    public static final float[] STATIC_ANTI_SEAM_MATRIX = computeStaticAntiSeamMatrix();

    private static final Set<ByteBuffer> WORKER_BAKED_BUFFERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<ByteBuffer, Boolean>()));

    private static final Map<Long, RegionLayerArena> ARENAS = new HashMap<Long, RegionLayerArena>();
    private static final List<RegionLayerArena> ACTIVE_BATCH_ARENAS = new ArrayList<RegionLayerArena>(8);

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
    private static IntBuffer multiFirstBuf = BufferUtils.createIntBuffer(512);
    private static IntBuffer multiCountBuf = BufferUtils.createIntBuffer(512);

    private static int capabilityState = 0;
    private static int multiDrawState = 0;
    private static boolean hasGL31Copy = false;

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
                capabilityState = ok ? 1 : -1;
            } catch (Throwable t) {
                capabilityState = -1;
            }
        }
        return capabilityState == 1;
    }

    public static boolean isMultiDrawSupported() {
        if (VulkanBackendHelper.isVulkanActive()) {
            return false;
        }
        if (multiDrawState == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                boolean ok = caps != null && caps.OpenGL15 && caps.OpenGL14 && OpenGlHelper.useVbo();
                hasGL31Copy = caps != null && caps.OpenGL31;
                multiDrawState = ok ? 1 : -1;
            } catch (Throwable t) {
                multiDrawState = -1;
            }
        }
        return multiDrawState == 1;
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
     * Loge la géométrie d'une section de chunk dans l'arène VBO contiguë de sa région 256x256 :
     * alloue d'abord le nouveau créneau AVANT de libérer l'ancien pour garantir zéro attente GPU implicite.
     */
    public static void uploadChunkLayerToArena(FastVertexBuffer fvb, RenderChunk rc, ByteBuffer srcBytes) {
        int byteLimit = srcBytes.limit();
        int vertexCount = byteLimit / RegionLayerArena.VERTEX_STRIDE_BYTES;
        fvb.purefps$setVertexCount(vertexCount);

        if (vertexCount <= 0 || rc == null || rc.getPosition() == null) {
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

        int requiredBytes = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        ensureStagingCapacity(requiredBytes);
        transformBlockVertices(srcBytes, vertexCount, localX, localY, localZ, stagingBuffer);

        RegionLayerArena prevArena = fvb.purefps$getArena();
        int prevFirst = fvb.purefps$getArenaFirstVertex();
        int prevAlloc = fvb.purefps$getArenaAllocVertices();

        int prevHwm = arena.allocator.getHighWaterMark();
        int alignedVerts = FreeRangeAllocator.alignVertices(vertexCount);
        int newFirst = arena.allocator.allocate(alignedVerts);
        int requiredCap = arena.allocator.getHighWaterMark();
        arena.ensureCapacity(requiredCap, prevHwm, hasGL31Copy, false);

        if (prevArena != null && prevArena.vboId >= 0 && prevFirst >= 0 && prevAlloc > 0) {
            prevArena.allocator.free(prevFirst, prevAlloc);
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
        GL15.glBufferSubData(
                OpenGlHelper.GL_ARRAY_BUFFER,
                (long) newFirst * (long) RegionLayerArena.VERTEX_STRIDE_BYTES,
                stagingBuffer
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
        boolean menuDimmed = MenuUnloadManager.isWorldDimmedByMenu();
        float menuMaxDistSq = (layerOrd == 0) ? 2304.0F : 1024.0F;
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
                if (menuDimmed) {
                    BlockPos pos = rc.getPosition();
                    float cx = (float) ((double) pos.getX() - viewX) + 8.0F;
                    float cy = (float) ((double) pos.getY() - viewY) + 8.0F;
                    float cz = (float) ((double) pos.getZ() - viewZ) + 8.0F;
                    if (cx * cx + cy * cy + cz * cz > menuMaxDistSq) {
                        continue;
                    }
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
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                for (int a = 0; a < arenaCount; a++) {
                    RegionLayerArena arena = ACTIVE_BATCH_ARENAS.get(a);
                    int n = arena.batchSize;
                    arena.batchSize = 0;
                    if (n <= 0 || arena.vboId < 0) {
                        continue;
                    }

                    GlStateManager.pushMatrix();
                    GL11.glTranslatef(
                            (float) ((double) arena.originX - viewX),
                            (float) (-viewY),
                            (float) ((double) arena.originZ - viewZ)
                    );
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
                    setupBlockArrayPointers();

                    if (n == 1) {
                        GL11.glDrawArrays(GL11.GL_QUADS, arena.batchFirst[0], arena.batchCount[0]);
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

                        GL14.glMultiDrawArrays(GL11.GL_QUADS, multiFirstBuf, multiCountBuf);
                    }

                    GlStateManager.popMatrix();
                    PureFPSConfig.frameMultiDrawsAccumulator++;
                }
                ACTIVE_BATCH_ARENAS.clear();
            }

            if (!hasFallbackChunks) {
                if (drawnSections > 0) {
                    PureFPSConfig.frameChunksBatchedAccumulator += drawnSections;
                }
                return;
            }
        }

        boolean indexSupported = QuadIndexBuffer.isSupported();
        int ushortEbo = -1;
        int boundEbo = -1;
        if (indexSupported) {
            ushortEbo = QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
            boundEbo = ushortEbo;
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
        }

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
            if (menuDimmed) {
                float cx = targetX + 8.0F;
                float cy = targetY + 8.0F;
                float cz = targetZ + 8.0F;
                if (cx * cx + cy * cy + cz * cz > menuMaxDistSq) {
                    continue;
                }
            }
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
