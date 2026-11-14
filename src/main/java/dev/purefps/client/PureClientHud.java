package dev.purefps.client;

import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Mouse;

/**
 * HUD PureClient 3.5 (Lunar / OneClient / Feather Grade) :
 * - Regroupe désormais TOUTES les cartes SDF arrondies en UNE SEULE session shader NanoUiRenderer.beginBatch()/endBatch()
 *   (supprime les 12 à 44 commutations glUseProgram par frame qui bridaient les FPS dans le vide !).
 * - Regroupe tous les textes SmoothFontRenderer par fonte en 1 à 2 Tessellator.draw() au total.
 * - Échantillonne le ping réseau toutes les 250 ms au lieu d'interroger la table UUID à chaque frame.
 */
public final class PureClientHud {

    public static final int CARD_BG = 0xCC0A0D14;
    public static final int CARD_BG_ACTIVE = 0xE05E6AD2;
    public static final int CARD_BORDER = 0x24FFFFFF;
    public static final int CARD_BORDER_ACCENT = 0x885E6AD2;

    public static final int FPS_CARD_W = 82;
    public static final int FPS_CARD_H = 31;
    public static final int PING_CARD_W = 82;
    public static final int PING_CARD_H = 19;
    public static final int SPRINT_CARD_W = 98;
    public static final int SPRINT_CARD_H = 19;
    public static final int KEYSTROKES_W = 78;
    public static final int KEYSTROKES_H = 84;

    private static int lastFpsVal = -1;
    private static String cachedFpsText = "0 FPS";

    private static int lastLeftCps = -1;
    private static int lastRightCps = -1;
    private static String cachedCpsText = "0  |  0 CPS";

    private static int lastPingVal = -2;
    private static int cachedRawPing = -1;
    private static long lastPingCheckMs = 0L;
    private static String cachedPingText = "-- ms";

    private PureClientHud() {
    }

    public static void render(Minecraft mc, ScaledResolution sr, boolean editorPreview) {
        if (mc == null || mc.fontRendererObj == null || sr == null) {
            return;
        }
        if (!editorPreview) {
            if (!PureFPSConfig.enabled || !PureFPSConfig.hudEnabled) {
                return;
            }
            if (mc.gameSettings.showDebugInfo) {
                return;
            }
        }

        boolean showFps = PureFPSConfig.hudFpsCps;
        boolean showPing = PureFPSConfig.hudPing;
        boolean showSprint = PureFPSConfig.hudSprintStatus && (PureFPSConfig.toggleSprint || editorPreview);
        boolean showKeys = PureFPSConfig.hudKeystrokes;
        boolean showArmor = PureFPSConfig.hudArmor && mc.thePlayer != null && !mc.thePlayer.capabilities.isCreativeMode;

        if (!showFps && !showPing && !showSprint && !showKeys && !showArmor) {
            return;
        }

        NanoUiRenderer.setScaleFactor(sr.getScaleFactor());
        boolean smooth = PureFPSConfig.smoothClientFont;
        FontRenderer vanillaFr = mc.fontRendererObj;

        long nowMs = System.currentTimeMillis();

        if (showFps) {
            int fps = Minecraft.getDebugFPS();
            if (fps != lastFpsVal) {
                lastFpsVal = fps;
                cachedFpsText = fps + " FPS";
            }

            int lCps = CpsTracker.getLeftCps(nowMs);
            int rCps = CpsTracker.getRightCps(nowMs);
            if (lCps != lastLeftCps || rCps != lastRightCps) {
                lastLeftCps = lCps;
                lastRightCps = rCps;
                cachedCpsText = lCps + "  \u2022  " + rCps + " CPS";
            }
        }

        int ping = cachedRawPing;
        if (showPing) {
            if (nowMs - lastPingCheckMs >= 250L || lastPingVal == -2) {
                lastPingCheckMs = nowMs;
                ping = resolvePing(mc);
                cachedRawPing = ping;
            }
            if (ping != lastPingVal) {
                lastPingVal = ping;
                cachedPingText = ping >= 0 ? (ping + " ms") : "Mode Solo";
            }
        }

        boolean keyW = showKeys && mc.gameSettings.keyBindForward.isKeyDown();
        boolean keyA = showKeys && mc.gameSettings.keyBindLeft.isKeyDown();
        boolean keyS = showKeys && mc.gameSettings.keyBindBack.isKeyDown();
        boolean keyD = showKeys && mc.gameSettings.keyBindRight.isKeyDown();
        boolean keyJump = showKeys && mc.gameSettings.keyBindJump.isKeyDown();
        boolean mouseL = showKeys && Mouse.isCreated() && Mouse.isButtonDown(0);
        boolean mouseR = showKeys && Mouse.isCreated() && Mouse.isButtonDown(1);

        // Passe 1 : Toutes les formes SDF (cartes + pastilles) en UNE SEULE activation du shader GLSL
        if (showFps || showPing || showSprint || showKeys) {
            NanoUiRenderer.beginBatch();

            if (showFps) {
                float fx = PureFPSConfig.hudFpsX;
                float fy = PureFPSConfig.hudFpsY;
                NanoUiRenderer.drawRoundedRect(fx, fy, FPS_CARD_W, FPS_CARD_H, 6.0F, CARD_BG, CARD_BORDER, 1.0F);
                NanoUiRenderer.drawCircle(fx + 8.5F, fy + 9.0F, 2.2F, 0xFF34D399);
            }

            if (showPing) {
                float px = PureFPSConfig.hudPingX;
                float py = PureFPSConfig.hudPingY;
                NanoUiRenderer.drawRoundedRect(px, py, PING_CARD_W, PING_CARD_H, 5.5F, CARD_BG, CARD_BORDER, 1.0F);
                int dotCol = (ping < 0) ? 0xFF60A5FA : (ping > 140 ? 0xFFFBBF24 : 0xFF34D399);
                NanoUiRenderer.drawCircle(px + 8.5F, py + PING_CARD_H * 0.5F, 2.2F, dotCol);
            }

            if (showSprint) {
                float sx = PureFPSConfig.hudSprintX;
                float sy = PureFPSConfig.hudSprintY;
                boolean on = PureFPSConfig.sprintToggledActive;
                int border = on ? CARD_BORDER_ACCENT : CARD_BORDER;
                NanoUiRenderer.drawRoundedRect(sx, sy, SPRINT_CARD_W, SPRINT_CARD_H, 5.5F, CARD_BG, border, 1.0F);
                NanoUiRenderer.drawCircle(sx + 8.5F, sy + SPRINT_CARD_H * 0.5F, 2.2F, on ? 0xFF5E6AD2 : 0xFF5E667A);
            }

            if (showKeys) {
                float kx = PureFPSConfig.hudKeystrokesX;
                float ky = PureFPSConfig.hudKeystrokesY;
                drawKeyCapBackground(kx + 27.0F, ky, 24.0F, 22.0F, keyW);
                drawKeyCapBackground(kx, ky + 24.0F, 25.0F, 22.0F, keyA);
                drawKeyCapBackground(kx + 27.0F, ky + 24.0F, 24.0F, 22.0F, keyS);
                drawKeyCapBackground(kx + 53.0F, ky + 24.0F, 25.0F, 22.0F, keyD);
                drawKeyCapBackground(kx, ky + 48.0F, 38.0F, 18.0F, mouseL);
                drawKeyCapBackground(kx + 40.0F, ky + 48.0F, 38.0F, 18.0F, mouseR);
                NanoUiRenderer.drawRoundedRect(kx, ky + 68.0F, 78.0F, 15.0F, 4.5F, keyJump ? CARD_BG_ACTIVE : CARD_BG, CARD_BORDER, 1.0F);
                NanoUiRenderer.drawRoundedRect(kx + 20.0F, ky + 74.5F, 38.0F, 2.0F, 1.0F, keyJump ? 0xFFFFFFFF : 0xBBECEFF6, 0, 0.0F);
            }

            NanoUiRenderer.endBatch();
        }

        // Passe 2 : Textes regroupés par atlas (1 draw pour body + 1 draw pour small)
        if (smooth) {
            if (showFps) {
                float fx = PureFPSConfig.hudFpsX;
                float fy = PureFPSConfig.hudFpsY;
                SmoothFontRenderer bodyF = SmoothFontRenderer.body();
                bodyF.beginBatch();
                bodyF.drawCentered(cachedFpsText, fx + FPS_CARD_W * 0.5F + 3.0F, fy + 4.5F, 0xFFECEFF6, true);
                bodyF.endBatch();
            }

            if (showFps || showPing || showSprint || showKeys) {
                SmoothFontRenderer smallF = SmoothFontRenderer.small();
                smallF.beginBatch();
                if (showFps) {
                    float fx = PureFPSConfig.hudFpsX;
                    float fy = PureFPSConfig.hudFpsY;
                    smallF.drawCentered(cachedCpsText, fx + FPS_CARD_W * 0.5F, fy + 18.0F, 0xFF949CB0, true);
                }
                if (showPing) {
                    float px = PureFPSConfig.hudPingX;
                    float py = PureFPSConfig.hudPingY;
                    int pingCol = (ping > 140) ? 0xFFFBBF24 : 0xFFECEFF6;
                    smallF.drawCentered(cachedPingText, px + PING_CARD_W * 0.5F + 3.0F, py + 5.5F, pingCol, true);
                }
                if (showSprint) {
                    float sx = PureFPSConfig.hudSprintX;
                    float sy = PureFPSConfig.hudSprintY;
                    boolean on = PureFPSConfig.sprintToggledActive;
                    String label = on ? "Sprint  \u2022  Actif" : "Sprint  \u2022  Manuel";
                    smallF.drawCentered(label, sx + SPRINT_CARD_W * 0.5F + 3.0F, sy + 5.5F, on ? 0xFFECEFF6 : 0xFF949CB0, true);
                }
                if (showKeys) {
                    float kx = PureFPSConfig.hudKeystrokesX;
                    float ky = PureFPSConfig.hudKeystrokesY;
                    drawKeyCapLabelSmooth(smallF, kx + 27.0F, ky, 24.0F, 22.0F, "W", keyW);
                    drawKeyCapLabelSmooth(smallF, kx, ky + 24.0F, 25.0F, 22.0F, "A", keyA);
                    drawKeyCapLabelSmooth(smallF, kx + 27.0F, ky + 24.0F, 24.0F, 22.0F, "S", keyS);
                    drawKeyCapLabelSmooth(smallF, kx + 53.0F, ky + 24.0F, 25.0F, 22.0F, "D", keyD);
                    drawKeyCapLabelSmooth(smallF, kx, ky + 48.0F, 38.0F, 18.0F, "LMB", mouseL);
                    drawKeyCapLabelSmooth(smallF, kx + 40.0F, ky + 48.0F, 38.0F, 18.0F, "RMB", mouseR);
                }
                smallF.endBatch();
            }
        } else {
            if (showFps) {
                float fx = PureFPSConfig.hudFpsX;
                float fy = PureFPSConfig.hudFpsY;
                drawVanillaCentered(vanillaFr, cachedFpsText, fx + FPS_CARD_W * 0.5F + 3.0F, fy + 4.5F, 0xFFECEFF6);
                drawVanillaCentered(vanillaFr, cachedCpsText, fx + FPS_CARD_W * 0.5F, fy + 17.5F, 0xFF949CB0);
            }
            if (showPing) {
                float px = PureFPSConfig.hudPingX;
                float py = PureFPSConfig.hudPingY;
                int pingCol = (ping > 140) ? 0xFFFBBF24 : 0xFFECEFF6;
                drawVanillaCentered(vanillaFr, cachedPingText, px + PING_CARD_W * 0.5F + 3.0F, py + 5.0F, pingCol);
            }
            if (showSprint) {
                float sx = PureFPSConfig.hudSprintX;
                float sy = PureFPSConfig.hudSprintY;
                boolean on = PureFPSConfig.sprintToggledActive;
                String label = on ? "Sprint  \u2022  Actif" : "Sprint  \u2022  Manuel";
                drawVanillaCentered(vanillaFr, label, sx + SPRINT_CARD_W * 0.5F + 3.0F, sy + 5.0F, on ? 0xFFECEFF6 : 0xFF949CB0);
            }
            if (showKeys) {
                float kx = PureFPSConfig.hudKeystrokesX;
                float ky = PureFPSConfig.hudKeystrokesY;
                drawKeyCapLabelVanilla(vanillaFr, kx + 27.0F, ky, 24.0F, 22.0F, "W", keyW);
                drawKeyCapLabelVanilla(vanillaFr, kx, ky + 24.0F, 25.0F, 22.0F, "A", keyA);
                drawKeyCapLabelVanilla(vanillaFr, kx + 27.0F, ky + 24.0F, 24.0F, 22.0F, "S", keyS);
                drawKeyCapLabelVanilla(vanillaFr, kx + 53.0F, ky + 24.0F, 25.0F, 22.0F, "D", keyD);
                drawKeyCapLabelVanilla(vanillaFr, kx, ky + 48.0F, 38.0F, 18.0F, "LMB", mouseL);
                drawKeyCapLabelVanilla(vanillaFr, kx + 40.0F, ky + 48.0F, 38.0F, 18.0F, "RMB", mouseR);
            }
        }

        if (showArmor) {
            renderArmorStrip(mc, sr);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawKeyCapBackground(float x, float y, float w, float h, boolean down) {
        int bg = down ? CARD_BG_ACTIVE : CARD_BG;
        int border = down ? 0x55FFFFFF : CARD_BORDER;
        NanoUiRenderer.drawRoundedRect(x, y, w, h, 5.0F, bg, border, 1.0F);
    }

    private static void drawKeyCapLabelSmooth(SmoothFontRenderer smallF, float x, float y, float w, float h, String text, boolean down) {
        smallF.drawCentered(text, x + w * 0.5F, y + (h - 9.0F) * 0.5F + 0.5F, down ? 0xFFFFFFFF : 0xFFECEFF6, true);
    }

    private static void drawKeyCapLabelVanilla(FontRenderer vanillaFr, float x, float y, float w, float h, String text, boolean down) {
        drawVanillaCentered(vanillaFr, text, x + w * 0.5F, y + (h - 9.0F) * 0.5F, down ? 0xFFFFFFFF : 0xFFECEFF6);
    }

    private static void drawVanillaCentered(FontRenderer vanillaFr, String text, float cx, float y, int argb) {
        int w = vanillaFr.getStringWidth(text);
        vanillaFr.drawStringWithShadow(text, cx - (w * 0.5F), y + 0.5F, argb & 0xFFFFFF);
    }

    private static void renderArmorStrip(Minecraft mc, ScaledResolution sr) {
        ItemStack[] armor = mc.thePlayer.inventory.armorInventory;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        RenderItem ri = mc.getRenderItem();
        int baseX = sr.getScaledWidth() / 2 + 12;
        int baseY = sr.getScaledHeight() - 56;

        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        int offset = 0;
        for (int i = 3; i >= 0; i--) {
            ItemStack stack = armor[i];
            if (stack != null) {
                int ix = baseX + offset * 20;
                ri.renderItemAndEffectIntoGUI(stack, ix, baseY);
                ri.renderItemOverlays(mc.fontRendererObj, stack, ix, baseY);
                offset++;
            }
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.popMatrix();
    }

    private static int resolvePing(Minecraft mc) {
        if (mc.isSingleplayer() || mc.thePlayer == null || mc.getNetHandler() == null) {
            return -1;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (info == null) {
            return 0;
        }
        return info.getResponseTime();
    }
}
