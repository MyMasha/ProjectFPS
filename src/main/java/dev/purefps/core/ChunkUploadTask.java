package dev.purefps.core;

import com.google.common.util.concurrent.ListenableFutureTask;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumWorldBlockLayer;

/**
 * Tâche d'upload différée hors du package mixin (évite toute classe anonyme $1 dans les Mixins 0.7.11).
 */
public final class ChunkUploadTask implements Runnable {

    private final ChunkRenderDispatcher dispatcher;
    private final EnumWorldBlockLayer layer;
    private final WorldRenderer worldRenderer;
    private final RenderChunk renderChunk;
    private final CompiledChunk compiledChunk;

    private ChunkUploadTask(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk
    ) {
        this.dispatcher = dispatcher;
        this.layer = layer;
        this.worldRenderer = worldRenderer;
        this.renderChunk = renderChunk;
        this.compiledChunk = compiledChunk;
    }

    @Override
    public void run() {
        this.dispatcher.uploadChunk(this.layer, this.worldRenderer, this.renderChunk, this.compiledChunk);
    }

    public static ListenableFutureTask<Object> create(
            ChunkRenderDispatcher dispatcher,
            EnumWorldBlockLayer layer,
            WorldRenderer worldRenderer,
            RenderChunk renderChunk,
            CompiledChunk compiledChunk
    ) {
        return ListenableFutureTask.create(
                new ChunkUploadTask(dispatcher, layer, worldRenderer, renderChunk, compiledChunk),
                null
        );
    }
}
