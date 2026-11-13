package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastBiomeColors;
import dev.purefps.core.RegionChunkSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. Porte les références directes O(1) aux slots d'arènes VBO régionales (RegionChunkSlot) et libère la VRAM
 *    dès qu'un RenderChunk change de coordonnées, vide une couche ou détruit ses ressources GL.
 * 2. Élimine les allocations répétées de EnumWorldBlockLayer.values() et EnumFacing.values().
 * 3. Réinitialise le cache de teinte biome ThreadLocal en début de compilation de chunk.
 */
@Mixin(RenderChunk.class)
public class MixinRenderChunk implements RegionChunkSlot {

    @Unique
    private Object[] purefps$arenas = new Object[4];

    @Unique
    private int[] purefps$firstVertices = new int[4];

    @Unique
    private int[] purefps$vertexCounts = new int[4];

    @Unique
    private int[] purefps$allocatedVertices = new int[4];

    @Override
    public Object purefps$getArena(int layerOrdinal) {
        return this.purefps$arenas != null ? this.purefps$arenas[layerOrdinal] : null;
    }

    @Override
    public int purefps$getFirstVertex(int layerOrdinal) {
        return this.purefps$firstVertices != null ? this.purefps$firstVertices[layerOrdinal] : 0;
    }

    @Override
    public int purefps$getVertexCount(int layerOrdinal) {
        return this.purefps$vertexCounts != null ? this.purefps$vertexCounts[layerOrdinal] : 0;
    }

    @Override
    public int purefps$getAllocatedVertices(int layerOrdinal) {
        return this.purefps$allocatedVertices != null ? this.purefps$allocatedVertices[layerOrdinal] : 0;
    }

    @Override
    public void purefps$setRegionSlot(
            int layerOrdinal,
            Object arena,
            int firstVertex,
            int vertexCount,
            int allocatedVertices
    ) {
        if (this.purefps$arenas == null) {
            this.purefps$arenas = new Object[4];
            this.purefps$firstVertices = new int[4];
            this.purefps$vertexCounts = new int[4];
            this.purefps$allocatedVertices = new int[4];
        }
        this.purefps$arenas[layerOrdinal] = arena;
        this.purefps$firstVertices[layerOrdinal] = firstVertex;
        this.purefps$vertexCounts[layerOrdinal] = vertexCount;
        this.purefps$allocatedVertices[layerOrdinal] = allocatedVertices;
    }

    @Override
    public void purefps$clearRegionSlot(int layerOrdinal) {
        if (this.purefps$arenas != null) {
            this.purefps$arenas[layerOrdinal] = null;
            this.purefps$firstVertices[layerOrdinal] = 0;
            this.purefps$vertexCounts[layerOrdinal] = 0;
            this.purefps$allocatedVertices[layerOrdinal] = 0;
        }
    }

    @Inject(method = "setPosition", at = @At("HEAD"))
    private void purefps$freeRegionSlotsOnReposition(BlockPos pos, CallbackInfo ci) {
        if (this.purefps$arenas != null && Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            ChunkRegionManager.freeChunkAllLayers(this);
        }
    }

    @Inject(method = "deleteGlResources", at = @At("HEAD"))
    private void purefps$freeRegionSlotsOnDelete(CallbackInfo ci) {
        if (this.purefps$arenas != null && Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            ChunkRegionManager.freeChunkAllLayers(this);
        }
    }

    @Inject(method = "setCompiledChunk", at = @At("HEAD"))
    private void purefps$freeEmptyLayerSlots(CompiledChunk compiledChunkIn, CallbackInfo ci) {
        if (this.purefps$arenas == null || !Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            return;
        }
        if (compiledChunkIn == null || compiledChunkIn == CompiledChunk.DUMMY) {
            ChunkRegionManager.freeChunkAllLayers(this);
            return;
        }
        EnumWorldBlockLayer[] layers = EnumCaches.BLOCK_LAYERS;
        for (int i = 0; i < 4; i++) {
            if (this.purefps$arenas[i] != null && compiledChunkIn.isLayerEmpty(layers[i])) {
                ChunkRegionManager.freeChunkLayer(this, i);
            }
        }
    }

    @Inject(method = "rebuildChunk", at = @At("HEAD"))
    private void purefps$onRebuildChunkStart(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors) {
            FastBiomeColors.onChunkRebuildStart();
        }
    }

    @Redirect(
            method = {"rebuildChunk", "resortTransparency", "deleteGlResources"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumWorldBlockLayer;values()[Lnet/minecraft/util/EnumWorldBlockLayer;")
    )
    private EnumWorldBlockLayer[] purefps$cachedBlockLayers() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.BLOCK_LAYERS
                : EnumWorldBlockLayer.values();
    }

    @Redirect(
            method = "setPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$cachedFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }
}
