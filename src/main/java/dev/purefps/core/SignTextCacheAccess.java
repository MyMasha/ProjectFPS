package dev.purefps.core;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.IChatComponent;

/**
 * Accès O(1) aux 4 lignes formatées mises en cache sur chaque TileEntitySign :
 * Évite d'exécuter 4x GuiUtilRenderComponents.splitText() + getFormattedText() + getStringWidth()
 * sur chaque panneau à chaque frame !
 */
public interface SignTextCacheAccess {

    String purefps$getCachedLine(int index, IChatComponent rawComponent, FontRenderer fontRenderer);

    int purefps$getCachedHalfWidth(int index);

    void purefps$invalidateSignCache();
}
