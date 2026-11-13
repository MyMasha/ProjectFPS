package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Option Chat Épuré (type Lunar / Badlion) : masque le fond noir opaque des lignes de chat et économise 1 draw call par ligne.
 */
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat extends Gui {

    @Redirect(
            method = "drawChat",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V", ordinal = 0)
    )
    private void purefps$conditionalChatBackground(int left, int top, int right, int bottom, int color) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.transparentChat) {
            drawRect(left, top, right, bottom, color);
        }
    }
}
