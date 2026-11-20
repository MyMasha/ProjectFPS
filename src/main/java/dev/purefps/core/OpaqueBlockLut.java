package dev.purefps.core;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;

/**
 * Table de correspondance O(1) (4 096 octets en cache L1) pour tester isOpaqueCube() et
 * isOpaqueCube() && isFullCube() directement depuis le tableau char[4096] d'un ExtendedBlockStorage
 * sans déréférencer Block.BLOCK_STATE_IDS ni appeler de méthode virtuelle à chaque voxel.
 *
 * Dans Minecraft 1.8.9, Block.isOpaqueCube() et Block.isFullCube() dépendent uniquement du Block
 * (pas des métadonnées) et l'ID de bloc est encodé dans rawState >>> 4 (0..4095).
 */
public final class OpaqueBlockLut {

    private static final byte UNKNOWN = 0;
    private static final byte NON_OPAQUE = 1;
    private static final byte OPAQUE_ONLY = 2;
    private static final byte OPAQUE_FULL_CUBE = 3;

    private static final byte[] FLAGS = new byte[4096];

    static {
        // ID 0 = Blocks.air : toujours non-opaque
        FLAGS[0] = NON_OPAQUE;
    }

    private OpaqueBlockLut() {
    }

    public static boolean isOpaqueCube(char rawState) {
        if (rawState == 0) {
            return false;
        }
        int blockId = (rawState >>> 4) & 4095;
        byte f = FLAGS[blockId];
        if (f == UNKNOWN) {
            f = populate(blockId, rawState);
        }
        return f >= OPAQUE_ONLY;
    }

    public static boolean isOpaqueFullCube(char rawState) {
        if (rawState == 0) {
            return false;
        }
        int blockId = (rawState >>> 4) & 4095;
        byte f = FLAGS[blockId];
        if (f == UNKNOWN) {
            f = populate(blockId, rawState);
        }
        return f == OPAQUE_FULL_CUBE;
    }

    private static byte populate(int blockId, char rawState) {
        Block block = Block.getBlockById(blockId);
        if (block == null) {
            IBlockState state = (IBlockState) Block.BLOCK_STATE_IDS.getByValue(rawState);
            block = (state != null) ? state.getBlock() : null;
        }
        if (block == null) {
            FLAGS[blockId] = NON_OPAQUE;
            return NON_OPAQUE;
        }
        byte f;
        if (!block.isOpaqueCube()) {
            f = NON_OPAQUE;
        } else if (block.isFullCube()) {
            f = OPAQUE_FULL_CUBE;
        } else {
            f = OPAQUE_ONLY;
        }
        FLAGS[blockId] = f;
        return f;
    }

    public static void clear() {
        java.util.Arrays.fill(FLAGS, UNKNOWN);
        FLAGS[0] = NON_OPAQUE;
    }
}
