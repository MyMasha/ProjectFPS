package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.RenderItemContext;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation extrême des boutiques BedWars / SkyWars / Coffres (GuiContainer) :
 * - Dans une boutique Hypixel de 54 items enchantés, Vanilla exécute 108 passes matricielles GL_TEXTURE
 *   + blend GL_ONE par frame (chute de FPS brutale à l'ouverture du shop PNJ).
 * - Avec Déchargement Menus Max, seul le slot actuellement survolé conserve l'animation de reflet en profil FPS,
 *   éliminant jusqu'à 106 passes GPU par frame dans les menus d'achat !
 */
@Mixin(GuiContainer.class)
public class MixinGuiContainer {

    @Shadow
    private Slot theSlot;

    @Unique
    private boolean purefps$prevSkipGlint = false;

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void purefps$beforeDrawSlot(Slot slotIn, CallbackInfo ci) {
        this.purefps$prevSkipGlint = RenderItemContext.skipGlintForCurrentItem;
        if (PureFPSConfig.enabled
                && PureFPSConfig.menuWorldUnload
                && PureFPSConfig.activeProfile == PureFPSProfile.FPS
                && slotIn != this.theSlot) {
            RenderItemContext.skipGlintForCurrentItem = true;
        }
    }

    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void purefps$afterDrawSlot(Slot slotIn, CallbackInfo ci) {
        RenderItemContext.skipGlintForCurrentItem = this.purefps$prevSkipGlint;
    }
}
