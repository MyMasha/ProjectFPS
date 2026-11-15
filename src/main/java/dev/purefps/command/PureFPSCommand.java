package dev.purefps.command;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.gui.GuiMashClientMenu;
import dev.purefps.module.MashModules;
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
        return "mashclient";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("mash", "pureclient", "purefps", "pc");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/mashclient [fps|balanced|quality|hud|status|toggle]";
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
            if ("copyshot".equalsIgnoreCase(sub) || "copyscreenshot".equalsIgnoreCase(sub)) {
                if (args.length > 1) {
                    StringBuilder joined = new StringBuilder();
                    for (int i = 1; i < args.length; i++) {
                        if (i > 1) joined.append(' ');
                        joined.append(args[i]);
                    }
                    dev.purefps.core.AsyncScreenshotManager.copyScreenshotToClipboardAsync(joined.toString());
                }
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
                        "\u00a76[MashClient 4.5.6] \u00a7fClient & Optimizations: "
                                + (PureFPSConfig.enabled
                                ? (PureFPSConfig.isEnglish() ? "\u00a7aENABLED" : "\u00a7aACTIVÉS")
                                : (PureFPSConfig.isEnglish() ? "\u00a7cDISABLED" : "\u00a7cDÉSACTIVÉS"))
                ));
                return;
            }
            if ("status".equalsIgnoreCase(sub)) {
                sender.addChatMessage(new ChatComponentText(
                        "\u00a76[MashClient 4.5.6] \u00a77" + (PureFPSConfig.isEnglish() ? "Profile: " : "Profil: ") + PureFPSConfig.activeProfile.getColoredName()
                                + " \u00a77| Backend: " + dev.purefps.core.VulkanBackendHelper.getF3BackendBadge()
                                + " \u00a77| Modules: \u00a7e" + MashModules.getActiveCount() + "/203"
                                + " \u00a77| MultiDraw: \u00a7a" + PureFPSConfig.chunksBatchedLastFrame + "c->" + PureFPSConfig.multiDrawsLastFrame + "d"
                                + " \u00a77| Culled E:\u00a7a" + PureFPSConfig.culledEntitiesLastFrame
                                + " \u00a77TE:\u00a7a" + PureFPSConfig.culledTileEntitiesLastFrame
                                + " \u00a77P:\u00a7a" + PureFPSConfig.culledParticlesLastFrame
                ));
                return;
            }
        }

        openGuiNextTick(false);
    }

    private static void openGuiNextTick(final boolean focusHudTab) {
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onClientTick(TickEvent.ClientTickEvent event) {
                if (event.phase == TickEvent.Phase.END) {
                    MinecraftForge.EVENT_BUS.unregister(this);
                    Minecraft.getMinecraft().displayGuiScreen(new GuiMashClientMenu(null, focusHudTab));
                }
            }
        });
    }

    private static void applyAndNotify(ICommandSender sender, PureFPSProfile profile) {
        PureFPSConfig.applyProfile(profile, true);
        MashModules.syncFromPureConfig();
        PureFPSConfig.save();
        sender.addChatMessage(new ChatComponentText(
                "\u00a76[MashClient 4.5.6] \u00a77" + (PureFPSConfig.isEnglish() ? "Applied profile: " : "Profil appliqué : ")
                        + profile.getColoredName() + " \u00a78(\u00a77" + profile.getDescription() + "\u00a78)"
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
