package dev.purefps.gui;

import net.minecraft.client.gui.GuiScreen;

/**
 * Pont de compatibilité : redirige vers le Studio unifié GuiMashClientMenu (onglet HUD).
 */
public class GuiHudEditor extends GuiMashClientMenu {
    public GuiHudEditor(GuiScreen parent) {
        super(parent, true);
    }
}
