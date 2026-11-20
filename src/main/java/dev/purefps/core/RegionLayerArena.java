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
 * Arène VBO GL_STATIC_DRAW + VAO matériel OpenGL 3.0 sur le GPU pour une couche (EnumWorldBlockLayer)
 * d'une région 256x256 blocs :
 * - Tier-1 (OpenGL 4.3 / ARB_multi_draw_indirect) : stocke 4 sommets/quad (-33 % VRAM / bande passante / VS)
 *   et lie l'EBO 16-bit partagé (QuadIndexBuffer) directement dans le VAO GL30 pour glMultiDrawElementsIndirect.
 * - Tier-2 (OpenGL 1.4-4.2) : stocke 6 sommets/quad (GL_TRIANGLES) pour GL14.glMultiDrawArrays.
 * - Suit emptySinceMs pour détruire automatiquement les arènes vides après 5 s (zéro fuite VRAM).
 */
public final class RegionLayerArena {

    public static final int VERTEX_STRIDE_BYTES = 28;
    private static final int INITIAL_SOLID_VERTICES = 262144;     // ~7 Mo (évite 5-6 resizes au chargement)
    private static final int INITIAL_ALPHA_VERTICES = 32768;      // ~896 Ko

    public final long regionLayerKey;
    public final int layerOrdinal;
    public final int originX;
    public final int originZ;
    public final boolean indexedQuads;

    public int vboId = -1;
    public int vaoId = -1;
    private boolean vaoFailed = false;
    public int capacityVertices = 0;
    public long emptySinceMs = 0L;
    public final FreeRangeAllocator allocator = new FreeRangeAllocator();

    // Tampons primitifs réutilisés chaque frame pour le batching MultiDraw / Indirect (zéro allocation)
    public int[] batchFirst = new int[256];
    public int[] batchCount = new int[256];
    public int batchSize = 0;

    public RegionLayerArena(long regionLayerKey, int layerOrdinal, int originX, int originZ) {
        this(regionLayerKey, layerOrdinal, originX, originZ, false);
    }

    public RegionLayerArena(long regionLayerKey, int layerOrdinal, int originX, int originZ, boolean indexedQuads) {
        this.regionLayerKey = regionLayerKey;
        this.layerOrdinal = layerOrdinal;
        this.originX = originX;
        this.originZ = originZ;
        this.indexedQuads = indexedQuads;
    }

    private static final int MAX_CAPACITY_VERTICES = 1 << 30;

    private static int nextSafeCapacity(int currentCap, int requiredVertices) {
        if (requiredVertices <= 0) {
            return currentCap;
        }
        int target = Math.min(requiredVertices, MAX_CAPACITY_VERTICES);
        int cap = Math.max(1, currentCap);
        while (cap < target) {
            if (cap >= (MAX_CAPACITY_VERTICES >> 1)) {
                return MAX_CAPACITY_VERTICES;
            }
            cap <<= 1;
        }
        return cap;
    }

    public void ensureCapacity(int requiredVertices, int previousHighWaterMark, boolean hasGL31, boolean hasGL30Vao) {
        if (requiredVertices <= 0) {
            return;
        }
        emptySinceMs = 0L;
        if (vboId < 0) {
            int initCap = (layerOrdinal <= 1) ? INITIAL_SOLID_VERTICES : INITIAL_ALPHA_VERTICES;
            int cap = nextSafeCapacity(initCap, requiredVertices);
            vboId = OpenGlHelper.glGenBuffers();
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) cap * VERTEX_STRIDE_BYTES, GL15.GL_STATIC_DRAW);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            capacityVertices = cap;
            if (hasGL30Vao && !vaoFailed) {
                rebuildHardwareVao();
            }
            return;
        }

        if (requiredVertices <= capacityVertices || capacityVertices >= MAX_CAPACITY_VERTICES) {
            if (hasGL30Vao && vaoId <= 0 && !vaoFailed) {
                rebuildHardwareVao();
            }
            return;
        }

        int newCap = nextSafeCapacity(capacityVertices, requiredVertices);

        int newVbo = OpenGlHelper.glGenBuffers();
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, newVbo);
        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, (long) newCap * VERTEX_STRIDE_BYTES, GL15.GL_STATIC_DRAW);

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
        if (hasGL30Vao && !vaoFailed) {
            rebuildHardwareVao();
        }
    }

    private void rebuildHardwareVao() {
        try {
            // Prépare l'EBO avant de lier le VAO afin de ne jamais polluer le VAO courant
            int eboId = indexedQuads
                    ? QuadIndexBuffer.ensureIndexBufferReady(QuadIndexBuffer.MAX_USHORT_VERTICES)
                    : -1;
            if (vaoId <= 0) {
                vaoId = GL30.glGenVertexArrays();
            }
            if (vaoId <= 0) {
                vaoFailed = true;
                return;
            }
            GL30.glBindVertexArray(vaoId);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vboId);
            if (indexedQuads && eboId > 0) {
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
            }

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
        } catch (Exception | LinkageError ignored) {
            vaoId = -1;
            vaoFailed = true;
        }
    }

    public void addDrawCommand(int firstVertex, int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        if (batchSize > 0) {
            int prev = batchSize - 1;
            if (batchFirst[prev] + batchCount[prev] == firstVertex) {
                batchCount[prev] += vertexCount;
                return;
            }
        }
        if (batchSize == batchFirst.length) {
            int newLen = batchFirst.length << 1;
            batchFirst = Arrays.copyOf(batchFirst, newLen);
            batchCount = Arrays.copyOf(batchCount, newLen);
        }
        batchFirst[batchSize] = firstVertex;
        batchCount[batchSize] = vertexCount;
        batchSize++;
    }

    public void markFreeCheck(long nowMs) {
        if (allocator.getLiveAllocations() == 0) {
            if (emptySinceMs == 0L) {
                emptySinceMs = nowMs;
            }
        } else {
            emptySinceMs = 0L;
        }
    }

    public void destroy() {
        if (vaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(vaoId);
            } catch (Exception | LinkageError ignored) {
            }
            vaoId = -1;
        }
        vaoFailed = false;
        if (vboId >= 0) {
            OpenGlHelper.glDeleteBuffers(vboId);
            vboId = -1;
        }
        capacityVertices = 0;
        batchSize = 0;
        emptySinceMs = 0L;
        allocator.reset();
    }
}
