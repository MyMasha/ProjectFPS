package dev.purefps.core;

import net.minecraft.util.EnumFacing;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * Flood-fill BFS ultra-rapide sur un ring-buffer int[4096] en ThreadLocal.
 * Remplace la LinkedList<Integer> + IntegerCache + EnumFacing.values().clone() de VisGraph vanilla.
 */
public final class FastVisGraphBFS {

    private static final int DX = 1;
    private static final int DZ = 16;
    private static final int DY = 256;

    private static final ThreadLocal<int[]> BFS_QUEUE = ThreadLocal.withInitial(() -> new int[4096]);

    private FastVisGraphBFS() {
    }

    public static Set<EnumFacing> floodFill(BitSet opaqueSet, int startIndex) {
        EnumSet<EnumFacing> visibleFaces = EnumSet.noneOf(EnumFacing.class);
        int[] queue = BFS_QUEUE.get();
        int head = 0;
        int tail = 0;

        opaqueSet.set(startIndex, true);
        queue[tail++] = startIndex;

        while (head < tail) {
            int idx = queue[head++];

            int x = idx & 15;
            int z = (idx >> 4) & 15;
            int y = (idx >> 8) & 15;

            // Enregistrer les faces de bordure atteintes et empiler les voisins non-opaques
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
