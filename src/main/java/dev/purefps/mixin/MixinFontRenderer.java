package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontBatcher;
import dev.purefps.core.FastFontCaches;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

/**
 * Optimise massivement FontRenderer sans AUCUNE perte visuelle :
 * 1. @Overwrite zéro-allocation sur getStringWidth(String) (cache O(1) ~2 ns sur Scoreboard, Tab, Chat, Nametags).
 * 2. Batching complet des glyphes ASCII via FastFontBatcher (1 seul glDrawArrays(GL_TRIANGLES) en VBO streaming
 *    par chaîne ou par lot Scoreboard, au lieu de 400 à 1 500 glBegin(GL_TRIANGLE_STRIP)/glEnd() par frame !).
 * 3. Supprime l'allocation String.toLowerCase(Locale.ENGLISH) exécutée à chaque code couleur '§'.
 * 4. Applique le module Nick Hider (hyp_nick_hider) globalement sur tous les textes rendus.
 */
@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

    @Shadow
    @Final
    protected ResourceLocation locationFontTexture;

    @Shadow
    @Final
    private TextureManager renderEngine;

    @Shadow
    protected int[] charWidth;

    @Shadow
    protected float posX;

    @Shadow
    protected float posY;

    @Shadow
    private boolean strikethroughStyle;

    @Shadow
    private boolean underlineStyle;

    @Shadow(remap = false)
    protected abstract void bindTexture(ResourceLocation location);

    @Shadow
    public abstract int getCharWidth(char character);

    @Unique
    private final FastFontCaches.WidthCache purefps$widthCache = new FastFontCaches.WidthCache();

    @Unique
    private int purefps$asciiGlTextureId = -1;

    @Unique
    private int purefps$stringBatchDepth = 0;

    @Unique
    private float purefps$curR = 1.0F;
    @Unique
    private float purefps$curG = 1.0F;
    @Unique
    private float purefps$curB = 1.0F;
    @Unique
    private float purefps$curA = 1.0F;

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void purefps$clearCacheOnReload(IResourceManager resourceManager, CallbackInfo ci) {
        this.purefps$widthCache.clear();
        this.purefps$asciiGlTextureId = -1;
        FastFontBatcher.invalidateGlState();
        FastSkullCache.clear();
    }

    @Inject(method = "setUnicodeFlag", at = @At("HEAD"))
    private void purefps$clearCacheOnUnicodeChange(boolean flag, CallbackInfo ci) {
        this.purefps$widthCache.clear();
    }

    @Inject(method = "setColor", at = @At("HEAD"), remap = false)
    private void purefps$trackCurrentColor(float r, float g, float b, float a, CallbackInfo ci) {
        this.purefps$curR = r;
        this.purefps$curG = g;
        this.purefps$curB = b;
        this.purefps$curA = a;
    }

    @Inject(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("HEAD"))
    private void purefps$beginDrawStringBatch(String text, float x, float y, int color, boolean dropShadow, CallbackInfoReturnable<Integer> cir) {
        this.purefps$stringBatchDepth++;
    }

    @Inject(method = "drawString(Ljava/lang/String;FFIZ)I", at = @At("RETURN"))
    private void purefps$endDrawStringBatch(String text, float x, float y, int color, boolean dropShadow, CallbackInfoReturnable<Integer> cir) {
        if (this.purefps$stringBatchDepth > 0) {
            this.purefps$stringBatchDepth--;
        }
        if (this.purefps$stringBatchDepth == 0 && !FastFontBatcher.isOuterBatchActive()) {
            FastFontBatcher.flushIfPending();
        }
    }

    @Inject(method = "renderStringAtPos", at = @At("HEAD"))
    private void purefps$beginRenderStringAtPos(String text, boolean shadow, CallbackInfo ci) {
        this.purefps$stringBatchDepth++;
    }

    @Inject(method = "renderStringAtPos", at = @At("RETURN"))
    private void purefps$endRenderStringAtPos(String text, boolean shadow, CallbackInfo ci) {
        if (this.purefps$stringBatchDepth > 0) {
            this.purefps$stringBatchDepth--;
        }
        if (this.purefps$stringBatchDepth == 0 && !FastFontBatcher.isOuterBatchActive()) {
            FastFontBatcher.flushIfPending();
        }
    }

    /**
     * @author MashClient
     * @reason Batching zéro-allocation des glyphes ASCII via FastFontBatcher (supprime glBegin/glEnd par caractère et CallbackInfoReturnable)
     */
    @Overwrite
    private float renderDefaultChar(int ch, boolean italic) {
        int col = (ch & 15) * 8;
        int row = (ch >>> 4) * 8;
        float italicOff = italic ? 1.0F : 0.0F;
        int w = this.charWidth[ch];
        float widthMinusEps = (float) w - 0.01F;

        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer && this.purefps$stringBatchDepth > 0) {
            int texId = this.purefps$resolveAsciiGlTextureId();
            if (texId > 0) {
                FastFontBatcher.appendAsciiGlyph(
                        texId,
                        this.posX,
                        this.posY,
                        col,
                        row,
                        widthMinusEps,
                        italicOff,
                        this.purefps$curR,
                        this.purefps$curG,
                        this.purefps$curB,
                        this.purefps$curA
                );
                return (float) w;
            }
        }

        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer) {
            int texId = this.purefps$resolveAsciiGlTextureId();
            if (texId > 0) {
                GlStateManager.bindTexture(texId);
            } else {
                this.bindTexture(this.locationFontTexture);
            }
        } else {
            this.bindTexture(this.locationFontTexture);
        }

        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP);
        org.lwjgl.opengl.GL11.glTexCoord2f((float) col / 128.0F, (float) row / 128.0F);
        org.lwjgl.opengl.GL11.glVertex3f(this.posX + italicOff, this.posY, 0.0F);
        org.lwjgl.opengl.GL11.glTexCoord2f((float) col / 128.0F, ((float) row + 7.99F) / 128.0F);
        org.lwjgl.opengl.GL11.glVertex3f(this.posX - italicOff, this.posY + 7.99F, 0.0F);
        org.lwjgl.opengl.GL11.glTexCoord2f(((float) col + widthMinusEps - 1.0F) / 128.0F, (float) row / 128.0F);
        org.lwjgl.opengl.GL11.glVertex3f(this.posX + widthMinusEps - 1.0F + italicOff, this.posY, 0.0F);
        org.lwjgl.opengl.GL11.glTexCoord2f(((float) col + widthMinusEps - 1.0F) / 128.0F, ((float) row + 7.99F) / 128.0F);
        org.lwjgl.opengl.GL11.glVertex3f(this.posX + widthMinusEps - 1.0F - italicOff, this.posY + 7.99F, 0.0F);
        org.lwjgl.opengl.GL11.glEnd();
        return (float) w;
    }

    @Inject(method = "renderUnicodeChar", at = @At("HEAD"))
    private void purefps$flushBeforeUnicodeChar(char ch, boolean italic, CallbackInfoReturnable<Float> cir) {
        FastFontBatcher.flushIfPending();
    }

    @Inject(method = "doDraw", at = @At("HEAD"), remap = false)
    private void purefps$flushBeforeDecorations(float advance, CallbackInfo ci) {
        if (this.strikethroughStyle || this.underlineStyle) {
            FastFontBatcher.flushIfPending();
        }
    }

    @ModifyVariable(method = "renderStringAtPos", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String purefps$applyNickHiderInRender(String text) {
        return FastFontCaches.applyNickHider(text);
    }

    /**
     * @author PureFPS
     * @reason Cache O(1) zéro-allocation (supprime les 2 CallbackInfoReturnable + Integer.valueOf par appel)
     */
    @Overwrite
    public int getStringWidth(String text) {
        if (text == null) {
            return 0;
        }
        text = FastFontCaches.applyNickHider(text);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        final boolean useCache = PureFPSConfig.enabled
                && PureFPSConfig.fastFontRenderer
                && mc != null
                && mc.isCallingFromMinecraftThread();
        if (useCache) {
            int cached = this.purefps$widthCache.get(text);
            if (cached >= 0) {
                return cached;
            }
        }

        int width = 0;
        boolean bold = false;
        int len = text.length();

        for (int i = 0; i < len; ++i) {
            char ch = text.charAt(i);
            int charWidth = this.getCharWidth(ch);

            if (charWidth < 0 && i < len - 1) {
                ++i;
                ch = text.charAt(i);
                if (ch != 'l' && ch != 'L') {
                    if (ch == 'r' || ch == 'R') {
                        bold = false;
                    }
                } else {
                    bold = true;
                }
                charWidth = 0;
            }

            width += charWidth;
            if (bold && charWidth > 0) {
                ++width;
            }
        }

        if (useCache) {
            this.purefps$widthCache.put(text, width);
        }
        return width;
    }

    @Unique
    private int purefps$resolveAsciiGlTextureId() {
        int texId = this.purefps$asciiGlTextureId;
        if (texId > 0) {
            return texId;
        }
        if (this.renderEngine != null && this.locationFontTexture != null) {
            ITextureObject tex = this.renderEngine.getTexture(this.locationFontTexture);
            if (tex == null) {
                this.bindTexture(this.locationFontTexture);
                tex = this.renderEngine.getTexture(this.locationFontTexture);
            }
            if (tex != null) {
                texId = tex.getGlTextureId();
                this.purefps$asciiGlTextureId = texId;
                return texId;
            }
        }
        return -1;
    }

    @Redirect(
            method = "renderStringAtPos",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;toLowerCase(Ljava/util/Locale;)Ljava/lang/String;",
                    remap = false
            )
    )
    private String purefps$avoidToLowerCaseAllocation(String text, Locale locale) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer) ? text : text.toLowerCase(locale);
    }

    @Redirect(
            method = "renderStringAtPos",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;indexOf(I)I",
                    ordinal = 0,
                    remap = false
            )
    )
    private int purefps$fastFormatCodeIndex(String formatAlphabet, int ch) {
        return (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer)
                ? FastFontCaches.lookupFormatIndex(ch)
                : formatAlphabet.indexOf(ch);
    }
}
