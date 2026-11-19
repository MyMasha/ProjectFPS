package dev.purefps.core;

import net.minecraft.util.BlockPos;

import java.util.BitSet;

/**
 * Contexte ThreadLocal unique par worker de chunk pour BlockModelRenderer.renderModelStandard
 * (évite new BitSet(3), 6x BlockPos.offset et double ThreadLocal.get() sur chaque bloc en profil FPS).
 */
public final class StandardModelContext {

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

    private StandardModelContext() {
    }

    public static StandardModelContext get() {
        StandardModelContext ctx = LOCAL.get();
        ctx.boundsFlags.clear();
        return ctx;
    }

    public static BitSet getAoBoundsFlags() {
        StandardModelContext ctx = LOCAL.get();
        ctx.aoBoundsFlags.clear();
        return ctx.aoBoundsFlags;
    }
}
