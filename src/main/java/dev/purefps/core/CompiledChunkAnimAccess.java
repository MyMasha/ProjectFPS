package dev.purefps.core;

/**
 * Stocke le bitmask 128 bits des textures animées réellement présentes dans un CompiledChunk.
 */
public interface CompiledChunkAnimAccess {
    long purefps$getAnimMask0();
    long purefps$getAnimMask1();
    void purefps$setAnimMasks(long mask0, long mask1);
}
