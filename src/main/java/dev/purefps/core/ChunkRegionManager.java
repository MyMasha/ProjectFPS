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
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Moteur GPU Zéro-Stall de rendu de Chunks (PureClient 3.5) :
 * - Remplace l'ancien glBufferSubData sur VBO partagé en vol (qui causait des attentes GPU implicites de 2-8 ms
 *   et des chunks manquants en tournant la caméra) par des VBO par-chunk orphanés + VAO matériel GL30 +
 *   tampon d'indices partagé GL_TRIANGLES (QuadIndexBuffer).
 * - Émet une seule paire pushMatrix/popMatrix par couche avec translation relative GL11.glTranslatef
 *   et anti-couture 1.000001f cuit à l'upload (zéro multModelviewMatrix par chunk).
 * - Pré-calcule la matrice anti-couture 4x4 colonne-majeure pour éliminer les 400+ appels synchrones
 *   GL11.glGetFloat(GL_MODELVIEW_MATRIX) lors du franchissement des bordures de chunks.
 */
public final class ChunkRegionManager {

    public static final float ANTI_SEAM_SCALE = 1.000001F;

    /**
     * Matrice colonne-majeure constante équivalente à :
     * loadIdentity(); translate(-8, -8, -8); scale(1.000001f); translate(8, 8, 8);
     */
    public static final float[] STATIC_ANTI_SEAM_MATRIX = computeStaticAntiSeamMatrix();

    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());

    private static int capabilityState = 0; // 0 = inconnu, 1 = supporté, -1 = non supporté
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
                hasGL30Vao = caps != null && caps.OpenGL30;
                capabilityState = ok ? 1 : -1;
            } catch (Throwable t) {
                capabilityState = -1;
            }
        }
        return capabilityState == 1;
    }

    public static boolean hasHardwareVao() {
        isSupported();
        return hasGL30Vao;
    }

    public static long packRegionLayerKey(int blockX, int blockZ, int layerOrdinal) {
        int rx = blockX >> 8;
        int rz = blockZ >> 8;
        return (((long) rx & 0xFFFFFFFL) << 36) | (((long) rz & 0xFFFFFFFL) << 8) | ((long) layerOrdinal & 0xFFL);
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
        boolean allowVao = PureFPSConfig.hardwareRegionVao && hasHardwareVao();

        if (QuadIndexBuffer.isSupported()) {
            QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES);
        }

        GlStateManager.pushMatrix();
        float prevX = 0.0F;
        float prevY = 0.0F;
        float prevZ = 0.0F;
        boolean boundAnyVao = false;
        boolean boundAnyEboOnDefaultVao = false;
        int drawnSections = 0;

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

            BlockPos pos = rc.getPosition();
            float targetX = (float) ((double) pos.getX() - viewX);
            float targetY = (float) ((double) pos.getY() - viewY);
            float targetZ = (float) ((double) pos.getZ() - viewZ);
            GL11.glTranslatef(targetX - prevX, targetY - prevY, targetZ - prevZ);
            prevX = targetX;
            prevY = targetY;
            prevZ = targetZ;

            boolean usedVao = fvb.purefps$drawChunkTriangles(allowVao);
            if (usedVao) {
                boundAnyVao = true;
            } else if (QuadIndexBuffer.isSupported()) {
                boundAnyEboOnDefaultVao = true;
            }
            drawnSections++;
        }

        if (boundAnyVao) {
            GL30.glBindVertexArray(0);
        }
        if (boundAnyEboOnDefaultVao) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
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
        // Les VAO par-VertexBuffer sont désormais libérés individuellement par MixinVertexBuffer.deleteGlBuffers.
    }
}
