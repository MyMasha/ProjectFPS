package dev.purefps.mixin;

import net.minecraft.client.model.TexturedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TexturedQuad.class)
public interface MixinTexturedQuadAccessor {

    @Accessor("invertNormal")
    boolean purefps$isInvertNormal();
}
