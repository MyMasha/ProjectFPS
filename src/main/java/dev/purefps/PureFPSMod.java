package dev.purefps;

import dev.purefps.client.ClientEventBridge;
import dev.purefps.command.PureFPSCommand;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashModules;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.lwjgl.opengl.Display;

import java.io.File;

@Mod(
        modid = PureFPSMod.MODID,
        name = PureFPSMod.NAME,
        version = PureFPSMod.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*"
)
public class PureFPSMod {

    public static final String MODID = "purefps";
    public static final String NAME = "MashClient";
    public static final String VERSION = "4.1.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File mcDir = event.getModConfigurationDirectory().getParentFile();
        PureFPSConfig.init(mcDir);
        MashModules.init(mcDir);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEventBridge());
        ClientCommandHandler.instance.registerCommand(new PureFPSCommand());

        PureFPSConfig.syncStartupGameSettingsIfNeeded();
        if (Display.isCreated()) {
            Display.setTitle("MashClient 4.1 | Minecraft 1.8.9");
        }
    }
}
