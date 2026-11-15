package dev.purefps.client.audio;

import dev.purefps.config.PureFPSConfig;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moteur audio zéro latence MashClient 4.4.6 :
 * - Dès que le contexte OpenAL (WASAPI mmdevapi basse latence) est actif, joue tous les effets UI
 *   et l'ambiance du Trou Noir directement via des tampons matériels OpenAL 2D pré-chargés (< 0.05 ms,
 *   zéro ouverture de ligne DirectSound, session audio unique partagée avec Minecraft pour Discord).
 * - Pendant les toutes premières millisecondes du démarrage (avant AL.create()), bascule proprement
 *   sur un repli léger pré-synthétisé puis libère toute ligne JavaSound dès qu'OpenAL prend le relais.
 */
public final class MashAudioEngine {

    private static final int SAMPLE_RATE_HZ = 44100;
    private static final float SAMPLE_RATE = 44100.0F;
    private static final AudioFormat STEREO_16 = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);

    private static final int CLIP_HOVER = 0;
    private static final int CLIP_CLICK = 1;
    private static final int CLIP_TOGGLE_ON = 2;
    private static final int CLIP_TOGGLE_OFF = 3;
    private static final int CLIP_MENU_OPEN = 4;
    private static final int CLIP_JOIN = 5;
    private static final int CLIP_EMERGENCE = 6;
    private static final int CLIP_AMBIENT_LOOP = 7;
    private static final int CLIP_COUNT = 8;
    private static final int UI_SOURCE_POOL_SIZE = 6;

    private static final Object BAKE_INTERACTIVE_LOCK = new Object();
    private static final Object BAKE_AMBIENT_LOCK = new Object();
    private static final Object OPENAL_LOCK = new Object();

    private static final AtomicBoolean INIT_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean EMERGENCE_PLAYED = new AtomicBoolean(false);

    private static volatile byte[] pcmEmergence;
    private static volatile byte[] pcmAmbientLoop;
    private static volatile byte[] pcmHover;
    private static volatile byte[] pcmClick;
    private static volatile byte[] pcmToggleOn;
    private static volatile byte[] pcmToggleOff;
    private static volatile byte[] pcmMenuOpen;
    private static volatile byte[] pcmJoin;

    private static final int[] alBuffers = new int[CLIP_COUNT];
    private static final int[] alUiSources = new int[UI_SOURCE_POOL_SIZE];
    private static int alUiRoundRobin = 0;
    private static int alAmbientSource = 0;

    private static volatile long lastHoverNs = 0L;
    private static volatile boolean menuAmbientDesired = false;
    private static volatile boolean menuAmbientLastDispatched = false;
    private static volatile boolean alAmbientPlaying = false;
    private static Clip activeAmbientClip = null;

    private static final ExecutorService AUDIO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MashClient-Audio");
        t.setDaemon(true);
        t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        return t;
    });

    private MashAudioEngine() {
    }

    public static void ensurePrewarmed() {
        if (INIT_STARTED.compareAndSet(false, true)) {
            AUDIO_EXEC.execute(() -> {
                bakeInteractiveClips();
                bakeAmbientLoopIfNeeded();
            });
        }
    }

    public static void resetBootState() {
        EMERGENCE_PLAYED.set(false);
        ensurePrewarmed();
    }

    private static boolean isOpenALReady() {
        try {
            return AL.isCreated();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Appelé par MixinSoundManager juste avant le déchargement/rechargement OpenAL (F3+T / pack de ressources)
     * afin de purger proprement les identifiants de tampons et sources OpenAL avant AL.destroy().
     */
    public static void onOpenALUnloading() {
        synchronized (OPENAL_LOCK) {
            if (isOpenALReady()) {
                for (int i = 0; i < UI_SOURCE_POOL_SIZE; i++) {
                    int src = alUiSources[i];
                    if (src != 0) {
                        try {
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                            AL10.alDeleteSources(src);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (alAmbientSource != 0) {
                    try {
                        AL10.alSourceStop(alAmbientSource);
                        AL10.alSourcei(alAmbientSource, AL10.AL_BUFFER, 0);
                        AL10.alDeleteSources(alAmbientSource);
                    } catch (Throwable ignored) {
                    }
                }
                for (int i = 0; i < CLIP_COUNT; i++) {
                    int buf = alBuffers[i];
                    if (buf != 0) {
                        try {
                            AL10.alDeleteBuffers(buf);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            Arrays.fill(alUiSources, 0);
            Arrays.fill(alBuffers, 0);
            alAmbientSource = 0;
            alUiRoundRobin = 0;
        }
        alAmbientPlaying = false;
        menuAmbientLastDispatched = false;
    }

    /**
     * Appelé sur le thread client dès que SoundManager a démarré OpenAL : ferme immédiatement toute boucle
     * JavaSound résiduelle et bascule l'ambiance sur OpenAL 2D depuis le thread client.
     */
    public static void migrateToOpenALIfReady() {
        if (!isOpenALReady()) {
            return;
        }
        AUDIO_EXEC.execute(MashAudioEngine::closeFallbackAmbientClip);
        boolean shouldPlay = menuAmbientDesired
                && PureFPSConfig.enabled
                && PureFPSConfig.sfxEnabled
                && PureFPSConfig.blackHoleMenuSound;
        byte[] data = pcmAmbientLoop;
        if (shouldPlay && data != null && !alAmbientPlaying) {
            alAmbientPlaying = startOpenALAmbientImmediate(data);
        }
    }

    /**
     * Pilote la boucle sonore gravitationnelle du Trou Noir sur le menu principal.
     * Zéro allocation par frame : tous les appels OpenAL s'exécutent sur le thread client.
     */
    public static void updateMenuAmbientLoop(boolean onBlackHoleMenu) {
        boolean shouldPlay = onBlackHoleMenu
                && PureFPSConfig.enabled
                && PureFPSConfig.sfxEnabled
                && PureFPSConfig.blackHoleMenuSound;
        menuAmbientDesired = shouldPlay;

        if (!shouldPlay) {
            if (alAmbientPlaying) {
                stopOpenALAmbientImmediate();
            }
            if (menuAmbientLastDispatched) {
                menuAmbientLastDispatched = false;
                AUDIO_EXEC.execute(MashAudioEngine::closeFallbackAmbientClip);
            }
            return;
        }

        ensurePrewarmed();
        if (isOpenALReady()) {
            if (menuAmbientLastDispatched) {
                menuAmbientLastDispatched = false;
                AUDIO_EXEC.execute(MashAudioEngine::closeFallbackAmbientClip);
            }
            if (!alAmbientPlaying) {
                byte[] data = pcmAmbientLoop;
                if (data != null) {
                    alAmbientPlaying = startOpenALAmbientImmediate(data);
                }
            }
            return;
        }

        if (!menuAmbientLastDispatched) {
            menuAmbientLastDispatched = true;
            AUDIO_EXEC.execute(MashAudioEngine::syncFallbackAmbientPlayback);
        }
    }

    private static void stopOpenALAmbientImmediate() {
        synchronized (OPENAL_LOCK) {
            alAmbientPlaying = false;
            if (alAmbientSource != 0 && isOpenALReady()) {
                try {
                    if (AL10.alIsSource(alAmbientSource)) {
                        AL10.alSourceStop(alAmbientSource);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void closeFallbackAmbientClip() {
        if (activeAmbientClip != null) {
            try {
                activeAmbientClip.stop();
                activeAmbientClip.close();
            } catch (Throwable ignored) {
            }
            activeAmbientClip = null;
        }
    }

    private static void syncFallbackAmbientPlayback() {
        boolean want = menuAmbientDesired
                && PureFPSConfig.enabled
                && PureFPSConfig.sfxEnabled
                && PureFPSConfig.blackHoleMenuSound
                && !isOpenALReady();
        if (!want) {
            closeFallbackAmbientClip();
            return;
        }
        if (pcmAmbientLoop == null) {
            bakeAmbientLoopIfNeeded();
        }
        byte[] data = pcmAmbientLoop;
        if (data == null || data.length == 0 || isOpenALReady()) {
            closeFallbackAmbientClip();
            return;
        }
        if (activeAmbientClip != null && activeAmbientClip.isOpen() && activeAmbientClip.isRunning()) {
            return;
        }
        closeFallbackAmbientClip();
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(STEREO_16, data, 0, data.length);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            activeAmbientClip = clip;
        } catch (Throwable ignored) {
            activeAmbientClip = null;
        }
    }

    private static boolean startOpenALAmbientImmediate(byte[] pcm) {
        synchronized (OPENAL_LOCK) {
            if (!isOpenALReady()) {
                return false;
            }
            try {
                int buf = ensureOpenALBufferLocked(CLIP_AMBIENT_LOOP, pcm);
                if (buf == 0) {
                    return false;
                }
                if (alAmbientSource == 0 || !AL10.alIsSource(alAmbientSource)) {
                    alAmbientSource = AL10.alGenSources();
                    if (alAmbientSource == 0) {
                        return false;
                    }
                    configure2DSourceLocked(alAmbientSource, true);
                }
                int state = AL10.alGetSourcei(alAmbientSource, AL10.AL_SOURCE_STATE);
                if (state == AL10.AL_PLAYING) {
                    return true;
                }
                AL10.alSourcei(alAmbientSource, AL10.AL_BUFFER, buf);
                AL10.alSourcei(alAmbientSource, AL10.AL_LOOPING, AL10.AL_TRUE);
                AL10.alSourcePlay(alAmbientSource);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static void playBlackHoleEmergenceOnce() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        if (!EMERGENCE_PLAYED.compareAndSet(false, true)) {
            return;
        }
        ensurePrewarmed();
        byte[] ready = pcmEmergence;
        if (ready != null && isOpenALReady() && playOpenALOneShotImmediate(CLIP_EMERGENCE, ready)) {
            return;
        }
        AUDIO_EXEC.execute(() -> {
            if (pcmEmergence == null) {
                bakeInteractiveClips();
            }
            dispatchFallbackClip(pcmEmergence);
        });
    }

    public static void playHover() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastHoverNs < 48_000_000L) {
            return;
        }
        lastHoverNs = now;
        ensurePrewarmed();
        byte[] data = pcmHover;
        if (data == null) {
            return;
        }
        if (isOpenALReady() && playOpenALOneShotImmediate(CLIP_HOVER, data)) {
            return;
        }
        AUDIO_EXEC.execute(() -> dispatchFallbackClip(data));
    }

    public static void playClick() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        byte[] data = pcmClick;
        if (data != null && isOpenALReady() && playOpenALOneShotImmediate(CLIP_CLICK, data)) {
            return;
        }
        AUDIO_EXEC.execute(() -> {
            if (pcmClick == null) {
                bakeInteractiveClips();
            }
            dispatchFallbackClip(pcmClick);
        });
    }

    public static void playToggle(boolean enabled) {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        int idx = enabled ? CLIP_TOGGLE_ON : CLIP_TOGGLE_OFF;
        byte[] data = enabled ? pcmToggleOn : pcmToggleOff;
        if (data != null && isOpenALReady() && playOpenALOneShotImmediate(idx, data)) {
            return;
        }
        AUDIO_EXEC.execute(() -> {
            if (pcmToggleOn == null || pcmToggleOff == null) {
                bakeInteractiveClips();
            }
            dispatchFallbackClip(enabled ? pcmToggleOn : pcmToggleOff);
        });
    }

    public static void playMenuOpen() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        byte[] data = pcmMenuOpen;
        if (data != null && isOpenALReady() && playOpenALOneShotImmediate(CLIP_MENU_OPEN, data)) {
            return;
        }
        AUDIO_EXEC.execute(() -> {
            if (pcmMenuOpen == null) {
                bakeInteractiveClips();
            }
            dispatchFallbackClip(pcmMenuOpen);
        });
    }

    public static void playJoinAction() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        byte[] data = pcmJoin;
        if (data != null && isOpenALReady() && playOpenALOneShotImmediate(CLIP_JOIN, data)) {
            return;
        }
        AUDIO_EXEC.execute(() -> {
            if (pcmJoin == null) {
                bakeInteractiveClips();
            }
            dispatchFallbackClip(pcmJoin);
        });
    }

    private static void bakeInteractiveClips() {
        if (pcmEmergence != null && pcmClick != null) {
            return;
        }
        synchronized (BAKE_INTERACTIVE_LOCK) {
            if (pcmHover == null) pcmHover = synthHover();
            if (pcmClick == null) pcmClick = synthClick();
            if (pcmToggleOn == null) pcmToggleOn = synthToggle(true);
            if (pcmToggleOff == null) pcmToggleOff = synthToggle(false);
            if (pcmMenuOpen == null) pcmMenuOpen = synthMenuOpen();
            if (pcmJoin == null) pcmJoin = synthJoin();
            if (pcmEmergence == null) pcmEmergence = synthBlackHoleEmergence();
        }
    }

    private static void bakeAmbientLoopIfNeeded() {
        if (pcmAmbientLoop != null) {
            return;
        }
        synchronized (BAKE_AMBIENT_LOCK) {
            if (pcmAmbientLoop == null) {
                pcmAmbientLoop = synthBlackHoleAmbientLoop();
            }
        }
    }

    private static void dispatchFallbackClip(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || !PureFPSConfig.sfxEnabled || isOpenALReady()) {
            return;
        }
        playFallbackJavaSoundNow(pcm);
    }

    private static boolean playOpenALOneShotImmediate(int clipIdx, byte[] pcm) {
        if (pcm == null || pcm.length == 0 || !PureFPSConfig.sfxEnabled) {
            return false;
        }
        synchronized (OPENAL_LOCK) {
            if (!isOpenALReady()) {
                return false;
            }
            try {
                int buf = ensureOpenALBufferLocked(clipIdx, pcm);
                if (buf == 0) {
                    return false;
                }
                int src = acquireUiSourceLocked();
                if (src == 0) {
                    return false;
                }
                AL10.alSourceStop(src);
                AL10.alSourcei(src, AL10.AL_BUFFER, buf);
                AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
                AL10.alSourcef(src, AL10.AL_PITCH, 1.0F);
                AL10.alSourcef(src, AL10.AL_GAIN, 1.0F);
                AL10.alSourcePlay(src);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static int ensureOpenALBufferLocked(int clipIdx, byte[] pcm) {
        int existing = alBuffers[clipIdx];
        if (existing != 0 && AL10.alIsBuffer(existing)) {
            return existing;
        }
        int buf = AL10.alGenBuffers();
        if (buf == 0) {
            return 0;
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(pcm.length).order(ByteOrder.nativeOrder());
        direct.put(pcm);
        direct.flip();
        AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, direct, SAMPLE_RATE_HZ);
        alBuffers[clipIdx] = buf;
        return buf;
    }

    private static int acquireUiSourceLocked() {
        for (int i = 0; i < UI_SOURCE_POOL_SIZE; i++) {
            int idx = (alUiRoundRobin + i) % UI_SOURCE_POOL_SIZE;
            int src = alUiSources[idx];
            if (src == 0 || !AL10.alIsSource(src)) {
                src = AL10.alGenSources();
                if (src == 0) {
                    continue;
                }
                configure2DSourceLocked(src, false);
                alUiSources[idx] = src;
                alUiRoundRobin = (idx + 1) % UI_SOURCE_POOL_SIZE;
                return src;
            }
            int state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                alUiRoundRobin = (idx + 1) % UI_SOURCE_POOL_SIZE;
                return src;
            }
        }
        int stealIdx = alUiRoundRobin;
        alUiRoundRobin = (alUiRoundRobin + 1) % UI_SOURCE_POOL_SIZE;
        return alUiSources[stealIdx];
    }

    private static void configure2DSourceLocked(int src, boolean loop) {
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
        AL10.alSource3f(src, AL10.AL_VELOCITY, 0.0F, 0.0F, 0.0F);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0.0F);
        AL10.alSourcef(src, AL10.AL_GAIN, 1.0F);
        AL10.alSourcef(src, AL10.AL_PITCH, 1.0F);
        AL10.alSourcei(src, AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);
    }

    private static void playFallbackJavaSoundNow(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || !PureFPSConfig.sfxEnabled) {
            return;
        }
        try {
            final Clip clip = AudioSystem.getClip();
            clip.addLineListener(evt -> {
                if (evt.getType() == LineEvent.Type.STOP) {
                    try {
                        clip.close();
                    } catch (Throwable ignored) {
                    }
                }
            });
            clip.open(STEREO_16, pcm, 0, pcm.length);
            clip.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Boucle gravitationnelle spatiale du Trou Noir (6.00 s, stéréo sans couture — phase exacte sur multiples entiers) :
     * - Onde sub-grave d'horizon (36 Hz / 54 Hz) + résonance chaude du disque d'accrétion (72 Hz / 108 Hz / 162 Hz)
     * - Souffle relativiste filtré circulaire garantissant 0 clic lors du bouclage continu.
     */
    public static byte[] synthBlackHoleAmbientLoop() {
        final int frames = 44100 * 6; // Exactement 6.00 secondes = 264 600 échantillons
        byte[] out = new byte[frames * 4];
        Random rng = new Random(0x47415247414E54L);

        float[] noiseL = new float[frames];
        float[] noiseR = new float[frames];
        for (int i = 0; i < frames; i++) {
            noiseL[i] = (float) (rng.nextDouble() * 2.0 - 1.0);
            noiseR[i] = (float) (rng.nextDouble() * 2.0 - 1.0);
        }

        double twoPiOverN = (2.0 * Math.PI) / (double) frames;
        double dt = 1.0 / SAMPLE_RATE;

        // Pré-chauffage circulaire du filtre passe-bas sur les 4 096 derniers échantillons pour zéro discontinuité
        double lp1L = 0.0, lp1R = 0.0;
        double lp2L = 0.0, lp2R = 0.0;
        for (int w = frames - 4096; w < frames; w++) {
            double lfo = 0.5 + 0.5 * Math.sin(twoPiOverN * w);
            double cutoff = 115.0 + 95.0 * lfo;
            double rc = 1.0 / (2.0 * Math.PI * cutoff);
            double alpha = dt / (rc + dt);
            lp1L += alpha * (noiseL[w] * 0.16 - lp1L);
            lp1R += alpha * (noiseR[w] * 0.16 - lp1R);
            lp2L += alpha * (lp1L - lp2L);
            lp2R += alpha * (lp1R - lp2R);
        }

        for (int i = 0; i < frames; i++) {
            double u = twoPiOverN * i;

            // LFO lents de respiration gravitationnelle (1 et 2 cycles exacts sur 6 secondes)
            double breath1 = 0.78 + 0.22 * Math.sin(u);
            double breath2 = 0.82 + 0.18 * Math.cos(2.0 * u);

            // Harmoniques exactes (k cycles sur 6.0 s -> continuité C-infinie au point de bouclage)
            // 216/6 = 36 Hz, 324/6 = 54 Hz, 432/6 = 72 Hz, 648/6 = 108 Hz, 972/6 = 162 Hz
            double subL = Math.sin(216.0 * u) * 0.068 + Math.sin(324.0 * u) * 0.036;
            double subR = Math.sin(217.0 * u) * 0.068 + Math.sin(323.0 * u) * 0.036;

            double droneL = (Math.sin(432.0 * u) * 0.044
                    + Math.sin(648.0 * u) * 0.025 * breath2
                    + Math.sin(972.0 * u) * 0.011 * (1.0 - 0.5 * Math.cos(u))) * breath1;
            double droneR = (Math.sin(433.0 * u) * 0.044
                    + Math.sin(647.0 * u) * 0.025 * breath2
                    + Math.sin(971.0 * u) * 0.011 * (1.0 - 0.5 * Math.cos(u))) * breath1;

            double lfoFilter = 0.5 + 0.5 * Math.sin(u);
            double cutoff = 115.0 + 95.0 * lfoFilter;
            double rc = 1.0 / (2.0 * Math.PI * cutoff);
            double alpha = dt / (rc + dt);

            lp1L += alpha * (noiseL[i] * 0.16 - lp1L);
            lp1R += alpha * (noiseR[i] * 0.16 - lp1R);
            lp2L += alpha * (lp1L - lp2L);
            lp2R += alpha * (lp1R - lp2R);

            double mixL = subL + droneL + lp2L * 0.52;
            double mixR = subR + droneR + lp2R * 0.52;
            writeStereoFrame(out, i, mixL, mixR);
        }
        return out;
    }

    /**
     * Émergence cinématique hors de l'Horizon des Événements (3.05 s, stéréo spatiale).
     */
    public static byte[] synthBlackHoleEmergence() {
        int frames = (int) (SAMPLE_RATE * 3.05F);
        byte[] out = new byte[frames * 4];
        Random rng = new Random(0x4D415348L);

        double subPhase = 0.0;
        double h1L = 0.0, h1R = 0.0;
        double h2L = 0.0, h2R = 0.0;
        double h3L = 0.0, h3R = 0.0;
        double h4L = 0.0, h4R = 0.0;
        double lpL = 0.0, lpR = 0.0;
        double lp2L = 0.0, lp2R = 0.0;

        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            double p = i / (double) frames;

            double envIn = smoothstep(0.0, 0.30, t);
            double envOut = 1.0 - smoothstep(1.85, 3.05, t);
            double masterEnv = envIn * envOut;

            double subFreq = 31.0 + 24.0 * smoothstep(0.0, 1.8, t);
            subPhase += (2.0 * Math.PI * subFreq) / SAMPLE_RATE;
            double sub = Math.sin(subPhase) * 0.24 * (1.0 - 0.35 * p);

            double swell = smoothstep(0.18, 1.35, t);
            h1L += (2.0 * Math.PI * 73.42) / SAMPLE_RATE;
            h1R += (2.0 * Math.PI * 73.68) / SAMPLE_RATE;
            h2L += (2.0 * Math.PI * 110.00) / SAMPLE_RATE;
            h2R += (2.0 * Math.PI * 109.65) / SAMPLE_RATE;
            h3L += (2.0 * Math.PI * 164.81) / SAMPLE_RATE;
            h3R += (2.0 * Math.PI * 165.22) / SAMPLE_RATE;
            h4L += (2.0 * Math.PI * 220.00) / SAMPLE_RATE;
            h4R += (2.0 * Math.PI * 219.45) / SAMPLE_RATE;

            double padL = (Math.sin(h1L) * 0.14 + Math.sin(h2L) * 0.11 + Math.sin(h3L) * 0.065 + Math.sin(h4L) * 0.045) * swell;
            double padR = (Math.sin(h1R) * 0.14 + Math.sin(h2R) * 0.11 + Math.sin(h3R) * 0.065 + Math.sin(h4R) * 0.045) * swell;

            double airBell = Math.exp(-Math.pow((t - 0.82) / 0.58, 2.0));
            double whiteL = (rng.nextDouble() * 2.0 - 1.0) * 0.13 * airBell;
            double whiteR = (rng.nextDouble() * 2.0 - 1.0) * 0.13 * airBell;

            double cutoffHz = 90.0 + 1950.0 * smoothstep(0.05, 1.45, t);
            double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
            double dt = 1.0 / SAMPLE_RATE;
            double alpha = dt / (rc + dt);

            lpL += alpha * ((padL + whiteL) - lpL);
            lpR += alpha * ((padR + whiteR) - lpR);
            lp2L += alpha * (lpL - lp2L);
            lp2R += alpha * (lpR - lp2R);

            double mixL = (sub + lp2L) * masterEnv * 0.72;
            double mixR = (sub + lp2R) * masterEnv * 0.72;
            writeStereoFrame(out, i, mixL, mixR);
        }
        return out;
    }

    private static byte[] synthHover() {
        int frames = (int) (SAMPLE_RATE * 0.022F);
        byte[] out = new byte[frames * 4];
        double phase = 0.0;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            double freq = 1320.0 - 420.0 * (i / (double) frames);
            phase += (2.0 * Math.PI * freq) / SAMPLE_RATE;
            double env = Math.sin(Math.PI * Math.min(1.0, i / 45.0)) * Math.exp(-t * 210.0);
            double s = Math.sin(phase) * env * 0.042;
            writeStereoFrame(out, i, s, s);
        }
        return out;
    }

    private static byte[] synthClick() {
        int frames = (int) (SAMPLE_RATE * 0.046F);
        byte[] out = new byte[frames * 4];
        double p1 = 0.0;
        double p2 = 0.0;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            double fBody = 310.0 * Math.exp(-t * 48.0) + 95.0;
            p1 += (2.0 * Math.PI * fBody) / SAMPLE_RATE;
            p2 += (2.0 * Math.PI * 1680.0) / SAMPLE_RATE;
            double att = Math.min(1.0, i / 32.0);
            double s = (Math.sin(p1) * Math.exp(-t * 95.0) * 0.13 + Math.sin(p2) * Math.exp(-t * 260.0) * 0.045) * att;
            writeStereoFrame(out, i, s, s);
        }
        return out;
    }

    private static byte[] synthToggle(boolean on) {
        int frames = (int) (SAMPLE_RATE * 0.072F);
        byte[] out = new byte[frames * 4];
        double p1 = 0.0;
        double p2 = 0.0;
        double fStart = on ? 440.0 : 587.33;
        double fEnd = on ? 659.25 : 392.00;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            double glide = smoothstep(0.0, 0.032, t);
            double f = fStart + (fEnd - fStart) * glide;
            p1 += (2.0 * Math.PI * f) / SAMPLE_RATE;
            p2 += (2.0 * Math.PI * (f * 2.0)) / SAMPLE_RATE;
            double att = Math.min(1.0, i / 40.0);
            double env = att * Math.exp(-t * 58.0);
            double s = (Math.sin(p1) * 0.11 + Math.sin(p2) * 0.03) * env;
            writeStereoFrame(out, i, s * 0.96, s * 1.04);
        }
        return out;
    }

    private static byte[] synthMenuOpen() {
        int frames = (int) (SAMPLE_RATE * 0.14F);
        byte[] out = new byte[frames * 4];
        double p1 = 0.0;
        double p2 = 0.0;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            p1 += (2.0 * Math.PI * (293.66 + 80.0 * smoothstep(0.0, 0.10, t))) / SAMPLE_RATE;
            p2 += (2.0 * Math.PI * 587.33) / SAMPLE_RATE;
            double env = smoothstep(0.0, 0.018, t) * Math.exp(-t * 28.0);
            double s = (Math.sin(p1) * 0.085 + Math.sin(p2) * 0.035) * env;
            writeStereoFrame(out, i, s * 0.94, s * 1.06);
        }
        return out;
    }

    private static byte[] synthJoin() {
        int frames = (int) (SAMPLE_RATE * 0.24F);
        byte[] out = new byte[frames * 4];
        double p1 = 0.0;
        double p2 = 0.0;
        double p3 = 0.0;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) SAMPLE_RATE;
            p1 += (2.0 * Math.PI * 440.0) / SAMPLE_RATE;
            p2 += (2.0 * Math.PI * 554.37) / SAMPLE_RATE;
            p3 += (2.0 * Math.PI * 659.25) / SAMPLE_RATE;
            double env1 = smoothstep(0.0, 0.012, t) * Math.exp(-t * 18.0);
            double env2 = smoothstep(0.035, 0.055, t) * Math.exp(-Math.max(0.0, t - 0.035) * 16.0);
            double sL = (Math.sin(p1) * 0.09 * env1 + Math.sin(p2) * 0.06 * env1 + Math.sin(p3) * 0.08 * env2);
            double sR = (Math.sin(p1) * 0.08 * env1 + Math.sin(p2) * 0.07 * env1 + Math.sin(p3) * 0.09 * env2);
            writeStereoFrame(out, i, sL, sR);
        }
        return out;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    private static void writeStereoFrame(byte[] buf, int frameIdx, double left, double right) {
        int l = (int) Math.round(Math.max(-0.96, Math.min(0.96, left)) * 32767.0);
        int r = (int) Math.round(Math.max(-0.96, Math.min(0.96, right)) * 32767.0);
        int o = frameIdx * 4;
        buf[o] = (byte) (l & 0xFF);
        buf[o + 1] = (byte) ((l >>> 8) & 0xFF);
        buf[o + 2] = (byte) (r & 0xFF);
        buf[o + 3] = (byte) ((r >>> 8) & 0xFF);
    }
}
