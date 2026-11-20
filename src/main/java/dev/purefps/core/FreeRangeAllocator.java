package dev.purefps.core;

import java.util.Arrays;

/**
 * Allocateur d'intervalles primitifs zéro-allocation (en nombre de sommets de 28 octets)
 * pour les arènes VBO régionales GPU (GL14.glMultiDrawArrays).
 * Fusionne immédiatement les blocs libres adjacents et réduit la taille utile en fin de tampon.
 */
public final class FreeRangeAllocator {

    public static final int ALIGN_VERTICES = 64;

    private int[] freeStarts = new int[32];
    private int[] freeLengths = new int[32];
    private int freeCount = 0;
    private int highWaterMark = 0;
    private int liveAllocations = 0;

    public static int alignVertices(int vertexCount) {
        if (vertexCount <= 0) {
            return 0;
        }
        return (vertexCount + (ALIGN_VERTICES - 1)) & ~(ALIGN_VERTICES - 1);
    }

    public int allocate(int alignedVertices) {
        if (alignedVertices <= 0) {
            return -1;
        }
        for (int i = 0; i < freeCount; i++) {
            int len = freeLengths[i];
            if (len >= alignedVertices) {
                int start = freeStarts[i];
                if (len == alignedVertices) {
                    removeAt(i);
                } else {
                    freeStarts[i] = start + alignedVertices;
                    freeLengths[i] = len - alignedVertices;
                }
                liveAllocations++;
                return start;
            }
        }
        int start = highWaterMark;
        highWaterMark += alignedVertices;
        liveAllocations++;
        return start;
    }

    public void free(int startVertex, int alignedVertices) {
        if (startVertex < 0 || alignedVertices <= 0) {
            return;
        }
        if (liveAllocations > 0) {
            liveAllocations--;
        }
        if (liveAllocations == 0) {
            freeCount = 0;
            highWaterMark = 0;
            return;
        }

        // Recherche binaire de la position d'insertion triée par startVertex
        int lo = 0;
        int hi = freeCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midVal = freeStarts[mid];
            if (midVal < startVertex) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        int idx = lo;

        boolean mergePrev = idx > 0 && (freeStarts[idx - 1] + freeLengths[idx - 1] == startVertex);
        boolean mergeNext = idx < freeCount && (startVertex + alignedVertices == freeStarts[idx]);

        if (mergePrev && mergeNext) {
            freeLengths[idx - 1] += alignedVertices + freeLengths[idx];
            removeAt(idx);
            trimTailIfPossible(idx - 1);
        } else if (mergePrev) {
            freeLengths[idx - 1] += alignedVertices;
            trimTailIfPossible(idx - 1);
        } else if (mergeNext) {
            freeStarts[idx] = startVertex;
            freeLengths[idx] += alignedVertices;
            trimTailIfPossible(idx);
        } else {
            if (startVertex + alignedVertices == highWaterMark) {
                highWaterMark = startVertex;
                if (freeCount > 0) {
                    trimTailIfPossible(freeCount - 1);
                }
                return;
            }
            insertAt(idx, startVertex, alignedVertices);
        }
    }

    private void trimTailIfPossible(int index) {
        if (index >= 0 && index == freeCount - 1) {
            if (freeStarts[index] + freeLengths[index] == highWaterMark) {
                highWaterMark = freeStarts[index];
                freeCount--;
            }
        }
    }

    private void insertAt(int idx, int start, int length) {
        if (freeCount == freeStarts.length) {
            int newCap = freeStarts.length * 2;
            freeStarts = Arrays.copyOf(freeStarts, newCap);
            freeLengths = Arrays.copyOf(freeLengths, newCap);
        }
        int move = freeCount - idx;
        if (move > 0) {
            System.arraycopy(freeStarts, idx, freeStarts, idx + 1, move);
            System.arraycopy(freeLengths, idx, freeLengths, idx + 1, move);
        }
        freeStarts[idx] = start;
        freeLengths[idx] = length;
        freeCount++;
    }

    private void removeAt(int idx) {
        int move = freeCount - idx - 1;
        if (move > 0) {
            System.arraycopy(freeStarts, idx + 1, freeStarts, idx, move);
            System.arraycopy(freeLengths, idx + 1, freeLengths, idx, move);
        }
        freeCount--;
    }

    public int getHighWaterMark() {
        return highWaterMark;
    }

    public int getLiveAllocations() {
        return liveAllocations;
    }

    public int getFreeBlockCount() {
        return freeCount;
    }

    public void reset() {
        freeCount = 0;
        highWaterMark = 0;
        liveAllocations = 0;
    }
}
