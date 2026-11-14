package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontCaches;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optimisations de RendererLivingEntity (joueurs et entités vivantes) :
 * 1. Élimine le Regex Pattern.matcher(name).replaceAll("") dans rotateCorpse ("Dinnerbone" / "Grumm").
 * 2. Court-circuite les 6 appels redondants à setBrightness() -> entity.getBrightness() (lookup chunk BlockPos !)
 *    dans renderLayers sur chaque joueur indemne (hurtTime <= 0 && deathTime <= 0 && combineTextures == false).
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity<T extends EntityLivingBase> {

    @Shadow
    protected abstract boolean setBrightness(T entitylivingbaseIn, float partialTicks, boolean combineTextures);

    @Shadow
    protected abstract int getColorMultiplier(T entitylivingbaseIn, float lightBrightness, float partialTickTime);

    @Redirect(
            method = "rotateCorpse",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/EnumChatFormatting;getTextWithoutFormattingCodes(Ljava/lang/String;)Ljava/lang/String;"
            )
    )
    private String purefps$fastDinnerboneCheck(String text) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer) {
            return FastFontCaches.resolveDinnerboneToken(text);
        }
        return EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }

    @SuppressWarnings("unchecked")
    @Redirect(
            method = "renderLayers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z"
            )
    )
    private boolean purefps$fastLayerBrightnessCheck(
            RendererLivingEntity<T> self,
            EntityLivingBase entity,
            float partialTicks,
            boolean combineTextures
    ) {
        if (PureFPSConfig.enabled
                && !combineTextures
                && entity.hurtTime <= 0
                && entity.deathTime <= 0
                && this.getColorMultiplier((T) entity, 0.0F, partialTicks) == 0) {
            return false;
        }
        return this.setBrightness((T) entity, partialTicks, combineTextures);
    }
}
