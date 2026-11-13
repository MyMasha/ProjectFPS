package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.GuiOverlayDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Affiche sur l'écran F3 le profil actif, le batching GPU MultiDraw VAO GL30 et la télémétrie temps-réel de PureClient 3.5.
 */
@Mixin(GuiOverlayDebug.class)
public class MixinGuiOverlayDebug {

    @Inject(method = "call", at = @At("RETURN"))
    private void purefps$appendF3Stats(CallbackInfoReturnable<List<String>> cir) {
        if (!PureFPSConfig.showF3Stats) {
            return;
        }
        List<String> lines = cir.getReturnValue();
        if (lines == null) {
            return;
        }
        if (!PureFPSConfig.enabled) {
            lines.add("\u00a7b[PureClient 3.5]\u00a7r Désactivé (/pureclient)");
        } else {
            lines.add(String.format(
                    "\u00a7b[PureClient 3.5 | %s\u00a7b]\u00a7r VAO+MultiDraw: %dc->%dd | LazyBFS:%s | NoGLErr:%s | Culled E:%d TE:%d P:%d",
                    PureFPSConfig.activeProfile.getColoredName(),
                    PureFPSConfig.chunksBatchedLastFrame,
                    PureFPSConfig.multiDrawsLastFrame,
                    PureFPSConfig.lazyTerrainSetup ? "ON" : "OFF",
                    PureFPSConfig.disableGlErrorChecks ? "ON" : "OFF",
                    PureFPSConfig.culledEntitiesLastFrame,
                    PureFPSConfig.culledTileEntitiesLastFrame,
                    PureFPSConfig.culledParticlesLastFrame
            ));
        }
    }
}
