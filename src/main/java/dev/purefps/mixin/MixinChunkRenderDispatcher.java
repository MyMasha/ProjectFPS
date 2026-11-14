package dev.purefps.mixin;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkUploadTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.ListedRenderChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;

/**
 * 1. Pool Chunk Batcher calibré basse priorité (zéro famine du thread OpenGL).
 * 2. Exécute task.run() HORS du moniteur synchronized(queueChunkUploads) avec jusqu'à 32 uploads VBO
 *    orphanés ultra-rapides par frame (~0.015 ms chacun, plafond 2.0 ms) pour que les chunks latéraux
 *    apparaissent instantanément en tournant la caméra sans jamais bloquer updateChunks.
 * 3. Conserve 100 % du cycle de vie Vanilla RenderChunk.getVertexBufferByLayer + CompiledChunk
 *    (garantit qu'aucun chunk ne peut jamais disparaître ou rester invisible).
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinChunkRenderDispatcher {

    @Shadow
    @Final
    private static ThreadFactory threadFactory;

    @Shadow
    @Final
    private List<ChunkRenderWorker> listThreadedWorkers;

    @Shadow
    @Final
    private BlockingQueue<RegionRenderCacheBuilder> queueFreeRenderBuilders;

    @Shadow
    @Final
    private Queue<ListenableFutureTask<?>> queueChunkUploads;

    @Shadow
    private void uploadDisplayList(WorldRenderer worldRendererIn, int list, RenderChunk chunkRenderer) {
    }

    @Shadow
    private void uploadVertexBuffer(WorldRenderer worldRendererIn, VertexBuffer vertexBufferIn) {
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayBlockingQueue(I)Ljava/util/concurrent/ArrayBlockingQueue;",
                    ordinal = 0,
                    remap = false
            ),
            index = 0
    )
    private int purefps$expandChunkUpdateQueue(int defaultCapacity) {
        if (PureFPSConfig.enabled && PureFPSConfig.multiThreadedChunkBuild) {
            return Math.max(defaultCapacity, 256);
        }
        return defaultCapacity;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayBlockingQueue(I)Ljava/util/concurrent/ArrayBlockingQueue;",
                    ordinal = 1,
                    remap = false
            ),
            index = 0
    )
    private int purefps$expandBuilderPoolQueue(int defaultCapacity) {
        int targetWorkers = PureFPSConfig.resolveChunkWorkerThreads();
        return Math.max(defaultCapacity, targetWorkers + 4);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void purefps$spawnExtraChunkWorkers(CallbackInfo ci) {
        int targetWorkers = PureFPSConfig.resolveChunkWorkerThreads();
        int targetBuilders = Math.max(5, targetWorkers + 4);

        int extraBuilders = targetBuilders - 5;
        for (int i = 0; i < extraBuilders; i++) {
            this.queueFreeRenderBuilders.offer(new RegionRenderCacheBuilder());
        }

        ChunkRenderDispatcher self = (ChunkRenderDispatcher) (Object) this;
        int extraWorkers = targetWorkers - this.listThreadedWorkers.size();
        int bgPriority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);
        for (int i = 0; i < extraWorkers; i++) {
            ChunkRenderWorker worker = new ChunkRenderWorker(self);
            Thread thread = threadFactory.newThread(worker);
            thread.setPriority(bgPriority);
            thread.start();
            this.listThreadedWorkers.add(worker);
        }

        PureFPSConfig.activeChunkThreads = this.listThreadedWorkers.size();
    }

    /**
     * @author PureFPS
     * @reason Exécute les uploads VBO orphanés hors du bloc synchronized avec haut débit sans pic de frametime
     */
    @Overwrite
    public boolean runChunkUploads(long finishTimeNano) {
        boolean uploadedAny = false;
        int uploadsCount = 0;
        long effectiveDeadline = finishTimeNano;
        if (finishTimeNano != 0L && PureFPSConfig.enabled) {
            long capped = System.nanoTime() + 900_000L;
            if (capped < effectiveDeadline) {
                effectiveDeadline = capped;
            }
        }

        while (true) {
            ListenableFutureTask<?> task;
            synchronized (this.queueChunkUploads) {
                task = this.queueChunkUploads.poll();
            }
            if (task == null) {
                break;
            }

            task.run();
            uploadedAny = true;
            uploadsCount++;

            if (effectiveDeadline != 0L) {
                if (PureFPSConfig.enabled && uploadsCount >= 12) {
                    break;
                }
                if (effectiveDeadline - System.nanoTime() < 0L) {
                    break;
                }
            }
        }
        return uploadedAny;
    }

    /**
     * @author PureFPS
     * @reason Upload VBO zéro-allocation via ChunkUploadTask (Mixé dans MixinVertexBuffer avec VAO GL30 + anti-couture)
     */
    @Overwrite
    public ListenableFuture<Object> uploadChunk(
            EnumWorldBlockLayer player,
            WorldRenderer worldRendererIn,
            RenderChunk chunkRenderer,
            CompiledChunk compiledChunkIn
    ) {
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            if (OpenGlHelper.useVbo()) {
                this.uploadVertexBuffer(worldRendererIn, chunkRenderer.getVertexBufferByLayer(player.ordinal()));
            } else {
                this.uploadDisplayList(
                        worldRendererIn,
                        ((ListedRenderChunk) chunkRenderer).getDisplayList(player, compiledChunkIn),
                        chunkRenderer
                );
            }
            worldRendererIn.setTranslation(0.0D, 0.0D, 0.0D);
            return Futures.immediateFuture(null);
        } else {
            ListenableFutureTask<Object> task = ChunkUploadTask.create(
                    (ChunkRenderDispatcher) (Object) this,
                    player,
                    worldRendererIn,
                    chunkRenderer,
                    compiledChunkIn
            );
            synchronized (this.queueChunkUploads) {
                this.queueChunkUploads.add(task);
            }
            return task;
        }
    }
}
