package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.module.MashFlags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Module Tab List Pro (vis_tab_list) :
 * affiche le ping numérique exact (ex: 24ms) coloré selon la qualité de connexion à la place des 5 barres Vanilla.
 */
@Mixin(GuiPlayerTabOverlay.class)
public class MixinGuiPlayerTabOverlay {

    @Shadow
    @Final
    private Minecraft mc;

    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    private void purefps$drawNumericTabPing(int width, int x, int y, NetworkPlayerInfo info, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || !MashFlags.tabPingNumbers || info == null || this.mc == null || this.mc.fontRendererObj == null) {
            return;
        }
        int ping = info.getResponseTime();
        if (ping <= 0) {
            return;
        }
        int color;
        if (ping < 65) {
            color = 0x34D399; // Émeraude
        } else if (ping < 130) {
            color = 0xF5B85C; // Or
        } else if (ping < 220) {
            color = 0xFB923C; // Orange
        } else {
            color = 0xF87171; // Corail
        }

        String txt = ping > 999 ? "999" : String.valueOf(ping);
        GlStateManager.pushMatrix();
        float scale = 0.72F;
        int rightEdge = x + width - 1;
        int textW = Math.round(this.mc.fontRendererObj.getStringWidth(txt) * scale);
        GlStateManager.translate(rightEdge - textW, y + 1.5F, 100.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        this.mc.fontRendererObj.drawStringWithShadow(txt, 0.0F, 0.0F, color);
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        ci.cancel();
    }
}
