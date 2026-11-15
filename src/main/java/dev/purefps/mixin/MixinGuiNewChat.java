package dev.purefps.mixin;

import dev.purefps.client.ui.HypixelChatTabsHelper;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Option Chat Épuré (type Lunar / Badlion) + filtrage optionnel par onglet ALL / TEAM / PARTY.
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

    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), cancellable = true)
    private void purefps$filterChatByChannelTab(IChatComponent chatComponent, int chatLineId, CallbackInfo ci) {
        if (chatComponent != null) {
            String unformatted = chatComponent.getUnformattedText();
            if (!HypixelChatTabsHelper.shouldDisplayChatLine(unformatted)) {
                ci.cancel();
            }
        }
    }
}
