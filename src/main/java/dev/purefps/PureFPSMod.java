package dev.purefps;

import dev.purefps.client.ClientEventBridge;
import dev.purefps.command.PureFPSCommand;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.lwjgl.opengl.Display;

@Mod(modid = PureFPSMod.MODID, name = PureFPSMod.NAME, version = PureFPSMod.VERSION, clientSideOnly = true)
public class PureFPSMod {

    public static final String MODID = "purefps";
    public static final String NAME = "PureClient";
    public static final String VERSION = "3.5.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PureFPSConfig.init(event.getModConfigurationDirectory().getParentFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEventBridge());
        ClientCommandHandler.instance.registerCommand(new PureFPSCommand());

        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null && OpenGlHelper.vboSupported && !mc.gameSettings.useVbo) {
            mc.gameSettings.useVbo = true;
            mc.gameSettings.saveOptions();
        }
        if (Display.isCreated()) {
            Display.setTitle("PureClient 3.5 | Minecraft 1.8.9");
        }
    }
}
