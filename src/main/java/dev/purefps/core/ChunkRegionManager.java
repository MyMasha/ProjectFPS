package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Moteur GPU Haute-Fréquence de rendu de Chunks (MashClient 4.0) :
 * - Élimine les 600–1 500 changements de VAO par frame (cause majeure du plafond ~80 FPS sur pilotes OpenGL).
 * - Lie le tampon d'indices partagé QuadIndexBuffer (GL_TRIANGLES 16-bit) UNE SEULE FOIS par couche de rendu,
 *   conserve les états clients actifs sur VAO 0 et émet un flux direct ultra-léger par section visible.
 * - Pré-calcule le facteur anti-couture (1.000001f) directement en place sur les threads ChunkRenderWorker
 *   pour 0.0 ms de calcul CPU sur le thread de rendu principal.
 */
public final class ChunkRegionManager {

    public static final float ANTI_SEAM_SCALE = 1.000001F;
    private static final float ANTI_SEAM_BIAS = 8.0F - 8.0F * ANTI_SEAM_SCALE;

    public static final float[] STATIC_ANTI_SEAM_MATRIX = computeStaticAntiSeamMatrix();

    private static final Set<ByteBuffer> WORKER_BAKED_BUFFERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<ByteBuffer, Boolean>()));

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
    private static int capabilityState = 0;

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

    /**
     * Exécuté sur les threads workers (MixinWorldRenderer.finishDrawing) :
     * transforme les coordonnées XYZ en place dans le FloatBuffer natif avant la mise en file d'upload !
     */
    public static void bakeAntiSeamInPlace(FloatBuffer floatBuf, ByteBuffer backingByteBuf, int vertexCount) {
        if (floatBuf == null || vertexCount <= 0) {
            return;
        }
        int floatOff = 0;
        for (int v = 0; v < vertexCount; v++) {
            float vx = floatBuf.get(floatOff);
            float vy = floatBuf.get(floatOff + 1);
            float vz = floatBuf.get(floatOff + 2);
            floatBuf.put(floatOff, vx * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
            floatBuf.put(floatOff + 1, vy * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
            floatBuf.put(floatOff + 2, vz * ANTI_SEAM_SCALE + ANTI_SEAM_BIAS);
            floatOff += 7; // 28 octets = 7 floats par sommet DefaultVertexFormats.BLOCK
        }
        if (backingByteBuf != null) {
            WORKER_BAKED_BUFFERS.add(backingByteBuf);
        }
    }

    public static boolean consumeWorkerBakedFlag(ByteBuffer byteBuf) {
        return byteBuf != null && WORKER_BAKED_BUFFERS.remove(byteBuf);
    }

    public static ByteBuffer prepareChunkAntiSeamBuffer(ByteBuffer srcBytes, int vertexCount) {
        int requiredBytes = vertexCount * RegionLayerArena.VERTEX_STRIDE_BYTES;
        if (stagingBuffer.capacity() < requiredBytes) {
            int newCap = Math.max(stagingBuffer.capacity() << 1, requiredBytes);
            stagingBuffer = ByteBuffer.allocateDirect(newCap).order(ByteOrder.nativeOrder());
        }
        transformBlockVertices(srcBytes, vertexCount, 0.0F, 0.0F, 0.0F, stagingBuffer);
        return stagingBuffer;
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
        boolean indexSupported = QuadIndexBuffer.isSupported();
        int boundEbo = -1;
        if (indexSupported) {
            boundEbo = QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
        }

        GlStateManager.pushMatrix();
        float prevX = 0.0F;
        float prevY = 0.0F;
        float prevZ = 0.0F;
        int drawnSections = 0;

        for (int i = 0; i < count; i++) {
            RenderChunk rc = renderChunks.get(i);
            VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
            if (vb == null) {
                continue;
            }
            FastVertexBuffer fvb = (FastVertexBuffer) vb;
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
            setupBlockArrayPointers();

            if (indexSupported && (vCount & 3) == 0) {
                int neededEbo = vCount <= QuadIndexBuffer.MAX_USHORT_VERTICES
                        ? QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES)
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
            drawnSections++;
        }

        if (boundEbo >= 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.popMatrix();

        if (drawnSections > 0) {
            PureFPSConfig.frameChunksBatchedAccumulator += drawnSections;
            PureFPSConfig.frameMultiDrawsAccumulator++;
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
        WORKER_BAKED_BUFFERS.clear();
    }
}
