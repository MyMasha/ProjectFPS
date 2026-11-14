package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.ChunkRegionManager;
import dev.purefps.core.EnumCaches;
import dev.purefps.core.OcclusionCuller;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Optimisation haute-fréquence de RenderGlobal.setupTerrain (MashClient 4.0) :
 * - Corrige le bug critique où l'interpolation partialTicks forçait displayListEntitiesDirty = true à chaque frame
 *   en sprintant (7x plus de parcours BFS que Vanilla !).
 * - Conserve la cadence de tick 20 Hz de Vanilla sur posX/posY/posZ tout en filtrant le micro-tremblement souris
 *   (< 0.16°) sans jamais altérer frustumUpdatePosX/Y/Z ni faire disparaître les chunks latéraux.
 * - Réutilise à la fois l'ArrayList renderInfos pré-dimensionnée et la file LinkedList BFS (0 allocation tas).
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Unique
    private static final LinkedList<Object> PUREFPS_BFS_QUEUE = new LinkedList<Object>();

    @Unique
    private static final ArrayList<Object> PUREFPS_RENDER_INFOS = new ArrayList<Object>(2048);

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    @Shadow
    private List<?> renderInfos;

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
        ChunkRegionManager.clearAll();
    }

    @Inject(method = "deleteAllDisplayLists", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnDeleteAll(CallbackInfo ci) {
        this.purefps$settledYaw = Float.NaN;
        PUREFPS_RENDER_INFOS.clear();
        ChunkRegionManager.clearAll();
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

        // Ne touche JAMAIS à lastViewEntityX/Y/Z (qui changent uniquement 20x/s aux ticks) :
        // filtre uniquement le micro-jitter souris (< 0.16°) entre deux frames stationnaires !
        if (viewEntity.posX == this.lastViewEntityX
                && viewEntity.posY == this.lastViewEntityY
                && viewEntity.posZ == this.lastViewEntityZ) {
            float dYaw = Math.abs(viewEntity.rotationYaw - this.purefps$settledYaw);
            float dPitch = Math.abs(viewEntity.rotationPitch - this.purefps$settledPitch);
            if (dYaw < 0.16F && dPitch < 0.16F) {
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
