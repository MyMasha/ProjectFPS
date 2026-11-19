package dev.purefps.core;

import com.google.common.util.concurrent.ListenableFutureTask;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumWorldBlockLayer;

import java.nio.ByteBuffer;

/**
 * Tâche d'upload différée hors du package mixin (évite toute classe anonyme $1 dans les Mixins 0.7.11) :
 * - Pré-convertit les quads en triangles régionaux directement sur le thread ChunkRenderWorker pour
 *   éliminer le coût CPU de transformation de sommets sur le thread OpenGL principal lors de runChunkUploads().
 */
public final class ChunkUploadTask implements Runnable {

    private final ChunkRenderDispatcher dispatcher;
    private final EnumWorldBlockLayer layer;
    private final WorldRenderer worldRenderer;
    private final RenderChunk renderChunk;
    private final CompiledChunk compiledChunk;

    private ByteBuffer prebakedTriBuf;
    private final int prebakedTriVertices;
    private final int prebakedPosX;
    private final int prebakedPosY;
    private final int prebakedPosZ;

    private ChunkUploadTask(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk,
            ByteBuffer prebakedTriBuf,
            int prebakedTriVertices,
            int prebakedPosX,
            int prebakedPosY,
            int prebakedPosZ
    ) {
        this.dispatcher = dispatcher;
        this.layer = layer;
        this.worldRenderer = worldRenderer;
        this.renderChunk = renderChunk;
        this.compiledChunk = compiledChunk;
        this.prebakedTriBuf = prebakedTriBuf;
        this.prebakedTriVertices = prebakedTriVertices;
        this.prebakedPosX = prebakedPosX;
        this.prebakedPosY = prebakedPosY;
        this.prebakedPosZ = prebakedPosZ;
    }

    @Override
    public void run() {
        ByteBuffer prebaked = this.prebakedTriBuf;
        if (prebaked != null) {
            this.prebakedTriBuf = null;
            ChunkRegionManager.setPendingPrebakedUpload(
                    prebaked,
                    this.prebakedTriVertices,
                    this.prebakedPosX,
                    this.prebakedPosY,
                    this.prebakedPosZ
            );
            try {
                this.dispatcher.uploadChunk(this.layer, this.worldRenderer, this.renderChunk, this.compiledChunk);
            } finally {
                ChunkRegionManager.clearPendingPrebakedUpload();
                ChunkRegionManager.releaseWorkerTriBuffer(prebaked);
            }
            return;
        }
        this.dispatcher.uploadChunk(this.layer, this.worldRenderer, this.renderChunk, this.compiledChunk);
    }

    public static ListenableFutureTask<Object> create(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk
    ) {
        ByteBuffer prebaked = null;
        int triVerts = 0;
        int posX = 0;
        int posY = 0;
        int posZ = 0;

        if (PureFPSConfig.enabled
                && PureFPSConfig.regionMultiDraw
                && layer != null
                && layer.ordinal() < 3
                && worldRenderer != null
                && renderChunk != null
                && renderChunk.getPosition() != null
                && OpenGlHelper.useVbo()
                && ChunkRegionManager.isMultiDrawReadyForWorkers()
                && worldRenderer.getVertexFormat() == DefaultVertexFormats.BLOCK) {
            ByteBuffer srcBytes = worldRenderer.getByteBuffer();
            if (srcBytes != null && srcBytes.limit() > 0) {
                BlockPos pos = renderChunk.getPosition();
                posX = pos.getX();
                posY = pos.getY();
                posZ = pos.getZ();
                prebaked = ChunkRegionManager.bakeChunkToRegionTriangles(srcBytes, posX, posY, posZ);
                if (prebaked != null) {
                    triVerts = prebaked.limit() / RegionLayerArena.VERTEX_STRIDE_BYTES;
                }
            }
        }

        return ListenableFutureTask.create(
                new ChunkUploadTask(
                        dispatcher,
                        layer,
                        worldRenderer,
                        renderChunk,
                        compiledChunk,
                        prebaked,
                        triVerts,
                        posX,
                        posY,
                        posZ
                ),
                null
        );
    }
}
