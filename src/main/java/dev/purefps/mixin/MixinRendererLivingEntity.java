package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontCaches;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Élimine le Regex Pattern.matcher(name).replaceAll("") exécuté par Vanilla dans rotateCorpse
 * sur chaque joueur et chaque mob à chaque frame juste pour vérifier "Dinnerbone" / "Grumm".
 * (Le culling de distance des pseudos est géré en amont zéro-allocation dans MixinRender.doRender.)
 */
@Mixin(RendererLivingEntity.class)
public class MixinRendererLivingEntity {

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
}
