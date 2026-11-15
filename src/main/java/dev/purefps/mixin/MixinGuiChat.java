package dev.purefps.mixin;

import dev.purefps.client.ui.HypixelChatTabsHelper;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiChat.class)
public abstract class MixinGuiChat extends GuiScreen {

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void purefps$drawHypixelChatTabs(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        HypixelChatTabsHelper.renderChatTabs(this.width, this.height, mouseX, mouseY, this.fontRendererObj);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void purefps$clickHypixelChatTabs(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (HypixelChatTabsHelper.onMouseClicked(mouseX, mouseY, mouseButton, this.width, this.height)) {
            ci.cancel();
        }
    }

    @Override
    public void sendChatMessage(String msg, boolean addToChat) {
        String transformed = HypixelChatTabsHelper.transformOutgoingChatMessage(msg);
        if (addToChat && this.mc != null && this.mc.ingameGUI != null && this.mc.ingameGUI.getChatGUI() != null) {
            this.mc.ingameGUI.getChatGUI().addToSentMessages(msg);
        }
        if (this.mc != null && this.mc.thePlayer != null && transformed != null && !transformed.trim().isEmpty()) {
            if (net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(this.mc.thePlayer, transformed) != 0) {
                return;
            }
            this.mc.thePlayer.sendChatMessage(transformed);
        }
    }
}
