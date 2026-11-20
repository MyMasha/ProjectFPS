package dev.purefps.mixin;

import dev.purefps.core.AnimatedSpriteAccess;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TextureAtlasSprite.class)
public class MixinTextureAtlasSprite implements AnimatedSpriteAccess {

    @Unique
    private int purefps$animIndex = -1;

    @Override
    public int purefps$getAnimIndex() {
        return this.purefps$animIndex;
    }

    @Override
    public void purefps$setAnimIndex(int index) {
        this.purefps$animIndex = index;
    }
}
