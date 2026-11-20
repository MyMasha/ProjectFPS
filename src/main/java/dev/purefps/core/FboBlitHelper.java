package dev.purefps.core;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.FloatBuffer;

/**
 * Présentation matérielle haute-fréquence de framebufferMc vers le Backbuffer 0 :
 * - Sur OpenGL natif : utilise GL30.glBlitFramebuffer (copie ROP/DMA matérielle directe 1:1 en GL_NEAREST,
 *   zéro shader, zéro sampler, zéro transition GL_ARRAY_BUFFER).
 * - Sur Vulkan (Mesa3D Zink / Kopper Win32 WSI) : utilise un triangle plein écran via VAO GL30 pré-compilé
 *   forçant Alpha = 1.0 afin que DirectComposition/Kopper (VK_FORMAT_B8G8R8A8_UNORM) reste 100 % opaque.
 */
public final class FboBlitHelper {

    private static final String OPAQUE_VS_SRC =
            "#version 120\n"
            + "uniform vec2 uUvScale;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "    vUv = (gl_Vertex.xy * 0.5 + 0.5) * uUvScale;\n"
            + "}\n";

    private static final String OPAQUE_FS_SRC =
            "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);\n"
            + "}\n";

    private static int gl30State = 0;         // 0 = inconnu, 1 = supporté, -1 = non supporté
    private static int opaqueShaderState = 0; // 0 = inconnu, 1 = prêt, -1 = indisponible
    private static int opaqueProgram = 0;
    private static int uUvScaleLoc = -1;
    private static int fullscreenTriVbo = -1;
    private static int fullscreenTriVao = -1;
    private static float cachedUScale = -1.0F;
    private static float cachedVScale = -1.0F;

    private FboBlitHelper() {
    }

    public static boolean canHardwareBlit() {
        if (gl30State == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                gl30State = (caps != null && caps.OpenGL30) ? 1 : -1;
            } catch (Exception | LinkageError t) {
                gl30State = -1;
            }
        }
        return gl30State == 1;
    }

    private static boolean ensureOpaqueShaderReady() {
        if (opaqueShaderState != 0) {
            return opaqueShaderState == 1;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null || !caps.OpenGL20 || !OpenGlHelper.vboSupported) {
                opaqueShaderState = -1;
                return false;
            }

            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, OPAQUE_VS_SRC);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteShader(vs);
                opaqueShaderState = -1;
                return false;
            }

            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, OPAQUE_FS_SRC);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteShader(vs);
                GL20.glDeleteShader(fs);
                opaqueShaderState = -1;
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
                opaqueShaderState = -1;
                return false;
            }

            int texLoc = GL20.glGetUniformLocation(prog, "uTex");
            uUvScaleLoc = GL20.glGetUniformLocation(prog, "uUvScale");
            GL20.glUseProgram(prog);
            if (texLoc >= 0) {
                GL20.glUniform1i(texLoc, 0);
            }
            GL20.glUseProgram(0);

            FloatBuffer triBuf = BufferUtils.createFloatBuffer(6);
            triBuf.put(-1.0F).put(-1.0F);
            triBuf.put(3.0F).put(-1.0F);
            triBuf.put(-1.0F).put(3.0F);
            triBuf.flip();

            int vbo = OpenGlHelper.glGenBuffers();
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(OpenGlHelper.GL_ARRAY_BUFFER, triBuf, GL15.GL_STATIC_DRAW);

            int vao = -1;
            if (caps.OpenGL30) {
                vao = GL30.glGenVertexArrays();
                if (vao > 0) {
                    GL30.glBindVertexArray(vao);
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, vbo);
                    GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                    GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);
                    GL30.glBindVertexArray(0);
                }
            }
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

            opaqueProgram = prog;
            fullscreenTriVbo = vbo;
            fullscreenTriVao = vao;
            cachedUScale = -1.0F;
            cachedVScale = -1.0F;
            opaqueShaderState = 1;
            return true;
        } catch (Exception | LinkageError t) {
            opaqueShaderState = -1;
            return false;
        }
    }

    /**
     * Copie framebufferTexture vers le Backbuffer 0 en forçant matériellement Alpha = 1.0 sur 100 % des pixels (Vulkan/Zink).
     */
    public static boolean blitOpaqueFullscreen(
            int fboTexId,
            int srcW,
            int srcH,
            int texW,
            int texH,
            int dstW,
            int dstH
    ) {
        if (fboTexId <= 0 || srcW <= 0 || srcH <= 0 || texW <= 0 || texH <= 0 || dstW <= 0 || dstH <= 0) {
            return false;
        }
        if (!ensureOpaqueShaderReady()) {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
            GlStateManager.colorMask(false, false, false, true);
            GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
            GlStateManager.colorMask(true, true, true, true);
            return false;
        }

        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        GlStateManager.viewport(0, 0, dstW, dstH);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.colorMask(true, true, true, true);

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(fboTexId);

        float uScale = (float) srcW / (float) texW;
        float vScale = (float) srcH / (float) texH;

        GL20.glUseProgram(opaqueProgram);
        if (uUvScaleLoc >= 0 && (uScale != cachedUScale || vScale != cachedVScale)) {
            GL20.glUniform2f(uUvScaleLoc, uScale, vScale);
            cachedUScale = uScale;
            cachedVScale = vScale;
        }

        if (fullscreenTriVao > 0) {
            GL30.glBindVertexArray(fullscreenTriVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL30.glBindVertexArray(0);
        } else {
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, fullscreenTriVbo);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        }

        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        return true;
    }

    /**
     * Copie matérielle directe GL30.glBlitFramebuffer vers Backbuffer 0 (chemin ultra-rapide OpenGL natif).
     */
    public static boolean blitToBackbuffer(int fboId, int srcW, int srcH, int dstW, int dstH) {
        if (fboId < 0 || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || !canHardwareBlit()) {
            return false;
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.viewport(0, 0, dstW, dstH);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        int filter = (srcW == dstW && srcH == dstH) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH, GL11.GL_COLOR_BUFFER_BIT, filter);
        GlStateCache.invalidateFramebuffer();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        return true;
    }

    /**
     * Test P-Vertex mathématiquement exact équivalent au test des 8 sommets de ClippingHelper.isBoxInFrustum,
     * en 1 produit scalaire par plan au lieu de 8 (soit jusqu'à 8x moins de multiplications).
     */
    public static boolean isBoxInFrustumPVertex(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double f0x, double f0y, double f0z, double f0w,
            double f1x, double f1y, double f1z, double f1w,
            double f2x, double f2y, double f2z, double f2w,
            double f3x, double f3y, double f3z, double f3w,
            double f4x, double f4y, double f4z, double f4w,
            double f5x, double f5y, double f5z, double f5w
    ) {
        if (f0x * (f0x >= 0.0D ? maxX : minX) + f0y * (f0y >= 0.0D ? maxY : minY) + f0z * (f0z >= 0.0D ? maxZ : minZ) + f0w <= 0.0D) {
            return false;
        }
        if (f1x * (f1x >= 0.0D ? maxX : minX) + f1y * (f1y >= 0.0D ? maxY : minY) + f1z * (f1z >= 0.0D ? maxZ : minZ) + f1w <= 0.0D) {
            return false;
        }
        if (f2x * (f2x >= 0.0D ? maxX : minX) + f2y * (f2y >= 0.0D ? maxY : minY) + f2z * (f2z >= 0.0D ? maxZ : minZ) + f2w <= 0.0D) {
            return false;
        }
        if (f3x * (f3x >= 0.0D ? maxX : minX) + f3y * (f3y >= 0.0D ? maxY : minY) + f3z * (f3z >= 0.0D ? maxZ : minZ) + f3w <= 0.0D) {
            return false;
        }
        if (f4x * (f4x >= 0.0D ? maxX : minX) + f4y * (f4y >= 0.0D ? maxY : minY) + f4z * (f4z >= 0.0D ? maxZ : minZ) + f4w <= 0.0D) {
            return false;
        }
        return f5x * (f5x >= 0.0D ? maxX : minX) + f5y * (f5y >= 0.0D ? maxY : minY) + f5z * (f5z >= 0.0D ? maxZ : minZ) + f5w > 0.0D;
    }
}
