package dev.purefps.core;

import net.minecraft.block.state.IBlockState;

import java.util.Arrays;

/**
 * Pool ThreadLocal des deux tableaux de 8 000 entrées (64 Ko) alloués par RegionRenderCache
 * lors de chaque recompilation de sous-chunk sur les threads workers.
 * Élimine ~64 Ko d'allocations jetables par sous-chunk compilé (soit ~64 Mo / 1 000 sections).
 */
public final class RegionCachePool {

    public static final int REGION_SIZE = 8000;

    private static final class ThreadBuffers {
        boolean active;
        boolean inUse;
        final int[] combinedLights = new int[REGION_SIZE];
        final IBlockState[] blockStates = new IBlockState[REGION_SIZE];
    }

    private static final ThreadLocal<ThreadBuffers> BUFFERS = new ThreadLocal<ThreadBuffers>() {
        @Override
        protected ThreadBuffers initialValue() {
            return new ThreadBuffers();
        }
    };

    private RegionCachePool() {
    }

    public static void beginPooledBuild() {
        ThreadBuffers tb = BUFFERS.get();
        tb.active = true;
        tb.inUse = false;
    }

    public static boolean isPoolActiveOnCurrentThread() {
        ThreadBuffers tb = BUFFERS.get();
        return tb.active && !tb.inUse;
    }

    public static int[] acquireLights() {
        ThreadBuffers tb = BUFFERS.get();
        if (!tb.active || tb.inUse) {
            int[] fallback = new int[REGION_SIZE];
            Arrays.fill(fallback, -1);
            return fallback;
        }
        Arrays.fill(tb.combinedLights, -1);
        return tb.combinedLights;
    }

    public static IBlockState[] acquireStates() {
        ThreadBuffers tb = BUFFERS.get();
        if (!tb.active || tb.inUse) {
            return new IBlockState[REGION_SIZE];
        }
        tb.inUse = true;
        Arrays.fill(tb.blockStates, null);
        return tb.blockStates;
    }

    public static void endPooledBuild() {
        ThreadBuffers tb = BUFFERS.get();
        if (tb.inUse) {
            Arrays.fill(tb.blockStates, null);
            tb.inUse = false;
        }
        tb.active = false;
    }
}
