package dev.purefps.core;

import dev.purefps.module.MashFlags;
import net.minecraft.client.gui.FontRenderer;
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

import java.util.Arrays;
import java.util.Collection;

/**
 * Rendu ultra-rapide du Scoreboard Hypixel (MashClient 4.5.15) :
 * - Cache intégral des 15 lignes triées/formatées, de leurs largeurs et de la largeur maximale,
 *   invalidé uniquement sur mutation réelle via le compteur de révision de MixinScoreboard.
 * - Table statique RED_POINTS_LUT[0..99] (zéro concaténation `"\u00a7c" + pts`).
 * - Fusionne les 17 appels Gui.drawRect en 1 seul draw 2-quads + 1 seul lot FastFontBatcher.
 * - Nettoie toutes les références Score/ScoreObjective via clearCache() au changement de monde.
 */
public final class FastScoreboardRenderer {

    private static final String[] RED_POINTS_LUT = buildRedPointsLut();
    private static final Score[] FILTERED_BUFFER = new Score[15];

    private static volatile int scoreboardRevision = 1;

    // Cache des lignes pré-calculées (valide tant que le scoreboard ne change pas)
    private static int cachedRevision = -1;
    private static ScoreObjective cachedObjective = null;
    private static FontRenderer cachedFont = null;
    private static boolean cachedHideNumbers = false;
    private static boolean cachedNickHider = false;
    private static String cachedNickReplacement = null;

    private static int cachedCount = 0;
    private static String cachedTitle = "";
    private static int cachedTitleWidth = 0;
    private static int cachedMaxWidth = 0;
    private static final String[] CACHED_NAMES = new String[15];
    private static final String[] CACHED_POINTS = new String[15];
    private static final int[] CACHED_POINTS_WIDTH = new int[15];

    private FastScoreboardRenderer() {
    }

    private static String[] buildRedPointsLut() {
        String prefix = EnumChatFormatting.RED.toString();
        String[] lut = new String[100];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = prefix + i;
        }
        return lut;
    }

    public static String formatRedPoints(int points) {
        if (points >= 0 && points < RED_POINTS_LUT.length) {
            return RED_POINTS_LUT[points];
        }
        return EnumChatFormatting.RED.toString() + points;
    }

    public static void notifyScoreboardMutated() {
        scoreboardRevision++;
    }

    public static int getRevisionForTest() {
        return scoreboardRevision;
    }

    public static void clearCache() {
        cachedRevision = -1;
        cachedObjective = null;
        cachedFont = null;
        cachedCount = 0;
        cachedTitle = "";
        cachedTitleWidth = 0;
        cachedMaxWidth = 0;
        Arrays.fill(FILTERED_BUFFER, null);
        Arrays.fill(CACHED_NAMES, null);
        Arrays.fill(CACHED_POINTS, null);
        Arrays.fill(CACHED_POINTS_WIDTH, 0);
    }

    static boolean hasCachedReferencesForTest() {
        if (cachedObjective != null || cachedFont != null) {
            return true;
        }
        for (int i = 0; i < FILTERED_BUFFER.length; i++) {
            if (FILTERED_BUFFER[i] != null || CACHED_NAMES[i] != null || CACHED_POINTS[i] != null) {
                return true;
            }
        }
        return false;
    }

    public static void render(ScoreObjective objective, ScaledResolution scaledRes, FontRenderer font) {
        if (MashFlags.scoreboardHidden || objective == null || font == null) {
            return;
        }

        boolean hideNumbers = MashFlags.scoreboardHideNumbers;
        boolean nickOn = MashFlags.nickHiderEnabled;
        String nickName = nickOn ? MashFlags.nickReplacement : null;
        int rev = scoreboardRevision;

        boolean cacheHit = (rev == cachedRevision)
                && (objective == cachedObjective)
                && (font == cachedFont)
                && (hideNumbers == cachedHideNumbers)
                && (nickOn == cachedNickHider)
                && (nickName == cachedNickReplacement || (nickName != null && nickName.equals(cachedNickReplacement)));

        if (!cacheHit) {
            rebuildCache(objective, font, hideNumbers, nickOn, nickName, rev);
        }

        int count = cachedCount;
        if (count == 0) {
            return;
        }

        int maxWidth = cachedMaxWidth;
        int fontHeight = font.FONT_HEIGHT;
        int bodyHeight = count * fontHeight;
        int bottomY = scaledRes.getScaledHeight() / 2 + bodyHeight / 3 + MashFlags.scoreboardOffsetY;
        int rightMargin = 3;
        int leftX = scaledRes.getScaledWidth() - maxWidth - rightMargin;
        int rightX = scaledRes.getScaledWidth() - rightMargin + 2;
        int bgLeftX = leftX - 2;
        int topRowY = bottomY - bodyHeight;
        int headerTopY = topRowY - fontHeight - 1;
        int headerBottomY = topRowY - 1;

        drawScoreboardBackground(bgLeftX, headerTopY, headerBottomY, rightX, bottomY);

        int titleX = leftX + maxWidth / 2 - cachedTitleWidth / 2;
        FastFontBatcher.beginOuterBatch();
        try {
            font.drawString(cachedTitle, titleX, topRowY - fontHeight, 553648127);

            for (int i = 0; i < count; i++) {
                int rowY = bottomY - (i + 1) * fontHeight;
                String name = CACHED_NAMES[i];
                if (name != null) {
                    font.drawString(name, leftX, rowY, 553648127);
                }
                if (!hideNumbers) {
                    String pts = CACHED_POINTS[i];
                    if (pts != null && !pts.isEmpty()) {
                        font.drawString(pts, rightX - CACHED_POINTS_WIDTH[i], rowY, 553648127);
                    }
                }
            }
        } finally {
            FastFontBatcher.endOuterBatch();
        }
    }

    private static void rebuildCache(
            ScoreObjective objective,
            FontRenderer font,
            boolean hideNumbers,
            boolean nickOn,
            String nickName,
            int rev
    ) {
        cachedRevision = rev;
        cachedObjective = objective;
        cachedFont = font;
        cachedHideNumbers = hideNumbers;
        cachedNickHider = nickOn;
        cachedNickReplacement = nickName;

        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> sorted = (scoreboard != null) ? scoreboard.getSortedScores(objective) : null;
        if (sorted == null || sorted.isEmpty()) {
            cachedCount = 0;
            return;
        }

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
        cachedCount = count;
        if (count == 0) {
            Arrays.fill(ring, null);
            return;
        }

        int startRingIdx = totalValid > 15 ? (totalValid % 15) : 0;
        String title = objective.getDisplayName();
        if (title == null) {
            title = "";
        }
        int titleW = font.getStringWidth(title);
        int maxWidth = titleW;
        int colonSpaceWidth = hideNumbers ? 0 : font.getStringWidth(": ");

        for (int i = 0; i < count; i++) {
            Score score = ring[(startRingIdx + i) % 15];
            String rawName = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(rawName);
            String formattedName = ScorePlayerTeam.formatPlayerName(team, rawName);
            CACHED_NAMES[i] = formattedName;

            int lineWidth;
            if (hideNumbers) {
                CACHED_POINTS[i] = "";
                CACHED_POINTS_WIDTH[i] = 0;
                lineWidth = font.getStringWidth(formattedName) + 2;
            } else {
                String pts = formatRedPoints(score.getScorePoints());
                int ptsW = font.getStringWidth(pts);
                CACHED_POINTS[i] = pts;
                CACHED_POINTS_WIDTH[i] = ptsW;
                lineWidth = font.getStringWidth(formattedName) + colonSpaceWidth + ptsW;
            }
            if (lineWidth > maxWidth) {
                maxWidth = lineWidth;
            }
        }
        for (int i = count; i < 15; i++) {
            CACHED_NAMES[i] = null;
            CACHED_POINTS[i] = null;
            CACHED_POINTS_WIDTH[i] = 0;
        }
        // Libère immédiatement les références Score du tampon de filtrage
        Arrays.fill(ring, null);

        cachedTitle = title;
        cachedTitleWidth = titleW;
        cachedMaxWidth = maxWidth;
    }

    private static void drawScoreboardBackground(int left, int headerTop, int headerBottom, int right, int bodyBottom) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 771);

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        putColoredRect(wr, left, headerTop, right, headerBottom, 0, 0, 0, 96);
        putColoredRect(wr, left, headerBottom, right, bodyBottom, 0, 0, 0, 80);
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void putColoredRect(WorldRenderer wr, int left, int top, int right, int bottom, int r, int g, int b, int a) {
        wr.pos((double) left, (double) bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) right, (double) bottom, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) right, (double) top, 0.0D).color(r, g, b, a).endVertex();
        wr.pos((double) left, (double) top, 0.0D).color(r, g, b, a).endVertex();
    }
}
