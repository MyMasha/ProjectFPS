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
 * Menu Principal MashClient 4.0 — Édition "Singularity" :
 * - Poursuit en temps réel à 600+ FPS la lentille analytique du Trou Noir relativiste (commencée à la place de "Mojang").
 * - Panneau de commande en verre fumé obsidienne & or chaud (#F5B85C), aligné sur le design du Launcher MashClient.exe.
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
    private static final int BTN_PROFILE = 9;
    private static final int BTN_BORDERLESS = 10;

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
                gpuBrand = renderer.replace("/PCIe/SSE2", "").trim();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        NanoUiRenderer.updateScaleFactor(this.mc);

        float uiAlpha = BlackHoleEmergenceRenderer.renderMenuBackground(this.mc, this.width, this.height, mouseX, mouseY);
        this.lastUiAlpha = uiAlpha;
        if (uiAlpha <= 0.015F) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        float cx = this.width * 0.5F;
        float cy = this.height * 0.5F;
        float cardW = 288.0F;
        float cardH = 244.0F;
        float px = cx - cardW * 0.5F;
        float py = cy - cardH * 0.5F + 6.0F;

        PureFPSProfile prof = PureFPSConfig.activeProfile;
        int profColor = mulAlpha(profileAccentArgb(prof), uiAlpha);

        NanoUiRenderer.beginBatch();

        NanoUiRenderer.drawShadow(px, py, cardW, cardH, 13.0F, 26.0F, mulAlpha(0xC0000000, uiAlpha));

        // Barre de télémétrie supérieure en verre fumé obsidienne
        NanoUiRenderer.drawRoundedRect(
                -4.0F, -4.0F, this.width + 8.0F, 34.0F, 0.0F,
                mulAlpha(0xBE06080E, uiAlpha), mulAlpha(0x18FFFFFF, uiAlpha), 1.0F
        );

        // Pilule Joueur + Profil cliquable
        boolean profHov = uiAlpha > 0.45F && inside(mouseX, mouseY, 10.0F, 5.5F, 198.0F, 19.0F);
        NanoUiRenderer.drawRoundedRect(
                10.0F, 5.5F, 198.0F, 19.0F, 9.5F,
                mulAlpha(profHov ? 0xE0111623 : 0xCC0B0E17, uiAlpha),
                mulAlpha(profHov ? 0x55F5B85C : 0x22FFFFFF, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawCircle(20.0F, 15.0F, 2.6F, profColor);

        // Pilule FPS temps-réel + Pilule Borderless
        NanoUiRenderer.drawRoundedRect(
                214.0F, 5.5F, 68.0F, 19.0F, 9.5F,
                mulAlpha(0xCC0B0E17, uiAlpha), mulAlpha(0x20FFFFFF, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawCircle(223.5F, 15.0F, 2.2F, mulAlpha(0xFF34D399, uiAlpha));

        boolean brdOn = PureFPSConfig.borderlessFullscreen;
        boolean brdHov = uiAlpha > 0.45F && inside(mouseX, mouseY, 288.0F, 5.5F, 92.0F, 19.0F);
        if (this.width >= 560) {
            NanoUiRenderer.drawRoundedRect(
                    288.0F, 5.5F, 92.0F, 19.0F, 9.5F,
                    mulAlpha(brdOn ? 0x2634D399 : 0xCC0B0E17, uiAlpha),
                    mulAlpha(brdHov ? 0x7734D399 : (brdOn ? 0x4D34D399 : 0x20FFFFFF), uiAlpha),
                    1.0F
            );
        }

        // Carte centrale Singularity
        NanoUiRenderer.drawRoundedGradient(
                px, py, cardW, cardH, 13.0F,
                mulAlpha(0xDA0A0D16, uiAlpha),
                mulAlpha(0xE806080E, uiAlpha),
                mulAlpha(0x2CF5B85C, uiAlpha),
                1.0F
        );
        NanoUiRenderer.drawLine(px + 22.0F, py + 54.0F, px + cardW - 22.0F, py + 54.0F, 0.8F, mulAlpha(0x1EFFFFFF, uiAlpha));

        // Emblème M doré + Badge 4.0
        SmoothFontRenderer titleF = SmoothFontRenderer.title();
        SmoothFontRenderer bodyF = SmoothFontRenderer.body();
        SmoothFontRenderer smallF = SmoothFontRenderer.small();

        String brand = "MASHCLIENT";
        float brandW = titleF.getStringWidth(brand);
        float badgeW = 32.0F;
        float emblemW = 20.0F;
        float groupW = emblemW + 7.0F + brandW + 7.0F + badgeW;
        float groupX = cx - groupW * 0.5F;

        NanoUiRenderer.drawRoundedGradient(
                groupX, py + 14.0F, emblemW, 20.0F, 5.5F,
                mulAlpha(0xFFF6C76E, uiAlpha), mulAlpha(0xFFC98122, uiAlpha),
                mulAlpha(0x99FFE7B5, uiAlpha), 1.0F
        );
        NanoUiRenderer.drawRoundedRect(
                groupX + emblemW + 7.0F + brandW + 7.0F, py + 17.5F,
                badgeW, 13.5F, 6.75F,
                mulAlpha(0x33F5B85C, uiAlpha), mulAlpha(0x77F5B85C, uiAlpha), 1.0F
        );

        // Boutons principaux
        float bx = cx - 122.0F;
        float bw = 244.0F;
        float by = py + 64.0F;
        float halfW = 119.0F;

        drawButtonBg(bx, by, bw, 28.0F, mouseX, mouseY, ButtonKind.PRIMARY, uiAlpha);
        drawButtonBg(bx, by + 34.0F, bw, 24.0F, mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonBg(bx, by + 63.0F, bw, 24.0F, mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonBg(bx, by + 92.0F, bw, 26.0F, mouseX, mouseY, ButtonKind.ACCENT_OUTLINE, uiAlpha);
        drawButtonBg(bx, by + 124.0F, halfW, 22.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(bx + 125.0F, by + 124.0F, halfW, 22.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(this.width - 154.0F, 5.5F, 82.0F, 19.0F, mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonBg(this.width - 66.0F, 5.5F, 56.0F, 19.0F, mouseX, mouseY, ButtonKind.DANGER, uiAlpha);

        NanoUiRenderer.drawCircle(14.0F, this.height - 11.5F, 2.2F, profColor);
        NanoUiRenderer.endBatch();

        // Typographie HD
        String sessionName = (this.mc.getSession() != null && this.mc.getSession().getUsername() != null)
                ? this.mc.getSession().getUsername()
                : "Player";
        titleF.drawStringWithShadow(brand, groupX + emblemW + 7.0F, py + 15.0F, mulAlpha(0xFFF7F4EC, uiAlpha));

        bodyF.beginBatch();
        bodyF.drawCentered("M", groupX + emblemW * 0.5F, py + 19.5F, mulAlpha(0xFF0A0B0E, uiAlpha), false);
        drawButtonLabel(bodyF, bx, by, bw, 28.0F, "Rejoindre Hypixel", mouseX, mouseY, ButtonKind.PRIMARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 34.0F, bw, 24.0F, "Multijoueur", mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 63.0F, bw, 24.0F, "Mode Solo", mouseX, mouseY, ButtonKind.SECONDARY, uiAlpha);
        drawButtonLabel(bodyF, bx, by + 92.0F, bw, 26.0F, "Studio MashClient (200 Modules)", mouseX, mouseY, ButtonKind.ACCENT_OUTLINE, uiAlpha);
        bodyF.endBatch();

        smallF.beginBatch();
        smallF.drawString(sessionName + "  \u2022  Profil " + prof.getDisplayName(), 28.0F, 11.0F, mulAlpha(0xFFEAECEF, uiAlpha));
        smallF.drawString(Minecraft.getDebugFPS() + " FPS", 230.5F, 11.0F, mulAlpha(0xFFECEFF6, uiAlpha));
        if (this.width >= 560) {
            smallF.drawCentered(brdOn ? "Borderless ON" : "Borderless OFF", 334.0F, 11.0F, mulAlpha(brdOn ? 0xFF6EE7B7 : 0xFF949CB0, uiAlpha), false);
        }

        smallF.drawCentered("v4.0", groupX + emblemW + 7.0F + brandW + 7.0F + badgeW * 0.5F, py + 20.5F, mulAlpha(0xFFF7CE86, uiAlpha), false);
        smallF.drawCentered("Singularity Engine  \u2022  RSHIFT pour les 200 modules & HUD", cx, py + 38.0F, mulAlpha(0xFF949CB0, uiAlpha), false);

        drawButtonLabel(smallF, bx, by + 124.0F, halfW, 22.0F, "Options MC", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, bx + 125.0F, by + 124.0F, halfW, 22.0F, "Mods Forge", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, this.width - 154.0F, 5.5F, 82.0F, 19.0F, "Menu Vanilla", mouseX, mouseY, ButtonKind.SUBTLE, uiAlpha);
        drawButtonLabel(smallF, this.width - 66.0F, 5.5F, 56.0F, 19.0F, "Quitter", mouseX, mouseY, ButtonKind.DANGER, uiAlpha);

        String footerLeft = gpuBrand + "  \u2022  " + MashModules.getActiveCount() + "/200 modules actifs";
        smallF.drawString(footerLeft, 21.0F, this.height - 15.5F, mulAlpha(0xFF7D8599, uiAlpha));
        String footerRight = "MashClient 4.0.0  \u2022  Forge 1.8.9";
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
                top = hov ? 0xEE1C1917 : 0xDD12131B;
                bot = hov ? 0xEE141312 : 0xDD0D0F16;
                border = hov ? 0xBBF5B85C : 0x5CF5B85C;
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
                x, y, w, h, 6.5F,
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
                this.mc.displayGuiScreen(new GuiMashClientMenu(this));
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
            case BTN_PROFILE: {
                PureFPSProfile next = PureFPSConfig.activeProfile == PureFPSProfile.FPS
                        ? PureFPSProfile.BALANCED
                        : (PureFPSConfig.activeProfile == PureFPSProfile.BALANCED ? PureFPSProfile.QUALITY : PureFPSProfile.FPS);
                PureFPSConfig.applyProfile(next, true);
                MashModules.syncFromPureConfig();
                break;
            }
            case BTN_BORDERLESS:
                PureFPSConfig.borderlessFullscreen = !PureFPSConfig.borderlessFullscreen;
                MashModules.MOD_BORDERLESS.setEnabledSilent(PureFPSConfig.borderlessFullscreen);
                PureFPSConfig.markDirty();
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
        float py = cy - 244.0F * 0.5F + 6.0F;
        float bx = cx - 122.0F;
        float bw = 244.0F;
        float by = py + 64.0F;
        float halfW = 119.0F;

        if (inside(mx, my, bx, by, bw, 28.0F)) return BTN_HYPIXEL;
        if (inside(mx, my, bx, by + 34.0F, bw, 24.0F)) return BTN_MULTI;
        if (inside(mx, my, bx, by + 63.0F, bw, 24.0F)) return BTN_SOLO;
        if (inside(mx, my, bx, by + 92.0F, bw, 26.0F)) return BTN_CLIENT;
        if (inside(mx, my, bx, by + 124.0F, halfW, 22.0F)) return BTN_OPTIONS;
        if (inside(mx, my, bx + 125.0F, by + 124.0F, halfW, 22.0F)) return BTN_MODS;
        if (inside(mx, my, 10.0F, 5.5F, 198.0F, 19.0F)) return BTN_PROFILE;
        if (this.width >= 560 && inside(mx, my, 288.0F, 5.5F, 92.0F, 19.0F)) return BTN_BORDERLESS;
        if (inside(mx, my, this.width - 154.0F, 5.5F, 82.0F, 19.0F)) return BTN_VANILLA;
        if (inside(mx, my, this.width - 66.0F, 5.5F, 56.0F, 19.0F)) return BTN_QUIT;
        return -1;
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
