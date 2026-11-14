package dev.purefps.gui;

import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.BlackHoleEmergenceRenderer;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.GuiModList;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/**
 * Menu Principal MashClient 4.1 — Édition "Singularity" :
 * - Rendu dans un viewport Bureau indépendant du GuiScale (~1280x720) fidèle à l'esthétique du Launcher MashClient.exe.
 * - Laisse le Trou Noir Gargantua 3D (une seule couronne équatoriale relativiste, espace noir pur) entièrement dégagé
 *   sur la droite, avec barre supérieure en verre fumé, bloc éditorial bas-gauche et dock inférieur avec CTA doré.
 */
public class GuiPureMainMenu extends GuiScreen {

    private static boolean bypassOnce = false;

    private static final int ACT_HYPIXEL = 1;
    private static final int ACT_MULTI = 2;
    private static final int ACT_SOLO = 3;
    private static final int ACT_STUDIO = 4;
    private static final int ACT_OPTIONS = 5;
    private static final int ACT_MODS = 6;
    private static final int ACT_VANILLA = 7;
    private static final int ACT_QUIT = 8;
    private static final int ACT_PROF_FPS = 9;
    private static final int ACT_PROF_BAL = 10;
    private static final int ACT_PROF_QUAL = 11;
    private static final int ACT_BORDERLESS = 12;

    private String gpuBrand = "GPU OpenGL";
    private float lastUiAlpha = 1.0F;

    public static boolean consumeBypassFlag() {
        if (bypassOnce) {
            bypassOnce = false;
            return true;
        }
        return false;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        MashModules.ensureRegistered();
        try {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (renderer != null && !renderer.isEmpty()) {
                gpuBrand = renderer.replace("/PCIe/SSE2", "").replace("(TM)", "").replace("(R)", "").trim();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float uiAlpha = BlackHoleEmergenceRenderer.renderMenuBackground(this.mc, this.width, this.height, mouseX, mouseY);
        this.lastUiAlpha = uiAlpha;
        if (uiAlpha <= 0.015F) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float mx = NanoUiRenderer.desktopMouseX(this.mc);
        float my = NanoUiRenderer.desktopMouseY(this.mc);
        boolean interactive = uiAlpha > 0.45F;

        NanoUiRenderer.beginDesktopViewport(this.mc);
        try {
            renderDesktopMainMenu(dw, dh, mx, my, interactive, uiAlpha);
        } finally {
            NanoUiRenderer.endDesktopViewport();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderDesktopMainMenu(float dw, float dh, float mx, float my, boolean interactive, float uiAlpha) {
        SmoothFontRenderer heroF = SmoothFontRenderer.uiHero();
        SmoothFontRenderer cardTitleF = SmoothFontRenderer.uiCardTitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.uiBody();
        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        SmoothFontRenderer boldSmallF = SmoothFontRenderer.uiBoldSmall();

        PureFPSProfile activeProf = PureFPSConfig.activeProfile;
        boolean brdOn = PureFPSConfig.borderlessFullscreen;
        int activeMods = MashModules.getActiveCount();
        int fps = Minecraft.getDebugFPS();
        String sessionName = (this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";

        // Géométrie barre supérieure (52 px)
        float topH = 52.0F;
        float navW = 472.0F;
        float navH = 34.0F;
        float navX = (dw - navW) * 0.5F;
        float navY = 9.0F;

        // Géométrie bloc éditorial bas-gauche
        float heroX = 44.0F;
        float dockH = 66.0F;
        float dockY = dh - dockH;
        float profBoxY = dockY - 62.0F;
        float profBoxW = 306.0F;
        float profBoxH = 38.0F;
        float heroBaseY = profBoxY - 128.0F;

        // Géométrie dock inférieur
        float segX = 32.0F;
        float segY = dockY + 15.0F;
        float segW = 312.0F;
        float segH = 36.0F;

        float brdPillX = segX + segW + 12.0F;
        float brdPillW = 166.0F;

        float ctaW = 226.0F;
        float ctaH = 42.0F;
        float ctaX = dw - ctaW - 32.0F;
        float ctaY = dockY + 12.0F;

        // Contrôles haut-droite
        float quitX = dw - 52.0F;
        float vanX = quitX - 68.0F;
        float modsX = vanX - 64.0F;
        float sessW = Math.max(128.0F, bodyF.getStringWidth(sessionName) + 82.0F);
        float sessX = modsX - sessW - 10.0F;

        // =====================================================================
        // PASSE 1 : TOUTES LES SURFACES SDF EN VERRE FUMÉ & OR CHAUD (1 DRAW)
        // =====================================================================
        NanoUiRenderer.beginBatch();

        // Légère vignette bas-gauche pour ancrer la lisibilité du titre éditorial sans toucher au Trou Noir
        NanoUiRenderer.drawRoundedGradient(
                -10.0F, heroBaseY - 36.0F, 520.0F, dockY - heroBaseY + 46.0F, 24.0F,
                mulAlpha(0x00030408, uiAlpha), mulAlpha(0x68030408, uiAlpha), 0, 0.0F
        );

        // 1. Barre supérieure en verre fumé
        NanoUiRenderer.drawRoundedRect(
                -2.0F, -2.0F, dw + 4.0F, topH + 2.0F, 0.0F,
                mulAlpha(0xBA05060A, uiAlpha), mulAlpha(0x16FFFFFF, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawSingularityEmblem(34.0F, 26.0F, 18.0F, uiAlpha);

        // Badge SINGULARITY 4.1
        String brandText = "M A S H C L I E N T";
        float brandW = boldSmallF.getStringWidth(brandText);
        float singPillX = 52.0F + brandW + 12.0F;
        NanoUiRenderer.drawRoundedRect(
                singPillX, 15.0F, 112.0F, 22.0F, 5.0F,
                mulAlpha(0x1FF5B85C, uiAlpha), mulAlpha(0x52F5B85C, uiAlpha), 1.0F
        );

        // Pilule centrale de navigation (Accueil | Multijoueur | Mode Solo | Studio 200 | Options)
        NanoUiRenderer.drawRoundedRect(
                navX, navY, navW, navH, 17.0F,
                mulAlpha(0xCC0A0D14, uiAlpha), mulAlpha(0x22FFFFFF, uiAlpha), 1.0F
        );
        float[] navItemWidths = {78.0F, 98.0F, 88.0F, 122.0F, 74.0F};
        float curNavX = navX + 6.0F;
        for (int i = 0; i < navItemWidths.length; i++) {
            float iw = navItemWidths[i];
            boolean active = (i == 0);
            boolean hov = interactive && inside(mx, my, curNavX, navY + 4.0F, iw, navH - 8.0F);
            if (active || hov) {
                NanoUiRenderer.drawRoundedRect(
                        curNavX, navY + 4.0F, iw, navH - 8.0F, 13.0F,
                        mulAlpha(active ? 0x24FFFFFF : 0x14FFFFFF, uiAlpha),
                        mulAlpha(active ? 0x2EFFFFFF : 0x00000000, uiAlpha),
                        active ? 1.0F : 0.0F
                );
            }
            curNavX += iw;
        }

        // Pilule Session + FPS haut-droite
        NanoUiRenderer.drawRoundedRect(
                sessX, 11.0F, sessW, 30.0F, 15.0F,
                mulAlpha(0xCC0A0D14, uiAlpha), mulAlpha(0x24FFFFFF, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawCircle(sessX + 14.0F, 26.0F, 3.2F, mulAlpha(0xFF34D399, uiAlpha));

        // Boutons Mods, Vanilla, Quitter
        drawSubtlePill(modsX, 11.0F, 56.0F, 30.0F, 15.0F, interactive && inside(mx, my, modsX, 11.0F, 56.0F, 30.0F), false, uiAlpha);
        drawSubtlePill(vanX, 11.0F, 60.0F, 30.0F, 15.0F, interactive && inside(mx, my, vanX, 11.0F, 60.0F, 30.0F), false, uiAlpha);
        drawSubtlePill(quitX, 11.0F, 30.0F, 30.0F, 15.0F, interactive && inside(mx, my, quitX, 11.0F, 30.0F, 30.0F), true, uiAlpha);

        // 2. Sélecteur de Profils Moteur (• Turbo FPS | • Équilibré | • Qualité)
        NanoUiRenderer.drawRoundedRect(
                heroX, profBoxY, profBoxW, profBoxH, 9.0F,
                mulAlpha(0xD4090B11, uiAlpha), mulAlpha(0x22FFFFFF, uiAlpha), 1.0F
        );
        PureFPSProfile[] profs = {PureFPSProfile.FPS, PureFPSProfile.BALANCED, PureFPSProfile.QUALITY};
        float segProfW = (profBoxW - 10.0F) / 3.0F;
        for (int i = 0; i < 3; i++) {
            float px = heroX + 5.0F + i * segProfW;
            float py = profBoxY + 4.5F;
            boolean sel = activeProf == profs[i];
            boolean hov = interactive && inside(mx, my, px, py, segProfW, profBoxH - 9.0F);
            if (sel || hov) {
                NanoUiRenderer.drawRoundedRect(
                        px, py, segProfW, profBoxH - 9.0F, 6.5F,
                        mulAlpha(sel ? 0x2EF5B85C : 0x14FFFFFF, uiAlpha),
                        mulAlpha(sel ? 0x70EAB354 : 0x00000000, uiAlpha),
                        sel ? 1.0F : 0.0F
                );
            }
        }

        // 3. Dock inférieur en verre fumé
        NanoUiRenderer.drawRoundedRect(
                -2.0F, dockY, dw + 4.0F, dockH + 4.0F, 0.0F,
                mulAlpha(0xC805060A, uiAlpha), mulAlpha(0x18FFFFFF, uiAlpha), 1.0F
        );

        // Groupe d'actions rapides bas-gauche (Multijoueur | Mode Solo | Studio RSHIFT)
        NanoUiRenderer.drawRoundedRect(
                segX, segY, segW, segH, 9.0F,
                mulAlpha(0xD80A0D14, uiAlpha), mulAlpha(0x22FFFFFF, uiAlpha), 1.0F
        );
        float subSegW = (segW - 8.0F) / 3.0F;
        for (int i = 0; i < 3; i++) {
            float sx = segX + 4.0F + i * subSegW;
            boolean hov = interactive && inside(mx, my, sx, segY + 4.0F, subSegW, segH - 8.0F);
            boolean accentStudio = (i == 2);
            if (hov || accentStudio) {
                NanoUiRenderer.drawRoundedRect(
                        sx, segY + 4.0F, subSegW, segH - 8.0F, 6.5F,
                        mulAlpha(hov ? 0x28FFFFFF : 0x16FFFFFF, uiAlpha),
                        mulAlpha(hov ? 0x44F5B85C : 0x20FFFFFF, uiAlpha),
                        1.0F
                );
            }
        }

        // Pilule Borderless F11
        boolean brdHov = interactive && inside(mx, my, brdPillX, segY, brdPillW, segH);
        NanoUiRenderer.drawRoundedRect(
                brdPillX, segY, brdPillW, segH, 9.0F,
                mulAlpha(brdOn ? 0x1E34D399 : (brdHov ? 0xE0111520 : 0xD80A0D14), uiAlpha),
                mulAlpha(brdOn ? 0x5534D399 : (brdHov ? 0x38FFFFFF : 0x22FFFFFF), uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawCircle(brdPillX + 15.0F, segY + segH * 0.5F, 3.0F, mulAlpha(brdOn ? 0xFF34D399 : 0xFF64748B, uiAlpha));

        // Bouton principal CTA Or Chaud (▶ JOUER SUR HYPIXEL)
        boolean ctaHov = interactive && inside(mx, my, ctaX, ctaY, ctaW, ctaH);
        NanoUiRenderer.drawShadow(ctaX, ctaY, ctaW, ctaH, 10.0F, 16.0F, mulAlpha(ctaHov ? 0x66F5B85C : 0x40F5B85C, uiAlpha));
        NanoUiRenderer.drawRoundedGradient(
                ctaX, ctaY, ctaW, ctaH, 10.0F,
                mulAlpha(ctaHov ? 0xFFF9D084 : 0xFFF3C26E, uiAlpha),
                mulAlpha(ctaHov ? 0xFFE39E3B : 0xFFD68E2F, uiAlpha),
                mulAlpha(0xBBFFE9BE, uiAlpha),
                1.2F
        );

        NanoUiRenderer.endBatch();

        // =====================================================================
        // PASSE 2 : TYPOGRAPHIE HD BUREAU
        // =====================================================================
        heroF.drawStringWithShadow("MASHCLIENT", heroX - 1.0F, heroBaseY + 18.0F, mulAlpha(0xFFFFFFFF, uiAlpha));

        boldSmallF.beginBatch();
        boldSmallF.drawString(brandText, 52.0F, 20.5F, mulAlpha(0xFFF7F4EC, uiAlpha));
        boldSmallF.drawCentered("SINGULARITY 4.1", singPillX + 56.0F, 20.5F, mulAlpha(0xFFEAB354, uiAlpha), false);
        boldSmallF.drawString(
                "FORGE 1.8.9   \u2022   VAO GL30 + MULTIDRAW   \u2022   RSHIFT STUDIO",
                heroX, heroBaseY, mulAlpha(0xFFD99B43, uiAlpha)
        );
        boldSmallF.endBatch();

        cardTitleF.beginBatch();
        cardTitleF.drawCentered(
                "\u25B6   REJOINDRE HYPIXEL",
                ctaX + ctaW * 0.5F,
                ctaY + (ctaH - cardTitleF.getHeight()) * 0.5F + 0.5F,
                mulAlpha(0xFF0A0B0E, uiAlpha),
                false
        );
        cardTitleF.endBatch();

        bodyF.beginBatch();
        // Onglets de la barre centrale
        String[] navLabels = {"Accueil", "Multijoueur", "Mode Solo", "Studio " + activeMods + "/200", "Options"};
        curNavX = navX + 6.0F;
        for (int i = 0; i < navLabels.length; i++) {
            float iw = navItemWidths[i];
            boolean hov = interactive && inside(mx, my, curNavX, navY + 4.0F, iw, navH - 8.0F);
            int col = (i == 0 || hov) ? 0xFFF7F4EC : (i == 3 ? 0xFFF3C677 : 0xFFA2AABF);
            bodyF.drawCentered(navLabels[i], curNavX + iw * 0.5F, navY + 10.5F, mulAlpha(col, uiAlpha), false);
            curNavX += iw;
        }

        // Session & FPS
        bodyF.drawString(sessionName, sessX + 24.0F, 19.5F, mulAlpha(0xFFECEFF6, uiAlpha));
        String fpsStr = fps + " FPS";
        bodyF.drawString(fpsStr, sessX + sessW - bodyF.getStringWidth(fpsStr) - 12.0F, 19.5F, mulAlpha(0xFF6EE7B7, uiAlpha));

        // Sous-texte éditorial
        bodyF.drawString(
                "Pipeline OpenGL sans blocage GPU, 200 modules PvP & HUD magnétiques",
                heroX, heroBaseY + 62.0F, mulAlpha(0xFFA6AEBF, uiAlpha)
        );
        bodyF.drawString(
                "et transition directe depuis l'horizon des événements vers Minecraft.",
                heroX, heroBaseY + 81.0F, mulAlpha(0xFFA6AEBF, uiAlpha)
        );

        // Segments Profils
        String[] profNames = {"\u2022  Turbo FPS", "\u2022  Équilibré", "\u2022  Qualité"};
        for (int i = 0; i < 3; i++) {
            float px = heroX + 5.0F + i * segProfW;
            boolean sel = activeProf == profs[i];
            boolean hov = interactive && inside(mx, my, px, profBoxY + 4.5F, segProfW, profBoxH - 9.0F);
            int col = sel ? 0xFFFFDF9E : (hov ? 0xFFECEFF6 : 0xFF9AA3B8);
            bodyF.drawCentered(profNames[i], px + segProfW * 0.5F, profBoxY + 12.5F, mulAlpha(col, uiAlpha), false);
        }

        // Segments Dock bas-gauche
        String[] dockActions = {"Multijoueur", "Mode Solo", "Studio 200 Mods"};
        for (int i = 0; i < 3; i++) {
            float sx = segX + 4.0F + i * subSegW;
            boolean hov = interactive && inside(mx, my, sx, segY + 4.0F, subSegW, segH - 8.0F);
            int col = hov ? 0xFFFFFFFF : (i == 2 ? 0xFFF5C878 : 0xFFD5DCEB);
            bodyF.drawCentered(dockActions[i], sx + subSegW * 0.5F, segY + 11.5F, mulAlpha(col, uiAlpha), false);
        }

        bodyF.drawString(
                brdOn ? "Borderless F11 : ON" : "Borderless F11 : OFF",
                brdPillX + 26.0F, segY + 11.5F,
                mulAlpha(brdOn ? 0xFF6EE7B7 : 0xFFA2AABF, uiAlpha)
        );
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawCentered("Mods", modsX + 28.0F, 20.0F, mulAlpha(0xFFB4BCD0, uiAlpha), false);
        smallF.drawCentered("Vanilla", vanX + 30.0F, 20.0F, mulAlpha(0xFFB4BCD0, uiAlpha), false);
        smallF.drawCentered("\u00D7", quitX + 15.0F, 19.5F, mulAlpha(0xFFF87171, uiAlpha), false);

        // Télémétrie centrale du dock inférieur
        float teleLeft = brdPillX + brdPillW + 18.0F;
        float teleAvail = ctaX - teleLeft - 18.0F;
        if (teleAvail > 120.0F) {
            String tele = smallF.trimToWidth(
                    gpuBrand + "   \u2022   " + activeMods + "/200 modules actifs   \u2022   RSHIFT Studio",
                    teleAvail
            );
            smallF.drawString(tele, teleLeft, dockY + 27.5F, mulAlpha(0xFF727B90, uiAlpha));
        }
        smallF.endBatch();
    }

    private static void drawSubtlePill(float x, float y, float w, float h, float r, boolean hov, boolean danger, float uiAlpha) {
        int fill = danger ? (hov ? 0xDD3B1720 : 0xCC130E13) : (hov ? 0xE6181D2B : 0xCC0A0D14);
        int brd = danger ? (hov ? 0x88F87171 : 0x34F87171) : (hov ? 0x3CFFFFFF : 0x22FFFFFF);
        NanoUiRenderer.drawRoundedRect(x, y, w, h, r, mulAlpha(fill, uiAlpha), mulAlpha(brd, uiAlpha), 1.0F);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0 || this.lastUiAlpha < 0.35F) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        float dw = NanoUiRenderer.desktopWidth(this.mc);
        float dh = NanoUiRenderer.desktopHeight(this.mc);
        float mx = NanoUiRenderer.desktopEventMouseX(this.mc);
        float my = NanoUiRenderer.desktopEventMouseY(this.mc);

        int act = hitTestDesktopAction(dw, dh, mx, my);
        if (act < 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
        switch (act) {
            case ACT_HYPIXEL:
                ServerData hypixel = new ServerData("Hypixel", "mc.hypixel.net", false);
                this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, hypixel));
                break;
            case ACT_MULTI:
                this.mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case ACT_SOLO:
                this.mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case ACT_STUDIO:
                this.mc.displayGuiScreen(new GuiMashClientMenu(this));
                break;
            case ACT_OPTIONS:
                this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
                break;
            case ACT_MODS:
                this.mc.displayGuiScreen(new GuiModList(this));
                break;
            case ACT_VANILLA:
                bypassOnce = true;
                this.mc.displayGuiScreen(new GuiMainMenu());
                break;
            case ACT_QUIT:
                this.mc.shutdown();
                break;
            case ACT_PROF_FPS:
                PureFPSConfig.applyProfile(PureFPSProfile.FPS, true);
                MashModules.syncFromPureConfig();
                break;
            case ACT_PROF_BAL:
                PureFPSConfig.applyProfile(PureFPSProfile.BALANCED, true);
                MashModules.syncFromPureConfig();
                break;
            case ACT_PROF_QUAL:
                PureFPSConfig.applyProfile(PureFPSProfile.QUALITY, true);
                MashModules.syncFromPureConfig();
                break;
            case ACT_BORDERLESS:
                PureFPSConfig.borderlessFullscreen = !PureFPSConfig.borderlessFullscreen;
                MashModules.MOD_BORDERLESS.setEnabledSilent(PureFPSConfig.borderlessFullscreen);
                PureFPSConfig.markDirty();
                break;
            default:
                break;
        }
    }

    private static int hitTestDesktopAction(float dw, float dh, float mx, float my) {
        // 1. Barre de navigation supérieure
        float navW = 472.0F;
        float navX = (dw - navW) * 0.5F;
        float navY = 9.0F;
        float[] navItemWidths = {78.0F, 98.0F, 88.0F, 122.0F, 74.0F};
        int[] navActions = {-1, ACT_MULTI, ACT_SOLO, ACT_STUDIO, ACT_OPTIONS};
        float curNavX = navX + 6.0F;
        for (int i = 0; i < navItemWidths.length; i++) {
            float iw = navItemWidths[i];
            if (inside(mx, my, curNavX, navY + 4.0F, iw, 26.0F)) {
                return navActions[i];
            }
            curNavX += iw;
        }

        // 2. Contrôles haut-droite
        float quitX = dw - 52.0F;
        float vanX = quitX - 68.0F;
        float modsX = vanX - 64.0F;
        if (inside(mx, my, quitX, 11.0F, 30.0F, 30.0F)) return ACT_QUIT;
        if (inside(mx, my, vanX, 11.0F, 60.0F, 30.0F)) return ACT_VANILLA;
        if (inside(mx, my, modsX, 11.0F, 56.0F, 30.0F)) return ACT_MODS;

        // 3. Sélecteur de Profils Moteur
        float dockY = dh - 66.0F;
        float profBoxY = dockY - 62.0F;
        float profBoxW = 306.0F;
        float segProfW = (profBoxW - 10.0F) / 3.0F;
        int[] profActs = {ACT_PROF_FPS, ACT_PROF_BAL, ACT_PROF_QUAL};
        for (int i = 0; i < 3; i++) {
            float px = 44.0F + 5.0F + i * segProfW;
            if (inside(mx, my, px, profBoxY + 4.5F, segProfW, 29.0F)) {
                return profActs[i];
            }
        }

        // 4. Dock inférieur
        float segX = 32.0F;
        float segY = dockY + 15.0F;
        float segW = 312.0F;
        float subSegW = (segW - 8.0F) / 3.0F;
        int[] dockActs = {ACT_MULTI, ACT_SOLO, ACT_STUDIO};
        for (int i = 0; i < 3; i++) {
            float sx = segX + 4.0F + i * subSegW;
            if (inside(mx, my, sx, segY + 4.0F, subSegW, 28.0F)) {
                return dockActs[i];
            }
        }

        float brdPillX = segX + segW + 12.0F;
        if (inside(mx, my, brdPillX, segY, 166.0F, 36.0F)) return ACT_BORDERLESS;

        float ctaW = 226.0F;
        float ctaX = dw - ctaW - 32.0F;
        if (inside(mx, my, ctaX, dockY + 12.0F, ctaW, 42.0F)) return ACT_HYPIXEL;

        return -1;
    }

    private static int mulAlpha(int argb, float alphaFactor) {
        int baseA = (argb >>> 24) & 0xFF;
        int scaledA = Math.max(0, Math.min(255, Math.round((float) baseA * alphaFactor)));
        return (scaledA << 24) | (argb & 0x00FFFFFF);
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
