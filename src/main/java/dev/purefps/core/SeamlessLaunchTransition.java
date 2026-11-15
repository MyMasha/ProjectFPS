package dev.purefps.core;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.gui.GuiPureMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Orchestrateur de la transition Plein Écran Sans Bordure MashClient 4.0 -> Minecraft 1.8.9 :
 * - Active le thread dédié Forge SplashProgress avec fond noir (#000000) pour que MixinSplashProgressThread
 *   anime la sortie du Trou Noir à 100 FPS constants sans aucun freeze pendant le chargement des ressources !
 * - Ouvre directement Minecraft en mode Borderless Windowed à (0, 0) via BorderlessWindowHelper et signale
 *   immédiatement DISPLAY_CREATED pour que le Launcher se réduise dans la zone de notification (en bas à droite).
 */
public final class SeamlessLaunchTransition {

    public static final String HANDOFF_FILE_NAME = ".purefps_handoff";
    public static final String DISPLAY_READY_FILE_NAME = ".purefps_display_ready";
    public static final String READY_FILE_NAME = ".purefps_menu_ready";
    public static final String DISPLAY_CREATED_SIGNAL = "[PureClient-Ready] DISPLAY_CREATED";
    public static final String READY_STDOUT_SIGNAL = "[PureClient-Ready] MENU_ACTIVE";

    private static final long HANDOFF_EXPIRY_MS = 120_000L;
    private static final float EMERGENCE_DURATION_MS = 650.0F;

    private static boolean checked = false;
    private static boolean active = false;
    private static boolean displaySignaled = false;
    private static boolean readySignaled = false;
    private static boolean curtainFinished = false;
    private static int menuFramesRendered = 0;
    private static long emergenceStartMs = -1L;
    private static long lastCursorCheckMs = 0L;

    private SeamlessLaunchTransition() {
    }

    public static void resetForTest() {
        checked = false;
        active = false;
        displaySignaled = false;
        readySignaled = false;
        curtainFinished = false;
        menuFramesRendered = 0;
        emergenceStartMs = -1L;
        lastCursorCheckMs = 0L;
    }

    public static boolean evaluateHandoffActivation(boolean jvmPropSet, File mcDataDir, long nowMs) {
        checked = true;
        boolean handoffFresh = false;
        if (mcDataDir != null) {
            File cfgDir = new File(mcDataDir, "config");
            File handoff = new File(cfgDir, HANDOFF_FILE_NAME);
            if (handoff.isFile()) {
                long age = nowMs - handoff.lastModified();
                if (age <= HANDOFF_EXPIRY_MS) {
                    handoffFresh = true;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    handoff.delete();
                }
            }
            File staleDisp = new File(cfgDir, DISPLAY_READY_FILE_NAME);
            if (staleDisp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                staleDisp.delete();
            }
            File staleMenu = new File(cfgDir, READY_FILE_NAME);
            if (staleMenu.exists()) {
                //noinspection ResultOfMethodCallIgnored
                staleMenu.delete();
            }
        }
        active = jvmPropSet || handoffFresh;
        return active;
    }

    public static void onStartGameHead(Minecraft mc) {
        if (checked) {
            return;
        }
        BlackHoleEmergenceRenderer.resetForBoot();

        File dataDir = mc != null ? mc.mcDataDir : null;
        ensureForgeSplashConfiguredDark(dataDir);

        boolean prop = Boolean.getBoolean("purefps.seamlessLaunch");
        evaluateHandoffActivation(prop, dataDir, System.currentTimeMillis());

        if (active || PureFPSConfig.borderlessFullscreen) {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
        }
    }

    /**
     * Désactive le thread SharedDrawable (wglShareLists) de Forge SplashProgress afin de préserver
     * 100 % des optimisations multithreadées du pilote NVIDIA (nvoglv64.dll) sur toute la session !
     * La fenêtre reste en #000000 pur pendant startGame(), puis émerge à 240 FPS dès GuiPureMainMenu.
     */
    public static void ensureForgeSplashConfiguredDark(File mcDataDir) {
        if (mcDataDir == null) {
            return;
        }
        try {
            File cfgDir = new File(mcDataDir, "config");
            if (!cfgDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();
            }
            File splashFile = new File(cfgDir, "splash.properties");
            String content = "# MashClient 4.5.0 — Single-Context DWM-Composed Fast Boot\n"
                    + "enabled=false\n"
                    + "background=0x000000\n"
                    + "font=0x000000\n"
                    + "barBackground=0x000000\n"
                    + "barBorder=0x000000\n"
                    + "bar=0x000000\n"
                    + "rotate=false\n";
            try (FileOutputStream fos = new FileOutputStream(splashFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean applyBorderlessDisplayMode(Minecraft mc) {
        if (!checked) {
            onStartGameHead(mc);
        }
        if (!active && !PureFPSConfig.borderlessFullscreen) {
            return false;
        }
        return BorderlessWindowHelper.applyInitialBorderless(mc);
    }

    public static boolean isActive() {
        return active;
    }

    public static void onDisplayCreated(Minecraft mc) {
        BorderlessWindowHelper.installBlackWindowBackground();

        if (active || PureFPSConfig.borderlessFullscreen) {
            // Sous rideau Launcher (active=true) : placement silencieux SWP_NOACTIVATE ; en lancement direct : activation immédiate
            BorderlessWindowHelper.ensurePostCreateBorderlessPlacement(mc, !active);
        }

        if (active || PureFPSConfig.enabled) {
            try {
                GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                Display.update();
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                Display.update();
            } catch (Throwable ignored) {
            }
        }

        BorderlessWindowHelper.ensureMenuCursorVisible();

        if (active) {
            signalDisplayCreated(mc != null ? mc.mcDataDir : null);
        }
    }

    public static int computeMenuCurtainAlphaAt(File mcDataDir, long nowMs) {
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
        float inv = 1.0F - t;
        float alphaNorm = inv * inv * inv;
        return Math.max(0, Math.min(255, Math.round(alphaNorm * 255.0F)));
    }

    public static void renderEmergenceCurtainIfNeeded(GuiScreen gui) {
        if (gui == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastCursorCheckMs >= 350L) {
            lastCursorCheckMs = nowMs;
            BorderlessWindowHelper.ensureMenuCursorVisible();
        }

        Minecraft mc = Minecraft.getMinecraft();
        File dataDir = mc != null ? mc.mcDataDir : null;

        if (gui instanceof GuiPureMainMenu) {
            if (active && !readySignaled && BlackHoleEmergenceRenderer.hasPresentedInitialBlackFrames()) {
                readySignaled = true;
                curtainFinished = true;
                BorderlessWindowHelper.ensureMenuCursorVisible();
                signalLauncherReady(dataDir);
            }
            return;
        }

        if (!active || curtainFinished) {
            return;
        }
        int alpha = computeMenuCurtainAlphaAt(dataDir, nowMs);
        if (alpha <= 0) {
            return;
        }
        GlStateManager.disableDepth();
        Gui.drawRect(0, 0, gui.width, gui.height, (alpha << 24));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void onUserToggleFullscreen() {
        if (active) {
            active = false;
            curtainFinished = true;
        }
    }

    private static void signalDisplayCreated(File mcDataDir) {
        if (displaySignaled) {
            return;
        }
        displaySignaled = true;
        try {
            if (mcDataDir != null) {
                File cfgDir = new File(mcDataDir, "config");
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();
                File dispReady = new File(cfgDir, DISPLAY_READY_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(dispReady)) {
                    fos.write("DISPLAY_READY\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
        System.out.println(DISPLAY_CREATED_SIGNAL);
        System.out.flush();
    }

    private static void signalLauncherReady(File mcDataDir) {
        writeMenuReadyFiles(mcDataDir);
        System.out.println(READY_STDOUT_SIGNAL);
        System.out.flush();
    }

    private static void writeMenuReadyFiles(File mcDataDir) {
        try {
            if (mcDataDir != null) {
                File cfgDir = new File(mcDataDir, "config");
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();

                File menuReady = new File(cfgDir, READY_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(menuReady)) {
                    fos.write("READY\n".getBytes(StandardCharsets.UTF_8));
                }

                File handoff = new File(cfgDir, HANDOFF_FILE_NAME);
                if (handoff.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    handoff.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
