package dev.purefps.mixin;

import com.google.common.collect.Sets;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastBiomeColors;
import dev.purefps.core.FastVertexBuffer;
import dev.purefps.core.FastVisGraphBFS;
import dev.purefps.core.IslandCoreCuller;
import dev.purefps.core.RegionCachePool;
import dev.purefps.core.SmartAnimations;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.RegionRenderCache;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Optimisations critiques de RenderChunk (MashClient 4.5.15) :
 * 1. Court-circuit des sous-chunks d'air AVANT createRegionRenderCache() (évite 64 Ko d'allocation).
 * 2. Pool ThreadLocal RegionCachePool pour les tableaux int[8000] / IBlockState[8000] de RegionRenderCache.
 * 3. Réutilisation directe de IslandCoreCuller.opaqueBits pour SetVisibility (zéro double scan VisGraph).
 * 4. Construction du bitmask 128-bits SmartAnimations par CompiledChunk (compatible AO/BALANCED et FPS).
 * 5. Hissement de WorldRenderer[4] et de lastForgeLayer hors de la boucle 3D.
 */
@Mixin(RenderChunk.class)
public abstract class MixinRenderChunk {

    @Shadow
    private World world;

    @Shadow
    @Final
    private RenderGlobal renderGlobal;

    @Shadow
    public static int renderChunksUpdated;

    @Shadow
    private BlockPos position;

    @Shadow
    @Final
    private ReentrantLock lockCompileTask;

    @Shadow
    @Final
    private Set<TileEntity> setTileEntities;

    @Shadow
    @Final
    private FloatBuffer modelviewMatrix;

    @Shadow
    @Final
    private VertexBuffer[] vertexBuffers;

    @Shadow
    private void preRenderBlocks(WorldRenderer worldRendererIn, BlockPos pos) {
    }

    @Shadow
    private void postRenderBlocks(
            EnumWorldBlockLayer layer,
            float x,
            float y,
            float z,
            WorldRenderer worldRendererIn,
            CompiledChunk compiledChunkIn
    ) {
    }

    @Shadow(remap = false)
    protected abstract RegionRenderCache createRegionRenderCache(World worldIn, BlockPos from, BlockPos to, int subtract);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void purefps$bindVertexBuffersToChunk(
            World worldIn,
            RenderGlobal renderGlobalIn,
            BlockPos blockPosIn,
            int indexIn,
            CallbackInfo ci
    ) {
        if (this.vertexBuffers != null) {
            RenderChunk self = (RenderChunk) (Object) this;
            int limit = Math.min(3, this.vertexBuffers.length);
            for (int i = 0; i < limit; i++) {
                VertexBuffer vb = this.vertexBuffers[i];
                if (vb != null) {
                    ((FastVertexBuffer) vb).purefps$bindOwnerChunk(self, i);
                }
            }
        }
    }

    @Inject(method = "setPosition", at = @At("HEAD"))
    private void purefps$releaseArenaSlotsOnMove(BlockPos pos, CallbackInfo ci) {
        if (this.vertexBuffers != null && (this.position == null || !this.position.equals(pos))) {
            int limit = Math.min(3, this.vertexBuffers.length);
            for (int i = 0; i < limit; i++) {
                VertexBuffer vb = this.vertexBuffers[i];
                if (vb != null) {
                    FastVertexBuffer fvb = (FastVertexBuffer) vb;
                    fvb.purefps$releaseArenaSlot();
                    fvb.purefps$setVertexCount(0);
                }
            }
        }
    }

    @Inject(method = "setCompiledChunk", at = @At("RETURN"))
    private void purefps$freeEmptyLayersOnMainThread(CompiledChunk compiledChunkIn, CallbackInfo ci) {
        if (this.vertexBuffers == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) {
            ChunkRegionManager.enqueueDeferredEmptyLayerCheck((RenderChunk) (Object) this);
            return;
        }
        EnumWorldBlockLayer[] layers = EnumCaches.BLOCK_LAYERS;
        int limit = Math.min(3, this.vertexBuffers.length);
        for (int i = 0; i < limit; i++) {
            if (compiledChunkIn == null || compiledChunkIn == CompiledChunk.DUMMY || compiledChunkIn.isLayerEmpty(layers[i])) {
                VertexBuffer vb = this.vertexBuffers[i];
                if (vb != null) {
                    FastVertexBuffer fvb = (FastVertexBuffer) vb;
                    fvb.purefps$releaseArenaSlot();
                    fvb.purefps$setVertexCount(0);
                }
            }
        }
    }

    /**
     * @author MashClient
     * @reason Remplace les 400-528 appels synchrones bloquants glGetFloat(GL_MODELVIEW_MATRIX) par franchissement de chunk
     *         par une copie directe en RAM de la matrice anti-couture pré-calculée (zéro appel JNI OpenGL, zéro freeze).
     */
    @Overwrite
    private void initModelviewMatrix() {
        ChunkRegionManager.fillStaticAntiSeamMatrix(this.modelviewMatrix);
    }

    /**
     * @author MashClient
     * @reason Court-circuit air avant RegionRenderCache + pool RegionCachePool + IslandCoreCuller/VisGraph fusionnés + SmartAnimations bitmask
     */
    @Overwrite
    public void rebuildChunk(float x, float y, float z, ChunkCompileTaskGenerator generator) {
        CompiledChunk compiledchunk = new CompiledChunk();
        BlockPos basePos = this.position;
        if (basePos == null || this.world == null) {
            return;
        }

        int bx0 = basePos.getX();
        int by0 = basePos.getY();
        int bz0 = basePos.getZ();

        // Vérification précoce du sous-chunk vide AVANT createRegionRenderCache() (évite 64 Ko + Chunk[3][3] sur le vide)
        boolean earlyEmpty = false;
        if (PureFPSConfig.enabled) {
            int subY = by0 >> 4;
            if (subY < 0 || subY >= 16) {
                earlyEmpty = true;
            } else {
                Chunk centerChunk = this.world.getChunkFromChunkCoords(bx0 >> 4, bz0 >> 4);
                if (centerChunk == null || centerChunk.isEmpty()) {
                    earlyEmpty = true;
                } else {
                    ExtendedBlockStorage[] storages = centerChunk.getBlockStorageArray();
                    if (storages == null || storages[subY] == null || storages[subY].isEmpty()) {
                        earlyEmpty = true;
                    }
                }
            }
        }

        if (earlyEmpty) {
            generator.getLock().lock();
            try {
                if (generator.getStatus() != ChunkCompileTaskGenerator.Status.COMPILING) {
                    return;
                }
                generator.setCompiledChunk(compiledchunk);
            } finally {
                generator.getLock().unlock();
            }
            compiledchunk.setVisibility(IslandCoreCuller.allOpenVisibility());
            this.purefps$syncGlobalTileEntities(null);
            return;
        }

        if (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors) {
            FastBiomeColors.onChunkRebuildStart(bx0, bz0);
        }

        BlockPos endPos = basePos.add(15, 15, 15);
        RegionRenderCache regionCache = null;
        boolean pooledActive = PureFPSConfig.enabled;
        if (pooledActive) {
            RegionCachePool.beginPooledBuild();
        }
        try {
            generator.getLock().lock();
            try {
                if (generator.getStatus() != ChunkCompileTaskGenerator.Status.COMPILING) {
                    return;
                }
                regionCache = this.createRegionRenderCache(this.world, basePos.add(-1, -1, -1), endPos.add(1, 1, 1), 1);
                MinecraftForgeClient.onRebuildChunk(this.world, this.position, regionCache);
                generator.setCompiledChunk(compiledchunk);
            } finally {
                generator.getLock().unlock();
            }

            HashSet<TileEntity> globalTileEntities = null;
            if (!regionCache.extendedLevelsInChunkCache()) {
                ++renderChunksUpdated;
                boolean useCoreCull = PureFPSConfig.enabled && PureFPSConfig.islandCoreCulling;
                IslandCoreCuller.Context coreCtx = null;
                if (useCoreCull) {
                    coreCtx = IslandCoreCuller.analyzeWorldSubchunk(regionCache, bx0, by0, bz0, x, y, z);
                }

                if (coreCtx != null && coreCtx.emptySubchunk) {
                    compiledchunk.setVisibility(IslandCoreCuller.allOpenVisibility());
                    IslandCoreCuller.clearCurrentThread();
                } else if (coreCtx != null && coreCtx.fullyBuried) {
                    compiledchunk.setVisibility(IslandCoreCuller.allOpaqueVisibility());
                    IslandCoreCuller.clearCurrentThread();
                } else {
                    boolean reuseOpaqueForVis = (coreCtx != null);
                    VisGraph visGraph = reuseOpaqueForVis ? null : new VisGraph();
                    boolean[] layerUsed = new boolean[4];
                    EnumWorldBlockLayer[] layers = EnumCaches.BLOCK_LAYERS;
                    RegionRenderCacheBuilder builder = generator.getRegionRenderCacheBuilder();
                    WorldRenderer wr0 = builder.getWorldRendererByLayerId(0);
                    WorldRenderer wr1 = builder.getWorldRendererByLayerId(1);
                    WorldRenderer wr2 = builder.getWorldRendererByLayerId(2);
                    WorldRenderer wr3 = builder.getWorldRendererByLayerId(3);
                    EnumWorldBlockLayer lastForgeLayer = null;

                    BlockRendererDispatcher blockDispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
                    BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

                    boolean trackAnims = PureFPSConfig.enabled && PureFPSConfig.smartAnimations;
                    if (trackAnims) {
                        SmartAnimations.beginChunkBuild();
                    }

                    try {
                        for (int ly = 0; ly < 16; ly++) {
                            int wy = by0 + ly;
                            int yShift = ly << 8;
                            for (int lz = 0; lz < 16; lz++) {
                                int wz = bz0 + lz;
                                int yzShift = yShift | (lz << 4);
                                for (int lx = 0; lx < 16; lx++) {
                                    IBlockState state;
                                    if (coreCtx != null) {
                                        int idx = yzShift | lx;
                                        if ((coreCtx.nonAirBits[idx >>> 6] & (1L << (idx & 63))) == 0L) {
                                            continue;
                                        }
                                        state = coreCtx.cachedStates[idx];
                                        mpos.set(bx0 + lx, wy, wz);
                                        if (state == null) {
                                            state = regionCache.getBlockState(mpos);
                                        }
                                    } else {
                                        mpos.set(bx0 + lx, wy, wz);
                                        state = regionCache.getBlockState(mpos);
                                    }
                                    Block block = state.getBlock();

                                    if (visGraph != null && block.isOpaqueCube()) {
                                        visGraph.func_178606_a(mpos);
                                    }

                                    if (block.hasTileEntity(state)) {
                                        TileEntity te = regionCache.getTileEntity(new BlockPos(mpos));
                                        TileEntitySpecialRenderer<TileEntity> tesr =
                                                TileEntityRendererDispatcher.instance.getSpecialRenderer(te);
                                        if (te != null && tesr != null) {
                                            compiledchunk.addTileEntity(te);
                                            if (tesr.forceTileEntityRender()) {
                                                if (globalTileEntities == null) {
                                                    globalTileEntities = Sets.newHashSet();
                                                }
                                                globalTileEntities.add(te);
                                            }
                                        }
                                    } else if (coreCtx != null && !coreCtx.isBlockVisibleFromExterior(lx, ly, lz)) {
                                        continue;
                                    }

                                    int renderType = block.getRenderType();
                                    if (renderType != -1) {
                                        for (int i = 0; i < 4; i++) {
                                            EnumWorldBlockLayer layer = layers[i];
                                            if (!block.canRenderInLayer(layer)) {
                                                continue;
                                            }
                                            if (layer != lastForgeLayer) {
                                                ForgeHooksClient.setRenderLayer(layer);
                                                lastForgeLayer = layer;
                                            }
                                            WorldRenderer wr = (i == 0) ? wr0 : (i == 1 ? wr1 : (i == 2 ? wr2 : wr3));
                                            if (!compiledchunk.isLayerStarted(layer)) {
                                                compiledchunk.setLayerStarted(layer);
                                                this.preRenderBlocks(wr, basePos);
                                            }
                                            layerUsed[i] |= blockDispatcher.renderBlock(state, mpos, regionCache, wr);
                                        }
                                    }
                                }
                            }
                        }

                        for (int i = 0; i < 4; i++) {
                            EnumWorldBlockLayer layer = layers[i];
                            if (layerUsed[i]) {
                                ((MixinCompiledChunkAccessor) compiledchunk).purefps$setLayerUsed(layer);
                            }
                            if (compiledchunk.isLayerStarted(layer)) {
                                WorldRenderer wr = (i == 0) ? wr0 : (i == 1 ? wr1 : (i == 2 ? wr2 : wr3));
                                this.postRenderBlocks(layer, x, y, z, wr, compiledchunk);
                            }
                        }
                        if (lastForgeLayer != null) {
                            ForgeHooksClient.setRenderLayer(null);
                        }
                        if (trackAnims) {
                            SmartAnimations.finishChunkBuild(compiledchunk);
                            trackAnims = false;
                        }
                        if (reuseOpaqueForVis) {
                            compiledchunk.setVisibility(FastVisGraphBFS.computeVisibilityFromOpaqueBits(coreCtx.opaqueBits, coreCtx.opaqueCount));
                        } else {
                            compiledchunk.setVisibility(visGraph.computeVisibility());
                        }
                    } finally {
                        if (trackAnims) {
                            SmartAnimations.abortChunkBuild();
                        }
                        IslandCoreCuller.clearCurrentThread();
                    }
                }
            } else {
                compiledchunk.setVisibility(IslandCoreCuller.allOpenVisibility());
            }

            this.purefps$syncGlobalTileEntities(globalTileEntities);
        } finally {
            if (pooledActive) {
                RegionCachePool.endPooledBuild();
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void purefps$syncGlobalTileEntities(HashSet<TileEntity> globalTileEntities) {
        this.lockCompileTask.lock();
        try {
            if ((globalTileEntities != null && !globalTileEntities.isEmpty()) || !this.setTileEntities.isEmpty()) {
                Set<TileEntity> added = (globalTileEntities != null)
                        ? Sets.newHashSet(globalTileEntities)
                        : Sets.<TileEntity>newHashSet();
                Set<TileEntity> removed = Sets.newHashSet(this.setTileEntities);
                added.removeAll(this.setTileEntities);
                removed.removeAll(globalTileEntities != null ? globalTileEntities : java.util.Collections.<TileEntity>emptySet());
                this.setTileEntities.clear();
                if (globalTileEntities != null) {
                    this.setTileEntities.addAll(globalTileEntities);
                }
                this.renderGlobal.updateTileEntities(removed, added);
            }
        } finally {
            this.lockCompileTask.unlock();
        }
    }

    @Redirect(
            method = {"resortTransparency", "deleteGlResources"},
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
