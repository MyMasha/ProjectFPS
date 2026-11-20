package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * État léger sur le thread client :
 * 1. Indique si l'item en cours de rendu est distant (coupe RenderItem.renderEffect au-delà de 16-28 blocs).
 * 2. Partage la distance carrée entité<->caméra calculée UNE SEULE FOIS dans RendererLivingEntity.doRender
 *    entre LayerArmorBase, LayerArrow, LayerCape et LayerHeldItem (élimine 4x getRenderViewEntity + getDistanceSq).
 */
public final class RenderItemContext {

    public static boolean skipGlintForCurrentItem = false;

    public static Entity currentLivingEntity = null;
    public static double currentLivingDistSq = -1.0D;
    public static boolean currentLivingIsSelf = false;

    private RenderItemContext() {
    }

    /**
     * Retourne -1.0D si l'entité est le joueur caméra lui-même (ne jamais culler ses propres couches),
     * sinon retourne la distance carrée au spectateur (O(1) depuis le cache doRender).
     */
    public static double getViewerDistSqForLayer(Entity entity) {
        if (entity == null) {
            return -1.0D;
        }
        if (entity == currentLivingEntity) {
            return currentLivingIsSelf ? -1.0D : currentLivingDistSq;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc != null ? mc.getRenderViewEntity() : null;
        if (viewer == null || viewer == entity) {
            return -1.0D;
        }
        return viewer.getDistanceSqToEntity(entity);
    }
}
