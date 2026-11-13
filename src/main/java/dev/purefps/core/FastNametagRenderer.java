package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;

/**
 * Rendu ultra-rapide 1-passe des hologrammes / pseudos (au lieu des 2 passes FontRenderer.drawString
 * par ligne dans Render.renderLivingLabel, qui doublent le coût des ~135 ArmorStands des lobbies Hypixel).
 */
public final class FastNametagRenderer {

    private static final float NAMETAG_SCALE = 0.02666667F;

    private FastNametagRenderer() {
    }

    public static void renderSinglePassLabel(
            Entity entity,
            String str,
            double x,
            double y,
            double z,
            int maxDistance,
            RenderManager renderManager,
            FontRenderer fontRenderer
    ) {
        if (str == null || str.isEmpty() || fontRenderer == null || renderManager == null || renderManager.livingPlayer == null) {
            return;
        }
        double distSq = entity.getDistanceSqToEntity(renderManager.livingPlayer);
        if (distSq > (double) (maxDistance * maxDistance)) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + entity.height + 0.5F, (float) z);
        GlStateManager.rotate(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-NAMETAG_SCALE, -NAMETAG_SCALE, NAMETAG_SCALE);
        GlStateManager.disableLighting();

        boolean sneaking = entity.isSneaking();
        int yOffset = "deadmau5".equals(str) ? -10 : 0;
        int halfWidth = fontRenderer.getStringWidth(str) >> 1;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        if (!PureFPSConfig.transparentNametags) {
            GlStateManager.depthMask(false);
            if (!sneaking) {
                GlStateManager.disableDepth();
            }
            GlStateManager.disableTexture2D();
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(-halfWidth - 1, -1 + yOffset, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(-halfWidth - 1, 8 + yOffset, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(halfWidth + 1, 8 + yOffset, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(halfWidth + 1, -1 + yOffset, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            tessellator.draw();
            GlStateManager.enableTexture2D();
        }

        // Passe unique de texte avec test de profondeur actif (économise 50 % des appels FontRenderer sur les hologrammes)
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        int textColor = sneaking ? 0x20FFFFFF : -1;
        fontRenderer.drawString(str, -halfWidth, yOffset, textColor);

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }
}
