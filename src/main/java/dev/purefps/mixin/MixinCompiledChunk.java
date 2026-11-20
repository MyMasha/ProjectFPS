package dev.purefps.mixin;

import dev.purefps.core.CompiledChunkAnimAccess;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CompiledChunk.class)
public class MixinCompiledChunk implements CompiledChunkAnimAccess {

    @Unique
    private long purefps$animMask0 = 0L;

    @Unique
    private long purefps$animMask1 = 0L;

    @Override
    public long purefps$getAnimMask0() {
        return this.purefps$animMask0;
    }

    @Override
    public long purefps$getAnimMask1() {
        return this.purefps$animMask1;
    }

    @Override
    public void purefps$setAnimMasks(long mask0, long mask1) {
        this.purefps$animMask0 = mask0;
        this.purefps$animMask1 = mask1;
    }
}
