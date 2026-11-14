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
 * Optimisation de RenderGlobal.setupTerrain sans disparition de chunks en bord d'écran (PureClient 3.5) :
 * - Calcule désormais l'ancre sur la position INTERPOLÉE exacte de la caméra (lastTickPos + (pos - lastTickPos) * partialTicks)
 *   avec des seuils sous-pixel stricts (0.04 bloc = distSq < 0.0016 et rotation < 0.12°) afin que tout mouvement
 *   de caméra rafraîchisse immédiatement le frustum sans jamais laisser de chunks non affichés sur les côtés !
 * - Réutilise une file LinkedList unique sur le thread client au lieu d'allouer Lists.newLinkedList() à chaque BFS.
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

        double camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        double camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
        double camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        float camYaw = viewEntity.prevRotationYaw + (viewEntity.rotationYaw - viewEntity.prevRotationYaw) * (float) partialTicks;
        float camPitch = viewEntity.prevRotationPitch + (viewEntity.rotationPitch - viewEntity.prevRotationPitch) * (float) partialTicks;

        if (this.displayListEntitiesDirty
                || this.chunksToUpdate == null
                || !this.chunksToUpdate.isEmpty()
                || this.renderInfos == null
                || this.renderInfos.isEmpty()
                || Double.isNaN(this.purefps$anchorX)) {
            this.purefps$anchorX = camX;
            this.purefps$anchorY = camY;
            this.purefps$anchorZ = camZ;
            this.purefps$anchorYaw = camYaw;
            this.purefps$anchorPitch = camPitch;
            return;
        }

        double dx = camX - this.purefps$anchorX;
        double dy = camY - this.purefps$anchorY;
        double dz = camZ - this.purefps$anchorZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        float dYaw = Math.abs(camYaw - this.purefps$anchorYaw);
        float dPitch = Math.abs(camPitch - this.purefps$anchorPitch);

        if (distSq < 0.0016D && dYaw < 0.12F && dPitch < 0.12F) {
            this.lastViewEntityX = viewEntity.posX;
            this.lastViewEntityY = viewEntity.posY;
            this.lastViewEntityZ = viewEntity.posZ;
            this.lastViewEntityPitch = (double) viewEntity.rotationPitch;
            this.lastViewEntityYaw = (double) viewEntity.rotationYaw;
        } else {
            this.purefps$anchorX = camX;
            this.purefps$anchorY = camY;
            this.purefps$anchorZ = camZ;
            this.purefps$anchorYaw = camYaw;
            this.purefps$anchorPitch = camPitch;
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
