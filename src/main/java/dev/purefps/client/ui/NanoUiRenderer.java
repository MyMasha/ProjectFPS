package dev.purefps.client.ui;

import dev.purefps.client.GlUiBatchRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * Moteur UI vectoriel anti-aliasé par shader GLSL 120 (Signed Distance Field en pixels physiques) :
 * - Supporte désormais un mode beginBatch() / endBatch() pour maintenir GL20.glUseProgram(programId) actif
 *   pendant tout le tracé des cartes du HUD au lieu d'effectuer 12 à 44 commutations de programme shader par frame !
 */
public final class NanoUiRenderer {

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n"
            + "varying vec2 vLocalPos;\n"
            + "void main() {\n"
            + "    vLocalPos = gl_MultiTexCoord0.xy;\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER_SRC =
            "#version 120\n"
            + "varying vec2 vLocalPos;\n"
            + "uniform vec2 uCenter;\n"
            + "uniform vec2 uHalfSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uBorderPx;\n"
            + "uniform float uSoftness;\n"
            + "uniform vec4 uFillTop;\n"
            + "uniform vec4 uFillBottom;\n"
            + "uniform vec4 uBorderColor;\n"
            + "\n"
            + "float sdRoundBox(vec2 p, vec2 b, float r) {\n"
            + "    vec2 q = abs(p) - b + vec2(r);\n"
            + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 p = vLocalPos - uCenter;\n"
            + "    float r = min(uRadius, min(uHalfSize.x, uHalfSize.y));\n"
            + "    float d = sdRoundBox(p, uHalfSize, r);\n"
            + "    float gradT = clamp((p.y + uHalfSize.y) / max(uHalfSize.y * 2.0, 1.0), 0.0, 1.0);\n"
            + "    vec4 fillCol = mix(uFillTop, uFillBottom, gradT);\n"
            + "\n"
            + "    if (uSoftness > 1.5) {\n"
            + "        float s = 1.0 - smoothstep(-2.0, uSoftness, d);\n"
            + "        gl_FragColor = vec4(fillCol.rgb, fillCol.a * s * s);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    float outerMask = 1.0 - smoothstep(-0.65, 0.65, d);\n"
            + "    if (outerMask <= 0.002) {\n"
            + "        discard;\n"
            + "    }\n"
            + "\n"
            + "    vec4 col = fillCol;\n"
            + "    if (uBorderPx > 0.05 && uBorderColor.a > 0.002) {\n"
            + "        float innerMask = 1.0 - smoothstep(-0.65, 0.65, d + uBorderPx);\n"
            + "        float bf = clamp(outerMask - innerMask, 0.0, 1.0);\n"
            + "        vec3 mixedRgb = mix(fillCol.rgb, uBorderColor.rgb, bf);\n"
            + "        float mixedA = mix(fillCol.a, max(fillCol.a, uBorderColor.a), bf);\n"
            + "        col = vec4(mixedRgb, mixedA);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(col.rgb, col.a * outerMask);\n"
            + "}\n";

    private static int shaderState = 0; // 0 = non initialisé, 1 = prêt, -1 = fallback
    private static int programId = 0;
    private static int locCenter = -1;
    private static int locHalfSize = -1;
    private static int locRadius = -1;
    private static int locBorderPx = -1;
    private static int locSoftness = -1;
    private static int locFillTop = -1;
    private static int locFillBottom = -1;
    private static int locBorderColor = -1;

    private static float cachedScaleFactor = 2.0F;
    private static boolean batchShaderActive = false;

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

    public static void beginBatch() {
        if (batchShaderActive || !ensureShader()) {
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL20.glUseProgram(programId);
        batchShaderActive = true;
    }

    public static void endBatch() {
        if (!batchShaderActive) {
            return;
        }
        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        batchShaderActive = false;
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
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(prog);
                shaderState = -1;
                return false;
            }

            programId = prog;
            locCenter = GL20.glGetUniformLocation(prog, "uCenter");
            locHalfSize = GL20.glGetUniformLocation(prog, "uHalfSize");
            locRadius = GL20.glGetUniformLocation(prog, "uRadius");
            locBorderPx = GL20.glGetUniformLocation(prog, "uBorderPx");
            locSoftness = GL20.glGetUniformLocation(prog, "uSoftness");
            locFillTop = GL20.glGetUniformLocation(prog, "uFillTop");
            locFillBottom = GL20.glGetUniformLocation(prog, "uFillBottom");
            locBorderColor = GL20.glGetUniformLocation(prog, "uBorderColor");
            shaderState = 1;
            return true;
        } catch (Throwable t) {
            shaderState = -1;
            return false;
        }
    }

    public static void drawShadow(float x, float y, float w, float h, float radiusGui, float spreadGui, int shadowArgb) {
        if (!ensureShader() || ((shadowArgb >>> 24) & 0xFF) == 0) {
            return;
        }
        float sf = cachedScaleFactor;
        float padGui = spreadGui + 2.0F;
        float qx0 = x - padGui;
        float qy0 = y - padGui + 1.5F;
        float qx1 = x + w + padGui;
        float qy1 = y + h + padGui + 1.5F;

        float totalWpx = (qx1 - qx0) * sf;
        float totalHpx = (qy1 - qy0) * sf;

        if (!batchShaderActive) {
            GlStateManager.enableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL20.glUseProgram(programId);
        }

        GL20.glUniform2f(locCenter, totalWpx * 0.5F, totalHpx * 0.5F);
        GL20.glUniform2f(locHalfSize, w * sf * 0.5F, h * sf * 0.5F);
        GL20.glUniform1f(locRadius, radiusGui * sf);
        GL20.glUniform1f(locBorderPx, 0.0F);
        GL20.glUniform1f(locSoftness, Math.max(2.0F, spreadGui * sf));
        setUniformColor(locFillTop, shadowArgb);
        setUniformColor(locFillBottom, shadowArgb);
        setUniformColor(locBorderColor, 0);

        emitScreenQuad(qx0, qy0, qx1, qy1, totalWpx, totalHpx);

        if (!batchShaderActive) {
            GL20.glUseProgram(0);
            GlStateManager.enableAlpha();
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

        float totalWpx = (qx1 - qx0) * sf;
        float totalHpx = (qy1 - qy0) * sf;

        if (!batchShaderActive) {
            GlStateManager.enableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL20.glUseProgram(programId);
        }

        GL20.glUniform2f(locCenter, totalWpx * 0.5F, totalHpx * 0.5F);
        GL20.glUniform2f(locHalfSize, w * sf * 0.5F, h * sf * 0.5F);
        GL20.glUniform1f(locRadius, radiusGui * sf);
        GL20.glUniform1f(locBorderPx, borderPhysPx);
        GL20.glUniform1f(locSoftness, 1.0F);
        setUniformColor(locFillTop, fillTopArgb);
        setUniformColor(locFillBottom, fillBottomArgb);
        setUniformColor(locBorderColor, borderArgb);

        emitScreenQuad(qx0, qy0, qx1, qy1, totalWpx, totalHpx);

        if (!batchShaderActive) {
            GL20.glUseProgram(0);
            GlStateManager.enableAlpha();
        }
    }

    public static void drawCircle(float cx, float cy, float radiusGui, int fillArgb) {
        float d = radiusGui * 2.0F;
        drawRoundedRect(cx - radiusGui, cy - radiusGui, d, d, radiusGui, fillArgb, 0, 0.0F);
    }

    public static void drawToggleSwitch(float x, float y, boolean active, boolean hovered) {
        float w = 26.0F;
        float h = 14.0F;
        int trackTop;
        int trackBot;
        int border;
        if (active) {
            trackTop = hovered ? 0xFF6E7AE8 : 0xFF5E6AD2;
            trackBot = hovered ? 0xFF5A65D0 : 0xFF4C57BD;
            border = 0x55FFFFFF;
        } else {
            trackTop = hovered ? 0xFF252B3D : 0xFF1C2130;
            trackBot = hovered ? 0xFF1E2434 : 0xFF171B27;
            border = 0x24FFFFFF;
        }
        drawRoundedGradient(x, y, w, h, 7.0F, trackTop, trackBot, border, 1.0F);

        float knobR = 5.0F;
        float knobCx = active ? (x + w - 7.0F) : (x + 7.0F);
        float knobCy = y + h * 0.5F;
        drawCircle(knobCx, knobCy, knobR, active ? 0xFFFFFFFF : 0xFF949CB0);
    }

    private static void setUniformColor(int location, int argb) {
        if (location < 0) {
            return;
        }
        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        GL20.glUniform4f(location, r, g, b, a);
    }

    private static void emitScreenQuad(float x0, float y0, float x1, float y1, float uMax, float vMax) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x0, y1, 0.0D).tex(0.0D, vMax).endVertex();
        wr.pos(x1, y1, 0.0D).tex(uMax, vMax).endVertex();
        wr.pos(x1, y0, 0.0D).tex(uMax, 0.0D).endVertex();
        wr.pos(x0, y0, 0.0D).tex(0.0D, 0.0D).endVertex();
        tess.draw();
    }
}
