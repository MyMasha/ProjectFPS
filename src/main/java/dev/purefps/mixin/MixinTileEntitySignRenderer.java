package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.FastFontBatcher;
import dev.purefps.core.SignTextCacheAccess;
import dev.purefps.module.MashFlags;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.IChatComponent;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation majeure des Panneaux (TileEntitySignRenderer) sans @Overwrite :
 * 1. Cache les 4 lignes formatées directement sur le TileEntitySign (zéro GuiUtilRenderComponents.splitText par frame).
 * 2. Ignore les lignes vides sans appeler FontRenderer.drawString (dans les shops/lobbies, 50%+ des lignes sont vides !).
 * 3. Au-delà de 24 blocs (FPS) ou 32 blocs (Équilibré), conserve le panneau en bois Vanilla mais coupe le rendu des 4 chaînes
 *    de caractères (qui font alors moins de 0.8 pixel de haut et saturaient le CPU/GPU en draw calls).
 */
@Mixin(TileEntitySignRenderer.class)
public abstract class MixinTileEntitySignRenderer extends TileEntitySpecialRenderer<TileEntitySign> {

    @Inject(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntitySign;DDDFI)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/tileentity/TileEntitySignRenderer;getFontRenderer()Lnet/minecraft/client/gui/FontRenderer;"
            ),
            cancellable = true
    )
    private void purefps$renderCachedSignText(
            TileEntitySign te,
            double x,
            double y,
            double z,
            float partialTicks,
            int destroyStage,
            CallbackInfo ci
    ) {
        if (destroyStage >= 0 || !PureFPSConfig.enabled || !(te instanceof SignTextCacheAccess)) {
            return;
        }

        boolean skipDistantText = false;
        if ((PureFPSConfig.fastSignText || MashFlags.signTextLimitEnabled) && te.lineBeingEdited < 0) {
            double distSq = x * x + y * y + z * z;
            double textMaxSq = MashFlags.signTextLimitEnabled
                    ? MashFlags.signTextMaxDistSq
                    : ((PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 576.0D : 1024.0D);
            if (distSq > textMaxSq) {
                skipDistantText = true;
            }
        }

        if (!skipDistantText) {
            float signScale = 0.6666667F;
            FontRenderer fontRenderer = this.getFontRenderer();
            float textScale = 0.015625F * signScale;
            GlStateManager.translate(0.0F, 0.5F * signScale, 0.07F * signScale);
            GlStateManager.scale(textScale, -textScale, textScale);
            GL11.glNormal3f(0.0F, 0.0F, -1.0F * textScale);
            GlStateManager.depthMask(false);

            boolean batchedSign = PureFPSConfig.fastFontRenderer;
            if (batchedSign) {
                FastFontBatcher.beginOuterBatch();
            }
            try {
                SignTextCacheAccess cache = (SignTextCacheAccess) te;
                int lineCount = te.signText.length;
                int baseY = -lineCount * 5;
                for (int k = 0; k < lineCount; ++k) {
                    IChatComponent comp = te.signText[k];
                    if (comp == null) {
                        continue;
                    }
                    String line = cache.purefps$getCachedLine(k, comp, fontRenderer);
                    if (k == te.lineBeingEdited) {
                        String edited = "> " + line + " <";
                        fontRenderer.drawString(edited, -fontRenderer.getStringWidth(edited) / 2, k * 10 + baseY, 0);
                    } else if (!line.isEmpty()) {
                        fontRenderer.drawString(line, -cache.purefps$getCachedHalfWidth(k), k * 10 + baseY, 0);
                    }
                }
            } finally {
                if (batchedSign) {
                    FastFontBatcher.endOuterBatch();
                }
                GlStateManager.depthMask(true);
            }
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
        ci.cancel();
    }
}
