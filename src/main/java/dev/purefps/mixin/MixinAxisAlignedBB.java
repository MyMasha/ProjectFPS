package dev.purefps.mixin;

import dev.purefps.core.FastMutableAABB;
import net.minecraft.util.AxisAlignedBB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AxisAlignedBB.class)
public class MixinAxisAlignedBB implements FastMutableAABB {

    @Shadow
    @Final
    @Mutable
    public double minX;

    @Shadow
    @Final
    @Mutable
    public double minY;

    @Shadow
    @Final
    @Mutable
    public double minZ;

    @Shadow
    @Final
    @Mutable
    public double maxX;

    @Shadow
    @Final
    @Mutable
    public double maxY;

    @Shadow
    @Final
    @Mutable
    public double maxZ;

    @Override
    public void purefps$setBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }
}
