package dev.purefps.client.ui;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Police TrueType haute définition anti-aliasée (Segoe UI sur-échantillonnée 3x avec filtrage GL_LINEAR) :
 * - Émet désormais l'ombre et le texte principal en UN SEUL Tessellator.draw() (divise par 2 tous les draw calls texte).
 * - Supporte beginBatch() / endBatch() pour regrouper toutes les chaînes d'une même fonte HUD en 1 seul draw call GPU.
 */
public final class SmoothFontRenderer {

    private static final int ATLAS_SIZE = 1024;
    private static final float GUI_SCALE_DIVISOR = 3.0F;
    private static final int GLYPH_SLOTS = 268;
    private static final int SLOT_BULLET = 256;    // •
    private static final int SLOT_EM_DASH = 257;   // —
    private static final int SLOT_EN_DASH = 258;   // –
    private static final int SLOT_ARROW = 259;     // →
    private static final int SLOT_CHECK = 260;     // ✓
    private static final int SLOT_STAR = 261;      // ★
    private static final int SLOT_STAR_OUT = 262;  // ☆
    private static final int SLOT_GEAR = 263;      // ⚙

    private static SmoothFontRenderer titleFont;
    private static SmoothFontRenderer subtitleFont;
    private static SmoothFontRenderer bodyFont;
    private static SmoothFontRenderer smallFont;

    private final int awtStyle;
    private final int awtPixelSize;
    private final Glyph[] glyphs = new Glyph[GLYPH_SLOTS];
    private final dev.purefps.core.FastFontCaches.WidthCache widthCache = new dev.purefps.core.FastFontCaches.WidthCache();
    private float fontHeightGui = 9.0F;
    private int textureId = -1;
    private boolean metricsBaked = false;
    private boolean batching = false;

    private static final class Glyph {
        float u0;
        float v0;
        float u1;
        float v1;
        float widthGui;
        float heightGui;
        float advanceGui;
    }

    private SmoothFontRenderer(int awtStyle, int awtPixelSize) {
        this.awtStyle = awtStyle;
        this.awtPixelSize = awtPixelSize;
    }

    public static SmoothFontRenderer title() {
        if (titleFont == null) {
            titleFont = new SmoothFontRenderer(Font.BOLD, 48);
        }
        return titleFont;
    }

    public static SmoothFontRenderer subtitle() {
        if (subtitleFont == null) {
            subtitleFont = new SmoothFontRenderer(Font.BOLD, 31);
        }
        return subtitleFont;
    }

    public static SmoothFontRenderer body() {
        if (bodyFont == null) {
            bodyFont = new SmoothFontRenderer(Font.BOLD, 26);
        }
        return bodyFont;
    }

    public static SmoothFontRenderer small() {
        if (smallFont == null) {
            smallFont = new SmoothFontRenderer(Font.PLAIN, 22);
        }
        return smallFont;
    }

    public BufferedImage bakeAtlasImage() {
        Font font = createBestFont(this.awtStyle, this.awtPixelSize);
        BufferedImage img = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);

        FontMetrics fm = g.getFontMetrics();
        int ascent = fm.getAscent();
        int lineH = fm.getHeight();
        this.fontHeightGui = lineH / GUI_SCALE_DIVISOR;

        int pad = 4;
        int penX = pad;
        int penY = pad;
        int rowHeight = lineH + pad * 2;

        for (int slot = 32; slot < GLYPH_SLOTS; slot++) {
            if (slot >= 127 && slot < 160) {
                continue;
            }
            char ch = slotToChar(slot);
            String s = String.valueOf(ch);
            int charAdvance = Math.max(1, fm.charWidth(ch));
            int cellW = charAdvance + pad * 2;
            int cellH = lineH + pad;

            if (penX + cellW + pad >= ATLAS_SIZE) {
                penX = pad;
                penY += rowHeight;
            }
            if (penY + cellH >= ATLAS_SIZE) {
                break;
            }

            g.drawString(s, penX + (pad >> 1), penY + ascent);

            Glyph glyph = new Glyph();
            glyph.u0 = (float) penX / (float) ATLAS_SIZE;
            glyph.v0 = (float) penY / (float) ATLAS_SIZE;
            glyph.u1 = (float) (penX + cellW) / (float) ATLAS_SIZE;
            glyph.v1 = (float) (penY + cellH) / (float) ATLAS_SIZE;
            glyph.widthGui = cellW / GUI_SCALE_DIVISOR;
            glyph.heightGui = cellH / GUI_SCALE_DIVISOR;
            glyph.advanceGui = charAdvance / GUI_SCALE_DIVISOR;
            this.glyphs[slot] = glyph;

            penX += cellW + pad;
        }
        g.dispose();

        int[] pixels = new int[ATLAS_SIZE * ATLAS_SIZE];
        img.getRGB(0, 0, ATLAS_SIZE, ATLAS_SIZE, pixels, 0, ATLAS_SIZE);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >>> 24) & 0xFF;
            pixels[i] = (alpha << 24) | 0x00FFFFFF;
        }
        img.setRGB(0, 0, ATLAS_SIZE, ATLAS_SIZE, pixels, 0, ATLAS_SIZE);

        this.metricsBaked = true;
        return img;
    }

    private void ensureUploaded() {
        if (this.textureId > 0) {
            return;
        }
        BufferedImage img = bakeAtlasImage();
        int id = TextureUtil.glGenTextures();
        TextureUtil.uploadTextureImageAllocate(id, img, true, true);
        this.textureId = id;
    }

    public float getHeight() {
        if (!this.metricsBaked) {
            bakeAtlasImage();
        }
        return this.fontHeightGui;
    }

    public float getStringWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        if (!this.metricsBaked) {
            bakeAtlasImage();
        }
        int cached = this.widthCache.get(text);
        if (cached >= 0) {
            return cached * (1.0F / 64.0F);
        }
        float width = 0.0F;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                i++;
                continue;
            }
            Glyph g = lookupGlyph(c);
            if (g != null) {
                width += g.advanceGui;
            }
        }
        this.widthCache.put(text, Math.max(0, Math.round(width * 64.0F)));
        return width;
    }

    public String trimToWidth(String text, float maxWidthGui) {
        if (text == null || text.isEmpty() || getStringWidth(text) <= maxWidthGui) {
            return text != null ? text : "";
        }
        float ellipsisW = getStringWidth("...");
        float limit = Math.max(0.0F, maxWidthGui - ellipsisW);
        float w = 0.0F;
        int len = text.length();
        int cut = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                i++;
                cut = i + 1;
                continue;
            }
            Glyph g = lookupGlyph(c);
            float adv = g != null ? g.advanceGui : 0.0F;
            if (w + adv > limit) {
                break;
            }
            w += adv;
            cut = i + 1;
        }
        return text.substring(0, cut) + "...";
    }

    public void beginBatch() {
        if (this.batching) {
            return;
        }
        ensureUploaded();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(this.textureId);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator.getInstance().getWorldRenderer().begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        this.batching = true;
    }

    public void endBatch() {
        if (!this.batching) {
            return;
        }
        this.batching = false;
        Tessellator.getInstance().draw();
        GlStateManager.enableAlpha();
    }

    public void drawString(String text, float x, float y, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (this.batching) {
            emitVertices(Tessellator.getInstance().getWorldRenderer(), text, x, y, argb, false);
            return;
        }
        beginSingleDraw();
        emitVertices(Tessellator.getInstance().getWorldRenderer(), text, x, y, argb, false);
        finishSingleDraw();
    }

    public void drawStringWithShadow(String text, float x, float y, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int baseAlpha = ((argb >>> 24) & 0xFF);
        if (baseAlpha == 0) {
            baseAlpha = 255;
            argb |= 0xFF000000;
        }
        int shadowAlpha = (baseAlpha * 115) / 255;
        int shadowArgb = (shadowAlpha << 24) | 0x05070D;

        if (this.batching) {
            WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
            emitVertices(wr, text, x + 0.55F, y + 0.60F, shadowArgb, true);
            emitVertices(wr, text, x, y, argb, false);
            return;
        }

        beginSingleDraw();
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        emitVertices(wr, text, x + 0.55F, y + 0.60F, shadowArgb, true);
        emitVertices(wr, text, x, y, argb, false);
        finishSingleDraw();
    }

    public void drawCentered(String text, float centerX, float y, int argb, boolean shadow) {
        float w = getStringWidth(text);
        if (shadow) {
            drawStringWithShadow(text, centerX - w * 0.5F, y, argb);
        } else {
            drawString(text, centerX - w * 0.5F, y, argb);
        }
    }

    private void beginSingleDraw() {
        ensureUploaded();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(this.textureId);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator.getInstance().getWorldRenderer().begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
    }

    private static void finishSingleDraw() {
        Tessellator.getInstance().draw();
        GlStateManager.enableAlpha();
    }

    private void emitVertices(WorldRenderer wr, String text, float x, float y, int argb, boolean ignoreColorCodes) {
        int alphaInt = (argb >>> 24) & 0xFF;
        if (alphaInt == 0) {
            alphaInt = 255;
        }
        int curR = (argb >>> 16) & 0xFF;
        int curG = (argb >>> 8) & 0xFF;
        int curB = argb & 0xFF;

        float padOffset = (4.0F * 0.5F) / GUI_SCALE_DIVISOR;
        float cursorX = x;
        float cursorY = y - 1.0F;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < len) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                int colorRgb = mcColorCodeToRgb(code, (argb & 0xFFFFFF));
                if (!ignoreColorCodes && colorRgb >= 0) {
                    curR = (colorRgb >>> 16) & 0xFF;
                    curG = (colorRgb >>> 8) & 0xFF;
                    curB = colorRgb & 0xFF;
                }
                i++;
                continue;
            }

            Glyph g = lookupGlyph(c);
            if (g == null) {
                continue;
            }
            float gx0 = cursorX - padOffset;
            float gy0 = cursorY;
            float gx1 = gx0 + g.widthGui;
            float gy1 = gy0 + g.heightGui;

            wr.pos(gx0, gy1, 0.0D).tex(g.u0, g.v1).color(curR, curG, curB, alphaInt).endVertex();
            wr.pos(gx1, gy1, 0.0D).tex(g.u1, g.v1).color(curR, curG, curB, alphaInt).endVertex();
            wr.pos(gx1, gy0, 0.0D).tex(g.u1, g.v0).color(curR, curG, curB, alphaInt).endVertex();
            wr.pos(gx0, gy0, 0.0D).tex(g.u0, g.v0).color(curR, curG, curB, alphaInt).endVertex();

            cursorX += g.advanceGui;
        }
    }

    private Glyph lookupGlyph(char ch) {
        int slot;
        if (ch == '\u2022') {
            slot = SLOT_BULLET;
        } else if (ch == '\u2014') {
            slot = SLOT_EM_DASH;
        } else if (ch == '\u2013') {
            slot = SLOT_EN_DASH;
        } else if (ch == '\u2192') {
            slot = SLOT_ARROW;
        } else if (ch == '\u2713') {
            slot = SLOT_CHECK;
        } else if (ch == '\u2605') {
            slot = SLOT_STAR;
        } else if (ch == '\u2606') {
            slot = SLOT_STAR_OUT;
        } else if (ch == '\u2699') {
            slot = SLOT_GEAR;
        } else if (ch >= 32 && ch < 256) {
            slot = (int) ch;
        } else {
            slot = (int) '?';
        }
        Glyph g = this.glyphs[slot];
        return g != null ? g : this.glyphs['?'];
    }

    private static char slotToChar(int slot) {
        switch (slot) {
            case SLOT_BULLET: return '\u2022';
            case SLOT_EM_DASH: return '\u2014';
            case SLOT_EN_DASH: return '\u2013';
            case SLOT_ARROW: return '\u2192';
            case SLOT_CHECK: return '\u2713';
            case SLOT_STAR: return '\u2605';
            case SLOT_STAR_OUT: return '\u2606';
            case SLOT_GEAR: return '\u2699';
            default: return (char) slot;
        }
    }

    private static Font createBestFont(int style, int size) {
        String[] candidates = {"Segoe UI", "Calibri", "Arial", Font.SANS_SERIF};
        for (String name : candidates) {
            Font f = new Font(name, style, size);
            if (f.canDisplay('É') && f.canDisplay('A')) {
                return f;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static int mcColorCodeToRgb(char code, int defaultRgb) {
        switch (code) {
            case '0': return 0x11141C;
            case '1': return 0x3B82F6;
            case '2': return 0x10B981;
            case '3': return 0x06B6D4;
            case '4': return 0xEF4444;
            case '5': return 0xA855F7;
            case '6': return 0xF59E0B;
            case '7': return 0x949CB0;
            case '8': return 0x5E667A;
            case '9': return 0x6366F1;
            case 'a': return 0x34D399;
            case 'b': return 0x60A5FA;
            case 'c': return 0xF87171;
            case 'd': return 0xE879F9;
            case 'e': return 0xFBBF24;
            case 'f': return 0xECEFF6;
            case 'r': return defaultRgb;
            default: return -1;
        }
    }
}
