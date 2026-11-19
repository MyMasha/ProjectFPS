package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.VulkanBackendHelper;
import net.minecraft.client.gui.GuiOverlayDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Affiche sur l'écran F3 le profil actif, le batching GPU VAO+EBO, le déchargement des coeurs d'îles,
 * les compteurs temps-réel et le backend graphique (Vulkan Mesa3D Zink vs OpenGL) de MashClient 4.5.12.
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
            lines.add("\u00a7b[MashClient 4.5.12]\u00a7r " + (PureFPSConfig.isEnglish() ? "Disabled (/mashclient)" : "Désactivé (/mashclient)"));
        } else {
            lines.add(String.format(
                    "\u00a7b[MashClient 4.5.12 | %s\u00a7b | %s\u00a7b]\u00a7r MultiDraw: %dc (%dd) | CoreUnload:%d | StaticSkip:%d | Culled E:%d TE:%d P:%d",
                    PureFPSConfig.activeProfile.getColoredName(),
                    VulkanBackendHelper.getF3BackendBadge(),
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

    @Inject(method = "getDebugInfoRight", at = @At("RETURN"))
    private void purefps$appendVulkanRightDebugInfo(CallbackInfoReturnable<List<String>> cir) {
        if (!VulkanBackendHelper.isVulkanActive() && !VulkanBackendHelper.isVulkanFlagSet()) {
            return;
        }
        List<String> rightLines = cir.getReturnValue();
        if (rightLines == null) {
            return;
        }
        String renderer = VulkanBackendHelper.getCachedRendererName();
        String label = VulkanBackendHelper.isNativeZinkConfirmed()
                ? "\u00a7dBackend: Vulkan 1.3 (Mesa3D Zink)"
                : "\u00a7eBackend: Vulkan Fallback -> OpenGL (" + (renderer != null && !renderer.isEmpty() ? renderer : "native") + ")";
        for (int i = 0; i < rightLines.size(); i++) {
            String line = rightLines.get(i);
            if (line != null && line.startsWith("Display: ")) {
                int insertIdx = Math.min(rightLines.size(), i + 3);
                rightLines.add(insertIdx, label);
                return;
            }
        }
        rightLines.add(label);
    }
}
