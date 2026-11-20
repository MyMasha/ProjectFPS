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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Moteur VBO Zéro-Stall + Arènes Régionales MultiDraw Indirect + Allocation paresseuse (Wave 2.2) :
 * - Allocation paresseuse de glBufferId pour DefaultVertexFormats.BLOCK (évite ~30 000 handles VBO morts).
 * - Les couches SOLID / CUTOUT_MIPPED / CUTOUT sont logées dans RegionLayerArena (Tier-1 4v/quad Indirect ou Tier-2 6v/quad).
 * - Libération des créneaux d'arène différée de 3 frames via ChunkRegionManager.scheduleArenaSlotFree().
 * - Supporte un EBO uint16 dédié par chunk TRANSLUCENT (tri alpha index-only 9,3x plus léger).
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
    private boolean purefps$indexedArenaChunk = false;

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

    @Unique
    private int purefps$customEboId = -1;

    @Unique
    private int[] purefps$faceStarts = null;

    @Unique
    private int[] purefps$faceCounts = null;

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;glGenBuffers()I")
    )
    private int purefps$lazyBlockVboId() {
        if (PureFPSConfig.enabled && PureFPSConfig.regionMultiDraw && this.vertexFormat == DefaultVertexFormats.BLOCK) {
            return -1;
        }
        return OpenGlHelper.glGenBuffers();
    }

    @Inject(method = "bindBuffer", at = @At("HEAD"))
    private void purefps$ensureGlBufferBeforeBind(CallbackInfo ci) {
        if (this.glBufferId < 0) {
            this.glBufferId = OpenGlHelper.glGenBuffers();
        }
    }

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
        this.purefps$faceStarts = null;
        this.purefps$faceCounts = null;
        if (arena != null && arena.vboId >= 0 && first >= 0 && alloc > 0) {
            ChunkRegionManager.scheduleArenaSlotFree(arena, first, alloc);
        }
    }

    @Override
    public boolean purefps$isTriangleBuffer() {
        return this.purefps$antiSeamReady;
    }

    @Override
    public boolean purefps$isIndexedArenaChunk() {
        return this.purefps$indexedArenaChunk;
    }

    @Override
    public void purefps$setIndexedArenaChunk(boolean indexed) {
        this.purefps$indexedArenaChunk = indexed;
    }

    @Override
    public int purefps$getCustomIndexBufferId() {
        return this.purefps$customEboId;
    }

    @Override
    public void purefps$uploadCustomIndexBuffer(ByteBuffer sortedUshortIndices, int quadCount) {
        if (sortedUshortIndices == null || quadCount <= 0) {
            return;
        }
        if (this.purefps$customEboId < 0) {
            this.purefps$customEboId = OpenGlHelper.glGenBuffers();
        }
        if (this.purefps$customEboId > 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.purefps$customEboId);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, sortedUshortIndices, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    @Override
    public void purefps$setFaceRanges(int[] starts, int[] counts) {
        this.purefps$faceStarts = starts;
        this.purefps$faceCounts = counts;
    }

    @Override
    public int[] purefps$getFaceStarts() {
        return this.purefps$faceStarts;
    }

    @Override
    public int[] purefps$getFaceCounts() {
        return this.purefps$faceCounts;
    }

    @Inject(method = "bufferData", at = @At("HEAD"), cancellable = true)
    private void purefps$fastOrphanedChunkUpload(ByteBuffer byteBuffer, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.vertexFormat != DefaultVertexFormats.BLOCK) {
            this.purefps$releaseArenaSlot();
            this.purefps$antiSeamReady = false;
            if (this.glBufferId < 0) {
                this.glBufferId = OpenGlHelper.glGenBuffers();
            }
            return;
        }

        if (this.purefps$ownerChunk != null
                && this.purefps$layerOrdinal >= 0
                && this.purefps$layerOrdinal < 3
                && PureFPSConfig.regionMultiDraw
                && ChunkRegionManager.isMultiDrawSupported()) {
            ChunkRegionManager.uploadChunkLayerToArena(this, this.purefps$ownerChunk, byteBuffer);
            this.purefps$antiSeamReady = !this.purefps$indexedArenaChunk;
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

        if (this.glBufferId < 0) {
            this.glBufferId = OpenGlHelper.glGenBuffers();
            if (this.glBufferId < 0) {
                return;
            }
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
        if (this.purefps$customEboId >= 0) {
            OpenGlHelper.glDeleteBuffers(this.purefps$customEboId);
            this.purefps$customEboId = -1;
        }
        this.purefps$allocatedBytes = 0;
        this.purefps$antiSeamReady = false;
        this.purefps$indexedArenaChunk = false;
    }
}
