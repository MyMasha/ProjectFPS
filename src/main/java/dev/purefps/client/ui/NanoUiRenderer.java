package dev.purefps.client.ui;

import dev.purefps.client.GlUiBatchRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * Moteur UI vectoriel SDF (Signed Distance Field) haute-fréquence MashClient 4.1 :
 * - Regroupe toutes les formes (cartes en verre fumé, dégradés, pilules, ombres portées, lignes magnétiques,
 *   interrupteurs dorés, curseurs) en un seul tampon direct et un seul appel GL11.glDrawArrays(GL_TRIANGLES).
 * - Utilise exclusivement des attributs génériques purs (aPos lié sur l'emplacement 0, zéro mélange gl_Vertex / gl_Color)
 *   et délie systématiquement GL_ARRAY_BUFFER pour une compatibilité totale NVIDIA / AMD / Intel avec VBO activés.
 * - Fournit un viewport Bureau indépendant du GuiScale Minecraft (~1280x720 logiques) pour des menus nets en toute résolution.
 */
public final class NanoUiRenderer {

    public static final float DESKTOP_BASE_W = 1280.0F;
    public static final float DESKTOP_BASE_H = 720.0F;

    // 48 octets par sommet (6 sommets / 2 triangles par quad) :
    // [0..8)   screenX, screenY (2 floats -> aPos, location 0)
    // [8..24)  localX, localY, halfW, halfH (4 floats -> aLocalHalf)
    // [24..36) radiusPx, borderPx, softnessPx (3 floats -> aParams)
    // [36..40) fillTop RGBA (4 ubytes norm -> aFillTop)
    // [40..44) fillBot RGBA (4 ubytes norm -> aFillBot)
    // [44..48) border  RGBA (4 ubytes norm -> aBorderCol)
    private static final int VERTEX_STRIDE_BYTES = 48;
    private static final int MAX_QUADS_PER_FLUSH = 2048;
    private static final int MAX_VERTS_PER_FLUSH = MAX_QUADS_PER_FLUSH * 6;
    private static final int BUFFER_CAPACITY_BYTES = MAX_VERTS_PER_FLUSH * VERTEX_STRIDE_BYTES;

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n"
            + "attribute vec2 aPos;\n"
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
            + "    gl_Position = gl_ModelViewProjectionMatrix * vec4(aPos, 0.0, 1.0);\n"
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
    private static int attrPos = 0;
    private static int attrLocalHalf = -1;
    private static int attrParams = -1;
    private static int attrFillTop = -1;
    private static int attrFillBot = -1;
    private static int attrBorderCol = -1;

    private static ByteBuffer batchBuffer = null;
    private static int batchedVertices = 0;
    private static float cachedScaleFactor = 2.0F;
    private static float savedScaleBeforeDesktop = 2.0F;
    private static boolean desktopViewportActive = false;
    private static boolean batchActive = false;

    private NanoUiRenderer() {
    }

    public static float desktopScale(Minecraft mc) {
        if (mc == null) {
            return 1.5F;
        }
        float w = Math.max(320, mc.displayWidth);
        float h = Math.max(240, mc.displayHeight);
        float s = Math.min(w / DESKTOP_BASE_W, h / DESKTOP_BASE_H);
        return Math.max(0.50F, Math.min(4.0F, s));
    }

    public static float desktopWidth(Minecraft mc) {
        if (mc == null) {
            return DESKTOP_BASE_W;
        }
        return Math.max(320, mc.displayWidth) / desktopScale(mc);
    }

    public static float desktopHeight(Minecraft mc) {
        if (mc == null) {
            return DESKTOP_BASE_H;
        }
        return Math.max(240, mc.displayHeight) / desktopScale(mc);
    }

    public static float desktopMouseX(Minecraft mc) {
        if (mc == null || !Mouse.isCreated()) {
            return 0.0F;
        }
        return Mouse.getX() / desktopScale(mc);
    }

    public static float desktopMouseY(Minecraft mc) {
        if (mc == null || !Mouse.isCreated()) {
            return 0.0F;
        }
        return (mc.displayHeight - Mouse.getY() - 1) / desktopScale(mc);
    }

    public static float desktopEventMouseX(Minecraft mc) {
        if (mc == null || !Mouse.isCreated()) {
            return 0.0F;
        }
        return Mouse.getEventX() / desktopScale(mc);
    }

    public static float desktopEventMouseY(Minecraft mc) {
        if (mc == null || !Mouse.isCreated()) {
            return 0.0F;
        }
        return (mc.displayHeight - Mouse.getEventY() - 1) / desktopScale(mc);
    }

    public static void beginDesktopViewport(Minecraft mc) {
        if (desktopViewportActive || mc == null) {
            return;
        }
        if (batchActive) {
            endBatch();
        }
        savedScaleBeforeDesktop = cachedScaleFactor;
        float ds = desktopScale(mc);
        float dw = Math.max(320, mc.displayWidth) / ds;
        float dh = Math.max(240, mc.displayHeight) / ds;
        cachedScaleFactor = Math.max(1.0F, ds);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, dw, dh, 0.0D, 1000.0D, 3000.0D);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        desktopViewportActive = true;
    }

    public static void endDesktopViewport() {
        if (!desktopViewportActive) {
            return;
        }
        if (batchActive) {
            endBatch();
        }
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        cachedScaleFactor = savedScaleBeforeDesktop;
        desktopViewportActive = false;
    }

    public static void enableDesktopScissor(Minecraft mc, float x, float y, float w, float h) {
        if (mc == null) {
            return;
        }
        float ds = desktopScale(mc);
        int sx = Math.max(0, Math.round(x * ds));
        int sy = Math.max(0, mc.displayHeight - Math.round((y + h) * ds));
        int sw = Math.max(1, Math.round(w * ds));
        int sh = Math.max(1, Math.round(h * ds));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
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
        GlStateManager.disableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        // Critique avec VBO Minecraft actif : délier GL_ARRAY_BUFFER avant de passer un ByteBuffer client !
        if (OpenGlHelper.useVbo()) {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }

        GL20.glUseProgram(programId);
        GL20.glEnableVertexAttribArray(attrPos);
        GL20.glEnableVertexAttribArray(attrLocalHalf);
        GL20.glEnableVertexAttribArray(attrParams);
        GL20.glEnableVertexAttribArray(attrFillTop);
        GL20.glEnableVertexAttribArray(attrFillBot);
        GL20.glEnableVertexAttribArray(attrBorderCol);

        buf.limit(vertCount * VERTEX_STRIDE_BYTES);

        buf.position(0);
        GL20.glVertexAttribPointer(attrPos, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buf);

        buf.position(8);
        GL20.glVertexAttribPointer(attrLocalHalf, 4, GL11.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buf);

        buf.position(24);
        GL20.glVertexAttribPointer(attrParams, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buf);

        buf.position(36);
        GL20.glVertexAttribPointer(attrFillTop, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        buf.position(40);
        GL20.glVertexAttribPointer(attrFillBot, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        buf.position(44);
        GL20.glVertexAttribPointer(attrBorderCol, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, buf);

        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);

        GL20.glDisableVertexAttribArray(attrBorderCol);
        GL20.glDisableVertexAttribArray(attrFillBot);
        GL20.glDisableVertexAttribArray(attrFillTop);
        GL20.glDisableVertexAttribArray(attrParams);
        GL20.glDisableVertexAttribArray(attrLocalHalf);
        GL20.glDisableVertexAttribArray(attrPos);
        GL20.glUseProgram(0);
        GlStateManager.enableCull();
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
            // Seul aPos est fixé sur 0 ; le pilote attribue librement les autres sans collision gl_Color !
            GL20.glBindAttribLocation(prog, 0, "aPos");
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(prog);
                shaderState = -1;
                return false;
            }

            attrPos = GL20.glGetAttribLocation(prog, "aPos");
            attrLocalHalf = GL20.glGetAttribLocation(prog, "aLocalHalf");
            attrParams = GL20.glGetAttribLocation(prog, "aParams");
            attrFillTop = GL20.glGetAttribLocation(prog, "aFillTop");
            attrFillBot = GL20.glGetAttribLocation(prog, "aFillBot");
            attrBorderCol = GL20.glGetAttribLocation(prog, "aBorderCol");

            if (attrPos < 0 || attrLocalHalf < 0 || attrParams < 0
                    || attrFillTop < 0 || attrFillBot < 0 || attrBorderCol < 0) {
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
        int steps = Math.max(2, Math.min(24, (int) (Math.hypot(dx, dy) / Math.max(0.8F, thicknessGui * 0.7F))));
        float r = thicknessGui * 0.55F;
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / (float) steps;
            drawCircle(x0 + dx * t, y0 + dy * t, r, argb);
        }
    }

    /**
     * Interrupteur pilule or chaud style Launcher Singularity (36x19 px en viewport Bureau).
     */
    public static void drawLauncherSwitch(float x, float y, boolean active, boolean hovered, float alphaFactor) {
        float w = 36.0F;
        float h = 19.0F;
        int trackTop;
        int trackBot;
        int border;
        int knob;
        if (active) {
            trackTop = hovered ? 0xFFF7CA77 : 0xFFEBB65B;
            trackBot = hovered ? 0xFFE29B39 : 0xFFCE8B2E;
            border = 0x88FFE6B3;
            knob = 0xFF0B0D12;
        } else {
            trackTop = hovered ? 0xFF1A1E28 : 0xFF11141B;
            trackBot = hovered ? 0xFF141720 : 0xFF0C0E14;
            border = hovered ? 0x55EAB354 : 0x30FFFFFF;
            knob = hovered ? 0xFFD8DEE9 : 0xFF8C95A8;
        }
        drawRoundedGradient(
                x, y, w, h, h * 0.5F,
                scaleAlpha(trackTop, alphaFactor),
                scaleAlpha(trackBot, alphaFactor),
                scaleAlpha(border, alphaFactor),
                1.1F
        );
        float knobR = 6.6F;
        float knobCx = active ? (x + w - 9.6F) : (x + 9.6F);
        float knobCy = y + h * 0.5F;
        drawCircle(knobCx, knobCy, knobR, scaleAlpha(knob, alphaFactor));
    }

    public static void drawToggleSwitch(float x, float y, boolean active, boolean hovered) {
        drawLauncherSwitch(x, y, active, hovered, 1.0F);
    }

    /**
     * Interrupteur pilule compact style Lunar Client (28x15 px en viewport Bureau).
     */
    public static void drawCompactSwitch(float x, float y, boolean active, boolean hovered, float alphaFactor) {
        float w = 28.0F;
        float h = 15.0F;
        int trackTop;
        int trackBot;
        int border;
        int knob;
        if (active) {
            trackTop = hovered ? 0xFFF7CA77 : 0xFFEBB65B;
            trackBot = hovered ? 0xFFE29B39 : 0xFFCE8B2E;
            border = 0x88FFE6B3;
            knob = 0xFF0B0D12;
        } else {
            trackTop = hovered ? 0xFF1A1E28 : 0xFF11141B;
            trackBot = hovered ? 0xFF141720 : 0xFF0C0E14;
            border = hovered ? 0x55EAB354 : 0x2CFFFFFF;
            knob = hovered ? 0xFFD8DEE9 : 0xFF8C95A8;
        }
        drawRoundedGradient(
                x, y, w, h, h * 0.5F,
                scaleAlpha(trackTop, alphaFactor),
                scaleAlpha(trackBot, alphaFactor),
                scaleAlpha(border, alphaFactor),
                1.0F
        );
        float knobR = 5.1F;
        float knobCx = active ? (x + w - 7.5F) : (x + 7.5F);
        float knobCy = y + h * 0.5F;
        drawCircle(knobCx, knobCy, knobR, scaleAlpha(knob, alphaFactor));
    }

    public static void drawSlider(float x, float y, float w, float fraction, int accentArgb, boolean hovered) {
        float clamped = fraction < 0.0F ? 0.0F : (fraction > 1.0F ? 1.0F : fraction);
        float trackH = 6.0F;
        drawRoundedRect(x, y, w, trackH, 3.0F, 0xFF12151E, 0x28FFFFFF, 1.0F);
        float fillW = Math.max(6.0F, w * clamped);
        if (clamped > 0.005F) {
            drawRoundedRect(x, y, fillW, trackH, 3.0F, accentArgb, 0, 0.0F);
        }
        float knobX = x + w * clamped;
        float knobY = y + trackH * 0.5F;
        drawCircle(knobX, knobY, hovered ? 6.8F : 5.8F, 0xFFF7F4EC);
        drawCircle(knobX, knobY, hovered ? 3.2F : 2.6F, accentArgb);
    }

    private static final ResourceLocation MASH_MARK_TEX =
            new ResourceLocation("purefps", "textures/gui/mash_mark.png");

    /**
     * Dessine le logo haute définition MashClient (128x128 4x SSAA RGBA filtré GL_LINEAR).
     * Peut être appelé à l'intérieur ou en dehors d'un bloc beginBatch()/endBatch().
     */
    public static void drawBrandLogo(Minecraft mc, float cx, float cy, float size, float alphaFactor) {
        if (size <= 0.0F || alphaFactor <= 0.004F) {
            return;
        }
        if (batchActive) {
            flushCurrentBatch();
        }
        Minecraft client = mc != null ? mc : Minecraft.getMinecraft();
        if (client == null || client.getTextureManager() == null) {
            return;
        }
        float a = Math.max(0.0F, Math.min(1.0F, alphaFactor));
        float half = size * 0.5F;
        double x0 = cx - half;
        double y0 = cy - half;
        double x1 = cx + half;
        double y1 = cy + half;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, a);

        client.getTextureManager().bindTexture(MASH_MARK_TEX);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x0, y1, 0.0D).tex(0.0D, 1.0D).endVertex();
        wr.pos(x1, y1, 0.0D).tex(1.0D, 1.0D).endVertex();
        wr.pos(x1, y0, 0.0D).tex(1.0D, 0.0D).endVertex();
        wr.pos(x0, y0, 0.0D).tex(0.0D, 0.0D).endVertex();
        tess.draw();

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
    }

    public static void drawSingularityEmblem(float cx, float cy, float size, float alphaFactor) {
        drawBrandLogo(Minecraft.getMinecraft(), cx, cy, size, alphaFactor);
    }

    /**
     * Icône vectorielle Play (triangle orienté à droite, anti-aliasé SDF, zéro police Unicode).
     */
    public static void drawPlayIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float x0 = cx - size * 0.34F;
        float x1 = cx + size * 0.44F;
        float maxHalfH = size * 0.45F;
        int slices = 10;
        float bw = ((x1 - x0) / slices) * 1.68F;
        for (int i = 0; i < slices; i++) {
            float t = i / (float) (slices - 1);
            float sx = x0 + (x1 - x0) * t;
            float halfH = Math.max(0.70F, maxHalfH * (1.0F - t * 0.88F));
            float r = Math.min(bw * 0.5F, halfH);
            drawRoundedRect(sx - bw * 0.5F, cy - halfH, bw, halfH * 2.0F, r, argb, 0, 0.0F);
        }
    }

    /**
     * Icône vectorielle Réglages / Sous-paramètres (2 rails horizontaux + curseurs opposés).
     */
    public static void drawSlidersIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float w = size * 0.86F;
        float trackH = Math.max(1.3F, size * 0.115F);
        float yTop = cy - size * 0.22F;
        float yBot = cy + size * 0.22F;
        float knobR = Math.max(1.9F, size * 0.175F);

        drawRoundedRect(cx - w * 0.5F, yTop - trackH * 0.5F, w, trackH, trackH * 0.5F, argb, 0, 0.0F);
        drawCircle(cx + size * 0.16F, yTop, knobR, argb);

        drawRoundedRect(cx - w * 0.5F, yBot - trackH * 0.5F, w, trackH, trackH * 0.5F, argb, 0, 0.0F);
        drawCircle(cx - size * 0.16F, yBot, knobR, argb);
    }

    /**
     * Icône vectorielle Étoile 5 branches (pleine ou contour, anti-aliasée SDF).
     */
    public static void drawStarIcon(float cx, float cy, float size, boolean filled, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float rOut = size * 0.48F;
        float rIn = size * 0.21F;
        float prevX = 0.0F;
        float prevY = 0.0F;
        float firstX = 0.0F;
        float firstY = 0.0F;
        float stroke = filled ? 1.25F : 1.15F;

        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI * 0.5 + i * (Math.PI / 5.0);
            float rad = (i % 2 == 0) ? rOut : rIn;
            float px = cx + (float) Math.cos(ang) * rad;
            float py = cy + (float) Math.sin(ang) * rad;
            if (i == 0) {
                firstX = px;
                firstY = py;
            } else {
                drawLine(prevX, prevY, px, py, stroke, argb);
            }
            if (filled && (i % 2 == 0)) {
                drawLine(cx, cy, px, py, Math.max(1.4F, size * 0.145F), argb);
            }
            prevX = px;
            prevY = py;
        }
        drawLine(prevX, prevY, firstX, firstY, stroke, argb);
        if (filled) {
            drawCircle(cx, cy, rIn * 1.05F, argb);
        }
    }

    /**
     * Icône vectorielle Haut-parleur (Son activé / coupé).
     */
    public static void drawSoundIcon(float cx, float cy, float size, boolean enabled, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        // Corps du haut-parleur
        drawRoundedRect(cx - size * 0.42F, cy - size * 0.17F, size * 0.20F, size * 0.34F, 1.0F, argb, 0, 0.0F);
        for (int i = 0; i < 5; i++) {
            float t = i / 4.0F;
            float sx = (cx - size * 0.24F) + (size * 0.26F) * t;
            float hh = size * (0.17F + 0.22F * t);
            drawRoundedRect(sx - 0.9F, cy - hh, 1.8F, hh * 2.0F, 0.9F, argb, 0, 0.0F);
        }
        if (enabled) {
            drawRoundedRect(cx + size * 0.15F, cy - size * 0.18F, 1.4F, size * 0.36F, 0.7F, argb, 0, 0.0F);
            drawRoundedRect(cx + size * 0.30F, cy - size * 0.31F, 1.4F, size * 0.62F, 0.7F, argb, 0, 0.0F);
        } else {
            float xA = cx + size * 0.14F;
            float xB = cx + size * 0.39F;
            float yA = cy - size * 0.18F;
            float yB = cy + size * 0.18F;
            drawLine(xA, yA, xB, yB, 1.35F, argb);
            drawLine(xA, yB, xB, yA, 1.35F, argb);
        }
    }

    /**
     * Icône vectorielle Fermer / Quitter (croix géométrique nette).
     */
    public static void drawCloseIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float r = size * 0.27F;
        drawLine(cx - r, cy - r, cx + r, cy + r, 1.45F, argb);
        drawLine(cx - r, cy + r, cx + r, cy - r, 1.45F, argb);
    }

    /**
     * Icône vectorielle Trou Noir / Disque d'accrétion (pour le son ambiant du menu).
     */
    public static void drawVortexIcon(float cx, float cy, float size, boolean enabled, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        drawRing(cx, cy, size * 0.36F, 1.25F * sf, argb);
        drawCircle(cx, cy, size * 0.14F, argb);
        drawLine(cx - size * 0.48F, cy + size * 0.10F, cx + size * 0.48F, cy - size * 0.10F, 1.25F, argb);
        if (!enabled) {
            drawLine(cx - size * 0.40F, cy - size * 0.40F, cx + size * 0.40F, cy + size * 0.40F, 1.35F, 0xFFF87171);
        }
    }

    /**
     * Icône vectorielle Grille 2x2 (Modules / HUD).
     */
    public static void drawGridIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float b = size * 0.36F;
        float g = size * 0.08F;
        float sf = Math.max(1.0F, cachedScaleFactor);
        drawRoundedRect(cx - b - g, cy - b - g, b, b, 1.2F, 0, argb, 1.2F * sf);
        drawRoundedRect(cx + g, cy - b - g, b, b, 1.2F, 0, argb, 1.2F * sf);
        drawRoundedRect(cx - b - g, cy + g, b, b, 1.2F, 0, argb, 1.2F * sf);
        drawRoundedRect(cx + g, cy + g, b, b, 1.2F, 0, argb, 1.2F * sf);
    }

    /**
     * Icône vectorielle Joueur Solo.
     */
    public static void drawUserIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        drawRing(cx, cy - size * 0.19F, size * 0.18F, 1.25F * sf, argb);
        drawRoundedRect(cx - size * 0.32F, cy + size * 0.08F, size * 0.64F, size * 0.28F, size * 0.14F, 0, argb, 1.25F * sf);
    }

    /**
     * Icône vectorielle Réticule (PvP).
     */
    public static void drawCrosshairIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        float r = size * 0.34F;
        drawRing(cx, cy, r, 1.2F * sf, argb);
        drawLine(cx - size * 0.48F, cy, cx - size * 0.20F, cy, 1.2F, argb);
        drawLine(cx + size * 0.20F, cy, cx + size * 0.48F, cy, 1.2F, argb);
        drawLine(cx, cy - size * 0.48F, cx, cy - size * 0.20F, 1.2F, argb);
        drawLine(cx, cy + size * 0.20F, cx, cy + size * 0.48F, 1.2F, argb);
    }

    /**
     * Icône vectorielle Œil (Visuel).
     */
    public static void drawEyeIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        drawRoundedRect(cx - size * 0.44F, cy - size * 0.24F, size * 0.88F, size * 0.48F, size * 0.24F, 0, argb, 1.2F * sf);
        drawCircle(cx, cy, size * 0.13F, argb);
    }

    /**
     * Icône vectorielle Éclair (Hypixel / Réactivité).
     */
    public static void drawBoltIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        drawLine(cx + size * 0.08F, cy - size * 0.44F, cx - size * 0.22F, cy + size * 0.02F, 1.4F, argb);
        drawLine(cx - size * 0.22F, cy + size * 0.02F, cx + size * 0.16F, cy + size * 0.02F, 1.4F, argb);
        drawLine(cx + size * 0.16F, cy + size * 0.02F, cx - size * 0.08F, cy + size * 0.44F, 1.4F, argb);
    }

    /**
     * Icône vectorielle Globe (Monde).
     */
    public static void drawGlobeIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        float r = size * 0.40F;
        drawRing(cx, cy, r, 1.2F * sf, argb);
        drawLine(cx - r * 0.92F, cy, cx + r * 0.92F, cy, 1.05F, argb);
        drawLine(cx, cy - r * 0.92F, cx, cy + r * 0.92F, 1.05F, argb);
    }

    /**
     * Icône vectorielle Puce GPU/CPU (Performance).
     */
    public static void drawCpuIcon(float cx, float cy, float size, int argb) {
        if (((argb >>> 24) & 0xFF) == 0 || size <= 0.0F) {
            return;
        }
        float sf = Math.max(1.0F, cachedScaleFactor);
        float s = size * 0.62F;
        drawRoundedRect(cx - s * 0.5F, cy - s * 0.5F, s, s, 2.0F, 0, argb, 1.2F * sf);
        float inner = size * 0.24F;
        drawRoundedRect(cx - inner * 0.5F, cy - inner * 0.5F, inner, inner, 1.0F, argb, 0, 0.0F);
        float p = size * 0.46F;
        drawLine(cx - size * 0.15F, cy - p, cx - size * 0.15F, cy - s * 0.5F, 1.1F, argb);
        drawLine(cx + size * 0.15F, cy - p, cx + size * 0.15F, cy - s * 0.5F, 1.1F, argb);
        drawLine(cx - size * 0.15F, cy + s * 0.5F, cx - size * 0.15F, cy + p, 1.1F, argb);
        drawLine(cx + size * 0.15F, cy + s * 0.5F, cx + size * 0.15F, cy + p, 1.1F, argb);
    }

    /**
     * Dessine l'icône vectorielle correspondant à une catégorie MashCategory.
     */
    public static void drawCategoryIcon(dev.purefps.module.MashCategory cat, float cx, float cy, float size, int argb) {
        if (cat == null) {
            drawGridIcon(cx, cy, size, argb);
            return;
        }
        switch (cat) {
            case HUD:
                drawGridIcon(cx, cy, size, argb);
                break;
            case PVP:
                drawCrosshairIcon(cx, cy, size, argb);
                break;
            case VISUAL:
                drawEyeIcon(cx, cy, size, argb);
                break;
            case HYPIXEL:
                drawBoltIcon(cx, cy, size, argb);
                break;
            case WORLD:
                drawGlobeIcon(cx, cy, size, argb);
                break;
            case ENGINE:
                drawCpuIcon(cx, cy, size, argb);
                break;
            default:
                drawGridIcon(cx, cy, size, argb);
                break;
        }
    }

    private static int scaleAlpha(int argb, float factor) {
        if (factor >= 0.995F) {
            return argb;
        }
        int a = (argb >>> 24) & 0xFF;
        int na = Math.max(0, Math.min(255, Math.round(a * factor)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    private static void appendQuad(
            float x0, float y0, float x1, float y1,
            float halfQuadWpx, float halfQuadHpx,
            float halfBoxWpx, float halfBoxHpx,
            float radiusPx, float borderPx, float softnessPx,
            int fillTopArgb, int fillBotArgb, int borderArgb
    ) {
        if (batchedVertices + 6 > MAX_VERTS_PER_FLUSH) {
            flushCurrentBatch();
        }
        ByteBuffer buf = getBuffer();
        int topAbgr = argbToLittleEndianRgba(fillTopArgb);
        int botAbgr = argbToLittleEndianRgba(fillBotArgb);
        int brdAbgr = argbToLittleEndianRgba(borderArgb);

        // Triangle 1 : (x0, y1), (x1, y1), (x1, y0)
        putVertex(buf, x0, y1, -halfQuadWpx, halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x1, y1, halfQuadWpx, halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x1, y0, halfQuadWpx, -halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);

        // Triangle 2 : (x1, y0), (x0, y0), (x0, y1)
        putVertex(buf, x1, y0, halfQuadWpx, -halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x0, y0, -halfQuadWpx, -halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        putVertex(buf, x0, y1, -halfQuadWpx, halfQuadHpx, halfBoxWpx, halfBoxHpx, radiusPx, borderPx, softnessPx, topAbgr, botAbgr, brdAbgr);
        batchedVertices += 6;
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
