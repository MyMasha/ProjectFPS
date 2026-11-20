package dev.purefps.mixin;

import dev.purefps.core.RenderInfoAccess;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation")
public class MixinRenderInfo implements RenderInfoAccess {

    @Shadow
    @Final
    private RenderChunk renderChunk;

    @Override
    public RenderChunk purefps$getRenderChunk() {
        return this.renderChunk;
    }
}
