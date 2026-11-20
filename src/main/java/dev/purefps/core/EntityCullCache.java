package dev.purefps.core;

public interface EntityCullCache {
    int purefps$getCachedOcclusionState(int currentTick, long packedCamPos, long packedEntPos);
    boolean purefps$getLastKnownOccluded();
    void purefps$setCulledResult(int currentTick, long packedCamPos, long packedEntPos, boolean occluded);
}
