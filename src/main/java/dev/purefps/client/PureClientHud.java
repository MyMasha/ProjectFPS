package dev.purefps.client;

import dev.purefps.module.MashHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Pont de rendu HUD MashClient 4.0 :
 * délègue l'intégralité du rendu au moteur unifié MashHudRenderer (65 modules HUD + Curseur + Arc + Vie critique).
 */
public final class PureClientHud {

    private PureClientHud() {
    }

    public static void render(Minecraft mc, ScaledResolution sr, boolean editorPreview) {
        MashHudRenderer.renderOverlay(mc, sr, editorPreview, null, false);
    }
}
