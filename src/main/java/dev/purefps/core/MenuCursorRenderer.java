package dev.purefps.core;

import dev.purefps.gui.GuiPureMainMenu;
import dev.purefps.module.MashFlags;
import dev.purefps.module.MashModules;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;

/**
 * Rendu matériel OpenGL indépendant du compositeur Windows DWM pour le curseur de souris dans tous les menus :
 * - Contourne définitivement la disparition du plan de curseur matériel Win32 en mode Borderless DirectFlip /
 *   NVIDIA DXGI Swapchain / Optimus / Discord Screen-Share / LWJGL 2.9.4 (GCLP_HCURSOR = NULL).
 * - Échantillonne la position réelle du pointeur noyau Win32 (GetCursorPos + ScreenToClient) juste avant
 *   SwapBuffers (Display.update) dans FBO 0 en résolution native 1:1 (latence minimale, actif dès la frame 1).
 * - Texture SDF 64x64 sur-échantillonnée 4x4 en alpha prémultiplié (zéro crénelage même sans MSAA).
 */
public final class MenuCursorRenderer {

    private static final int TEX_SIZE = 64;
    private static final float ARROW_HOTSPOT_X = 8.0F;
    private static final float ARROW_HOTSPOT_Y = 8.0F;
    private static final float CROSS_HOTSPOT_X = 32.0F;
    private static final float CROSS_HOTSPOT_Y = 32.0F;

    private static int cursorTexId = 0;
    private static int builtStyle = -1;
    private static int builtFillArgb = 0;
    private static int builtOutlineArgb = 0;
    private static boolean builtShadow = true;

    private static final float[] CURSOR_POS_XY = new float[2];
    private static ByteBuffer uploadBuffer = null;
    private static float clickPulseAnim = 0.0F;
    private static long lastFrameNs = 0L;

    private MenuCursorRenderer() {
    }

    /**
     * Appelé juste avant Display.update() dans Minecraft.updateDisplay() :
     * dessine le curseur directement dans le backbuffer natif (FBO 0) par-dessus tous les écrans,
     * info-bulles et objets d'inventaire, sans jamais dépendre du curseur matériel Win32.
     */
    public static void onBeforeSwapBuffers(Minecraft mc) {
        if (mc == null || !Display.isCreated()) {
            return;
        }

        boolean inMenu = mc.currentScreen != null
                && (!Mouse.isCreated() || !Mouse.isGrabbed());

        // Garde le rideau #000000 100 % pur sur les 2 frames de pré-chauffage avant l'émergence
        if (inMenu
                && mc.currentScreen instanceof GuiPureMainMenu
                && !BlackHoleEmergenceRenderer.hasPresentedInitialBlackFrames()) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(true);
            return;
        }

        if (!inMenu || !PureFPSConfigAllowsCustomCursor()) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            if (inMenu) {
                BorderlessWindowHelper.ensureMenuCursorVisible();
            }
            return;
        }

        int dispW = Math.max(1, mc.displayWidth);
        int dispH = Math.max(1, mc.displayHeight);
        int cursorState = BorderlessWindowHelper.queryClientCursorState(mc, CURSOR_POS_XY);
        if (cursorState == 0) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            BorderlessWindowHelper.ensureMenuCursorVisible();
            return;
        }

        boolean windowFocused = Display.isActive();
        if (!windowFocused && cursorState < 2) {
            // Une autre application (ex: Discord) recouvre ce pixel ou se trouve sur un autre écran
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            return;
        }

        float px = CURSOR_POS_XY[0];
        float py = CURSOR_POS_XY[1];
        boolean insideClient = px >= 0.0F && py >= 0.0F && px < (float) dispW && py < (float) dispH;
        if (!insideClient) {
            // Souris sur la barre de titre en mode fenêtré F11 ou sur un second moniteur
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            return;
        }

        // Masque le curseur client LWJGL dans HTCLIENT pour éviter tout double curseur au-dessus de Minecraft
        BorderlessWindowHelper.setClientHardwareCursorBlank(true);

        MashModules.ensureRegistered();
        int style = clampInt(MashFlags.menuCursorStyle, 0, 3);
        int fillArgb = resolveFillArgb(style);
        int outlineArgb = resolveOutlineArgb(style);
        boolean shadow = MashFlags.menuCursorShadow;

        ensureCursorTexture(style, fillArgb, outlineArgb, shadow);
        if (cursorTexId == 0) {
            BorderlessWindowHelper.setClientHardwareCursorBlank(false);
            BorderlessWindowHelper.ensureMenuCursorVisible();
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

        drawCursorQuadStateSafe(dispW, dispH, x0, y0, x1, y1, px, py, totalScale);
    }

    private static boolean PureFPSConfigAllowsCustomCursor() {
        return !dev.purefps.config.PureFPSConfig.enabled || MashFlags.menuCursorEnabled;
    }

    private static int resolveFillArgb(int style) {
        long now = System.currentTimeMillis();
        if (MashFlags.menuCursorChroma) {
            int hueRgb = java.awt.Color.HSBtoRGB((now % 3200L) / 3200.0F, 0.78F, 1.0F) & 0x00FFFFFF;
            return 0xFF000000 | hueRgb;
        }
        if (style == 1) {
            // Flèche Mash : cœur obsidienne sombre, sauf si l'utilisateur a choisi une teinte personnalisée non-blanche
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

    private static void drawCursorQuadStateSafe(
            int dispW,
            int dispH,
            float x0,
            float y0,
            float x1,
            float y1,
            float tipX,
            float tipY,
            float scale
    ) {
        int prevProgram = 0;
        int prevActiveTex = GL13.GL_TEXTURE0;
        int prevTex2D = 0;
        boolean programChanged = false;
        boolean activeTexChanged = false;

        try {
            try {
                prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                if (prevProgram != 0) {
                    GL20.glUseProgram(0);
                    programChanged = true;
                }
            } catch (Throwable ignored) {
            }

            try {
                prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                if (prevActiveTex != GL13.GL_TEXTURE0) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    activeTexChanged = true;
                }
            } catch (Throwable ignored) {
            }

            prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            GL11.glPushAttrib(
                    GL11.GL_ENABLE_BIT
                            | GL11.GL_COLOR_BUFFER_BIT
                            | GL11.GL_CURRENT_BIT
                            | GL11.GL_TRANSFORM_BIT
                            | GL11.GL_VIEWPORT_BIT
            );
            try {
                GL11.glViewport(0, 0, dispW, dispH);

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(0.0D, (double) dispW, (double) dispH, 0.0D, -1.0D, 1.0D);

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();

                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glColorMask(true, true, true, true);

                GL11.glEnable(GL11.GL_BLEND);

                if (clickPulseAnim > 0.02F) {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    float radius = (6.5F + (1.0F - clickPulseAnim) * 4.5F) * scale;
                    float alpha = 0.42F * clickPulseAnim;
                    GL11.glLineWidth(Math.max(1.2F, 1.5F * scale));
                    GL11.glColor4f(0.96F, 0.72F, 0.36F, alpha);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    for (int i = 0; i < 20; i++) {
                        double ang = (Math.PI * 2.0D * i) / 20.0D;
                        GL11.glVertex2f(
                                tipX + (float) Math.cos(ang) * radius,
                                tipY + (float) Math.sin(ang) * radius
                        );
                    }
                    GL11.glEnd();
                }

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, cursorTexId);
                // Alpha prémultiplié : élimine toute frange sombre au filtrage bilinéaire
                GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0.0F, 0.0F);
                GL11.glVertex2f(x0, y0);
                GL11.glTexCoord2f(0.0F, 1.0F);
                GL11.glVertex2f(x0, y1);
                GL11.glTexCoord2f(1.0F, 1.0F);
                GL11.glVertex2f(x1, y1);
                GL11.glTexCoord2f(1.0F, 0.0F);
                GL11.glVertex2f(x1, y0);
                GL11.glEnd();

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
            } finally {
                GL11.glPopAttrib();
            }

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
        } catch (Throwable ignored) {
        } finally {
            if (activeTexChanged) {
                try {
                    GL13.glActiveTexture(prevActiveTex);
                } catch (Throwable ignored) {
                }
            }
            if (programChanged) {
                try {
                    GL20.glUseProgram(prevProgram);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void ensureCursorTexture(int style, int fillArgb, int outlineArgb, boolean shadow) {
        if (cursorTexId != 0
                && style == builtStyle
                && fillArgb == builtFillArgb
                && outlineArgb == builtOutlineArgb
                && shadow == builtShadow) {
            return;
        }

        byte[] rgba = rasterizeSupersampledCursorRgba(style, fillArgb, outlineArgb, shadow);
        if (uploadBuffer == null || uploadBuffer.capacity() < rgba.length) {
            uploadBuffer = BufferUtils.createByteBuffer(rgba.length);
        }
        uploadBuffer.clear();
        uploadBuffer.put(rgba);
        uploadBuffer.flip();

        int prevBind = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (cursorTexId == 0) {
                cursorTexId = GL11.glGenTextures();
            }
            if (cursorTexId == 0) {
                return;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cursorTexId);
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

            builtStyle = style;
            builtFillArgb = fillArgb;
            builtOutlineArgb = outlineArgb;
            builtShadow = shadow;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBind);
        }
    }

    /**
     * Génère la texture RGBA8 64x64 prémultipliée (sur-échantillonnage analytique 4x4) pour les tests et le GPU.
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

    /**
     * Distance signée exacte (< 0 à l'intérieur, > 0 à l'extérieur) autour du point chaud (0, 0),
     * calculée sur le polygone complet sans aucune arête interne.
     */
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
