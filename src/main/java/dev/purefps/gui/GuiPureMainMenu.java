package dev.purefps.gui;

import dev.purefps.client.audio.MashAudioEngine;
import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.BlackHoleEmergenceRenderer;
import dev.purefps.module.MashModules;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.GuiModList;

import java.io.IOException;

/**
 * Menu principal MashClient épuré :
 * - Espace 3D dégagé autour du trou noir, sans barres lourdes ni texte superflu.
 * - Icônes 100 % vectorielles SDF + logo haute définition mash_mark.png.
 * - Effets sonores tactiles discrets (survol, clic, ouverture des modules).
 */
public class GuiPureMainMenu extends GuiScreen {

    private static boolean bypassOnce = false;

    private static final int ACT_MULTI = 1;
    private static final int ACT_SOLO = 2;
    private static final int ACT_MODULES = 3;
    private static final int ACT_HYPIXEL = 4;
    private static final int ACT_OPTIONS = 5;
    private static final int ACT_MODS = 6;
    private static final int ACT_SOUND = 7;
    private static final int ACT_QUIT = 8;

    private static final float STACK_X = 54.0F;
    private static final float STACK_W = 236.0F;

    private float lastUiAlpha = 1.0F;
    private int lastHoveredAction = -1;

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
        this.lastHoveredAction = -1;
        MashModules.ensureRegistered();
        MashAudioEngine.ensurePrewarmed();
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

        if (interactive) {
            int hov = hitTestDesktopAction(dw, dh, mx, my, this.SessionWidthCached());
            if (hov != this.lastHoveredAction) {
                if (hov > 0) {
                    MashAudioEngine.playHover();
                }
                this.lastHoveredAction = hov;
            }
        }

        NanoUiRenderer.beginDesktopViewport(this.mc);
        try {
            renderMinimalMenu(dw, dh, mx, my, interactive, uiAlpha);
        } finally {
            NanoUiRenderer.endDesktopViewport();
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String getSessionName() {
        return (this.mc != null && this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";
    }

    private float SessionWidthCached() {
        String sessionName = getSessionName();
        return Math.max(96.0F, SmoothFontRenderer.uiBody().getStringWidth(sessionName) + 36.0F);
    }

    private static float computeStackTopY(float dh) {
        // Ensemble total : titre (54px) + 4 boutons (~182px) = ~236px, centré légèrement sous la médiane
        return Math.max(150.0F, dh * 0.50F - 62.0F);
    }

    private void renderMinimalMenu(float dw, float dh, float mx, float my, boolean interactive, float uiAlpha) {
        SmoothFontRenderer heroF = SmoothFontRenderer.uiHero();
        SmoothFontRenderer cardTitleF = SmoothFontRenderer.uiCardTitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.uiBody();
        SmoothFontRenderer smallF = SmoothFontRenderer.uiSmall();
        SmoothFontRenderer boldSmallF = SmoothFontRenderer.uiBoldSmall();

        String sessionName = getSessionName();
        float sessW = Math.max(96.0F, bodyF.getStringWidth(sessionName) + 36.0F);

        // Contrôles flottants haut-droite
        float topY = 22.0F;
        float quitX = dw - 58.0F;
        float soundX = quitX - 38.0F;
        float sessX = soundX - sessW - 10.0F;

        // Pile principale à gauche
        float stackTopY = computeStackTopY(dh);
        float multiY = stackTopY;
        float multiH = 42.0F;

        float soloY = multiY + multiH + 10.0F;
        float soloH = 38.0F;

        float modY = soloY + soloH + 9.0F;
        float modH = 38.0F;

        float subY = modY + modH + 10.0F;
        float subH = 33.0F;
        float subGap = 7.0F;
        float subW = (STACK_W - subGap * 2.0F) / 3.0F;

        boolean multiHov = interactive && inside(mx, my, STACK_X, multiY, STACK_W, multiH);
        boolean soloHov = interactive && inside(mx, my, STACK_X, soloY, STACK_W, soloH);
        boolean modHov = interactive && inside(mx, my, STACK_X, modY, STACK_W, modH);
        boolean hypHov = interactive && inside(mx, my, STACK_X, subY, subW, subH);
        boolean optHov = interactive && inside(mx, my, STACK_X + subW + subGap, subY, subW, subH);
        boolean fmlHov = interactive && inside(mx, my, STACK_X + (subW + subGap) * 2.0F, subY, subW, subH);
        boolean soundHov = interactive && inside(mx, my, soundX, topY, 30.0F, 30.0F);
        boolean quitHov = interactive && inside(mx, my, quitX, topY, 30.0F, 30.0F);

        // =====================================================================
        // PASSE 1 : SURFACES VERRE FUMÉ, BOUTON OR & ICÔNES VECTORIELLES
        // =====================================================================
        NanoUiRenderer.beginBatch();

        // Légère ombre douce derrière la colonne gauche uniquement (n'altère pas le trou noir à droite)
        NanoUiRenderer.drawRoundedGradient(
                STACK_X - 28.0F, stackTopY - 78.0F, STACK_W + 56.0F, 286.0F, 26.0F,
                mulAlpha(0x00020306, uiAlpha), mulAlpha(0x52020306, uiAlpha), 0, 0.0F
        );

        // Logo haut-gauche haute définition
        NanoUiRenderer.drawBrandLogo(this.mc, 44.0F, 37.0F, 26.0F, uiAlpha);

        // Pilule pseudo haut-droite
        NanoUiRenderer.drawRoundedRect(
                sessX, topY, sessW, 30.0F, 15.0F,
                mulAlpha(0xB80A0D14, uiAlpha), mulAlpha(0x1FFFFFFF, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawCircle(sessX + 14.0F, topY + 15.0F, 3.0F, mulAlpha(0xFF34D399, uiAlpha));

        // Bouton Son ON/OFF
        NanoUiRenderer.drawRoundedRect(
                soundX, topY, 30.0F, 30.0F, 15.0F,
                mulAlpha(soundHov ? 0xDC151A26 : 0xB80A0D14, uiAlpha),
                mulAlpha(PureFPSConfig.sfxEnabled ? (soundHov ? 0x66F5B85C : 0x28FFFFFF) : 0x22FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawSoundIcon(
                soundX + 15.0F, topY + 15.0F, 13.0F,
                PureFPSConfig.sfxEnabled,
                mulAlpha(PureFPSConfig.sfxEnabled ? 0xFFECEFF6 : 0xFF6E778C, uiAlpha)
        );

        // Bouton Quitter
        NanoUiRenderer.drawRoundedRect(
                quitX, topY, 30.0F, 30.0F, 15.0F,
                mulAlpha(quitHov ? 0xDD3B1720 : 0xB80A0D14, uiAlpha),
                mulAlpha(quitHov ? 0x88F87171 : 0x24FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawCloseIcon(
                quitX + 15.0F, topY + 15.0F, 12.0F,
                mulAlpha(quitHov ? 0xFFFF9B9B : 0xFFD4DCEB, uiAlpha)
        );

        // 1. Bouton principal : MULTIJOUEUR (Or chaud + icône Play vectorielle)
        NanoUiRenderer.drawShadow(STACK_X, multiY, STACK_W, multiH, 10.0F, 15.0F, mulAlpha(multiHov ? 0x5CF5B85C : 0x36F5B85C, uiAlpha));
        NanoUiRenderer.drawRoundedGradient(
                STACK_X, multiY, STACK_W, multiH, 10.0F,
                mulAlpha(multiHov ? 0xFFFAD38A : 0xFFF2C26D, uiAlpha),
                mulAlpha(multiHov ? 0xFFE4A03D : 0xFFD58E2F, uiAlpha),
                mulAlpha(0xB8FFE9BE, uiAlpha),
                1.15F
        );
        float multiLabelW = cardTitleF.getStringWidth("MULTIJOUEUR");
        float playIconX = STACK_X + (STACK_W - (multiLabelW + 18.0F)) * 0.5F + 4.0F;
        NanoUiRenderer.drawPlayIcon(playIconX, multiY + multiH * 0.5F, 10.5F, mulAlpha(0xFF0A0B0E, uiAlpha));

        // 2. SOLO
        drawGlassButton(STACK_X, soloY, STACK_W, soloH, 9.5F, soloHov, false, uiAlpha);

        // 3. MODULES
        drawGlassButton(STACK_X, modY, STACK_W, modH, 9.5F, modHov, true, uiAlpha);

        // 4. Ligne secondaire épurée : Hypixel · Options · Mods
        drawGlassButton(STACK_X, subY, subW, subH, 8.5F, hypHov, false, uiAlpha);
        drawGlassButton(STACK_X + subW + subGap, subY, subW, subH, 8.5F, optHov, false, uiAlpha);
        drawGlassButton(STACK_X + (subW + subGap) * 2.0F, subY, subW, subH, 8.5F, fmlHov, false, uiAlpha);

        NanoUiRenderer.endBatch();

        // =====================================================================
        // PASSE 2 : TYPOGRAPHIE ÉPURÉE
        // =====================================================================
        heroF.drawStringWithShadow("MASHCLIENT", STACK_X - 1.0F, stackTopY - 52.0F, mulAlpha(0xFFFFFFFF, uiAlpha));

        boldSmallF.beginBatch();
        boldSmallF.drawString("MASHCLIENT", 63.0F, 31.5F, mulAlpha(0xFFF5F3EE, uiAlpha));
        float topBrandW = boldSmallF.getStringWidth("MASHCLIENT");
        boldSmallF.drawString("1.8.9", 63.0F + topBrandW + 8.0F, 31.5F, mulAlpha(0xFF7D869C, uiAlpha));
        boldSmallF.endBatch();

        cardTitleF.beginBatch();
        cardTitleF.drawString(
                "MULTIJOUEUR",
                playIconX + 13.0F,
                multiY + (multiH - cardTitleF.getHeight()) * 0.5F + 0.5F,
                mulAlpha(0xFF0A0B0E, uiAlpha)
        );
        cardTitleF.endBatch();

        bodyF.beginBatch();
        bodyF.drawString(sessionName, sessX + 24.0F, topY + 9.5F, mulAlpha(0xFFECEFF6, uiAlpha));
        bodyF.drawCentered("SOLO", STACK_X + STACK_W * 0.5F, soloY + (soloH - bodyF.getHeight()) * 0.5F + 0.5F, mulAlpha(soloHov ? 0xFFFFFFFF : 0xFFE6EAF4, uiAlpha), false);
        bodyF.drawCentered("MODULES", STACK_X + STACK_W * 0.5F, modY + (modH - bodyF.getHeight()) * 0.5F + 0.5F, mulAlpha(modHov ? 0xFFFFDF9E : 0xFFF2C879, uiAlpha), false);
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString("RSHIFT", STACK_X + STACK_W - smallF.getStringWidth("RSHIFT") - 12.0F, modY + (modH - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(0xFF7D869C, uiAlpha));
        smallF.drawCentered("Hypixel", STACK_X + subW * 0.5F, subY + (subH - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(hypHov ? 0xFFFFDF9E : 0xFFB8C1D4, uiAlpha), false);
        smallF.drawCentered("Options", STACK_X + subW + subGap + subW * 0.5F, subY + (subH - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(optHov ? 0xFFFFFFFF : 0xFFB8C1D4, uiAlpha), false);
        smallF.drawCentered("Mods", STACK_X + (subW + subGap) * 2.0F + subW * 0.5F, subY + (subH - smallF.getHeight()) * 0.5F + 0.5F, mulAlpha(fmlHov ? 0xFFFFFFFF : 0xFFB8C1D4, uiAlpha), false);
        smallF.endBatch();
    }

    private static void drawGlassButton(float x, float y, float w, float h, float r, boolean hov, boolean goldTint, float uiAlpha) {
        int top = hov ? (goldTint ? 0xE61D1A18 : 0xE6161B28) : 0xC80B0E16;
        int bot = hov ? (goldTint ? 0xE6151314 : 0xE610141E) : 0xC8080A10;
        int brd = hov ? (goldTint ? 0x70F5B85C : 0x44FFFFFF) : (goldTint ? 0x34F5B85C : 0x20FFFFFF);
        NanoUiRenderer.drawRoundedGradient(x, y, w, h, r, mulAlpha(top, uiAlpha), mulAlpha(bot, uiAlpha), mulAlpha(brd, uiAlpha), 1.0F);
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

        int act = hitTestDesktopAction(dw, dh, mx, my, this.SessionWidthCached());
        if (act < 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        switch (act) {
            case ACT_MULTI:
                MashAudioEngine.playJoinAction();
                this.mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case ACT_SOLO:
                MashAudioEngine.playJoinAction();
                this.mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case ACT_MODULES:
                MashAudioEngine.playMenuOpen();
                this.mc.displayGuiScreen(new GuiMashClientMenu(this));
                break;
            case ACT_HYPIXEL:
                MashAudioEngine.playJoinAction();
                ServerData hypixel = new ServerData("Hypixel", "mc.hypixel.net", false);
                this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, hypixel));
                break;
            case ACT_OPTIONS:
                MashAudioEngine.playClick();
                this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
                break;
            case ACT_MODS:
                MashAudioEngine.playClick();
                this.mc.displayGuiScreen(new GuiModList(this));
                break;
            case ACT_SOUND:
                boolean next = !PureFPSConfig.sfxEnabled;
                PureFPSConfig.sfxEnabled = next;
                PureFPSConfig.save();
                if (next) {
                    MashAudioEngine.playToggle(true);
                }
                break;
            case ACT_QUIT:
                MashAudioEngine.playClick();
                this.mc.shutdown();
                break;
            default:
                break;
        }
    }

    private static int hitTestDesktopAction(float dw, float dh, float mx, float my, float sessW) {
        float topY = 22.0F;
        float quitX = dw - 58.0F;
        float soundX = quitX - 38.0F;
        if (inside(mx, my, quitX, topY, 30.0F, 30.0F)) return ACT_QUIT;
        if (inside(mx, my, soundX, topY, 30.0F, 30.0F)) return ACT_SOUND;

        float stackTopY = computeStackTopY(dh);
        float multiY = stackTopY;
        float multiH = 42.0F;
        if (inside(mx, my, STACK_X, multiY, STACK_W, multiH)) return ACT_MULTI;

        float soloY = multiY + multiH + 10.0F;
        float soloH = 38.0F;
        if (inside(mx, my, STACK_X, soloY, STACK_W, soloH)) return ACT_SOLO;

        float modY = soloY + soloH + 9.0F;
        float modH = 38.0F;
        if (inside(mx, my, STACK_X, modY, STACK_W, modH)) return ACT_MODULES;

        float subY = modY + modH + 10.0F;
        float subH = 33.0F;
        float subGap = 7.0F;
        float subW = (STACK_W - subGap * 2.0F) / 3.0F;
        if (inside(mx, my, STACK_X, subY, subW, subH)) return ACT_HYPIXEL;
        if (inside(mx, my, STACK_X + subW + subGap, subY, subW, subH)) return ACT_OPTIONS;
        if (inside(mx, my, STACK_X + (subW + subGap) * 2.0F, subY, subW, subH)) return ACT_MODS;

        return -1;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= (x + w) && my >= y && my <= (y + h);
    }

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }
}
