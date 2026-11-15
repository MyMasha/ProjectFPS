package dev.purefps.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Détection et pré-chargement sans allocation du backend de rendu MashClient 4.5.5 :
 * - Standard : OpenGL natif (1.8.9 PureFPS)
 * - Bêta : Traduction OpenGL -> Vulkan via Mesa3D Zink (1.8.9 PureFPS Vulkan [Bêta])
 *
 * Correctif Windows critique (v4.5.5) :
 * Java 8 charge lwjgl64.dll via LoadLibraryExW(..., LOAD_WITH_ALTERED_SEARCH_PATH), ce qui remplace
 * le dossier de javaw.exe par le dossier natives-1.8.9 comme premier répertoire de recherche DLL,
 * suivi immédiatement de C:\Windows\System32\opengl32.dll.
 * Pour garantir que Zink (opengl32.dll) soit toujours utilisé en mode Vulkan :
 * 1. Le launcher copie opengl32.dll dans natives-1.8.9-vulkan\ (à côté de lwjgl64.dll) ET dans vk_bin\.
 * 2. preloadZinkIfConfigured() appelle SetDllDirectoryW + LoadLibraryW(opengl32.dll) dès le début de
 *    Minecraft.startGame(), avant toute initialisation de LWJGL / Display.create().
 */
public final class VulkanBackendHelper {

    public static final String BACKEND_PROP = "purefps.rendererBackend";
    public static final String VULKAN_DLL_PROP = "purefps.vulkanDllPath";

    private static final AtomicBoolean PRELOAD_ATTEMPTED = new AtomicBoolean(false);
    private static volatile boolean zinkDllPreloaded = false;
    private static int cachedZinkState = 0; // 0 = inconnu, 1 = Zink actif, -1 = OpenGL natif
    private static String cachedRendererString = "";

    private VulkanBackendHelper() {
    }

    public interface Win32KernelDll extends Library {
        boolean SetDllDirectoryW(WString lpPathName);
        Pointer LoadLibraryW(WString lpLibFileName);
    }

    /**
     * Pré-charge explicitement la DLL Mesa3D Zink (opengl32.dll) dans la table des modules Win32
     * du processus avant que lwjgl64.dll ne soit chargé par LWJGL.
     */
    public static void preloadZinkIfConfigured() {
        if (!PRELOAD_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        if (!isVulkanFlagSet()) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        try {
            String dllPath = System.getProperty(VULKAN_DLL_PROP, "").trim();
            File dllFile = dllPath.isEmpty() ? null : new File(dllPath);
            if (dllFile == null || !dllFile.isFile()) {
                String libPath = System.getProperty("org.lwjgl.librarypath", "").trim();
                if (!libPath.isEmpty()) {
                    File candidate = new File(libPath, "opengl32.dll");
                    if (candidate.isFile()) {
                        dllFile = candidate;
                    }
                }
            }
            if (dllFile != null && dllFile.isFile() && dllFile.length() > 100_000L) {
                Win32KernelDll kernel32 = (Win32KernelDll) Native.loadLibrary("kernel32", Win32KernelDll.class);
                File parentDir = dllFile.getParentFile();
                if (parentDir != null && parentDir.isDirectory()) {
                    kernel32.SetDllDirectoryW(new WString(parentDir.getAbsolutePath()));
                    String[] companions = new String[]{
                            "libgallium_wgl.dll",
                            "libglapi.dll",
                            "zink_dri.dll",
                            "vulkan-1.dll"
                    };
                    for (String comp : companions) {
                        File compFile = new File(parentDir, comp);
                        if (compFile.isFile() && compFile.length() > 1024L) {
                            try {
                                kernel32.LoadLibraryW(new WString(compFile.getAbsolutePath()));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                Pointer handle = kernel32.LoadLibraryW(new WString(dllFile.getAbsolutePath()));
                if (handle != null && Pointer.nativeValue(handle) != 0L) {
                    zinkDllPreloaded = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isZinkDllPreloaded() {
        return zinkDllPreloaded;
    }

    public static boolean isVulkanFlagSet() {
        String prop = System.getProperty(BACKEND_PROP);
        return prop != null && "vulkan".equalsIgnoreCase(prop.trim());
    }

    public static boolean isVulkanActive() {
        if (cachedZinkState == 0) {
            try {
                if (Display.isCreated()) {
                    String renderer = GL11.glGetString(GL11.GL_RENDERER);
                    String version = GL11.glGetString(GL11.GL_VERSION);
                    if (renderer != null) {
                        cachedRendererString = renderer;
                        String lowerR = renderer.toLowerCase(Locale.ROOT);
                        String lowerV = version != null ? version.toLowerCase(Locale.ROOT) : "";
                        cachedZinkState = (lowerR.contains("zink")
                                || lowerR.contains("vulkan")
                                || lowerV.contains("zink")
                                || lowerV.contains("mesa")) ? 1 : -1;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return cachedZinkState == 1 || isVulkanFlagSet();
    }

    public static boolean isNativeZinkConfirmed() {
        isVulkanActive();
        return cachedZinkState == 1 || zinkDllPreloaded;
    }

    public static String getCachedRendererName() {
        isVulkanActive();
        return cachedRendererString;
    }

    public static String getWindowTitle(String version) {
        if (isVulkanActive()) {
            String badge = dev.purefps.config.PureFPSConfig.isEnglish() ? "[Vulkan Beta]" : "[Vulkan Bêta]";
            return "MashClient " + version + " " + badge + " | Minecraft 1.8.9";
        }
        return "MashClient " + version + " | Minecraft 1.8.9";
    }

    public static String getF3BackendBadge() {
        String beta = dev.purefps.config.PureFPSConfig.isEnglish() ? "Vulkan Beta" : "Vulkan Bêta";
        if (cachedZinkState == 1 || zinkDllPreloaded) {
            return "\u00a7d" + beta + " (Zink)";
        }
        if (isVulkanFlagSet()) {
            return "\u00a7d" + beta;
        }
        return "\u00a7aOpenGL";
    }
}
