package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moteur GPU Region MultiDraw + VAO Matériel OpenGL 3.0 (PureClient 3.5) :
 * - Regroupe jusqu'à 256 sections de chunks par arène régionale 256x256 blocs.
 * - Remplace les 8 appels JNI d'état client par lot par UN SEUL GL30.glBindVertexArray(arena.vaoId).
 * - Émet un seul GL14.glMultiDrawArrays par arène active.
 */
public final class ChunkRegionManager {

    private static final float ANTI_SEAM_SCALE = 1.000001F;
    private static final Map<Long, RegionLayerArena> ARENAS = new HashMap<Long, RegionLayerArena>(64);
    private static final List<RegionLayerArena> ACTIVE_ARENAS = new ArrayList<RegionLayerArena>(32);

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
    private static IntBuffer multiFirstBuf = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private static IntBuffer multiCountBuf = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();

    private static int capabilityState = 0; // 0 = inconnu, 1 = supporté, -1 = non supporté
    private static boolean hasGL30Vao = false;
    private static boolean hasGL31Copy = false;

    private ChunkRegionManager() {
    }

    public static boolean isSupported() {
        if (capabilityState == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                boolean ok = caps != null && caps.OpenGL15 && caps.OpenGL14 && OpenGlHelper.useVbo();
                hasGL30Vao = caps != null && caps.OpenGL30;
                hasGL31Copy = caps != null && caps.OpenGL31;
                capabilityState = ok ? 1 : -1;
            } catch (Throwable t) {
                capabilityState = -1;
            }
        }
        return capabilityState == 1;
    }

    public static long packRegionLayerKey(int blockX, int blockZ, int layerOrdinal) {
        int rx = blockX >> 8;
        int rz = blockZ >> 8;
        return (((long) rx & 0xFFFFFFFL) << 36) | (((long) rz & 0xFFFFFFFL) << 8) | ((long) layerOrdinal & 0xFFL);
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

        int byteOff = 0;
        for (int v = 0; v < vertexCount; v++) {
            float vx = srcBytes.getFloat(byteOff);
            float vy = srcBytes.getFloat(byteOff + 4);
            float vz = srcBytes.getFloat(byteOff + 8);

            float rx = (vx - 8.0F) * ANTI_SEAM_SCALE + 8.0F + localOffsetX;
            float ry = (vy - 8.0F) * ANTI_SEAM_SCALE + 8.0F + localOffsetY;
            float rz = (vz - 8.0F) * ANTI_SEAM_SCALE + 8.0F + localOffsetZ;

            dstBytes.putFloat(byteOff, rx);
            dstBytes.putFloat(byteOff + 4, ry);
            dstBytes.putFloat(byteOff + 8, rz);
            dstBytes.putLong(byteOff + 12, srcBytes.getLong(byteOff + 12));
            dstBytes.putLong(byteOff + 20, srcBytes.getLong(byteOff + 20));
            byteOff += RegionLayerArena.VERTEX_STRIDE_BYTES;
        }

        srcBytes.limit(prevLimit);
        srcBytes.position(prevPos);
        dstBytes.position(0);
        dstBytes.limit(requiredBytes);
    }

    public static void uploadChunkLayer(EnumWorldBlockLayer layer, WorldRenderer worldRenderer, RenderChunk renderChunk) {
        isSupported();
        int layerOrd = layer.ordinal();
        RegionChunkSlot slot = (RegionChunkSlot) renderChunk;
        int vertexCount = worldRenderer.getVertexCount();
        worldRenderer.reset();

        if (vertexCount <= 0) {
            freeChunkLayer(slot, layerOrd);
            return;
        }

        BlockPos pos = renderChunk.getPosition();
        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();
        long key = packRegionLayerKey(bx, bz, layerOrd);

        RegionLayerArena arena = ARENAS.get(key);
        if (arena == null) {
            int originX = (bx >> 8) << 8;
            int originZ = (bz >> 8) << 8;
            arena = new RegionLayerArena(key, layerOrd, originX, originZ);
            ARENAS.put(key, arena);
        }

        int neededAligned = FreeRangeAllocator.alignVertices(vertexCount);
        RegionLayerArena prevArena = (RegionLayerArena) slot.purefps$getArena(layerOrd);
        int prevAlloc = slot.purefps$getAllocatedVertices(layerOrd);
        int firstVertex;

        if (prevArena == arena && prevAlloc >= neededAligned && neededAligned >= (prevAlloc >> 1) && arena.vboId >= 0) {
            firstVertex = slot.purefps$getFirstVertex(layerOrd);
            slot.purefps$setRegionSlot(layerOrd, arena, firstVertex, vertexCount, prevAlloc);
        } else {
            if (prevArena != null) {
                freeChunkLayer(slot, layerOrd);
                if (!ARENAS.containsKey(key)) {
                    ARENAS.put(key, arena);
                }
            }
            int prevHwm = arena.allocator.getHighWaterMark();
            firstVertex = arena.allocator.allocate(neededAligned);
            arena.ensureCapacity(arena.allocator.getHighWaterMark(), prevHwm, hasGL31Copy, hasGL30Vao);
            slot.purefps$setRegionSlot(layerOrd, arena, firstVertex, vertexCount, neededAligned);
        }

        int byteLen = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        if (stagingBuffer.capacity() < byteLen) {
            int newCap = Math.max(stagingBuffer.capacity() << 1, byteLen);
            stagingBuffer = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        }

        float localX = (float) (bx - arena.originX);
        float localY = (float) by;
        float localZ = (float) (bz - arena.originZ);
        transformBlockVertices(worldRenderer.getByteBuffer(), vertexCount, localX, localY, localZ, stagingBuffer);

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
        GL15.glBufferSubData(
                OpenGlHelper.GL_ARRAY_BUFFER,
                (long) firstVertex * RegionLayerArena.VERTEX_STRIDE_BYTES,
                stagingBuffer
        );
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
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

        if (layer == EnumWorldBlockLayer.TRANSLUCENT) {
            renderTranslucentOrdered(layerOrd, renderChunks, count, viewX, viewY, viewZ);
            return;
        }

        ACTIVE_ARENAS.clear();
        for (int i = 0; i < count; i++) {
            RenderChunk rc = renderChunks.get(i);
            RegionChunkSlot slot = (RegionChunkSlot) rc;
            RegionLayerArena arena = (RegionLayerArena) slot.purefps$getArena(layerOrd);
            int vCount = slot.purefps$getVertexCount(layerOrd);
            if (arena != null && arena.vboId >= 0 && vCount > 0) {
                if (arena.batchSize == 0) {
                    ACTIVE_ARENAS.add(arena);
                }
                arena.addDrawCommand(slot.purefps$getFirstVertex(layerOrd), vCount);
                PureFPSConfig.frameChunksBatchedAccumulator++;
            } else {
                drawFallbackSingleChunk(rc, layerOrd, viewX, viewY, viewZ);
            }
        }

        int arenaCount = ACTIVE_ARENAS.size();
        for (int i = 0; i < arenaCount; i++) {
            RegionLayerArena arena = ACTIVE_ARENAS.get(i);
            flushArenaBatch(arena, viewX, viewY, viewZ);
        }
        ACTIVE_ARENAS.clear();
    }

    private static void renderTranslucentOrdered(
            int layerOrd,
            List<RenderChunk> renderChunks,
            int count,
            double viewX,
            double viewY,
            double viewZ
    ) {
        RegionLayerArena currentArena = null;
        for (int i = 0; i < count; i++) {
            RenderChunk rc = renderChunks.get(i);
            RegionChunkSlot slot = (RegionChunkSlot) rc;
            RegionLayerArena arena = (RegionLayerArena) slot.purefps$getArena(layerOrd);
            int vCount = slot.purefps$getVertexCount(layerOrd);
            if (arena != null && arena.vboId >= 0 && vCount > 0) {
                if (currentArena != null && currentArena != arena) {
                    flushArenaBatch(currentArena, viewX, viewY, viewZ);
                }
                currentArena = arena;
                arena.addDrawCommand(slot.purefps$getFirstVertex(layerOrd), vCount);
                PureFPSConfig.frameChunksBatchedAccumulator++;
            } else {
                if (currentArena != null) {
                    flushArenaBatch(currentArena, viewX, viewY, viewZ);
                    currentArena = null;
                }
                drawFallbackSingleChunk(rc, layerOrd, viewX, viewY, viewZ);
            }
        }
        if (currentArena != null) {
            flushArenaBatch(currentArena, viewX, viewY, viewZ);
        }
    }

    private static void flushArenaBatch(RegionLayerArena arena, double viewX, double viewY, double viewZ) {
        int draws = arena.batchSize;
        if (draws <= 0) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(
                (float) ((double) arena.originX - viewX),
                (float) (-viewY),
                (float) ((double) arena.originZ - viewZ)
        );

        boolean useVao = hasGL30Vao && PureFPSConfig.hardwareRegionVao && arena.vaoId > 0;
        if (useVao) {
            GL30.glBindVertexArray(arena.vaoId);
        } else {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, arena.vboId);
            setupBlockArrayPointers();
        }

        if (draws == 1) {
            GL11.glDrawArrays(GL11.GL_QUADS, arena.batchFirst[0], arena.batchCount[0]);
        } else {
            ensureMultiBuffersCapacity(draws);
            multiFirstBuf.clear();
            multiFirstBuf.put(arena.batchFirst, 0, draws);
            multiFirstBuf.flip();

            multiCountBuf.clear();
            multiCountBuf.put(arena.batchCount, 0, draws);
            multiCountBuf.flip();

            GL14.glMultiDrawArrays(GL11.GL_QUADS, multiFirstBuf, multiCountBuf);
        }

        if (useVao) {
            GL30.glBindVertexArray(0);
        }

        GlStateManager.popMatrix();
        arena.batchSize = 0;
        PureFPSConfig.frameMultiDrawsAccumulator++;
    }

    private static void drawFallbackSingleChunk(RenderChunk rc, int layerOrd, double viewX, double viewY, double viewZ) {
        VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
        if (vb == null) {
            return;
        }
        BlockPos pos = rc.getPosition();
        GlStateManager.pushMatrix();
        GlStateManager.translate(
                (float) ((double) pos.getX() - viewX),
                (float) ((double) pos.getY() - viewY),
                (float) ((double) pos.getZ() - viewZ)
        );
        rc.multModelviewMatrix();
        vb.bindBuffer();
        setupBlockArrayPointers();
        vb.drawArrays(GL11.GL_QUADS);
        GlStateManager.popMatrix();
    }

    public static void setupBlockArrayPointers() {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void ensureMultiBuffersCapacity(int draws) {
        if (multiFirstBuf.capacity() < draws) {
            int cap = Math.max(multiFirstBuf.capacity() << 1, draws);
            multiFirstBuf = ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            multiCountBuf = ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        }
    }

    public static void freeChunkLayer(RegionChunkSlot slot, int layerOrd) {
        RegionLayerArena arena = (RegionLayerArena) slot.purefps$getArena(layerOrd);
        if (arena != null) {
            int first = slot.purefps$getFirstVertex(layerOrd);
            int alloc = slot.purefps$getAllocatedVertices(layerOrd);
            slot.purefps$clearRegionSlot(layerOrd);
            arena.allocator.free(first, alloc);
            if (arena.allocator.getLiveAllocations() == 0) {
                arena.destroy();
                ARENAS.remove(arena.regionLayerKey);
            }
        }
    }

    public static void freeChunkAllLayers(RegionChunkSlot slot) {
        for (int i = 0; i < 4; i++) {
            freeChunkLayer(slot, i);
        }
    }

    public static void clearAll() {
        for (RegionLayerArena arena : ARENAS.values()) {
            arena.destroy();
        }
        ARENAS.clear();
        ACTIVE_ARENAS.clear();
    }
}
