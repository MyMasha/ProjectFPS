package dev.purefps.core;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;

public final class EnumCaches {

    public static final EnumFacing[] FACINGS = EnumFacing.VALUES;

    public static final EnumWorldBlockLayer[] BLOCK_LAYERS = new EnumWorldBlockLayer[]{
            EnumWorldBlockLayer.SOLID,
            EnumWorldBlockLayer.CUTOUT_MIPPED,
            EnumWorldBlockLayer.CUTOUT,
            EnumWorldBlockLayer.TRANSLUCENT
    };

    private EnumCaches() {
    }
}
