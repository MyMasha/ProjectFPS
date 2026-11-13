package dev.purefps.core;

import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Arène VBO + VAO matériel OpenGL 3.0 sur le GPU pour une couche (EnumWorldBlockLayer) d'une région 256x256 blocs.
 * Regroupe jusqu'à 256+ sections de chunks dans un seul tampon contigu pour GL14.glMultiDrawArrays,
 * et pré-enregistre les pointeurs client (Position, Couleur, UV0, Lightmap UV1) dans un VAO GL30
 * pour réduire l'état par lot à un seul appel JNI glBindVertexArray.
 */
public final class RegionLayerArena {

    public static final int VERTEX_STRIDE_BYTES = 28;
    private static final int INITIAL_SOLID_VERTICES = 65536;      // ~1.75 Mo
    private static final int INITIAL_ALPHA_VERTICES = 16384;      // ~448 Ko

    public final long regionLayerKey;
    public final int layerOrdinal;
    public final int originX;
    public final int originZ;

    public int vboId = -1;
    public int vaoId = -1;
    public int capacityVertices = 0;
    public final FreeRangeAllocator allocator = new FreeRangeAllocator();

    // Tampons primitifs réutilisés chaque frame pour le batching MultiDraw (zéro allocation)
    public int[] batchFirst = new int[256];
    public int[] batchCount = new int[256];
    public int batchSize = 0;

    public RegionLayerArena(long regionLayerKey, int layerOrdinal, int originX, int originZ) {
        this.regionLayerKey = regionLayerKey;
        this.layerOrdinal = layerOrdinal;
        this.originX = originX;
        this.originZ = originZ;
    }

    public void ensureCapacity(int requiredVertices, int previousHighWaterMark, boolean hasGL31, boolean hasGL30Vao) {
        if (vboId < 0) {
            int cap = (layerOrdinal <= 1) ? INITIAL_SOLID_VERTICES : INITIAL_ALPHA_VERTICES;
            while (cap < requiredVertices) {
                cap <<= 1;
            }
            vboId = OpenGlHelper.glGenBuffers();
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) cap * VERTEX_STRIDE_BYTES, GL15.GL_DYNAMIC_DRAW);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            capacityVertices = cap;
            if (hasGL30Vao) {
                rebuildHardwareVao();
            }
            return;
        }

        if (requiredVertices <= capacityVertices) {
            return;
        }

        int newCap = capacityVertices;
        while (newCap < requiredVertices) {
            newCap <<= 1;
        }

        int newVbo = OpenGlHelper.glGenBuffers();
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, newVbo);
        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) newCap * VERTEX_STRIDE_BYTES, GL15.GL_DYNAMIC_DRAW);

        int validVertices = Math.min(previousHighWaterMark, capacityVertices);
        long copyBytes = (long) validVertices * VERTEX_STRIDE_BYTES;
        if (copyBytes > 0L) {
            if (hasGL31) {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vboId);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newVbo);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, copyBytes);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            } else {
                ByteBuffer tmp = ByteBuffer.allocateDirect((int) copyBytes).order(ByteOrder.nativeOrder());
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
                GL15.glGetBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, 0L, tmp);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, newVbo);
                GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, 0L, tmp);
            }
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        OpenGlHelper.glDeleteBuffers(vboId);
        vboId = newVbo;
        capacityVertices = newCap;
        if (hasGL30Vao) {
            rebuildHardwareVao();
        }
    }

    private void rebuildHardwareVao() {
        try {
            if (vaoId <= 0) {
                vaoId = GL30.glGenVertexArrays();
            }
            if (vaoId <= 0) {
                return;
            }
            GL30.glBindVertexArray(vaoId);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);

            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 0L);

            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, 12L);

            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 16L);

            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, VERTEX_STRIDE_BYTES, 24L);

            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL30.glBindVertexArray(0);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        } catch (Throwable ignored) {
            vaoId = -1;
        }
    }

    public void addDrawCommand(int firstVertex, int vertexCount) {
        if (batchSize == batchFirst.length) {
            int newLen = batchFirst.length << 1;
            batchFirst = Arrays.copyOf(batchFirst, newLen);
            batchCount = Arrays.copyOf(batchCount, newLen);
        }
        batchFirst[batchSize] = firstVertex;
        batchCount[batchSize] = vertexCount;
        batchSize++;
    }

    public void destroy() {
        if (vaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(vaoId);
            } catch (Throwable ignored) {
            }
            vaoId = -1;
        }
        if (vboId >= 0) {
            OpenGlHelper.glDeleteBuffers(vboId);
            vboId = -1;
        }
        capacityVertices = 0;
        batchSize = 0;
        allocator.reset();
    }
}
