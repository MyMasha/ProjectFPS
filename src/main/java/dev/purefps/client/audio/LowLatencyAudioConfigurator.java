package dev.purefps.client.audio;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashModules;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Configurateur basse latence OpenAL Soft (WASAPI mmdevapi) & régulateur anti-saturation SoundManager :
 * - Par défaut, l'OpenAL64.dll (OpenAL Soft 1.15.1) embarquée avec LWJGL 2.9.4 ouvre un tampon de
 *   4 x 1024 échantillons à 44.1 kHz (~93 ms de latence interne, voire 180+ ms en repli DirectSound).
 * - Génère config/mashclient_alsoft.ini (drivers=mmdevapi,dsound, periods=3, period_size=320 -> aligné
 *   par MMDevApiResetPlayback sur exactement 2 périodes matérielles WASAPI de 10 ms = ~20 ms au total)
 *   et injecte ALSOFT_CONF dans le bloc d'environnement Win32 AVANT le chargement de OpenAL64.dll.
 * - Fournit également le limiteur O(1) par tick (wld_sound_limiter) afin d'éviter l'engorgement du
 *   CommandThread Paulscode lors des salves d'explosions TNT / Fireball / cosmétiques de lobby.
 */
public final class LowLatencyAudioConfigurator {

    public static final String ALSOFT_INI_FILENAME = "mashclient_alsoft.ini";
    public static final int TARGET_NORMAL_CHANNELS = 64;
    public static final int TARGET_STREAMING_CHANNELS = 8;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final AtomicBoolean ENV_APPLIED = new AtomicBoolean(false);

    // État O(1) du limiteur de sons par tick client (thread client uniquement)
    private static int currentSoundTick = Integer.MIN_VALUE;
    private static final Map<String, Integer> perTickSoundCounts = new HashMap<>(32);

    public static final int ALC_DEFAULT_ALL_DEVICES_SPECIFIER = 0x1012;
    public static final int ALC_ALL_DEVICES_SPECIFIER = 0x1013;

    private static volatile List<String> cachedOutputDevices = Collections.emptyList();
    private static volatile long lastDeviceEnumMs = 0L;

    private LowLatencyAudioConfigurator() {
    }

    public static String buildAlsoftIniContent() {
        return buildAlsoftIniContent("");
    }

    public static String buildAlsoftIniContent(String preferredDevice) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("# MashClient 4.5.9 — Profil OpenAL Soft WASAPI Basse Latence\n")
                .append("[general]\n")
                .append("drivers = mmdevapi,dsound\n")
                .append("periods = 3\n")
                .append("period_size = 320\n")
                .append("sources = 256\n")
                .append("slots = 4\n")
                .append("cf_level = 0\n");
        if (preferredDevice != null) {
            String clean = preferredDevice.trim().replace("\r", "").replace("\n", "");
            if (!clean.isEmpty()) {
                sb.append("device = ").append(clean).append('\n');
            }
        }
        return sb.toString();
    }

    public static File ensureConfigFile(File mcDir) {
        try {
            File baseDir = mcDir != null ? mcDir : new File(".");
            File cfgDir = new File(baseDir, "config");
            if (!cfgDir.exists() && !cfgDir.mkdirs()) {
                return null;
            }
            File iniFile = new File(cfgDir, ALSOFT_INI_FILENAME);
            String expected = buildAlsoftIniContent(PureFPSConfig.audioOutputDevice);
            if (iniFile.isFile()) {
                try {
                    String existing = new String(Files.readAllBytes(iniFile.toPath()), StandardCharsets.UTF_8);
                    if (expected.equals(existing)) {
                        return iniFile;
                    }
                } catch (Throwable ignored) {
                }
            }
            try (FileOutputStream out = new FileOutputStream(iniFile, false)) {
                out.write(expected.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return iniFile;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Écrit mashclient_alsoft.ini et positionne ALSOFT_CONF / ALSOFT_DRIVERS au niveau Win32
     * avant que LibraryLWJGLOpenAL n'appelle System.loadLibrary("OpenAL64").
     */
    public static void applyBeforeOpenALInit(File mcDir) {
        File iniFile = ensureConfigFile(mcDir);
        if (iniFile == null) {
            return;
        }
        String absPath = iniFile.getAbsolutePath();
        System.setProperty("purefps.alsoftConf", absPath);
        if (!IS_WINDOWS) {
            ENV_APPLIED.set(true);
            return;
        }
        try {
            String prefDev = PureFPSConfig.audioOutputDevice != null ? PureFPSConfig.audioOutputDevice.trim() : "";
            Win32EnvBridge.setProcessEnv(absPath, prefDev);
            ENV_APPLIED.set(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Énumère les périphériques de sortie audio disponibles via OpenAL Soft (ALC_ALL_DEVICES_SPECIFIER 0x1013)
     * avec lecture de la liste double-null-terminée en mémoire native, et repli Java Sound AudioSystem.
     */
    public static List<String> listAvailableOutputDevices(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && !cachedOutputDevices.isEmpty() && (now - lastDeviceEnumMs) < 3000L) {
            return cachedOutputDevices;
        }
        List<String> result = new ArrayList<>();
        try {
            if (org.lwjgl.openal.AL.isCreated()) {
                readOpenAlMultiString(ALC_ALL_DEVICES_SPECIFIER, result);
                readOpenAlMultiString( org.lwjgl.openal.ALC10.ALC_DEVICE_SPECIFIER, result);
                try {
                    String defAll = org.lwjgl.openal.ALC10.alcGetString(null, ALC_DEFAULT_ALL_DEVICES_SPECIFIER);
                    addUniqueDevice(result, defAll);
                } catch (Throwable ignored) {
                }
                try {
                    String defSpec = org.lwjgl.openal.ALC10.alcGetString(null, org.lwjgl.openal.ALC10.ALC_DEFAULT_DEVICE_SPECIFIER);
                    addUniqueDevice(result, defSpec);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        // Compléter / replier avec les périphériques de lecture Java Sound (WASAPI / DirectAudio)
        try {
            javax.sound.sampled.Mixer.Info[] mixers = javax.sound.sampled.AudioSystem.getMixerInfo();
            if (mixers != null) {
                for (javax.sound.sampled.Mixer.Info info : mixers) {
                    if (info == null) continue;
                    String name = info.getName();
                    if (name == null) continue;
                    String trim = name.trim();
                    if (trim.isEmpty()
                            || trim.startsWith("Port ")
                            || trim.equalsIgnoreCase("Java Sound Audio Engine")
                            || trim.equalsIgnoreCase("Primary Sound Driver")
                            || trim.equalsIgnoreCase("Périphérique audio principal")
                            || trim.toLowerCase(Locale.ROOT).contains("microphone")
                            || trim.toLowerCase(Locale.ROOT).contains("capture")) {
                        continue;
                    }
                    try {
                        javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(info);
                        if (mixer != null && mixer.getSourceLineInfo().length > 0) {
                            // Si OpenAL Soft a déjà trouvé l'équivalent "OpenAL Soft on <Name>", ne pas dupliquer
                            boolean alreadyCovered = false;
                            for (String existing : result) {
                                if (existing.equalsIgnoreCase(trim)
                                        || existing.toLowerCase(Locale.ROOT).contains(trim.toLowerCase(Locale.ROOT))) {
                                    alreadyCovered = true;
                                    break;
                                }
                            }
                            if (!alreadyCovered) {
                                addUniqueDevice(result, trim);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        cachedOutputDevices = Collections.unmodifiableList(result);
        lastDeviceEnumMs = now;
        return cachedOutputDevices;
    }

    private static void readOpenAlMultiString(int pname, List<String> out) {
        try {
            java.lang.reflect.Method nalcGetString =
                    org.lwjgl.openal.ALC10.class.getDeclaredMethod("nalcGetString", long.class, int.class);
            nalcGetString.setAccessible(true);
            Object ret = nalcGetString.invoke(null, 0L, pname);
            if (ret instanceof Long) {
                long addr = (Long) ret;
                if (addr != 0L) {
                    com.sun.jna.Pointer ptr = new com.sun.jna.Pointer(addr);
                    long offset = 0L;
                    for (int i = 0; i < 32; i++) {
                        byte first = ptr.getByte(offset);
                        if (first == 0) {
                            break;
                        }
                        int len = 0;
                        while (len < 512 && ptr.getByte(offset + len) != 0) {
                            len++;
                        }
                        byte[] bytes = ptr.getByteArray(offset, len);
                        String devName = new String(bytes, StandardCharsets.UTF_8).trim();
                        addUniqueDevice(out, devName);
                        offset += (len + 1);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String single = org.lwjgl.openal.ALC10.alcGetString(null, pname);
            addUniqueDevice(out, single);
        } catch (Throwable ignored) {
        }
    }

    private static void addUniqueDevice(List<String> list, String raw) {
        if (raw == null) return;
        String clean = raw.trim();
        if (clean.isEmpty()) return;
        for (String existing : list) {
            if (existing.equalsIgnoreCase(clean)) {
                return;
            }
        }
        list.add(clean);
    }

    public static String formatCleanDeviceName(String rawDevice) {
        if (rawDevice == null || rawDevice.trim().isEmpty()) {
            return PureFPSConfig.isEnglish() ? "System Default" : "Par défaut (Système)";
        }
        String clean = rawDevice.trim();
        if (clean.startsWith("OpenAL Soft on ")) {
            clean = clean.substring("OpenAL Soft on ".length()).trim();
        }
        if (clean.length() > 36) {
            clean = clean.substring(0, 33) + "...";
        }
        return clean;
    }

    public static String getCurrentAudioOutputDisplayLabel() {
        String prefix = PureFPSConfig.isEnglish() ? "Audio Output: " : "Sortie Audio : ";
        String devName = formatCleanDeviceName(PureFPSConfig.audioOutputDevice);
        return prefix + "\u00a7b" + devName;
    }

    public static void cycleAudioOutputDevice(net.minecraft.client.Minecraft mc) {
        List<String> devices = listAvailableOutputDevices(true);
        List<String> choices = new ArrayList<>(devices.size() + 1);
        choices.add(""); // "" = System Default
        choices.addAll(devices);

        String current = PureFPSConfig.audioOutputDevice != null ? PureFPSConfig.audioOutputDevice.trim() : "";
        int idx = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).equalsIgnoreCase(current)) {
                idx = i;
                break;
            }
        }
        String next = choices.get((idx + 1) % choices.size());
        PureFPSConfig.audioOutputDevice = next;
        PureFPSConfig.save();

        File mcDir = mc != null ? mc.mcDataDir : new File(".");
        applyBeforeOpenALInit(mcDir);

        if (mc != null && mc.getSoundHandler() != null) {
            try {
                java.lang.reflect.Field sndMgrField = null;
                for (java.lang.reflect.Field f : net.minecraft.client.audio.SoundHandler.class.getDeclaredFields()) {
                    if (net.minecraft.client.audio.SoundManager.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        sndMgrField = f;
                        break;
                    }
                }
                if (sndMgrField != null) {
                    net.minecraft.client.audio.SoundManager sm =
                            (net.minecraft.client.audio.SoundManager) sndMgrField.get(mc.getSoundHandler());
                    if (sm != null) {
                        sm.reloadSoundSystem();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Vérifie après AL.create() que le contexte OpenAL actif est bien ouvert sur le périphérique
     * sélectionné dans PureFPSConfig.audioOutputDevice (et bascule le contexte ALC10 si nécessaire).
     */
    public static void ensureOpenALBoundToConfiguredDevice() {
        String target = PureFPSConfig.audioOutputDevice != null ? PureFPSConfig.audioOutputDevice.trim() : "";
        if (target.isEmpty()) {
            return;
        }
        try {
            if (!org.lwjgl.openal.AL.isCreated()) {
                return;
            }
            org.lwjgl.openal.ALCdevice currentDev = org.lwjgl.openal.AL.getDevice();
            String currentSpec = "";
            try {
                currentSpec = org.lwjgl.openal.ALC10.alcGetString(currentDev, ALC_ALL_DEVICES_SPECIFIER);
                if (currentSpec == null || currentSpec.isEmpty()) {
                    currentSpec = org.lwjgl.openal.ALC10.alcGetString(currentDev, org.lwjgl.openal.ALC10.ALC_DEVICE_SPECIFIER);
                }
            } catch (Throwable ignored) {
            }
            if (currentSpec != null && (currentSpec.equalsIgnoreCase(target)
                    || currentSpec.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT)))) {
                return;
            }
            org.lwjgl.openal.ALCdevice newDevice = org.lwjgl.openal.ALC10.alcOpenDevice(target);
            if (newDevice == null && !target.startsWith("OpenAL Soft on ")) {
                newDevice = org.lwjgl.openal.ALC10.alcOpenDevice("OpenAL Soft on " + target);
            }
            if (newDevice != null) {
                org.lwjgl.openal.ALCcontext newCtx = org.lwjgl.openal.ALC10.alcCreateContext(newDevice, (java.nio.IntBuffer) null);
                if (newCtx != null) {
                    org.lwjgl.openal.ALCcontext oldCtx = org.lwjgl.openal.AL.getContext();
                    org.lwjgl.openal.ALC10.alcMakeContextCurrent(newCtx);
                    try {
                        java.lang.reflect.Field devField = org.lwjgl.openal.AL.class.getDeclaredField("device");
                        devField.setAccessible(true);
                        devField.set(null, newDevice);
                        java.lang.reflect.Field ctxField = org.lwjgl.openal.AL.class.getDeclaredField("context");
                        ctxField.setAccessible(true);
                        ctxField.set(null, newCtx);
                    } catch (Throwable ignored) {
                    }
                    if (oldCtx != null) {
                        try {
                            org.lwjgl.openal.ALC10.alcDestroyContext(oldCtx);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (currentDev != null) {
                        try {
                            org.lwjgl.openal.ALC10.alcCloseDevice(currentDev);
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    org.lwjgl.openal.ALC10.alcCloseDevice(newDevice);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Win32EnvBridge {
        public interface Win32KernelEnv extends Library {
            boolean SetEnvironmentVariableW(WString lpName, WString lpValue);
        }

        private static Win32KernelEnv kernelEnvLib;
        private static boolean kernelEnvFailed = false;

        static void setProcessEnv(String absPath, String preferredDevice) {
            if (kernelEnvFailed) {
                return;
            }
            try {
                if (kernelEnvLib == null) {
                    kernelEnvLib = (Win32KernelEnv) Native.loadLibrary("kernel32", Win32KernelEnv.class);
                }
                if (kernelEnvLib != null) {
                    kernelEnvLib.SetEnvironmentVariableW(new WString("ALSOFT_CONF"), new WString(absPath));
                    String existingDrivers = System.getenv("ALSOFT_DRIVERS");
                    if (existingDrivers == null || existingDrivers.trim().isEmpty()) {
                        kernelEnvLib.SetEnvironmentVariableW(new WString("ALSOFT_DRIVERS"), new WString("mmdevapi,dsound"));
                    }
                    if (preferredDevice != null && !preferredDevice.isEmpty()) {
                        kernelEnvLib.SetEnvironmentVariableW(new WString("ALSOFT_DEFAULT_DEVICE"), new WString(preferredDevice));
                    } else {
                        kernelEnvLib.SetEnvironmentVariableW(new WString("ALSOFT_DEFAULT_DEVICE"), null);
                    }
                }
            } catch (Throwable t) {
                kernelEnvFailed = true;
            }
        }
    }

    public static void onSoundTick(int playTime) {
        if (playTime != currentSoundTick) {
            currentSoundTick = playTime;
            if (!perTickSoundCounts.isEmpty()) {
                perTickSoundCounts.clear();
            }
        }
    }

    public static void resetLimiterState() {
        currentSoundTick = Integer.MIN_VALUE;
        perTickSoundCounts.clear();
    }

    /**
     * Plafonne le nombre d'instances identiques lancées durant le même tick client afin d'empêcher
     * la file FIFO CommandQueue de Paulscode de prendre du retard lors de grosses explosions.
     */
    public static boolean allowSoundOnTick(int playTime, String soundLocation, int maxSamePerTick) {
        if (soundLocation == null || soundLocation.isEmpty() || maxSamePerTick <= 0) {
            return true;
        }
        onSoundTick(playTime);
        Integer prev = perTickSoundCounts.get(soundLocation);
        int count = prev != null ? prev : 0;
        if (count >= maxSamePerTick) {
            return false;
        }
        perTickSoundCounts.put(soundLocation, count + 1);
        return true;
    }

    public static boolean shouldAllowGameSound(int playTime, String soundLocation, boolean repeating) {
        if (repeating || !PureFPSConfig.enabled) {
            return true;
        }
        if (!MashModules.isOn("wld_sound_limiter")) {
            return true;
        }
        int cap = Math.max(2, Math.round(MashModules.num("wld_sound_limiter", "max_same", 6.0F)));
        return allowSoundOnTick(playTime, soundLocation, cap);
    }

    /**
     * Calcule le multiplicateur de volume des modules audio MashClient sans aucune allocation.
     */
    public static float computeModuleVolumeScale(String soundPath) {
        if (!PureFPSConfig.enabled || soundPath == null || soundPath.isEmpty()) {
            return 1.0F;
        }
        String path = stripMinecraftDomain(soundPath);
        float scale = 1.0F;
        if (path.startsWith("step.") && MashModules.isOn("wld_footstep_vol")) {
            scale *= clamp01(MashModules.num("wld_footstep_vol", "gain", 100.0F) * 0.01F);
        } else if (path.startsWith("portal.") && MashModules.isOn("wld_portal_sound_mute")) {
            scale *= clamp01(MashModules.num("wld_portal_sound_mute", "vol", 15.0F) * 0.01F);
        } else if ((path.startsWith("mob.enderdragon.") || path.startsWith("mob.wither."))
                && MashModules.isOn("wld_wither_dragon_mute")) {
            scale *= clamp01(MashModules.num("wld_wither_dragon_mute", "vol", 35.0F) * 0.01F);
        } else if (path.startsWith("fireworks.") && MashModules.isOn("hyp_mystery_dust_mute")) {
            scale *= 0.25F;
        }
        return scale;
    }

    /**
     * Calcule le multiplicateur de hauteur (pitch) pour pvp_sound_hit_confirm.
     */
    public static float computeModulePitchScale(String soundPath) {
        if (!PureFPSConfig.enabled || soundPath == null || soundPath.isEmpty()) {
            return 1.0F;
        }
        if (!MashModules.isOn("pvp_sound_hit_confirm")) {
            return 1.0F;
        }
        String path = stripMinecraftDomain(soundPath);
        if (path.equals("random.successful_hit")
                || path.startsWith("game.player.hurt")
                || path.startsWith("game.neutral.hurt")
                || path.equals("random.bowhit")) {
            float p = MashModules.num("pvp_sound_hit_confirm", "pitch", 1.0F);
            return p < 0.5F ? 0.5F : (p > 2.0F ? 2.0F : p);
        }
        return 1.0F;
    }

    private static String stripMinecraftDomain(String s) {
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }
}
