package dev.purefps.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.lwjgl.Sys;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Détection, contournement de Windows KnownDLLs (\KnownDlls\opengl32.dll) et liaison directe PE64 IAT
 * du backend de rendu MashClient 4.5.9 :
 * - Standard : OpenGL natif (1.8.9 PureFPS)
 * - Bêta : Traduction OpenGL 4.6 -> Vulkan 1.3 via Mesa3D Zink (1.8.9 PureFPS Vulkan [Bêta])
 *
 * Pourquoi Windows 10/11 ignorait opengl32.dll auparavant :
 * 1. Sur Windows 10/11, "opengl32.dll" figure dans HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\KnownDLLs.
 *    Le chargeur PE de Windows ignore SetDllDirectoryW, PATH et .local pour toute DLL inscrite dans KnownDLLs
 *    lorsqu'elle est importée ou chargée sous le nom court "opengl32.dll", forçant C:\Windows\System32\opengl32.dll.
 * 2. De plus, lwjgl64.dll importe ChoosePixelFormat, DescribePixelFormat, SetPixelFormat et SwapBuffers depuis
 *    GDI32.dll, qui délègue au pilote ICD OpenGL système au lieu des fonctions wgl* de Mesa3D Zink.
 *
 * Solution 100 % espace utilisateur (sans droits administrateur) :
 * 1. Copie opengl32.dll vers "zinkgl32.dll" (12 octets ASCII, absent de KnownDLLs) et remplace les chaînes internes
 *    "opengl32.dll\0" par "zinkgl32.dll\0" (dans zinkgl32.dll et dans natives-1.8.9-vulkan\lwjgl64.dll).
 * 2. Injecte les variables d'environnement Zink/Kopper à la fois via Kernel32.SetEnvironmentVariableW et via
 *    ucrtbase._putenv / msvcrt._putenv avant le chargement de zinkgl32.dll.
 * 3. Charge zinkgl32.dll, initialise lwjgl64.dll via Sys.initialize(), puis redirige en mémoire (VirtualProtect)
 *    la table d'importation PE64 (IAT) de lwjgl64.dll pour GDI32.dll (ChoosePixelFormat -> wglChoosePixelFormat,
 *    DescribePixelFormat -> wglDescribePixelFormat, SetPixelFormat -> wglSetPixelFormat, SwapBuffers -> wglSwapBuffers)
 *    et OPENGL32.dll / zinkgl32.dll vers zinkgl32.dll !
 */
public final class VulkanBackendHelper {

    public static final String BACKEND_PROP = "purefps.rendererBackend";
    public static final String VULKAN_DLL_PROP = "purefps.vulkanDllPath";
    public static final String ZINK_ALIAS_DLL = "zinkgl32.dll";

    private static final int PAGE_READWRITE = 0x04;
    private static final byte[] TARGET_LOWER = "opengl32.dll\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TARGET_UPPER = "OPENGL32.dll\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REPLACEMENT_ZINK = "zinkgl32.dll\0".getBytes(StandardCharsets.US_ASCII);

    private static final AtomicBoolean PRELOAD_ATTEMPTED = new AtomicBoolean(false);
    private static volatile boolean zinkDllPreloaded = false;
    private static volatile int patchedIatSlotsCount = 0;
    private static int cachedZinkState = 0; // 0 = inconnu, 1 = Zink actif, -1 = OpenGL natif
    private static String cachedRendererString = "";

    private VulkanBackendHelper() {
    }

    public interface Win32KernelDll extends Library {
        boolean SetDllDirectoryW(WString lpPathName);
        boolean SetEnvironmentVariableW(WString lpName, WString lpValue);
        Pointer LoadLibraryW(WString lpLibFileName);
        Pointer GetModuleHandleW(WString lpModuleName);
        Pointer GetProcAddress(Pointer hModule, String lpProcName);
        boolean VirtualProtect(Pointer lpAddress, long dwSize, int flNewProtect, IntByReference lpflOldProtect);
    }

    public interface CrtEnvLibrary extends Library {
        int _putenv(String envString);
    }

    /**
     * Remplace en place toutes les occurrences exactes de "opengl32.dll\0" (insensible à la casse)
     * par "zinkgl32.dll\0" (même longueur exacte de 13 octets incluant le NUL final).
     */
    public static int patchOpenGlDllNameBytes(byte[] data) {
        if (data == null || data.length < REPLACEMENT_ZINK.length) {
            return 0;
        }
        int patched = 0;
        int limit = data.length - REPLACEMENT_ZINK.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < REPLACEMENT_ZINK.length; j++) {
                byte b = data[i + j];
                if (b != TARGET_LOWER[j] && b != TARGET_UPPER[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(REPLACEMENT_ZINK, 0, data, i, REPLACEMENT_ZINK.length);
                patched++;
                i += REPLACEMENT_ZINK.length - 1;
            }
        }
        return patched;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        long len = file.length();
        if (len <= 0L || len > 128L * 1024L * 1024L) {
            return null;
        }
        byte[] buf = new byte[(int) len];
        FileInputStream fis = new FileInputStream(file);
        try {
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            if (off != buf.length) {
                return null;
            }
            return buf;
        } finally {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeFileBytes(File file, byte[] data) throws IOException {
        FileOutputStream fos = new FileOutputStream(file, false);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Prépare zinkgl32.dll et patche lwjgl64.dll sur disque dans le dossier natives-1.8.9-vulkan
     * afin que le chargeur PE de Windows ne résolve jamais \KnownDlls\opengl32.dll.
     */
    public static File preparePatchedZinkDllFiles(File openglDllFile) {
        if (openglDllFile == null || !openglDllFile.isFile()) {
            return null;
        }
        File parentDir = openglDllFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            return openglDllFile;
        }
        File zinkAliasFile = new File(parentDir, ZINK_ALIAS_DLL);
        try {
            if (!zinkAliasFile.isFile() || zinkAliasFile.length() != openglDllFile.length()) {
                byte[] zinkBytes = readFileBytes(openglDllFile);
                if (zinkBytes != null) {
                    patchOpenGlDllNameBytes(zinkBytes);
                    writeFileBytes(zinkAliasFile, zinkBytes);
                }
            }
        } catch (Exception | LinkageError ignored) {
        }

        File lwjglFile = new File(parentDir, "lwjgl64.dll");
        if (lwjglFile.isFile() && zinkAliasFile.isFile()) {
            try {
                byte[] lwjglBytes = readFileBytes(lwjglFile);
                if (lwjglBytes != null && patchOpenGlDllNameBytes(lwjglBytes) > 0) {
                    writeFileBytes(lwjglFile, lwjglBytes);
                }
            } catch (Exception | LinkageError ignored) {
            }
        }
        return zinkAliasFile.isFile() ? zinkAliasFile : openglDllFile;
    }

    private static void applyNativeZinkEnvVars(Win32KernelDll kernel32) {
        String[][] envPairs = new String[][]{
                {"GALLIUM_DRIVER", "zink"},
                {"MESA_LOADER_DRIVER_OVERRIDE", "zink"},
                {"LIBGL_ALWAYS_SOFTWARE", "0"},
                {"WGL_SWAP_INTERVAL", "0"},
                {"wgl_swap_interval", "0"},
                {"vblank_mode", "0"},
                {"wgl_frame_latency", "2"},
                {"GALLIUM_THREAD", "1"},
                {"mesa_glthread", "true"},
                {"mesa_glthread_driver", "true"},
                {"ZINK_DESCRIPTORS", "lazy"},
                {"ZINK_INLINE_UNIFORMS", "0"},
                {"mesa_no_error", "true"},
                {"MESA_NO_ERROR", "1"}
        };
        CrtEnvLibrary ucrt = null;
        CrtEnvLibrary msvcrt = null;
        try {
            ucrt = (CrtEnvLibrary) Native.loadLibrary("ucrtbase", CrtEnvLibrary.class);
        } catch (Exception | LinkageError ignored) {
        }
        try {
            msvcrt = (CrtEnvLibrary) Native.loadLibrary("msvcrt", CrtEnvLibrary.class);
        } catch (Exception | LinkageError ignored) {
        }
        for (int i = 0; i < envPairs.length; i++) {
            String k = envPairs[i][0];
            String v = envPairs[i][1];
            try {
                kernel32.SetEnvironmentVariableW(new WString(k), new WString(v));
            } catch (Exception | LinkageError ignored) {
            }
            String expr = k + "=" + v;
            if (ucrt != null) {
                try {
                    ucrt._putenv(expr);
                } catch (Exception | LinkageError ignored) {
                }
            }
            if (msvcrt != null) {
                try {
                    msvcrt._putenv(expr);
                } catch (Exception | LinkageError ignored) {
                }
            }
        }
    }

    public static String mapLwjglImportToZinkExport(String dllName, String funcName) {
        if (dllName == null || funcName == null || funcName.isEmpty()) {
            return null;
        }
        String lowerDll = dllName.toLowerCase(Locale.ROOT);
        if ("opengl32.dll".equals(lowerDll) || ZINK_ALIAS_DLL.equals(lowerDll)) {
            return funcName;
        }
        if ("gdi32.dll".equals(lowerDll)) {
            if ("ChoosePixelFormat".equals(funcName)) {
                return "wglChoosePixelFormat";
            }
            if ("DescribePixelFormat".equals(funcName)) {
                return "wglDescribePixelFormat";
            }
            if ("SetPixelFormat".equals(funcName)) {
                return "wglSetPixelFormat";
            }
            if ("SwapBuffers".equals(funcName)) {
                return "wglSwapBuffers";
            }
            if ("GetPixelFormat".equals(funcName)) {
                return "wglGetPixelFormat";
            }
        }
        return null;
    }

    private static int patchLoadedLwjglModuleInMemory(Win32KernelDll kernel32, Pointer lwjglBase, Pointer zinkModule) {
        if (lwjglBase == null || zinkModule == null) {
            return 0;
        }
        final int IMAGE_SCN_MEM_EXECUTE = 0x20000000;
        final int IMAGE_SCN_MEM_READ = 0x40000000;
        int patchedCount = 0;
        try {
            if (lwjglBase.getShort(0L) != 0x5A4D) { // 'MZ'
                return 0;
            }
            int peOffset = lwjglBase.getInt(0x3CL);
            if (peOffset <= 0 || peOffset > 4096 || lwjglBase.getInt(peOffset) != 0x00004550) { // "PE\0\0"
                return 0;
            }
            short optMagic = lwjglBase.getShort(peOffset + 24L);
            boolean is64 = (optMagic == 0x020B);
            if (!is64 && optMagic != 0x010B) {
                return 0;
            }
            int sizeOfImage = lwjglBase.getInt(peOffset + 0x50L);
            if (sizeOfImage <= 0x1000 || sizeOfImage > 64 * 1024 * 1024) {
                return 0;
            }
            int importDirOffset = is64 ? (peOffset + 0x90) : (peOffset + 0x80);
            int importDirRva = lwjglBase.getInt(importDirOffset);
            if (importDirRva <= 0 || importDirRva + 20 > sizeOfImage) {
                return 0;
            }

            IntByReference oldProtect = new IntByReference();
            int descOff = importDirRva;
            for (int d = 0; d < 64 && descOff + 20 <= sizeOfImage; d++, descOff += 20) {
                int origFirstThunk = lwjglBase.getInt(descOff);
                int nameRva = lwjglBase.getInt(descOff + 12L);
                int firstThunk = lwjglBase.getInt(descOff + 16L);
                if (nameRva == 0 && firstThunk == 0) {
                    break;
                }
                if (nameRva <= 0 || nameRva >= sizeOfImage || firstThunk <= 0 || firstThunk >= sizeOfImage) {
                    continue;
                }
                String dllName = lwjglBase.getString(nameRva);
                if (dllName == null) {
                    continue;
                }
                String lowerDll = dllName.toLowerCase(Locale.ROOT);
                if (!"opengl32.dll".equals(lowerDll)
                        && !ZINK_ALIAS_DLL.equals(lowerDll)
                        && !"gdi32.dll".equals(lowerDll)) {
                    continue;
                }

                int iltRva = origFirstThunk != 0 ? origFirstThunk : firstThunk;
                if (iltRva <= 0 || iltRva >= sizeOfImage) {
                    continue;
                }
                int ptrSize = is64 ? 8 : 4;
                for (int idx = 0; idx < 512; idx++) {
                    long slotRva = (long) firstThunk + (long) idx * ptrSize;
                    long lookupRva = (long) iltRva + (long) idx * ptrSize;
                    if (slotRva < 0L || slotRva + ptrSize > sizeOfImage
                            || lookupRva < 0L || lookupRva + ptrSize > sizeOfImage) {
                        break;
                    }
                    long thunkData = is64 ? lwjglBase.getLong(lookupRva) : (lwjglBase.getInt(lookupRva) & 0xFFFFFFFFL);
                    if (thunkData == 0L) {
                        break;
                    }
                    boolean isOrdinal = is64 ? ((thunkData & (1L << 63)) != 0L) : ((thunkData & 0x80000000L) != 0L);
                    if (isOrdinal) {
                        continue;
                    }
                    int hintNameRva = (int) (thunkData & 0x7FFFFFFFL);
                    if (hintNameRva <= 0 || hintNameRva + 2 >= sizeOfImage) {
                        continue;
                    }
                    String funcName = lwjglBase.getString(hintNameRva + 2L);
                    String zinkExport = mapLwjglImportToZinkExport(dllName, funcName);
                    if (zinkExport == null) {
                        continue;
                    }
                    Pointer targetProc = kernel32.GetProcAddress(zinkModule, zinkExport);
                    if (targetProc == null || Pointer.nativeValue(targetProc) == 0L) {
                        continue;
                    }
                    Pointer slotPtr = lwjglBase.share(slotRva);
                    if (kernel32.VirtualProtect(slotPtr, ptrSize, PAGE_READWRITE, oldProtect)) {
                        try {
                            if (is64) {
                                slotPtr.setLong(0L, Pointer.nativeValue(targetProc));
                            } else {
                                slotPtr.setInt(0L, (int) Pointer.nativeValue(targetProc));
                            }
                            patchedCount++;
                        } finally {
                            kernel32.VirtualProtect(slotPtr, ptrSize, oldProtect.getValue(), oldProtect);
                        }
                    }
                }
            }

            // Remplace également la chaîne "opengl32.dll" uniquement dans les sections de données non-exécutables (.data/.rdata)
            short numSections = lwjglBase.getShort(peOffset + 6L);
            short optHeaderSize = lwjglBase.getShort(peOffset + 20L);
            int secTableOff = peOffset + 24 + (optHeaderSize & 0xFFFF);
            for (int s = 0; s < numSections && s < 16; s++) {
                int sh = secTableOff + s * 40;
                if (sh < 0 || sh + 40 > sizeOfImage) {
                    break;
                }
                int characteristics = lwjglBase.getInt(sh + 36L);
                // Ne jamais retirer PAGE_EXECUTE d'une section .text active dans un processus JVM multithread !
                if ((characteristics & IMAGE_SCN_MEM_EXECUTE) != 0 || (characteristics & IMAGE_SCN_MEM_READ) == 0) {
                    continue;
                }
                int vSize = lwjglBase.getInt(sh + 8L);
                int vRva = lwjglBase.getInt(sh + 12L);
                if (vRva <= 0 || vSize <= REPLACEMENT_ZINK.length || vSize > 2 * 1024 * 1024
                        || ((long) vRva + (long) vSize) > (long) sizeOfImage) {
                    continue;
                }
                byte[] secBytes = lwjglBase.getByteArray(vRva, vSize);
                int limit = secBytes.length - REPLACEMENT_ZINK.length;
                for (int i = 0; i <= limit; i++) {
                    boolean match = true;
                    for (int j = 0; j < REPLACEMENT_ZINK.length; j++) {
                        byte b = secBytes[i + j];
                        if (b != TARGET_LOWER[j] && b != TARGET_UPPER[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        Pointer strPtr = lwjglBase.share((long) vRva + i);
                        if (kernel32.VirtualProtect(strPtr, REPLACEMENT_ZINK.length, PAGE_READWRITE, oldProtect)) {
                            try {
                                strPtr.write(0L, REPLACEMENT_ZINK, 0, REPLACEMENT_ZINK.length);
                            } finally {
                                kernel32.VirtualProtect(strPtr, REPLACEMENT_ZINK.length, oldProtect.getValue(), oldProtect);
                            }
                        }
                        i += REPLACEMENT_ZINK.length - 1;
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        return patchedCount;
    }

    private static boolean isAllowedZinkDllFile(File dllFile, String lwjglLibPath) {
        if (dllFile == null || !dllFile.isFile()) {
            return false;
        }
        String name = dllFile.getName().toLowerCase(Locale.ROOT);
        if (!"opengl32.dll".equals(name) && !ZINK_ALIAS_DLL.equals(name)) {
            return false;
        }
        if (lwjglLibPath != null && !lwjglLibPath.isEmpty()) {
            try {
                File canonLib = new File(lwjglLibPath).getCanonicalFile();
                File canonParent = dllFile.getCanonicalFile().getParentFile();
                if (canonParent == null || !canonParent.equals(canonLib)) {
                    return false;
                }
            } catch (IOException ex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pré-charge explicitement la DLL Mesa3D Zink (zinkgl32.dll) et relie l'IAT de lwjgl64.dll
     * vers Zink avant toute création de fenêtre Display.create().
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
            String libPath = System.getProperty("org.lwjgl.librarypath", "").trim();
            String dllPath = System.getProperty(VULKAN_DLL_PROP, "").trim();
            File dllFile = dllPath.isEmpty() ? null : new File(dllPath);
            if (dllFile != null && !isAllowedZinkDllFile(dllFile, libPath)) {
                dllFile = null;
            }
            if (dllFile == null || !dllFile.isFile()) {
                if (!libPath.isEmpty()) {
                    File candidateZink = new File(libPath, ZINK_ALIAS_DLL);
                    File candidateOgl = new File(libPath, "opengl32.dll");
                    if (candidateOgl.isFile()) {
                        dllFile = candidateOgl;
                    } else if (candidateZink.isFile()) {
                        dllFile = candidateZink;
                    }
                }
            }
            if (dllFile != null && isAllowedZinkDllFile(dllFile, libPath) && dllFile.length() > 100_000L) {
                Win32KernelDll kernel32 = (Win32KernelDll) Native.loadLibrary("kernel32", Win32KernelDll.class);

                // Charge exclusivement le chargeur Vulkan système depuis System32 (empêche le détournement local de vulkan-1.dll)
                String sysRoot = System.getenv("SystemRoot");
                if (sysRoot == null || sysRoot.trim().isEmpty()) {
                    sysRoot = "C:\\Windows";
                }
                File sysVulkanDll = new File(new File(sysRoot.trim(), "System32"), "vulkan-1.dll");
                String sysVulkanPath = sysVulkanDll.isFile() ? sysVulkanDll.getAbsolutePath() : "vulkan-1.dll";
                Pointer sysVk = kernel32.LoadLibraryW(new WString(sysVulkanPath));
                if (sysVk == null || Pointer.nativeValue(sysVk) == 0L) {
                    return;
                }

                applyNativeZinkEnvVars(kernel32);

                File effectiveZinkFile = preparePatchedZinkDllFiles(dllFile);
                File parentDir = effectiveZinkFile.getParentFile();
                boolean dllDirChanged = false;
                try {
                    if (parentDir != null && parentDir.isDirectory()) {
                        dllDirChanged = kernel32.SetDllDirectoryW(new WString(parentDir.getAbsolutePath()));
                        // Ne jamais charger vulkan-1.dll depuis un dossier local (uniquement les DLL compagnes Mesa3D)
                        String[] companions = new String[]{
                                "libglapi.dll",
                                "libgallium_wgl.dll",
                                "zink_dri.dll"
                        };
                        for (int i = 0; i < companions.length; i++) {
                            File compFile = new File(parentDir, companions[i]);
                            if (compFile.isFile() && compFile.length() > 1024L) {
                                try {
                                    kernel32.LoadLibraryW(new WString(compFile.getAbsolutePath()));
                                } catch (Exception | LinkageError ignored) {
                                }
                            }
                        }
                    }

                    Pointer zinkHandle = kernel32.LoadLibraryW(new WString(effectiveZinkFile.getAbsolutePath()));
                    if (zinkHandle != null && Pointer.nativeValue(zinkHandle) != 0L) {
                        zinkDllPreloaded = true;
                        try {
                            Sys.initialize();
                        } catch (Exception | LinkageError ignored) {
                        }
                        Pointer lwjglModule = kernel32.GetModuleHandleW(new WString("lwjgl64.dll"));
                        if (lwjglModule == null || Pointer.nativeValue(lwjglModule) == 0L) {
                            lwjglModule = kernel32.GetModuleHandleW(new WString("lwjgl.dll"));
                        }
                        if (lwjglModule != null && Pointer.nativeValue(lwjglModule) != 0L) {
                            patchedIatSlotsCount = patchLoadedLwjglModuleInMemory(kernel32, lwjglModule, zinkHandle);
                        }
                    }
                } finally {
                    if (dllDirChanged) {
                        try {
                            kernel32.SetDllDirectoryW(null);
                        } catch (Exception | LinkageError ignored) {
                        }
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
    }

    public static boolean isZinkDllPreloaded() {
        return zinkDllPreloaded;
    }

    public static int getPatchedIatSlotsCount() {
        return patchedIatSlotsCount;
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
            } catch (Exception | LinkageError ignored) {
            }
        }
        if (cachedZinkState != 0) {
            return cachedZinkState == 1;
        }
        return isVulkanFlagSet();
    }

    public static boolean isNativeZinkConfirmed() {
        isVulkanActive();
        return cachedZinkState == 1;
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
        if (isNativeZinkConfirmed()) {
            return "\u00a7d" + beta + " (Zink)";
        }
        if (isVulkanFlagSet()) {
            if (cachedZinkState == -1) {
                return "\u00a7e" + beta + " -> OpenGL";
            }
            return "\u00a7d" + beta;
        }
        return "\u00a7aOpenGL";
    }
}
