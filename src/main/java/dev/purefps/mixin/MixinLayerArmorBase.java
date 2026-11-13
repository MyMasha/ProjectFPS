package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOD du reflet d'enchantement d'armure (LayerArmorBase.skipRenderGlint) :
 * Sur Hypixel (lobbies, BedWars, SkyWars) avec un pack 32x, chaque pièce d'armure enchantée
 * redessine deux fois le ModelBiped complet avec blend matriciel (8 passes supplémentaires par joueur !).
 * Au-delà de 18 blocs (FPS) ou 32 blocs (Équilibré), désactive temporairement skipRenderGlint sans affecter l'armure proche.
 */
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBase {

    @Shadow
    private boolean skipRenderGlint;

    @Unique
    private boolean purefps$prevSkipGlint = false;
    @Unique
    private boolean purefps$overrodeSkipGlint = false;

    @Inject(method = "doRenderLayer", at = @At("HEAD"))
    private void purefps$beginDistantArmorGlintLod(
            EntityLivingBase entity,
            float p_177141_2_,
            float p_177141_3_,
            float partialTicks,
            float p_177141_5_,
            float p_177141_6_,
            float p_177141_7_,
            float scale,
            CallbackInfo ci
    ) {
        this.purefps$overrodeSkipGlint = false;
        if (!PureFPSConfig.enabled || !PureFPSConfig.limitDistantGlint || this.skipRenderGlint || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc != null ? mc.getRenderViewEntity() : null;
        if (viewer == null || viewer == entity) {
            return;
        }
        double maxDistSq = (PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 324.0D : 1024.0D;
        if (viewer.getDistanceSqToEntity(entity) > maxDistSq) {
            this.purefps$prevSkipGlint = this.skipRenderGlint;
            this.skipRenderGlint = true;
            this.purefps$overrodeSkipGlint = true;
        }
    }

    @Inject(method = "doRenderLayer", at = @At("RETURN"))
    private void purefps$endDistantArmorGlintLod(
            EntityLivingBase entity,
            float p_177141_2_,
            float p_177141_3_,
            float partialTicks,
            float p_177141_5_,
            float p_177141_6_,
            float p_177141_7_,
            float scale,
            CallbackInfo ci
    ) {
        if (this.purefps$overrodeSkipGlint) {
            this.skipRenderGlint = this.purefps$prevSkipGlint;
            this.purefps$overrodeSkipGlint = false;
        }
    }
}
