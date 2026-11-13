package dev.purefps.core;

/**
 * État léger sur le thread client indiquant si l'item en cours de rendu est un objet au sol distant,
 * permettant d'éviter la double passe matricielle d'enchantement (RenderItem.renderEffect) à > 16 blocs.
 */
public final class RenderItemContext {

    public static boolean skipGlintForCurrentItem = false;

    private RenderItemContext() {
    }
}
