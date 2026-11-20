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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Gestionnaire natif de Plein Écran Sans Bordure ("Borderless Windowed") & Pont Curseur Win32/LWJGL :
 * - Remplace le plein écran exclusif LWJGL par une fenêtre sans bordure instantanée à (0, 0) couvrant
 *   exactement la résolution du bureau, avec pinceau de classe GDI #000000 (zéro flash blanc WM_ERASEBKGND).
 * - Fournit l'échantillonnage noyau direct (GetCursorPos + ScreenToClient + WindowFromPoint) à MenuCursorRenderer
 *   et synchronise WindowsDisplay.current_cursor pour éliminer tout double curseur ou disparition matérielle.
 */
public final class BorderlessWindowHelper {

    public interface Win32Borderless extends Library {
        int GWL_STYLE = -16;
        int GWL_EXSTYLE = -20;
        int GCLP_HBRBACKGROUND = -10;
        int GCLP_HCURSOR = -12;
        int WS_POPUP = 0x80000000;
        int WS_VISIBLE = 0x10000000;
        int WS_CLIPSIBLINGS = 0x04000000;
        int WS_CLIPCHILDREN = 0x02000000;
        int WS_OVERLAPPEDWINDOW = 0x00CF0000;
        int WS_EX_LAYERED = 0x00080000;
        int SWP_NOZORDER = 0x0004;
        int SWP_NOACTIVATE = 0x0010;
        int SWP_FRAMECHANGED = 0x0020;
        int SWP_SHOWWINDOW = 0x0040;
        int SWP_NOOWNERZORDER = 0x0200;
        int CURSOR_SHOWING = 0x00000001;
        int SM_XVIRTUALSCREEN = 76;
        int SM_YVIRTUALSCREEN = 77;
        int SM_CXVIRTUALSCREEN = 78;
        int SM_CYVIRTUALSCREEN = 79;
        long IDC_ARROW_ID = 32512L;

        int GetSystemMetrics(int nIndex);

        int GetWindowLongW(Pointer hWnd, int nIndex);

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

        boolean GetCursorPos(Pointer lpPoint);

        boolean ScreenToClient(Pointer hWnd, Pointer lpPoint);

        boolean GetClientRect(Pointer hWnd, Pointer lpRect);
    }

    public interface Win32ClassLong64 extends Library {
        Pointer SetClassLongPtrW(Pointer hWnd, int nIndex, Pointer dwNewLong);

        Pointer WindowFromPoint(long packedPoint);
    }

    public interface Win32Gdi extends Library {
        int BLACK_BRUSH = 4;

        Pointer GetStockObject(int fnObject);
    }

    public interface Win32Dwm extends Library {
        int DWM_BB_ENABLE = 0x00000001;

        int DwmEnableBlurBehindWindow(Pointer hWnd, Pointer pBlurBehind);
    }

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static Win32Borderless cachedUser32;
    private static Win32ClassLong64 cachedClass64;
    private static Win32Gdi cachedGdi32;
    private static Win32Dwm cachedDwmapi;
    private static Pointer cachedArrowCursor;
    private static ByteBuffer cachedArrowCursorBuffer;
    private static Pointer cachedBlackBrush;
    private static Memory cursorInfoBuffer;
    private static Memory pointBuffer;
    private static Memory clientRectBuffer;
    private static Memory dwmBlurBuffer;

    // Cache de réflexion LWJGL WindowsDisplay (zéro allocation par frame)
    private static boolean lwjglReflectionResolved = false;
    private static Field displayImplField;
    private static Field currentCursorField;
    private static Field windowsMouseField;
    private static Method getHwndMethod;
    private static Method getBlankCursorMethod;
    private static Method updateCursorMethod;

    private static Boolean clientHardwareCursorBlank = null;

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

    private static Win32ClassLong64 class64() {
        if (cachedClass64 == null && IS_WINDOWS && Pointer.SIZE == 8) {
            cachedClass64 = (Win32ClassLong64) Native.loadLibrary("user32", Win32ClassLong64.class);
        }
        return cachedClass64;
    }

    private static Win32Gdi gdi32() {
        if (cachedGdi32 == null && IS_WINDOWS) {
            cachedGdi32 = (Win32Gdi) Native.loadLibrary("gdi32", Win32Gdi.class);
        }
        return cachedGdi32;
    }

    private static Win32Dwm dwmapi() {
        if (cachedDwmapi == null && IS_WINDOWS) {
            String sysRoot = System.getenv("SystemRoot");
            if (sysRoot == null || sysRoot.trim().isEmpty()) {
                sysRoot = "C:\\Windows";
            }
            java.io.File sysDwm = new java.io.File(new java.io.File(sysRoot.trim(), "System32"), "dwmapi.dll");
            if (sysDwm.isFile()) {
                cachedDwmapi = (Win32Dwm) Native.loadLibrary(sysDwm.getAbsolutePath(), Win32Dwm.class);
            }
        }
        return cachedDwmapi;
    }

    private static void setClassPointer(Pointer hwnd, int index, Pointer value) {
        if (hwnd == null || !IS_WINDOWS) {
            return;
        }
        try {
            if (Pointer.SIZE == 8) {
                Win32ClassLong64 c64 = class64();
                if (c64 != null) {
                    c64.SetClassLongPtrW(hwnd, index, value);
                }
            } else {
                Win32Borderless u32 = user32();
                if (u32 != null) {
                    u32.SetClassLongW(hwnd, index, value == null ? 0 : (int) Pointer.nativeValue(value));
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static Object resolveWindowsDisplayImpl() {
        try {
            if (displayImplField == null) {
                Field f = Display.class.getDeclaredField("display_impl");
                f.setAccessible(true);
                displayImplField = f;
            }
            Object impl = displayImplField.get(null);
            if (impl != null && !lwjglReflectionResolved) {
                lwjglReflectionResolved = true;
                Class<?> cls = impl.getClass();
                try {
                    Method gh = cls.getDeclaredMethod("getHwnd");
                    gh.setAccessible(true);
                    getHwndMethod = gh;
                } catch (Exception | LinkageError ignored) {
                }
                try {
                    Field cc = cls.getDeclaredField("current_cursor");
                    cc.setAccessible(true);
                    currentCursorField = cc;
                } catch (Exception | LinkageError ignored) {
                }
                try {
                    Field mf = cls.getDeclaredField("mouse");
                    mf.setAccessible(true);
                    windowsMouseField = mf;
                } catch (Exception | LinkageError ignored) {
                }
                try {
                    Method uc = cls.getDeclaredMethod("updateCursor");
                    uc.setAccessible(true);
                    updateCursorMethod = uc;
                } catch (Exception | LinkageError ignored) {
                }
            }
            return impl;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static ByteBuffer ensureArrowCursorDirectBuffer() {
        if (cachedArrowCursorBuffer != null) {
            return cachedArrowCursorBuffer;
        }
        Win32Borderless u32 = user32();
        if (u32 == null) {
            return null;
        }
        if (cachedArrowCursor == null) {
            cachedArrowCursor = u32.LoadCursorW(null, new Pointer(Win32Borderless.IDC_ARROW_ID));
        }
        if (cachedArrowCursor == null) {
            return null;
        }
        long handle = Pointer.nativeValue(cachedArrowCursor);
        ByteBuffer buf = ByteBuffer.allocateDirect(Pointer.SIZE).order(ByteOrder.nativeOrder());
        if (Pointer.SIZE == 8) {
            buf.putLong(0, handle);
        } else {
            buf.putInt(0, (int) handle);
        }
        cachedArrowCursorBuffer = buf;
        return buf;
    }

    private static void ensureOsCursorCounterPositive(Win32Borderless u32) {
        if (u32 == null) {
            return;
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
    }

    /**
     * Synchronise le curseur client matériel de LWJGL (WindowsDisplay.current_cursor) :
     * - blankInClient = true  : lorsque MenuCursorRenderer dessine déjà le curseur OpenGL dans FBO 0,
     *   assigne le curseur transparent 1x1 de WindowsMouse à HTCLIENT pour éviter tout double curseur.
     * - blankInClient = false : assigne un ByteBuffer direct contenant IDC_ARROW à WindowsDisplay.current_cursor
     *   afin que LWJGL n'écrase jamais plus GCLP_HCURSOR à NULL hors focus ou si le module est désactivé.
     */
    public static void setClientHardwareCursorBlank(boolean blankInClient) {
        if (!IS_WINDOWS || !Display.isCreated()) {
            return;
        }
        if (clientHardwareCursorBlank != null && clientHardwareCursorBlank == blankInClient) {
            return;
        }
        try {
            Object impl = resolveWindowsDisplayImpl();
            if (impl == null || currentCursorField == null) {
                return;
            }
            if (blankInClient) {
                Object mouseObj = windowsMouseField != null ? windowsMouseField.get(impl) : null;
                if (mouseObj == null) {
                    return;
                }
                if (getBlankCursorMethod == null) {
                    Method gb = mouseObj.getClass().getDeclaredMethod("getBlankCursor");
                    gb.setAccessible(true);
                    getBlankCursorMethod = gb;
                }
                Object blankCursor = getBlankCursorMethod.invoke(mouseObj);
                if (blankCursor == null) {
                    return;
                }
                currentCursorField.set(impl, blankCursor);
                clientHardwareCursorBlank = Boolean.TRUE;
                if (updateCursorMethod != null && (!Mouse.isCreated() || !Mouse.isGrabbed())) {
                    updateCursorMethod.invoke(impl);
                }
            } else {
                ByteBuffer arrowBuf = ensureArrowCursorDirectBuffer();
                if (arrowBuf != null) {
                    currentCursorField.set(impl, arrowBuf);
                }
                clientHardwareCursorBlank = Boolean.FALSE;
                if (!Mouse.isCreated() || !Mouse.isGrabbed()) {
                    if (updateCursorMethod != null) {
                        updateCursorMethod.invoke(impl);
                    }
                    Win32Borderless u32 = user32();
                    if (u32 != null) {
                        ensureOsCursorCounterPositive(u32);
                        if (cachedArrowCursor != null) {
                            u32.SetCursor(cachedArrowCursor);
                        }
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    /**
     * Interroge en direct le pointeur noyau Win32 (GetCursorPos + ScreenToClient + GetClientRect) :
     * Retourne :
     * - 2 si outXY est valide ET le curseur survole directement la fenêtre OpenGL (WindowFromPoint == hwnd)
     * - 1 si outXY est valide mais une autre fenêtre recouvre ce pixel
     * - 0 en cas d'indisponibilité
     */
    public static int queryClientCursorState(Minecraft mc, float[] outXY) {
        if (outXY == null || outXY.length < 2 || !Display.isCreated()) {
            return 0;
        }
        int dispW = mc != null ? Math.max(1, mc.displayWidth) : Math.max(1, Display.getWidth());
        int dispH = mc != null ? Math.max(1, mc.displayHeight) : Math.max(1, Display.getHeight());

        if (IS_WINDOWS) {
            try {
                Win32Borderless u32 = user32();
                long hwndVal = extractLwjglHwnd();
                if (u32 != null && hwndVal != 0L) {
                    if (pointBuffer == null) {
                        pointBuffer = new Memory(8);
                    }
                    if (u32.GetCursorPos(pointBuffer)) {
                        boolean directlyOverHwnd = Display.isActive();
                        if (!directlyOverHwnd && Pointer.SIZE == 8) {
                            Win32ClassLong64 c64 = class64();
                            if (c64 != null) {
                                Pointer hit = c64.WindowFromPoint(pointBuffer.getLong(0));
                                directlyOverHwnd = hit != null && Pointer.nativeValue(hit) == hwndVal;
                            }
                        }

                        Pointer hwnd = new Pointer(hwndVal);
                        if (u32.ScreenToClient(hwnd, pointBuffer)) {
                            int cx = pointBuffer.getInt(0);
                            int cy = pointBuffer.getInt(4);

                            if (clientRectBuffer == null) {
                                clientRectBuffer = new Memory(16);
                            }
                            if (u32.GetClientRect(hwnd, clientRectBuffer)) {
                                int cw = clientRectBuffer.getInt(8) - clientRectBuffer.getInt(0);
                                int ch = clientRectBuffer.getInt(12) - clientRectBuffer.getInt(4);
                                if (cw > 0 && ch > 0) {
                                    outXY[0] = (float) cx * ((float) dispW / (float) cw);
                                    outXY[1] = (float) cy * ((float) dispH / (float) ch);
                                    return directlyOverHwnd ? 2 : 1;
                                }
                            }
                            outXY[0] = (float) cx;
                            outXY[1] = (float) cy;
                            return directlyOverHwnd ? 2 : 1;
                        }
                    }
                }
            } catch (Exception | LinkageError ignored) {
            }
        }

        if (Mouse.isCreated()) {
            outXY[0] = (float) Mouse.getX();
            outXY[1] = (float) (dispH - 1 - Mouse.getY());
            return Display.isActive() ? 2 : 1;
        }
        return 0;
    }

    /**
     * Remplace le pinceau de fond blanc par défaut de la classe LWJGL (COLOR_WINDOW+1) par BLACK_BRUSH (#000000),
     * retire WS_EX_LAYERED et désactive explicitement DwmEnableBlurBehindWindow afin que Windows DWM /
     * DirectComposition traite la fenêtre OpenGL/Vulkan comme une surface 100 % opaque.
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
            Pointer hwnd = new Pointer(hwndVal);
            if (cachedBlackBrush == null) {
                Win32Gdi gdi = gdi32();
                if (gdi != null) {
                    cachedBlackBrush = gdi.GetStockObject(Win32Gdi.BLACK_BRUSH);
                }
            }
            if (cachedBlackBrush != null) {
                setClassPointer(hwnd, Win32Borderless.GCLP_HBRBACKGROUND, cachedBlackBrush);
            }
            try {
                Win32Borderless u32 = user32();
                if (u32 != null) {
                    int exStyle = u32.GetWindowLongW(hwnd, Win32Borderless.GWL_EXSTYLE);
                    if ((exStyle & Win32Borderless.WS_EX_LAYERED) != 0) {
                        u32.SetWindowLongW(hwnd, Win32Borderless.GWL_EXSTYLE, exStyle & ~Win32Borderless.WS_EX_LAYERED);
                    }
                }
            } catch (Exception | LinkageError ignored) {
            }
            try {
                Win32Dwm dwm = dwmapi();
                if (dwm != null) {
                    int cbBlur = Pointer.SIZE == 8 ? 24 : 16;
                    if (dwmBlurBuffer == null || dwmBlurBuffer.size() < cbBlur) {
                        dwmBlurBuffer = new Memory(cbBlur);
                    }
                    dwmBlurBuffer.clear();
                    dwmBlurBuffer.setInt(0, Win32Dwm.DWM_BB_ENABLE);
                    dwmBlurBuffer.setInt(4, 0); // fEnable = FALSE
                    dwm.DwmEnableBlurBehindWindow(hwnd, dwmBlurBuffer);
                }
            } catch (Exception | LinkageError ignored) {
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    /**
     * Maintient le compteur ShowCursor >= 0 et installe IDC_ARROW en secours lorsque le rendu OpenGL
     * du curseur n'est pas en train de masquer volontairement le curseur client.
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
            ensureOsCursorCounterPositive(u32);
            if (Boolean.TRUE.equals(clientHardwareCursorBlank)) {
                return;
            }
            ensureArrowCursorDirectBuffer();
            setClientHardwareCursorBlank(false);
            long hwndVal = extractLwjglHwnd();
            Pointer hwnd = getCachedHwndPointer(hwndVal);
            if (hwnd != null && cachedArrowCursor != null) {
                setClassPointer(hwnd, Win32Borderless.GCLP_HCURSOR, cachedArrowCursor);
                u32.SetCursor(cachedArrowCursor);
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    /**
     * Active proprement la fenêtre Minecraft lorsqu'elle a été lancée directement sans le Launcher MashClient.
     */
    public static void activateWindowNow() {
        if (!IS_WINDOWS || !Display.isCreated()) {
            return;
        }
        try {
            long hwndVal = extractLwjglHwnd();
            Pointer hwnd = getCachedHwndPointer(hwndVal);
            if (hwnd == null) {
                return;
            }
            Win32Borderless u32 = user32();
            if (u32 == null) {
                return;
            }
            u32.ShowWindow(hwnd, 5);
            u32.BringWindowToTop(hwnd);
            u32.SetForegroundWindow(hwnd);
        } catch (Exception | LinkageError ignored) {
        }
    }

    /**
     * Calcule la géométrie Plein Écran Sans Bordure :
     * - Par défaut (dwmSafePlusOne == false) : résolution native EXACTE {0, 0, deskW, deskH} (ex: 1920x1080) ->
     *   Alt-Tab instantané sans écran noir ET Hardware Independent Flip / MPO + alignement FBO/Depth 16x16/32x32 !
     * - Opt-in (dwmSafePlusOne == true) : délègue à resolveDwmSafeBorderlessBounds (+1 px pour anciens pilotes Win10).
     */
    public static int[] resolveBorderlessBounds(
            int deskW,
            int deskH,
            boolean dwmSafePlusOne,
            int virtX,
            int virtY,
            int virtW,
            int virtH
    ) {
        if (!dwmSafePlusOne) {
            return new int[]{0, 0, Math.max(854, deskW), Math.max(480, deskH)};
        }
        return resolveDwmSafeBorderlessBounds(deskW, deskH, virtX, virtY, virtW, virtH);
    }

    /**
     * Calcule une géométrie sans bordure forcée en "Composed: Flip" (+1 px) pour compatibilité
     * avec les anciens pilotes Windows 10 présentant un gel de capture Discord ou Win+Shift+S.
     */
    public static int[] resolveDwmSafeBorderlessBounds(
            int deskW,
            int deskH,
            int virtX,
            int virtY,
            int virtW,
            int virtH
    ) {
        int w = Math.max(854, deskW);
        int h = Math.max(480, deskH);
        if (virtW <= 0 || virtH <= 0) {
            return new int[]{0, 0, w, h + 1};
        }
        long virtRight = (long) virtX + (long) virtW;
        long virtBottom = (long) virtY + (long) virtH;
        if (virtBottom <= (long) h) {
            return new int[]{0, 0, w, h + 1};
        }
        if (virtY >= 0) {
            return new int[]{0, -1, w, h + 1};
        }
        if (virtRight <= (long) w) {
            return new int[]{0, 0, w + 1, h};
        }
        if (virtX >= 0) {
            return new int[]{-1, 0, w + 1, h};
        }
        return new int[]{0, 0, w, h + 1};
    }

    public static int[] computeDwmSafeBorderlessBounds(int deskW, int deskH) {
        int w = Math.max(854, deskW);
        int h = Math.max(480, deskH);
        if (IS_WINDOWS) {
            try {
                Win32Borderless u32 = user32();
                if (u32 != null) {
                    int vx = u32.GetSystemMetrics(Win32Borderless.SM_XVIRTUALSCREEN);
                    int vy = u32.GetSystemMetrics(Win32Borderless.SM_YVIRTUALSCREEN);
                    int vw = u32.GetSystemMetrics(Win32Borderless.SM_CXVIRTUALSCREEN);
                    int vh = u32.GetSystemMetrics(Win32Borderless.SM_CYVIRTUALSCREEN);
                    return resolveDwmSafeBorderlessBounds(w, h, vx, vy, vw, vh);
                }
            } catch (Exception | LinkageError ignored) {
            }
        }
        return new int[]{0, 0, w, h + 1};
    }

    public static int[] computeActiveBorderlessBounds(int deskW, int deskH) {
        if (!dev.purefps.config.PureFPSConfig.borderlessDwmSafe) {
            return new int[]{0, 0, Math.max(854, deskW), Math.max(480, deskH)};
        }
        return computeDwmSafeBorderlessBounds(deskW, deskH);
    }

    public static void applyPreCreateBorderlessLocation() {
        try {
            DisplayMode desktop = Display.getDesktopDisplayMode();
            int deskW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : 1920;
            int deskH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : 1080;
            int[] bounds = computeActiveBorderlessBounds(deskW, deskH);
            Display.setLocation(bounds[0], bounds[1]);
            Display.setResizable(false);
        } catch (Exception | LinkageError ignored) {
        }
    }

    public static boolean applyInitialBorderless(Minecraft mc) {
        try {
            System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
            DisplayMode desktop = Display.getDesktopDisplayMode();
            int deskW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : 1920;
            int deskH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : 1080;
            int[] bounds = computeActiveBorderlessBounds(deskW, deskH);
            int bx = bounds[0];
            int by = bounds[1];
            int bw = bounds[2];
            int bh = bounds[3];

            if (mc != null) {
                if (mc.displayWidth > 0 && mc.displayWidth < deskW) {
                    savedWindowedW = mc.displayWidth;
                }
                if (mc.displayHeight > 0 && mc.displayHeight < deskH) {
                    savedWindowedH = mc.displayHeight;
                }
                mc.displayWidth = bw;
                mc.displayHeight = bh;
            }

            Display.setFullscreen(false);
            Display.setDisplayMode(new DisplayMode(bw, bh));
            Display.setLocation(bx, by);
            Display.setResizable(false);
            currentlyBorderless = true;
            return true;
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    public static void ensurePostCreateBorderlessPlacement(Minecraft mc, boolean bringToFront) {
        DisplayMode desktop = Display.getDesktopDisplayMode();
        int deskW = (desktop != null && desktop.getWidth() > 0) ? desktop.getWidth() : (mc != null ? mc.displayWidth : 1920);
        int deskH = (desktop != null && desktop.getHeight() > 0) ? desktop.getHeight() : (mc != null ? mc.displayHeight : 1080);
        int[] bounds = computeActiveBorderlessBounds(deskW, deskH);
        if (mc != null) {
            mc.displayWidth = bounds[2];
            mc.displayHeight = bounds[3];
        }
        currentlyBorderless = true;
        applyWin32WindowStyle(true, bounds[0], bounds[1], bounds[2], bounds[3], bringToFront, !bringToFront);
    }

    /**
     * Bascule instantanément F11 entre Plein Écran Sans Bordure Natif et Fenêtré décoré centré (zéro écran noir).
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
                int[] bounds = computeActiveBorderlessBounds(deskW, deskH);
                int bx = bounds[0];
                int by = bounds[1];
                int bw = bounds[2];
                int bh = bounds[3];

                System.setProperty("org.lwjgl.opengl.Window.undecorated", "true");
                Display.setFullscreen(false);
                Display.setResizable(false);
                Display.setDisplayMode(new DisplayMode(bw, bh));
                Display.setLocation(bx, by);
                applyWin32WindowStyle(true, bx, by, bw, bh, false, false);
                currentlyBorderless = true;
                if (mc != null) {
                    mc.displayWidth = bw;
                    mc.displayHeight = bh;
                    mc.resize(bw, bh);
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
        } catch (Exception | LinkageError ignored) {
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
            Pointer hwnd = getCachedHwndPointer(hwndVal);
            if (hwnd == null) {
                return;
            }
            installBlackWindowBackground();
            Win32Borderless u32 = user32();
            if (u32 == null) {
                return;
            }
            int clipFlags = Win32Borderless.WS_CLIPSIBLINGS | Win32Borderless.WS_CLIPCHILDREN;
            int style = borderless
                    ? (Win32Borderless.WS_POPUP | Win32Borderless.WS_VISIBLE | clipFlags)
                    : (Win32Borderless.WS_OVERLAPPEDWINDOW | Win32Borderless.WS_VISIBLE | clipFlags);
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
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static Object cachedDisplayImplForHwnd = null;
    private static long cachedHwndValue = 0L;
    private static Pointer cachedHwndPointer = null;
    private static boolean cachedDisplayCreated = false;
    private static boolean cachedDisplayActive = true;

    public static void updateCachedDisplayState(boolean created, boolean active) {
        cachedDisplayCreated = created;
        cachedDisplayActive = active;
        if (!created) {
            cachedDisplayImplForHwnd = null;
            cachedHwndValue = 0L;
            cachedHwndPointer = null;
        }
    }

    public static boolean isCachedDisplayCreated() {
        return cachedDisplayCreated;
    }

    public static boolean isCachedDisplayActive() {
        return cachedDisplayActive;
    }

    private static Pointer getCachedHwndPointer(long hwndVal) {
        if (hwndVal == 0L) {
            return null;
        }
        if (cachedHwndPointer == null || cachedHwndValue != hwndVal) {
            cachedHwndValue = hwndVal;
            cachedHwndPointer = new Pointer(hwndVal);
        }
        return cachedHwndPointer;
    }

    public static long extractLwjglHwnd() {
        try {
            Object impl = resolveWindowsDisplayImpl();
            if (impl == null || getHwndMethod == null) {
                cachedDisplayImplForHwnd = null;
                cachedHwndValue = 0L;
                cachedHwndPointer = null;
                return 0L;
            }
            if (impl == cachedDisplayImplForHwnd && cachedHwndValue != 0L) {
                return cachedHwndValue;
            }
            Object val = getHwndMethod.invoke(impl);
            if (val instanceof Long) {
                long v = (Long) val;
                if (v != 0L) {
                    cachedDisplayImplForHwnd = impl;
                    cachedHwndValue = v;
                    cachedHwndPointer = new Pointer(v);
                }
                return v;
            }
        } catch (Exception | LinkageError ignored) {
        }
        return 0L;
    }
}
