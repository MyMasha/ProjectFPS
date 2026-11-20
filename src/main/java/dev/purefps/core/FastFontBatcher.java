package dev.purefps.core;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Batcher OpenGL haute-fréquence zéro-stall (100 % identique pixel pour pixel) pour FontRenderer Vanilla :
 * - Stocke 4 sommets par glyphe (96 octets au lieu de 144 = -33 % bande passante) avec EBO 16-bit partagé (QuadIndexBuffer).
 * - Utilise un Ring Buffer VBO de taille fixe (512 Ko) + VAO GL30 + GL32.glDrawElementsBaseVertex + glMapBufferRange(UNSYNCHRONIZED),
 *   éliminant les 50-200 glBufferData(GL_STREAM_DRAW) de taille variable par frame (catastrophiques sur Mesa3D Zink & NVIDIA).
 * - Supporte les glyphes ASCII, les pages Unicode (flush uniquement au changement de texture de page),
 *   les décorations §m/§n (batchées en un seul draw sans texture) et la transformation CPU optionnelle (scale/offset).
 */
public final class FastFontBatcher {

    // 24 octets par sommet : XYZ (12o) + UV (8o) + RGBA (4o)
    public static final int VERTEX_STRIDE_BYTES = 24;
    private static final int VERTS_PER_QUAD = 4;
    private static final int MAX_GLYPHS_PER_FLUSH = 2048;
    private static final int MAX_VERTS_PER_FLUSH = MAX_GLYPHS_PER_FLUSH * VERTS_PER_QUAD;
    private static final int BUFFER_CAPACITY_BYTES = MAX_VERTS_PER_FLUSH * VERTEX_STRIDE_BYTES;

    private static final int MAX_DECOR_QUADS_PER_FLUSH = 512;
    private static final int MAX_DECOR_VERTS = MAX_DECOR_QUADS_PER_FLUSH * VERTS_PER_QUAD;
    private static final int DECOR_CAPACITY_BYTES = MAX_DECOR_VERTS * VERTEX_STRIDE_BYTES;

    // Ring VBO fixe de 512 Ko (~5 461 glyphes avant wrap-around) -> orphaning ~1x toutes les 5-15 frames
    public static final int RING_CAPACITY_VERTS = 21844; // 21844 * 24 = 524 256 octets (~512 Ko)
    public static final int RING_CAPACITY_BYTES = RING_CAPACITY_VERTS * VERTEX_STRIDE_BYTES;

    private static final boolean IS_LITTLE_ENDIAN = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
    private static final int MAP_FLAGS_UNSYNC = GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;

    private static ByteBuffer vertexBuffer = null;
    private static ByteBuffer decorBuffer = null;

    private static int ringVboId = -1;
    private static int glyphVaoId = -1;
    private static int decorVaoId = -1;
    private static int ringWriteVert = 0;
    private static int capsState = 0; // 0=inconnu, 1=GL32+VAO+Ring, 2=VBO+EBO legacy, -1=client arrays
    private static boolean vboFailed = false;
    private static ByteBuffer mappedScratch = null;

    private static int outerBatchDepth = 0;
    private static int activeTexId = -1;
    private static int batchedVertices = 0;
    private static int batchedDecorVertices = 0;

    private static float lastColorR = 1.0F;
    private static float lastColorG = 1.0F;
    private static float lastColorB = 1.0F;
    private static float lastColorA = 1.0F;
    private static int lastPackedRgba = 0xFFFFFFFF;
    private static boolean colorCacheValid = false;

    // Transformation CPU optionnelle (élimine glPushMatrix/translate/scale/popMatrix dans Tab/HUD sans casser le batch)
    private static boolean cpuTransformActive = false;
    private static float cpuOffsetX = 0.0F;
    private static float cpuOffsetY = 0.0F;
    private static float cpuOffsetZ = 0.0F;
    private static float cpuScale = 1.0F;

    private FastFontBatcher() {
    }

    private static ByteBuffer ensureBuffer() {
        if (vertexBuffer == null) {
            vertexBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY_BYTES).order(ByteOrder.nativeOrder());
        }
        return vertexBuffer;
    }

    private static ByteBuffer ensureDecorBuffer() {
        if (decorBuffer == null) {
            decorBuffer = ByteBuffer.allocateDirect(DECOR_CAPACITY_BYTES).order(ByteOrder.nativeOrder());
        }
        return decorBuffer;
    }

    public static void beginOuterBatch() {
        outerBatchDepth++;
    }

    public static void endOuterBatch() {
        if (outerBatchDepth > 0) {
            outerBatchDepth--;
            if (outerBatchDepth == 0) {
                flush();
            }
        }
    }

    public static boolean isOuterBatchActive() {
        return outerBatchDepth > 0;
    }

    public static boolean hasPendingDecorations() {
        return batchedDecorVertices > 0;
    }

    public static void setCpuTransform(float offsetX, float offsetY, float offsetZ, float scale) {
        cpuTransformActive = true;
        cpuOffsetX = offsetX;
        cpuOffsetY = offsetY;
        cpuOffsetZ = offsetZ;
        cpuScale = scale;
    }

    public static void clearCpuTransform() {
        cpuTransformActive = false;
        cpuOffsetX = 0.0F;
        cpuOffsetY = 0.0F;
        cpuOffsetZ = 0.0F;
        cpuScale = 1.0F;
    }

    /**
     * Ajoute un glyphe ASCII (4 sommets indexés via QuadIndexBuffer -> 2 triangles identiques au GL_TRIANGLE_STRIP Vanilla).
     */
    public static void appendAsciiGlyph(
            int glTexId,
            float posX,
            float posY,
            int colPx,
            int rowPx,
            float widthMinusEps,
            float italicOff,
            float r,
            float g,
            float b,
            float a
    ) {
        if (glTexId <= 0) {
            return;
        }
        if (batchedVertices > 0 && activeTexId != glTexId) {
            flush();
        }
        if (batchedVertices + VERTS_PER_QUAD > MAX_VERTS_PER_FLUSH) {
            flush();
        }

        activeTexId = glTexId;
        int rgba = packRgbaCached(r, g, b, a);

        float u0 = colPx * (1.0F / 128.0F);
        float v0 = rowPx * (1.0F / 128.0F);
        float u1 = (colPx + widthMinusEps - 1.0F) * (1.0F / 128.0F);
        float v1 = (rowPx + 7.99F) * (1.0F / 128.0F);

        float x0Top = posX + italicOff;
        float x0Bot = posX - italicOff;
        float x1Top = posX + widthMinusEps - 1.0F + italicOff;
        float x1Bot = posX + widthMinusEps - 1.0F - italicOff;
        float y0 = posY;
        float y1 = posY + 7.99F;
        float z = 0.0F;

        if (cpuTransformActive) {
            x0Top = cpuOffsetX + x0Top * cpuScale;
            x0Bot = cpuOffsetX + x0Bot * cpuScale;
            x1Top = cpuOffsetX + x1Top * cpuScale;
            x1Bot = cpuOffsetX + x1Bot * cpuScale;
            y0 = cpuOffsetY + y0 * cpuScale;
            y1 = cpuOffsetY + y1 * cpuScale;
            z = cpuOffsetZ;
        }

        appendQuadVerts(x0Top, y0, x0Bot, y1, x1Bot, x1Top, z, u0, v0, u1, v1, rgba);
    }

    /**
     * Ajoute un glyphe Unicode (page texture GL spécifique) sans casser le batch tant que la page reste identique.
     */
    public static void appendUnicodeGlyph(
            int pageTexId,
            float posX,
            float posY,
            float u0,
            float v0,
            float u1,
            float v1,
            float widthHalf,
            float italicOff,
            float r,
            float g,
            float b,
            float a
    ) {
        if (pageTexId <= 0) {
            return;
        }
        if (batchedVertices > 0 && activeTexId != pageTexId) {
            flush();
        }
        if (batchedVertices + VERTS_PER_QUAD > MAX_VERTS_PER_FLUSH) {
            flush();
        }

        activeTexId = pageTexId;
        int rgba = packRgbaCached(r, g, b, a);

        float x0Top = posX + italicOff;
        float x0Bot = posX - italicOff;
        float x1Top = posX + widthHalf + italicOff;
        float x1Bot = posX + widthHalf - italicOff;
        float y0 = posY;
        float y1 = posY + 7.99F;
        float z = 0.0F;

        if (cpuTransformActive) {
            x0Top = cpuOffsetX + x0Top * cpuScale;
            x0Bot = cpuOffsetX + x0Bot * cpuScale;
            x1Top = cpuOffsetX + x1Top * cpuScale;
            x1Bot = cpuOffsetX + x1Bot * cpuScale;
            y0 = cpuOffsetY + y0 * cpuScale;
            y1 = cpuOffsetY + y1 * cpuScale;
            z = cpuOffsetZ;
        }

        appendQuadVerts(x0Top, y0, x0Bot, y1, x1Bot, x1Top, z, u0, v0, u1, v1, rgba);
    }

    private static void appendQuadVerts(
            float x0Top,
            float y0,
            float x0Bot,
            float y1,
            float x1Bot,
            float x1Top,
            float z,
            float u0,
            float v0,
            float u1,
            float v1,
            int rgba
    ) {
        ByteBuffer buf = ensureBuffer();
        int off = batchedVertices * VERTEX_STRIDE_BYTES;
        if (batchedVertices == 0 || buf.limit() < off + (VERTS_PER_QUAD * VERTEX_STRIDE_BYTES)) {
            buf.clear();
        }

        // Ordre QuadIndexBuffer {0,1,2, 2,3,0} : v0=TL, v1=BL, v2=BR, v3=TR (winding strictement identique Vanilla)
        putVertex(buf, off, x0Top, y0, z, u0, v0, rgba);
        putVertex(buf, off + 24, x0Bot, y1, z, u0, v1, rgba);
        putVertex(buf, off + 48, x1Bot, y1, z, u1, v1, rgba);
        putVertex(buf, off + 72, x1Top, y0, z, u1, v0, rgba);

        batchedVertices += VERTS_PER_QUAD;
    }

    /**
     * Accumule un quad de décoration sans texture (§m barré / §n souligné) dessiné en 1 seul draw au flush.
     */
    public static void appendDecorationQuad(
            float x0,
            float y0,
            float x1,
            float y1,
            float r,
            float g,
            float b,
            float a
    ) {
        if (batchedDecorVertices + VERTS_PER_QUAD > MAX_DECOR_VERTS) {
            flush();
        }
        int rgba = packRgbaCached(r, g, b, a);
        float z = 0.0F;
        if (cpuTransformActive) {
            x0 = cpuOffsetX + x0 * cpuScale;
            x1 = cpuOffsetX + x1 * cpuScale;
            y0 = cpuOffsetY + y0 * cpuScale;
            y1 = cpuOffsetY + y1 * cpuScale;
            z = cpuOffsetZ;
        }

        ByteBuffer dbuf = ensureDecorBuffer();
        int off = batchedDecorVertices * VERTEX_STRIDE_BYTES;
        if (batchedDecorVertices == 0 || dbuf.limit() < off + (VERTS_PER_QUAD * VERTEX_STRIDE_BYTES)) {
            dbuf.clear();
        }

        // Quad plein CCW : TL, BL, BR, TR (correspond à {0,1,2, 2,3,0})
        putVertex(dbuf, off, x0, y0, z, 0.0F, 0.0F, rgba);
        putVertex(dbuf, off + 24, x0, y1, z, 0.0F, 0.0F, rgba);
        putVertex(dbuf, off + 48, x1, y1, z, 0.0F, 0.0F, rgba);
        putVertex(dbuf, off + 72, x1, y0, z, 0.0F, 0.0F, rgba);

        batchedDecorVertices += VERTS_PER_QUAD;
    }

    private static void putVertex(ByteBuffer buf, int off, float x, float y, float z, float u, float v, int rgba) {
        buf.putFloat(off, x);
        buf.putFloat(off + 4, y);
        buf.putFloat(off + 8, z);
        buf.putFloat(off + 12, u);
        buf.putFloat(off + 16, v);
        buf.putInt(off + 20, rgba);
    }

    private static int packRgbaCached(float r, float g, float b, float a) {
        if (colorCacheValid && r == lastColorR && g == lastColorG && b == lastColorB && a == lastColorA) {
            return lastPackedRgba;
        }
        lastColorR = r;
        lastColorG = g;
        lastColorB = b;
        lastColorA = a;
        int ir = clampByte((int) (r * 255.0F + 0.5F));
        int ig = clampByte((int) (g * 255.0F + 0.5F));
        int ib = clampByte((int) (b * 255.0F + 0.5F));
        int ia = clampByte((int) (a * 255.0F + 0.5F));
        lastPackedRgba = IS_LITTLE_ENDIAN
                ? ((ia << 24) | (ib << 16) | (ig << 8) | ir)
                : ((ir << 24) | (ig << 16) | (ib << 8) | ia);
        colorCacheValid = true;
        return lastPackedRgba;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    public static void flushIfPending() {
        if (batchedVertices > 0 || batchedDecorVertices > 0) {
            flush();
        }
    }

    // --- Helpers de test unitaire (sans contexte GL) ---

    static ByteBuffer prepareFlushSliceForTest() {
        int verts = batchedVertices;
        batchedVertices = 0;
        ByteBuffer buf = ensureBuffer();
        buf.position(0);
        buf.limit(verts * VERTEX_STRIDE_BYTES);
        return buf;
    }

    static ByteBuffer prepareDecorSliceForTest() {
        int verts = batchedDecorVertices;
        batchedDecorVertices = 0;
        ByteBuffer buf = ensureDecorBuffer();
        buf.position(0);
        buf.limit(verts * VERTEX_STRIDE_BYTES);
        return buf;
    }

    static int allocateRingSliceVertsForTest(int verts) {
        return advanceRingWriteVert(verts);
    }

    static void resetRingCursorForTest() {
        ringWriteVert = 0;
    }

    static void finishFlushReset() {
        if (vertexBuffer != null) {
            vertexBuffer.clear();
        }
        if (decorBuffer != null) {
            decorBuffer.clear();
        }
        batchedVertices = 0;
        batchedDecorVertices = 0;
    }

    private static int advanceRingWriteVert(int verts) {
        if (ringWriteVert + verts > RING_CAPACITY_VERTS) {
            ringWriteVert = 0;
        }
        int base = ringWriteVert;
        ringWriteVert += verts;
        return base;
    }

    private static int resolveCaps() {
        if (capsState != 0) {
            return capsState;
        }
        if (vboFailed || !OpenGlHelper.vboSupported || !QuadIndexBuffer.isSupported()) {
            capsState = -1;
            return capsState;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps != null && caps.OpenGL30 && caps.OpenGL32) {
                capsState = 1;
            } else {
                capsState = 2;
            }
        } catch (Exception | LinkageError ignored) {
            capsState = -1;
        }
        return capsState;
    }

    private static boolean ensureRingVboAndVaos(int mode) {
        if (vboFailed) {
            return false;
        }
        if (ringVboId <= 0) {
            ringVboId = OpenGlHelper.glGenBuffers();
            if (ringVboId <= 0) {
                vboFailed = true;
                return false;
            }
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            ringWriteVert = 0;
        }
        if (mode == 1 && (glyphVaoId <= 0 || decorVaoId <= 0)) {
            int eboId = QuadIndexBuffer.ensureIndexBufferReady(MAX_VERTS_PER_FLUSH);
            if (eboId <= 0) {
                return false;
            }
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

            glyphVaoId = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(glyphVaoId);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 0L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 12L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, 20L);

            decorVaoId = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(decorVaoId);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 0L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, 20L);

            GL30.glBindVertexArray(0);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
        return true;
    }

    private static int uploadSliceToRing(ByteBuffer src, int verts, boolean allowMapRange) {
        int byteLen = verts * VERTEX_STRIDE_BYTES;
        boolean wrapped = (ringWriteVert + verts > RING_CAPACITY_VERTS);
        if (wrapped) {
            // Orphaning complet uniquement lors du wrap-around (1x toutes les ~5 400 glyphes)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, RING_CAPACITY_BYTES, GL15.GL_STREAM_DRAW);
            ringWriteVert = 0;
        }
        int baseVert = ringWriteVert;
        long byteOffset = (long) baseVert * VERTEX_STRIDE_BYTES;
        ringWriteVert += verts;

        src.position(0);
        src.limit(byteLen);

        if (allowMapRange) {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    GL15.GL_ARRAY_BUFFER,
                    byteOffset,
                    byteLen,
                    MAP_FLAGS_UNSYNC,
                    mappedScratch
            );
            if (mapped != null) {
                mappedScratch = mapped;
                mapped.clear();
                mapped.put(src);
                GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                return baseVert;
            }
        }
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, byteOffset, src);
        return baseVert;
    }

    public static void flush() {
        int verts = batchedVertices;
        int decorVerts = batchedDecorVertices;
        if (verts <= 0 && decorVerts <= 0) {
            finishFlushReset();
            return;
        }
        batchedVertices = 0;
        batchedDecorVertices = 0;
        int texId = activeTexId;

        int mode = resolveCaps();
        boolean useRingVao = (mode == 1) && ensureRingVboAndVaos(1);
        boolean useLegacyVbo = !useRingVao && (mode == 2) && ensureRingVboAndVaos(2);

        try {
            if (verts > 0 && texId > 0) {
                ByteBuffer buf = ensureBuffer();
                int indexCount = (verts >> 2) * 6;
                GlStateManager.bindTexture(texId);
                if (useRingVao) {
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
                    int baseVert = uploadSliceToRing(buf, verts, true);
                    GL30.glBindVertexArray(glyphVaoId);
                    GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_SHORT, 0L, baseVert);
                    GL30.glBindVertexArray(0);
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
                } else if (useLegacyVbo) {
                    drawLegacyVbo(buf, verts, true);
                } else {
                    drawClientArrays(buf, verts, true);
                }
            }

            if (decorVerts > 0) {
                ByteBuffer dbuf = ensureDecorBuffer();
                int decorIndexCount = (decorVerts >> 2) * 6;
                GlStateManager.disableTexture2D();
                if (useRingVao) {
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
                    int baseVert = uploadSliceToRing(dbuf, decorVerts, true);
                    GL30.glBindVertexArray(decorVaoId);
                    GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, decorIndexCount, GL11.GL_UNSIGNED_SHORT, 0L, baseVert);
                    GL30.glBindVertexArray(0);
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
                } else if (useLegacyVbo) {
                    drawLegacyVbo(dbuf, decorVerts, false);
                } else {
                    drawClientArrays(dbuf, decorVerts, false);
                }
                GlStateManager.enableTexture2D();
            }

            // Restaure l'état couleur exact attendu par Vanilla après glColorPointer
            GlStateManager.resetColor();
            GlStateManager.color(lastColorR, lastColorG, lastColorB, lastColorA);
        } finally {
            if (vertexBuffer != null) {
                vertexBuffer.clear();
            }
            if (decorBuffer != null) {
                decorBuffer.clear();
            }
        }
    }

    private static void drawLegacyVbo(ByteBuffer buf, int verts, boolean textured) {
        int eboId = QuadIndexBuffer.ensureIndexBufferReady(verts);
        int indexCount = (verts >> 2) * 6;
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, ringVboId);
        int baseVert = uploadSliceToRing(buf, verts, false);
        long byteOff = (long) baseVert * VERTEX_STRIDE_BYTES;

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        if (textured) {
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, byteOff);
        if (textured) {
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, byteOff + 12L);
        }
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, byteOff + 20L);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_SHORT, 0L);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        if (textured) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    private static void drawClientArrays(ByteBuffer buf, int verts, boolean textured) {
        if (OpenGlHelper.vboSupported) {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        if (textured) {
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        buf.position(0);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, buf);
        if (textured) {
            buf.position(12);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, buf);
        }
        buf.position(20);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, buf);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, verts);

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        if (textured) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    public static void invalidateGlState() {
        flushIfPending();
        activeTexId = -1;
        colorCacheValid = false;
        finishFlushReset();
    }

    public static void deleteGlResources() {
        invalidateGlState();
        if (glyphVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(glyphVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            glyphVaoId = -1;
        }
        if (decorVaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(decorVaoId);
            } catch (Exception | LinkageError ignored) {
            }
            decorVaoId = -1;
        }
        if (ringVboId > 0) {
            try {
                OpenGlHelper.glDeleteBuffers(ringVboId);
            } catch (Exception | LinkageError ignored) {
            }
            ringVboId = -1;
        }
        mappedScratch = null;
        ringWriteVert = 0;
        capsState = 0;
    }
}
