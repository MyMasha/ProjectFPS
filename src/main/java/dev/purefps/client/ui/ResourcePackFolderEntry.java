package dev.purefps.client.ui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.ResourcePackListEntry;

import java.io.File;

/**
 * Entrée de dossier navigable dans l'écran Resource Packs de Minecraft 1.8.9.
 */
public final class ResourcePackFolderEntry extends ResourcePackListEntry {

    private final File targetFolder;
    private final boolean parentLink;
    private final int packCount;
    private final Runnable onNavigate;

    public ResourcePackFolderEntry(
            GuiScreenResourcePacks screen,
            File targetFolder,
            boolean parentLink,
            int packCount,
            Runnable onNavigate
    ) {
        super(screen);
        this.targetFolder = targetFolder;
        this.parentLink = parentLink;
        this.packCount = packCount;
        this.onNavigate = onNavigate;
    }

    public File getTargetFolder() {
        return targetFolder;
    }

    @Override
    public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected) {
        boolean hovered = mouseX >= x && mouseX <= x + listWidth && mouseY >= y && mouseY <= y + slotHeight;
        int bg = hovered ? 0x4422293A : 0x24141824;
        Gui.drawRect(x, y, x + listWidth - 6, y + 32, bg);

        // Icône dossier minimaliste (32x32)
        int iconBg = hovered ? 0xFF23293A : 0xFF171B26;
        int tabCol = parentLink ? 0xFF94A3B8 : 0xFFF5B85C;
        int bodyCol = parentLink ? 0xFFCBD5E1 : 0xFFE5A23C;
        Gui.drawRect(x + 1, y + 1, x + 31, y + 31, iconBg);
        Gui.drawRect(x + 6, y + 8, x + 15, y + 11, tabCol);
        Gui.drawRect(x + 6, y + 11, x + 26, y + 24, bodyCol);
        Gui.drawRect(x + 7, y + 13, x + 25, y + 23, hovered ? 0xFFFAD38A : (parentLink ? 0xFF94A3B8 : 0xFFF5B85C));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        String title = func_148312_b();
        int maxW = 157;
        if (this.mc.fontRendererObj.getStringWidth(title) > maxW) {
            title = this.mc.fontRendererObj.trimStringToWidth(title, maxW - this.mc.fontRendererObj.getStringWidth("...")) + "...";
        }
        this.mc.fontRendererObj.drawStringWithShadow(title, x + 35, y + 4, hovered ? 0xFFFFDF9E : 0xFFF1F5F9);
        this.mc.fontRendererObj.drawStringWithShadow(func_148311_a(), x + 35, y + 17, 0xFF8C96AC);
    }

    @Override
    protected int func_183019_a() {
        return 1;
    }

    @Override
    protected String func_148311_a() {
        if (parentLink) {
            return "Retour au dossier supérieur";
        }
        return packCount + (packCount > 1 ? " packs disponibles" : " pack disponible");
    }

    @Override
    protected String func_148312_b() {
        if (parentLink) {
            return "\u00a7e.. \u00a77(Dossier parent)";
        }
        return targetFolder.getName();
    }

    @Override
    protected void func_148313_c() {
    }

    @Override
    protected boolean func_148310_d() {
        return false;
    }

    @Override
    public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX, int relativeY) {
        if (mouseEvent == 0 && this.onNavigate != null) {
            this.onNavigate.run();
            return true;
        }
        return false;
    }
}
