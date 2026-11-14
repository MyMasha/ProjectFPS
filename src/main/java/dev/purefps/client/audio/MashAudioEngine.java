package dev.purefps.client.audio;

import dev.purefps.config.PureFPSConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moteur audio autonome MashClient (44.1 kHz, 16-bit stéréo PCM via javax.sound.sampled) :
 * - Fonctionne dès la toute première milliseconde de l'émergence du Trou Noir (avant même l'init de SoundManager).
 * - Synthèse pré-calculée en arrière-plan sur thread démon : 0 ms de blocage sur le thread de rendu OpenGL.
 * - Ignoré silencieusement dans les environnements sans périphérique audio (tests unitaires CI).
 */
public final class MashAudioEngine {

    private static final float SAMPLE_RATE = 44100.0F;
    private static final AudioFormat STEREO_16 = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);

    private static final AtomicBoolean INIT_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean EMERGENCE_PLAYED = new AtomicBoolean(false);

    private static volatile byte[] pcmEmergence;
    private static volatile byte[] pcmHover;
    private static volatile byte[] pcmClick;
    private static volatile byte[] pcmToggleOn;
    private static volatile byte[] pcmToggleOff;
    private static volatile byte[] pcmMenuOpen;
    private static volatile byte[] pcmJoin;

    private static volatile long lastHoverNs = 0L;

    private static final ExecutorService AUDIO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MashClient-Audio");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private MashAudioEngine() {
    }

    public static void ensurePrewarmed() {
        if (INIT_STARTED.compareAndSet(false, true)) {
            AUDIO_EXEC.execute(MashAudioEngine::bakeAllClips);
        }
    }

    public static void resetBootState() {
        EMERGENCE_PLAYED.set(false);
        ensurePrewarmed();
    }

    public static void playBlackHoleEmergenceOnce() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        if (!EMERGENCE_PLAYED.compareAndSet(false, true)) {
            return;
        }
        ensurePrewarmed();
        AUDIO_EXEC.execute(() -> {
            if (pcmEmergence == null) {
                bakeAllClips();
            }
            playPcmNow(pcmEmergence);
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
        if (data != null) {
            AUDIO_EXEC.execute(() -> playPcmNow(data));
        }
    }

    public static void playClick() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        AUDIO_EXEC.execute(() -> {
            if (pcmClick == null) {
                bakeAllClips();
            }
            playPcmNow(pcmClick);
        });
    }

    public static void playToggle(boolean enabled) {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        AUDIO_EXEC.execute(() -> {
            if (pcmToggleOn == null) {
                bakeAllClips();
            }
            playPcmNow(enabled ? pcmToggleOn : pcmToggleOff);
        });
    }

    public static void playMenuOpen() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        AUDIO_EXEC.execute(() -> {
            if (pcmMenuOpen == null) {
                bakeAllClips();
            }
            playPcmNow(pcmMenuOpen);
        });
    }

    public static void playJoinAction() {
        if (!PureFPSConfig.sfxEnabled) {
            return;
        }
        ensurePrewarmed();
        AUDIO_EXEC.execute(() -> {
            if (pcmJoin == null) {
                bakeAllClips();
            }
            playPcmNow(pcmJoin);
        });
    }

    private static synchronized void bakeAllClips() {
        if (pcmClick != null) {
            return;
        }
        pcmHover = synthHover();
        pcmClick = synthClick();
        pcmToggleOn = synthToggle(true);
        pcmToggleOff = synthToggle(false);
        pcmMenuOpen = synthMenuOpen();
        pcmJoin = synthJoin();
        pcmEmergence = synthBlackHoleEmergence();
    }

    private static void playPcmNow(byte[] pcm) {
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
            // Aucun périphérique audio (ex: serveur CI headless) : ignoré sans erreur
        }
    }

    /**
     * Émergence cinématique hors de l'Horizon des Événements (3.05 s, stéréo spatiale) :
     * grondement sub-grave gravitationnel + ouverture progressive du filtre + accord harmonique chaud (Ré majeur 9 / quinte).
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

            // Enveloppe globale douce sans clic (fade-in 0.28s, maintien ample, extinction cosinus sur la dernière seconde)
            double envIn = smoothstep(0.0, 0.30, t);
            double envOut = 1.0 - smoothstep(1.85, 3.05, t);
            double masterEnv = envIn * envOut;

            // 1. Onde gravitationnelle sub-basse (31 Hz -> 55 Hz)
            double subFreq = 31.0 + 24.0 * smoothstep(0.0, 1.8, t);
            subPhase += (2.0 * Math.PI * subFreq) / SAMPLE_RATE;
            double sub = Math.sin(subPhase) * 0.24 * (1.0 - 0.35 * p);

            // 2. Accord spatial chaud (D2 73.42 Hz, A2 110 Hz, E3 164.81 Hz, F#3 185.0 Hz) avec léger désaccord stéréo
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

            // 3. Souffle relativiste filtré (décompression à la sortie du trou noir)
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
