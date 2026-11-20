package dev.purefps.client.ui;

import dev.purefps.core.FastFontCaches;
import dev.purefps.core.QuadIndexBuffer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Police TrueType haute définition anti-aliasée (MashClient 4.5.15) :
 * - Supprime le double-baking (conserve pendingAtlasImage entre getStringWidth/getHeight et ensureUploaded).
 * - Accès direct au tampon DataBufferInt (zéro copie pixels[] / getRGB / setRGB).
 * - Tailles d'atlas calibrées au plus juste (512x512 / 1024x512 / 1024x1024 / 2048x1024 -> VRAM divisée par 2 à 4).
 * - Applique applyNickHider une seule fois par appel (jamais 2 fois pour l'ombre).
 * - Rendu sur Ring VBO GL_TRIANGLES + VAO GL30 + QuadIndexBuffer (zéro GL_QUADS / Tessellator).
 */
public final class SmoothFontRenderer {

    private static final float HUD_SCALE_DIVISOR = 3.0F;
    private static final float DESKTOP_SCALE_DIVISOR = 2.0F;

    private static final int GLYPH_SLOTS = 270;
    private static final int SLOT_BULLET = 256;    // •
    private static final int SLOT_EM_DASH = 257;   // —
    private static final int SLOT_EN_DASH = 258;   // –
    private static final int SLOT_ARROW = 259;     // →
    private static final int SLOT_CHECK = 260;     // ✓
    private static final int SLOT_STAR = 261;      // ★
    private static final int SLOT_STAR_OUT = 262;  // ☆
    private static final int SLOT_GEAR = 263;      // ⚙
    private static final int SLOT_PLAY = 264;      // ▶
    private static final int SLOT_HEART = 265;     // ♥

    // Ring VBO partagé : 24 octets/sommet (XYZ 12B + UV 8B + RGBA 4B), 4 sommets/glyphe + QuadIndexBuffer
    private static final int VERT_STRIDE = 24;
    private static final int MAX_BATCH_QUADS = 2048;
    private static final int MAX_BATCH_VERTS = MAX_BATCH_QUADS * 4;
    private static final int RING_CAPACITY_VERTS = 16384; // 384 Ko
    private static final int RING_CAPACITY_BYTES = RING_CAPACITY_VERTS * VERT_STRIDE;
    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private static final ByteBuffer CPU_VERTS = ByteBuffer.allocateDirect(MAX_BATCH_VERTS * VERT_STRIDE)
            .order(ByteOrder.nativeOrder());
    private static int queuedQuads = 0;
    private static int cpuWriteOff = 0;

    private static boolean glCapsChecked = false;
    private static boolean vboReady = false;
    private static boolean gl30Ready = false;
    private static boolean gl32Ready = false;
    private static int ringVboId = -1;
    private static int ringVaoId = -1;
    private static int ringWriteVert = 0;
    private static ByteBuffer mappedScratch = null;

    // Fontes HUD (ScaledResolution)
    private static SmoothFontRenderer titleFont;
    private static SmoothFontRenderer subtitleFont;
    private static SmoothFontRenderer bodyFont;
    private static SmoothFontRenderer smallFont;

    // Fontes Bureau (Viewport 1280x720 style Launcher Singularity)
    private static SmoothFontRenderer uiHeroFont;
    private static SmoothFontRenderer uiHeaderFont;
    private static SmoothFontRenderer uiCardTitleFont;
    private static SmoothFontRenderer uiBodyFont;
    private static SmoothFontRenderer uiSmallFont;
    private static SmoothFontRenderer uiBoldSmallFont;

    private final int awtStyle;
    private final int awtPixelSize;
    private final float scaleDivisor;
    private final int atlasWidth;
    private final int atlasHeight;
    private final Glyph[] glyphs = new Glyph[GLYPH_SLOTS];
    private final FastFontCaches.WidthCache widthCache = new FastFontCaches.WidthCache();
    private float fontHeightGui = 9.0F;
    private int textureId = -1;
    private boolean metricsBaked = false;
    private BufferedImage pendingAtlasImage = null;
    private int bakeCount = 0;
    private boolean batching = false;
    private boolean batchUsingVbo = false;

    private static final class Glyph {
        float u0;
        float v0;
        float u1;
        float v1;
        float widthGui;
        float heightGui;
        float advanceGui;
    }

    private SmoothFontRenderer(int awtStyle, int awtPixelSize, float scaleDivisor, int atlasWidth, int atlasHeight) {
        this.awtStyle = awtStyle;
        this.awtPixelSize = awtPixelSize;
        this.scaleDivisor = scaleDivisor;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
    }

    // =========================================================================
    // Fontes HUD (ScaledResolution)
    // =========================================================================
    public static SmoothFontRenderer title() {
        if (titleFont == null) {
            titleFont = new SmoothFontRenderer(Font.BOLD, 48, HUD_SCALE_DIVISOR, 1024, 1024);
        }
        return titleFont;
    }

    public static SmoothFontRenderer subtitle() {
        if (subtitleFont == null) {
            subtitleFont = new SmoothFontRenderer(Font.BOLD, 31, HUD_SCALE_DIVISOR, 1024, 512);
        }
        return subtitleFont;
    }

    public static SmoothFontRenderer body() {
        if (bodyFont == null) {
            bodyFont = new SmoothFontRenderer(Font.BOLD, 26, HUD_SCALE_DIVISOR, 1024, 1024);
        }
        return bodyFont;
    }

    public static SmoothFontRenderer small() {
        if (smallFont == null) {
            smallFont = new SmoothFontRenderer(Font.PLAIN, 22, HUD_SCALE_DIVISOR, 512, 512);
        }
        return smallFont;
    }

    // =========================================================================
    // Fontes Bureau (Viewport 1280x720 style Launcher)
    // =========================================================================
    public static SmoothFontRenderer uiHero() {
        if (uiHeroFont == null) {
            uiHeroFont = new SmoothFontRenderer(Font.BOLD, 70, DESKTOP_SCALE_DIVISOR, 2048, 1024);
        }
        return uiHeroFont;
    }

    public static SmoothFontRenderer uiHeader() {
        if (uiHeaderFont == null) {
            uiHeaderFont = new SmoothFontRenderer(Font.BOLD, 42, DESKTOP_SCALE_DIVISOR, 1024, 1024);
        }
        return uiHeaderFont;
    }

    public static SmoothFontRenderer uiCardTitle() {
        if (uiCardTitleFont == null) {
            uiCardTitleFont = new SmoothFontRenderer(Font.BOLD, 30, DESKTOP_SCALE_DIVISOR, 1024, 512);
        }
        return uiCardTitleFont;
    }

    public static SmoothFontRenderer uiBody() {
        if (uiBodyFont == null) {
            uiBodyFont = new SmoothFontRenderer(Font.PLAIN, 26, DESKTOP_SCALE_DIVISOR, 1024, 512);
        }
        return uiBodyFont;
    }

    public static SmoothFontRenderer uiSmall() {
        if (uiSmallFont == null) {
            uiSmallFont = new SmoothFontRenderer(Font.PLAIN, 23, DESKTOP_SCALE_DIVISOR, 512, 512);
        }
        return uiSmallFont;
    }

    public static SmoothFontRenderer uiBoldSmall() {
        if (uiBoldSmallFont == null) {
            uiBoldSmallFont = new SmoothFontRenderer(Font.BOLD, 23, DESKTOP_SCALE_DIVISOR, 512, 512);
        }
        return uiBoldSmallFont;
    }

    public int getBakeCountForTest() {
        return this.bakeCount;
    }

    public boolean hasPendingAtlasImageForTest() {
        return this.pendingAtlasImage != null;
    }

    public BufferedImage bakeAtlasImage() {
        if (this.pendingAtlasImage != null) {
            return this.pendingAtlasImage;
        }
        int width = this.atlasWidth;
        int height = this.atlasHeight;
        Font font = createBestFont(this.awtStyle, this.awtPixelSize);
        Font fallbackFont = new Font(Font.DIALOG, this.awtStyle, this.awtPixelSize);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);

        FontMetrics primaryFm = g.getFontMetrics(font);
        FontMetrics fallbackFm = g.getFontMetrics(fallbackFont);
        int ascent = primaryFm.getAscent();
        int lineH = primaryFm.getHeight();
        this.fontHeightGui = lineH / this.scaleDivisor;

        int pad = 4;
        int penX = pad;
        int penY = pad;
        int rowHeight = lineH + pad * 2;
        float invW = 1.0F / (float) width;
        float invH = 1.0F / (float) height;

        for (int slot = 32; slot < GLYPH_SLOTS; slot++) {
            if (slot >= 127 && slot < 160) {
                continue;
            }
            char ch = slotToChar(slot);
            boolean usePrimary = font.canDisplay(ch);
            Font activeFont = usePrimary ? font : fallbackFont;
            FontMetrics activeFm = usePrimary ? primaryFm : fallbackFm;
            String s = String.valueOf(ch);
            int charAdvance = Math.max(1, activeFm.charWidth(ch));
            int cellW = charAdvance + pad * 2;
            int cellH = lineH + pad;

            if (penX + cellW + pad >= width) {
                penX = pad;
                penY += rowHeight;
            }
            if (penY + cellH >= height) {
                break;
            }

            g.setFont(activeFont);
            g.drawString(s, penX + (pad >> 1), penY + ascent);

            Glyph glyph = new Glyph();
            glyph.u0 = (float) penX * invW;
            glyph.v0 = (float) penY * invH;
            glyph.u1 = (float) (penX + cellW) * invW;
            glyph.v1 = (float) (penY + cellH) * invH;
            glyph.widthGui = cellW / this.scaleDivisor;
            glyph.heightGui = cellH / this.scaleDivisor;
            glyph.advanceGui = charAdvance / this.scaleDivisor;
            this.glyphs[slot] = glyph;

            penX += cellW + pad;
        }
        g.dispose();

        // Mutation directe du tampon DataBufferInt (0 allocation int[], supprime getRGB + setRGB)
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0, n = pixels.length; i < n; i++) {
            pixels[i] = (pixels[i] & 0xFF000000) | 0x00FFFFFF;
        }

        this.metricsBaked = true;
        this.bakeCount++;
        if (this.textureId <= 0) {
            this.pendingAtlasImage = img;
        }
        return img;
    }

    private void ensureUploaded() {
        if (this.textureId > 0) {
            return;
        }
        BufferedImage img = this.pendingAtlasImage;
        if (img == null) {
            img = bakeAtlasImage();
        }
        this.pendingAtlasImage = null;
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
        text = FastFontCaches.applyNickHider(text);
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

    private static boolean ensureRingVboReady() {
        if (!glCapsChecked) {
            glCapsChecked = true;
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                vboReady = caps != null && caps.OpenGL15 && QuadIndexBuffer.isSupported();
                gl30Ready = caps != null && caps.OpenGL30;
                gl32Ready = caps != null && caps.OpenGL32;
            } catch (Exception | LinkageError ignored) {
                vboReady = false;
            }
        }
        if (!vboReady) {
            return false;
        }
        if (ringVboId > 0) {
            return true;
        }
        int eboId = QuadIndexBuffer.ensureIndexBufferReady(MAX_BATCH_VERTS);
        if (eboId <= 0) {
            vboReady = false;
            return false;
        }
        ringVboId = OpenGlHelper.glGenBuffers();
        if (ringVboId <= 0) {
            vboReady = false;
            return false;
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
        GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
        ringWriteVert = 0;

        if (gl30Ready) {
            ringVaoId = GL30.glGenVertexArrays();
            if (ringVaoId > 0) {
                GL30.glBindVertexArray(ringVaoId);
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, VERT_STRIDE, 0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERT_STRIDE, 12L);
                GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERT_STRIDE, 20L);
                GL30.glBindVertexArray(0);
            }
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        return true;
    }

    private static void flushVboQuads() {
        int quads = queuedQuads;
        if (quads <= 0) {
            return;
        }
        int vertCount = quads << 2;
        int byteLen = cpuWriteOff;
        queuedQuads = 0;
        cpuWriteOff = 0;

        CPU_VERTS.position(0);
        CPU_VERTS.limit(byteLen);

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
        boolean useBaseVertex = gl32Ready;
        int baseVert;
        if (useBaseVertex) {
            if (ringWriteVert + vertCount > RING_CAPACITY_VERTS) {
                GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
                ringWriteVert = 0;
            }
            baseVert = ringWriteVert;
            ringWriteVert += vertCount;
        } else {
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            baseVert = 0;
            ringWriteVert = vertCount;
        }
        long byteOff = (long) baseVert * VERT_STRIDE;

        boolean uploaded = false;
        if (gl30Ready) {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    OpenGlHelper.GL_ARRAY_BUFFER, byteOff, byteLen, MAP_FLAGS, mappedScratch
            );
            if (mapped != null) {
                mappedScratch = mapped;
                mapped.clear();
                mapped.put(CPU_VERTS);
                GL15.glUnmapBuffer(OpenGlHelper.GL_ARRAY_BUFFER);
                uploaded = true;
            }
        }
        if (!uploaded) {
            GL15.glBufferSubData(OpenGlHelper.GL_ARRAY_BUFFER, byteOff, CPU_VERTS);
        }

        int idxCount = quads * 6;
        if (ringVaoId > 0) {
            GL30.glBindVertexArray(ringVaoId);
            if (useBaseVertex) {
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, idxCount, GL11.GL_UNSIGNED_SHORT, 0L, baseVert);
            } else {
                GL11.glDrawElements(GL11.GL_TRIANGLES, idxCount, GL11.GL_UNSIGNED_SHORT, 0L);
            }
            GL30.glBindVertexArray(0);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        } else {
            int eboId = QuadIndexBuffer.ensureIndexBufferReady(MAX_BATCH_VERTS);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERT_STRIDE, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERT_STRIDE, 12L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERT_STRIDE, 20L);
            if (useBaseVertex) {
                GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, idxCount, GL11.GL_UNSIGNED_SHORT, 0L, baseVert);
            } else {
                GL11.glDrawElements(GL11.GL_TRIANGLES, idxCount, GL11.GL_UNSIGNED_SHORT, 0L);
            }
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
    }

    public void beginBatch() {
        if (this.batching) {
            return;
        }
        ensureUploaded();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA
        );
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(this.textureId);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.batchUsingVbo = ensureRingVboReady();
        if (this.batchUsingVbo) {
            queuedQuads = 0;
            cpuWriteOff = 0;
        } else {
            Tessellator.getInstance().getWorldRenderer().begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        }
        this.batching = true;
    }

    public void endBatch() {
        if (!this.batching) {
            return;
        }
        this.batching = false;
        if (this.batchUsingVbo) {
            flushVboQuads();
        } else {
            Tessellator.getInstance().draw();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
    }

    public void drawString(String text, float x, float y, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        text = FastFontCaches.applyNickHider(text);
        if (this.batching) {
            emitVertices(text, x, y, 1.0F, argb, false);
            return;
        }
        beginBatch();
        emitVertices(text, x, y, 1.0F, argb, false);
        endBatch();
    }

    public void drawStringWithShadow(String text, float x, float y, int argb) {
        if (text == null || text.isEmpty()) {
            return;
        }
        text = FastFontCaches.applyNickHider(text);
        int baseAlpha = ((argb >>> 24) & 0xFF);
        if (baseAlpha == 0) {
            baseAlpha = 255;
            argb |= 0xFF000000;
        }
        int shadowAlpha = (baseAlpha * 115) / 255;
        int shadowArgb = (shadowAlpha << 24) | 0x05070D;
        float shOff = 1.65F / this.scaleDivisor;

        if (this.batching) {
            emitVertices(text, x + shOff, y + shOff, 1.0F, shadowArgb, true);
            emitVertices(text, x, y, 1.0F, argb, false);
            return;
        }

        beginBatch();
        emitVertices(text, x + shOff, y + shOff, 1.0F, shadowArgb, true);
        emitVertices(text, x, y, 1.0F, argb, false);
        endBatch();
    }

    /**
     * Dessine une chaîne mise à l'échelle directement dans le lot actif (0 pushMatrix, 0 changement de draw call).
     */
    public void drawBatchedScaled(String text, float x, float y, float scale, int argb, boolean shadow, boolean centered) {
        if (text == null || text.isEmpty()) {
            return;
        }
        text = FastFontCaches.applyNickHider(text);
        float s = scale > 0.01F ? scale : 1.0F;
        float drawX = centered ? (x - getStringWidthRaw(text) * 0.5F * s) : x;
        if (!shadow) {
            if (this.batching) {
                emitVertices(text, drawX, y, s, argb, false);
                return;
            }
            beginBatch();
            emitVertices(text, drawX, y, s, argb, false);
            endBatch();
            return;
        }

        int baseAlpha = ((argb >>> 24) & 0xFF);
        if (baseAlpha == 0) {
            baseAlpha = 255;
            argb |= 0xFF000000;
        }
        int shadowAlpha = (baseAlpha * 115) / 255;
        int shadowArgb = (shadowAlpha << 24) | 0x05070D;
        float shOff = (1.65F / this.scaleDivisor) * s;

        if (this.batching) {
            emitVertices(text, drawX + shOff, y + shOff, s, shadowArgb, true);
            emitVertices(text, drawX, y, s, argb, false);
            return;
        }

        beginBatch();
        emitVertices(text, drawX + shOff, y + shOff, s, shadowArgb, true);
        emitVertices(text, drawX, y, s, argb, false);
        endBatch();
    }

    public void drawCentered(String text, float centerX, float y, int argb, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        text = FastFontCaches.applyNickHider(text);
        float w = getStringWidthRaw(text);
        if (shadow) {
            drawStringWithShadowRaw(text, centerX - w * 0.5F, y, argb);
        } else {
            drawStringRaw(text, centerX - w * 0.5F, y, argb);
        }
    }

    private float getStringWidthRaw(String text) {
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

    private void drawStringRaw(String text, float x, float y, int argb) {
        if (this.batching) {
            emitVertices(text, x, y, 1.0F, argb, false);
            return;
        }
        beginBatch();
        emitVertices(text, x, y, 1.0F, argb, false);
        endBatch();
    }

    private void drawStringWithShadowRaw(String text, float x, float y, int argb) {
        int baseAlpha = ((argb >>> 24) & 0xFF);
        if (baseAlpha == 0) {
            baseAlpha = 255;
            argb |= 0xFF000000;
        }
        int shadowAlpha = (baseAlpha * 115) / 255;
        int shadowArgb = (shadowAlpha << 24) | 0x05070D;
        float shOff = 1.65F / this.scaleDivisor;
        if (this.batching) {
            emitVertices(text, x + shOff, y + shOff, 1.0F, shadowArgb, true);
            emitVertices(text, x, y, 1.0F, argb, false);
            return;
        }
        beginBatch();
        emitVertices(text, x + shOff, y + shOff, 1.0F, shadowArgb, true);
        emitVertices(text, x, y, 1.0F, argb, false);
        endBatch();
    }

    private void emitVertices(String text, float x, float y, float scale, int argb, boolean ignoreColorCodes) {
        int alphaInt = (argb >>> 24) & 0xFF;
        if (alphaInt == 0) {
            alphaInt = 255;
        }
        int curR = (argb >>> 16) & 0xFF;
        int curG = (argb >>> 8) & 0xFF;
        int curB = argb & 0xFF;
        int packedColor = packRgba(curR, curG, curB, alphaInt);

        float padOffset = ((4.0F * 0.5F) / this.scaleDivisor) * scale;
        float cursorX = x;
        float cursorY = y - (3.0F / this.scaleDivisor) * scale;

        boolean useVbo = this.batchUsingVbo;
        WorldRenderer wr = useVbo ? null : Tessellator.getInstance().getWorldRenderer();
        ByteBuffer buf = CPU_VERTS;

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
                    packedColor = packRgba(curR, curG, curB, alphaInt);
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
            float gx1 = gx0 + g.widthGui * scale;
            float gy1 = gy0 + g.heightGui * scale;

            if (useVbo) {
                if (queuedQuads >= MAX_BATCH_QUADS) {
                    flushVboQuads();
                }
                int off = cpuWriteOff;
                buf.putFloat(off, gx0);      buf.putFloat(off + 4, gy1);  buf.putFloat(off + 8, 0.0F);  buf.putFloat(off + 12, g.u0); buf.putFloat(off + 16, g.v1); buf.putInt(off + 20, packedColor);
                buf.putFloat(off + 24, gx1); buf.putFloat(off + 28, gy1); buf.putFloat(off + 32, 0.0F); buf.putFloat(off + 36, g.u1); buf.putFloat(off + 40, g.v1); buf.putInt(off + 44, packedColor);
                buf.putFloat(off + 48, gx1); buf.putFloat(off + 52, gy0); buf.putFloat(off + 56, 0.0F); buf.putFloat(off + 60, g.u1); buf.putFloat(off + 64, g.v0); buf.putInt(off + 68, packedColor);
                buf.putFloat(off + 72, gx0); buf.putFloat(off + 76, gy0); buf.putFloat(off + 80, 0.0F); buf.putFloat(off + 84, g.u0); buf.putFloat(off + 88, g.v0); buf.putInt(off + 92, packedColor);
                cpuWriteOff = off + 96;
                queuedQuads++;
            } else {
                wr.pos(gx0, gy1, 0.0D).tex(g.u0, g.v1).color(curR, curG, curB, alphaInt).endVertex();
                wr.pos(gx1, gy1, 0.0D).tex(g.u1, g.v1).color(curR, curG, curB, alphaInt).endVertex();
                wr.pos(gx1, gy0, 0.0D).tex(g.u1, g.v0).color(curR, curG, curB, alphaInt).endVertex();
                wr.pos(gx0, gy0, 0.0D).tex(g.u0, g.v0).color(curR, curG, curB, alphaInt).endVertex();
            }

            cursorX += g.advanceGui * scale;
        }
    }

    private static int packRgba(int r, int g, int b, int a) {
        return IS_LITTLE_ENDIAN
                ? ((a << 24) | (b << 16) | (g << 8) | r)
                : ((r << 24) | (g << 16) | (b << 8) | a);
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
        } else if (ch == '\u25B6') {
            slot = SLOT_PLAY;
        } else if (ch == '\u2665') {
            slot = SLOT_HEART;
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
            case SLOT_PLAY: return '\u25B6';
            case SLOT_HEART: return '\u2665';
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
