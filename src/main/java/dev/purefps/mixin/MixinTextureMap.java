package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartAnimations;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Smart Animations v2.1 sans aucun hook dans la boucle de rendu (0 surcharge CPU par frame).
 */
@Mixin(TextureMap.class)
public class MixinTextureMap {

    @Shadow
    @Final
    private List<TextureAtlasSprite> listAnimatedSprites;

    @Shadow
    @Final
    private String basePath;

    @Inject(method = "updateAnimations", at = @At("HEAD"))
    private void purefps$beginUpdateAnimations(CallbackInfo ci) {
        if ("textures".equals(this.basePath)) {
            PureFPSConfig.totalAnimationsCount = this.listAnimatedSprites.size();
            PureFPSConfig.skippedAnimationsLastTick = 0;
            if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations) {
                SmartAnimations.beginTick();
            }
        }
    }

    @Redirect(
            method = "updateAnimations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;updateAnimation()V"
            )
    )
    private void purefps$updateVisibleSpriteOnly(TextureAtlasSprite sprite) {
        if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations && "textures".equals(this.basePath)) {
            if (!SmartAnimations.shouldAnimateThisTick(sprite)) {
                PureFPSConfig.skippedAnimationsLastTick++;
                return;
            }
        }
        sprite.updateAnimation();
    }
}
