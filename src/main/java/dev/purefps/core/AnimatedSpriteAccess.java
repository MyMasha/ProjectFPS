package dev.purefps.core;

/**
 * Stocke l'indice d'animation séquentiel (0..127) directement sur TextureAtlasSprite pour un test bitmask O(1).
 */
public interface AnimatedSpriteAccess {
    int purefps$getAnimIndex();
    void purefps$setAnimIndex(int index);
}
