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
 * - Pré-cuisson hors thread OpenGL sur ChunkRenderWorker avec partitionnement 6-directions (Wave 4.1) :
 *   - Tier-1 Indirect : 4 sommets/quad régionaux (-33 % bande passante / VRAM / temps worker).
 *   - Tier-2 MultiDraw : 6 sommets/quad triangles régionaux.
 * - Libère systématiquement le tampon worker au pool même si la ListenableFutureTask est annulée avant run().
 */
public final class ChunkUploadTask implements Runnable {

    private final ChunkRenderDispatcher dispatcher;
    private final EnumWorldBlockLayer layer;
    private final WorldRenderer worldRenderer;
    private final RenderChunk renderChunk;
    private final CompiledChunk compiledChunk;

    private ByteBuffer prebakedBuf;
    private final int prebakedVertices;
    private final boolean prebakedIndexed;
    private final int prebakedPosX;
    private final int prebakedPosY;
    private final int prebakedPosZ;
    private final int[] prebakedFaceStarts;
    private final int[] prebakedFaceCounts;

    private ChunkUploadTask(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk,
            ByteBuffer prebakedBuf,
            int prebakedVertices,
            boolean prebakedIndexed,
            int prebakedPosX,
            int prebakedPosY,
            int prebakedPosZ,
            int[] prebakedFaceStarts,
            int[] prebakedFaceCounts
    ) {
        this.dispatcher = dispatcher;
        this.layer = layer;
        this.worldRenderer = worldRenderer;
        this.renderChunk = renderChunk;
        this.compiledChunk = compiledChunk;
        this.prebakedBuf = prebakedBuf;
        this.prebakedVertices = prebakedVertices;
        this.prebakedIndexed = prebakedIndexed;
        this.prebakedPosX = prebakedPosX;
        this.prebakedPosY = prebakedPosY;
        this.prebakedPosZ = prebakedPosZ;
        this.prebakedFaceStarts = prebakedFaceStarts;
        this.prebakedFaceCounts = prebakedFaceCounts;
    }

    private void releasePrebakedIfStillHeld() {
        ByteBuffer buf = this.prebakedBuf;
        if (buf != null) {
            this.prebakedBuf = null;
            ChunkRegionManager.releaseWorkerTriBuffer(buf);
        }
    }

    @Override
    public void run() {
        ByteBuffer prebaked = this.prebakedBuf;
        if (prebaked != null) {
            this.prebakedBuf = null;
            ChunkRegionManager.setPendingPrebakedUpload(
                    prebaked,
                    this.prebakedVertices,
                    this.prebakedIndexed,
                    this.prebakedPosX,
                    this.prebakedPosY,
                    this.prebakedPosZ,
                    this.prebakedFaceStarts,
                    this.prebakedFaceCounts
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

    private static final java.util.concurrent.Executor DIRECT_EXECUTOR = new java.util.concurrent.Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static final class ReleaseOnDone implements Runnable {
        private final ChunkUploadTask task;

        ReleaseOnDone(ChunkUploadTask task) {
            this.task = task;
        }

        @Override
        public void run() {
            this.task.releasePrebakedIfStillHeld();
        }
    }

    public static ListenableFutureTask<Object> create(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk
    ) {
        ByteBuffer prebaked = null;
        int verts = 0;
        boolean indexed = false;
        int posX = 0;
        int posY = 0;
        int posZ = 0;
        int[] faceStarts = null;
        int[] faceCounts = null;

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
                boolean wantIndexed = ChunkRegionManager.isIndirectReadyForWorkers();
                ChunkRegionManager.PrebakedChunk result = ChunkRegionManager.bakeChunkToRegionPartitioned(
                        srcBytes, posX, posY, posZ, wantIndexed
                );
                if (result != null) {
                    prebaked = result.buffer;
                    verts = result.vertices;
                    indexed = result.indexedQuads;
                    faceStarts = result.faceStarts;
                    faceCounts = result.faceCounts;
                }
            }
        }

        ChunkUploadTask uploadTask = new ChunkUploadTask(
                dispatcher,
                layer,
                worldRenderer,
                renderChunk,
                compiledChunk,
                prebaked,
                verts,
                indexed,
                posX,
                posY,
                posZ,
                faceStarts,
                faceCounts
        );
        ListenableFutureTask<Object> future = ListenableFutureTask.create(uploadTask, null);
        if (prebaked != null) {
            future.addListener(new ReleaseOnDone(uploadTask), DIRECT_EXECUTOR);
        }
        return future;
    }
}
