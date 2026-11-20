package dev.purefps.core;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Tri rapide in-place par primitives (int[] + float[]) sans aucun autoboxing Integer[],
 * ni Comparator lambda, ni BitSet, ni IntBuffer.slice() par quad.
 * Élimine 100 % des allocations GC lors du tri des quads translucides (eau, glace, vitraux, TESR).
 */
public final class PrimitiveQuadSorter {

    private static final ThreadLocal<SortScratch> SCRATCH = ThreadLocal.withInitial(SortScratch::new);

    private PrimitiveQuadSorter() {
    }

    public static void sortQuads(
            IntBuffer rawIntBuffer,
            FloatBuffer rawFloatBuffer,
            int vertexCount,
            int vertexIntSize,
            float cameraX,
            float cameraY,
            float cameraZ
    ) {
        int quadCount = vertexCount / 4;
        if (quadCount <= 1) {
            return;
        }

        int quadIntStride = vertexIntSize * 4;
        SortScratch scratch = SCRATCH.get();
        scratch.ensureCapacity(quadCount, quadIntStride);

        float[] distances = scratch.distances;
        int[] indices = scratch.indices;
        boolean[] visited = scratch.visited;
        int[] tempQuad = scratch.tempQuad;
        int[] moveQuad = scratch.moveQuad;

        // 1. Calcul des distances au carré des centres de chaque quad vers la caméra
        for (int i = 0; i < quadCount; i++) {
            int base = i * quadIntStride;
            float x0 = rawFloatBuffer.get(base);
            float y0 = rawFloatBuffer.get(base + 1);
            float z0 = rawFloatBuffer.get(base + 2);

            int v1 = base + vertexIntSize;
            float x1 = rawFloatBuffer.get(v1);
            float y1 = rawFloatBuffer.get(v1 + 1);
            float z1 = rawFloatBuffer.get(v1 + 2);

            int v2 = v1 + vertexIntSize;
            float x2 = rawFloatBuffer.get(v2);
            float y2 = rawFloatBuffer.get(v2 + 1);
            float z2 = rawFloatBuffer.get(v2 + 2);

            int v3 = v2 + vertexIntSize;
            float x3 = rawFloatBuffer.get(v3);
            float y3 = rawFloatBuffer.get(v3 + 1);
            float z3 = rawFloatBuffer.get(v3 + 2);

            float dx = (x0 + x1 + x2 + x3) * 0.25f - cameraX;
            float dy = (y0 + y1 + y2 + y3) * 0.25f - cameraY;
            float dz = (z0 + z1 + z2 + z3) * 0.25f - cameraZ;

            distances[i] = dx * dx + dy * dy + dz * dz;
            indices[i] = i;
            visited[i] = false;
        }

        // 2. Tri décroissant (du plus loin au plus proche pour le blending alpha) sans boxing
        dualPivotDescSort(indices, distances, 0, quadCount - 1);

        // 3. Permutation cyclique directe dans rawIntBuffer sans allouer d'IntBuffer.slice()
        int savedLimit = rawIntBuffer.limit();
        int savedPos = rawIntBuffer.position();
        rawIntBuffer.limit(quadCount * quadIntStride);

        for (int start = 0; start < quadCount; start++) {
            if (visited[start]) {
                continue;
            }
            int from = indices[start];
            if (from == start) {
                visited[start] = true;
                continue;
            }

            // Sauvegarder le quad à 'from'
            rawIntBuffer.position(from * quadIntStride);
            rawIntBuffer.get(tempQuad, 0, quadIntStride);

            int current = from;
            int next = indices[current];
            while (current != start) {
                rawIntBuffer.position(next * quadIntStride);
                rawIntBuffer.get(moveQuad, 0, quadIntStride);

                rawIntBuffer.position(current * quadIntStride);
                rawIntBuffer.put(moveQuad, 0, quadIntStride);

                visited[current] = true;
                current = next;
                next = indices[current];
            }

            rawIntBuffer.position(start * quadIntStride);
            rawIntBuffer.put(tempQuad, 0, quadIntStride);
            visited[start] = true;
        }

        rawIntBuffer.limit(savedLimit);
        rawIntBuffer.position(savedPos);
    }

    private static void dualPivotDescSort(int[] indices, float[] distances, int low, int high) {
        while (low < high) {
            if (high - low < 24) {
                // Insertion sort pour les petites partitions
                for (int i = low + 1; i <= high; i++) {
                    int idx = indices[i];
                    float dist = distances[idx];
                    int j = i - 1;
                    while (j >= low && dist > distances[indices[j]]) {
                        indices[j + 1] = indices[j];
                        j--;
                    }
                    indices[j + 1] = idx;
                }
                return;
            }

            int mid = (low + high) >>> 1;
            // Médiane de 3 pour éviter le pire cas O(n²) sur quads déjà triés
            if (distances[indices[mid]] > distances[indices[low]]) {
                swap(indices, low, mid);
            }
            if (distances[indices[high]] > distances[indices[low]]) {
                swap(indices, low, high);
            }
            if (distances[indices[high]] > distances[indices[mid]]) {
                swap(indices, mid, high);
            }

            float pivotDist = distances[indices[mid]];
            int i = low;
            int j = high;

            while (i <= j) {
                while (distances[indices[i]] > pivotDist) {
                    i++;
                }
                while (distances[indices[j]] < pivotDist) {
                    j--;
                }
                if (i <= j) {
                    swap(indices, i, j);
                    i++;
                    j--;
                }
            }

            // Récursion sur la plus petite moitié uniquement (garantit O(log n) de pile)
            if (j - low < high - i) {
                if (low < j) {
                    dualPivotDescSort(indices, distances, low, j);
                }
                low = i;
            } else {
                if (i < high) {
                    dualPivotDescSort(indices, distances, i, high);
                }
                high = j;
            }
        }
    }

    private static void swap(int[] arr, int a, int b) {
        int tmp = arr[a];
        arr[a] = arr[b];
        arr[b] = tmp;
    }

    private static final class SortScratch {
        float[] distances = new float[512];
        int[] indices = new int[512];
        boolean[] visited = new boolean[512];
        int[] tempQuad = new int[64];
        int[] moveQuad = new int[64];

        void ensureCapacity(int quadCount, int quadIntStride) {
            if (distances.length < quadCount) {
                int newCap = Math.max(quadCount, distances.length * 2);
                distances = new float[newCap];
                indices = new int[newCap];
                visited = new boolean[newCap];
            }
            if (tempQuad.length < quadIntStride) {
                int newStride = Math.max(quadIntStride, tempQuad.length * 2);
                tempQuad = new int[newStride];
                moveQuad = new int[newStride];
            }
        }
    }
}
