package dev.purefps.gui;

import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
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
 * Menu Principal PureClient 3.5 (Standard Lunar Client / OneClient / Linear Dark Glass) :
 * - Zéro bouton Vanilla : rendu intégral par shader SDF anti-aliasé (NanoUiRenderer) + typographie Segoe UI HD (SmoothFontRenderer).
 * - Fluide à 120 FPS (sans le flou panoramique 6-passes énergivore de Vanilla).
 */
public class GuiPureMainMenu extends GuiScreen {

    private static boolean bypassOnce = false;

    private static final int BTN_HYPIXEL = 1;
    private static final int BTN_MULTI = 2;
    private static final int BTN_SOLO = 3;
    private static final int BTN_CLIENT = 4;
    private static final int BTN_OPTIONS = 5;
    private static final int BTN_MODS = 6;
    private static final int BTN_QUIT = 7;
    private static final int BTN_VANILLA = 8;

    private String gpuBrand = "GPU OpenGL";

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
        try {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (renderer != null && !renderer.isEmpty()) {
                gpuBrand = renderer.replace("/PCIe/SSE2", "").trim();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        NanoUiRenderer.updateScaleFactor(this.mc);

        // Toile obsidienne profonde + halo ambiant subtil derrière la carte centrale
        this.drawGradientRect(0, 0, this.width, this.height, 0xFF07090F, 0xFF0E1322);

        float cx = this.width * 0.5F;
        float cy = this.height * 0.5F;
        float cardW = 268.0F;
        float cardH = 232.0F;
        float px = cx - cardW * 0.5F;
        float py = cy - cardH * 0.5F + 6.0F;

        // Halo ambiant Indigo + ombre portée profonde sous la carte héroïque
        NanoUiRenderer.drawShadow(px + 10.0F, py - 8.0F, cardW - 20.0F, 80.0F, 24.0F, 30.0F, 0x345E6AD2);
        NanoUiRenderer.drawShadow(px, py, cardW, cardH, 12.0F, 18.0F, 0x99000000);

        // Barre supérieure vitrée (30 px)
        NanoUiRenderer.drawRoundedRect(-4.0F, -4.0F, this.width + 8.0F, 33.0F, 0.0F, 0xCC0B0E16, 0x16FFFFFF, 1.0F);

        SmoothFontRenderer titleF = SmoothFontRenderer.title();
        SmoothFontRenderer subF = SmoothFontRenderer.subtitle();
        SmoothFontRenderer bodyF = SmoothFontRenderer.body();
        SmoothFontRenderer smallF = SmoothFontRenderer.small();

        // Badge joueur & profil actif en haut à gauche
        String sessionName = (this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";
        PureFPSProfile prof = PureFPSConfig.activeProfile;
        int profColor = profileAccentArgb(prof);

        NanoUiRenderer.drawRoundedRect(10.0F, 5.0F, 196.0F, 19.0F, 9.5F, 0xCC131724, 0x20FFFFFF, 1.0F);
        NanoUiRenderer.drawCircle(19.5F, 14.5F, 3.0F, 0xFF34D399);
        smallF.drawString(sessionName + "  \u2022  Profil " + prof.getDisplayName(), 27.0F, 10.5F, 0xFFECEFF6);

        // Contrôles discrets en haut à droite
        drawActionButton(this.width - 154.0F, 5.0F, 82.0F, 19.0F, "Menu Vanilla", mouseX, mouseY, ButtonKind.SUBTLE);
        drawActionButton(this.width - 66.0F, 5.0F, 56.0F, 19.0F, "Quitter", mouseX, mouseY, ButtonKind.DANGER);

        // Carte centrale PureClient 3.5
        NanoUiRenderer.drawRoundedGradient(px, py, cardW, cardH, 12.0F, 0xF0111520, 0xF00C0F18, 0x24FFFFFF, 1.0F);

        // En-tête de marque : PURECLIENT + badge pilule 3.5
        String brand = "PURECLIENT";
        float brandW = titleF.getStringWidth(brand);
        float badgeW = 28.0F;
        float groupX = cx - (brandW + 6.0F + badgeW) * 0.5F;
        titleF.drawStringWithShadow(brand, groupX, py + 15.0F, 0xFFECEFF6);

        NanoUiRenderer.drawRoundedRect(groupX + brandW + 6.0F, py + 17.5F, badgeW, 13.0F, 6.5F, 0x335E6AD2, 0x775E6AD2, 1.0F);
        smallF.drawCentered("v3.5", groupX + brandW + 6.0F + badgeW * 0.5F, py + 20.0F, 0xFF9BA6FF, false);

        smallF.drawCentered("Moteur OpenGL 3.0 VAO MultiDraw  \u2022  RSHIFT en jeu", cx, py + 36.0F, 0xFF8A93A8, false);

        // Séparateur hairline
        NanoUiRenderer.drawRoundedRect(px + 18.0F, py + 50.0F, cardW - 36.0F, 0.8F, 0.0F, 0x16FFFFFF, 0, 0.0F);

        // Boutons principaux empilés avec rythme 8px
        float bx = cx - 116.0F;
        float bw = 232.0F;
        float by = py + 60.0F;

        drawActionButton(bx, by, bw, 27.0F, "Rejoindre Hypixel", mouseX, mouseY, ButtonKind.PRIMARY);
        drawActionButton(bx, by + 33.0F, bw, 24.0F, "Multijoueur", mouseX, mouseY, ButtonKind.SECONDARY);
        drawActionButton(bx, by + 62.0F, bw, 24.0F, "Mode Solo", mouseX, mouseY, ButtonKind.SECONDARY);
        drawActionButton(bx, by + 91.0F, bw, 25.0F, "Tableau de Bord PureClient", mouseX, mouseY, ButtonKind.ACCENT_OUTLINE);

        float halfW = 113.0F;
        drawActionButton(bx, by + 122.0F, halfW, 22.0F, "Options MC", mouseX, mouseY, ButtonKind.SUBTLE);
        drawActionButton(bx + 119.0F, by + 122.0F, halfW, 22.0F, "Mods Forge", mouseX, mouseY, ButtonKind.SUBTLE);

        // Pied de page télémétrie matérielle
        NanoUiRenderer.drawCircle(14.0F, this.height - 11.5F, 2.2F, profColor);
        String footerLeft = gpuBrand + "  \u2022  Java " + System.getProperty("java.version", "1.8");
        smallF.drawString(footerLeft, 21.0F, this.height - 15.5F, 0xFF6B748A);

        String footerRight = "PureClient 3.5.0  \u2022  Forge 1.8.9";
        float rw = smallF.getStringWidth(footerRight);
        smallF.drawString(footerRight, this.width - rw - 12.0F, this.height - 15.5F, 0xFF6B748A);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private enum ButtonKind {
        PRIMARY,
        SECONDARY,
        ACCENT_OUTLINE,
        SUBTLE,
        DANGER
    }

    private static void drawActionButton(float x, float y, float w, float h, String label, int mx, int my, ButtonKind kind) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int top;
        int bot;
        int border;
        int textCol;

        switch (kind) {
            case PRIMARY:
                top = hov ? 0xFF6F7BF0 : 0xFF5E6AD2;
                bot = hov ? 0xFF5763D6 : 0xFF4954BA;
                border = hov ? 0x66FFFFFF : 0x3AFFFFFF;
                textCol = 0xFFFFFFFF;
                break;
            case ACCENT_OUTLINE:
                top = hov ? 0xEE20263B : 0xDD171C2C;
                bot = hov ? 0xEE1A2032 : 0xDD131725;
                border = hov ? 0xAA6E7AE8 : 0x665E6AD2;
                textCol = hov ? 0xFFFFFFFF : 0xFFB8C2FF;
                break;
            case DANGER:
                top = hov ? 0xDD3A1820 : 0xAA1A141B;
                bot = hov ? 0xDD2C1218 : 0xAA151117;
                border = hov ? 0x88F87171 : 0x38F87171;
                textCol = hov ? 0xFFFFB4B4 : 0xFFF87171;
                break;
            case SUBTLE:
                top = hov ? 0xEE1E2434 : 0xCC141824;
                bot = hov ? 0xEE181D2B : 0xCC11141F;
                border = hov ? 0x34FFFFFF : 0x18FFFFFF;
                textCol = hov ? 0xFFECEFF6 : 0xFF949CB0;
                break;
            case SECONDARY:
            default:
                top = hov ? 0xF0212738 : 0xE6171C28;
                bot = hov ? 0xF01A1F2E : 0xE6131722;
                border = hov ? 0x3EFFFFFF : 0x1EFFFFFF;
                textCol = 0xFFECEFF6;
                break;
        }

        NanoUiRenderer.drawRoundedGradient(x, y, w, h, 6.5F, top, bot, border, 1.0F);
        SmoothFontRenderer sf = (kind == ButtonKind.PRIMARY || kind == ButtonKind.SECONDARY || kind == ButtonKind.ACCENT_OUTLINE)
                ? SmoothFontRenderer.body()
                : SmoothFontRenderer.small();
        float textY = y + (h - sf.getHeight()) * 0.5F + 0.5F;
        sf.drawCentered(label, x + w * 0.5F, textY, textCol, kind == ButtonKind.PRIMARY);
    }

    private static int profileAccentArgb(PureFPSProfile prof) {
        switch (prof) {
            case FPS: return 0xFF34D399;
            case BALANCED: return 0xFF5E6AD2;
            case QUALITY: return 0xFFFBBF24;
            default: return 0xFFA855F7;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        int hit = hitTestButton(mouseX, mouseY);
        if (hit < 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
        switch (hit) {
            case BTN_HYPIXEL:
                ServerData hypixel = new ServerData("Hypixel", "mc.hypixel.net", false);
                this.mc.displayGuiScreen(new GuiConnecting(this, this.mc, hypixel));
                break;
            case BTN_MULTI:
                this.mc.displayGuiScreen(new GuiMultiplayer(this));
                break;
            case BTN_SOLO:
                this.mc.displayGuiScreen(new GuiSelectWorld(this));
                break;
            case BTN_CLIENT:
                this.mc.displayGuiScreen(new GuiPureFPSSettings(this));
                break;
            case BTN_OPTIONS:
                this.mc.displayGuiScreen(new GuiOptions(this, this.mc.gameSettings));
                break;
            case BTN_MODS:
                this.mc.displayGuiScreen(new GuiModList(this));
                break;
            case BTN_VANILLA:
                bypassOnce = true;
                this.mc.displayGuiScreen(new GuiMainMenu());
                break;
            case BTN_QUIT:
                this.mc.shutdown();
                break;
            default:
                break;
        }
    }

    private int hitTestButton(int mx, int my) {
        float cx = this.width * 0.5F;
        float cy = this.height * 0.5F;
        float py = cy - 232.0F * 0.5F + 6.0F;
        float bx = cx - 116.0F;
        float bw = 232.0F;
        float by = py + 60.0F;
        float halfW = 113.0F;

        if (inside(mx, my, bx, by, bw, 27.0F)) return BTN_HYPIXEL;
        if (inside(mx, my, bx, by + 33.0F, bw, 24.0F)) return BTN_MULTI;
        if (inside(mx, my, bx, by + 62.0F, bw, 24.0F)) return BTN_SOLO;
        if (inside(mx, my, bx, by + 91.0F, bw, 25.0F)) return BTN_CLIENT;
        if (inside(mx, my, bx, by + 122.0F, halfW, 22.0F)) return BTN_OPTIONS;
        if (inside(mx, my, bx + 119.0F, by + 122.0F, halfW, 22.0F)) return BTN_MODS;
        if (inside(mx, my, this.width - 154.0F, 5.0F, 82.0F, 19.0F)) return BTN_VANILLA;
        if (inside(mx, my, this.width - 66.0F, 5.0F, 56.0F, 19.0F)) return BTN_QUIT;
        return -1;
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
