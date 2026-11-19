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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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

    @Unique
    private static String purefps$stripLeadingTimestamp(String text) {
        if (text != null && text.length() >= 8
                && text.charAt(0) == '['
                && Character.isDigit(text.charAt(1))
                && Character.isDigit(text.charAt(2))
                && text.charAt(3) == ':'
                && Character.isDigit(text.charAt(4))
                && Character.isDigit(text.charAt(5))
                && text.charAt(6) == ']'
                && text.charAt(7) == ' ') {
            return text.substring(8);
        }
        return text;
    }

    @Unique
    private static boolean purefps$isSystemGameOverBanner(String unformatted) {
        if (unformatted == null) {
            return false;
        }
        String trimmed = purefps$stripLeadingTimestamp(unformatted).trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String low = trimmed.toLowerCase(Locale.ROOT);
        // Exclut tout message envoyé par un joueur (préfixes <Joueur>, PM, canaux, séparateurs » / >)
        if (low.startsWith("<")
                || low.startsWith("from ")
                || low.startsWith("to ")
                || low.startsWith("party >")
                || low.startsWith("guild >")
                || low.startsWith("officer >")
                || low.startsWith("co-op >")
                || low.startsWith("[shout]")
                || low.startsWith("[team]")
                || low.startsWith("[party]")
                || low.startsWith("[guild]")
                || low.startsWith("[spectator]")
                || low.contains(" \u00bb ")
                || low.contains(" > ")) {
            return false;
        }
        if (low.startsWith("winner:") || low.startsWith("winners:")) {
            return true;
        }
        // Tout autre message contenant ": " provient généralement d'un joueur ("Pseudo: texte")
        if (low.contains(": ")) {
            return false;
        }
        return low.startsWith("1st killer -")
                || low.startsWith("1st place -")
                || low.startsWith("winning team -")
                || low.equals("reward summary")
                || low.startsWith("reward summary ");
    }

    @Redirect(
            method = "drawChat",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V", ordinal = 0)
    )
    private void purefps$conditionalChatBackground(int left, int top, int right, int bottom, int color) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.transparentChat) {
            drawRect(left, top, right, bottom, color);
        }
    }

    @ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), argsOnly = true)
    private IChatComponent purefps$prependChatTimestamp(IChatComponent chatComponent) {
        if (!PureFPSConfig.enabled || !MashFlags.chatTimestamps || chatComponent == null) {
            return chatComponent;
        }
        String unformatted = chatComponent.getUnformattedText();
        if (unformatted == null || unformatted.startsWith("[")) {
            return chatComponent;
        }
        LocalTime t = LocalTime.now();
        String prefix = String.format(Locale.ROOT, "\u00a78[%02d:%02d] \u00a7r", t.getHour(), t.getMinute());
        return new ChatComponentText(prefix).appendSibling(chatComponent);
    }

    @Inject(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), cancellable = true)
    private void purefps$handleIncomingChatModules(IChatComponent chatComponent, int chatLineId, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || chatComponent == null) {
            return;
        }
        String rawUnformatted = chatComponent.getUnformattedText();
        if (rawUnformatted == null) {
            return;
        }
        String unformatted = purefps$stripLeadingTimestamp(rawUnformatted);
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

        // AutoGG (hyp_autogg) — uniquement sur bannière système authentique (anti-usurpation chat joueur)
        if (MashModules.isOn("hyp_autogg") && purefps$isSystemGameOverBanner(unformatted)) {
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
