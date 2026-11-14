package dev.purefps.gui;

import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.BlackHoleEmergenceRenderer;
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
 * Menu Principal PureClient 3.6 — Édition "Singularity" :
 * - Poursuit en temps réel à 120 FPS la sortie de la caméra hors du Trou Noir relativiste (commencée à la place de "Mojang").
 * - L'interface en verre fumé obsidienne & or Doppler apparaît en fondu progressif pendant que la caméra se stabilise en orbite.
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

        // Arrière-plan Trou Noir temps-réel GLSL 120 (sortie directe de l'Horizon des Événements -> orbite 120 FPS)
        float uiAlpha = BlackHoleEmergenceRenderer.renderMenuBackground(this.mc, this.width, this.height, mouseX, mouseY);
        this.lastUiAlpha = uiAlpha;
        if (uiAlpha <= 0.015F) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        float cx = this.width * 0.5F;
        float cy = this.height * 0.5F;
        float cardW = 276.0F;
        float cardH = 236.0F;
        float px = cx - cardW * 0.5F;
        float py = cy - cardH * 0.5F + 6.0F;

        NanoUiRenderer.beginBatch();

        NanoUiRenderer.drawShadow(px, py, cardW, cardH, 12.0F, 24.0F, mulAlpha(0xB8000000, uiAlpha));

        // Barre supérieure vitrée fumée (30 px) laissant transparaître le halo gravitationnel
        NanoUiRenderer.drawRoundedRect(
                -4.0F,
                -4.0F,
                this.width + 8.0F,
                33.0F,
                0.0F,
                mulAlpha(0xB805070C, uiAlpha),
                mulAlpha(0x16FFFFFF, uiAlpha),
                1.0F
        );

        // Pilule session joueur
        PureFPSProfile prof = PureFPSConfig.activeProfile;
        int profColor = mulAlpha(profileAccentArgb(prof), uiAlpha);
        NanoUiRenderer.drawRoundedRect(
                10.0F,
                5.0F,
                204.0F,
                19.0F,
                9.5F,
                mulAlpha(0xC20B0E16, uiAlpha),
                mulAlpha(0x20FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawCircle(19.5F, 14.5F, 2.8F, mulAlpha(0xFF34D399, uiAlpha));

        // Carte centrale Singularity verre fumé obsidienne semi-transparente sur le Trou Noir
        NanoUiRenderer.drawRoundedGradient(
                px,
                py,
                cardW,
                cardH,
                12.0F,
                mulAlpha(0xD4090C13, uiAlpha),
                mulAlpha(0xDF06080D, uiAlpha),
                mulAlpha(0x28FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawRoundedRect(px + 20.0F, py + 50.0F, cardW - 40.0F, 0.8F, 0.0F, mulAlpha(0x18FFFFFF, uiAlpha), 0, 0.0F);

        // Badge version ambre doré subtil
        SmoothFontRenderer titleF = SmoothFontRenderer.title();
        SmoothFontRenderer smallF = SmoothFontRenderer.small();
        String brand = "PURECLIENT";
        float brandW = titleF.getStringWidth(brand);
        float badgeW = 30.0F;
        float groupX = cx - (brandW + 7.0F + badgeW) * 0.5F;
        NanoUiRenderer.drawRoundedRect(
                groupX + brandW + 7.0F,
                py + 17.5F,
                badgeW,
                13.0F,
                6.5F,
                mulAlpha(0x2EF5B85C, uiAlpha),
                mulAlpha(0x66F5B85C, uiAlpha),
                1.0F
        );

        // Boutons d'action (fond SDF en un seul lot)
        float bx = cx - 118.0F;
        float bw = 236.0F;
        float by = py + 60.0F;
        float halfW = 115.0F;

        drawButtonBg(bx, by, bw, 28.0F, mouseX, mouseY, ButtonKind.PRIMARY, uiAlpha);
        drawButtonBg(bx, by + 34.0F, bw, 24.0F, mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonBg(bx, by + 63.0F, bw, 24.0F, mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonBg(bx, by + 92.0F, bw, 25.0F, mouseX, mouseY, ButtonKind.ACCENT_OUTLINE, uiAlpha);
        drawButtonBg(bx, by + 123.0F, halfW, 22.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(bx + 121.0F, by + 123.0F, halfW, 22.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(this.width - 154.0F, 5.0F, 82.0F, 19.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(this.width - 66.0F, 5.0F, 56.0F, 19.0F, mouseX, mouseY, ButtonKind.DANGER, uiAlpha);

        NanoUiRenderer.drawCircle(14.0F, this.height - 11.5F, 2.2F, profColor);

        NanoUiRenderer.endBatch();

        // Passe typographique HD
        String sessionName = (this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";
        titleF.drawStringWithShadow(brand, groupX, py + 15.0F, mulAlpha(0xFFF7F4EC, uiAlpha));

        SmoothFontRenderer bodyF = SmoothFontRenderer.body();
        bodyF.beginBatch();
        drawButtonLabel(bodyF, bx, by, bw, 28.0F, "Rejoindre Hypixel", mouseX, mouseY, ButtonKind.PRIMARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 34.0F, bw, 24.0F, "Multijoueur", mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 63.0F, bw, 24.0F, "Mode Solo", mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 92.0F, bw, 25.0F, "Tableau de Bord PureClient", mouseX, mouseY, ButtonKind.ACCENT_OUTLINE, uiAlpha);
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString(sessionName + "  \u2022  Profil " + prof.getDisplayName(), 27.0F, 10.5F, mulAlpha(0xFFEAECEF, uiAlpha));
        smallF.drawCentered("v3.6", groupX + brandW + 7.0F + badgeW * 0.5F, py + 20.0F, mulAlpha(0xFFF7CE86, uiAlpha), false);
        smallF.drawCentered("Singularity VAO + Triangles  \u2022  RSHIFT en jeu", cx, py + 36.0F, mulAlpha(0xFF949CB0, uiAlpha), false);

        drawButtonLabel(smallF, bx, by + 123.0F, halfW, 22.0F, "Options MC", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, bx + 121.0F, by + 123.0F, halfW, 22.0F, "Mods Forge", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, this.width - 154.0F, 5.0F, 82.0F, 19.0F, "Menu Vanilla", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, this.width - 66.0F, 5.0F, 56.0F, 19.0F, "Quitter", mouseX, mouseY, ButtonKind.DANGER, uiAlpha);

        String footerLeft = gpuBrand + "  \u2022  Java " + System.getProperty("java.version", "1.8");
        smallF.drawString(footerLeft, 21.0F, this.height - 15.5F, mulAlpha(0xFF7D8599, uiAlpha));
        String footerRight = "PureClient 3.6.0  \u2022  Forge 1.8.9";
        float rw = smallF.getStringWidth(footerRight);
        smallF.drawString(footerRight, this.width - rw - 12.0F, this.height - 15.5F, mulAlpha(0xFF7D8599, uiAlpha));
        smallF.endBatch();

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

    private static int mulAlpha(int argb, float alphaFactor) {
        int baseA = (argb >>> 24) & 0xFF;
        int scaledA = Math.max(0, Math.min(255, Math.round((float) baseA * alphaFactor)));
        return (scaledA << 24) | (argb & 0x00FFFFFF);
    }

    private static void drawButtonBg(float x, float y, float w, float h, int mx, int my, ButtonKind kind, float uiAlpha) {
        boolean hov = uiAlpha > 0.45F && mx >= x && mx <= x + w && my >= y && my <= y + h;
        int top;
        int bot;
        int border;

        switch (kind) {
            case PRIMARY:
                top = hov ? 0xFFF8CA78 : 0xFFEAB354;
                bot = hov ? 0xFFE39E3B : 0xFFC98324;
                border = hov ? 0xAAFFF3D6 : 0x66FFE6B3;
                break;
            case ACCENT_OUTLINE:
                top = hov ? 0xEE1A1816 : 0xDD121319;
                bot = hov ? 0xEE141312 : 0xDD0D0F15;
                border = hov ? 0x99F5B85C : 0x4DF5B85C;
                break;
            case DANGER:
                top = hov ? 0xDD3A1820 : 0xAA181217;
                bot = hov ? 0xDD2C1218 : 0xAA120E13;
                border = hov ? 0x88F87171 : 0x38F87171;
                break;
            case SUBTLE:
                top = hov ? 0xEE1A1E2A : 0xCC10131C;
                bot = hov ? 0xEE141822 : 0xCC0C0F17;
                border = hov ? 0x32FFFFFF : 0x16FFFFFF;
                break;
            case SECONDARY:
            default:
                top = hov ? 0xF01C202C : 0xE6131720;
                bot = hov ? 0xF0161923 : 0xE60F121A;
                border = hov ? 0x3AFFFFFF : 0x1CFFFFFF;
                break;
        }
        NanoUiRenderer.drawRoundedGradient(
                x,
                y,
                w,
                h,
                6.5F,
                mulAlpha(top, uiAlpha),
                mulAlpha(bot, uiAlpha),
                mulAlpha(border, uiAlpha),
                1.0F
        );
    }

    private static void drawButtonLabel(
            SmoothFontRenderer sf,
            float x,
            float y,
            float w,
            float h,
            String label,
            int mx,
            int my,
            ButtonKind kind,
            float uiAlpha
    ) {
        boolean hov = uiAlpha > 0.45F && mx >= x && mx <= x + w && my >= y && my <= y + h;
        int textCol;
        switch (kind) {
            case PRIMARY:
                textCol = 0xFF0A0B0E;
                break;
            case ACCENT_OUTLINE:
                textCol = hov ? 0xFFFFF4DC : 0xFFF5C878;
                break;
            case DANGER:
                textCol = hov ? 0xFFFFB4B4 : 0xFFF87171;
                break;
            case SUBTLE:
                textCol = hov ? 0xFFECEFF6 : 0xFF949CB0;
                break;
            case SECONDARY:
            default:
                textCol = 0xFFECEFF6;
                break;
        }
        float textY = y + (h - sf.getHeight()) * 0.5F + 0.5F;
        sf.drawCentered(label, x + w * 0.5F, textY, mulAlpha(textCol, uiAlpha), false);
    }

    private static int profileAccentArgb(PureFPSProfile prof) {
        switch (prof) {
            case FPS: return 0xFF34D399;
            case BALANCED: return 0xFFF5B85C;
            case QUALITY: return 0xFF60A5FA;
            default: return 0xFFE5E7EB;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0 || this.lastUiAlpha < 0.35F) {
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
        float py = cy - 236.0F * 0.5F + 6.0F;
        float bx = cx - 118.0F;
        float bw = 236.0F;
        float by = py + 60.0F;
        float halfW = 115.0F;

        if (inside(mx, my, bx, by, bw, 28.0F)) return BTN_HYPIXEL;
        if (inside(mx, my, bx, by + 34.0F, bw, 24.0F)) return BTN_MULTI;
        if (inside(mx, my, bx, by + 63.0F, bw, 24.0F)) return BTN_SOLO;
        if (inside(mx, my, bx, by + 92.0F, bw, 25.0F)) return BTN_CLIENT;
        if (inside(mx, my, bx, by + 123.0F, halfW, 22.0F)) return BTN_OPTIONS;
        if (inside(mx, my, bx + 121.0F, by + 123.0F, halfW, 22.0F)) return BTN_MODS;
        if (inside(mx, my, this.width - 154.0F, 5.0F, 82.0F, 19.0F)) return BTN_VANILLA;
        if (inside(mx, my, this.width - 66.0F, 5.0F, 56.0F, 19.0F)) return BTN_QUIT;
        return -1;
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
