package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import org.lwjgl.opengl.GL11;

/**
 * Rendu 3D flottant du compte à rebours TNT (style Lunar Client) :
 * - Compatible Hypixel BedWars (52 ticks = 2.60s) et Vanilla / SkyWars (80 ticks = 4.00s).
 * - Interpolation fluide via partialTicks et dégradé vert -> jaune -> orange -> rouge.
 */
public final class TntTimerRenderer {

    private static final double MAX_RENDER_DIST_SQ = 64.0D * 64.0D;

    private static long lastScoreboardCheckMs = 0L;
    private static boolean cachedBedWarsServer = false;

    private TntTimerRenderer() {
    }

    public static float computeRemainingSeconds(
            int fuseTicks,
            int ticksExisted,
            float partialTicks,
            int fuseMode,
            boolean detectedBedWars
    ) {
        boolean useBedWars52 = (fuseMode == 2)
                || (fuseMode == 0 && detectedBedWars && (fuseTicks + ticksExisted) >= 68);
        float effectiveTicks = useBedWars52
                ? ((float) (fuseTicks - 28) - partialTicks)
                : ((float) fuseTicks - partialTicks);
        if (effectiveTicks < 0.0F) {
            effectiveTicks = 0.0F;
        }
        return effectiveTicks / 20.0F;
    }

    public static float resolveMaxFuseSeconds(int fuseTicks, int ticksExisted, int fuseMode, boolean detectedBedWars) {
        boolean useBedWars52 = (fuseMode == 2)
                || (fuseMode == 0 && detectedBedWars && (fuseTicks + ticksExisted) >= 68);
        return useBedWars52 ? 2.60F : 4.00F;
    }

    public static int computeFuseColor(float remainingSec, float maxSec, boolean dynamicColor) {
        if (!dynamicColor) {
            return 0xFFF4F6FA;
        }
        float ratio = maxSec > 0.01F ? (remainingSec / maxSec) : 0.0F;
        if (ratio > 0.62F) {
            return 0xFF34D399; // Vert émeraude
        }
        if (ratio > 0.36F) {
            return 0xFFFBBF24; // Jaune ambré
        }
        if (ratio > 0.16F) {
            return 0xFFFB923C; // Orange vif
        }
        return 0xFFF87171; // Rouge critique
    }

    public static String formatSeconds(float seconds) {
        int centis = Math.max(0, Math.round(seconds * 100.0F));
        int whole = centis / 100;
        int rem = centis % 100;
        StringBuilder sb = new StringBuilder(6);
        sb.append(whole).append('.');
        if (rem < 10) {
            sb.append('0');
        }
        sb.append(rem).append('s');
        return sb.toString();
    }

    public static void render(EntityTNTPrimed entity, double x, double y, double z, float partialTicks) {
        if (!PureFPSConfig.enabled || !MashFlags.tntTimerEnabled || entity == null) {
            return;
        }
        if (x * x + y * y + z * z > MAX_RENDER_DIST_SQ) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || mc.getRenderManager() == null) {
            return;
        }

        boolean bedWars = isBedWarsActive(mc);
        int fuseMode = MashFlags.tntTimerFuseMode;
        float remSec = computeRemainingSeconds(entity.fuse, entity.ticksExisted, partialTicks, fuseMode, bedWars);
        float maxSec = resolveMaxFuseSeconds(entity.fuse, entity.ticksExisted, fuseMode, bedWars);
        int color = computeFuseColor(remSec, maxSec, MashFlags.tntTimerDynamicColor);
        String label = formatSeconds(remSec);

        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = mc.fontRendererObj;
        float viewSign = (mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2) ? -1.0F : 1.0F;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + entity.height + 0.52F, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(rm.playerViewX * viewSign, 1.0F, 0.0F, 0.0F);
        float scale = 0.02666667F;
        GlStateManager.scale(-scale, -scale, scale);

        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        int halfW = fr.getStringWidth(label) / 2;
        GlStateManager.disableTexture2D();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-halfW - 3, -2, 0.0D).color(8, 10, 16, 140).endVertex();
        wr.pos(-halfW - 3, 9, 0.0D).color(8, 10, 16, 140).endVertex();
        wr.pos(halfW + 3, 9, 0.0D).color(8, 10, 16, 140).endVertex();
        wr.pos(halfW + 3, -2, 0.0D).color(8, 10, 16, 140).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();

        fr.drawString(label, -halfW, 0, (color & 0x00FFFFFF) | 0x66000000);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        fr.drawStringWithShadow(label, -halfW, 0, color);

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static boolean isBedWarsActive(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastScoreboardCheckMs < 1000L) {
            return cachedBedWarsServer;
        }
        lastScoreboardCheckMs = now;
        cachedBedWarsServer = false;
        if (mc.theWorld == null) {
            return false;
        }
        Scoreboard sb = mc.theWorld.getScoreboard();
        if (sb == null) {
            return false;
        }
        ScoreObjective sidebar = sb.getObjectiveInDisplaySlot(1);
        if (sidebar == null || sidebar.getDisplayName() == null) {
            return false;
        }
        String clean = stripFormattingFast(sidebar.getDisplayName());
        cachedBedWarsServer = clean.contains("BED WARS") || clean.contains("BEDWARS");
        return cachedBedWarsServer;
    }

    private static String stripFormattingFast(String raw) {
        int len = raw.length();
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                i++;
                continue;
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
}
