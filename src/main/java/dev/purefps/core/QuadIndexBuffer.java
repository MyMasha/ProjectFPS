package dev.purefps.core;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Tampon d'indices GPU statique partagé (GL_ELEMENT_ARRAY_BUFFER) convertissant les GL_QUADS
 * en GL_TRIANGLES matériels natifs ({0,1,2, 2,3,0, ...}) :
 * - Élimine l'émulation logicielle des quads dans le pilote OpenGL (nvoglv64 / amdvlk / igxelpicd).
 * - Utilise GL_UNSIGNED_SHORT (2 octets/indice, bande passante divisée par 2) jusqu'à 65 536 sommets,
 *   et bascule automatiquement sur GL_UNSIGNED_INT au-delà.
 */
public final class QuadIndexBuffer {

    public static final int MAX_USHORT_VERTICES = 65536;
    public static final int MAX_USHORT_QUADS = MAX_USHORT_VERTICES >> 2;

    private static int gl15State = 0; // 0 = inconnu, 1 = supporté, -1 = non supporté
    private static int ushortEboId = -1;
    private static int uintEboId = -1;
    private static int uintQuadCapacity = 0;

    private QuadIndexBuffer() {
    }

    public static boolean isSupported() {
        if (gl15State == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                gl15State = (caps != null && caps.OpenGL15) ? 1 : -1;
            } catch (Throwable t) {
                gl15State = -1;
            }
        }
        return gl15State == 1;
    }

    public static int quadVertexCountToIndexCount(int vertexCount) {
        return (vertexCount >> 2) * 6;
    }

    public static void fillUshortIndices(ShortBuffer dst, int quadCount) {
        dst.clear();
        for (int q = 0; q < quadCount; q++) {
            int base = q << 2;
            dst.put((short) base);
            dst.put((short) (base + 1));
            dst.put((short) (base + 2));
            dst.put((short) (base + 2));
            dst.put((short) (base + 3));
            dst.put((short) base);
        }
        dst.flip();
    }

    public static void fillUintIndices(IntBuffer dst, int quadCount) {
        dst.clear();
        for (int q = 0; q < quadCount; q++) {
            int base = q << 2;
            dst.put(base);
            dst.put(base + 1);
            dst.put(base + 2);
            dst.put(base + 2);
            dst.put(base + 3);
            dst.put(base);
        }
        dst.flip();
    }

    /**
     * Prépare l'EBO sur le contexte GL (à appeler lorsque VAO == 0 afin de ne pas polluer un VAO actif)
     * et retourne l'identifiant du buffer GL_ELEMENT_ARRAY_BUFFER adapté à vertexCount.
     */
    public static int ensureIndexBufferReady(int vertexCount) {
        if (vertexCount <= MAX_USHORT_VERTICES) {
            if (ushortEboId < 0) {
                ushortEboId = GL15.glGenBuffers();
                ByteBuffer raw = ByteBuffer.allocateDirect(MAX_USHORT_QUADS * 6 * 2).order(ByteOrder.nativeOrder());
                ShortBuffer sb = raw.asShortBuffer();
                fillUshortIndices(sb, MAX_USHORT_QUADS);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ushortEboId);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, sb, GL15.GL_STATIC_DRAW);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            }
            return ushortEboId;
        }

        int neededQuads = (vertexCount + 3) >> 2;
        if (uintEboId < 0) {
            uintEboId = GL15.glGenBuffers();
        }
        if (uintQuadCapacity < neededQuads) {
            int newCap = Math.max(32768, Integer.highestOneBit(neededQuads - 1) << 1);
            if (newCap < neededQuads) {
                newCap = neededQuads;
            }
            ByteBuffer raw = ByteBuffer.allocateDirect(newCap * 6 * 4).order(ByteOrder.nativeOrder());
            IntBuffer ib = raw.asIntBuffer();
            fillUintIndices(ib, newCap);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, uintEboId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            uintQuadCapacity = newCap;
        }
        return uintEboId;
    }

    public static int resolveIndexType(int vertexCount) {
        return vertexCount <= MAX_USHORT_VERTICES ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT;
    }
}
