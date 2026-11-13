package dev.purefps.command;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.gui.GuiHudEditor;
import dev.purefps.gui.GuiPureFPSSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Arrays;
import java.util.List;

public class PureFPSCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "pureclient";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("purefps", "pc");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/pureclient [fps|balanced|quality|hud|status|toggle]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 0) {
            String sub = args[0];
            if ("profile".equalsIgnoreCase(sub) && args.length > 1) {
                sub = args[1];
            }

            if ("fps".equalsIgnoreCase(sub)) {
                applyAndNotify(sender, PureFPSProfile.FPS);
                return;
            }
            if ("balanced".equalsIgnoreCase(sub) || "equilibre".equalsIgnoreCase(sub)) {
                applyAndNotify(sender, PureFPSProfile.BALANCED);
                return;
            }
            if ("quality".equalsIgnoreCase(sub) || "qualite".equalsIgnoreCase(sub)) {
                applyAndNotify(sender, PureFPSProfile.QUALITY);
                return;
            }
            if ("hud".equalsIgnoreCase(sub)) {
                openGuiNextTick(true);
                return;
            }
            if ("toggle".equalsIgnoreCase(sub)) {
                PureFPSConfig.enabled = !PureFPSConfig.enabled;
                PureFPSConfig.refreshProfileAfterManualEdit();
                PureFPSConfig.save();
                if (Minecraft.getMinecraft().renderGlobal != null) {
                    Minecraft.getMinecraft().renderGlobal.loadRenderers();
                }
                sender.addChatMessage(new ChatComponentText(
                        "\u00a7b[PureClient 3.5] \u00a7fClient & Optimisations: " + (PureFPSConfig.enabled ? "\u00a7aACTIVÉS" : "\u00a7cDÉSACTIVÉS")
                ));
                return;
            }
            if ("status".equalsIgnoreCase(sub)) {
                sender.addChatMessage(new ChatComponentText(
                        "\u00a7b[PureClient 3.5] \u00a77Profil: " + PureFPSConfig.activeProfile.getColoredName()
                                + " \u00a77| VAO+MultiDraw: \u00a7a" + PureFPSConfig.chunksBatchedLastFrame + "c->" + PureFPSConfig.multiDrawsLastFrame + "d"
                                + " \u00a77| LazyBFS:\u00a7a" + (PureFPSConfig.lazyTerrainSetup ? "ON" : "OFF")
                                + " \u00a77| Culled E:\u00a7a" + PureFPSConfig.culledEntitiesLastFrame
                                + " \u00a77TE:\u00a7a" + PureFPSConfig.culledTileEntitiesLastFrame
                                + " \u00a77P:\u00a7a" + PureFPSConfig.culledParticlesLastFrame
                ));
                return;
            }
        }

        openGuiNextTick(false);
    }

    private static void openGuiNextTick(final boolean hudEditor) {
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) {
                    MinecraftForge.EVENT_BUS.unregister(this);
                    if (hudEditor) {
                        Minecraft.getMinecraft().displayGuiScreen(new GuiHudEditor(null));
                    } else {
                        Minecraft.getMinecraft().displayGuiScreen(new GuiPureFPSSettings(null));
                    }
                }
            }
        });
    }

    private static void applyAndNotify(ICommandSender sender, PureFPSProfile profile) {
        PureFPSConfig.applyProfile(profile, true);
        PureFPSConfig.save();
        sender.addChatMessage(new ChatComponentText(
                "\u00a7b[PureClient 3.5] \u00a77Profil appliqué : " + profile.getColoredName() + " \u00a78(\u00a77" + profile.getDescription() + "\u00a78)"
        ));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "fps", "balanced", "quality", "hud", "profile", "status", "toggle");
        }
        if (args.length == 2 && "profile".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "fps", "balanced", "quality");
        }
        return null;
    }
}
