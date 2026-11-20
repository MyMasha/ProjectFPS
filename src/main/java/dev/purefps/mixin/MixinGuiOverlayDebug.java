package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontBatcher;
import dev.purefps.core.VulkanBackendHelper;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Affiche sur l'écran F3 le profil actif, le batching GPU VAO+EBO, le déchargement des coeurs d'îles,
 * les compteurs temps-réel et le backend graphique (Vulkan Mesa3D Zink vs OpenGL) de MashClient 1.0.0.
 * Batching complet de toutes les lignes F3 via FastFontBatcher et mise en cache 100 ms sans String.format.
 */
@Mixin(GuiOverlayDebug.class)
public class MixinGuiOverlayDebug {

    @Unique
    private final StringBuilder purefps$f3Builder = new StringBuilder(160);
    @Unique
    private String purefps$cachedF3Line = "";
    @Unique
    private long purefps$lastF3BuildMs = 0L;
    @Unique
    private boolean purefps$f3BatchActive = false;

    @Inject(method = "renderDebugInfo", at = @At("HEAD"))
    private void purefps$beginF3Batch(ScaledResolution scaledResolutionIn, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer) {
            FastFontBatcher.beginOuterBatch();
            this.purefps$f3BatchActive = true;
        }
    }

    @Inject(method = "renderDebugInfo", at = @At("RETURN"))
    private void purefps$endF3Batch(ScaledResolution scaledResolutionIn, CallbackInfo ci) {
        if (this.purefps$f3BatchActive) {
            this.purefps$f3BatchActive = false;
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            FastFontBatcher.endOuterBatch();
        }
    }

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
            lines.add(PureFPSConfig.isEnglish()
                    ? "\u00a7b[MashClient 1.0.0]\u00a7r Disabled (/mashclient)"
                    : "\u00a7b[MashClient 1.0.0]\u00a7r Désactivé (/mashclient)");
        } else {
            long now = System.currentTimeMillis();
            if (now - this.purefps$lastF3BuildMs >= 100L || this.purefps$cachedF3Line.isEmpty()) {
                this.purefps$lastF3BuildMs = now;
                StringBuilder sb = this.purefps$f3Builder;
                sb.setLength(0);
                sb.append("\u00a7b[MashClient 1.0.0 | ")
                        .append(PureFPSConfig.activeProfile.getColoredName())
                        .append("\u00a7b | ")
                        .append(VulkanBackendHelper.getF3BackendBadge())
                        .append("\u00a7b]\u00a7r MultiDraw: ")
                        .append(PureFPSConfig.chunksBatchedLastFrame)
                        .append("c (")
                        .append(PureFPSConfig.multiDrawsLastFrame)
                        .append("d) | CoreUnload:")
                        .append(PureFPSConfig.unloadedIslandCoresTotal)
                        .append(" | StaticSkip:")
                        .append(PureFPSConfig.throttledStaticUpdatesTotal)
                        .append(" | Culled E:")
                        .append(PureFPSConfig.culledEntitiesLastFrame)
                        .append(" TE:")
                        .append(PureFPSConfig.culledTileEntitiesLastFrame)
                        .append(" P:")
                        .append(PureFPSConfig.culledParticlesLastFrame);
                this.purefps$cachedF3Line = sb.toString();
            }
            lines.add(this.purefps$cachedF3Line);
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
        String label = VulkanBackendHelper.getF3RightBackendLabel();
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
