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
 * - Mode accéléré (VAO GL30 + QuadIndexBuffer GL_TRIANGLES) : 1 seule paire pushMatrix/popMatrix par couche,
 *   translation relative GL11.glTranslatef, zéro multModelviewMatrix et triangles matériels natifs.
 */
@Mixin(VboRenderList.class)
public abstract class MixinVboRenderList extends MixinChunkRenderContainer {

    /**
     * @author PureFPS
     * @reason Rendu VAO GL30 + GL_TRIANGLES indexé + suppression des ~600 pushMatrix/popMatrix/multMatrix par frame
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
            boolean indexSupported = dev.purefps.core.QuadIndexBuffer.isSupported();
            int ushortEbo = indexSupported
                    ? dev.purefps.core.QuadIndexBuffer.ensureIndexBufferReady(dev.purefps.core.QuadIndexBuffer.MAX_USHORT_VERTICES)
                    : -1;
            int boundEbo = -1;
            if (indexSupported && ushortEbo >= 0) {
                boundEbo = ushortEbo;
                 org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
            }
            for (int i = 0; i < size; i++) {
                RenderChunk rc = chunks.get(i);
                VertexBuffer vb = rc.getVertexBufferByLayer(layerOrd);
                if (vb == null) {
                    continue;
                }
                int vCount = ((dev.purefps.core.FastVertexBuffer) vb).purefps$getVertexCount();
                if (vCount <= 0) {
                    continue;
                }
                BlockPos pos = rc.getPosition();
                GlStateManager.pushMatrix();
                GlStateManager.translate(
                        (float) ((double) pos.getX() - viewX),
                        (float) ((double) pos.getY() - viewY),
                        (float) ((double) pos.getZ() - viewZ)
                );
                if (!PureFPSConfig.enabled) {
                    rc.multModelviewMatrix();
                }
                vb.bindBuffer();
                ChunkRegionManager.setupBlockArrayPointers();
                if (((dev.purefps.core.FastVertexBuffer) vb).purefps$isTriangleBuffer()) {
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vCount);
                } else if (indexSupported && (vCount & 3) == 0) {
                    int neededEbo = vCount <= dev.purefps.core.QuadIndexBuffer.MAX_USHORT_VERTICES
                            ? ushortEbo
                            : dev.purefps.core.QuadIndexBuffer.ensureIndexBufferReady(vCount);
                    if (neededEbo != boundEbo) {
                        boundEbo = neededEbo;
                        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, boundEbo);
                    }
                    GL11.glDrawElements(
                            GL11.GL_TRIANGLES,
                            dev.purefps.core.QuadIndexBuffer.quadVertexCountToIndexCount(vCount),
                            dev.purefps.core.QuadIndexBuffer.resolveIndexType(vCount),
                            0L
                    );
                } else {
                    vb.drawArrays(GL11.GL_QUADS);
                }
                GlStateManager.popMatrix();
            }
            if (boundEbo >= 0) {
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            }
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        chunks.clear();
    }
}
