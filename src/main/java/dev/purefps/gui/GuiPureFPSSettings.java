package dev.purefps.gui;

import net.minecraft.client.gui.GuiScreen;

/**
 * Pont de compatibilité : redirige vers le nouveau Studio unifié GuiMashClientMenu.
 */
public class GuiPureFPSSettings extends GuiMashClientMenu {
    public GuiPureFPSSettings(GuiScreen parentScreen) {
        super(parentScreen, false);
    }
}
