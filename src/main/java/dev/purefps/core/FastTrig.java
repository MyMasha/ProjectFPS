package dev.purefps.core;

/**
 * Mathématiques rapides dédiées au rendu (atan2 polynomiale sans table de 256 Ko).
 *
 * IMPORTANT ANTI-CHEAT (Hypixel Watchdog / NCP / Grim) :
 * MathHelper.sin(float) et MathHelper.cos(float) ne doivent JAMAIS être modifiés,
 * car Entity.moveFlying et EntityPlayer.jump les utilisent pour calculer motionX/motionZ
 * et les anti-cheats serveur prédisent au bit près la table SIN_TABLE[65536] vanilla.
 */
public final class FastTrig {

    private static final int SIN_BITS = 12;
    private static final int SIN_MASK = ~(-1 << SIN_BITS);
    private static final int SIN_COUNT = SIN_MASK + 1;

    private static final float RAD_FULL = (float) (Math.PI * 2.0);
    private static final float RAD_TO_INDEX = SIN_COUNT / RAD_FULL;
    private static final float DEG_FULL = 360.0f;
    private static final float DEG_TO_INDEX = SIN_COUNT / DEG_FULL;
    private static final int COS_OFFSET = SIN_COUNT / 4;

    private static final float[] SIN_TABLE = new float[SIN_COUNT];

    static {
        for (int i = 0; i < SIN_COUNT; i++) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0 / (double) SIN_COUNT);
        }
        for (int i = 0; i < 360; i += 90) {
            SIN_TABLE[(int) (i * DEG_TO_INDEX) & SIN_MASK] = (float) Math.sin(i * Math.PI / 180.0);
        }
    }

    private FastTrig() {
    }

    /**
     * Usage purement visuel interne (jamais injecté dans MathHelper.sin).
     */
    public static float sin(float radians) {
        int idx = (int) Math.floor(radians * RAD_TO_INDEX + 0.5f);
        return SIN_TABLE[idx & SIN_MASK];
    }

    /**
     * Usage purement visuel interne (jamais injecté dans MathHelper.cos).
     */
    public static float cos(float radians) {
        int idx = (int) Math.floor(radians * RAD_TO_INDEX + 0.5f) + COS_OFFSET;
        return SIN_TABLE[idx & SIN_MASK];
    }

    /**
     * Approximation polynomiale minimax d'ordre 3 pour atan2 avec erreur max < 0.0015 rad.
     * Zéro accès mémoire (pas de miss de cache L1) et 0 impact sur la physique joueur.
     */
    public static double fastAtan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }
        double ay = y < 0.0 ? -y : y;
        double ax = x < 0.0 ? -x : x;
        double min = ay < ax ? ay : ax;
        double max = ay > ax ? ay : ax;
        if (max == 0.0) {
            return 0.0;
        }
        double a = min / max;
        double s = a * a;
        double r = ((-0.0464964749 * s + 0.15931422) * s - 0.327622764) * s * a + a;
        if (ay > ax) {
            r = 1.5707963267948966 - r;
        }
        if (x < 0.0) {
            r = 3.141592653589793 - r;
        }
        return y < 0.0 ? -r : r;
    }
}
