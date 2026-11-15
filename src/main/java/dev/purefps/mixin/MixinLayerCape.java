package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LOD des capes des autres joueurs (LayerCape) :
 * Coupe le calcul trigonométrique + bindTexture + 4 rotations matricielles des capes au-delà de
 * 28 blocs (profil FPS) ou 40 blocs (profil Équilibré) dans les lobbies chargés.
 */
@Mixin(LayerCape.class)
public class MixinLayerCape {

    @Inject(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void purefps$cullDistantPlayerCapes(
            AbstractClientPlayer player,
            float f,
            float g,
            float partialTicks,
            float h,
            float i,
            float j,
            float scale,
            CallbackInfo ci
    ) {
        if (!PureFPSConfig.enabled || (!PureFPSConfig.fastPlayerRendering && !dev.purefps.module.MashFlags.capeCullEnabled) || player == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc != null ? mc.getRenderViewEntity() : null;
        if (viewer != null && viewer != player) {
            double maxDistSq = dev.purefps.module.MashFlags.capeCullEnabled
                    ? dev.purefps.module.MashFlags.capeMaxDistSq
                    : ((PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 784.0D : 1600.0D);
            if (viewer.getDistanceSqToEntity(player) > maxDistSq) {
                ci.cancel();
            }
        }
    }
}
