package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.MenuUnloadManager;
import dev.purefps.core.OcclusionCuller;
import dev.purefps.core.SmartChunkUpdateScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Optimisation haute-fréquence de RenderGlobal (MashClient 4.3) :
 * 1. setupTerrain sans allocation et sans faux positifs partialTicks.
 * 2. Ordonnanceur à 2 vitesses (SmartChunkUpdateScheduler) dans updateChunks + filtrage notifyLightSet :
 *    - 0 ms de délai pour les nouveaux blocs posés/cassés par les joueurs, les lits (BlockBed) et les générateurs.
 *    - Raréfaction maximale des recompilations sur les îles statiques et suspension dans les menus.
 * 3. Déchargement des nuages et caches dynamiques dans les menus / changements de lobby.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Unique
    private static final LinkedList<Object> PUREFPS_BFS_QUEUE = new LinkedList<Object>();

    @Unique
    private static final ArrayList<Object> PUREFPS_RENDER_INFOS = new ArrayList<Object>(2048);

    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    private WorldClient theWorld;

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    @Shadow
    private List<?> renderInfos;

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
    private boolean displayListEntitiesDirty;

    @Unique
    private float purefps$settledYaw = Float.NaN;

    @Unique
    private float purefps$settledPitch = 0.0F;

    @Inject(method = "loadRenderers", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnReload(CallbackInfo ci) {
        this.purefps$settledYaw = Float.NaN;
        PUREFPS_RENDER_INFOS.clear();
        MenuUnloadManager.onWorldChangeOrUnload();
    }

    @Inject(method = "deleteAllDisplayLists", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnDeleteAll(CallbackInfo ci) {
        this.purefps$settledYaw = Float.NaN;
        PUREFPS_RENDER_INFOS.clear();
        MenuUnloadManager.onWorldChangeOrUnload();
    }

    @Inject(method = "markBlockForUpdate", at = @At("HEAD"))
    private void purefps$markBlockUpdateUrgent(BlockPos pos, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates && pos != null) {
            boolean isBed = false;
            if (this.theWorld != null && this.theWorld.isBlockLoaded(pos)) {
                Block block = this.theWorld.getBlockState(pos).getBlock();
                isBed = (block instanceof BlockBed);
            }
            SmartChunkUpdateScheduler.markDynamicBlockChange(pos.getX(), pos.getY(), pos.getZ(), isBed);
        }
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
     * @reason Priorité 0 ms aux blocs posés/cassés par les joueurs, aux lits et aux générateurs ;
     *         raréfaction des mises à jour sur le décor statique et pause dans les menus bloquants.
     */
    @Overwrite
    public void updateChunks(long finishTimeNano) {
        this.displayListEntitiesDirty |= this.renderDispatcher.runChunkUploads(finishTimeNano);

        if (PureFPSConfig.enabled && PureFPSConfig.smartChunkUpdates) {
            if (this.chunksToUpdate.isEmpty() && SmartChunkUpdateScheduler.getDeferredStaticCount() == 0) {
                return;
            }
            Entity viewer = this.mc != null ? this.mc.getRenderViewEntity() : null;
            boolean menuDimmed = MenuUnloadManager.isWorldDimmedByMenu(this.mc);
            SmartChunkUpdateScheduler.dispatchPrioritizedUpdates(
                    this.chunksToUpdate,
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

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void purefps$skipCloudsWhenMenuOpen(float partialTicks, int pass, CallbackInfo ci) {
        if (MenuUnloadManager.isWorldDimmedByMenu(this.mc)) {
            ci.cancel();
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
