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
import java.util.HashMap;
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

    private LowLatencyAudioConfigurator() {
    }

    public static String buildAlsoftIniContent() {
        return "# MashClient 4.4.6 — Profil OpenAL Soft WASAPI Basse Latence\n"
                + "[general]\n"
                + "drivers = mmdevapi,dsound\n"
                + "periods = 3\n"
                + "period_size = 320\n"
                + "sources = 256\n"
                + "slots = 4\n"
                + "cf_level = 0\n";
    }

    public static File ensureConfigFile(File mcDir) {
        try {
            File baseDir = mcDir != null ? mcDir : new File(".");
            File cfgDir = new File(baseDir, "config");
            if (!cfgDir.exists() && !cfgDir.mkdirs()) {
                return null;
            }
            File iniFile = new File(cfgDir, ALSOFT_INI_FILENAME);
            String expected = buildAlsoftIniContent();
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
     * Si MashClient.exe a déjà injecté ALSOFT_CONF dans l'environnement du processus, aucun appel JNA n'est requis.
     */
    public static void applyBeforeOpenALInit(File mcDir) {
        File iniFile = ensureConfigFile(mcDir);
        if (iniFile == null || !ENV_APPLIED.compareAndSet(false, true)) {
            return;
        }
        String absPath = iniFile.getAbsolutePath();
        System.setProperty("purefps.alsoftConf", absPath);
        if (!IS_WINDOWS) {
            return;
        }
        try {
            String existingConf = System.getenv("ALSOFT_CONF");
            if (existingConf != null && !existingConf.trim().isEmpty()) {
                return;
            }
            Win32EnvBridge.setProcessEnv(absPath);
        } catch (Throwable ignored) {
        }
    }

    private static final class Win32EnvBridge {
        public interface Win32KernelEnv extends Library {
            boolean SetEnvironmentVariableW(WString lpName, WString lpValue);
        }

        private static Win32KernelEnv kernelEnvLib;
        private static boolean kernelEnvFailed = false;

        static void setProcessEnv(String absPath) {
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
