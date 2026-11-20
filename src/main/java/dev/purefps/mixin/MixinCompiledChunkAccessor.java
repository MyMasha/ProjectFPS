package dev.purefps.mixin;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CompiledChunk.class)
public interface MixinCompiledChunkAccessor {

    @Invoker("setLayerUsed")
    void purefps$setLayerUsed(EnumWorldBlockLayer layer);
}
