package dev.purefps.mixin;

import com.google.common.collect.Sets;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastBiomeColors;
import dev.purefps.core.FastVertexBuffer;
import dev.purefps.core.IslandCoreCuller;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.RegionRenderCache;
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
 * Optimisations critiques de RenderChunk (MashClient 4.5.1) :
 * 1. Élimine le stall synchrone CPU<->GPU GlStateManager.getFloat(GL_MODELVIEW_MATRIX) dans initModelviewMatrix().
 * 2. Lie les VertexBuffers SOLID/CUTOUT_MIPPED/CUTOUT aux arènes régionales GPU (GL14.glMultiDrawArrays) et libère
 *    instantanément leurs créneaux VRAM lors des recyclages ViewFrustum.updateChunkPositions (setPosition).
 * 3. Décharge à 100 % les coeurs d'îles et cavités internes scellées (BedWars / SkyWars) via IslandCoreCuller.
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
     * @reason Déchargement complet des coeurs d'îles BedWars/SkyWars (IslandCoreCuller) + boucle 3D zéro-allocation
     */
    @Overwrite
    public void rebuildChunk(float x, float y, float z, ChunkCompileTaskGenerator generator) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastBiomeColors) {
            FastBiomeColors.onChunkRebuildStart();
        }

        CompiledChunk compiledchunk = new CompiledChunk();
        BlockPos basePos = this.position;
        if (basePos == null || this.world == null) {
            return;
        }
        BlockPos endPos = basePos.add(15, 15, 15);
        generator.getLock().lock();
        RegionRenderCache regionCache;
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
                coreCtx = IslandCoreCuller.analyzeWorldSubchunk(
                        regionCache, basePos.getX(), basePos.getY(), basePos.getZ(), x, y, z
                );
            }

            if (coreCtx != null && coreCtx.emptySubchunk) {
                compiledchunk.setVisibility(IslandCoreCuller.allOpenVisibility());
                IslandCoreCuller.clearCurrentThread();
            } else if (coreCtx != null && coreCtx.fullyBuried) {
                compiledchunk.setVisibility(IslandCoreCuller.allOpaqueVisibility());
                IslandCoreCuller.clearCurrentThread();
            } else {
                VisGraph visGraph = new VisGraph();
                boolean[] layerUsed = new boolean[4];
                EnumWorldBlockLayer[] layers = EnumCaches.BLOCK_LAYERS;
                BlockRendererDispatcher blockDispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
                BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
                int bx0 = basePos.getX();
                int by0 = basePos.getY();
                int bz0 = basePos.getZ();

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

                                if (block.isOpaqueCube()) {
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
                                        ForgeHooksClient.setRenderLayer(layer);
                                        int layerOrd = layer.ordinal();
                                        WorldRenderer wr = generator.getRegionRenderCacheBuilder().getWorldRendererByLayerId(layerOrd);
                                        if (!compiledchunk.isLayerStarted(layer)) {
                                            compiledchunk.setLayerStarted(layer);
                                            this.preRenderBlocks(wr, basePos);
                                        }
                                        layerUsed[layerOrd] |= blockDispatcher.renderBlock(state, mpos, regionCache, wr);
                                    }
                                }
                            }
                        }
                    }

                    for (int i = 0; i < 4; i++) {
                        EnumWorldBlockLayer layer = layers[i];
                        if (layerUsed[layer.ordinal()]) {
                            ((MixinCompiledChunkAccessor) compiledchunk).purefps$setLayerUsed(layer);
                        }
                        if (compiledchunk.isLayerStarted(layer)) {
                            this.postRenderBlocks(
                                    layer, x, y, z,
                                    generator.getRegionRenderCacheBuilder().getWorldRendererByLayer(layer),
                                    compiledchunk
                            );
                        }
                    }
                    ForgeHooksClient.setRenderLayer(null);
                    compiledchunk.setVisibility(visGraph.computeVisibility());
                } finally {
                    IslandCoreCuller.clearCurrentThread();
                }
            }
        } else {
            VisGraph emptyGraph = new VisGraph();
            compiledchunk.setVisibility(emptyGraph.computeVisibility());
        }

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
