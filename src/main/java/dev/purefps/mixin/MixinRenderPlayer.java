package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.model.ModelBase;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation LOD du rendu des autres joueurs (RenderPlayer) :
 * - Chaque joueur possède 6 boîtes de seconde couche de skin (veste, manches, jambes, chapeau) dessinées en alpha-blend.
 * - Au-delà de 24 blocs (FPS) ou 36 blocs (Équilibré), l'épaisseur de 0.25 pixel de ces sur-couches est inférieure à 0.3 px
 *   à l'écran : les masquer divise par ~2 le nombre de boîtes de skin et l'overdraw alpha des lobbies bondés sans perte visuelle !
 */
@Mixin(RenderPlayer.class)
public abstract class MixinRenderPlayer extends RendererLivingEntity<AbstractClientPlayer> {

    @Shadow
    public abstract ModelPlayer getMainModel();

    protected MixinRenderPlayer(RenderManager renderManagerIn, ModelBase modelBaseIn, float shadowSizeIn) {
        super(renderManagerIn, modelBaseIn, shadowSizeIn);
    }

    @Inject(method = "setModelVisibilities", at = @At("RETURN"))
    private void purefps$applyDistantPlayerWearLod(AbstractClientPlayer clientPlayer, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastPlayerRendering || clientPlayer == null || clientPlayer.isSpectator()) {
            return;
        }
        Entity viewer = this.renderManager != null ? this.renderManager.livingPlayer : null;
        if (viewer == null || viewer == clientPlayer) {
            return;
        }
        double distSq = viewer.getDistanceSqToEntity(clientPlayer);
        double wearCutoffSq = (PureFPSConfig.activeProfile == PureFPSProfile.FPS)
                ? Math.min(576.0D, dev.purefps.module.MashFlags.playerDetailDistSq)
                : dev.purefps.module.MashFlags.playerDetailDistSq;
        if (distSq > wearCutoffSq) {
            ModelPlayer mp = this.getMainModel();
            mp.bipedBodyWear.showModel = false;
            mp.bipedLeftLegwear.showModel = false;
            mp.bipedRightLegwear.showModel = false;
            mp.bipedLeftArmwear.showModel = false;
            mp.bipedRightArmwear.showModel = false;
            if (distSq > wearCutoffSq * 1.5D) {
                mp.bipedHeadwear.showModel = false;
            }
        }
    }
}
