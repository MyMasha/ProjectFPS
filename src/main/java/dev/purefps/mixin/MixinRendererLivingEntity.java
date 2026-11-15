package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontCaches;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.FloatBuffer;

/**
 * Optimisations de RendererLivingEntity (joueurs et entités vivantes) :
 * 1. Élimine le Regex Pattern.matcher(name).replaceAll("") dans rotateCorpse ("Dinnerbone" / "Grumm").
 * 2. Court-circuite les 6 appels redondants à setBrightness() -> entity.getBrightness() (lookup chunk BlockPos !)
 *    dans renderLayers sur chaque joueur indemne (hurtTime <= 0 && deathTime <= 0 && combineTextures == false).
 * 3. Applique le module Hit Color (pvp_hit_color) et l'affichage du pseudo en F5 (vis_nametags).
 */
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity<T extends EntityLivingBase> {

    @Shadow
    protected FloatBuffer brightnessBuffer;

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

    @Inject(
            method = "setBrightness",
            at = @At(value = "INVOKE", target = "Ljava/nio/FloatBuffer;flip()Ljava/nio/Buffer;", remap = false)
    )
    private void purefps$applyCustomHitColor(T entitylivingbaseIn, float partialTicks, boolean combineTextures, CallbackInfoReturnable<Boolean> cir) {
        if (!PureFPSConfig.enabled || !MashFlags.hitColorEnabled || this.brightnessBuffer == null) {
            return;
        }
        if (entitylivingbaseIn.hurtTime > 0 || entitylivingbaseIn.deathTime > 0) {
            int argb = MashFlags.hitColorArgb;
            if (MashFlags.hitColorChroma) {
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha <= 0) alpha = 102;
                float hue = (System.currentTimeMillis() % 3200L) / 3200.0F;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.82F, 1.0F) & 0x00FFFFFF;
                argb = (alpha << 24) | rgb;
            }
            float a = ((argb >>> 24) & 0xFF) / 255.0F;
            float r = ((argb >>> 16) & 0xFF) / 255.0F;
            float g = ((argb >>> 8) & 0xFF) / 255.0F;
            float b = (argb & 0xFF) / 255.0F;
            this.brightnessBuffer.position(0);
            this.brightnessBuffer.put(r);
            this.brightnessBuffer.put(g);
            this.brightnessBuffer.put(b);
            this.brightnessBuffer.put(a);
        }
    }

    @Inject(method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z", at = @At("HEAD"), cancellable = true)
    private void purefps$showOwnNametagInF5(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (PureFPSConfig.enabled && MashFlags.nametagShowOwnInF5) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && entity == mc.thePlayer && mc.gameSettings != null
                    && mc.gameSettings.thirdPersonView > 0 && !mc.gameSettings.hideGUI && !entity.isSneaking()) {
                cir.setReturnValue(Minecraft.isGuiEnabled());
            }
        }
    }
}
