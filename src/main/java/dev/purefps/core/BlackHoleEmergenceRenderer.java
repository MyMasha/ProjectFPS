package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * Rendu OpenGL GLSL 120 temps-réel du Trou Noir relativiste de Schwarzschild (Gargantua) dans Minecraft 1.8.9 :
 * - Remplace intégralement l'écran "Mojang" par la caméra du joueur qui SORT de l'Horizon des Événements
 *   (trajectoire inverse exacte du plongeon caméra du Launcher : r = 1.08 -> sphère de photons r = 1.5 -> orbite r = 16.2).
 * - Poursuit l'émergence sans aucune coupure à 120 FPS dans GuiPureMainMenu pendant que l'interface verre fumé apparaît en fondu.
 * - Libère automatiquement son tampon FBO dès l'entrée dans un monde pour garantir 0 octet de VRAM superflu en jeu.
 */
public final class BlackHoleEmergenceRenderer {

    private static final float MAX_STARTUP_EMERGENCE = 0.58F;
    private static final float MENU_EMERGENCE_DURATION_MS = 2050.0F;
    private static final long STARTUP_FRAME_MIN_INTERVAL_NS = 28_000_000L; // ~35 FPS fluides pendant le chargement CPU
    private static final int MAX_MENU_FBO_HEIGHT = 864;

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
            + "\n"
            + "float hash21(vec2 p) {\n"
            + "    p = fract(p * vec2(234.34, 435.345));\n"
            + "    p += dot(p, p + 34.23);\n"
            + "    return fract(p.x * p.y);\n"
            + "}\n"
            + "\n"
            + "vec3 hash33(vec3 p) {\n"
            + "    p = fract(p * vec3(0.1031, 0.1030, 0.0973));\n"
            + "    p += dot(p, p.yxz + 33.33);\n"
            + "    return fract((p.xxy + p.yxx) * p.zyx);\n"
            + "}\n"
            + "\n"
            + "float noise(vec2 p) {\n"
            + "    vec2 i = floor(p);\n"
            + "    vec2 f = fract(p);\n"
            + "    vec2 u = f * f * (3.0 - 2.0 * f);\n"
            + "    float a = hash21(i);\n"
            + "    float b = hash21(i + vec2(1.0, 0.0));\n"
            + "    float c = hash21(i + vec2(0.0, 1.0));\n"
            + "    float d = hash21(i + vec2(1.0, 1.0));\n"
            + "    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n"
            + "}\n"
            + "\n"
            + "float fbm(vec2 p) {\n"
            + "    float v = 0.0;\n"
            + "    float amp = 0.54;\n"
            + "    mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "        v += amp * noise(p);\n"
            + "        p = rot * p * 2.04 + vec2(17.1, 31.7);\n"
            + "        amp *= 0.5;\n"
            + "    }\n"
            + "    return v;\n"
            + "}\n"
            + "\n"
            + "vec3 sampleStarfield(vec3 dir, float warpBoost) {\n"
            + "    vec3 d = normalize(dir);\n"
            + "    vec3 stars = vec3(0.0);\n"
            + "\n"
            + "    vec3 sc1 = d * 78.0;\n"
            + "    vec3 id1 = floor(sc1);\n"
            + "    vec3 f1 = fract(sc1) - 0.5;\n"
            + "    vec3 rnd1 = hash33(id1);\n"
            + "    if (rnd1.x > 0.945) {\n"
            + "        vec3 jitter = (rnd1 - 0.5) * 0.58;\n"
            + "        float dist = length(f1 - jitter);\n"
            + "        float core = max(smoothstep(0.14 + warpBoost * 0.07, 0.0, dist), 0.0001);\n"
            + "        vec3 tint = mix(vec3(1.0, 0.88, 0.70), vec3(0.76, 0.87, 1.0), rnd1.y);\n"
            + "        stars += tint * pow(core, 1.6) * (1.8 + warpBoost * 2.8) * (0.5 + rnd1.z);\n"
            + "    }\n"
            + "\n"
            + "    vec3 sc2 = d * 152.0 + vec3(19.3, 7.1, 41.9);\n"
            + "    vec3 id2 = floor(sc2);\n"
            + "    vec3 f2 = fract(sc2) - 0.5;\n"
            + "    vec3 rnd2 = hash33(id2);\n"
            + "    if (rnd2.x > 0.955) {\n"
            + "        float dist2 = length(f2 - (rnd2 - 0.5) * 0.5);\n"
            + "        float core2 = max(smoothstep(0.12, 0.0, dist2), 0.0001);\n"
            + "        stars += vec3(0.90, 0.89, 0.87) * pow(core2, 1.5) * (0.75 + warpBoost * 1.3);\n"
            + "    }\n"
            + "\n"
            + "    float dust = fbm(d.xz * 3.5 + vec2(d.y * 2.2, -1.1));\n"
            + "    float galPlane = exp(-abs(d.y + 0.14 * d.x) * 4.8);\n"
            + "    stars += vec3(0.048, 0.032, 0.020) * dust * galPlane;\n"
            + "    return stars;\n"
            + "}\n"
            + "\n"
            + "vec4 evaluateAccretionDisk(vec3 hit, vec3 rayDir, float spinTime, float warpBoost) {\n"
            + "    float r = length(hit.xz);\n"
            + "    float rIn = 1.80;\n"
            + "    float rOut = 9.60;\n"
            + "    if (r < rIn || r > rOut) {\n"
            + "        return vec4(0.0);\n"
            + "    }\n"
            + "\n"
            + "    float radialNorm = clamp((r - rIn) / (rOut - rIn), 0.0, 1.0);\n"
            + "    float innerEdge = smoothstep(rIn, rIn + 0.34, r);\n"
            + "    float outerEdge = 1.0 - smoothstep(rOut - 3.6, rOut, r);\n"
            + "    float profile = innerEdge * outerEdge * exp(-radialNorm * 1.38);\n"
            + "\n"
            + "    vec2 unitXZ = hit.xz / max(r, 0.0001);\n"
            + "    float keplerPhase = spinTime * (2.75 / pow(max(r, 1.35), 1.26)) + 3.8 / (r + 0.40);\n"
            + "    float cs = cos(keplerPhase);\n"
            + "    float sn = sin(keplerPhase);\n"
            + "    vec2 spunDir = vec2(unitXZ.x * cs - unitXZ.y * sn, unitXZ.x * sn + unitXZ.y * cs);\n"
            + "\n"
            + "    float phase2 = -spinTime * 0.65 + 6.4 / (r + 0.55);\n"
            + "    float cs2 = cos(phase2);\n"
            + "    float sn2 = sin(phase2);\n"
            + "    vec2 spunDir2 = vec2(unitXZ.x * cs2 - unitXZ.y * sn2, unitXZ.x * sn2 + unitXZ.y * cs2);\n"
            + "\n"
            + "    float turbCoarse = fbm(spunDir * 2.6 + unitXZ * (r * 0.42));\n"
            + "    float turbFine   = fbm(spunDir2 * 5.6 + vec2(r * 1.85, -r * 1.35));\n"
            + "    float ringMod    = fbm(vec2(r * 4.2 - spinTime * 0.18, turbCoarse * 2.4));\n"
            + "    float shear      = 0.55 * turbCoarse + 0.45 * turbFine;\n"
            + "    float filaments  = smoothstep(0.18, 0.84, shear) * (0.62 + 0.58 * ringMod);\n"
            + "\n"
            + "    float density = profile * (0.34 + 1.12 * filaments);\n"
            + "\n"
            + "    vec3 tangentVel = normalize(vec3(-hit.z, 0.0, hit.x));\n"
            + "    float beta = clamp(0.54 / sqrt(max(r - 0.85, 0.35)), 0.0, 0.65);\n"
            + "    float cosTheta = dot(tangentVel, -rayDir);\n"
            + "    float doppler = 1.0 / (1.0 - beta * cosTheta);\n"
            + "    float dopplerNorm = max(clamp((doppler - 0.60) / 1.36, 0.0, 1.0), 0.0001);\n"
            + "    float beaming = mix(0.46, 3.45, pow(dopplerNorm, 1.25));\n"
            + "\n"
            + "    vec3 hotIsco    = vec3(1.52, 1.32, 0.98);\n"
            + "    vec3 warmGold   = vec3(1.10, 0.66, 0.22);\n"
            + "    vec3 deepCopper = vec3(0.64, 0.19, 0.042);\n"
            + "\n"
            + "    vec3 thermal = mix(hotIsco, warmGold, smoothstep(0.0, 0.32, radialNorm));\n"
            + "    thermal = mix(thermal, deepCopper, smoothstep(0.28, 0.92, radialNorm));\n"
            + "\n"
            + "    float blueShift = clamp((doppler - 0.86) * 0.95, 0.0, 1.0);\n"
            + "    vec3 color = mix(thermal * vec3(1.05, 0.72, 0.44), thermal * vec3(1.06, 1.04, 1.05), blueShift);\n"
            + "\n"
            + "    float hdrRadiance = density * beaming * (2.55 + warpBoost * 1.55);\n"
            + "    float alpha = clamp(density * 1.45, 0.0, 0.96);\n"
            + "    return vec4(color * hdrRadiance, alpha);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / max(min(uRes.x, uRes.y), 1.0);\n"
            + "\n"
            + "    float e = clamp(uEmergence, 0.0, 1.0);\n"
            + "    float inv = 1.0 - e;\n"
            + "    float eSmooth = e * e * (3.0 - 2.0 * e);\n"
            + "    float warpBoost = 1.0 - eSmooth;\n"
            + "    float eCurve = (e <= 0.001) ? 0.0 : pow(e, 2.10);\n"
            + "    float invCurve = (inv <= 0.001) ? 0.0 : pow(inv, 1.72);\n"
            + "\n"
            + "    // Émergence directe depuis l'intérieur de l'Horizon des Événements (r = 1.08 -> sphère de photons -> r = 16.2)\n"
            + "    float camDist = mix(1.08, 16.2, eCurve);\n"
            + "    float elev = mix(0.052, 0.178 + uMouse.y * 0.035 * eSmooth, eSmooth);\n"
            + "    float azim = uTime * 0.068 + uMouse.x * 0.07 * eSmooth - invCurve * 2.35;\n"
            + "\n"
            + "    vec3 ro = vec3(\n"
            + "        camDist * cos(elev) * sin(azim),\n"
            + "        camDist * sin(elev),\n"
            + "        camDist * cos(elev) * cos(azim)\n"
            + "    );\n"
            + "\n"
            + "    vec3 forward = normalize(-ro);\n"
            + "    float rollAngle = mix(-0.62, -0.165, eSmooth);\n"
            + "    vec3 worldUp = vec3(sin(rollAngle), cos(rollAngle), 0.0);\n"
            + "    vec3 right = normalize(cross(forward, worldUp));\n"
            + "    vec3 up = cross(right, forward);\n"
            + "\n"
            + "    // Décompression grand-angle relativiste lors de la sortie du puits gravitationnel\n"
            + "    float focal = mix(0.54, 1.56, smoothstep(0.03, 0.96, e));\n"
            + "    vec3 rd = normalize(forward * focal + right * uv.x + up * uv.y);\n"
            + "\n"
            + "    vec3 hVec = cross(ro, rd);\n"
            + "    float h2 = dot(hVec, hVec);\n"
            + "\n"
            + "    vec3 pos = ro;\n"
            + "    vec3 vel = rd;\n"
            + "    vec3 diskAccum = vec3(0.0);\n"
            + "    vec3 haloAccum = vec3(0.0);\n"
            + "    float transmittance = 1.0;\n"
            + "    bool swallowed = false;\n"
            + "    float minR = 100.0;\n"
            + "\n"
            + "    float spinClock = uTime * 0.78 + (1.0 - invCurve) * 4.2;\n"
            + "\n"
            + "    for (int i = 0; i < 78; i++) {\n"
            + "        float r = length(pos);\n"
            + "        minR = min(minR, r);\n"
            + "\n"
            + "        if (r <= 1.015) {\n"
            + "            swallowed = true;\n"
            + "            break;\n"
            + "        }\n"
            + "        if (r > 28.0 && i > 12) {\n"
            + "            break;\n"
            + "        }\n"
            + "\n"
            + "        float dt = clamp(0.136 * (r - 0.86), 0.044, 0.78);\n"
            + "        vec3 prevPos = pos;\n"
            + "\n"
            + "        float r2 = r * r;\n"
            + "        vec3 a1 = -1.5 * h2 * pos / (r2 * r2 * r);\n"
            + "        vec3 midVel = normalize(vel + a1 * (0.5 * dt));\n"
            + "        vec3 midPos = pos + midVel * (0.5 * dt);\n"
            + "        float mr = max(length(midPos), 1.001);\n"
            + "        float mr2 = mr * mr;\n"
            + "        vec3 a2 = -1.5 * h2 * midPos / (mr2 * mr2 * mr);\n"
            + "\n"
            + "        vel = normalize(vel + a2 * dt);\n"
            + "        pos = prevPos + midVel * dt;\n"
            + "\n"
            + "        if (prevPos.y * pos.y <= 0.0) {\n"
            + "            float denom = prevPos.y - pos.y;\n"
            + "            float frac = abs(denom) > 0.000001 ? clamp(prevPos.y / denom, 0.0, 1.0) : 0.5;\n"
            + "            vec3 hit = mix(prevPos, pos, frac);\n"
            + "            vec4 diskSample = evaluateAccretionDisk(hit, vel, spinClock, warpBoost);\n"
            + "            diskAccum += transmittance * diskSample.rgb;\n"
            + "            transmittance *= (1.0 - diskSample.a);\n"
            + "            if (transmittance < 0.02) {\n"
            + "                break;\n"
            + "            }\n"
            + "        }\n"
            + "\n"
            + "        float photonShell = exp(-abs(r - 1.54) * 4.2) * dt * 0.098;\n"
            + "        haloAccum += transmittance * vec3(1.0, 0.76, 0.42) * photonShell;\n"
            + "    }\n"
            + "\n"
            + "    vec3 accumColor = diskAccum;\n"
            + "    if (!swallowed) {\n"
            + "        float einsteinRing = exp(-abs(minR - 1.505) * 22.0);\n"
            + "        float outerBloom = exp(-max(minR - 1.50, 0.0) * 1.85) * 0.15;\n"
            + "        accumColor += haloAccum;\n"
            + "        accumColor += transmittance * (vec3(1.48, 1.24, 0.90) * einsteinRing * 1.65 + vec3(0.92, 0.54, 0.18) * outerBloom);\n"
            + "        accumColor += transmittance * sampleStarfield(vel, warpBoost);\n"
            + "    }\n"
            + "\n"
            + "    // Éclat photonique lors du franchissement vers l'extérieur de la sphère de photons (r ~ 1.5)\n"
            + "    float crossingDelta = (e - 0.17) / 0.11;\n"
            + "    float photonCrossing = exp(-crossingDelta * crossingDelta) * 0.24 * (1.0 - smoothstep(0.0, 0.55, length(uv)));\n"
            + "    accumColor += vec3(1.15, 0.86, 0.48) * photonCrossing;\n"
            + "\n"
            + "    vec3 x = max(vec3(0.0), accumColor);\n"
            + "    vec3 mapped = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);\n"
            + "    mapped = pow(max(clamp(mapped, 0.0, 1.0), vec3(0.0001)), vec3(0.90));\n"
            + "\n"
            + "    // Ouverture progressive depuis le noir absolu #000000 de l'Horizon des Événements\n"
            + "    float horizonOpen = smoothstep(0.005, 0.15, e);\n"
            + "    mapped *= horizonOpen;\n"
            + "\n"
            + "    gl_FragColor = vec4(mapped, 1.0);\n"
            + "}\n";

    private static int shaderState = 0; // 0 = non initialisé, 1 = prêt, -1 = fallback
    private static int programId = 0;
    private static int locRes = -1;
    private static int locTime = -1;
    private static int locEmergence = -1;
    private static int locMouse = -1;

    private static Framebuffer menuFbo = null;
    private static int menuFboW = 0;
    private static int menuFboH = 0;

    private static long bootNanoTime = System.nanoTime();
    private static long lastStartupRenderNs = 0L;
    private static float startupMilestone = 0.0F;
    private static float currentEmergence = 0.0F;

    private static boolean menuEntered = false;
    private static boolean menuEmergenceDone = false;
    private static long menuEnterMs = 0L;
    private static float menuStartEmergence = 0.24F;

    private static float smoothMouseX = 0.0F;
    private static float smoothMouseY = 0.0F;

    private BlackHoleEmergenceRenderer() {
    }

    static void resetTimelineForTest() {
        bootNanoTime = 0L;
        lastStartupRenderNs = 0L;
        startupMilestone = 0.0F;
        currentEmergence = 0.0F;
        menuEntered = false;
        menuEmergenceDone = false;
        menuEnterMs = 0L;
        menuStartEmergence = 0.24F;
    }

    public static void beginStartup() {
        bootNanoTime = System.nanoTime();
        lastStartupRenderNs = 0L;
        startupMilestone = 0.0F;
        currentEmergence = 0.0F;
        menuEntered = false;
        menuEmergenceDone = false;
        menuEnterMs = 0L;
    }

    static float computeStartupEmergence(float milestoneTarget, float elapsedSeconds) {
        float clampedTarget = Math.max(0.0F, Math.min(MAX_STARTUP_EMERGENCE, milestoneTarget));
        float timeDriven = Math.max(0.0F, Math.min(MAX_STARTUP_EMERGENCE, elapsedSeconds * 0.135F));
        return Math.max(clampedTarget, timeDriven);
    }

    static float advanceMenuEmergenceAt(long nowMs) {
        if (menuEmergenceDone) {
            currentEmergence = 1.0F;
            return 1.0F;
        }
        if (!menuEntered) {
            menuEntered = true;
            menuEnterMs = nowMs;
            menuStartEmergence = Math.max(0.18F, Math.min(MAX_STARTUP_EMERGENCE, currentEmergence));
            currentEmergence = menuStartEmergence;
            return currentEmergence;
        }
        long elapsed = Math.max(0L, nowMs - menuEnterMs);
        if (elapsed >= (long) MENU_EMERGENCE_DURATION_MS) {
            menuEmergenceDone = true;
            currentEmergence = 1.0F;
            return 1.0F;
        }
        float t = Math.max(0.0F, Math.min(1.0F, (float) elapsed / MENU_EMERGENCE_DURATION_MS));
        float inv = 1.0F - t;
        float easeOutCubic = 1.0F - (inv * inv * inv);
        currentEmergence = menuStartEmergence + (1.0F - menuStartEmergence) * easeOutCubic;
        return currentEmergence;
    }

    /**
     * Opacité [0.0 .. 1.0] des cartes UI du menu principal pendant que la caméra finit de sortir du Trou Noir.
     */
    public static float computeMenuUiAlpha(float emergence) {
        if (emergence >= 0.96F) {
            return 1.0F;
        }
        if (emergence <= 0.56F) {
            return 0.0F;
        }
        float x = (emergence - 0.56F) / (0.96F - 0.56F);
        return x * x * (3.0F - 2.0F * x);
    }

    public static float getCurrentEmergence() {
        return currentEmergence;
    }

    /**
     * Rendu d'une étape de sortie du Trou Noir pendant startGame() (en remplacement total de l'écran Mojang).
     */
    public static void renderStartupCheckpoint(Minecraft mc, float minProgress, boolean forceFrame) {
        if (mc == null || !Display.isCreated()) {
            return;
        }
        long nowNs = System.nanoTime();
        if (!forceFrame && lastStartupRenderNs != 0L && (nowNs - lastStartupRenderNs) < STARTUP_FRAME_MIN_INTERVAL_NS) {
            return;
        }
        lastStartupRenderNs = nowNs;

        if (minProgress > startupMilestone) {
            startupMilestone = Math.min(MAX_STARTUP_EMERGENCE, minProgress);
        }
        float elapsedSec = Math.max(0.0F, (float) (nowNs - bootNanoTime) * 1.0e-9F);
        currentEmergence = Math.max(currentEmergence, computeStartupEmergence(startupMilestone, elapsedSec));

        try {
            Display.processMessages();
        } catch (Throwable ignored) {
        }

        int w = Math.max(1, mc.displayWidth);
        int h = Math.max(1, mc.displayHeight);

        try {
            if (OpenGlHelper.isFramebufferEnabled()) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
            }
            GL11.glViewport(0, 0, w, h);
            if (!drawShaderPass(w, h, elapsedSec, currentEmergence, 0.0F, 0.0F)) {
                GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            }
            Display.update();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Rendu 120 FPS de l'arrière-plan Trou Noir dans GuiPureMainMenu et retourne l'opacité [0..1] de l'interface.
     */
    public static float renderMenuBackground(Minecraft mc, int guiWidth, int guiHeight, int mouseX, int mouseY) {
        float emergence = advanceMenuEmergenceAt(System.currentTimeMillis());
        float uiAlpha = computeMenuUiAlpha(emergence);

        float targetMx = guiWidth > 0 ? ((float) mouseX / (float) guiWidth) * 2.0F - 1.0F : 0.0F;
        float targetMy = guiHeight > 0 ? ((float) mouseY / (float) guiHeight) * 2.0F - 1.0F : 0.0F;
        smoothMouseX += (targetMx - smoothMouseX) * 0.06F;
        smoothMouseY += (targetMy - smoothMouseY) * 0.06F;

        float timeSec = Math.max(0.0F, (float) (System.nanoTime() - bootNanoTime) * 1.0e-9F);
        int dispW = mc != null ? Math.max(1, mc.displayWidth) : Math.max(1, guiWidth * 2);
        int dispH = mc != null ? Math.max(1, mc.displayHeight) : Math.max(1, guiHeight * 2);

        if (!ensureShader()) {
            Gui.drawRect(0, 0, guiWidth, guiHeight, 0xFF020306);
            return uiAlpha;
        }

        // Rendu mis à l'échelle (<= 864p, filtrage bilinéaire) pour tenir 120 FPS constants avec < 0.7 ms GPU
        if (OpenGlHelper.isFramebufferEnabled() && dispH > MAX_MENU_FBO_HEIGHT && mc != null && mc.getFramebuffer() != null) {
            float scale = (float) MAX_MENU_FBO_HEIGHT / (float) dispH;
            int targetW = Math.max(320, Math.round(dispW * scale));
            int targetH = MAX_MENU_FBO_HEIGHT;
            Framebuffer fbo = ensureMenuFbo(targetW, targetH);
            if (fbo != null) {
                fbo.bindFramebuffer(true);
                drawShaderPass(targetW, targetH, timeSec, emergence, smoothMouseX, -smoothMouseY);
                mc.getFramebuffer().bindFramebuffer(true);
                drawFboQuadToGui(fbo, guiWidth, guiHeight);
                return uiAlpha;
            }
        }

        GL11.glViewport(0, 0, dispW, dispH);
        drawShaderPass(dispW, dispH, timeSec, emergence, smoothMouseX, -smoothMouseY);
        return uiAlpha;
    }

    public static void releaseMenuResources() {
        if (menuFbo != null) {
            try {
                menuFbo.deleteFramebuffer();
            } catch (Throwable ignored) {
            }
            menuFbo = null;
            menuFboW = 0;
            menuFboH = 0;
        }
    }

    private static Framebuffer ensureMenuFbo(int w, int h) {
        try {
            if (menuFbo == null || menuFboW != w || menuFboH != h) {
                if (menuFbo != null) {
                    menuFbo.deleteFramebuffer();
                }
                menuFbo = new Framebuffer(w, h, false);
                menuFbo.setFramebufferFilter(GL11.GL_LINEAR);
                menuFboW = w;
                menuFboH = h;
            }
            return menuFbo;
        } catch (Throwable t) {
            menuFbo = null;
            return null;
        }
    }

    private static void drawFboQuadToGui(Framebuffer fbo, int guiWidth, int guiHeight) {
        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        fbo.bindFramebufferTexture();

        float uMax = (float) fbo.framebufferWidth / (float) fbo.framebufferTextureWidth;
        float vMax = (float) fbo.framebufferHeight / (float) fbo.framebufferTextureHeight;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(0.0D, guiHeight, 0.0D).tex(0.0D, 0.0D).endVertex();
        wr.pos(guiWidth, guiHeight, 0.0D).tex(uMax, 0.0D).endVertex();
        wr.pos(guiWidth, 0.0D, 0.0D).tex(uMax, vMax).endVertex();
        wr.pos(0.0D, 0.0D, 0.0D).tex(0.0D, vMax).endVertex();
        tess.draw();

        fbo.unbindFramebufferTexture();
        GlStateManager.enableAlpha();
    }

    private static boolean drawShaderPass(int pixelW, int pixelH, float timeSec, float emergence, float mx, float my) {
        if (!ensureShader()) {
            return false;
        }
        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GL20.glUseProgram(programId);
        GL20.glUniform2f(locRes, (float) pixelW, (float) pixelH);
        GL20.glUniform1f(locTime, timeSec);
        GL20.glUniform1f(locEmergence, emergence);
        GL20.glUniform2f(locMouse, mx, my);

        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(-1.0F, -1.0F);
        GL11.glVertex2f(3.0F, -1.0F);
        GL11.glVertex2f(-1.0F, 3.0F);
        GL11.glEnd();

        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        return true;
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
            locRes = GL20.glGetUniformLocation(prog, "uRes");
            locTime = GL20.glGetUniformLocation(prog, "uTime");
            locEmergence = GL20.glGetUniformLocation(prog, "uEmergence");
            locMouse = GL20.glGetUniformLocation(prog, "uMouse");
            shaderState = 1;
            return true;
        } catch (Throwable t) {
            shaderState = -1;
            return false;
        }
    }
}
