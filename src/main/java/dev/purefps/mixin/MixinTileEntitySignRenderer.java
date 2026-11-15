package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.config.PureFPSProfile;
import dev.purefps.core.SignTextCacheAccess;
import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.model.ModelSign;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Optimisation majeure des Panneaux (TileEntitySignRenderer) :
 * 1. Cache les 4 lignes formatées directement sur le TileEntitySign (zéro GuiUtilRenderComponents.splitText par frame).
 * 2. Ignore les lignes vides sans appeler FontRenderer.drawString (dans les shops/lobbies, 50%+ des lignes sont vides !).
 * 3. Au-delà de 24 blocs (FPS) ou 32 blocs (Équilibré), dessine le panneau en bois mais coupe le rendu des 4 chaînes
 *    de caractères (qui font alors moins de 0.8 pixel de haut et saturaient le CPU/GPU en draw calls).
 */
@Mixin(TileEntitySignRenderer.class)
public abstract class MixinTileEntitySignRenderer extends TileEntitySpecialRenderer<TileEntitySign> {

    @Shadow
    @Final
    private static ResourceLocation SIGN_TEXTURE;

    @Shadow
    @Final
    private ModelSign model;

    /**
     * @author PureClient
     * @reason Cache zéro-allocation des textes de panneaux + LOD de texte distant sans masquer le panneau en bois
     */
    @Overwrite
    public void renderTileEntityAt(TileEntitySign te, double x, double y, double z, float partialTicks, int destroyStage) {
        Block block = te.getBlockType();
        GlStateManager.pushMatrix();
        float f = 0.6666667F;
        if (block == Blocks.standing_sign) {
            GlStateManager.translate((float) x + 0.5F, (float) y + 0.75F * f, (float) z + 0.5F);
            float g = (float) (te.getBlockMetadata() * 360) / 16.0F;
            GlStateManager.rotate(-g, 0.0F, 1.0F, 0.0F);
            this.model.signStick.showModel = true;
        } else {
            int meta = te.getBlockMetadata();
            float rot = 0.0F;
            if (meta == 2) {
                rot = 180.0F;
            } else if (meta == 4) {
                rot = 90.0F;
            } else if (meta == 5) {
                rot = -90.0F;
            }
            GlStateManager.translate((float) x + 0.5F, (float) y + 0.75F * f, (float) z + 0.5F);
            GlStateManager.rotate(-rot, 0.0F, 1.0F, 0.0F);
            GlStateManager.translate(0.0F, -0.3125F, -0.4375F);
            this.model.signStick.showModel = false;
        }

        if (destroyStage >= 0) {
            this.bindTexture(DESTROY_STAGES[destroyStage]);
            GlStateManager.matrixMode(GL11.GL_TEXTURE);
            GlStateManager.pushMatrix();
            GlStateManager.scale(4.0F, 2.0F, 1.0F);
            GlStateManager.translate(0.0625F, 0.0625F, 0.0625F);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        } else {
            this.bindTexture(SIGN_TEXTURE);
        }

        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        GlStateManager.scale(f, -f, -f);
        this.model.renderSign();
        GlStateManager.popMatrix();

        if (destroyStage < 0) {
            boolean skipDistantText = false;
            if (PureFPSConfig.enabled && (PureFPSConfig.fastSignText || dev.purefps.module.MashFlags.signTextLimitEnabled) && te.lineBeingEdited < 0) {
                double distSq = x * x + y * y + z * z;
                double textMaxSq = dev.purefps.module.MashFlags.signTextLimitEnabled
                        ? dev.purefps.module.MashFlags.signTextMaxDistSq
                        : ((PureFPSConfig.activeProfile == PureFPSProfile.FPS) ? 576.0D : 1024.0D);
                if (distSq > textMaxSq) {
                    skipDistantText = true;
                }
            }

            if (!skipDistantText) {
                FontRenderer fontRenderer = this.getFontRenderer();
                float textScale = 0.015625F * f;
                GlStateManager.translate(0.0F, 0.5F * f, 0.07F * f);
                GlStateManager.scale(textScale, -textScale, textScale);
                GL11.glNormal3f(0.0F, 0.0F, -1.0F * textScale);
                GlStateManager.depthMask(false);

                if (PureFPSConfig.enabled && te instanceof SignTextCacheAccess) {
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
                } else {
                    for (int k = 0; k < te.signText.length; ++k) {
                        if (te.signText[k] == null) {
                            continue;
                        }
                        List<IChatComponent> list = GuiUtilRenderComponents.splitText(te.signText[k], 90, fontRenderer, false, true);
                        String s = (list != null && !list.isEmpty()) ? list.get(0).getFormattedText() : "";
                        if (k == te.lineBeingEdited) {
                            s = "> " + s + " <";
                            fontRenderer.drawString(s, -fontRenderer.getStringWidth(s) / 2, k * 10 - te.signText.length * 5, 0);
                        } else {
                            fontRenderer.drawString(s, -fontRenderer.getStringWidth(s) / 2, k * 10 - te.signText.length * 5, 0);
                        }
                    }
                }

                GlStateManager.depthMask(true);
            }
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();

        if (destroyStage >= 0) {
            GlStateManager.matrixMode(GL11.GL_TEXTURE);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        }
    }
}
