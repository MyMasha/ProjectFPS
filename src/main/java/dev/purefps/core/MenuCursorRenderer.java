package dev.purefps.core;

import dev.purefps.gui.GuiPureMainMenu;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Rendu matériel OpenGL haute-performance pour le pointeur personnalisé optionnel dans les menus :
 * - Zéro glGetInteger, zéro glPushAttrib/glPopAttrib, zéro glBegin/glEnd (compatibilité parfaite mesa_glthread=true).
 * - Zéro appel JNA lorsque la fenêtre a le focus (lecture directe Mouse.getX()/getY()).
 * - Texture scindée en 2 couches statiques (ombre+contour pré-cuite + masque blanc teinté sur GPU via GlStateManager.color)
 *   -> 0 rastérisation SDF CPU et 0 glTexImage2D par frame même en mode Chroma !
 */
public final class MenuCursorRenderer {

    private static final int TEX_SIZE = 64;
    private static final float ARROW_HOTSPOT_X = 8.0F;
    private static final float ARROW_HOTSPOT_Y = 8.0F;
    private static final float CROSS_HOTSPOT_X = 32.0F;
    private static final float CROSS_HOTSPOT_Y = 32.0F;

    private static int baseTexId = 0;   // Ombre portée + contour
    private static int fillTexId = 0;   // Masque de remplissage blanc (teinté via GlStateManager.color)
    private static int builtStyle = -1;
    private static int builtOutlineArgb = 0;
    private static boolean builtShadow = true;

    private static int quadVboId = -1;
    private static ByteBuffer quadBuf = null;

    private static final float[] CURSOR_POS_XY = new float[2];
    private static ByteBuffer uploadBuffer = null;
    private static float clickPulseAnim = 0.0F;
    private static long lastFrameNs = 0L;

    private MenuCursorRenderer() {
    }

    /**
     * Appelé juste avant Display.update() dans Minecraft.updateDisplay() :
     * dessine le curseur optionnel directement dans le backbuffer natif (FBO 0) sans jamais bloquer le pipeline GL.
     */
    public static void onBeforeSwapBuffers(Minecraft mc) {
        if (mc == null) {
            return;
        }
        boolean inMenu = mc.currentScreen != null
                && (!Mouse.isCreated() || !Mouse.isGrabbed());
        if (!inMenu) {
            return;
        }

        // Garde le rideau #000000 100 % pur uniquement sur les 2 frames de pré-chauffage depuis le Launcher
        if (SeamlessLaunchTransition.isActive()
                && mc.currentScreen instanceof GuiPureMainMenu
                && !BlackHoleEmergenceRenderer.hasPresentedInitialBlackFrames()) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(true);
            return;
        }

        if (!isCustomPointerModuleActive()) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            return;
        }

        int dispW = Math.max(1, mc.displayWidth);
        int dispH = Math.max(1, mc.displayHeight);
        boolean windowFocused = BorderlessWindowHelper.isCachedDisplayActive();

        float px;
        float py;
        if (windowFocused && Mouse.isCreated()) {
            px = (float) Mouse.getX();
            py = (float) (dispH - 1 - Mouse.getY());
        } else {
            int cursorState = BorderlessWindowHelper.queryClientCursorState(mc, CURSOR_POS_XY);
            if (cursorState < 2) {
                BorderlessWindowHelper.setClientHardwareCursorBlank(false);
                return;
            }
            px = CURSOR_POS_XY[0];
            py = CURSOR_POS_XY[1];
        }

        boolean insideClient = px >= 0.0F && py >= 0.0F && px < (float) dispW && py < (float) dispH;
        if (!insideClient) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            return;
        }

        BorderlessWindowHelper.setClientHardwareCursorBlank(true);

        MashModules.ensureRegistered();
        int style = clampInt(MashFlags.menuCursorStyle, 0, 3);
        int fillArgb = resolveFillArgb(style);
        int outlineArgb = resolveOutlineArgb(style);
        boolean shadow = MashFlags.menuCursorShadow;

        ensureCursorLayers(style, outlineArgb, shadow);
        if (baseTexId == 0 || fillTexId == 0) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            return;
        }

        long nowNs = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0.008F : (float) ((nowNs - lastFrameNs) * 1.0E-9D);
        lastFrameNs = nowNs;
        if (dt < 0.0F || dt > 0.1F) {
            dt = 0.008F;
        }
        boolean leftDown = Mouse.isCreated() && Mouse.isButtonDown(0);
        float pulseTarget = (MashFlags.menuCursorClickPulse && leftDown) ? 1.0F : 0.0F;
        clickPulseAnim += (pulseTarget - clickPulseAnim) * Math.min(1.0F, dt * 22.0F);

        float dpiScale = Math.max(0.85F, Math.min(2.25F, (float) dispH / 1080.0F));
        float userScale = Math.max(0.65F, Math.min(2.20F, MashFlags.menuCursorScale));
        float pressShrink = 1.0F - 0.06F * clickPulseAnim;
        float totalScale = dpiScale * userScale * pressShrink;

        float hotX = (style == 3 ? CROSS_HOTSPOT_X : ARROW_HOTSPOT_X) * totalScale;
        float hotY = (style == 3 ? CROSS_HOTSPOT_Y : ARROW_HOTSPOT_Y) * totalScale;
        float quadSize = (float) TEX_SIZE * totalScale;

        float x0 = px - hotX;
        float y0 = py - hotY;
        float x1 = x0 + quadSize;
        float y1 = y0 + quadSize;

        drawCursorQuadFast(dispW, dispH, x0, y0, x1, y1, fillArgb);
    }

    public static boolean isCustomPointerModuleActive() {
        return dev.purefps.config.PureFPSConfig.enabled && MashFlags.menuCursorEnabled;
    }

    private static int resolveFillArgb(int style) {
        if (MashFlags.menuCursorChroma) {
            long now = System.currentTimeMillis();
            int hueRgb = java.awt.Color.HSBtoRGB((now % 3200L) / 3200.0F, 0.78F, 1.0F) & 0x00FFFFFF;
            return 0xFF000000 | hueRgb;
        }
        if (style == 1) {
            return (MashFlags.menuCursorColor & 0x00FFFFFF) == 0x00FFFFFF
                    ? 0xFF0E121B
                    : (0xFF000000 | (MashFlags.menuCursorColor & 0x00FFFFFF));
        }
        return 0xFF000000 | (MashFlags.menuCursorColor & 0x00FFFFFF);
    }

    private static int resolveOutlineArgb(int style) {
        if (style == 1 && (MashFlags.menuCursorOutlineColor & 0x00FFFFFF) == 0x000A0D14) {
            return 0xFFF5B85C;
        }
        return 0xFF000000 | (MashFlags.menuCursorOutlineColor & 0x00FFFFFF);
    }

    private static void drawCursorQuadFast(
            int dispW,
            int dispH,
            float x0,
            float y0,
            float x1,
            float y1,
            int fillArgb
    ) {
        try {
            GlStateManager.viewport(0, 0, dispW, dispH);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0D, (double) dispW, (double) dispH, 0.0D, -1.0D, 1.0D);

            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            GlStateManager.loadIdentity();

            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableAlpha();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.disableFog();
            GlStateManager.colorMask(true, true, true, false);
            GlStateManager.enableBlend();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

            if (quadBuf == null) {
                // 6 sommets * 16 octets (XY + UV) = 96 octets
                quadBuf = ByteBuffer.allocateDirect(6 * 16).order(ByteOrder.nativeOrder());
            }
            quadBuf.clear();
            putV(quadBuf, x0, y0, 0.0F, 0.0F);
            putV(quadBuf, x0, y1, 0.0F, 1.0F);
            putV(quadBuf, x1, y1, 1.0F, 1.0F);
            putV(quadBuf, x1, y1, 1.0F, 1.0F);
            putV(quadBuf, x1, y0, 1.0F, 0.0F);
            putV(quadBuf, x0, y0, 0.0F, 0.0F);
            quadBuf.flip();

            boolean useVbo = OpenGlHelper.vboSupported;
            if (useVbo) {
                if (quadVboId <= 0) {
                    quadVboId = OpenGlHelper.glGenBuffers();
                }
                if (quadVboId > 0) {
                    OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, quadVboId);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadBuf, GL15.GL_STREAM_DRAW);
                    GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                    GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                    GL11.glVertexPointer(2, GL11.GL_FLOAT, 16, 0L);
                    GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 16, 8L);
                } else {
                    useVbo = false;
                }
            }
            if (!useVbo) {
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                quadBuf.position(0);
                GL11.glVertexPointer(2, GL11.GL_FLOAT, 16, quadBuf);
                quadBuf.position(8);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 16, quadBuf);
            }

            // Couche 1 : Ombre + Contour (alpha prémultiplié -> GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(baseTexId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            // Couche 2 : Remplissage teinté matériellement (0 upload CPU/GPU même en mode Chroma !)
            float fillR = ((fillArgb >> 16) & 0xFF) * (1.0F / 255.0F);
            float fillG = ((fillArgb >> 8) & 0xFF) * (1.0F / 255.0F);
            float fillB = (fillArgb & 0xFF) * (1.0F / 255.0F);
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(fillR, fillG, fillB, 1.0F);
            GlStateManager.bindTexture(fillTexId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            if (useVbo) {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            }

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        } catch (Exception | LinkageError ignored) {
        }
    }

    private static void putV(ByteBuffer buf, float x, float y, float u, float v) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(u);
        buf.putFloat(v);
    }

    private static void ensureCursorLayers(int style, int outlineArgb, boolean shadow) {
        if (baseTexId != 0
                && fillTexId != 0
                && style == builtStyle
                && outlineArgb == builtOutlineArgb
                && shadow == builtShadow) {
            return;
        }

        byte[] baseRgba = new byte[TEX_SIZE * TEX_SIZE * 4];
        byte[] fillRgba = new byte[TEX_SIZE * TEX_SIZE * 4];
        rasterizeCursorLayersRgba(style, outlineArgb, shadow, baseRgba, fillRgba);

        if (uploadBuffer == null || uploadBuffer.capacity() < baseRgba.length) {
            uploadBuffer = BufferUtils.createByteBuffer(baseRgba.length);
        }

        if (baseTexId == 0) {
            baseTexId = GL11.glGenTextures();
        }
        if (fillTexId == 0) {
            fillTexId = GL11.glGenTextures();
        }
        if (baseTexId == 0 || fillTexId == 0) {
            return;
        }

        uploadTextureRgba(baseTexId, baseRgba);
        uploadTextureRgba(fillTexId, fillRgba);

        builtStyle = style;
        builtOutlineArgb = outlineArgb;
        builtShadow = shadow;
    }

    private static void uploadTextureRgba(int texId, byte[] rgba) {
        uploadBuffer.clear();
        uploadBuffer.put(rgba);
        uploadBuffer.flip();
        GlStateManager.bindTexture(texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                TEX_SIZE,
                TEX_SIZE,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                uploadBuffer
        );
    }

    private static void rasterizeCursorLayersRgba(int style, int outlineArgb, boolean shadow, byte[] baseOut, byte[] fillOut) {
        float outR = ((outlineArgb >> 16) & 0xFF) / 255.0F;
        float outG = ((outlineArgb >> 8) & 0xFF) / 255.0F;
        float outB = (outlineArgb & 0xFF) / 255.0F;

        float hotX = (style == 3) ? CROSS_HOTSPOT_X : ARROW_HOTSPOT_X;
        float hotY = (style == 3) ? CROSS_HOTSPOT_Y : ARROW_HOTSPOT_Y;
        float outlineWidth = (style == 3) ? 1.10F : 1.30F;

        for (int py = 0; py < TEX_SIZE; py++) {
            for (int px = 0; px < TEX_SIZE; px++) {
                float accumPr = 0.0F;
                float accumPg = 0.0F;
                float accumPb = 0.0F;
                float accumA = 0.0F;
                float accumFillA = 0.0F;

                for (int sy = 0; sy < 4; sy++) {
                    float y = ((float) py + (sy + 0.5F) * 0.25F) - hotY;
                    for (int sx = 0; sx < 4; sx++) {
                        float x = ((float) px + (sx + 0.5F) * 0.25F) - hotX;

                        float pr = 0.0F;
                        float pg = 0.0F;
                        float pb = 0.0F;
                        float pa = 0.0F;

                        if (shadow) {
                            float sdSh = evaluateCursorSdf(x - 1.15F, y - 1.65F, style) - outlineWidth;
                            if (sdSh < 2.8F) {
                                pa = 0.34F * (float) Math.exp(-Math.max(0.0F, sdSh) * 1.15F);
                            }
                        }

                        float sd = evaluateCursorSdf(x, y, style);
                        float borderCov = clamp01((outlineWidth + 0.32F - sd) / 0.64F);
                        if (borderCov > 0.0F) {
                            pr = outR * borderCov + pr * (1.0F - borderCov);
                            pg = outG * borderCov + pg * (1.0F - borderCov);
                            pb = outB * borderCov + pb * (1.0F - borderCov);
                            pa = borderCov + pa * (1.0F - borderCov);
                        }

                        float fillCov = clamp01((0.28F - sd) / 0.56F);
                        accumFillA += fillCov;

                        accumPr += pr;
                        accumPg += pg;
                        accumPb += pb;
                        accumA += pa;
                    }
                }

                int base = (py * TEX_SIZE + px) * 4;
                baseOut[base] = (byte) clampInt(Math.round((accumPr / 16.0F) * 255.0F), 0, 255);
                baseOut[base + 1] = (byte) clampInt(Math.round((accumPg / 16.0F) * 255.0F), 0, 255);
                baseOut[base + 2] = (byte) clampInt(Math.round((accumPb / 16.0F) * 255.0F), 0, 255);
                baseOut[base + 3] = (byte) clampInt(Math.round((accumA / 16.0F) * 255.0F), 0, 255);

                fillOut[base] = (byte) 255;
                fillOut[base + 1] = (byte) 255;
                fillOut[base + 2] = (byte) 255;
                fillOut[base + 3] = (byte) clampInt(Math.round((accumFillA / 16.0F) * 255.0F), 0, 255);
            }
        }
    }

    /**
     * Génère la texture RGBA8 64x64 prémultipliée (sur-échantillonnage analytique 4x4) pour les tests unitaires.
     */
    public static byte[] rasterizeSupersampledCursorRgba(int style, int fillArgb, int outlineArgb, boolean shadow) {
        byte[] out = new byte[TEX_SIZE * TEX_SIZE * 4];

        float fillR = ((fillArgb >> 16) & 0xFF) / 255.0F;
        float fillG = ((fillArgb >> 8) & 0xFF) / 255.0F;
        float fillB = (fillArgb & 0xFF) / 255.0F;

        float outR = ((outlineArgb >> 16) & 0xFF) / 255.0F;
        float outG = ((outlineArgb >> 8) & 0xFF) / 255.0F;
        float outB = (outlineArgb & 0xFF) / 255.0F;

        float hotX = (style == 3) ? CROSS_HOTSPOT_X : ARROW_HOTSPOT_X;
        float hotY = (style == 3) ? CROSS_HOTSPOT_Y : ARROW_HOTSPOT_Y;
        float outlineWidth = (style == 3) ? 1.10F : 1.30F;

        for (int py = 0; py < TEX_SIZE; py++) {
            for (int px = 0; px < TEX_SIZE; px++) {
                float accumPr = 0.0F;
                float accumPg = 0.0F;
                float accumPb = 0.0F;
                float accumA = 0.0F;

                for (int sy = 0; sy < 4; sy++) {
                    float y = ((float) py + (sy + 0.5F) * 0.25F) - hotY;
                    for (int sx = 0; sx < 4; sx++) {
                        float x = ((float) px + (sx + 0.5F) * 0.25F) - hotX;

                        float pr = 0.0F;
                        float pg = 0.0F;
                        float pb = 0.0F;
                        float pa = 0.0F;

                        if (shadow) {
                            float sdSh = evaluateCursorSdf(x - 1.15F, y - 1.65F, style) - outlineWidth;
                            if (sdSh < 2.8F) {
                                float shA = 0.34F * (float) Math.exp(-Math.max(0.0F, sdSh) * 1.15F);
                                pa = shA;
                            }
                        }

                        float sd = evaluateCursorSdf(x, y, style);
                        float borderCov = clamp01((outlineWidth + 0.32F - sd) / 0.64F);
                        if (borderCov > 0.0F) {
                            pr = outR * borderCov + pr * (1.0F - borderCov);
                            pg = outG * borderCov + pg * (1.0F - borderCov);
                            pb = outB * borderCov + pb * (1.0F - borderCov);
                            pa = borderCov + pa * (1.0F - borderCov);
                        }

                        float fillCov = clamp01((0.28F - sd) / 0.56F);
                        if (fillCov > 0.0F) {
                            pr = fillR * fillCov + pr * (1.0F - fillCov);
                            pg = fillG * fillCov + pg * (1.0F - fillCov);
                            pb = fillB * fillCov + pb * (1.0F - fillCov);
                            pa = fillCov + pa * (1.0F - fillCov);
                        }

                        accumPr += pr;
                        accumPg += pg;
                        accumPb += pb;
                        accumA += pa;
                    }
                }

                int base = (py * TEX_SIZE + px) * 4;
                out[base] = (byte) clampInt(Math.round((accumPr / 16.0F) * 255.0F), 0, 255);
                out[base + 1] = (byte) clampInt(Math.round((accumPg / 16.0F) * 255.0F), 0, 255);
                out[base + 2] = (byte) clampInt(Math.round((accumPb / 16.0F) * 255.0F), 0, 255);
                out[base + 3] = (byte) clampInt(Math.round((accumA / 16.0F) * 255.0F), 0, 255);
            }
        }
        return out;
    }

    private static final float[] POLY_SYSTEM_ARROW = {
            0.0F, 0.0F,
            0.0F, 16.8F,
            4.3F, 12.7F,
            7.0F, 18.8F,
            9.4F, 17.7F,
            6.7F, 11.6F,
            12.0F, 11.6F
    };

    private static final float[] POLY_MASH_CHEVRON = {
            0.0F, 0.0F,
            0.0F, 16.8F,
            5.0F, 12.2F,
            12.2F, 11.6F
    };

    private static final float[] POLY_DIAMOND = {
            0.0F, 0.0F,
            1.2F, 15.6F,
            6.4F, 11.4F,
            13.4F, 8.2F
    };

    public static float evaluateCursorSdf(float x, float y, int style) {
        switch (style) {
            case 1:
                return sdPolygon(x, y, POLY_MASH_CHEVRON);
            case 2:
                return sdPolygon(x, y, POLY_DIAMOND);
            case 3: {
                float ax = Math.abs(x);
                float ay = Math.abs(y);
                float dot = (float) Math.sqrt(x * x + y * y) - 1.25F;
                float hArm = sdAxisBox(ax - 5.4F, ay, 2.8F, 0.85F);
                float vArm = sdAxisBox(ax, ay - 5.4F, 0.85F, 2.8F);
                return Math.min(dot, Math.min(hArm, vArm));
            }
            case 0:
            default:
                return sdPolygon(x, y, POLY_SYSTEM_ARROW);
        }
    }

    private static float sdAxisBox(float px, float py, float halfW, float halfH) {
        float dx = Math.abs(px) - halfW;
        float dy = Math.abs(py) - halfH;
        float outX = Math.max(dx, 0.0F);
        float outY = Math.max(dy, 0.0F);
        return (float) Math.sqrt(outX * outX + outY * outY) + Math.min(Math.max(dx, dy), 0.0F);
    }

    private static float sdPolygon(float px, float py, float[] v) {
        int n = v.length >>> 1;
        float dSq = Float.MAX_VALUE;
        int sign = 1;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float vx = v[i * 2], vy = v[i * 2 + 1];
            float wx = v[j * 2], wy = v[j * 2 + 1];
            float ex = wx - vx, ey = wy - vy;
            float dx = px - vx, dy = py - vy;
            float denom = Math.max(1.0E-6F, ex * ex + ey * ey);
            float h = clamp01((dx * ex + dy * ey) / denom);
            float bx = dx - ex * h, by = dy - ey * h;
            float distSq = bx * bx + by * by;
            if (distSq < dSq) {
                dSq = distSq;
            }
            boolean c1 = py >= vy;
            boolean c2 = py < wy;
            boolean c3 = ex * dy > ey * dx;
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) {
                sign = -sign;
            }
        }
        return (float) sign * (float) Math.sqrt(dSq);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
