package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFrustumAccess;
import dev.purefps.core.FastNametagRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. Supprime l'allocation new AxisAlignedBB(posX-2..posX+2) sur les entités marqueurs.
 * 2. Rendu 1-passe ultra-rapide des hologrammes / pseudos (renderLivingLabel) divisant par 2 le coût texte des lobbies Hypixel.
 */
@Mixin(Render.class)
public abstract class MixinRender<T extends Entity> {

    @Shadow
    @Final
    protected RenderManager renderManager;

    @Shadow
    protected abstract void renderName(T entity, double x, double y, double z);

    @Shadow
    public abstract FontRenderer getFontRendererFromRenderManager();

    /**
     * @author PureClient
     * @reason Test Frustum direct sans allouer new AxisAlignedBB sur les entités de taille 0
     */
    @Overwrite
    public boolean shouldRender(T livingEntity, ICamera camera, double camX, double camY, double camZ) {
        if (!livingEntity.isInRangeToRender3d(camX, camY, camZ)) {
            return false;
        }
        if (livingEntity.ignoreFrustumCheck) {
            return true;
        }
        AxisAlignedBB bb = livingEntity.getEntityBoundingBox();
        if (bb.hasNaN() || bb.getAverageEdgeLength() == 0.0D) {
            if (PureFPSConfig.enabled && camera instanceof FastFrustumAccess) {
                return ((FastFrustumAccess) camera).purefps$isSphereOrBoxInFrustum(
                        livingEntity.posX - 2.0D, livingEntity.posY - 2.0D, livingEntity.posZ - 2.0D,
                        livingEntity.posX + 2.0D, livingEntity.posY + 2.0D, livingEntity.posZ + 2.0D
                );
            }
            bb = new AxisAlignedBB(
                    livingEntity.posX - 2.0D, livingEntity.posY - 2.0D, livingEntity.posZ - 2.0D,
                    livingEntity.posX + 2.0D, livingEntity.posY + 2.0D, livingEntity.posZ + 2.0D
            );
        }
        return camera.isBoundingBoxInFrustum(bb);
    }

    /**
     * @author PureClient
     * @reason Culling de distance des pseudos/hologrammes zéro-allocation avant la création des événements Forge
     */
    @Overwrite
    public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks) {
        if (PureFPSConfig.enabled && PureFPSConfig.nametagDistanceCulling) {
            double maxDist = PureFPSConfig.maxNametagDistance;
            if ((x * x + y * y + z * z) > maxDist * maxDist) {
                return;
            }
        }
        this.renderName(entity, x, y, z);
    }

    @Inject(method = "renderLivingLabel", at = @At("HEAD"), cancellable = true)
    private void purefps$fastSinglePassLabel(
            T entityIn,
            String str,
            double x,
            double y,
            double z,
            int maxDistance,
            CallbackInfo ci
    ) {
        if (PureFPSConfig.enabled && (PureFPSConfig.fastNametags || PureFPSConfig.transparentNametags)) {
            FastNametagRenderer.renderSinglePassLabel(
                    entityIn,
                    str,
                    x,
                    y,
                    z,
                    maxDistance,
                    this.renderManager,
                    this.getFontRendererFromRenderManager()
            );
            ci.cancel();
        }
    }
}
