package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.FastVertexBuffer;
import dev.purefps.core.RegionLayerArena;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Moteur VBO Zéro-Stall + Arènes Régionales MultiDraw (MashClient 4.5.1) :
 * - Les couches SOLID / CUTOUT_MIPPED / CUTOUT (98 %+ des draw calls de terrain) sont logées directement
 *   dans les arènes régionales 256x256 (RegionLayerArena) et rendues en 1 seul appel GL14.glMultiDrawArrays par région !
 * - La couche TRANSLUCENT conserve son tampon orphelin individuel pour préserver le tri exact arrière-vers-avant
 *   entre régions adjacentes sans aucune fragmentation d'arène lors de resortTransparency.
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer implements FastVertexBuffer {

    @Shadow
    private int glBufferId;

    @Shadow
    @Final
    private VertexFormat vertexFormat;

    @Shadow
    private int count;

    @Shadow
    public abstract void bindBuffer();

    @Shadow
    public abstract void unbindBuffer();

    @Unique
    private int purefps$allocatedBytes = 0;

    @Unique
    private boolean purefps$antiSeamReady = false;

    @Unique
    private RenderChunk purefps$ownerChunk = null;

    @Unique
    private int purefps$layerOrdinal = -1;

    @Unique
    private RegionLayerArena purefps$arena = null;

    @Unique
    private int purefps$arenaFirstVertex = -1;

    @Unique
    private int purefps$arenaAllocVertices = 0;

    @Override
    public int purefps$getVertexCount() {
        return this.count;
    }

    @Override
    public void purefps$setVertexCount(int vertexCount) {
        this.count = vertexCount;
    }

    @Override
    public int purefps$getGlBufferId() {
        return this.glBufferId;
    }

    @Override
    public void purefps$bindOwnerChunk(RenderChunk ownerChunk, int layerOrdinal) {
        this.purefps$ownerChunk = ownerChunk;
        this.purefps$layerOrdinal = layerOrdinal;
    }

    @Override
    public int purefps$getLayerOrdinal() {
        return this.purefps$layerOrdinal;
    }

    @Override
    public RegionLayerArena purefps$getArena() {
        return this.purefps$arena;
    }

    @Override
    public int purefps$getArenaFirstVertex() {
        return this.purefps$arenaFirstVertex;
    }

    @Override
    public int purefps$getArenaAllocVertices() {
        return this.purefps$arenaAllocVertices;
    }

    @Override
    public void purefps$setArenaSlot(RegionLayerArena arena, int firstVertex, int allocVertices) {
        this.purefps$arena = arena;
        this.purefps$arenaFirstVertex = firstVertex;
        this.purefps$arenaAllocVertices = allocVertices;
    }

    @Override
    public void purefps$releaseArenaSlot() {
        RegionLayerArena arena = this.purefps$arena;
        int first = this.purefps$arenaFirstVertex;
        int alloc = this.purefps$arenaAllocVertices;
        this.purefps$arena = null;
        this.purefps$arenaFirstVertex = -1;
        this.purefps$arenaAllocVertices = 0;
        if (arena != null && arena.vboId >= 0 && first >= 0 && alloc > 0) {
            arena.allocator.free(first, alloc);
        }
    }

    @Override
    public boolean purefps$isTriangleBuffer() {
        return this.purefps$antiSeamReady;
    }

    @Inject(method = "bufferData", at = @At("HEAD"), cancellable = true)
    private void purefps$fastOrphanedChunkUpload(ByteBuffer byteBuffer, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.vertexFormat != DefaultVertexFormats.BLOCK || this.glBufferId < 0) {
            this.purefps$releaseArenaSlot();
            this.purefps$antiSeamReady = false;
            return;
        }

        if (this.purefps$ownerChunk != null
                && this.purefps$layerOrdinal >= 0
                && this.purefps$layerOrdinal < 3
                && PureFPSConfig.regionMultiDraw
                && ChunkRegionManager.isMultiDrawSupported()) {
            ChunkRegionManager.uploadChunkLayerToArena(this, this.purefps$ownerChunk, byteBuffer);
            this.purefps$antiSeamReady = true;
            ci.cancel();
            return;
        }

        this.purefps$releaseArenaSlot();

        int byteLimit = byteBuffer.limit();
        int quadVertexCount = byteLimit / 28;
        if (quadVertexCount <= 0) {
            this.count = 0;
            this.purefps$allocatedBytes = 0;
            this.purefps$antiSeamReady = false;
            ci.cancel();
            return;
        }

        boolean useIndexedQuads = dev.purefps.core.QuadIndexBuffer.isSupported() && (quadVertexCount & 3) == 0;
        ByteBuffer uploadBuf;
        if (useIndexedQuads) {
            this.count = quadVertexCount;
            uploadBuf = ChunkRegionManager.prepareChunkAntiSeamBuffer(byteBuffer, quadVertexCount);
            this.purefps$antiSeamReady = false;
        } else {
            int triVertexCount = (quadVertexCount >> 2) * 6;
            this.count = triVertexCount;
            if (triVertexCount <= 0) {
                this.purefps$allocatedBytes = 0;
                this.purefps$antiSeamReady = true;
                ci.cancel();
                return;
            }
            uploadBuf = ChunkRegionManager.prepareChunkTrianglesBuffer(byteBuffer, quadVertexCount);
            this.purefps$antiSeamReady = true;
        }

        int uploadBytes = uploadBuf.limit();

        this.bindBuffer();
        if (this.purefps$allocatedBytes > 0) {
            // Orphaning explicite en 1 appel JNI : détache l'ancienne page VRAM si le GPU lit encore la frame précédente
            OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, uploadBuf, GL15.GL_DYNAMIC_DRAW);
        } else {
            OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, uploadBuf, GL15.GL_STATIC_DRAW);
        }
        this.purefps$allocatedBytes = uploadBytes;
        this.unbindBuffer();
        ci.cancel();
    }

    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void purefps$resetAllocationTracking(CallbackInfo ci) {
        this.purefps$releaseArenaSlot();
        this.purefps$allocatedBytes = 0;
        this.purefps$antiSeamReady = false;
    }
}
