package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.GuiOverlayDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Affiche sur l'écran F3 le profil actif, le batching GPU VAO+EBO, le déchargement des coeurs d'îles et les compteurs temps-réel de MashClient 4.3.
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
            lines.add("\u00a7b[MashClient 4.5.3]\u00a7r Désactivé (/mashclient)");
        } else {
            lines.add(String.format(
                    "\u00a7b[MashClient 4.5.3 | %s\u00a7b | %s\u00a7b]\u00a7r MultiDraw: %dc (%dd) | CoreUnload:%d | StaticSkip:%d | Culled E:%d TE:%d P:%d",
                    PureFPSConfig.activeProfile.getColoredName(),
                    dev.purefps.core.VulkanBackendHelper.getF3BackendBadge(),
                    PureFPSConfig.chunksBatchedLastFrame,
                    PureFPSConfig.multiDrawsLastFrame,
                    PureFPSConfig.unloadedIslandCoresTotal,
                    PureFPSConfig.throttledStaticUpdatesTotal,
                    PureFPSConfig.culledEntitiesLastFrame,
                    PureFPSConfig.culledTileEntitiesLastFrame,
                    PureFPSConfig.culledParticlesLastFrame
            ));
        }
    }
}
