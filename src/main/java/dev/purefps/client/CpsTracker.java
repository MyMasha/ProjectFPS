package dev.purefps.client;

/**
 * Compteur CPS (Clics Par Seconde) gauche/droit 100 % primitif sans aucune allocation (zéro ArrayList<Long>).
 */
public final class CpsTracker {

    private static final int RING_CAPACITY = 128;
    private static final int RING_MASK = RING_CAPACITY - 1;
    private static final long WINDOW_MS = 1000L;

    private static final long[] LEFT_TIMESTAMPS = new long[RING_CAPACITY];
    private static final long[] RIGHT_TIMESTAMPS = new long[RING_CAPACITY];
    private static int leftHead = 0;
    private static int rightHead = 0;

    private CpsTracker() {
    }

    public static void recordLeftClick(long nowMs) {
        LEFT_TIMESTAMPS[leftHead] = nowMs;
        leftHead = (leftHead + 1) & RING_MASK;
    }

    public static void recordRightClick(long nowMs) {
        RIGHT_TIMESTAMPS[rightHead] = nowMs;
        rightHead = (rightHead + 1) & RING_MASK;
    }

    public static int getLeftCps(long nowMs) {
        return countRecent(LEFT_TIMESTAMPS, leftHead, nowMs);
    }

    public static int getRightCps(long nowMs) {
        return countRecent(RIGHT_TIMESTAMPS, rightHead, nowMs);
    }

    private static int countRecent(long[] ring, int head, long nowMs) {
        long cutoff = nowMs - WINDOW_MS;
        int count = 0;
        for (int i = 1; i <= RING_CAPACITY; i++) {
            int idx = (head - i) & RING_MASK;
            long ts = ring[idx];
            if (ts <= cutoff || ts == 0L) {
                break;
            }
            count++;
        }
        return count;
    }

    public static void reset() {
        for (int i = 0; i < RING_CAPACITY; i++) {
            LEFT_TIMESTAMPS[i] = 0L;
            RIGHT_TIMESTAMPS[i] = 0L;
        }
        leftHead = 0;
        rightHead = 0;
    }
}
