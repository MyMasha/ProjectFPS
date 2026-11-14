package dev.purefps.core;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Gestionnaire natif de Plein Écran Sans Bordure ("Borderless Windowed") & Curseur Matériel Win32 :
 * - Remplace le plein écran exclusif LWJGL par une fenêtre sans bordure instantanée à (0, 0) couvrant
 *   exactement la résolution du bureau, avec pinceau de classe GDI #000000 (zéro flash blanc WM_ERASEBKGND).
 * - Corrige le bogue natif LWJGL 2.9.4 (nSetNativeCursor -> SetClassLongPtr(GCLP_HCURSOR, NULL)) qui
 *   rendait le curseur flèche Windows invisible dans les menus en mode sans bordure plein écran.
 */
public final class BorderlessWindowHelper {

    public interface Win32Borderless extends Library {
        int GWL_STYLE = -16;
        int GCLP_HBRBACKGROUND = -10;
        int GCLP_HCURSOR = -12;
        int WS_POPUP = 0x80000000;
        int WS_VISIBLE = 0x10000000;
        int WS_OVERLAPPEDWINDOW = 0x00CF0000;
        int SWP_NOZORDER = 0x0004;
        int SWP_NOACTIVATE = 0x0010;
        int SWP_FRAMECHANGED = 0x0020;
        int SWP_SHOWWINDOW = 0x0040;
        int SWP_NOOWNERZORDER = 0x0200;
        int CURSOR_SHOWING = 0x00000001;
        long IDC_ARROW_ID = 32512L;

        int SetWindowLongW(Pointer hWnd, int nIndex, int dwNewLong);

        int SetClassLongW(Pointer hWnd, int nIndex, int dwNewLong);

        boolean SetWindowPos(Pointer hWnd, Pointer hWndInsertAfter, int x, int y, int cx, int cy, int uFlags);

        boolean ShowWindow(Pointer hWnd, int nCmdShow);

        boolean BringWindowToTop(Pointer hWnd);

        boolean SetForegroundWindow(Pointer hWnd);

        Pointer LoadCursorW(Pointer hInstance, Pointer lpCursorName);

        Pointer SetCursor(Pointer hCursor);

        int ShowCursor(boolean bShow);

        boolean GetCursorInfo(Pointer pci);
    }

    public interface Win32ClassLong64 extends Library {
        Pointer SetClassLongPtrW(Pointer hWnd, int nIndex, Pointer dwNewLong);
    }

    public interface Win32Gdi extends Library {
        int BLACK_BRUSH = 4;

        Pointer GetStockObject(int fnObject);
    }

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static Win32Borderless cachedUser32;
    private static Win32ClassLong64 cachedClass64;
    private static Win32Gdi cachedGdi32;
    private static Pointer cachedArrowCursor;
    private static Pointer cachedBlackBrush;
    private static Memory cursorInfoBuffer;

    private static boolean currentlyBorderless = false;
    private static int savedWindowedW = 1280;
    private static int savedWindowedH = 720;

    private BorderlessWindowHelper() {
    }

    public static boolean isCurrentlyBorderless() {
        return currentlyBorderless;
    }

    private static Win32Borderless user32() {
        if (cachedUser32 == null && IS_WINDOWS) {
            cachedUser32 = (Win32Borderless) Native.loadLibrary("user32", Win32Borderless.class);
        }
        return cachedUser32;
    }

    private static Win32Gdi gdi32() {
        if (cachedGdi32 == null && IS_WINDOWS) {
            cachedGdi32 = (Win32Gdi) Native.loadLibrary("gdi32", Win32Gdi.class);
        }
        return cachedGdi32;
    }

    private static void setClassPointer(Pointer hwnd, int index, Pointer value) {
        if (hwnd == null || value == null || !IS_WINDOWS) {
            return;
        }
        try {
            if (Pointer.SIZE == 8) {
                if (cachedClass64 == null) {
                    cachedClass64 = (Win32ClassLong64) Native.loadLibrary("user32", Win32ClassLong64.class);
                }
                cachedClass64.SetClassLongPtrW(hwnd, index, value);
            } else {
                Win32Borderless u32 = user32();
                if (u32 != null) {
                    u32.SetClassLongW(hwnd, index, (int) Pointer.nativeValue(value));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Remplace le pinceau de fond blanc par défaut de la classe LWJGL (COLOR_WINDOW+1) par BLACK_BRUSH (#000000)
     * afin qu'aucun message WM_ERASEBKGND lors du redimensionnement sans bordure ne puisse flasher en blanc.
     */
    public static void installBlackWindowBackground() {
        if (!IS_WINDOWS || !Display.isCreated()) {
            return;
        }
        try {
            long hwndVal = extractLwjglHwnd();
            if (hwndVal == 0L) {
                return;
            }
            if (cachedBlackBrush == null) {
                Win32Gdi gdi = gdi32();
                if (gdi != null) {
                    cachedBlackBrush = gdi.GetStockObject(Win32Gdi.BLACK_BRUSH);
                }
            }
            if (cachedBlackBrush != null) {
                setClassPointer(new Pointer(hwndVal), Win32Borderless.GCLP_HBRBACKGROUND, cachedBlackBrush);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Restaure le curseur matériel flèche (IDC_ARROW) sur la classe de fenêtre LWJGL et dans l'état Win32
     * chaque fois qu'un menu est ouvert (contourne le bogue LWJGL 2.9.4 où nSetNativeCursor(null) écrase
     * GCLP_HCURSOR à NULL et laisse le curseur invisible en mode Borderless 1920x1080).
     */
    public static void ensureMenuCursorVisible() {
        if (!IS_WINDOWS || !Display.isCreated()) {
            return;
        }
        try {
            if (Mouse.isCreated() && Mouse.isGrabbed()) {
                return;
            }
            Win32Borderless u32 = user32();
            if (u32 == null) {
                return;
            }
            if (cachedArrowCursor == null) {
                cachedArrowCursor = u32.LoadCursorW(null, new Pointer(Win32Borderless.IDC_ARROW_ID));
            }
            long hwndVal = extractLwjglHwnd();
            if (hwndVal != 0L && cachedArrowCursor != null) {
                setClassPointer(new Pointer(hwndVal), Win32Borderless.GCLP_HCURSOR, cachedArrowCursor);
            }

            int cbSize = Pointer.SIZE == 8 ? 24 : 20;
            if (cursorInfoBuffer == null || cursorInfoBuffer.size() < cbSize) {
                cursorInfoBuffer = new Memory(cbSize);
            }
            cursorInfoBuffer.clear();
            cursorInfoBuffer.setInt(0, cbSize);
            if (u32.GetCursorInfo(cursorInfoBuffer)) {
                int flags = cursorInfoBuffer.getInt(4);
                if ((flags & Win32Borderless.CURSOR_SHOWING) == 0) {
                    for (int i = 0; i < 8; i++) {
                        if (u32.ShowCursor(true) >= 0) {
                            break;
                        }
                    }
                }
            }
            if (cachedArrowCursor != null) {
                u32.SetCursor(cachedArrowCursor);
            }
        } catch (Throwable ignored) {
        }
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
        applyWin32WindowStyle(true, 0, 0, w, h, bringToFront, !bringToFront);
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
                applyWin32WindowStyle(true, 0, 0, deskW, deskH, false, false);
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
                applyWin32WindowStyle(false, posX, posY, winW, winH, false, false);
                currentlyBorderless = false;
                if (mc != null) {
                    mc.displayWidth = winW;
                    mc.displayHeight = winH;
                    mc.resize(winW, winH);
                }
            }
            Display.setVSyncEnabled(mc != null && mc.gameSettings != null && mc.gameSettings.enableVsync);
            ensureMenuCursorVisible();
            if (mc != null) {
                mc.updateDisplay();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyWin32WindowStyle(
            boolean borderless,
            int x,
            int y,
            int width,
            int height,
            boolean bringToFront,
            boolean silentBehindCurtain
    ) {
        if (!IS_WINDOWS) {
            return;
        }
        try {
            long hwndVal = extractLwjglHwnd();
            if (hwndVal == 0L) {
                return;
            }
            installBlackWindowBackground();
            Win32Borderless u32 = user32();
            if (u32 == null) {
                return;
            }
            Pointer hwnd = new Pointer(hwndVal);
            int style = borderless
                    ? (Win32Borderless.WS_POPUP | Win32Borderless.WS_VISIBLE)
                    : (Win32Borderless.WS_OVERLAPPEDWINDOW | Win32Borderless.WS_VISIBLE);
            u32.SetWindowLongW(hwnd, Win32Borderless.GWL_STYLE, style);

            if (silentBehindCurtain) {
                int bgFlags = Win32Borderless.SWP_FRAMECHANGED
                        | Win32Borderless.SWP_NOZORDER
                        | Win32Borderless.SWP_NOACTIVATE
                        | Win32Borderless.SWP_NOOWNERZORDER;
                u32.SetWindowPos(hwnd, null, x, y, width, height, bgFlags);
                return;
            }

            Pointer hwndTopmost = new Pointer(-1L);
            Pointer hwndNoTopmost = new Pointer(-2L);
            int flags = Win32Borderless.SWP_FRAMECHANGED
                    | Win32Borderless.SWP_SHOWWINDOW
                    | Win32Borderless.SWP_NOOWNERZORDER;

            u32.ShowWindow(hwnd, 5);
            if (bringToFront) {
                u32.SetWindowPos(hwnd, hwndTopmost, x, y, width, height, flags);
            }
            u32.SetWindowPos(hwnd, hwndNoTopmost, x, y, width, height, flags);
            if (bringToFront) {
                u32.BringWindowToTop(hwnd);
                u32.SetForegroundWindow(hwnd);
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
