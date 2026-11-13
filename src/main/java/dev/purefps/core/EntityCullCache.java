package dev.purefps.core;

public interface EntityCullCache {
    int purefps$getCachedOcclusionState(int currentTick);
    boolean purefps$getLastKnownOccluded();
    void purefps$setCulledResult(int currentTick, boolean occluded);
}
