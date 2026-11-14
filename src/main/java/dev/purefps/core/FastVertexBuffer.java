package dev.purefps.core;

/**
 * Interface d'accès direct O(1) injectée sur net.minecraft.client.renderer.vertex.VertexBuffer :
 * - Expose le nombre de sommets sans réflexion.
 * - Gère un VAO matériel OpenGL 3.0 par VertexBuffer de chunk couplé à QuadIndexBuffer (GL_TRIANGLES).
 */
public interface FastVertexBuffer {

    int purefps$getVertexCount();

    boolean purefps$drawChunkTriangles(boolean allowHardwareVao);
}
