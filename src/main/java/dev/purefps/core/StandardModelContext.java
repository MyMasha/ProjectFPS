package dev.purefps.core;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.BitSet;

/**
 * Contexte ThreadLocal unique par worker de chunk pour BlockModelRenderer (Standard et Ambient Occlusion) :
 * - Réutilise BitSet(3), MutableBlockPos d'offset, et anneau de 32 MutableBlockPos pour AmbientOcclusionFace
 *   sans aucun ThreadLocal.get() par face.
 */
public final class StandardModelContext {

    public static final int AO_RING_SIZE = 32;
    public static final int AO_RING_MASK = AO_RING_SIZE - 1;

    private static final ThreadLocal<StandardModelContext> LOCAL =
            new ThreadLocal<StandardModelContext>() {
                @Override
                protected StandardModelContext initialValue() {
                    return new StandardModelContext();
                }
            };

    public final BitSet boundsFlags = new BitSet(3);
    public final BitSet aoBoundsFlags = new BitSet(3);
    public final BlockPos.MutableBlockPos offsetPos = new BlockPos.MutableBlockPos();
    public final BlockPos.MutableBlockPos aoOffsetPos = new BlockPos.MutableBlockPos();
    public final BlockPos.MutableBlockPos[] aoFaceRing = new BlockPos.MutableBlockPos[AO_RING_SIZE];

    private StandardModelContext() {
        for (int i = 0; i < AO_RING_SIZE; i++) {
            this.aoFaceRing[i] = new BlockPos.MutableBlockPos();
        }
    }

    public static StandardModelContext get() {
        StandardModelContext ctx = LOCAL.get();
        ctx.boundsFlags.clear();
        return ctx;
    }

    public static BitSet getBoundsFlags() {
        StandardModelContext ctx = LOCAL.get();
        ctx.boundsFlags.clear();
        return ctx.boundsFlags;
    }

    public static BlockPos offsetStandardPos(BlockPos base, EnumFacing facing) {
        BlockPos.MutableBlockPos mp = LOCAL.get().offsetPos;
        mp.set(
                base.getX() + facing.getFrontOffsetX(),
                base.getY() + facing.getFrontOffsetY(),
                base.getZ() + facing.getFrontOffsetZ()
        );
        return mp;
    }

    public static BitSet getAoBoundsFlags() {
        StandardModelContext ctx = LOCAL.get();
        ctx.aoBoundsFlags.clear();
        return ctx.aoBoundsFlags;
    }

    public static BlockPos offsetAoPos(BlockPos base, EnumFacing facing) {
        BlockPos.MutableBlockPos mp = LOCAL.get().aoOffsetPos;
        mp.set(
                base.getX() + facing.getFrontOffsetX(),
                base.getY() + facing.getFrontOffsetY(),
                base.getZ() + facing.getFrontOffsetZ()
        );
        return mp;
    }

    public static BlockPos.MutableBlockPos[] getAoFaceRing() {
        return LOCAL.get().aoFaceRing;
    }
}
