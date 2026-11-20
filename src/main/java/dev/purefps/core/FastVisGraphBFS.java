package dev.purefps.core;

import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.util.EnumFacing;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * Flood-fill BFS ultra-rapide sur un ring-buffer int[4096] en ThreadLocal.
 * 1. Remplace la LinkedList<Integer> + IntegerCache + EnumFacing.values().clone() de VisGraph vanilla.
 * 2. Calcule directement SetVisibility depuis le bitmask long[64] opaqueBits d'IslandCoreCuller
 *    (élimine le double scan des 4 096 blocs et l'allocation VisGraph + BitSet par section).
 */
public final class FastVisGraphBFS {

    private static final int DX = 1;
    private static final int DZ = 16;
    private static final int DY = 256;

    private static final int[] BOUNDARY_INDICES = buildBoundaryIndices();

    private static final class Scratch {
        final int[] queue = new int[4096];
        final long[] visited = new long[64];
    }

    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private FastVisGraphBFS() {
    }

    private static int[] buildBoundaryIndices() {
        int[] indices = new int[1352];
        int k = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                        indices[k++] = x | (z << 4) | (y << 8);
                    }
                }
            }
        }
        return indices;
    }

    /**
     * Calcule SetVisibility directement depuis le bitmask opaqueBits[64] déjà collecté par IslandCoreCuller.
     */
    public static SetVisibility computeVisibilityFromOpaqueBits(long[] opaqueBits, int opaqueCount) {
        if (opaqueCount < 256) {
            return IslandCoreCuller.allOpenVisibility();
        }
        if (opaqueCount >= 4096) {
            return IslandCoreCuller.allOpaqueVisibility();
        }

        Scratch sc = SCRATCH.get();
        long[] visited = sc.visited;
        System.arraycopy(opaqueBits, 0, visited, 0, 64);

        SetVisibility setVisibility = new SetVisibility();
        int[] queue = sc.queue;
        int[] boundary = BOUNDARY_INDICES;
        for (int i = 0; i < boundary.length; i++) {
            int startIdx = boundary[i];
            int word = startIdx >>> 6;
            long mask = 1L << (startIdx & 63);
            if ((visited[word] & mask) == 0L) {
                EnumSet<EnumFacing> faces = floodFillLongMask(visited, queue, startIdx);
                if (faces.size() > 1) {
                    setVisibility.setManyVisible(faces);
                }
            }
        }
        return setVisibility;
    }

    private static EnumSet<EnumFacing> floodFillLongMask(long[] visited, int[] queue, int startIndex) {
        EnumSet<EnumFacing> visibleFaces = EnumSet.noneOf(EnumFacing.class);
        int head = 0;
        int tail = 0;

        visited[startIndex >>> 6] |= (1L << (startIndex & 63));
        queue[tail++] = startIndex;

        while (head < tail) {
            int idx = queue[head++];
            int x = idx & 15;
            int z = (idx >>> 4) & 15;
            int y = (idx >>> 8) & 15;

            if (x == 0) {
                visibleFaces.add(EnumFacing.WEST);
            } else {
                int n = idx - DX;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }

            if (x == 15) {
                visibleFaces.add(EnumFacing.EAST);
            } else {
                int n = idx + DX;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }

            if (y == 0) {
                visibleFaces.add(EnumFacing.DOWN);
            } else {
                int n = idx - DY;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }

            if (y == 15) {
                visibleFaces.add(EnumFacing.UP);
            } else {
                int n = idx + DY;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }

            if (z == 0) {
                visibleFaces.add(EnumFacing.NORTH);
            } else {
                int n = idx - DZ;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }

            if (z == 15) {
                visibleFaces.add(EnumFacing.SOUTH);
            } else {
                int n = idx + DZ;
                int w = n >>> 6;
                long m = 1L << (n & 63);
                if ((visited[w] & m) == 0L) {
                    visited[w] |= m;
                    queue[tail++] = n;
                }
            }
        }

        return visibleFaces;
    }

    public static Set<EnumFacing> floodFill(BitSet opaqueSet, int startIndex) {
        EnumSet<EnumFacing> visibleFaces = EnumSet.noneOf(EnumFacing.class);
        int[] queue = SCRATCH.get().queue;
        int head = 0;
        int tail = 0;

        opaqueSet.set(startIndex, true);
        queue[tail++] = startIndex;

        while (head < tail) {
            int idx = queue[head++];

            int x = idx & 15;
            int z = (idx >> 4) & 15;
            int y = (idx >> 8) & 15;

            if (x == 0) {
                visibleFaces.add(EnumFacing.WEST);
            } else {
                int west = idx - DX;
                if (!opaqueSet.get(west)) {
                    opaqueSet.set(west, true);
                    queue[tail++] = west;
                }
            }

            if (x == 15) {
                visibleFaces.add(EnumFacing.EAST);
            } else {
                int east = idx + DX;
                if (!opaqueSet.get(east)) {
                    opaqueSet.set(east, true);
                    queue[tail++] = east;
                }
            }

            if (y == 0) {
                visibleFaces.add(EnumFacing.DOWN);
            } else {
                int down = idx - DY;
                if (!opaqueSet.get(down)) {
                    opaqueSet.set(down, true);
                    queue[tail++] = down;
                }
            }

            if (y == 15) {
                visibleFaces.add(EnumFacing.UP);
            } else {
                int up = idx + DY;
                if (!opaqueSet.get(up)) {
                    opaqueSet.set(up, true);
                    queue[tail++] = up;
                }
            }

            if (z == 0) {
                visibleFaces.add(EnumFacing.NORTH);
            } else {
                int north = idx - DZ;
                if (!opaqueSet.get(north)) {
                    opaqueSet.set(north, true);
                    queue[tail++] = north;
                }
            }

            if (z == 15) {
                visibleFaces.add(EnumFacing.SOUTH);
            } else {
                int south = idx + DZ;
                if (!opaqueSet.get(south)) {
                    opaqueSet.set(south, true);
                    queue[tail++] = south;
                }
            }
        }

        return visibleFaces;
    }
}
