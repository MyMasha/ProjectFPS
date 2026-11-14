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
 * Rendu 3D Géodésique de Schwarzschild Haute-Fréquence (MashClient 4.1) :
 * - UN SEUL disque d'accrétion équatorial 3D (`y = 0`) traversé par intégration RK2 adaptative
 *   le long des géodésiques nulles de Schwarzschild : produit la vraie courbure gravitationnelle
 *   de Gargantua SANS aucune double aura artificielle !
 * - Espace profond #000000 pur + étoiles 3D analytiques circulaires (ZÉRO lecture de texture de bruit
 *   dans l'espace = ZÉRO point blanc/noir parasite).
 * - Texture procédurale périodique 512×512 (coarse/fine/ridge FBM) dédiée exclusivement aux filaments
 *   du disque d'accrétion (120+ FPS constants sans aucun freeze).
 */
public final class BlackHoleEmergenceRenderer {

    private static final float MAX_SPLASH_EMERGENCE = 0.68F;
    private static final float MENU_REMAINING_MS = 1550.0F;
    private static final int NOISE_SIZE = 512;
    private static final int MAX_MENU_FBO_HEIGHT = 820;

    private static final String VERTEX_SHADER_SRC =
            "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAGMENT_SHADER_SRC =
            "#version 120\n"
            + "uniform vec2 uRes;\n"
            + "uniform float uTime;\n"
            + "uniform float uEmergence;\n"
            + "uniform vec2 uMouse;\n"
            + "uniform float uOffsetX;\n"
            + "uniform sampler2D uNoiseTex;\n"
            + "\n"
            + "vec3 hash33(vec3 p) {\n"
            + "    p = fract(p * vec3(0.1031, 0.1030, 0.0973));\n"
            + "    p += dot(p, p.yxz + vec3(33.33, 33.33, 33.33));\n"
            + "    return fract((p.xxy + p.yxx) * p.zyx);\n"
            + "}\n"
            + "\n"
            + "vec3 renderStarfield(vec3 dir, float emerge) {\n"
            + "    vec3 d = normalize(dir);\n"
            + "    vec3 col = vec3(0.0, 0.0, 0.0);\n"
            + "    vec3 grid1 = d * 78.0;\n"
            + "    vec3 id1 = floor(grid1);\n"
            + "    vec3 f1 = fract(grid1) - vec3(0.5, 0.5, 0.5);\n"
            + "    vec3 rnd1 = hash33(id1);\n"
            + "    if (rnd1.x > 0.968) {\n"
            + "        vec3 jitter = (rnd1 - vec3(0.5, 0.5, 0.5)) * 0.52;\n"
            + "        vec3 diff = f1 - jitter;\n"
            + "        float dist2 = dot(diff, diff);\n"
            + "        float core = exp(-dist2 * 95.0) * smoothstep(0.968, 0.999, rnd1.x);\n"
            + "        vec3 tint = mix(vec3(0.84, 0.90, 1.0), vec3(1.0, 0.90, 0.74), rnd1.y);\n"
            + "        col += tint * core * (1.35 - 0.35 * emerge);\n"
            + "    }\n"
            + "    vec3 grid2 = d * 142.0 + vec3(19.3, 19.3, 19.3);\n"
            + "    vec3 id2 = floor(grid2);\n"
            + "    vec3 f2 = fract(grid2) - vec3(0.5, 0.5, 0.5);\n"
            + "    vec3 rnd2 = hash33(id2);\n"
            + "    if (rnd2.x > 0.976) {\n"
            + "        vec3 jitter2 = (rnd2 - vec3(0.5, 0.5, 0.5)) * 0.48;\n"
            + "        vec3 diff2 = f2 - jitter2;\n"
            + "        float dist2 = dot(diff2, diff2);\n"
            + "        float core2 = exp(-dist2 * 125.0) * smoothstep(0.976, 0.999, rnd2.x);\n"
            + "        col += vec3(0.92, 0.94, 0.98) * core2 * 0.56;\n"
            + "    }\n"
            + "    return col;\n"
            + "}\n"
            + "\n"
            + "vec3 geodesicAccel(vec3 p, float h2) {\n"
            + "    float r2 = max(dot(p, p), 0.04);\n"
            + "    float r = sqrt(r2);\n"
            + "    return (-1.5 * h2 / (r2 * r2 * r)) * p;\n"
            + "}\n"
            + "\n"
            + "vec4 evaluateAccretionDisk(vec3 hit, vec3 rayDir, float spinPhase) {\n"
            + "    float r = length(hit.xz);\n"
            + "    float rIn = 1.82;\n"
            + "    float rOut = 9.60;\n"
            + "    if (r < rIn || r > rOut) {\n"
            + "        return vec4(0.0, 0.0, 0.0, 0.0);\n"
            + "    }\n"
            + "    float normR = clamp((r - rIn) / (rOut - rIn), 0.0, 1.0);\n"
            + "    float angle = atan(hit.z, hit.x);\n"
            + "    float omega = 2.70 / pow(max(r, 1.5), 1.35);\n"
            + "    float theta = angle + spinPhase * omega;\n"
            + "    vec2 uv1 = vec2(normR * 2.35 - spinPhase * 0.032, theta * 0.318309886);\n"
            + "    vec2 uv2 = vec2(normR * 5.20 + spinPhase * 0.021, theta * 0.795774715);\n"
            + "    vec3 tex1 = texture2D(uNoiseTex, uv1).rgb;\n"
            + "    vec3 tex2 = texture2D(uNoiseTex, uv2).rgb;\n"
            + "    float filaments = smoothstep(0.16, 0.88, tex1.r * 0.56 + tex2.g * 0.30 + tex1.b * 0.14);\n"
            + "    float innerFade = smoothstep(rIn, rIn + 0.56, r);\n"
            + "    float outerFade = pow(max(1.0 - normR, 0.0), 1.95);\n"
            + "    float dIsco = (r - 2.26) / 0.42;\n"
            + "    float iscoRing = exp(-dIsco * dIsco) * 0.86;\n"
            + "    float density = (innerFade * outerFade * (0.38 + 0.86 * filaments) + iscoRing) * 0.94;\n"
            + "    vec3 tangent = normalize(vec3(-hit.z, 0.0, hit.x));\n"
            + "    float beta = clamp(0.66 / sqrt(max(r - 0.85, 0.40)), 0.0, 0.78);\n"
            + "    float cosTheta = dot(tangent, -normalize(rayDir));\n"
            + "    float gamma = inversesqrt(max(1.0 - beta * beta, 0.12));\n"
            + "    float doppler = 1.0 / max(gamma * (1.0 - beta * cosTheta), 0.24);\n"
            + "    float beaming = pow(clamp(doppler, 0.28, 2.35), 2.50);\n"
            + "    vec3 cHot = vec3(1.44, 1.28, 0.98);\n"
            + "    vec3 cGold = vec3(1.08, 0.65, 0.22);\n"
            + "    vec3 cCopper = vec3(0.58, 0.18, 0.04);\n"
            + "    vec3 baseCol = mix(cHot, cGold, smoothstep(0.0, 0.35, normR));\n"
            + "    baseCol = mix(baseCol, cCopper, smoothstep(0.33, 0.94, normR));\n"
            + "    float shiftT = clamp((doppler - 0.74) * 0.64, 0.0, 1.0);\n"
            + "    vec3 beamedCol = mix(baseCol * vec3(1.10, 0.50, 0.22), mix(baseCol, vec3(1.14, 1.18, 1.26), 0.36), shiftT);\n"
            + "    float alpha = clamp(density * clamp(0.42 + 0.50 * beaming, 0.22, 1.55), 0.0, 0.96);\n"
            + "    return vec4(beamedCol * density * beaming * 1.10, alpha);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / max(min(uRes.x, uRes.y), 1.0);\n"
            + "    float e = clamp(uEmergence, 0.0, 1.0);\n"
            + "    float easeOut = 1.0 - pow(1.0 - e, 2.65);\n"
            + "    float menuSettle = smoothstep(0.58, 1.0, e);\n"
            + "    uv.x -= uOffsetX * menuSettle;\n"
            + "\n"
            + "    // Sortie continue de l'Horizon (r = 1.08 -> orbite rapprochée r = 15.6 identique au Launcher)\n"
            + "    float camDist = mix(1.08, 15.6, easeOut);\n"
            + "    float elev = mix(0.055, 0.176 + uMouse.y * 0.04 * menuSettle, easeOut);\n"
            + "    float azim = uTime * 0.032 + (1.0 - easeOut) * 0.72 + uMouse.x * 0.07 * menuSettle;\n"
            + "\n"
            + "    vec3 ro = vec3(\n"
            + "        camDist * cos(elev) * sin(azim),\n"
            + "        camDist * sin(elev),\n"
            + "        camDist * cos(elev) * cos(azim)\n"
            + "    );\n"
            + "\n"
            + "    vec3 forward = normalize(-ro);\n"
            + "    vec3 worldUp = vec3(0.0, 1.0, 0.0);\n"
            + "    float roll = mix(0.26, -0.155, easeOut);\n"
            + "    vec3 right0 = normalize(cross(forward, worldUp));\n"
            + "    vec3 up0 = cross(right0, forward);\n"
            + "    vec3 right = right0 * cos(roll) + up0 * sin(roll);\n"
            + "    vec3 up = -right0 * sin(roll) + up0 * cos(roll);\n"
            + "\n"
            + "    float focal = mix(0.66, 1.56, easeOut);\n"
            + "    vec3 rd = normalize(forward * focal + uv.x * right + uv.y * up);\n"
            + "\n"
            + "    vec3 pos = ro;\n"
            + "    vec3 vel = rd;\n"
            + "    vec3 hVec = cross(pos, vel);\n"
            + "    float h2 = dot(hVec, hVec);\n"
            + "\n"
            + "    vec3 accumColor = vec3(0.0, 0.0, 0.0);\n"
            + "    float transmittance = 1.0;\n"
            + "    float captured = 0.0;\n"
            + "    float closestR = 100.0;\n"
            + "    float spinPhase = uTime * 0.56 + (1.0 - easeOut) * 1.95;\n"
            + "\n"
            + "    for (int i = 0; i < 44; i++) {\n"
            + "        float r = length(pos);\n"
            + "        closestR = min(closestR, r);\n"
            + "        if (r <= 1.015) {\n"
            + "            captured = 1.0;\n"
            + "            break;\n"
            + "        }\n"
            + "        if (r > 26.0 && i > 8) {\n"
            + "            break;\n"
            + "        }\n"
            + "        float dt = clamp(0.172 * (r - 0.90), 0.058, 0.96);\n"
            + "        vec3 a1 = geodesicAccel(pos, h2);\n"
            + "        vec3 midPos = pos + vel * (0.5 * dt);\n"
            + "        vec3 midVel = vel + a1 * (0.5 * dt);\n"
            + "        vec3 a2 = geodesicAccel(midPos, h2);\n"
            + "        vec3 prevPos = pos;\n"
            + "        pos += midVel * dt;\n"
            + "        vel = normalize(vel + a2 * dt);\n"
            + "        if (prevPos.y * pos.y <= 0.0) {\n"
            + "            float frac = abs(prevPos.y) / (abs(prevPos.y) + abs(pos.y) + 0.000001);\n"
            + "            vec3 hit = mix(prevPos, pos, frac);\n"
            + "            if (length(hit) > 1.03) {\n"
            + "                vec4 disk = evaluateAccretionDisk(hit, vel, spinPhase);\n"
            + "                accumColor += disk.rgb * transmittance;\n"
            + "                transmittance *= (1.0 - disk.a);\n"
            + "                if (transmittance < 0.028) {\n"
            + "                    break;\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    float photonGlow = exp(-max(closestR - 1.50, 0.0) * 8.4) * (1.0 - captured);\n"
            + "    accumColor += vec3(1.18, 0.88, 0.52) * photonGlow * 0.42 * transmittance;\n"
            + "\n"
            + "    if (captured < 0.5 && transmittance > 0.01) {\n"
            + "        accumColor += renderStarfield(vel, easeOut) * transmittance;\n"
            + "    }\n"
            + "\n"
            + "    vec3 x = max(vec3(0.0, 0.0, 0.0), accumColor);\n"
            + "    vec3 mapped = (x * (2.51 * x + vec3(0.03, 0.03, 0.03))) / (x * (2.43 * x + vec3(0.59, 0.59, 0.59)) + vec3(0.14, 0.14, 0.14));\n"
            + "    mapped = pow(clamp(mapped, 0.0, 1.0), vec3(0.92, 0.92, 0.92));\n"
            + "    mapped *= smoothstep(0.0, 0.08, e);\n"
            + "    gl_FragColor = vec4(mapped, 1.0);\n"
            + "}\n";

    // Ressources thread SplashProgress$3 (isolées puis détruites proprement dans clearGL)
    private static int splashProg = 0;
    private static int splashNoiseTex = 0;
    private static int splashULocRes = -1;
    private static int splashULocTime = -1;
    private static int splashULocEmergence = -1;
    private static int splashULocMouse = -1;
    private static int splashULocOffsetX = -1;
    private static int splashULocNoise = -1;

    // Ressources thread principal (Menu MashClient)
    private static int mainShaderState = 0; // 0 = non init, 1 = prêt, -1 = indisponible
    private static int mainProg = 0;
    private static int mainNoiseTex = 0;
    private static int mainULocRes = -1;
    private static int mainULocTime = -1;
    private static int mainULocEmergence = -1;
    private static int mainULocMouse = -1;
    private static int mainULocOffsetX = -1;
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
                    splashULocOffsetX,
                    splashULocNoise,
                    fbW,
                    fbH,
                    timeSec,
                    latestEmergence,
                    0.0F,
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
                        mainULocOffsetX,
                        mainULocNoise,
                        fbW,
                        fbH,
                        timeSec,
                        latestEmergence,
                        0.0F,
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
     * Rendu 120+ FPS dans GuiPureMainMenu : poursuit sans coupure la trajectoire de la caméra depuis latestEmergence jusqu'à 1.0,
     * avec le même décalage éditorial (uOffsetX = 0.14) et le même cadrage rapproché que l'accueil du Launcher !
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
                        mainULocOffsetX,
                        mainULocNoise,
                        fboW,
                        fboH,
                        timeSec,
                        emergence,
                        smoothMouseX,
                        -smoothMouseY,
                        0.14F
                );
                mc.getFramebuffer().bindFramebuffer(true);

                GlStateManager.disableDepth();
                GlStateManager.disableAlpha();
                GlStateManager.disableBlend();
                GlStateManager.enableTexture2D();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                menuFbo.bindFramebufferTexture();

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(0.0D, 1.0D, 1.0D, 0.0D, -1.0D, 1.0D);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0.0F, 0.0F);
                GL11.glVertex2f(0.0F, 1.0F);
                GL11.glTexCoord2f(1.0F, 0.0F);
                GL11.glVertex2f(1.0F, 1.0F);
                GL11.glTexCoord2f(1.0F, 1.0F);
                GL11.glVertex2f(1.0F, 0.0F);
                GL11.glTexCoord2f(0.0F, 1.0F);
                GL11.glVertex2f(0.0F, 0.0F);
                GL11.glEnd();

                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);

                GlStateManager.bindTexture(0);
                GlStateManager.enableAlpha();
                return computeCameraUiAlpha(emergence);
            }
        }

        Gui.drawRect(0, 0, guiWidth, guiHeight, 0xFF000000);
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
            int uOffsetX,
            int uNoise,
            int w,
            int h,
            float timeSec,
            float emergence,
            float mx,
            float my,
            float offsetX
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
        GL20.glUniform1f(uOffsetX, offsetX);
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
            splashULocOffsetX = GL20.glGetUniformLocation(prog, "uOffsetX");
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
            mainULocOffsetX = GL20.glGetUniformLocation(prog, "uOffsetX");
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
            System.err.println("[MashClient] BlackHole VS error: " + GL20.glGetShaderInfoLog(vs, 2048));
            GL20.glDeleteShader(vs);
            return 0;
        }

        int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fs, FRAGMENT_SHADER_SRC);
        GL20.glCompileShader(fs);
        if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[MashClient] BlackHole FS error: " + GL20.glGetShaderInfoLog(fs, 2048));
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
            System.err.println("[MashClient] BlackHole Link error: " + GL20.glGetProgramInfoLog(prog, 2048));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /**
     * Pré-calcule une seule fois sur CPU une texture de plasma périodique 512×512 lisse et sans couture (GL_REPEAT) :
     * uniquement utilisée pour les filaments du disque d'accrétion (ZÉRO bruit aléatoire dans l'espace).
     */
    private static synchronized ByteBuffer getOrBuildSeamlessNoisePixels() {
        if (cachedNoisePixels != null) {
            cachedNoisePixels.position(0);
            return cachedNoisePixels;
        }
        ByteBuffer buf = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                float coarse = tileableFbm(x, y, 8, 5, 101);
                float fine = tileableFbm(x, y, 20, 5, 409);
                float ridge = 1.0F - Math.abs(tileableFbm(x, y, 12, 4, 733) * 2.0F - 1.0F);
                buf.put((byte) clampByte(coarse * 255.0F));
                buf.put((byte) clampByte(fine * 255.0F));
                buf.put((byte) clampByte(ridge * 255.0F));
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
