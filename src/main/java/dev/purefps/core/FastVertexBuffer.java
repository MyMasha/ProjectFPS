package dev.purefps.core;

/**
 * Interface d'accès direct O(1) injectée sur net.minecraft.client.renderer.vertex.VertexBuffer :
 * - Expose le nombre de sommets et l'ID du tampon VBO sans réflexion ni changement de VAO coûteux.
 */
public interface FastVertexBuffer {

    int purefps$getVertexCount();

    int purefps$getGlBufferId();
}
