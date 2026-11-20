package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.gui.GuiHudEditor;
import dev.purefps.gui.GuiMashClientMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;

/**
 * Gestionnaire de déchargement automatique des ressources inutiles dans les menus (MashClient 4.3) :
 * - Détecte quand un menu bloquant (Menu RSHIFT MashClient, Boutique BedWars, Coffre, Inventaire, Pause, Options)
 *   recouvre le monde afin de décharger immédiatement les chunks lointains, particules, pluies, entités distantes,
 *   TileEntities distantes et recompilations statiques.
 * - Libère instantanément les tampons FBO du menu principal en entrant dans un monde, et purge tous les caches
 *   dynamiques à chaque changement de serveur/lobby ou retour au menu principal.
 */
public final class MenuUnloadManager {

    private MenuUnloadManager() {
    }

    public static boolean isWorldDimmedByMenu(Minecraft mc) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.menuWorldUnload || mc == null || mc.theWorld == null) {
            return false;
        }
        GuiScreen screen = mc.currentScreen;
        if (screen == null) {
            return false;
        }
        if (screen instanceof GuiChat || screen instanceof GuiHudEditor) {
            return false;
        }
        if (screen instanceof GuiMashClientMenu) {
            return !((GuiMashClientMenu) screen).isCanvasPeekActive();
        }
        return true;
    }

    public static boolean isWorldDimmedByMenu() {
        return isWorldDimmedByMenu(Minecraft.getMinecraft());
    }

    public static void onWorldChangeOrUnload() {
        BlackHoleEmergenceRenderer.releaseMenuResources();
        FastSkullCache.clear();
        FastItemFrameRenderer.clearMapCache();
        FastScoreboardRenderer.clearCache();
        dev.purefps.module.MashHudRenderer.clearWorldRefs();
        ChunkRegionManager.clearAll();
        SmartChunkUpdateScheduler.clear();
        IslandCoreCuller.clearCurrentThread();
        OcclusionCuller.setActiveCamera(null);
        RealPingEstimator.reset();
        PureFPSConfig.unloadedIslandCoresTotal = 0;
        PureFPSConfig.throttledStaticUpdatesTotal = 0;
    }
}
