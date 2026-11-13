package dev.purefps.mixin;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(ChunkRenderContainer.class)
public abstract class MixinChunkRenderContainer {

    @Shadow
    protected List<RenderChunk> renderChunks;

    @Shadow
    protected boolean initialized;

    @Shadow
    private double viewEntityX;

    @Shadow
    private double viewEntityY;

    @Shadow
    private double viewEntityZ;

    @Unique
    protected final double purefps$getViewX() {
        return this.viewEntityX;
    }

    @Unique
    protected final double purefps$getViewY() {
        return this.viewEntityY;
    }

    @Unique
    protected final double purefps$getViewZ() {
        return this.viewEntityZ;
    }
}
