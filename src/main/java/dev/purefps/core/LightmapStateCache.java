package dev.purefps.core;

/**
 * Mémorise les dernières coordonnées de lightmap envoyées à OpenGL sur l'unité GL_TEXTURE1
 * durant la frame courante pour éviter des centaines de transitions JNI glMultiTexCoord2f identiques.
 */
public final class LightmapStateCache {

    private static float appliedX = Float.NaN;
    private static float appliedY = Float.NaN;

    private LightmapStateCache() {
    }

    public static boolean checkAndUpdate(float x, float y) {
        if (x == appliedX && y == appliedY) {
            return true; // Déjà appliqué sur l'unité de texture lightmap
        }
        appliedX = x;
        appliedY = y;
        return false;
    }

    public static void invalidate() {
        appliedX = Float.NaN;
        appliedY = Float.NaN;
    }
}
