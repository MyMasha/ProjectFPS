package dev.purefps.core;

import net.minecraft.client.renderer.chunk.RenderChunk;

/**
 * Accesseur direct zéro-réflexion pour RenderGlobal$ContainerLocalRenderInformation.renderChunk.
 */
public interface RenderInfoAccess {
    RenderChunk purefps$getRenderChunk();
}
