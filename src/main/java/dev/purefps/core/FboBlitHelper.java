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
 * Présentation matérielle Opaque (Alpha = 1.0 garanti) de framebufferMc vers le Backbuffer 0 :
 * - Sous Vulkan (Mesa3D Zink / Kopper Win32 WSI) et DirectComposition DWM, VkSwapchainKHR utilise
 *   VK_FORMAT_B8G8R8A8_UNORM : tout pixel du Backbuffer 0 ayant Alpha < 1.0 laisse transparaître
 *   les fenêtres situées derrière Minecraft (navigateur, Discord, bureau) !
 * - Dessine un unique triangle plein écran (3 sommets, zéro couture diagonale, zéro read-modify-write)
 *   forçant gl_FragColor = vec4(rgb, 1.0) avec colorMask(true, true, true, true) et GL_BLEND désactivé.
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
    private static float cachedUScale = -1.0F;
    private static float cachedVScale = -1.0F;

    private FboBlitHelper() {
    }

    public static boolean canHardwareBlit() {
        if (gl30State == 0) {
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                gl30State = (caps != null && caps.OpenGL30) ? 1 : -1;
            } catch (Throwable t) {
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
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

            opaqueProgram = prog;
            fullscreenTriVbo = vbo;
            cachedUScale = -1.0F;
            cachedVScale = -1.0F;
            opaqueShaderState = 1;
            return true;
        } catch (Throwable t) {
            opaqueShaderState = -1;
            return false;
        }
    }

    /**
     * Copie framebufferTexture vers le Backbuffer 0 en forçant matériellement Alpha = 1.0 sur 100 % des pixels.
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
            // Repli défensif : scelle d'abord le canal Alpha du Backbuffer 0 à 1.0 afin que le quad vanilla
            // (qui dessine avec colorMask(true, true, true, false)) conserve une fenêtre 100 % opaque.
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

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, fullscreenTriVbo);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, 0, 0L);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        GL20.glUseProgram(0);
        GlStateManager.bindTexture(0);
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.colorMask(true, true, true, true);
        return true;
    }

    public static void blitToBackbuffer(int fboId, int srcW, int srcH, int dstW, int dstH) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        int filter = (srcW == dstW && srcH == dstH) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH, GL11.GL_COLOR_BUFFER_BIT, filter);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
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
