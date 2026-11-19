package dev.purefps.core;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Batcher OpenGL haute-fréquence sans perte visuelle (100 % identique pixel pour pixel) pour FontRenderer Vanilla :
 * - Remplace les 400 à 1 500 appels glBegin(GL_TRIANGLE_STRIP)/glEnd() caractère par caractère sur Hypixel
 *   (Scoreboard, Chat, Bossbar, Nametags, Hologrammes, Tab) par UN SEUL appel glDrawArrays(GL_TRIANGLES)
 *   via VBO streaming orphané (GL_STREAM_DRAW) sur le même ordre de sommets/winding que Vanilla.
 */
public final class FastFontBatcher {

    // 24 octets par sommet : XYZ (12o) + UV (8o) + RGBA (4o)
    private static final int VERTEX_STRIDE_BYTES = 24;
    private static final int MAX_GLYPHS_PER_FLUSH = 1024;
    private static final int MAX_VERTS_PER_FLUSH = MAX_GLYPHS_PER_FLUSH * 6;
    private static final int BUFFER_CAPACITY_BYTES = MAX_VERTS_PER_FLUSH * VERTEX_STRIDE_BYTES;

    private static ByteBuffer vertexBuffer = null;
    private static int streamingVboId = -1;

    private static int outerBatchDepth = 0;
    private static int activeTexId = -1;
    private static int batchedVertices = 0;
    private static float lastColorR = 1.0F;
    private static float lastColorG = 1.0F;
    private static float lastColorB = 1.0F;
    private static float lastColorA = 1.0F;

    private FastFontBatcher() {
    }

    private static ByteBuffer ensureBuffer() {
        if (vertexBuffer == null) {
            vertexBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY_BYTES).order(ByteOrder.nativeOrder());
        }
        return vertexBuffer;
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

    /**
     * Ajoute un glyphe ASCII (2 triangles GL_TRIANGLES, winding strictement identique au GL_TRIANGLE_STRIP Vanilla).
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
        if (batchedVertices + 6 > MAX_VERTS_PER_FLUSH) {
            flush();
        }

        activeTexId = glTexId;
        lastColorR = r;
        lastColorG = g;
        lastColorB = b;
        lastColorA = a;

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

        int rgba = packRgba(r, g, b, a);
        ByteBuffer buf = ensureBuffer();
        int off = batchedVertices * VERTEX_STRIDE_BYTES;

        // Triangle 1 : TL, BL, TR (identique au 1er triangle du GL_TRIANGLE_STRIP Vanilla)
        putVertex(buf, off, x0Top, y0, u0, v0, rgba);
        putVertex(buf, off + 24, x0Bot, y1, u0, v1, rgba);
        putVertex(buf, off + 48, x1Top, y0, u1, v0, rgba);

        // Triangle 2 : TR, BL, BR (identique au 2e triangle du GL_TRIANGLE_STRIP Vanilla)
        putVertex(buf, off + 72, x1Top, y0, u1, v0, rgba);
        putVertex(buf, off + 96, x0Bot, y1, u0, v1, rgba);
        putVertex(buf, off + 120, x1Bot, y1, u1, v1, rgba);

        batchedVertices += 6;
    }

    private static void putVertex(ByteBuffer buf, int off, float x, float y, float u, float v, int rgba) {
        buf.putFloat(off, x);
        buf.putFloat(off + 4, y);
        buf.putFloat(off + 8, 0.0F);
        buf.putFloat(off + 12, u);
        buf.putFloat(off + 16, v);
        buf.putInt(off + 20, rgba);
    }

    private static int packRgba(float r, float g, float b, float a) {
        int ir = clampByte(Math.round(r * 255.0F));
        int ig = clampByte(Math.round(g * 255.0F));
        int ib = clampByte(Math.round(b * 255.0F));
        int ia = clampByte(Math.round(a * 255.0F));
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            return (ia << 24) | (ib << 16) | (ig << 8) | ir;
        }
        return (ir << 24) | (ig << 16) | (ib << 8) | ia;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    public static void flushIfPending() {
        if (batchedVertices > 0) {
            flush();
        }
    }

    public static void flush() {
        int verts = batchedVertices;
        if (verts <= 0) {
            return;
        }
        batchedVertices = 0;
        int texId = activeTexId;
        if (texId <= 0) {
            return;
        }

        ByteBuffer buf = ensureBuffer();
        int byteLen = verts * VERTEX_STRIDE_BYTES;
        buf.position(0);
        buf.limit(byteLen);

        GlStateManager.bindTexture(texId);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        boolean useVbo = OpenGlHelper.vboSupported;
        if (useVbo) {
            if (streamingVboId <= 0) {
                streamingVboId = OpenGlHelper.glGenBuffers();
            }
            if (streamingVboId > 0) {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, streamingVboId);
                // Orphaning VRAM exact (1 seul appel JNI) : zéro stall CPU<->GPU et zéro sur-allocation
                OpenGlHelper.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, buf, GL15.GL_STREAM_DRAW);

                GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, 12L);
                GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, 20L);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);

                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            } else {
                drawClientArrays(buf, verts);
            }
        } else {
            drawClientArrays(buf, verts);
        }

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);

        // Restaure l'état couleur exact attendu par Vanilla après glColorPointer
        GlStateManager.resetColor();
        GlStateManager.color(lastColorR, lastColorG, lastColorB, lastColorA);
    }

    private static void drawClientArrays(ByteBuffer buf, int verts) {
        if (OpenGlHelper.vboSupported) {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }
        buf.position(0);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, buf);
        buf.position(12);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, buf);
        buf.position(20);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX_STRIDE_BYTES, buf);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
    }

    public static void invalidateGlState() {
        flushIfPending();
        activeTexId = -1;
    }
}
