package dev.purefps.client.ui;

import dev.purefps.module.MashModule;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileFilter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Navigation par sous-dossiers et recherche temps-réel dans le dossier {@code resourcepacks/} :
 * - Explore récursivement les sous-dossiers d'organisation (ex: "PvP 16x", "BedWars") jusqu'à 3 niveaux.
 * - Normalise les noms et descriptions de packs (sans codes couleurs § ni accents) pour le filtrage instantané.
 */
public final class ResourcePackBrowserHelper {

    private static final int MAX_SUBFOLDER_DEPTH = 3;
    private static Field entryFileField;
    private static boolean entryFileFieldResolved = false;

    private ResourcePackBrowserHelper() {
    }

    public static File[] collectPackFilesRecursively(File rootDir, FileFilter vanillaPackFilter) {
        if (rootDir != null && !rootDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            rootDir.mkdirs();
        }
        List<File> collected = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        if (rootDir != null && rootDir.isDirectory()) {
            scanDirectory(rootDir, vanillaPackFilter, 0, collected, seenNames);
        }
        File fallbackRoot = resolveDefaultMinecraftResourcePacksDir();
        if (fallbackRoot != null && fallbackRoot.isDirectory() && (rootDir == null || !isSameDirectory(rootDir, fallbackRoot))) {
            scanDirectory(fallbackRoot, vanillaPackFilter, 0, collected, seenNames);
        }
        return collected.toArray(new File[0]);
    }

    public static File resolveDefaultMinecraftResourcePacksDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.trim().isEmpty()) {
            File std = new File(new File(appdata.trim(), ".minecraft"), "resourcepacks");
            if (std.isDirectory()) {
                return std;
            }
        }
        return null;
    }

    public static void openFolderInFileManager(File dir) {
        if (dir == null) {
            return;
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String absPath = dir.getAbsolutePath();
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String sysRoot = System.getenv("SystemRoot");
                if (sysRoot == null || sysRoot.trim().isEmpty()) {
                    sysRoot = System.getenv("WINDIR");
                }
                if (sysRoot == null || sysRoot.trim().isEmpty()) {
                    sysRoot = "C:\\Windows";
                }
                File explorer = new File(sysRoot.trim(), "explorer.exe");
                String explorerCmd = explorer.isFile() ? explorer.getAbsolutePath() : "C:\\Windows\\explorer.exe";
                new ProcessBuilder(explorerCmd, absPath).start();
                return;
            }
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Exception | LinkageError ignored) {
        }
        try {
            org.lwjgl.Sys.openURL(dir.toURI().toString());
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static void scanDirectory(File dir, FileFilter packFilter, int depth, List<File> out, java.util.Set<String> seenNames) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(f -> f.getName().toLowerCase()));
        for (File child : children) {
            if (packFilter != null && packFilter.accept(child)) {
                String key = child.getName().toLowerCase();
                if (seenNames.add(key)) {
                    out.add(child);
                }
            } else if (depth < MAX_SUBFOLDER_DEPTH && isNavigableSubfolder(child)) {
                scanDirectory(child, packFilter, depth + 1, out, seenNames);
            }
        }
    }

    public static boolean isNavigableSubfolder(File file) {
        if (file == null || !file.isDirectory() || file.isHidden()) {
            return false;
        }
        String name = file.getName();
        if (name.startsWith(".")) {
            return false;
        }
        // Un dossier contenant pack.mcmeta est un Resource Pack décompressé, pas un sous-dossier de rangement
        return !new File(file, "pack.mcmeta").isFile();
    }

    public static List<File> listSubfolders(File currentDir) {
        List<File> folders = new ArrayList<>();
        if (currentDir == null || !currentDir.isDirectory()) {
            return folders;
        }
        File[] children = currentDir.listFiles();
        if (children == null) {
            return folders;
        }
        for (File child : children) {
            if (isNavigableSubfolder(child)) {
                folders.add(child);
            }
        }
        folders.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        return folders;
    }

    public static File getEntryFile(ResourcePackRepository.Entry entry) {
        if (entry == null) {
            return null;
        }
        if (!entryFileFieldResolved) {
            entryFileFieldResolved = true;
            for (Field f : ResourcePackRepository.Entry.class.getDeclaredFields()) {
                if (f.getType() == File.class) {
                    f.setAccessible(true);
                    entryFileField = f;
                    break;
                }
            }
        }
        if (entryFileField != null) {
            try {
                return (File) entryFileField.get(entry);
            } catch (Exception | LinkageError ignored) {
            }
        }
        return null;
    }

    public static boolean isSameDirectory(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception | LinkageError ignored) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    public static boolean isInsideDirectory(File file, File folder) {
        if (file == null || folder == null) {
            return false;
        }
        try {
            String fp = file.getCanonicalPath();
            String dp = folder.getCanonicalPath();
            if (!dp.endsWith(File.separator)) {
                dp = dp + File.separator;
            }
            return fp.startsWith(dp);
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }

    public static boolean matchesSearch(String packName, String packDescription, File packFile, String rawQuery) {
        String normQ = MashModule.normalizeSearch(rawQuery);
        if (normQ.isEmpty()) {
            return true;
        }
        String cleanName = MashModule.normalizeSearch(stripFormatting(packName));
        String cleanDesc = MashModule.normalizeSearch(stripFormatting(packDescription));
        String folderName = (packFile != null && packFile.getParentFile() != null)
                ? MashModule.normalizeSearch(packFile.getParentFile().getName())
                : "";
        String haystack = cleanName + " " + cleanDesc + " " + folderName;
        for (String token : normQ.split(" ")) {
            if (!token.isEmpty() && !haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    public static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return stripped != null ? stripped : text;
    }
}
