package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.SmartAnimations;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Smart Animations v5.0 : court-circuit avant TextureUtil.bindTexture() quand aucun sprite animé
 * n'est visible dans les chunks/items rendus, et boucle indexée zéro-Iterator en O(1) par bitmask.
 */
@Mixin(TextureMap.class)
public abstract class MixinTextureMap extends AbstractTexture {

    @Shadow
    @Final
    private List<TextureAtlasSprite> listAnimatedSprites;

    @Shadow
    @Final
    private String basePath;

    /**
     * @author MashClient
     * @reason Évite TextureUtil.bindTexture() si aucun sprite animé n'est visible + test bitmask O(1) sans Iterator
     */
    @Overwrite
    public void updateAnimations() {
        List<TextureAtlasSprite> list = this.listAnimatedSprites;
        int n = list.size();
        boolean isBlocksAtlas = "textures".equals(this.basePath);

        if (isBlocksAtlas) {
            PureFPSConfig.totalAnimationsCount = n;
            PureFPSConfig.skippedAnimationsLastTick = 0;
            if (PureFPSConfig.enabled && PureFPSConfig.smartAnimations) {
                SmartAnimations.syncAnimatedSprites(list);
                SmartAnimations.beginTick();
                if (!SmartAnimations.hasAnyAnimationThisTick()) {
                    PureFPSConfig.skippedAnimationsLastTick = n;
                    return;
                }
            }
        }

        net.minecraft.client.renderer.GlStateManager.bindTexture(this.getGlTextureId());

        if (isBlocksAtlas && PureFPSConfig.enabled && PureFPSConfig.smartAnimations) {
            int skipped = 0;
            for (int i = 0; i < n; i++) {
                TextureAtlasSprite sprite = list.get(i);
                if (SmartAnimations.shouldAnimateThisTick(sprite)) {
                    sprite.updateAnimation();
                } else {
                    skipped++;
                }
            }
            PureFPSConfig.skippedAnimationsLastTick = skipped;
            return;
        }

        for (int i = 0; i < n; i++) {
            list.get(i).updateAnimation();
        }
    }
}
