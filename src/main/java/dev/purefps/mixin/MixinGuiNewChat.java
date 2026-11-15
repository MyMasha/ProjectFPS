package dev.purefps.mixin;

import dev.purefps.client.ui.HypixelChatTabsHelper;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * Option Chat Épuré (type Lunar / Badlion) + filtrage par onglet ALL / TEAM / PARTY + Timestamps + AutoGG.
 */
@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat extends Gui {

    @Shadow
    @Final
    private List<ChatLine> chatLines;

    @Unique
    private long purefps$lastAutoGgMs = 0L;

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
    private void purefps$handleIncomingChatModules(IChatComponent chatComponent, int chatLineId, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || chatComponent == null) {
            return;
        }
        String unformatted = chatComponent.getUnformattedText();
        if (unformatted == null) {
            return;
        }
        String low = unformatted.toLowerCase(Locale.ROOT);

        // Filtre connexions lobby (hyp_compact_join_leave)
        if (MashModules.isOn("hyp_compact_join_leave")) {
            if (low.contains(" joined the lobby!") || low.contains(" a rejoint le lobby !")
                    || (low.startsWith(">>> ") && low.endsWith("!"))
                    || (low.startsWith("[+] ") && low.length() < 36)) {
                ci.cancel();
                return;
            }
        }

        // AutoGG (hyp_autogg)
        if (MashModules.isOn("hyp_autogg")) {
            if (low.contains("1st killer -") || low.contains("1st place -")
                    || low.contains("winner:") || low.contains("winners:")
                    || low.contains("winning team -") || low.contains("reward summary")) {
                long now = System.currentTimeMillis();
                if (now - this.purefps$lastAutoGgMs > 8000L) {
                    this.purefps$lastAutoGgMs = now;
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.thePlayer != null) {
                        int phraseIdx = MashModules.modeIdx("hyp_autogg", "phrase", 0);
                        String gg = phraseIdx == 1 ? "GG" : (phraseIdx == 2 ? "gf" : "gg");
                        mc.thePlayer.sendChatMessage("/ac " + gg);
                    }
                }
            }
        }

        // Horodatage du chat (hyp_chat_pro -> timestamps)
        if (MashFlags.chatTimestamps && !unformatted.startsWith("[")) {
            LocalTime t = LocalTime.now();
            String prefix = String.format("\u00a78[%02d:%02d] \u00a7r", t.getHour(), t.getMinute());
            List<IChatComponent> siblings = chatComponent.getSiblings();
            if (siblings != null) {
                siblings.add(0, new ChatComponentText(prefix));
            }
        }
    }

    @Inject(method = "setChatLine", at = @At("HEAD"), cancellable = true)
    private void purefps$filterSetChatLineByChannelTab(
            IChatComponent chatComponent,
            int chatLineId,
            int updateCounter,
            boolean displayOnly,
            CallbackInfo ci
    ) {
        if (chatComponent != null) {
            String unformatted = chatComponent.getUnformattedText();
            if (!HypixelChatTabsHelper.shouldDisplayChatLine(unformatted)) {
                if (!displayOnly && this.chatLines != null) {
                    this.chatLines.add(0, new ChatLine(updateCounter, chatComponent, chatLineId));
                    while (this.chatLines.size() > 100) {
                        this.chatLines.remove(this.chatLines.size() - 1);
                    }
                }
                ci.cancel();
            }
        }
    }
}
