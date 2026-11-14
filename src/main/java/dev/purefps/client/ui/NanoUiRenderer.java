package dev.purefps.client.ui;

import dev.purefps.client.GlUiBatchRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * Moteur UI vectoriel SDF (Signed Distance Field) haute-fréquence MashClient 4.0 :
 * - Regroupe TOUTES les formes (cartes arrondies, dégradés, cercles, anneaux, ombres portées, lignes magnétiques,
 *   interrupteurs, curseurs) en UN SEUL tampon de sommets direct et UN SEUL appel GL11.glDrawArrays(GL_QUADS) !
 * - ZÉRO appel glUniform* par rectangle et ZÉRO instruction `discard` dans le fragment shader (préserve Early-Z GPU).
 */
public final class NanoUiRenderer {

    private static final int ATTR_LOCAL_HALF = 1;
    private static final int ATTR_PARAMS = 2;
    private static final int ATTR_FILL_TOP = 3;
    private static final int ATTR_FILL_BOT = 4;
    private static final int ATTR_BORDER_COL = 5;

    // 48 octets par sommet :
    // [0..8)   screenX, screenY (2 floats -> gl_Vertex)
    // [8..24)  localX, localY, halfW, halfH (4 floats -> ATTR_LOCAL_HALF)
    // [24..36) radiusPx, borderPx, softnessPx (3 floats -> ATTR_PARAMS)
    // [36..40) fillTop ABGR (4 ubytes norm -> ATTR_FILL_TOP)
    // [40..44) fillBot ABGR (4 ubytes norm -> ATTR_FILL_BOT)
    // [44..48) border  ABGR (4 ubytes norm -> ATTR_BORDER_COL)
    private static final int VERTEX_STRIDE_BYTES = 48;
    private static final int MAX_QUADS_PER_FLUSH = 2048;
    private static final int BUFFER_CAPACITY_BYTES = MAX_QUADS_PER_FLUSH * 4 * VERTEX_STRIDE_BYTES;

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n"
            + "attribute vec4 aLocalHalf;\n"
            + "attribute vec3 aParams;\n"
            + "attribute vec4 aFillTop;\n"
            + "attribute vec4 aFillBot;\n"
            + "attribute vec4 aBorderCol;\n"
            + "varying vec4 vLocalHalf;\n"
            + "varying vec3 vParams;\n"
            + "varying vec4 vFillTop;\n"
            + "varying vec4 vFillBot;\n"
            + "varying vec4 vBorderCol;\n"
            + "void main() {\n"
            + "    vLocalHalf = aLocalHalf;\n"
            + "    vParams = aParams;\n"
            + "    vFillTop = aFillTop;\n"
            + "    vFillBot = aFillBot;\n"
            + "    vBorderCol = aBorderCol;\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAGMENT_SHADER_SRC =
            "#version 120\n"
            + "varying vec4 vLocalHalf;\n"
            + "varying vec3 vParams;\n"
            + "varying vec4 vFillTop;\n"
            + "varying vec4 vFillBot;\n"
            + "varying vec4 vBorderCol;\n"
            + "\n"
            + "float sdRoundBox(vec2 p, vec2 b, float r) {\n"
            + "    vec2 q = abs(p) - b + vec2(r);\n"
            + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 p = vLocalHalf.xy;\n"
            + "    vec2 halfSize = vLocalHalf.zw;\n"
            + "    float r = min(vParams.x, min(halfSize.x, halfSize.y));\n"
            + "    float borderPx = vParams.y;\n"
            + "    float softness = vParams.z;\n"
            + "    float d = sdRoundBox(p, halfSize, r);\n"
            + "    float gradT = clamp((p.y + halfSize.y) / max(halfSize.y * 2.0, 1.0), 0.0, 1.0);\n"
            + "    vec4 fillCol = mix(vFillTop, vFillBot, gradT);\n"
            + "\n"
            + "    if (softness > 1.5) {\n"
            + "        float s = 1.0 - smoothstep(-2.0, softness, d);\n"
            + "        gl_FragColor = vec4(fillCol.rgb, fillCol.a * s * s);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    float outerMask = 1.0 - smoothstep(-0.65, 0.65, d);\n"
            + "    vec4 col = fillCol;\n"
            + "    if (borderPx > 0.05 && vBorderCol.a > 0.002) {\n"
            + "        float innerMask = 1.0 - smoothstep(-0.65, 0.65, d + borderPx);\n"
            + "        float bf = clamp(outerMask - innerMask, 0.0, 1.0);\n"
            + "        vec3 mixedRgb = mix(fillCol.rgb, vBorderCol.rgb, bf);\n"
            + "        float mixedA = mix(fillCol.a, max(fillCol.a, vBorderCol.a), bf);\n"
            + "        col = vec4(mixedRgb, mixedA);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(col.rgb, col.a * outerMask);\n"
            + "}\n";

    private static int shaderState = 0; // 0 = non initialisé, 1 = prêt, -1 = fallback
    private static int programId = 0;

    private static ByteBuffer batchBuffer = null;
    private static int batchedVertices = 0;
    private static float cachedScaleFactor = 2.0F;
    private static boolean batchActive = false;

    private NanoUiRenderer() {
    }

    public static void updateScaleFactor(Minecraft mc) {
        if (mc != null) {
            ScaledResolution sr = new ScaledResolution(mc);
            cachedScaleFactor = Math.max(1.0F, (float) sr.getScaleFactor());
        }
    }

    public static void setScaleFactor(int scaleFactor) {
        cachedScaleFactor = Math.max(1.0F, (float) scaleFactor);
    }

    public static float getScaleFactor() {
        return cachedScaleFactor;
    }

    private static ByteBuffer getBuffer() {
        if (batchBuffer == null) {
            batchBuffer = BufferUtils.createByteBuffer(BUFFER_CAPACITY_BYTES);
        }
        return batchBuffer;
    }

    public static void beginBatch() {
        if (batchActive) {
            return;
        }
        if (!ensureShader()) {
            return;
        }
        getBuffer().clear();
        batchedVertices = 0;
        batchActive = true;
    }

    public static void flushCurrentBatch() {
        if (!batchActive || batchedVertices <= 0) {
            batchedVertices = 0;
            if (batchBuffer != null) {
                batchBuffer.clear();
            }
            return;
        }

        ByteBuffer buf = getBuffer();
        int vertCount = batchedVertices;
        batchedVertices = 0;

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GL20.glUseProgram(programId);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL20.glEnableVertexAttribArray(ATTR_LOCAL_HALF);
        GL20.glEnableVertexAttribArray(ATTR_PARAMS);
        GL20.glEnableVertexAttribArray(ATTR_FILL_TOP);
        GL20.glEnableVertexAttribArray(ATTR_FILL_BOT);
        GL20.glEnableVertexAttribArray(ATTR_BORDER_COL);

        buf.limit(vertCount * VERTEX_STRIDE_BYTES);

        buf.position(0);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, VERTEX_STRIDE_BYTES, buf);

        buf.position(8);
        GL20.glVertexAttribPointer(ATTR_LOCAL_HALF, 4, GL11.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buf);

        buf.position(24);
        GL20.glVertexAttribPointer(ATTR_PARAMS, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buf);

        buf.position(36);
        GL20.glVertexAttribPointer(ATTR_FILL_TOP, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        buf.position(40);
        GL20.glVertexAttribPointer(ATTR_FILL_BOT, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        buf.position(44);
        GL20.glVertexAttribPointer(ATTR_BORDER_COL, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertCount);

        GL20.glDisableVertexAttribArray(ATTR_BORDER_COL);
        GL20.glDisableVertexAttribArray(ATTR_FILL_BOT);
        GL20.glDisableVertexAttribArray(ATTR_FILL_TOP);
        GL20.glDisableVertexAttribArray(ATTR_PARAMS);
        GL20.glDisableVertexAttribArray(ATTR_LOCAL_HALF);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();

        buf.clear();
    }

    public static void endBatch() {
        if (!batchActive) {
            return;
        }
        flushCurrentBatch();
        batchActive = false;
    }

    private static boolean ensureShader() {
        if (shaderState != 0) {
            return shaderState == 1;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null || !caps.OpenGL20) {
                shaderState = -1;
                return false;
            }
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERTEX_SHADER_SRC);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteShader(vs);
                shaderState = -1;
                return false;
            }

            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAGMENT_SHADER_SRC);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteShader(vs);
                GL20.glDeleteShader(fs);
                shaderState = -1;
                return false;
            }

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, ATTR_LOCAL_HALF, "aLocalHalf");
            GL20.glBindAttribLocation(prog, ATTR_PARAMS, "aParams");
            GL20.glBindAttribLocation(prog, ATTR_FILL_TOP, "aFillTop");
            GL20.glBindAttribLocation(prog, ATTR_FILL_BOT, "aFillBot");
            GL20.glBindAttribLocation(prog, ATTR_BORDER_COL, "aBorderCol");
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(prog);
                shaderState = -1;
                return false;
            }

            programId = prog;
            shaderState = 1;
            return true;
        } catch (Throwable t) {
            shaderState = -1;
            return false;
        }
    }

    public static void drawShadow(float x, float y, float w, float h, float radiusGui, float spreadGui, int shadowArgb) {
        if (w <= 0.0F || h <= 0.0F || ((shadowArgb >>> 24) & 0xFF) == 0 || !ensureShader()) {
            return;
        }
        float sf = cachedScaleFactor;
        float padGui = spreadGui + 2.0F;
        float qx0 = x - padGui;
        float qy0 = y - padGui + 1.5F;
        float qx1 = x + w + padGui;
        float qy1 = y + h + padGui + 1.5F;

        float halfQuadWpx = (qx1 - qx0) * sf * 0.5F;
        float halfQuadHpx = (qy1 - qy0) * sf * 0.5F;

        boolean autoFlush = !batchActive;
        if (autoFlush) {
            beginBatch();
        }
        appendQuad(
                qx0, qy0, qx1, qy1,
                halfQuadWpx, halfQuadHpx,
                w * sf * 0.5F, h * sf * 0.5F,
                radiusGui * sf,
                0.0F,
                Math.max(2.0F, spreadGui * sf),
                shadowArgb,
                shadowArgb,
                0
        );
        if (autoFlush) {
            endBatch();
        }
    }

    public static void drawRoundedRect(
            float x,
            float y,
            float w,
            float h,
            float radiusGui,
            int fillArgb,
            int borderArgb,
            float borderPhysPx
    ) {
        drawRoundedGradient(x, y, w, h, radiusGui, fillArgb, fillArgb, borderArgb, borderPhysPx);
    }

    public static void drawRoundedGradient(
            float x,
            float y,
            float w,
            float h,
            float radiusGui,
            int fillTopArgb,
            int fillBottomArgb,
            int borderArgb,
            float borderPhysPx
    ) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        if (((fillTopArgb >>> 24) == 0) && ((fillBottomArgb >>> 24) == 0) && ((borderArgb >>> 24) == 0 || borderPhysPx <= 0.0F)) {
            return;
        }
        if (!ensureShader()) {
            WorldRenderer wr = GlUiBatchRenderer.beginBatch();
            GlUiBatchRenderer.addRoundedCard(wr, x, y, w, h, fillTopArgb, borderArgb);
            GlUiBatchRenderer.flushBatch();
            return;
        }

        float sf = cachedScaleFactor;
        float padGui = 1.0F;
        float qx0 = x - padGui;
        float qy0 = y - padGui;
        float qx1 = x + w + padGui;
        float qy1 = y + h + padGui;

        float halfQuadWpx = (qx1 - qx0) * sf * 0.5F;
        float halfQuadHpx = (qy1 - qy0) * sf * 0.5F;

        boolean autoFlush = !batchActive;
        if (autoFlush) {
            beginBatch();
        }
        appendQuad(
                qx0, qy0, qx1, qy1,
                halfQuadWpx, halfQuadHpx,
                w * sf * 0.5F, h * sf * 0.5F,
                radiusGui * sf,
                borderPhysPx,
                1.0F,
                fillTopArgb,
                fillBottomArgb,
                borderArgb
        );
        if (autoFlush) {
            endBatch();
        }
    }

    public static void drawCircle(float cx, float cy, float radiusGui, int fillArgb) {
        float d = radiusGui * 2.0F;
        drawRoundedRect(cx - radiusGui, cy - radiusGui, d, d, radiusGui, fillArgb, 0, 0.0F);
    }

    public static void drawRing(float cx, float cy, float radiusGui, float thicknessPhysPx, int strokeArgb) {
        float d = radiusGui * 2.0F;
        drawRoundedRect(cx - radiusGui, cy - radiusGui, d, d, radiusGui, 0x00000000, strokeArgb, thicknessPhysPx);
    }

    public static void drawLine(float x0, float y0, float x1, float y1, float thicknessGui, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) {
            return;
        }
        float dx = x1 - x0;
        float dy = y1 - y0;
        if (Math.abs(dy) < 0.01F) {
            float minX = Math.min(x0, x1);
            float len = Math.abs(dx);
            drawRoundedRect(minX, y0 - thicknessGui * 0.5F, Math.max(0.5F, len), thicknessGui, thicknessGui * 0.5F, argb, 0, 0.0F);
            return;
        }
        if (Math.abs(dx) < 0.01F) {
            float minY = Math.min(y0, y1);
            float len = Math.abs(dy);
            drawRoundedRect(x0 - thicknessGui * 0.5F, minY, thicknessGui, Math.max(0.5F, len), thicknessGui * 0.5F, argb, 0, 0.0F);
            return;
        }
        // Segment oblique court (graphe FPS / chevron crosshair)
        int steps = Math.max(2, Math.min(24, (int) (Math.hypot(dx, dy) / Math.max(0.8F, thicknessGui * 0.7F))));
        float r = thicknessGui * 0.55F;
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / (float) steps;
            drawCircle(x0 + dx * t, y0 + dy * t, r, argb);
        }
    }

    public static void drawToggleSwitch(float x, float y, boolean active, boolean hovered) {
        float w = 26.0F;
        float h = 13.5F;
        int trackTop;
        int trackBot;
        int border;
        if (active) {
            trackTop = hovered ? 0xFFF8CA78 : 0xFFEAB354;
            trackBot = hovered ? 0xFFE09A38 : 0xFFC98324;
            border = 0x77FFE6B3;
        } else {
            trackTop = hovered ? 0xFF222634 : 0xFF171A24;
            trackBot = hovered ? 0xFF1B1E2A : 0xFF12151E;
            border = 0x26FFFFFF;
        }
        drawRoundedGradient(x, y, w, h, 6.75F, trackTop, trackBot, border, 1.0F);

        float knobR = 4.8F;
        float knobCx = active ? (x + w - 6.8F) : (x + 6.8F);
        float knobCy = y + h * 0.5F;
        drawCircle(knobCx, knobCy, knobR, active ? 0xFF0A0B0E : 0xFF949CB0);
    }

    public static void drawSlider(float x, float y, float w, float fraction, int accentArgb, boolean hovered) {
        float clamped = fraction < 0.0F ? 0.0F : (fraction > 1.0F ? 1.0F : fraction);
        float trackH = 4.5F;
        drawRoundedRect(x, y, w, trackH, 2.25F, 0xFF141822, 0x22FFFFFF, 1.0F);
        float fillW = Math.max(4.5F, w * clamped);
        if (clamped > 0.005F) {
            drawRoundedRect(x, y, fillW, trackH, 2.25F, accentArgb, 0, 0.0F);
        }
        float knobX = x + w * clamped;
        float knobY = y + trackH * 0.5F;
        drawCircle(knobX, knobY, hovered ? 5.2F : 4.4F, 0xFFF7F4EC);
        drawCircle(knobX, knobY, hovered ? 2.4F : 2.0F, accentArgb);
    }

    private static void appendQuad(
            float x0, float y0, float x1, float y1,
            float halfQuadWpx, float halfQuadHpx,
            float halfBoxWpx, float halfBoxHpx,
            float radiusPx, float borderPx, float softnessPx,
            int fillTopArgb, int fillBotArgb, int borderArgb
    ) {
        if (batchedVertices + 4 > MAX_QUADS_PER_FLUSH * 4) {
            flushCurrentBatch();
        }
        ByteBuffer buf = getBuffer();
        int topAbgr = argbToLittleEndianRgba(fillTopArgb);
        int botAbgr = argbToLittleEndianRgba(fillBotArgb);
        int brdAbgr = argbToLittleEndianRgba(borderArgb);

        putVertex(buf, x0, y1, -halfQuadWpx, halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x1, y1, halfQuadWpx, halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x1, y0, halfQuadWpx, -halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x0, y0, -halfQuadWpx, -halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        batchedVertices += 4;
    }

    private static void putVertex(
            ByteBuffer buf,
            float sx, float sy,
            float lx, float ly, float hw, float hh,
            float rad, float brd, float soft,
            int topAbgr, int botAbgr, int brdAbgr
    ) {
        buf.putFloat(sx);
        buf.putFloat(sy);
        buf.putFloat(lx);
        buf.putFloat(ly);
        buf.putFloat(hw);
        buf.putFloat(hh);
        buf.putFloat(rad);
        buf.putFloat(brd);
        buf.putFloat(soft);
        buf.putInt(topAbgr);
        buf.putInt(botAbgr);
        buf.putInt(brdAbgr);
    }

    private static int argbToLittleEndianRgba(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
