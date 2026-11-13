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
 * Optimisation majeure de RenderGlobal.setupTerrain (PureClient 3.5) :
 * - Lazy Terrain BFS : évite de reconstruire le graphe BFS de ~1 200 chunks et d'allouer ~2 400 objets
 *   sur chaque micro-mouvement de souris (< 0.65°) ou déplacement sous-bloc (< 0.20 bloc) tant qu'aucun chunk n'est sale.
 * - Réutilise une file LinkedList unique sur le thread client au lieu d'allouer Lists.newLinkedList() à chaque reconstruction.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Unique
    private static final LinkedList<Object> PUREFPS_BFS_QUEUE = new LinkedList<Object>();

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
    private double purefps$anchorX = Double.NaN;
    @Unique
    private double purefps$anchorY = 0.0D;
    @Unique
    private double purefps$anchorZ = 0.0D;
    @Unique
    private float purefps$anchorYaw = 0.0F;
    @Unique
    private float purefps$anchorPitch = 0.0F;

    @Inject(method = "loadRenderers", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnReload(CallbackInfo ci) {
        this.purefps$anchorX = Double.NaN;
        ChunkRegionManager.clearAll();
    }

    @Inject(method = "deleteAllDisplayLists", at = @At("HEAD"))
    private void purefps$clearRegionArenasOnDeleteAll(CallbackInfo ci) {
        this.purefps$anchorX = Double.NaN;
        ChunkRegionManager.clearAll();
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void purefps$captureCameraAndLazyCheck(
            Entity viewEntity,
            double partialTicks,
            ICamera camera,
            int frameCount,
            boolean playerSpectator,
            CallbackInfo ci
    ) {
        OcclusionCuller.setActiveCamera(camera);
        if (viewEntity == null || !PureFPSConfig.enabled || !PureFPSConfig.lazyTerrainSetup) {
            this.purefps$anchorX = Double.NaN;
            return;
        }

        if (this.displayListEntitiesDirty
                || this.chunksToUpdate == null
                || !this.chunksToUpdate.isEmpty()
                || this.renderInfos == null
                || this.renderInfos.isEmpty()
                || Double.isNaN(this.purefps$anchorX)) {
            this.purefps$anchorX = viewEntity.posX;
            this.purefps$anchorY = viewEntity.posY;
            this.purefps$anchorZ = viewEntity.posZ;
            this.purefps$anchorYaw = viewEntity.rotationYaw;
            this.purefps$anchorPitch = viewEntity.rotationPitch;
            return;
        }

        double dx = viewEntity.posX - this.purefps$anchorX;
        double dy = viewEntity.posY - this.purefps$anchorY;
        double dz = viewEntity.posZ - this.purefps$anchorZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        float dYaw = Math.abs(viewEntity.rotationYaw - this.purefps$anchorYaw);
        float dPitch = Math.abs(viewEntity.rotationPitch - this.purefps$anchorPitch);

        if (distSq < 0.04D && dYaw < 0.65F && dPitch < 0.65F) {
            // Synchronise les coordonnées précédentes pour conserver renderInfos intact sur ce micro-mouvement
            this.lastViewEntityX = viewEntity.posX;
            this.lastViewEntityY = viewEntity.posY;
            this.lastViewEntityZ = viewEntity.posZ;
            this.lastViewEntityPitch = (double) viewEntity.rotationPitch;
            this.lastViewEntityYaw = (double) viewEntity.rotationYaw;
        } else {
            this.purefps$anchorX = viewEntity.posX;
            this.purefps$anchorY = viewEntity.posY;
            this.purefps$anchorZ = viewEntity.posZ;
            this.purefps$anchorYaw = viewEntity.rotationYaw;
            this.purefps$anchorPitch = viewEntity.rotationPitch;
            this.displayListEntitiesDirty = true;
        }
    }

    @Redirect(
            method = "setupTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;",
                    remap = false
            )
    )
    private <E> ArrayList<E> purefps$presizedRenderInfosList() {
        return PureFPSConfig.enabled ? new ArrayList<E>(1536) : new ArrayList<E>();
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
