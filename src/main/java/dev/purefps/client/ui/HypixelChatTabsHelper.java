package dev.purefps.client.ui;

import dev.purefps.client.audio.MashAudioEngine;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.GlStateManager;

import java.util.Locale;

/**
 * Interactive Hypixel Chat Channel Tabs (ALL / TEAM / PARTY) rendered directly above the GuiChat input bar.
 */
public final class HypixelChatTabsHelper {

    public static final int CHANNEL_ALL = 0;
    public static final int CHANNEL_TEAM = 1;
    public static final int CHANNEL_PARTY = 2;

    private static final String[] CHANNEL_LABELS = {"ALL", "TEAM", "PARTY"};
    private static final int[] CHANNEL_ACCENTS = {
            0xFF38BDF8, // ALL - Sky Blue
            0xFF34D399, // TEAM - Emerald Green
            0xFFA855F7  // PARTY - Royal Purple
    };

    private static int activeChannel = CHANNEL_ALL;

    private HypixelChatTabsHelper() {
    }

    public static int getActiveChannel() {
        return activeChannel;
    }

    public static void setActiveChannel(int channel) {
        if (channel >= CHANNEL_ALL && channel <= CHANNEL_PARTY) {
            activeChannel = channel;
        }
    }

    public static void cycleChannel() {
        Minecraft mc = Minecraft.getMinecraft();
        int prev = activeChannel;
        activeChannel = (activeChannel + 1) % CHANNEL_LABELS.length;
        MashAudioEngine.playClick();
        notifyServerChannelSwitch(mc, prev, activeChannel);
        if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
            mc.ingameGUI.getChatGUI().refreshChat();
        }
    }

    public static boolean isModuleActive() {
        return PureFPSConfig.enabled && MashFlags.hypChatTabs;
    }

    public static void renderChatTabs(int screenWidth, int screenHeight, int mouseX, int mouseY, FontRenderer font) {
        if (!isModuleActive() || font == null) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        int barY = screenHeight - 28;
        int barH = 12;
        int curX = 2;

        for (int i = 0; i < CHANNEL_LABELS.length; i++) {
            String label = CHANNEL_LABELS[i];
            int textW = font.getStringWidth(label);
            int tabW = textW + 14;
            boolean selected = (activeChannel == i);
            boolean hovered = mouseX >= curX && mouseX < curX + tabW && mouseY >= barY && mouseY < barY + barH;

            int bg = selected ? 0xE6141D2E : (hovered ? 0xCC182234 : 0xB00B0F17);
            Gui.drawRect(curX, barY, curX + tabW, barY + barH, bg);

            int accent = CHANNEL_ACCENTS[i];
            if (selected) {
                Gui.drawRect(curX, barY + barH - 2, curX + tabW, barY + barH, accent);
                Gui.drawRect(curX, barY, curX + tabW, barY + 1, (accent & 0x00FFFFFF) | 0x88000000);
            } else if (hovered) {
                Gui.drawRect(curX, barY + barH - 1, curX + tabW, barY + barH, (accent & 0x00FFFFFF) | 0x88000000);
            }

            int textColor = selected ? 0xFFFFFFFF : (hovered ? 0xFFE2E8F0 : 0xFF94A3B8);
            int textX = curX + (tabW - textW) / 2;
            int textY = barY + 2;
            font.drawStringWithShadow(label, textX, textY, textColor);

            curX += tabW + 3;
        }

        String statusText;
        if (activeChannel == CHANNEL_PARTY) {
            statusText = PureFPSConfig.isEnglish() ? "\u00a7dParty Chat (/pc)" : "\u00a7dCanal Groupe (/pc)";
        } else if (activeChannel == CHANNEL_TEAM) {
            statusText = PureFPSConfig.isEnglish() ? "\u00a7aTeam Chat (/tc)" : "\u00a7aCanal Équipe (/tc)";
        } else {
            statusText = PureFPSConfig.isEnglish() ? "\u00a7bAll Chat" : "\u00a7bCanal Global";
        }
        int statusW = font.getStringWidth(statusText) + 8;
        Gui.drawRect(curX + 2, barY, curX + 2 + statusW, barY + barH, 0x800B0F17);
        font.drawStringWithShadow(statusText, curX + 6, barY + 2, 0xFFCBD5E1);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    public static boolean onMouseClicked(int mouseX, int mouseY, int mouseButton, int screenWidth, int screenHeight) {
        if (!isModuleActive() || mouseButton != 0) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return false;
        }
        FontRenderer font = mc.fontRendererObj;
        int barY = screenHeight - 28;
        int barH = 12;
        if (mouseY < barY || mouseY >= barY + barH) {
            return false;
        }

        int curX = 2;
        for (int i = 0; i < CHANNEL_LABELS.length; i++) {
            int textW = font.getStringWidth(CHANNEL_LABELS[i]);
            int tabW = textW + 14;
            if (mouseX >= curX && mouseX < curX + tabW) {
                if (activeChannel != i) {
                    int prev = activeChannel;
                    activeChannel = i;
                    MashAudioEngine.playClick();
                    notifyServerChannelSwitch(mc, prev, activeChannel);
                    if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                        mc.ingameGUI.getChatGUI().refreshChat();
                    }
                }
                return true;
            }
            curX += tabW + 3;
        }
        return false;
    }

    private static void notifyServerChannelSwitch(Minecraft mc, int prevChannel, int newChannel) {
        if (!MashFlags.hypChatTabsAutoPrefix || mc == null || mc.thePlayer == null) {
            return;
        }
        if (!isHypixelServer(mc)) {
            return;
        }
        if (newChannel == CHANNEL_PARTY) {
            mc.thePlayer.sendChatMessage("/chat p");
        } else if (prevChannel == CHANNEL_PARTY && (newChannel == CHANNEL_ALL || newChannel == CHANNEL_TEAM)) {
            mc.thePlayer.sendChatMessage("/chat a");
        }
    }

    public static void sendRoutedChatMessage(GuiScreen chatScreen, String rawMessage) {
        if (chatScreen == null || rawMessage == null) {
            return;
        }
        String transformed = transformOutgoingChatMessage(rawMessage);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
            mc.ingameGUI.getChatGUI().addToSentMessages(rawMessage);
        }
        if (mc != null && mc.thePlayer != null && transformed != null && !transformed.trim().isEmpty()) {
            try {
                if (net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, transformed) != 0) {
                    return;
                }
            } catch (Throwable ignored) {
            }
            mc.thePlayer.sendChatMessage(transformed);
        }
    }

    public static String transformOutgoingChatMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty() || !isModuleActive() || !MashFlags.hypChatTabsAutoPrefix) {
            return rawMessage;
        }
        String trimmed = rawMessage.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("/")) {
            return rawMessage;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean singleplayer = mc != null && mc.isSingleplayer();
        if (activeChannel == CHANNEL_PARTY) {
            return singleplayer ? ("[Party] " + rawMessage) : ("/pc " + rawMessage);
        }
        if (activeChannel == CHANNEL_TEAM) {
            if (singleplayer) {
                return "[Team] " + rawMessage;
            }
            return isHypixelServer(mc) ? rawMessage : ("/tc " + rawMessage);
        }
        return rawMessage;
    }

    public static boolean shouldDisplayChatLine(String unformattedText) {
        if (!isModuleActive() || !MashFlags.hypChatTabsFilter || activeChannel == CHANNEL_ALL) {
            return true;
        }
        if (unformattedText == null || unformattedText.isEmpty()) {
            return true;
        }
        String low = unformattedText.toLowerCase(Locale.ROOT).trim();
        if (activeChannel == CHANNEL_PARTY) {
            return low.startsWith("party >")
                    || low.startsWith("[party]")
                    || low.startsWith("groupe >")
                    || low.startsWith("p >")
                    || low.contains("party >")
                    || low.contains("[party]")
                    || low.contains("joined the party")
                    || low.contains("left the party")
                    || low.contains("you are not in a party")
                    || low.contains("party chat")
                    || low.contains("party leader")
                    || low.contains("party members");
        }
        if (activeChannel == CHANNEL_TEAM) {
            return low.startsWith("[team]")
                    || low.startsWith("team >")
                    || low.startsWith("[équipe]")
                    || low.startsWith("[equipe]")
                    || low.contains("[team]")
                    || low.contains("team >")
                    || (!low.startsWith("[shout]")
                    && !low.startsWith("party >")
                    && !low.startsWith("[party]")
                    && !low.startsWith("guild >")
                    && !low.startsWith("[guild]"));
        }
        return true;
    }

    private static boolean isHypixelServer(Minecraft mc) {
        try {
            if (mc == null) {
                return false;
            }
            ServerData data = mc.getCurrentServerData();
            if (data == null || data.serverIP == null) {
                return false;
            }
            return data.serverIP.toLowerCase(Locale.ROOT).contains("hypixel");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
