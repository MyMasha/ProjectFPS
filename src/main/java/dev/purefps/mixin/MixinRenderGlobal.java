package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.FastArrayQueueList;
import dev.purefps.core.FastVisGraphBFS;
import dev.purefps.core.MenuUnloadManager;
import dev.purefps.core.OcclusionCuller;
import dev.purefps.core.OpaqueBlockLut;
import dev.purefps.core.RenderInfoAccess;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Optimisation haute-fréquence de RenderGlobal (MashClient 4.5.15) :
 * 1. File BFS circulaire zéro-allocation (FastArrayQueueList) et liste renderInfos réutilisée.
 * 2. Cache de getVisibleFacings(BlockPos) avec lecture directe ExtendedBlockStorage + OpaqueBlockLut.
 * 3. renderBlockLayer court-circuitant les couches vides (0 enableLightmap / 0 glEnableClientState)
 *    et omettant les états clients VAO-0 lorsque les VAOs matériels des arènes sont actifs.
 * 4. Bake initial 360° de toute la carte une seule fois, puis gel strict des chunks hors rayon joueur ±1.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Unique
    private static final FastArrayQueueList<Object> PUREFPS_BFS_QUEUE = new FastArrayQueueList<Object>(1024);

    @Unique
    private static final ArrayList<Object> PUREFPS_RENDER_INFOS = new ArrayList<Object>(2048);

    @Unique
    private static final EnumSet<EnumFacing> PUREFPS_ALL_FACINGS = EnumSet.allOf(EnumFacing.class);

    @Unique
    private static final BitSet PUREFPS_VIS_BITSET = new BitSet(4096);

    @Unique
    private static final String[] PUREFPS_LAYER_SECTION_NAMES = new String[] {
            "render_SOLID", "render_CUTOUT_MIPPED", "render_CUTOUT", "render_TRANSLUCENT"
    };

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private WorldClient theWorld;

    @Shadow
    private ViewFrustum viewFrustum;

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    @Shadow
    private List<?> renderInfos;

    @Shadow
    private ChunkRenderContainer renderContainer;

    @Shadow
    private ChunkRenderDispatcher renderDispatcher;

    @Shadow
    private double lastViewEntityX;

    @Shadow
    private double lastViewEntityY;

    @Shadow
    private double lastViewEntityZ;

    @Shadow
    private double lastViewEntityPitch;

    @Shadow
    private double lastViewEntityYaw;

    @Shadow
    private double prevRenderSortX;

    @Shadow
    private double prevRenderSortY;

    @Shadow
    private double prevRenderSortZ;

    @Shadow
    private boolean displayListEntitiesDirty;

    @Unique
    private float purefps$settledYaw = Float.NaN;

    @Unique
    private float purefps$settledPitch = 0.0F;

    @Unique
    private int purefps$visCacheX = Integer.MIN_VALUE;

    @Unique
    private int purefps$visCacheY = Integer.MIN_VALUE;

    @Unique
    private int purefps$visCacheZ = Integer.MIN_VALUE;

    @Unique
    private int purefps$visCacheRev = Integer.MIN_VALUE;

    @Unique
    private Set<EnumFacing> purefps$visCacheResult = null;

    @Inject(method = "loadRenderers", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnReload(CallbackInfo ci) {
        this.purefps$settledYaw = Float.NaN;
        this.purefps$visCacheResult = null;
        PUREFPS_RENDER_INFOS.clear();
        MenuUnloadManager.onWorldChangeOrUnload();
    }

    @Inject(method = "deleteAllDisplayLists", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnDeleteAll(CallbackInfo ci) {
        this.purefps$settledYaw = Float.NaN;
        this.purefps$visCacheResult = null;
        PUREFPS_RENDER_INFOS.clear();
        MenuUnloadManager.onWorldChangeOrUnload();
    }

    @Inject(method = "notifyLightSet", at = @At("HEAD"), cancellable = true)
    private void purefps$throttleStaticIslandLightUpdates(BlockPos pos, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && pos != null) {
            if (!SmartChunkUpdateScheduler.shouldAllowLightRebuild(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    System.currentTimeMillis()
            )) {
                ci.cancel();
            }
        }
    }

    /**
     * @author MashClient
     * @reason Cache la visibilité des faces du sous-chunk caméra + scan direct ExtendedBlockStorage/OpaqueBlockLut
     *         (supprime 4 096 Chunk.getBlock + allocation VisGraph/BitSet à chaque rotation de caméra)
     */
    @Overwrite
    private Set<EnumFacing> getVisibleFacings(BlockPos pos) {
        int px = pos.getX();
        int py = pos.getY();
        int pz = pos.getZ();
        int rev = SmartChunkUpdateScheduler.getTerrainMutationRevision();

        if (PureFPSConfig.enabled
                && this.purefps$visCacheResult != null
                && px == this.purefps$visCacheX
                && py == this.purefps$visCacheY
                && pz == this.purefps$visCacheZ
                && rev == this.purefps$visCacheRev) {
            return this.purefps$visCacheResult;
        }

        Set<EnumFacing> result;
        if (this.theWorld == null || py < 0 || py >= 256) {
            result = PUREFPS_ALL_FACINGS;
        } else {
            Chunk chunk = this.theWorld.getChunkFromChunkCoords(px >> 4, pz >> 4);
            ExtendedBlockStorage[] storages = (chunk != null && !chunk.isEmpty()) ? chunk.getBlockStorageArray() : null;
            ExtendedBlockStorage storage = (storages != null) ? storages[py >> 4] : null;
            if (storage == null || storage.isEmpty()) {
                result = PUREFPS_ALL_FACINGS;
            } else {
                char[] data = storage.getData();
                BitSet bits = PUREFPS_VIS_BITSET;
                bits.clear();
                int opaqueCount = 0;
                for (int i = 0; i < 4096; i++) {
                    char raw = data[i];
                    if (raw != 0 && OpaqueBlockLut.isOpaqueCube(raw)) {
                        bits.set(i);
                        opaqueCount++;
                    }
                }
                if (opaqueCount == 0) {
                    result = PUREFPS_ALL_FACINGS;
                } else {
                    int startIdx = (px & 15) | ((pz & 15) << 4) | ((py & 15) << 8);
                    result = FastVisGraphBFS.floodFill(bits, startIdx);
                }
            }
        }

        this.purefps$visCacheX = px;
        this.purefps$visCacheY = py;
        this.purefps$visCacheZ = pz;
        this.purefps$visCacheRev = rev;
        this.purefps$visCacheResult = result;
        return result;
    }

    /**
     * @author MashClient
     * @reason Court-circuite immédiatement les couches vides (0 enableLightmap, 0 StringBuilder, 0 glEnableClientState)
     *         et élimine l'allocation Iterator sur renderInfos
     */
    @Overwrite
    public int renderBlockLayer(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass, Entity entityIn) {
        RenderHelper.disableStandardItemLighting();

        List<?> infos = this.renderInfos;
        int infoCount = infos != null ? infos.size() : 0;

        if (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            double d0 = entityIn.posX - this.prevRenderSortX;
            double d1 = entityIn.posY - this.prevRenderSortY;
            double d2 = entityIn.posZ - this.prevRenderSortZ;
            if (d0 * d0 + d1 * d1 + d2 * d2 > 1.0D) {
                this.prevRenderSortX = entityIn.posX;
                this.prevRenderSortY = entityIn.posY;
                this.prevRenderSortZ = entityIn.posZ;
                int resorted = 0;
                for (int i = 0; i < infoCount; i++) {
                    RenderChunk rc = ((RenderInfoAccess) infos.get(i)).purefps$getRenderChunk();
                    if (rc.getCompiledChunk().isLayerStarted(blockLayerIn) && resorted++ < 15) {
                        this.renderDispatcher.updateTransparencyLater(rc);
                    }
                }
            }
        }

        if (infoCount == 0) {
            return 0;
        }

        int visibleCount = 0;
        boolean reverse = (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT);
        ChunkRenderContainer container = this.renderContainer;
        if (reverse) {
            for (int i = infoCount - 1; i >= 0; i--) {
                RenderChunk rc = ((RenderInfoAccess) infos.get(i)).purefps$getRenderChunk();
                if (!rc.getCompiledChunk().isLayerEmpty(blockLayerIn)) {
                    visibleCount++;
                    container.addRenderChunk(rc, blockLayerIn);
                }
            }
        } else {
            for (int i = 0; i < infoCount; i++) {
                RenderChunk rc = ((RenderInfoAccess) infos.get(i)).purefps$getRenderChunk();
                if (!rc.getCompiledChunk().isLayerEmpty(blockLayerIn)) {
                    visibleCount++;
                    container.addRenderChunk(rc, blockLayerIn);
                }
            }
        }

        if (visibleCount == 0 && PureFPSConfig.enabled) {
            return 0;
        }

        this.mc.mcProfiler.startSection(PUREFPS_LAYER_SECTION_NAMES[blockLayerIn.ordinal()]);
        this.renderBlockLayer(blockLayerIn);
        this.mc.mcProfiler.endSection();
        return visibleCount;
    }

    /**
     * @author MashClient
     * @reason Supprime les 8 appels glEnableClientState/glDisableClientState + Iterator DefaultVertexFormats.BLOCK
     *         lorsque les VAOs matériels des arènes régionales sont actifs
     */
    @Overwrite
    private void renderBlockLayer(EnumWorldBlockLayer blockLayerIn) {
        this.mc.entityRenderer.enableLightmap();
        boolean useVbo = OpenGlHelper.useVbo();
        boolean skipVao0States = useVbo && ChunkRegionManager.canSkipVao0ClientStates(blockLayerIn);

        if (useVbo && !skipVao0States) {
            ChunkRegionManager.enableBlockClientStates();
        }

        this.renderContainer.renderChunkLayer(blockLayerIn);

        if (useVbo && !skipVao0States) {
            ChunkRegionManager.disableBlockClientStates();
        }

        this.mc.entityRenderer.disableLightmap();
    }

    /**
     * @author MashClient
     * @reason Compilation initiale 360° de toute la carte à la connexion, puis gel intégral des chunks
     *         hors rayon joueur ±1 chunk (sauf explosions Fireball/TNT et modifications réelles).
     */
    @Overwrite
    public void updateChunks(long finishTimeNano) {
        this.displayListEntitiesDirty |= this.renderDispatcher.runChunkUploads(finishTimeNano);

        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates) {
            if (this.chunksToUpdate.isEmpty() && !SmartChunkUpdateScheduler.hasPendingInitialBake()) {
                return;
            }
            Entity viewer = this.mc != null ? this.mc.getRenderViewEntity() : null;
            boolean menuDimmed = MenuUnloadManager.isWorldDimmedByMenu(this.mc);
            SmartChunkUpdateScheduler.dispatchPrioritizedUpdates(
                    this.chunksToUpdate,
                    this.viewFrustum,
                    this.theWorld,
                    this.renderDispatcher,
                    finishTimeNano,
                    viewer,
                    menuDimmed
            );
            return;
        }

        if (this.chunksToUpdate.isEmpty()) {
            return;
        }

        Iterator<RenderChunk> iterator = this.chunksToUpdate.iterator();
        while (iterator.hasNext()) {
            RenderChunk renderchunk = iterator.next();
            if (!this.renderDispatcher.updateChunkLater(renderchunk)) {
                break;
            }
            renderchunk.setNeedsUpdate(false);
            iterator.remove();
            long remaining = finishTimeNano - System.nanoTime();
            if (finishTimeNano != 0L && remaining < 0L && renderchunk.getCompiledChunk() != CompiledChunk.DUMMY) {
                break;
            }
        }
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void purefps$captureCameraAndFilterMicroJitter(
            Entity viewEntity,
            double partialTicks,
            ICamera camera,
            int frameCount,
            boolean playerSpectator,
            CallbackInfo ci
    ) {
        OcclusionCuller.setActiveCamera(camera);
        if (viewEntity == null || !PureFPSConfig.enabled || !PureFPSConfig.lazyTerrainSetup) {
            this.purefps$settledYaw = Float.NaN;
            return;
        }

        if (this.displayListEntitiesDirty
                || this.chunksToUpdate == null
                || !this.chunksToUpdate.isEmpty()
                || this.renderInfos == null
                || this.renderInfos.isEmpty()
                || Float.isNaN(this.purefps$settledYaw)) {
            this.purefps$settledYaw = viewEntity.rotationYaw;
            this.purefps$settledPitch = viewEntity.rotationPitch;
            return;
        }

        double mdx = viewEntity.posX - this.lastViewEntityX;
        double mdy = viewEntity.posY - this.lastViewEntityY;
        double mdz = viewEntity.posZ - this.lastViewEntityZ;
        if (mdx * mdx + mdy * mdy + mdz * mdz <= 16.0D) {
            float dYaw = Math.abs(viewEntity.rotationYaw - this.purefps$settledYaw);
            float dPitch = Math.abs(viewEntity.rotationPitch - this.purefps$settledPitch);
            if (dYaw < 0.22F && dPitch < 0.22F) {
                this.lastViewEntityYaw = (double) viewEntity.rotationYaw;
                this.lastViewEntityPitch = (double) viewEntity.rotationPitch;
                return;
            }
        }

        this.purefps$settledYaw = viewEntity.rotationYaw;
        this.purefps$settledPitch = viewEntity.rotationPitch;
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;",
                    remap = false
            )
    )
    private <E> ArrayList<E> purefps$reusedRenderInfosList() {
        if (PureFPSConfig.enabled) {
            PUREFPS_RENDER_INFOS.clear();
            return (ArrayList<E>) PUREFPS_RENDER_INFOS;
        }
        return new ArrayList<E>();
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newLinkedList()Ljava/util/LinkedList;",
                    remap = false
            )
    )
    private <E> LinkedList<E> purefps$reusedBfsQueue() {
        if (PureFPSConfig.enabled) {
            PUREFPS_BFS_QUEUE.clear();
            return (LinkedList<E>) PUREFPS_BFS_QUEUE;
        }
        return new LinkedList<E>();
    }

    @Redirect(
            method = "setupTerrain",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/EnumFacing;values()[Lnet/minecraft/util/EnumFacing;")
    )
    private EnumFacing[] purefps$setupTerrainCachedFacings() {
        return (PureFPSConfig.enabled && PureFPSConfig.zeroAllocEnumCache)
                ? EnumCaches.FACINGS
                : EnumFacing.values();
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void purefps$captureCameraOnEntities(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        OcclusionCuller.setActiveCamera(camera);
    }
}
