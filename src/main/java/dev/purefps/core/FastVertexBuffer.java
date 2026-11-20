package dev.purefps.core;

import net.minecraft.client.renderer.chunk.RenderChunk;

import java.nio.ByteBuffer;

/**
 * Interface d'accès direct O(1) injectée sur net.minecraft.client.renderer.vertex.VertexBuffer :
 * - Expose le nombre de sommets, l'ID VBO paresseux, le créneau d'arène régionale GPU (Tier-1 Indirect 4v/quad
 *   ou Tier-2 MultiDraw 6v/quad), les sous-plages 6-directions (Wave 4.1) et l'EBO uint16 TRANSLUCENT.
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

    boolean purefps$isIndexedArenaChunk();

    void purefps$setIndexedArenaChunk(boolean indexed);

    int purefps$getCustomIndexBufferId();

    void purefps$uploadCustomIndexBuffer(ByteBuffer sortedUshortIndices, int quadCount);

    void purefps$setFaceRanges(int[] starts, int[] counts);

    int[] purefps$getFaceStarts();

    int[] purefps$getFaceCounts();
}
