package dev.purefps.core;

import dev.purefps.client.ui.ResourcePackBrowserHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Recharge ultra-rapide des Resource Packs sans coupure OpenAL :
 * En 1.8.9 vanilla, chaque changement de pack détruit et recrée tout le moteur SoundSystem Paulscode/OpenAL
 * (~2.5 à 4.5 secondes de freeze + coupure audio), même quand le pack ne modifie que des textures .png !
 * Ce module calcule une empreinte 64 bits des actifs audio (sounds.json et fichiers .ogg) des packs actifs :
 * si l'empreinte audio est identique, le rechargement OpenAL est intégralement court-circuité.
 */
public final class FastResourcePackReload {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final ResourceLocation MC_SOUNDS_JSON = new ResourceLocation("minecraft", "sounds.json");

    private static boolean initialLoadCompleted = false;
    private static volatile boolean openAlLoaded = false;
    private static long lastAudioSignature = 0L;

    private FastResourcePackReload() {
    }

    public static void setOpenAlLoaded(boolean loaded) {
        openAlLoaded = loaded;
    }

    public static boolean isOpenAlLoaded() {
        return openAlLoaded;
    }

    public static void resetState() {
        initialLoadCompleted = false;
        lastAudioSignature = 0L;
    }

    public static boolean shouldSkipSoundReload(Minecraft mc) {
        long currentSig = computeActiveAudioSignature(mc);
        if (!initialLoadCompleted || !openAlLoaded) {
            initialLoadCompleted = true;
            lastAudioSignature = currentSig;
            return false;
        }
        if (currentSig == lastAudioSignature) {
            return true;
        }
        lastAudioSignature = currentSig;
        return false;
    }

    public static long computeActiveAudioSignature(Minecraft mc) {
        long hash = FNV_OFFSET_BASIS;
        if (mc == null || mc.getResourcePackRepository() == null) {
            return hash;
        }
        ResourcePackRepository repo = mc.getResourcePackRepository();
        List<ResourcePackRepository.Entry> activeEntries = repo.getRepositoryEntries();
        if (activeEntries != null) {
            for (int i = 0; i < activeEntries.size(); i++) {
                ResourcePackRepository.Entry entry = activeEntries.get(i);
                if (entry == null) {
                    continue;
                }
                File packFile = ResourcePackBrowserHelper.getEntryFile(entry);
                long packAudioHash = computePackAudioHash(packFile, entry.getResourcePack());
                if (packAudioHash != 0L) {
                    hash = mixLong(hash, i + 1L);
                    hash = mixLong(hash, packAudioHash);
                }
            }
        }

        IResourcePack serverPack = repo.getResourcePackInstance();
        if (serverPack != null) {
            hash = mixString(hash, "server:" + serverPack.getPackName());
            try {
                if (serverPack.resourceExists(MC_SOUNDS_JSON)) {
                    hash = mixString(hash, "server:sounds.json");
                }
            } catch (Exception | LinkageError ignored) {
            }
        }
        return hash;
    }

    public static long computePackAudioHash(File packFile, IResourcePack fallbackPack) {
        if (packFile != null && packFile.exists()) {
            if (packFile.isFile()) {
                return hashZipAudioEntries(packFile);
            }
            if (packFile.isDirectory()) {
                return hashDirectoryAudioEntries(packFile);
            }
        }
        if (fallbackPack != null) {
            try {
                if (fallbackPack.resourceExists(MC_SOUNDS_JSON)) {
                    return mixString(FNV_OFFSET_BASIS, fallbackPack.getPackName());
                }
            } catch (Exception | LinkageError ignored) {
            }
        }
        return 0L;
    }

    static long hashZipAudioEntries(File zipPath) {
        long h = FNV_OFFSET_BASIS;
        boolean foundAudio = false;
        try (ZipFile zf = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                String name = ze.getName();
                if (isAudioResourceEntry(name)) {
                    foundAudio = true;
                    h = mixString(h, name);
                    h = mixLong(h, ze.getSize());
                    h = mixLong(h, ze.getCrc());
                }
            }
        } catch (Exception | LinkageError ignored) {
            return mixString(FNV_OFFSET_BASIS, zipPath.getName() + ":" + zipPath.lastModified());
        }
        return foundAudio ? (h == 0L ? 1L : h) : 0L;
    }

    static long hashDirectoryAudioEntries(File rootDir) {
        File assetsDir = new File(rootDir, "assets");
        if (!assetsDir.isDirectory()) {
            return 0L;
        }
        long[] acc = new long[]{FNV_OFFSET_BASIS, 0L};
        scanDirForAudio(assetsDir, "assets", 0, acc);
        return acc[1] != 0L ? (acc[0] == 0L ? 1L : acc[0]) : 0L;
    }

    private static void scanDirForAudio(File dir, String relPrefix, int depth, long[] acc) {
        if (depth > 8) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String rel = relPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                scanDirForAudio(child, rel, depth + 1, acc);
            } else if (isAudioResourceEntry(rel)) {
                acc[1] = 1L;
                acc[0] = mixString(acc[0], rel);
                acc[0] = mixLong(acc[0], child.length());
                acc[0] = mixLong(acc[0], child.lastModified());
            }
        }
    }

    public static boolean isAudioResourceEntry(String entryPath) {
        if (entryPath == null || !entryPath.startsWith("assets/")) {
            return false;
        }
        String lower = entryPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ogg") || lower.endsWith("/sounds.json");
    }

    private static long mixLong(long hash, long value) {
        long h = hash;
        for (int i = 0; i < 8; i++) {
            h ^= (value & 0xFFL);
            h *= FNV_PRIME;
            value >>>= 8;
        }
        return h;
    }

    private static long mixString(long hash, String s) {
        if (s == null) {
            return hash;
        }
        long h = hash;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }
}
