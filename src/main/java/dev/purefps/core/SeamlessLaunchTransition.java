package dev.purefps.core;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.gui.GuiPureMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * Transition sans couture "Singularity" entre PureClient Launcher et Minecraft 1.8.9 :
 * 1. Désactive automatiquement le SplashProgress blanc de Forge dès startGame() HEAD pour que
 *    l'écran "Mojang" soit 100 % remplacé par la caméra du joueur qui sort du Trou Noir.
 * 2. Configure une fenêtre OpenGL sans bordure plein écran à (0, 0) à la résolution native du bureau
 *    et la place au premier plan Windows (SetWindowPos TOPMOST -> NOTOPMOST + SetForegroundWindow)
 *    exactement à la place de l'écran noir du Launcher, tout en laissant Alt-Tab 100 % fonctionnel.
 * 3. Signale immédiatement DISPLAY_CREATED (.purefps_display_ready + .purefps_menu_ready + stdout)
 *    dès l'ouverture du contexte OpenGL #000000 pour libérer instantanément le rideau du Launcher.
 */
public final class SeamlessLaunchTransition {

    public static final String HANDOFF_FILE_NAME = ".purefps_handoff";
    public static final String DISPLAY_READY_FILE_NAME = ".purefps_display_ready";
    public static final String READY_FILE_NAME = ".purefps_menu_ready";

    private static final long MAX_HANDOFF_AGE_MS = 180_000L;
    private static final float EMERGENCE_DURATION_MS = 650.0F;

    private static boolean checked = false;
    private static boolean active = false;
    private static boolean displaySignaled = false;
    private static boolean readySignaled = false;
    private static boolean curtainFinished = false;
    private static int menuFramesRendered = 0;
    private static long emergenceStartMs = 0L;

    public interface Win32User32 extends StdCallLibrary {
        boolean ShowWindow(Pointer hWnd, int nCmdShow);
        boolean SetWindowPos(Pointer hWnd, Pointer hWndInsertAfter, int x, int y, int cx, int cy, int uFlags);
        boolean SetForegroundWindow(Pointer hWnd);
        boolean BringWindowToTop(Pointer hWnd);
        Pointer SetFocus(Pointer hWnd);
    }

    private SeamlessLaunchTransition() {
    }

    static void resetForTest() {
        checked = false;
        active = false;
        displaySignaled = false;
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
                    //noinspection ResultOfMethodCallIgnored
                    handoff.delete();
                }
            }
        }
        active = propRequested || fileRequested;
        return active;
    }

    /**
     * Appelé tout au début de Minecraft.startGame() AVANT setInitialDisplayMode(), createDisplay()
     * et FMLClientHandler.beginMinecraftLoading() :
     * - Désactive l'écran blanc Mojang/Forge (splash.properties -> enabled=false) pour laisser place
     *   à la caméra qui émerge du Trou Noir.
     * - Prépare le mode plein écran sans bordure si lancé depuis PureClient Launcher.
     */
    public static void onStartGameHead(Minecraft mc) {
        BlackHoleEmergenceRenderer.beginStartup();
        File dataDir = mc != null ? mc.mcDataDir : null;
        if (PureFPSConfig.enabled) {
            ensureForgeWhiteSplashDisabled(dataDir);
        }
        if (!checked) {
            checked = true;
            boolean propRequested = "true".equalsIgnoreCase(System.getProperty("purefps.seamlessLaunch"));
            evaluateHandoffActivation(propRequested, dataDir, System.currentTimeMillis());
        }
        if (active) {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
        }
    }

    public static void ensureForgeWhiteSplashDisabled(File mcDataDir) {
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
            Properties props = new Properties();
            if (splashFile.isFile()) {
                try (FileInputStream fis = new FileInputStream(splashFile)) {
                    props.load(fis);
                } catch (Throwable ignored) {
                }
                if ("false".equalsIgnoreCase(props.getProperty("enabled"))) {
                    return;
                }
            }
            String content = "# PureClient Singularity Black Hole Emergence Splash\n"
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

    /**
     * Remplace setInitialDisplayMode() lors d'un lancement sans couture pour ouvrir une vraie fenêtre
     * sans bordure à (0, 0) à la résolution exacte du moniteur principal (sans ChangeDisplaySettings exclusif).
     */
    public static boolean applyBorderlessDisplayMode(Minecraft mc) {
        if (!checked) {
            onStartGameHead(mc);
        }
        if (!active) {
            return false;
        }
        try {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
            DisplayMode desktop = Display.getDesktopDisplayMode();
            int targetW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : (mc != null ? mc.displayWidth : 1920);
            int targetH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : (mc != null ? mc.displayHeight : 1080);

            if (mc != null) {
                mc.displayWidth = targetW;
                mc.displayHeight = targetH;
            }
            Display.setFullscreen(false);
            Display.setDisplayMode(new DisplayMode(targetW, targetH));
            Display.setLocation(0, 0);
            Display.setResizable(false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Appelé immédiatement après Display.create() :
     * - Vide les deux tampons OpenGL en noir pur (#000000).
     * - Place la fenêtre Minecraft exactement à (0, 0) au premier plan à la place de l'écran noir du Launcher.
     * - Signale immédiatement DISPLAY_CREATED au Launcher pour qu'il retire son rideau noir sans délai.
     */
    public static void onDisplayCreated(Minecraft mc) {
        int w = mc != null ? Math.max(1, mc.displayWidth) : 1920;
        int h = mc != null ? Math.max(1, mc.displayHeight) : 1080;

        if (active || PureFPSConfig.enabled) {
            try {
                if (active) {
                    Display.setLocation(0, 0);
                }
                GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                Display.update();
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            } catch (Throwable ignored) {
            }
        }

        if (active) {
            bringLwjglWindowToFrontWindows(w, h);
            signalDisplayCreated(mc != null ? mc.mcDataDir : null);
        }
    }

    private static void bringLwjglWindowToFrontWindows(int width, int height) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        try {
            long hwndVal = extractLwjglHwnd();
            if (hwndVal == 0L) {
                return;
            }
            Win32User32 user32 = (Win32User32) Native.loadLibrary("user32", Win32User32.class);
            Pointer hwnd = new Pointer(hwndVal);
            Pointer hwndTopmost = new Pointer(-1L);
            Pointer hwndNoTopmost = new Pointer(-2L);
            int swpShowWindow = 0x0040;

            user32.ShowWindow(hwnd, 5); // SW_SHOW
            // Passe brièvement TOPMOST puis NOTOPMOST à (0, 0) : place Minecraft tout devant le bureau
            // sans jamais bloquer Alt-Tab vers les autres applications !
            user32.SetWindowPos(hwnd, hwndTopmost, 0, 0, width, height, swpShowWindow);
            user32.SetWindowPos(hwnd, hwndNoTopmost, 0, 0, width, height, swpShowWindow);
            user32.BringWindowToTop(hwnd);
            user32.SetForegroundWindow(hwnd);
            user32.SetFocus(hwnd);
        } catch (Throwable ignored) {
        }
    }

    private static long extractLwjglHwnd() {
        try {
            Field implField = Display.class.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            if (impl == null) {
                return 0L;
            }
            Method getHwnd = impl.getClass().getDeclaredMethod("getHwnd");
            getHwnd.setAccessible(true);
            Object val = getHwnd.invoke(impl);
            if (val instanceof Long) {
                return (Long) val;
            }
        } catch (Throwable ignored) {
        }
        return 0L;
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
        float inv = 1.0F - t;
        float alphaNorm = inv * inv * inv;
        return Math.max(0, Math.min(255, Math.round(alphaNorm * 255.0F)));
    }

    public static void renderEmergenceCurtainIfNeeded(GuiScreen gui) {
        if (gui == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        File dataDir = mc != null ? mc.mcDataDir : null;

        // Sur GuiPureMainMenu, c'est BlackHoleEmergenceRenderer qui pilote directement la sortie du Trou Noir !
        if (gui instanceof GuiPureMainMenu) {
            if (active && !readySignaled) {
                readySignaled = true;
                curtainFinished = true;
                signalLauncherReady(dataDir);
            }
            return;
        }

        if (!active || curtainFinished) {
            return;
        }
        int alpha = computeMenuCurtainAlphaAt(dataDir, System.currentTimeMillis());
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
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "false");
            try {
                Display.setResizable(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void signalDisplayCreated(File mcDataDir) {
        if (displaySignaled) {
            return;
        }
        displaySignaled = true;
        writeSignalFiles(mcDataDir);
        System.out.println("[PureClient-Ready] DISPLAY_CREATED");
        System.out.flush();
    }

    private static void signalLauncherReady(File mcDataDir) {
        writeSignalFiles(mcDataDir);
        System.out.println("[PureClient-Ready] MENU_ACTIVE");
        System.out.flush();
    }

    private static void writeSignalFiles(File mcDataDir) {
        try {
            if (mcDataDir != null) {
                File cfgDir = new File(mcDataDir, "config");
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();

                File dispReady = new File(cfgDir, DISPLAY_READY_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(dispReady)) {
                    fos.write("DISPLAY_READY\n".getBytes(StandardCharsets.UTF_8));
                    fos.getFD().sync();
                }

                File menuReady = new File(cfgDir, READY_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(menuReady)) {
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
    }
}
