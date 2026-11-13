package dev.purefps.client;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

/**
 * Batcher 2D ultra-léger : regroupe toutes les cartes arrondies (chanfrein pixel 2px type OneClient/Lunar)
 * et leurs bordures hairline 1px dans UN SEUL appel Tessellator.draw() par frame.
 */
public final class GlUiBatchRenderer {

    private GlUiBatchRenderer() {
    }

    public static WorldRenderer beginBatch() {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        return wr;
    }

    public static void flushBatch() {
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void addQuad(WorldRenderer wr, float x1, float y1, float x2, float y2, int argb) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        wr.pos(x1, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, 0.0D).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, 0.0D).color(r, g, b, a).endVertex();
    }

    /**
     * Ajoute une carte aux coins adoucis (chanfrein 2px sans shader coûteux) + bordure hairline 1px
     * directement dans le tampon de quads en cours.
     */
    public static void addRoundedCard(WorldRenderer wr, float x, float y, float w, float h, int fillArgb, int borderArgb) {
        // Corps central + bandes haut/bas (coins adoucis de 2px)
        addQuad(wr, x + 2.0F, y + 1.0F, x + w - 2.0F, y + 2.0F, fillArgb);
        addQuad(wr, x + 1.0F, y + 2.0F, x + w - 1.0F, y + h - 2.0F, fillArgb);
        addQuad(wr, x + 2.0F, y + h - 2.0F, x + w - 2.0F, y + h - 1.0F, fillArgb);

        if ((borderArgb >>> 24) != 0) {
            // Bordure hairline 1px
            addQuad(wr, x + 2.0F, y, x + w - 2.0F, y + 1.0F, borderArgb);
            addQuad(wr, x + 2.0F, y + h - 1.0F, x + w - 2.0F, y + h, borderArgb);
            addQuad(wr, x, y + 2.0F, x + 1.0F, y + h - 2.0F, borderArgb);
            addQuad(wr, x + w - 1.0F, y + 2.0F, x + w, y + h - 2.0F, borderArgb);
            // Pixels diagonaux des 4 coins
            addQuad(wr, x + 1.0F, y + 1.0F, x + 2.0F, y + 2.0F, borderArgb);
            addQuad(wr, x + w - 2.0F, y + 1.0F, x + w - 1.0F, y + 2.0F, borderArgb);
            addQuad(wr, x + 1.0F, y + h - 2.0F, x + 2.0F, y + h - 1.0F, borderArgb);
            addQuad(wr, x + w - 2.0F, y + h - 2.0F, x + w - 1.0F, y + h - 1.0F, borderArgb);
        }
    }
}
