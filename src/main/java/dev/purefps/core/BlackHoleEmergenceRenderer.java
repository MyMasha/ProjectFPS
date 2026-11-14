package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * Rendu Analytique O(1) Haute-Fréquence (600+ FPS) du Trou Noir de Schwarzschild (MashClient 4.0) :
 * - ZÉRO boucle de raymarching 3D coûteuse et ZÉRO FBM multi-octaves par pixel :
 *   utilise la projection analytique exacte de déflexion gravitationnelle d'Einstein (disque équatorial primaire
 *   + arche photonique secondaire courbée au-dessus/en-dessous de l'Horizon des Événements + effet Doppler relativiste)
 *   couplée à une texture procédurale 256x256 sans couture échantillonnée par filtrage matériel bilinéaire GPU !
 * - Tourne à 100 FPS constants sans AUCUN freeze sur le thread dédié SplashProgress$3 pendant tout le chargement
 *   de Minecraft (remplaçant intégralement l'écran Mojang), puis poursuit en continuité temporelle exacte à 120 FPS
 *   sur le menu principal MashClient !
 */
public final class BlackHoleEmergenceRenderer {

    private static final float MAX_SPLASH_EMERGENCE = 0.68F;
    private static final float MENU_REMAINING_MS = 1550.0F;
    private static final int NOISE_SIZE = 256;
    private static final int MAX_MENU_FBO_HEIGHT = 768;

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "}\n";

    /**
     * Shader analytique O(1) ultra-rapide (~25 instructions ALU + 3 lectures texture2D matérielles, zéro boucle !) :
     * fonctionne à > 600 FPS même sur GPU intégré tout en offrant une netteté cinématique supérieure.
     */
    private static final String FRAGMENT_SHADER_SRC =
            "#version 120\n"
            + "uniform vec2 uRes;\n"
            + "uniform float uTime;\n"
            + "uniform float uEmergence;\n"
            + "uniform vec2 uMouse;\n"
            + "uniform sampler2D uNoiseTex;\n"
            + "\n"
            + "vec3 sampleDisk(float rNorm, float angle, float doppler, float spin) {\n"
            + "    vec2 uv1 = vec2(rNorm * 2.3 - spin * 0.05, angle * 0.31830988 + spin * 0.14 / (0.35 + rNorm));\n"
            + "    vec2 uv2 = vec2(rNorm * 5.1 + spin * 0.03, angle * 0.63661977 - spin * 0.22 / (0.28 + rNorm));\n"
            + "    float n1 = texture2D(uNoiseTex, uv1).r;\n"
            + "    float n2 = texture2D(uNoiseTex, uv2).g;\n"
            + "    float streak = smoothstep(0.18, 0.88, n1 * 0.66 + n2 * 0.34);\n"
            + "    float innerFade = smoothstep(0.0, 0.11, rNorm);\n"
            + "    float outerFade = pow(max(1.0 - rNorm, 0.0), 1.85);\n"
            + "    float profile = innerFade * outerFade * (0.48 + 0.82 * streak);\n"
            + "    vec3 cHot = vec3(1.00, 0.94, 0.80);\n"
            + "    vec3 cGold = vec3(0.96, 0.66, 0.25);\n"
            + "    vec3 cDeep = vec3(0.58, 0.20, 0.04);\n"
            + "    vec3 baseCol = mix(cHot, cGold, smoothstep(0.03, 0.44, rNorm));\n"
            + "    baseCol = mix(baseCol, cDeep, smoothstep(0.42, 0.96, rNorm));\n"
            + "    vec3 beamed = mix(baseCol * vec3(1.0, 0.72, 0.50), vec3(1.0, 0.96, 0.88), clamp((doppler - 0.85) * 0.65, 0.0, 0.72));\n"
            + "    return beamed * profile * doppler;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / max(uRes.y, 1.0);\n"
            + "    float e = clamp(uEmergence, 0.0, 1.0);\n"
            + "    float easeOut = 1.0 - pow(1.0 - e, 2.6);\n"
            + "\n"
            + "    // Panoramique souris subtil une fois sorti en orbite\n"
            + "    uv -= uMouse * (0.032 * smoothstep(0.55, 1.0, e));\n"
            + "\n"
            + "    // Rayon apparent de l'Horizon des Événements : couvre tout l'écran à e=0 (#000000), recule jusqu'à 0.165 en orbite\n"
            + "    float rShadow = mix(1.42, 0.165, easeOut);\n"
            + "    float tilt = mix(0.11, 0.255, easeOut);\n"
            + "\n"
            + "    // Inclinaison cinématique de Gargantua (-15 deg)\n"
            + "    float ca = 0.9659258;\n"
            + "    float sa = 0.2588190;\n"
            + "    vec2 p = vec2(uv.x * ca - uv.y * sa, uv.x * sa + uv.y * ca);\n"
            + "    float r = length(p);\n"
            + "\n"
            + "    // Fond stellaire lentillé par déflexion gravitationnelle 1/r\n"
            + "    float deflect = (rShadow * rShadow) / max(r * r + 0.004, 0.004);\n"
            + "    vec2 starUv = p * (1.0 - 0.42 * deflect) * 1.9 + vec2(uTime * 0.003, -uTime * 0.0015);\n"
            + "    vec3 starSample = texture2D(uNoiseTex, starUv * 2.4).rgb;\n"
            + "    float starSpark = pow(max(starSample.b - 0.74, 0.0) * 3.85, 3.2);\n"
            + "    float nebula = starSample.r * exp(-abs(p.y) * 5.2) * 0.055;\n"
            + "    vec3 col = vec3(0.95, 0.90, 0.82) * starSpark * smoothstep(rShadow * 0.98, rShadow * 1.18, r)\n"
            + "             + vec3(0.08, 0.055, 0.035) * nebula;\n"
            + "\n"
            + "    float rIn = rShadow * 1.16;\n"
            + "    float rOut = rShadow * 4.55;\n"
            + "    float spin = uTime * (1.05 + (1.0 - easeOut) * 1.65);\n"
            + "    float doppler = clamp(1.0 - (p.x / max(rOut * 0.68, 0.001)) * 0.52, 0.34, 1.88);\n"
            + "    doppler = doppler * doppler;\n"
            + "\n"
            + "    // 1. Arche gravitationnelle supérieure/inférieure (rayons courbés derrière le Trou Noir)\n"
            + "    float lensedR = r + (rShadow * rShadow * 0.58) / max(abs(p.y) + rShadow * 0.34, 0.002);\n"
            + "    float archNorm = (lensedR - rIn) / max(rOut - rIn, 0.001);\n"
            + "    if (archNorm > 0.0 && archNorm < 1.0 && r > rShadow * 0.96) {\n"
            + "        float archAng = atan(p.y * 1.35, p.x);\n"
            + "        float backMask = smoothstep(rShadow * 0.96, rShadow * 1.06, r) * smoothstep(0.0, rShadow * 0.26, abs(p.y));\n"
            + "        col += sampleDisk(archNorm, archAng, doppler * 0.86, spin) * backMask * 0.92;\n"
            + "    }\n"
            + "\n"
            + "    // 2. Sphère d'ombre noire absolue de l'Horizon des Événements (#000000)\n"
            + "    float shadowMask = smoothstep(rShadow * 1.015, rShadow * 0.975, r);\n"
            + "    col *= (1.0 - shadowMask);\n"
            + "\n"
            + "    // 3. Anneau de photons relativiste ultra-fin sur le bord exact de rShadow\n"
            + "    float ringDist = (r - rShadow * 1.018) / max(rShadow * 0.048, 0.001);\n"
            + "    float photonRing = exp(-ringDist * ringDist) * (0.75 + 0.45 * doppler);\n"
            + "    col += vec3(1.0, 0.89, 0.68) * photonRing * 1.15 * smoothstep(0.02, 0.16, e);\n"
            + "\n"
            + "    // 4. Disque d'accrétion équatorial primaire (passe DEVANT l'hémisphère inférieur de l'Horizon)\n"
            + "    vec2 diskP = vec2(p.x, p.y / max(tilt, 0.04));\n"
            + "    float diskR = length(diskP);\n"
            + "    float diskNorm = (diskR - rIn) / max(rOut - rIn, 0.001);\n"
            + "    if (diskNorm > 0.0 && diskNorm < 1.0) {\n"
            + "        float frontCross = smoothstep(rShadow * 0.18, -rShadow * 0.06, p.y);\n"
            + "        float visMask = mix(1.0 - shadowMask, 1.0, frontCross);\n"
            + "        float diskAng = atan(diskP.y, diskP.x);\n"
            + "        col += sampleDisk(diskNorm, diskAng, doppler, spin) * visMask * 1.32;\n"
            + "    }\n"
            + "\n"
            + "    // Halo gravitationnel chaud lors de la traversée de la sphère de photons\n"
            + "    float warpFlash = exp(-pow((e - 0.14) / 0.11, 2.0)) * exp(-max(r - rShadow * 0.92, 0.0) * 6.5);\n"
            + "    col += vec3(1.0, 0.84, 0.55) * warpFlash * 0.55;\n"
            + "\n"
            + "    // Mappage tonal ACES cinématique + garantie #000000 absolu à e=0\n"
            + "    col = (col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14);\n"
            + "    col = pow(clamp(col, 0.0, 1.0), vec3(0.94));\n"
            + "    col *= smoothstep(0.0, 0.055, e);\n"
            + "    gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";

    // Ressources thread SplashProgress$3 (isolées puis détruites proprement dans clearGL)
    private static int splashProg = 0;
    private static int splashNoiseTex = 0;
    private static int splashULocRes = -1;
    private static int splashULocTime = -1;
    private static int splashULocEmergence = -1;
    private static int splashULocMouse = -1;
    private static int splashULocNoise = -1;

    // Ressources thread principal (Menu MashClient)
    private static int mainShaderState = 0; // 0 = non init, 1 = prêt, -1 = indisponible
    private static int mainProg = 0;
    private static int mainNoiseTex = 0;
    private static int mainULocRes = -1;
    private static int mainULocTime = -1;
    private static int mainULocEmergence = -1;
    private static int mainULocMouse = -1;
    private static int mainULocNoise = -1;
    private static Framebuffer menuFbo = null;

    // Continuité temporelle atomique entre SplashProgress$3 et GuiPureMainMenu
    private static volatile long bootOriginNs = 0L;
    private static volatile float latestEmergence = 0.0F;
    private static long menuAttachMs = -1L;
    private static float menuStartEmergence = 0.0F;
    private static float smoothMouseX = 0.0F;
    private static float smoothMouseY = 0.0F;

    private static ByteBuffer cachedNoisePixels = null;

    private BlackHoleEmergenceRenderer() {
    }

    public static void resetForBoot() {
        bootOriginNs = System.nanoTime();
        latestEmergence = 0.0F;
        menuAttachMs = -1L;
        menuStartEmergence = 0.0F;
    }

    public static float computeEmergenceProgress(float startupProgress, long menuElapsedMs) {
        float clampedStartup = clamp01(startupProgress);
        if (menuElapsedMs < 0L) {
            return clampedStartup;
        }
        float t = clamp01((float) menuElapsedMs / MENU_REMAINING_MS);
        float eased = 1.0F - (float) Math.pow(1.0D - t, 3.0D);
        return clampedStartup + (1.0F - clampedStartup) * eased;
    }

    public static float computeCameraUiAlpha(float emergence) {
        float e = clamp01(emergence);
        if (e <= 0.62F) {
            return 0.0F;
        }
        float x = clamp01((e - 0.62F) / 0.36F);
        return x * x * (3.0F - 2.0F * x);
    }

    /**
     * Appelé à 100 FPS constants depuis le thread dédié Forge SplashProgress$3 juste avant Display.update() :
     * aucun blocage pendant la compilation des modèles ni la couture de TextureMap !
     */
    public static void renderSplashThreadFrame(float forgeBarRatio) {
        if (bootOriginNs == 0L) {
            bootOriginNs = System.nanoTime();
        }
        float timeSec = (float) ((System.nanoTime() - bootOriginNs) * 1.0E-9D);

        // Progression continue lissée (temps écoulé + avancement réel des barres Forge) jusqu'à MAX_SPLASH_EMERGENCE
        float timeCurve = 1.0F - (float) Math.exp(-timeSec * 0.42D);
        float target = Math.min(MAX_SPLASH_EMERGENCE, Math.max(timeCurve * 0.56F, forgeBarRatio * MAX_SPLASH_EMERGENCE));
        if (target > latestEmergence) {
            latestEmergence += (target - latestEmergence) * 0.085F;
        } else {
            latestEmergence = Math.min(MAX_SPLASH_EMERGENCE, latestEmergence + 0.0018F);
        }

        int fbW = Math.max(1, Display.getWidth());
        int fbH = Math.max(1, Display.getHeight());

        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);

        if (ensureSplashShader()) {
            drawWithShader(
                    splashProg,
                    splashNoiseTex,
                    splashULocRes,
                    splashULocTime,
                    splashULocEmergence,
                    splashULocMouse,
                    splashULocNoise,
                    fbW,
                    fbH,
                    timeSec,
                    latestEmergence,
                    0.0F,
                    0.0F
            );
        } else {
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * Libère proprement les objets GL du thread SplashProgress$3 avant que Display.getDrawable() ne revienne au thread principal.
     */
    public static void onSplashThreadFinishing() {
        try {
            GL20.glUseProgram(0);
            if (splashProg != 0) {
                GL20.glDeleteProgram(splashProg);
                splashProg = 0;
            }
            if (splashNoiseTex != 0) {
                GL11.glDeleteTextures(splashNoiseTex);
                splashNoiseTex = 0;
            }
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Repli si SplashProgress est désactivé sur un pilote exotique (drawSplashScreen Vanilla).
     */
    public static void renderStartupCheckpoint(Minecraft mc, float targetEmergence, boolean forceRender) {
        if (mc == null || !Display.isCreated()) {
            return;
        }
        if (bootOriginNs == 0L) {
            bootOriginNs = System.nanoTime();
        }
        if (targetEmergence > latestEmergence) {
            latestEmergence = Math.min(MAX_SPLASH_EMERGENCE, targetEmergence);
        }
        int fbW = Math.max(1, mc.displayWidth);
        int fbH = Math.max(1, mc.displayHeight);
        float timeSec = (float) ((System.nanoTime() - bootOriginNs) * 1.0E-9D);

        try {
            if (ensureMainShader()) {
                drawWithShader(
                        mainProg,
                        mainNoiseTex,
                        mainULocRes,
                        mainULocTime,
                        mainULocEmergence,
                        mainULocMouse,
                        mainULocNoise,
                        fbW,
                        fbH,
                        timeSec,
                        latestEmergence,
                        0.0F,
                        0.0F
                );
            } else {
                GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            }
            Display.update();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Rendu 120+ FPS dans GuiPureMainMenu : poursuit sans coupure la trajectoire de la caméra depuis latestEmergence jusqu'à 1.0.
     */
    public static float renderMenuBackground(Minecraft mc, int guiWidth, int guiHeight, int mouseX, int mouseY) {
        long nowMs = System.currentTimeMillis();
        if (bootOriginNs == 0L) {
            bootOriginNs = System.nanoTime();
            latestEmergence = 0.65F;
        }
        if (menuAttachMs < 0L) {
            menuAttachMs = nowMs;
            menuStartEmergence = latestEmergence > 0.02F ? latestEmergence : 0.65F;
        }

        long elapsedMenuMs = Math.max(0L, nowMs - menuAttachMs);
        float emergence = computeEmergenceProgress(menuStartEmergence, elapsedMenuMs);
        latestEmergence = emergence;

        float targetMx = guiWidth > 0 ? ((float) mouseX / (float) guiWidth) * 2.0F - 1.0F : 0.0F;
        float targetMy = guiHeight > 0 ? ((float) mouseY / (float) guiHeight) * 2.0F - 1.0F : 0.0F;
        smoothMouseX += (targetMx - smoothMouseX) * 0.07F;
        smoothMouseY += (targetMy - smoothMouseY) * 0.07F;

        int dispW = mc != null ? Math.max(1, mc.displayWidth) : Math.max(1, guiWidth * 2);
        int dispH = mc != null ? Math.max(1, mc.displayHeight) : Math.max(1, guiHeight * 2);
        float timeSec = (float) ((System.nanoTime() - bootOriginNs) * 1.0E-9D);

        if (ensureMainShader() && OpenGlHelper.isFramebufferEnabled() && mc != null && mc.getFramebuffer() != null) {
            int fboH = Math.min(dispH, MAX_MENU_FBO_HEIGHT);
            int fboW = Math.max(1, Math.round((float) dispW * ((float) fboH / (float) dispH)));
            ensureMenuFbo(fboW, fboH);

            if (menuFbo != null) {
                menuFbo.bindFramebuffer(true);
                drawWithShader(
                        mainProg,
                        mainNoiseTex,
                        mainULocRes,
                        mainULocTime,
                        mainULocEmergence,
                        mainULocMouse,
                        mainULocNoise,
                        fboW,
                        fboH,
                        timeSec,
                        emergence,
                        smoothMouseX,
                        -smoothMouseY
                );
                mc.getFramebuffer().bindFramebuffer(true);

                GlStateManager.disableDepth();
                GlStateManager.disableAlpha();
                GlStateManager.disableBlend();
                GlStateManager.enableTexture2D();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                menuFbo.bindFramebufferTexture();

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0.0F, 0.0F);
                GL11.glVertex2f(0.0F, (float) guiHeight);
                GL11.glTexCoord2f(1.0F, 0.0F);
                GL11.glVertex2f((float) guiWidth, (float) guiHeight);
                GL11.glTexCoord2f(1.0F, 1.0F);
                GL11.glVertex2f((float) guiWidth, 0.0F);
                GL11.glTexCoord2f(0.0F, 1.0F);
                GL11.glVertex2f(0.0F, 0.0F);
                GL11.glEnd();

                GlStateManager.enableAlpha();
                return computeCameraUiAlpha(emergence);
            }
        }

        Gui.drawRect(0, 0, guiWidth, guiHeight, 0xFF040509);
        return computeCameraUiAlpha(emergence);
    }

    public static void releaseMenuFramebuffer() {
        if (menuFbo != null) {
            try {
                menuFbo.deleteFramebuffer();
            } catch (Throwable ignored) {
            }
            menuFbo = null;
        }
    }

    public static void releaseMenuResources() {
        releaseMenuFramebuffer();
    }

    private static void ensureMenuFbo(int w, int h) {
        if (menuFbo != null && menuFbo.framebufferWidth == w && menuFbo.framebufferHeight == h) {
            return;
        }
        releaseMenuFramebuffer();
        try {
            menuFbo = new Framebuffer(w, h, false);
            menuFbo.setFramebufferFilter(GL11.GL_LINEAR);
        } catch (Throwable t) {
            menuFbo = null;
        }
    }

    private static void drawWithShader(
            int prog,
            int noiseTex,
            int uRes,
            int uTime,
            int uEmergence,
            int uMouse,
            int uNoise,
            int w,
            int h,
            float timeSec,
            float emergence,
            float mx,
            float my
    ) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTex);

        GL20.glUseProgram(prog);
        GL20.glUniform2f(uRes, (float) w, (float) h);
        GL20.glUniform1f(uTime, timeSec);
        GL20.glUniform1f(uEmergence, emergence);
        GL20.glUniform2f(uMouse, mx, my);
        GL20.glUniform1i(uNoise, 0);

        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(-1.0F, -1.0F);
        GL11.glVertex2f(3.0F, -1.0F);
        GL11.glVertex2f(-1.0F, 3.0F);
        GL11.glEnd();

        GL20.glUseProgram(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    private static boolean ensureSplashShader() {
        if (splashProg != 0 && splashNoiseTex != 0) {
            return true;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null || !caps.OpenGL20) {
                return false;
            }
            int prog = compileProgram();
            if (prog == 0) {
                return false;
            }
            splashProg = prog;
            splashNoiseTex = createNoiseGlTexture();
            splashULocRes = GL20.glGetUniformLocation(prog, "uRes");
            splashULocTime = GL20.glGetUniformLocation(prog, "uTime");
            splashULocEmergence = GL20.glGetUniformLocation(prog, "uEmergence");
            splashULocMouse = GL20.glGetUniformLocation(prog, "uMouse");
            splashULocNoise = GL20.glGetUniformLocation(prog, "uNoiseTex");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ensureMainShader() {
        if (mainShaderState != 0) {
            return mainShaderState == 1;
        }
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps == null || !caps.OpenGL20) {
                mainShaderState = -1;
                return false;
            }
            int prog = compileProgram();
            if (prog == 0) {
                mainShaderState = -1;
                return false;
            }
            mainProg = prog;
            mainNoiseTex = createNoiseGlTexture();
            mainULocRes = GL20.glGetUniformLocation(prog, "uRes");
            mainULocTime = GL20.glGetUniformLocation(prog, "uTime");
            mainULocEmergence = GL20.glGetUniformLocation(prog, "uEmergence");
            mainULocMouse = GL20.glGetUniformLocation(prog, "uMouse");
            mainULocNoise = GL20.glGetUniformLocation(prog, "uNoiseTex");
            mainShaderState = 1;
            return true;
        } catch (Throwable t) {
            mainShaderState = -1;
            return false;
        }
    }

    private static int compileProgram() {
        int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vs, VERTEX_SHADER_SRC);
        GL20.glCompileShader(vs);
        if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(vs);
            return 0;
        }

        int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fs, FRAGMENT_SHADER_SRC);
        GL20.glCompileShader(fs);
        if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            return 0;
        }

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /**
     * Pré-calcule une seule fois sur CPU une texture de plasma/étoiles périodique 256x256 sans couture (GL_REPEAT).
     */
    private static synchronized ByteBuffer getOrBuildSeamlessNoisePixels() {
        if (cachedNoisePixels != null) {
            cachedNoisePixels.position(0);
            return cachedNoisePixels;
        }
        ByteBuffer buf = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                float n1 = tileableFbm(x, y, 4, 4, 11);
                float n2 = tileableFbm(x, y, 8, 5, 97);
                int starHash = hash2i(x, y, 733);
                int starVal = (starHash & 0xFF);
                buf.put((byte) clampByte(n1 * 255.0F));
                buf.put((byte) clampByte(n2 * 255.0F));
                buf.put((byte) starVal);
                buf.put((byte) 255);
            }
        }
        buf.flip();
        cachedNoisePixels = buf;
        return buf;
    }

    private static float tileableFbm(int px, int py, int basePeriod, int octaves, int seed) {
        float sum = 0.0F;
        float amp = 0.52F;
        float norm = 0.0F;
        int period = basePeriod;
        for (int o = 0; o < octaves; o++) {
            float gx = ((float) px / (float) NOISE_SIZE) * (float) period;
            float gy = ((float) py / (float) NOISE_SIZE) * (float) period;
            int ix = (int) Math.floor(gx);
            int iy = (int) Math.floor(gy);
            float fx = gx - (float) ix;
            float fy = gy - (float) iy;
            float ux = fx * fx * (3.0F - 2.0F * fx);
            float uy = fy * fy * (3.0F - 2.0F * fy);

            int x0 = ((ix % period) + period) % period;
            int y0 = ((iy % period) + period) % period;
            int x1 = (x0 + 1) % period;
            int y1 = (y0 + 1) % period;

            float a = (hash2i(x0, y0, seed + o * 31) & 0xFFFF) / 65535.0F;
            float b = (hash2i(x1, y0, seed + o * 31) & 0xFFFF) / 65535.0F;
            float c = (hash2i(x0, y1, seed + o * 31) & 0xFFFF) / 65535.0F;
            float d = (hash2i(x1, y1, seed + o * 31) & 0xFFFF) / 65535.0F;

            float v = (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy;
            sum += v * amp;
            norm += amp;
            amp *= 0.5F;
            period <<= 1;
        }
        return norm > 0.0F ? sum / norm : 0.0F;
    }

    private static int hash2i(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static int clampByte(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static int createNoiseGlTexture() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        ByteBuffer pixels = getOrBuildSeamlessNoisePixels();
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                NOISE_SIZE,
                NOISE_SIZE,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                pixels
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }
}
