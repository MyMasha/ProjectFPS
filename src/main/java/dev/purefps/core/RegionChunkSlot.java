package dev.purefps.core;

/**
 * Accès direct O(1) (sans HashMap) aux slots d'arènes VBO régionales stockés sur chaque RenderChunk.
 */
public interface RegionChunkSlot {

    Object purefps$getArena(int layerOrdinal);

    int purefps$getFirstVertex(int layerOrdinal);

    int purefps$getVertexCount(int layerOrdinal);

    int purefps$getAllocatedVertices(int layerOrdinal);

    void purefps$setRegionSlot(
            int layerOrdinal,
            Object arena,
            int firstVertex,
            int vertexCount,
            int allocatedVertices
    );

    void purefps$clearRegionSlot(int layerOrdinal);
}
