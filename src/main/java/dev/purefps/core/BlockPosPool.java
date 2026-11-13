package dev.purefps.core;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/**
 * Pool annulaire ThreadLocal de 32 MutableBlockPos par thread (Chunk Batcher / Client Thread).
 * Permet d'éliminer les ~55 000 allocations de BlockPos.offset() par chunk dans
 * BlockModelRenderer et AmbientOcclusionFace.updateVertexBrightness (qui utilise <= 15 offsets simultanés).
 */
public final class BlockPosPool {

    private static final int POOL_SIZE = 32;
    private static final int POOL_MASK = POOL_SIZE - 1;

    private static final class Ring {
        final BlockPos.MutableBlockPos[] slots = new BlockPos.MutableBlockPos[POOL_SIZE];
        int cursor = 0;

        Ring() {
            for (int i = 0; i < POOL_SIZE; i++) {
                slots[i] = new BlockPos.MutableBlockPos();
            }
        }

        BlockPos nextOffset(BlockPos base, EnumFacing facing) {
            BlockPos.MutableBlockPos m = slots[cursor & POOL_MASK];
            cursor++;
            return m.set(
                    base.getX() + facing.getFrontOffsetX(),
                    base.getY() + facing.getFrontOffsetY(),
                    base.getZ() + facing.getFrontOffsetZ()
            );
        }
        BlockPos nextSet(int x, int y, int z) {
            BlockPos.MutableBlockPos m = slots[cursor & POOL_MASK];
            cursor++;
            return m.set(x, y, z);
        }
    }

    private static final ThreadLocal<Ring> THREAD_RING = ThreadLocal.withInitial(Ring::new);

    private BlockPosPool() {
    }

    public static BlockPos offset(BlockPos base, EnumFacing facing) {
        return THREAD_RING.get().nextOffset(base, facing);
    }

    public static BlockPos acquire(int x, int y, int z) {
        return THREAD_RING.get().nextSet(x, y, z);
    }
}
