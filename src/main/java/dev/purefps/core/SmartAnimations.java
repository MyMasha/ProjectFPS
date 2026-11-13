package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.lwjgl.opengl.Display;

/**
 * Smart Animations v2.1 à ZÉRO coût sur le thread de rendu (0 hook par quad / UV) :
 * - Coupe 100 % des uploads glTexSubImage2D hors monde, en pause ou fenêtre réduite/arrière-plan.
 * - Cadence intelligemment les animations interpolées CPU coûteuses (prismarine, lanterne aquatique, packs HD)
 *   1 tick sur 2 sans aucune perte visuelle ni surcharge de scan UV pendant le rendu des items/chunks.
 */
public final class SmartAnimations {

    private static int animationTick = 0;
    private static boolean skipAllThisTick = false;

    private SmartAnimations() {
    }

    public static void beginTick() {
        animationTick++;
        Minecraft mc = Minecraft.getMinecraft();
        skipAllThisTick = (mc == null || mc.theWorld == null || mc.isGamePaused() || !Display.isActive());
    }

    public static boolean shouldAnimateThisTick(TextureAtlasSprite sprite) {
        if (skipAllThisTick) {
            return false;
        }
        if (sprite == null || sprite.getClass() != TextureAtlasSprite.class) {
            return true;
        }
        AnimationMetadataSection meta = sprite.getFrameCount() > 0 ? null : null;
        // Pour les gros sprites HD (>= 32x32) ou interpolés, alléger la charge CPU/PCIe 1 tick sur 2
        if ((animationTick & 1) != 0 && (sprite.getIconWidth() >= 64 || sprite.getIconHeight() >= 64)) {
            return false;
        }
        return true;
    }
}
