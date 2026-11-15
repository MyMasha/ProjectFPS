package dev.purefps.core;

import net.minecraft.client.renderer.chunk.RenderChunk;

/**
 * Interface d'accès direct O(1) injectée sur net.minecraft.client.renderer.vertex.VertexBuffer :
 * - Expose le nombre de sommets, l'ID du tampon VBO et le créneau d'arène régionale GPU (GL14.glMultiDrawArrays)
 *   sans réflexion ni changement de VAO coûteux.
 */
public interface FastVertexBuffer {

    int purefps$getVertexCount();

    void purefps$setVertexCount(int vertexCount);

    int purefps$getGlBufferId();

    void purefps$bindOwnerChunk(RenderChunk ownerChunk, int layerOrdinal);

    int purefps$getLayerOrdinal();

    RegionLayerArena purefps$getArena();

    int purefps$getArenaFirstVertex();

    int purefps$getArenaAllocVertices();

    void purefps$setArenaSlot(RegionLayerArena arena, int firstVertex, int allocVertices);

    void purefps$releaseArenaSlot();

    boolean purefps$isTriangleBuffer();
}
