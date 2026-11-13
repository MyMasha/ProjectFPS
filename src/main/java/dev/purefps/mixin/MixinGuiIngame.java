package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastScoreboardRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. Économise une passe plein-écran alpha-blendée (vignette.png) à chaque frame tout en gardant l'alerte WorldBorder.
 * 2. Fusionne les 17 draw calls OpenGL du Scoreboard Hypixel en un seul lot de 2 quads sans allocations Guava/StringBuilder.
 */
@Mixin(GuiIngame.class)
public abstract class MixinGuiIngame {

    @Shadow
    @Final
    protected Minecraft mc;

    @Shadow
    public abstract FontRenderer getFontRenderer();

    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void purefps$skipVignettePass(float lightLevel, ScaledResolution scaledRes, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || !PureFPSConfig.disableVignette) {
            return;
        }
        if (this.mc.theWorld != null && this.mc.thePlayer != null) {
            WorldBorder border = this.mc.theWorld.getWorldBorder();
            if (border != null) {
                double dist = border.getClosestDistance(this.mc.thePlayer);
                double warnDist = Math.max(border.getWarningDistance(), 5.0D);
                if (dist < warnDist) {
                    return;
                }
            }
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        ci.cancel();
    }

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void purefps$fastBatchedScoreboard(ScoreObjective objective, ScaledResolution scaledRes, CallbackInfo ci) {
        if (PureFPSConfig.enabled && PureFPSConfig.fastScoreboard && objective != null) {
            FastScoreboardRenderer.render(objective, scaledRes, this.getFontRenderer());
            ci.cancel();
        }
    }
}
