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
    public static final String VERSION = "4.3.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File mcDir = event.getModConfigurationDirectory().getParentFile();
        PureFPSConfig.init(mcDir);
        MashModules.init(mcDir);
        dev.purefps.client.audio.MashAudioEngine.ensurePrewarmed();
        applyMashWindowIcon();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEventBridge());
        ClientCommandHandler.instance.registerCommand(new PureFPSCommand());

        PureFPSConfig.syncStartupGameSettingsIfNeeded();
        if (Display.isCreated()) {
            Display.setTitle("MashClient | Minecraft 1.8.9");
            applyMashWindowIcon();
        }
    }

    private static void applyMashWindowIcon() {
        if (!Display.isCreated()) {
            return;
        }
        try {
            java.nio.ByteBuffer icon16 = loadIconRgbaBuffer("/assets/purefps/textures/gui/mash_icon_16.png");
            java.nio.ByteBuffer icon32 = loadIconRgbaBuffer("/assets/purefps/textures/gui/mash_icon_32.png");
            if (icon16 != null && icon32 != null) {
                Display.setIcon(new java.nio.ByteBuffer[]{icon16, icon32});
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.nio.ByteBuffer loadIconRgbaBuffer(String resourcePath) throws Exception {
        try (java.io.InputStream in = PureFPSMod.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = new int[w * h];
            img.getRGB(0, 0, w, h, argb, 0, w);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            for (int px : argb) {
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
                buf.put((byte) ((px >>> 24) & 0xFF));
            }
            buf.flip();
            return buf;
        }
    }
}
