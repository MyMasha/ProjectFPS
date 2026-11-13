package dev.purefps.gui;

import dev.purefps.client.PureClientHud;
import dev.purefps.client.ui.NanoUiRenderer;
import dev.purefps.client.ui.SmoothFontRenderer;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;

/**
 * Éditeur visuel Drag & Drop des modules HUD PureClient 3.5 (zéro bouton Vanilla en pierre).
 */
public class GuiHudEditor extends GuiScreen {

    private final GuiScreen parent;
    private int draggingId = 0; // 0=aucun, 1=fps, 2=ping, 3=sprint, 4=keys
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

    public GuiHudEditor(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        NanoUiRenderer.setScaleFactor(sr.getScaleFactor());

        drawRect(0, 0, this.width, this.height, 0x7A06080E);

        // Repères d'alignement centraux subtils
        drawRect(this.width / 2, 0, this.width / 2 + 1, this.height, 0x16FFFFFF);
        drawRect(0, this.height / 2, this.width, this.height / 2 + 1, 0x16FFFFFF);

        PureClientHud.render(this.mc, sr, true);

        // Contours de sélection Indigo sur le survol ou le déplacement
        drawSelectionBox(mouseX, mouseY, 1, PureFPSConfig.hudFpsCps, PureFPSConfig.hudFpsX, PureFPSConfig.hudFpsY, PureClientHud.FPS_CARD_W, PureClientHud.FPS_CARD_H);
        drawSelectionBox(mouseX, mouseY, 2, PureFPSConfig.hudPing, PureFPSConfig.hudPingX, PureFPSConfig.hudPingY, PureClientHud.PING_CARD_W, PureClientHud.PING_CARD_H);
        drawSelectionBox(mouseX, mouseY, 3, PureFPSConfig.hudSprintStatus, PureFPSConfig.hudSprintX, PureFPSConfig.hudSprintY, PureClientHud.SPRINT_CARD_W, PureClientHud.SPRINT_CARD_H);
        drawSelectionBox(mouseX, mouseY, 4, PureFPSConfig.hudKeystrokes, PureFPSConfig.hudKeystrokesX, PureFPSConfig.hudKeystrokesY, PureClientHud.KEYSTROKES_W, PureClientHud.KEYSTROKES_H);

        // Pilule d'aide flottante en haut
        float bannerW = 290.0F;
        float bx = (this.width - bannerW) * 0.5F;
        NanoUiRenderer.drawShadow(bx, 10.0F, bannerW, 24.0F, 12.0F, 10.0F, 0x77000000);
        NanoUiRenderer.drawRoundedRect(bx, 10.0F, bannerW, 24.0F, 12.0F, 0xEE0E121D, 0x26FFFFFF, 1.0F);
        SmoothFontRenderer.body().drawCentered("Éditeur HUD  \u2022  Glissez chaque module à la souris", this.width * 0.5F, 17.5F, 0xFFECEFF6, false);

        // Barre d'actions flottante en bas
        float resetX = this.width * 0.5F - 114.0F;
        float saveX = this.width * 0.5F + 4.0F;
        float btnY = this.height - 32.0F;

        boolean hovReset = hit(mouseX, mouseY, (int) resetX, (int) btnY, 110, 22);
        boolean hovSave = hit(mouseX, mouseY, (int) saveX, (int) btnY, 110, 22);

        NanoUiRenderer.drawRoundedRect(resetX, btnY, 110.0F, 22.0F, 6.5F, hovReset ? 0xEE22283A : 0xDD151926, 0x28FFFFFF, 1.0F);
        SmoothFontRenderer.body().drawCentered("Réinitialiser", resetX + 55.0F, btnY + 6.5F, 0xFFECEFF6, false);

        NanoUiRenderer.drawRoundedRect(saveX, btnY, 110.0F, 22.0F, 6.5F, hovSave ? 0xFF6E7AE8 : 0xFF5E6AD2, 0x48FFFFFF, 1.0F);
        SmoothFontRenderer.body().drawCentered("Enregistrer", saveX + 55.0F, btnY + 6.5F, 0xFFFFFFFF, true);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSelectionBox(int mx, int my, int id, boolean enabled, int x, int y, int w, int h) {
        if (!enabled) {
            return;
        }
        boolean active = (this.draggingId == id) || hit(mx, my, x, y, w, h);
        if (active) {
            NanoUiRenderer.drawRoundedRect(x - 1.5F, y - 1.5F, w + 3.0F, h + 3.0F, 6.5F, 0x105E6AD2, 0xCC7B87F4, 1.2F);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }

        int resetX = (int) (this.width * 0.5F - 114.0F);
        int saveX = (int) (this.width * 0.5F + 4.0F);
        int btnY = this.height - 32;

        if (hit(mouseX, mouseY, resetX, btnY, 110, 22)) {
            playClick();
            PureFPSConfig.hudFpsX = 8;
            PureFPSConfig.hudFpsY = 8;
            PureFPSConfig.hudPingX = 8;
            PureFPSConfig.hudPingY = 44;
            PureFPSConfig.hudSprintX = 8;
            PureFPSConfig.hudSprintY = 66;
            PureFPSConfig.hudKeystrokesX = 8;
            PureFPSConfig.hudKeystrokesY = 92;
            PureFPSConfig.save();
            return;
        }
        if (hit(mouseX, mouseY, saveX, btnY, 110, 22)) {
            playClick();
            PureFPSConfig.save();
            this.mc.displayGuiScreen(this.parent);
            return;
        }

        if (PureFPSConfig.hudFpsCps && hit(mouseX, mouseY, PureFPSConfig.hudFpsX, PureFPSConfig.hudFpsY, PureClientHud.FPS_CARD_W, PureClientHud.FPS_CARD_H)) {
            draggingId = 1;
            dragOffsetX = mouseX - PureFPSConfig.hudFpsX;
            dragOffsetY = mouseY - PureFPSConfig.hudFpsY;
        } else if (PureFPSConfig.hudPing && hit(mouseX, mouseY, PureFPSConfig.hudPingX, PureFPSConfig.hudPingY, PureClientHud.PING_CARD_W, PureClientHud.PING_CARD_H)) {
            draggingId = 2;
            dragOffsetX = mouseX - PureFPSConfig.hudPingX;
            dragOffsetY = mouseY - PureFPSConfig.hudPingY;
        } else if (PureFPSConfig.hudSprintStatus && hit(mouseX, mouseY, PureFPSConfig.hudSprintX, PureFPSConfig.hudSprintY, PureClientHud.SPRINT_CARD_W, PureClientHud.SPRINT_CARD_H)) {
            draggingId = 3;
            dragOffsetX = mouseX - PureFPSConfig.hudSprintX;
            dragOffsetY = mouseY - PureFPSConfig.hudSprintY;
        } else if (PureFPSConfig.hudKeystrokes && hit(mouseX, mouseY, PureFPSConfig.hudKeystrokesX, PureFPSConfig.hudKeystrokesY, PureClientHud.KEYSTROKES_W, PureClientHud.KEYSTROKES_H)) {
            draggingId = 4;
            dragOffsetX = mouseX - PureFPSConfig.hudKeystrokesX;
            dragOffsetY = mouseY - PureFPSConfig.hudKeystrokesY;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingId == 0) {
            return;
        }
        int nx = Math.max(2, Math.min(this.width - 40, mouseX - dragOffsetX));
        int ny = Math.max(2, Math.min(this.height - 20, mouseY - dragOffsetY));
        switch (draggingId) {
            case 1: PureFPSConfig.hudFpsX = nx; PureFPSConfig.hudFpsY = ny; break;
            case 2: PureFPSConfig.hudPingX = nx; PureFPSConfig.hudPingY = ny; break;
            case 3: PureFPSConfig.hudSprintX = nx; PureFPSConfig.hudSprintY = ny; break;
            case 4: PureFPSConfig.hudKeystrokesX = nx; PureFPSConfig.hudKeystrokesY = ny; break;
            default: break;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (draggingId != 0) {
            draggingId = 0;
            PureFPSConfig.save();
        }
    }

    @Override
    public void onGuiClosed() {
        PureFPSConfig.save();
    }

    private void playClick() {
        this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static boolean hit(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
