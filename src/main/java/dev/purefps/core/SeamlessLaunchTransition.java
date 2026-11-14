package dev.purefps.core;

import net.minecraft.client.Minecraft;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Transition sans couture "Event Horizon" entre PureClient Launcher et Minecraft 1.8.9 :
 * - Détecte le jeton de lancement (.purefps_handoff ou -Dpurefps.seamlessLaunch=true).
 * - Force une fenêtre OpenGL sans bordure plein écran (0, 0, résolution native du moniteur)
 *   pour éviter tout clignotement Windows ChangeDisplaySettings ou bordure blanche 854x480.
 * - Remplace l'écran blanc Mojang/Forge par un noir absolu (#000000) identique au rideau du Launcher.
 * - Signale au Launcher (fichier .purefps_menu_ready + stdout) l'instant exact où GuiPureMainMenu
 *   est prêt à émerger du noir.
 */
public final class SeamlessLaunchTransition {

    private static final String HANDOFF_FILE_NAME = ".purefps_handoff";
    private static final String READY_FILE_NAME = ".purefps_menu_ready";
    private static final long MAX_HANDOFF_AGE_MS = 120_000L;
    private static final float EMERGENCE_DURATION_MS = 850.0F;

    private static boolean checked = false;
    private static boolean active = false;
    private static boolean readySignaled = false;
    private static boolean curtainFinished = false;
    private static int menuFramesRendered = 0;
    private static long emergenceStartMs = 0L;

    private SeamlessLaunchTransition() {
    }

    static void resetForTest() {
        checked = false;
        active = false;
        readySignaled = false;
        curtainFinished = false;
        menuFramesRendered = 0;
        emergenceStartMs = 0L;
    }

    static boolean evaluateHandoffActivation(boolean propRequested, File mcDataDir, long nowMs) {
        boolean fileRequested = false;
        if (mcDataDir != null) {
            File handoff = new File(new File(mcDataDir, "config"), HANDOFF_FILE_NAME);
            if (handoff.isFile()) {
                long age = Math.abs(nowMs - handoff.lastModified());
                if (age <= MAX_HANDOFF_AGE_MS) {
                    fileRequested = true;
                } else {
                    // Ancien jeton orphelin : nettoyage silencieux
                    //noinspection ResultOfMethodCallIgnored
                    handoff.delete();
                }
            }
        }
        active = propRequested || fileRequested;
        return active;
    }

    public static boolean checkAndPrepareBeforeDisplay(Minecraft mc) {
        if (checked) {
            return active;
        }
        checked = true;

        boolean propRequested = "true".equalsIgnoreCase(System.getProperty("purefps.seamlessLaunch"));
        File dataDir = mc != null ? mc.mcDataDir : null;
        if (!evaluateHandoffActivation(propRequested, dataDir, System.currentTimeMillis())) {
            return false;
        }

        try {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
            DisplayMode desktop = Display.getDesktopDisplayMode();
            if (desktop != null && desktop.getWidth() > 0 && desktop.getHeight() > 0) {
                if (mc != null) {
                    mc.displayWidth = desktop.getWidth();
                    mc.displayHeight = desktop.getHeight();
                }
                Display.setDisplayMode(new DisplayMode(desktop.getWidth(), desktop.getHeight()));
            }
            Display.setLocation(0, 0);
            Display.setResizable(false);
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void clearDisplayToPureBlack() {
        if (!active) {
            return;
        }
        try {
            Display.setLocation(0, 0);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            Display.update();
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        } catch (Throwable ignored) {
        }
    }

    public static boolean renderBlackSplashIfSeamless() throws LWJGLException {
        if (!active) {
            return false;
        }
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        Display.update();
        return true;
    }

    /**
     * Retourne l'opacité [0..255] du voile noir d'émergence affiché par-dessus le premier écran GUI
     * (GuiPureMainMenu, GuiMainMenu ou GuiConnecting en Quick-Join), et notifie le Launcher dès que
     * le tampon OpenGL noir du menu est prêt en façade.
     */
    public static int computeMenuCurtainAlphaAndSignal(File mcDataDir) {
        return computeMenuCurtainAlphaAt(mcDataDir, System.currentTimeMillis());
    }

    static int computeMenuCurtainAlphaAt(File mcDataDir, long nowMs) {
        if (!active || curtainFinished) {
            return 0;
        }
        menuFramesRendered++;
        if (!readySignaled && menuFramesRendered >= 2) {
            readySignaled = true;
            emergenceStartMs = nowMs;
            signalLauncherReady(mcDataDir);
        }
        if (!readySignaled) {
            return 255;
        }
        long elapsed = Math.max(0L, nowMs - emergenceStartMs);
        if (elapsed >= (long) EMERGENCE_DURATION_MS) {
            curtainFinished = true;
            return 0;
        }
        float t = Math.max(0.0F, Math.min(1.0F, (float) elapsed / EMERGENCE_DURATION_MS));
        // Courbe ease-out cubique douce depuis le noir absolu (#000000)
        float inv = 1.0F - t;
        float alphaNorm = inv * inv * inv;
        return Math.max(0, Math.min(255, Math.round(alphaNorm * 255.0F)));
    }

    public static void renderEmergenceCurtainIfNeeded(net.minecraft.client.gui.GuiScreen gui) {
        if (!active || curtainFinished || gui == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int alpha = computeMenuCurtainAlphaAndSignal(mc != null ? mc.mcDataDir : null);
        if (alpha <= 0) {
            return;
        }
        net.minecraft.client.renderer.GlStateManager.disableDepth();
        net.minecraft.client.gui.Gui.drawRect(0, 0, gui.width, gui.height, (alpha << 24));
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void onUserToggleFullscreen() {
        if (active) {
            active = false;
            curtainFinished = true;
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "false");
            try {
                Display.setResizable(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void signalLauncherReady(File mcDataDir) {
        try {
            if (mcDataDir != null) {
                File cfgDir = new File(mcDataDir, "config");
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();
                File ready = new File(cfgDir, READY_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(ready)) {
                    fos.write("READY\n".getBytes(StandardCharsets.UTF_8));
                    fos.getFD().sync();
                }
                File handoff = new File(cfgDir, HANDOFF_FILE_NAME);
                if (handoff.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    handoff.delete();
                }
            }
        } catch (Throwable ignored) {
        }
        System.out.println("[PureClient-Ready] MENU_ACTIVE");
        System.out.flush();
    }
}
