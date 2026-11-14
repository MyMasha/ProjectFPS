package dev.purefps.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Gestionnaire natif de Plein Écran Sans Bordure ("Borderless Windowed") pour MashClient 4.0 :
 * - Remplace le plein écran exclusif LWJGL (qui cause un écran noir moniteur de 1-2s et casse Alt-Tab)
 *   par une fenêtre sans bordure instantanée à (0, 0) couvrant exactement la résolution du bureau.
 * - Permet à F11 d'alterner instantanément entre Plein Écran Sans Bordure et Fenêtré décoré (1280x720)
 *   via User32 SetWindowLongW / SetWindowPos sans recréer le contexte OpenGL !
 */
public final class BorderlessWindowHelper {

    public interface Win32Borderless extends Library {
        int GWL_STYLE = -16;
        int WS_POPUP = 0x80000000;
        int WS_VISIBLE = 0x10000000;
        int WS_OVERLAPPEDWINDOW = 0x00CF0000;
        int SWP_FRAMECHANGED = 0x0020;
        int SWP_SHOWWINDOW = 0x0040;
        int SWP_NOOWNERZORDER = 0x0200;

        int SetWindowLongW(Pointer hWnd, int nIndex, int dwNewLong);

        boolean SetWindowPos(Pointer hWnd, Pointer hWndInsertAfter, int x, int y, int cx, int cy, int uFlags);

        boolean ShowWindow(Pointer hWnd, int nCmdShow);

        boolean BringWindowToTop(Pointer hWnd);

        boolean SetForegroundWindow(Pointer hWnd);
    }

    private static boolean currentlyBorderless = false;
    private static int savedWindowedW = 1280;
    private static int savedWindowedH = 720;

    private BorderlessWindowHelper() {
    }

    public static boolean isCurrentlyBorderless() {
        return currentlyBorderless;
    }

    public static boolean applyInitialBorderless(Minecraft mc) {
        try {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
            DisplayMode desktop = Display.getDesktopDisplayMode();
            int targetW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : 1920;
            int targetH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : 1080;

            if (mc != null) {
                if (mc.displayWidth > 0 && mc.displayWidth < targetW) {
                    savedWindowedW = mc.displayWidth;
                }
                if (mc.displayHeight > 0 && mc.displayHeight < targetH) {
                    savedWindowedH = mc.displayHeight;
                }
                mc.displayWidth = targetW;
                mc.displayHeight = targetH;
            }

            Display.setFullscreen(false);
            Display.setDisplayMode(new DisplayMode(targetW, targetH));
            Display.setLocation(0, 0);
            Display.setResizable(false);
            currentlyBorderless = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void ensurePostCreateBorderlessPlacement(Minecraft mc, boolean bringToFront) {
        DisplayMode desktop = Display.getDesktopDisplayMode();
        int w = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : (mc != null ? mc.displayWidth : 1920);
        int h = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : (mc != null ? mc.displayHeight : 1080);
        currentlyBorderless = true;
        applyWin32WindowStyle(true, 0, 0, w, h, bringToFront);
    }

    /**
     * Bascule instantanément F11 entre Plein Écran Sans Bordure (0, 0) et Fenêtré décoré centré.
     */
    public static boolean toggleBorderlessFullscreen(Minecraft mc) {
        try {
            DisplayMode desktop = Display.getDesktopDisplayMode();
            int deskW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : 1920;
            int deskH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : 1080;

            if (!currentlyBorderless) {
                if (Display.getWidth() < deskW && Display.getHeight() < deskH) {
                    savedWindowedW = Math.max(854, Display.getWidth());
                    savedWindowedH = Math.max(480, Display.getHeight());
                }
                System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
                Display.setFullscreen(false);
                Display.setResizable(false);
                Display.setDisplayMode(new DisplayMode(deskW, deskH));
                Display.setLocation(0, 0);
                applyWin32WindowStyle(true, 0, 0, deskW, deskH, false);
                currentlyBorderless = true;
                if (mc != null) {
                    mc.displayWidth = deskW;
                    mc.displayHeight = deskH;
                    mc.resize(deskW, deskH);
                }
            } else {
                int winW = Math.min(savedWindowedW, Math.max(854, deskW - 120));
                int winH = Math.min(savedWindowedH, Math.max(480, deskH - 120));
                int posX = Math.max(0, (deskW - winW) / 2);
                int posY = Math.max(0, (deskH - winH) / 2);

                System.setProperty("org.lwjgl.opengl.Window.undecorated", "false");
                Display.setFullscreen(false);
                Display.setDisplayMode(new DisplayMode(winW, winH));
                Display.setResizable(true);
                Display.setLocation(posX, posY);
                applyWin32WindowStyle(false, posX, posY, winW, winH, false);
                currentlyBorderless = false;
                if (mc != null) {
                    mc.displayWidth = winW;
                    mc.displayHeight = winH;
                    mc.resize(winW, winH);
                }
            }
            Display.setVSyncEnabled(mc != null && mc.gameSettings != null && mc.gameSettings.enableVsync);
            if (mc != null) {
                mc.updateDisplay();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyWin32WindowStyle(boolean borderless, int x, int y, int width, int height, boolean bringToFront) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        try {
            long hwndVal = extractLwjglHwnd();
            if (hwndVal == 0L) {
                return;
            }
            Win32Borderless user32 = (Win32Borderless) Native.loadLibrary("user32", Win32Borderless.class);
            Pointer hwnd = new Pointer(hwndVal);
            int style = borderless
                    ? (Win32Borderless.WS_POPUP | Win32Borderless.WS_VISIBLE)
                    : (Win32Borderless.WS_OVERLAPPEDWINDOW | Win32Borderless.WS_VISIBLE);
            user32.SetWindowLongW(hwnd, Win32Borderless.GWL_STYLE, style);

            Pointer hwndTopmost = new Pointer(-1L);
            Pointer hwndNoTopmost = new Pointer(-2L);
            int flags = Win32Borderless.SWP_FRAMECHANGED | Win32Borderless.SWP_SHOWWINDOW | Win32Borderless.SWP_NOOWNERZORDER;

            user32.ShowWindow(hwnd, 5);
            if (bringToFront) {
                user32.SetWindowPos(hwnd, hwndTopmost, x, y, width, height, flags);
            }
            user32.SetWindowPos(hwnd, hwndNoTopmost, x, y, width, height, flags);
            if (bringToFront) {
                user32.BringWindowToTop(hwnd);
                user32.SetForegroundWindow(hwnd);
            }
        } catch (Throwable ignored) {
        }
    }

    public static long extractLwjglHwnd() {
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
}
