package dev.purefps.mixin;

import dev.purefps.config.PureFPSConfig;
import dev.purefps.core.FastFontBatcher;
import dev.purefps.core.FastFontCaches;
import dev.purefps.core.FastSkullCache;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Locale;

/**
 * Optimise massivement FontRenderer sans AUCUNE perte visuelle et avec ZÉRO allocation CallbackInfo sur le chemin chaud :
 * 1. @Overwrite zéro-allocation sur getStringWidth, drawString, setColor, renderDefaultChar, renderUnicodeChar et doDraw.
 * 2. Batching complet des glyphes ASCII, Unicode (changement de page sans casser le batch inutilement) et décorations §m/§n
 *    via FastFontBatcher (Ring VBO 512 Ko + VAO GL30 + EBO 16-bit partagé).
 * 3. Supprime l'allocation String.toLowerCase(Locale.ENGLISH) exécutée à chaque code couleur '§'.
 * 4. Applique le module Nick Hider (hyp_nick_hider) UNE seule fois par drawString (au lieu de 2x avec dropShadow).
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
    protected byte[] glyphWidth;

    @Shadow
    public int FONT_HEIGHT;

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

    @Shadow(remap = false)
    protected abstract void enableAlpha();

    @Shadow
    protected abstract void resetStyles();

    @Shadow
    protected abstract int renderString(String text, float x, float y, int color, boolean dropShadow);

    @Shadow
    protected abstract ResourceLocation getUnicodePageLocation(int page);

    @Shadow
    public abstract int getCharWidth(char character);

    @Unique
    private final FastFontCaches.WidthCache purefps$widthCache = new FastFontCaches.WidthCache();

    @Unique
    private int purefps$asciiGlTextureId = -1;

    @Unique
    private final int[] purefps$unicodePageTexIds = new int[256];

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
        Arrays.fill(this.purefps$unicodePageTexIds, 0);
        FastFontBatcher.invalidateGlState();
        FastSkullCache.clear();
    }

    @Inject(method = "setUnicodeFlag", at = @At("HEAD"))
    private void purefps$clearCacheOnUnicodeChange(boolean flag, CallbackInfo ci) {
        this.purefps$widthCache.clear();
    }

    /**
     * @author MashClient
     * @reason Suit la couleur courante sans CallbackInfo et évite glColor4f redondant en mode batché
     */
    @Overwrite(remap = false)
    protected void setColor(float r, float g, float b, float a) {
        this.purefps$curR = r;
        this.purefps$curG = g;
        this.purefps$curB = b;
        this.purefps$curA = a;
        if (!PureFPSConfig.enabled || !PureFPSConfig.fastFontRenderer || this.purefps$stringBatchDepth <= 0) {
            GlStateManager.color(r, g, b, a);
        }
    }

    /**
     * @author MashClient
     * @reason Zéro CallbackInfoReturnable, 1 seul applyNickHider par chaîne (shadow+fg partagés), batching continu
     */
    @Overwrite
    public int drawString(String text, float x, float y, int color, boolean dropShadow) {
        this.enableAlpha();
        this.resetStyles();
        text = FastFontCaches.applyNickHider(text);
        this.purefps$stringBatchDepth++;
        int i;
        try {
            if (dropShadow) {
                i = this.renderString(text, x + 1.0F, y + 1.0F, color, true);
                if (FastFontBatcher.hasPendingDecorations()) {
                    // Garantit que le souligné/barré de l'ombre est dessiné SOUS le texte principal
                    FastFontBatcher.flushIfPending();
                }
                i = Math.max(i, this.renderString(text, x, y, color, false));
            } else {
                i = this.renderString(text, x, y, color, false);
            }
        } finally {
            if (this.purefps$stringBatchDepth > 0) {
                this.purefps$stringBatchDepth--;
            }
            if (this.purefps$stringBatchDepth == 0 && !FastFontBatcher.isOuterBatchActive()) {
                FastFontBatcher.flushIfPending();
            }
        }
        return i;
    }

    @Redirect(
            method = "renderStringAligned",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;renderString(Ljava/lang/String;FFIZ)I"
            )
    )
    private int purefps$batchRenderStringAligned(FontRenderer self, String text, float x, float y, int color, boolean dropShadow) {
        String processed = FastFontCaches.applyNickHider(text);
        this.purefps$stringBatchDepth++;
        try {
            return this.renderString(processed, x, y, color, dropShadow);
        } finally {
            if (this.purefps$stringBatchDepth > 0) {
                this.purefps$stringBatchDepth--;
            }
            if (this.purefps$stringBatchDepth == 0 && !FastFontBatcher.isOuterBatchActive()) {
                FastFontBatcher.flushIfPending();
            }
        }
    }

    /**
     * @author MashClient
     * @reason Batching zéro-allocation des glyphes ASCII via FastFontBatcher (4 sommets indexés, Ring VBO + VAO)
     */
    @Overwrite
    protected float renderDefaultChar(int ch, boolean italic) {
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

        GlStateManager.color(this.purefps$curR, this.purefps$curG, this.purefps$curB, this.purefps$curA);
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

        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2f((float) col / 128.0F, (float) row / 128.0F);
        GL11.glVertex3f(this.posX + italicOff, this.posY, 0.0F);
        GL11.glTexCoord2f((float) col / 128.0F, ((float) row + 7.99F) / 128.0F);
        GL11.glVertex3f(this.posX - italicOff, this.posY + 7.99F, 0.0F);
        GL11.glTexCoord2f(((float) col + widthMinusEps - 1.0F) / 128.0F, (float) row / 128.0F);
        GL11.glVertex3f(this.posX + widthMinusEps - 1.0F + italicOff, this.posY, 0.0F);
        GL11.glTexCoord2f(((float) col + widthMinusEps - 1.0F) / 128.0F, ((float) row + 7.99F) / 128.0F);
        GL11.glVertex3f(this.posX + widthMinusEps - 1.0F - italicOff, this.posY + 7.99F, 0.0F);
        GL11.glEnd();
        return (float) w;
    }

    /**
     * @author MashClient
     * @reason Route les glyphes Unicode (✓, ✗, ✪, ❤ Hypixel) dans FastFontBatcher sans casser le batch tant que la page reste identique
     */
    @Overwrite
    protected float renderUnicodeChar(char ch, boolean italic) {
        byte packedBounds = this.glyphWidth[ch];
        if (packedBounds == 0) {
            return 0.0F;
        }
        int pageIndex = ch >>> 8;
        float glyphStart = (float) ((packedBounds & 0xFF) >>> 4);
        float glyphEnd = (float) ((packedBounds & 0x0F) + 1);
        float texU = (float) ((ch & 15) * 16) + glyphStart;
        float texV = (float) (((ch & 255) >>> 4) * 16);
        float span = glyphEnd - glyphStart - 0.02F;
        float italicSkew = italic ? 1.0F : 0.0F;
        float invAtlas = 1.0F / 256.0F;
        float u0 = texU * invAtlas;
        float v0 = texV * invAtlas;
        float u1 = (texU + span) * invAtlas;
        float v1 = (texV + 15.98F) * invAtlas;
        float drawW = span * 0.5F;

        if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer && this.purefps$stringBatchDepth > 0) {
            int pageTexId = this.purefps$resolveUnicodePageGlTextureId(pageIndex);
            if (pageTexId > 0) {
                FastFontBatcher.appendUnicodeGlyph(
                        pageTexId,
                        this.posX,
                        this.posY,
                        u0,
                        v0,
                        u1,
                        v1,
                        drawW,
                        italicSkew,
                        this.purefps$curR,
                        this.purefps$curG,
                        this.purefps$curB,
                        this.purefps$curA
                );
                return (glyphEnd - glyphStart) * 0.5F + 1.0F;
            }
        }

        FastFontBatcher.flushIfPending();
        GlStateManager.color(this.purefps$curR, this.purefps$curG, this.purefps$curB, this.purefps$curA);
        this.bindTexture(this.getUnicodePageLocation(pageIndex));
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2f(u0, v0);
        GL11.glVertex3f(this.posX + italicSkew, this.posY, 0.0F);
        GL11.glTexCoord2f(u0, v1);
        GL11.glVertex3f(this.posX - italicSkew, this.posY + 7.99F, 0.0F);
        GL11.glTexCoord2f(u1, v0);
        GL11.glVertex3f(this.posX + drawW + italicSkew, this.posY, 0.0F);
        GL11.glTexCoord2f(u1, v1);
        GL11.glVertex3f(this.posX + drawW - italicSkew, this.posY + 7.99F, 0.0F);
        GL11.glEnd();
        return (glyphEnd - glyphStart) * 0.5F + 1.0F;
    }

    /**
     * @author MashClient
     * @reason Supprime le @Inject (1.2-2.4M CallbackInfo/s) et batche les barres §m/§n dans FastFontBatcher sans flush par caractère
     */
    @Overwrite(remap = false)
    protected void doDraw(float advance) {
        if (this.strikethroughStyle || this.underlineStyle) {
            if (PureFPSConfig.enabled && PureFPSConfig.fastFontRenderer && this.purefps$stringBatchDepth > 0) {
                if (this.strikethroughStyle) {
                    float yBot = this.posY + (float) (this.FONT_HEIGHT / 2);
                    FastFontBatcher.appendDecorationQuad(
                            this.posX,
                            yBot - 1.0F,
                            this.posX + advance,
                            yBot,
                            this.purefps$curR,
                            this.purefps$curG,
                            this.purefps$curB,
                            this.purefps$curA
                    );
                }
                if (this.underlineStyle) {
                    float yBot = this.posY + (float) this.FONT_HEIGHT;
                    FastFontBatcher.appendDecorationQuad(
                            this.posX - 1.0F,
                            yBot - 1.0F,
                            this.posX + advance,
                            yBot,
                            this.purefps$curR,
                            this.purefps$curG,
                            this.purefps$curB,
                            this.purefps$curA
                    );
                }
            } else {
                FastFontBatcher.flushIfPending();
                GlStateManager.color(this.purefps$curR, this.purefps$curG, this.purefps$curB, this.purefps$curA);
                if (this.strikethroughStyle) {
                    float midY = this.posY + (float) (this.FONT_HEIGHT / 2);
                    purefps$drawDecorationQuadImmediate(this.posX, midY - 1.0F, this.posX + advance, midY);
                }
                if (this.underlineStyle) {
                    float bottomY = this.posY + (float) this.FONT_HEIGHT;
                    purefps$drawDecorationQuadImmediate(this.posX - 1.0F, bottomY - 1.0F, this.posX + advance, bottomY);
                }
            }
        }
        this.posX += (float) ((int) advance);
    }

    @Unique
    private static void purefps$drawDecorationQuadImmediate(float x0, float y0, float x1, float y1) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        GlStateManager.disableTexture2D();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        wr.pos((double) x0, (double) y1, 0.0D).endVertex();
        wr.pos((double) x1, (double) y1, 0.0D).endVertex();
        wr.pos((double) x1, (double) y0, 0.0D).endVertex();
        wr.pos((double) x0, (double) y0, 0.0D).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();
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

    @Unique
    private int purefps$resolveUnicodePageGlTextureId(int page) {
        if (page < 0 || page >= 256) {
            return -1;
        }
        int cached = this.purefps$unicodePageTexIds[page];
        if (cached > 0) {
            return cached;
        }
        if (this.renderEngine == null) {
            return -1;
        }
        ResourceLocation loc = this.getUnicodePageLocation(page);
        if (loc == null) {
            return -1;
        }
        ITextureObject tex = this.renderEngine.getTexture(loc);
        if (tex == null) {
            this.bindTexture(loc);
            tex = this.renderEngine.getTexture(loc);
        }
        if (tex != null) {
            int id = tex.getGlTextureId();
            if (id > 0) {
                this.purefps$unicodePageTexIds[page] = id;
                return id;
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
