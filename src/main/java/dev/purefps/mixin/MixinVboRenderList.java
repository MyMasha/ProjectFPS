package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;

/**
 * Remplace la boucle VboRenderList.renderChunkLayer (qui exécutait ~7 200 appels JNI OpenGL par frame) :
 * - Mode Region MultiDraw actif : regroupe toute une région 256x256 en un seul GL14.glMultiDrawArrays.
 * - Mode fallback VBO individuel : une seule paire pushMatrix/popMatrix par couche avec translation relative.
 */
@Mixin(VboRenderList.class)
public abstract class MixinVboRenderList extends MixinChunkRenderContainer {

    /**
     * @author PureFPS
     * @reason Batching GPU MultiDraw régional + suppression des 600 pushMatrix/popMatrix/multMatrix par frame
     */
    @Overwrite
    public void renderChunkLayer(EnumWorldBlockLayer layer) {
        if (!this.initialized) {
            return;
        }
        List<RenderChunk> chunks = this.renderChunks;
        int size = chunks.size();
        if (size == 0) {
            return;
        }

        double viewX = this.purefps$getViewX();
        double viewY = this.purefps$getViewY();
        double viewZ = this.purefps$getViewZ();

        if (PureFPSConfig.enabled && PureFPSConfig.regionMultiDraw && ChunkRegionManager.isSupported()) {
            ChunkRegionManager.renderLayer(layer, chunks, viewX, viewY, viewZ);
        } else {
            int layerOrd = layer.ordinal();
            GlStateManager.pushMatrix();
            float prevX = 0.0F;
            float prevY = 0.0F;
            float prevZ = 0.0F;
            for (int i = 0; i < size; i++) {
                RenderChunk rc = chunks.get(i);
                VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
                if (vb == null) {
                    continue;
                }
                BlockPos pos = rc.getPosition();
                float targetX = (float) ((double) pos.getX() - viewX);
                float targetY = (float) ((double) pos.getY() - viewY);
                float targetZ = (float) ((double) pos.getZ() - viewZ);
                GL11.glTranslatef(targetX - prevX, targetY - prevY, targetZ - prevZ);
                prevX = targetX;
                prevY = targetY;
                prevZ = targetZ;

                vb.bindBuffer();
                ChunkRegionManager.setupBlockArrayPointers();
                vb.drawArrays(GL11.GL_QUADS);
            }
            GlStateManager.popMatrix();
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        chunks.clear();
    }
}
