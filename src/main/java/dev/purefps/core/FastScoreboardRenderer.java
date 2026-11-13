package dev.purefps.core;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.opengl.GL11;

import java.util.Collection;

/**
 * Rendu ultra-rapide du Scoreboard Hypixel (100 % identique au pixel près à Vanilla 1.8.9) :
 * - Supprime Iterables.filter, Predicate anonyme, double Lists.newArrayList et les 30 StringBuilder par frame.
 * - Fusionne les 17 appels Gui.drawRect (17 draw calls OpenGL + 34 bascules Texture2D) en 1 seul appel 2-quads !
 */
public final class FastScoreboardRenderer {

    private static final Score[] FILTERED_BUFFER = new Score[64];
    private static final Score[] DISPLAY_SCORES = new Score[15];
    private static final String[] DISPLAY_NAMES = new String[15];
    private static final String[] DISPLAY_POINTS = new String[15];

    private FastScoreboardRenderer() {
    }

    public static void render(ScoreObjective objective, ScaledResolution scaledRes, FontRenderer font) {
        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> sorted = scoreboard.getSortedScores(objective);
        if (sorted == null || sorted.isEmpty()) {
            return;
        }

        // Passe unique sans Predicate ni ArrayList intermédiaire : conserve les 15 derniers scores valides
        Score[] ring = FILTERED_BUFFER;
        int totalValid = 0;
        for (Score score : sorted) {
            if (score == null) {
                continue;
            }
            String playerName = score.getPlayerName();
            if (playerName == null || playerName.startsWith("#")) {
                continue;
            }
            ring[totalValid % 15] = score;
            totalValid++;
        }

        int count = Math.min(totalValid, 15);
        if (count == 0) {
            return;
        }

        int startRingIdx = totalValid > 15 ? (totalValid % 15) : 0;
        String title = objective.getDisplayName();
        int maxWidth = font.getStringWidth(title);
        int colonSpaceWidth = font.getStringWidth(": ");

        for (int i = 0; i < count; i++) {
            Score score = ring[(startRingIdx + i) % 15];
            DISPLAY_SCORES[i] = score;
            String rawName = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(rawName);
            String formattedName = ScorePlayerTeam.formatPlayerName(team, rawName);
            String pointsText = EnumChatFormatting.RED.toString() + score.getScorePoints();
            DISPLAY_NAMES[i] = formattedName;
            DISPLAY_POINTS[i] = pointsText;

            int lineWidth = font.getStringWidth(formattedName) + colonSpaceWidth + font.getStringWidth(pointsText);
            if (lineWidth > maxWidth) {
                maxWidth = lineWidth;
            }
        }

        int fontHeight = font.FONT_HEIGHT;
        int bodyHeight = count * fontHeight;
        int bottomY = scaledRes.getScaledHeight() / 2 + bodyHeight / 3;
        int rightMargin = 3;
        int leftX = scaledRes.getScaledWidth() - maxWidth - rightMargin;
        int rightX = scaledRes.getScaledWidth() - rightMargin + 2;
        int bgLeftX = leftX - 2;
        int topRowY = bottomY - bodyHeight;
        int headerTopY = topRowY - fontHeight - 1;
        int headerBottomY = topRowY - 1;

        // 1 SEUL draw call OpenGL (2 quads colorés) au lieu de 17 appels Gui.drawRect séparés !
        drawScoreboardBackground(bgLeftX, headerTopY, headerBottomY, rightX, bottomY);

        // Titre centré
        int titleX = leftX + maxWidth / 2 - font.getStringWidth(title) / 2;
        font.drawString(title, titleX, topRowY - fontHeight, 553648127);

        // Lignes de bas en haut comme Vanilla (j = 1..count)
        for (int i = 0; i < count; i++) {
            int rowY = bottomY - (i + 1) * fontHeight;
            String name = DISPLAY_NAMES[i];
            String pts = DISPLAY_POINTS[i];
            font.drawString(name, leftX, rowY, 553648127);
            font.drawString(pts, rightX - font.getStringWidth(pts), rowY, 553648127);

            DISPLAY_SCORES[i] = null;
            DISPLAY_NAMES[i] = null;
            DISPLAY_POINTS[i] = null;
        }
    }

    private static void drawScoreboardBackground(int left, int headerTop, int headerBottom, int right, int bodyBottom) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // En-tête : 1610612736 = 0x60000000 (alpha = 96, RGB = 0)
        putColoredRect(wr, left, headerTop, right, headerBottom, 0, 0, 0, 96);
        // Séparateur 1px + corps complet : 1342177280 = 0x50000000 (alpha = 80, RGB = 0)
        putColoredRect(wr, left, headerBottom, right, bodyBottom, 0, 0, 0, 80);
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        // Maintient la compatibilité si un mod attend l'état color post-drawRect
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void putColoredRect(WorldRenderer wr, int left, int top, int right, int bottom, int r, int g, int b, int a) {
        wr.pos((double) left, (double) bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) right, (double) bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) right, (double) top, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) left, (double) top, 0.0D).color(r, g, b, a).endVertex();
    }
}
