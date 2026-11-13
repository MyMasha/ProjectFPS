package dev.purefps.config;

import dev.purefps.core.GpuUploadBudget;
import dev.purefps.core.LightmapStateCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.opengl.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration unifiée PureClient 3.5 en champs statiques directs (zéro indirection dans les boucles de rendu).
 * Partagée en temps réel avec le Launcher Bureau natif (PureClient-Launcher.exe).
 */
public final class PureFPSConfig {

    public static PureFPSProfile activeProfile = PureFPSProfile.BALANCED;
    public static boolean enabled = true;

    // CPU, Rendu Chunk & Mathématiques
    public static boolean fastMath = true;
    public static boolean fastWorldVertexSort = true;
    public static boolean fastChunkVisGraph = true;
    public static boolean lazyTerrainSetup = true;
    public static boolean zeroAllocEnumCache = true;
    public static boolean zeroAllocSmoothLighting = true;
    public static boolean fastBiomeColors = true;
    public static boolean multiThreadedChunkBuild = true;
    public static int chunkWorkerThreads = 0; // 0 = Auto (3 sur Ryzen 5 5500)
    public static boolean disableWorldLoadGC = true;
    public static boolean skipVoidFogLoop = true;

    // GPU MultiDraw, VAO GL30, Zéro glGetError, FBO Blit GL30, Frustum P-Vertex & UI
    public static boolean disableGlErrorChecks = true;
    public static boolean regionMultiDraw = true;
    public static boolean hardwareRegionVao = true;
    public static boolean fastFboBlit = true;
    public static boolean fastFrustumPVertex = true;
    public static boolean asyncSkinUploadPacing = true;
    public static boolean fastItemRender = true;
    public static boolean limitDistantGlint = true;
    public static boolean fastScoreboard = true;
    public static boolean smartAnimations = true;
    public static boolean fastFontRenderer = true;
    public static boolean fastSkullRendering = true;
    public static boolean fastLightmapCoords = true;
    public static boolean disableVignette = true;

    // Culling & Entités
    public static boolean entityOcclusionCulling = true;
    public static boolean entityDistanceCulling = true;
    public static int maxEntityRenderDistance = 64;
    public static boolean fastDroppedItems = true;

    public static boolean tileEntityFrustumCulling = true;
    public static boolean tileEntityDistanceCulling = true;
    public static int maxTileEntityRenderDistance = 48;

    public static boolean fastArmorStands = true;
    public static boolean fastNametags = true;
    public static boolean transparentNametags = false;
    public static boolean nametagDistanceCulling = true;
    public static int maxNametagDistance = 32;

    // Particules & Monde
    public static boolean particleFrustumCulling = true;
    public static boolean fastParticlePhysics = true;
    public static int maxParticlesPerLayer = 1000;
    public static boolean fastBlockBreakParticles = true;
    public static boolean skipClientSkylightUpdates = false;

    // Fenêtre & Économie GPU
    public static boolean unfocusedFpsLimiter = true;
    public static int unfocusedFpsLimit = 15;
    public static boolean showF3Stats = true;

    // Modules PureClient 3.5 (Lunar / Badlion / OneClient Grade)
    public static boolean customMainMenu = true;
    public static boolean smoothClientFont = true;
    public static boolean hudEnabled = true;
    public static boolean hudFpsCps = true;
    public static boolean hudPing = true;
    public static boolean hudSprintStatus = true;
    public static boolean hudKeystrokes = false;
    public static boolean hudArmor = true;

    public static boolean toggleSprint = true;
    public static boolean sprintToggledActive = true;
    public static boolean zoomEnabled = true;
    public static boolean zoomSmoothCamera = false;
    public static boolean fullbright = true;
    public static boolean lowFire = true;
    public static boolean noHurtCam = false;
    public static boolean staticFov = false;
    public static boolean transparentChat = false;

    // Positions HUD (en pixels ScaledResolution, déplaçables via GuiHudEditor)
    public static int hudFpsX = 8;
    public static int hudFpsY = 8;
    public static int hudPingX = 8;
    public static int hudPingY = 44;
    public static int hudSprintX = 8;
    public static int hudSprintY = 66;
    public static int hudKeystrokesX = 8;
    public static int hudKeystrokesY = 92;

    // Compteurs de télémétrie par frame / tick
    public static int culledEntitiesLastFrame = 0;
    public static int culledTileEntitiesLastFrame = 0;
    public static int culledParticlesLastFrame = 0;
    public static int multiDrawsLastFrame = 0;
    public static int chunksBatchedLastFrame = 0;

    public static int frameEntitiesAccumulator = 0;
    public static int frameTileEntitiesAccumulator = 0;
    public static int frameParticlesAccumulator = 0;
    public static int frameMultiDrawsAccumulator = 0;
    public static int frameChunksBatchedAccumulator = 0;

    public static int activeChunkThreads = 2;
    public static int skippedAnimationsLastTick = 0;
    public static int totalAnimationsCount = 0;

    private static File configFile;

    private PureFPSConfig() {
    }

    public static void applyProfile(PureFPSProfile profile, boolean syncVanillaGameSettings) {
        if (profile == null || profile == PureFPSProfile.CUSTOM) {
            activeProfile = PureFPSProfile.CUSTOM;
            return;
        }

        // Socle commun 100 % sans perte visuelle : actif sur les 3 profils
        enabled = true;
        disableGlErrorChecks = true;
        fastMath = true;
        fastWorldVertexSort = true;
        fastChunkVisGraph = true;
        lazyTerrainSetup = true;
        zeroAllocEnumCache = true;
        zeroAllocSmoothLighting = true;
        fastBiomeColors = true;
        multiThreadedChunkBuild = true;
        chunkWorkerThreads = 0;
        disableWorldLoadGC = true;
        skipVoidFogLoop = true;
        regionMultiDraw = true;
        hardwareRegionVao = true;
        fastFboBlit = true;
        fastFrustumPVertex = true;
        asyncSkinUploadPacing = true;
        fastItemRender = true;
        fastScoreboard = true;
        fastFontRenderer = true;
        fastSkullRendering = true;
        fastLightmapCoords = true;
        fastArmorStands = true;
        tileEntityFrustumCulling = true;
        particleFrustumCulling = true;
        unfocusedFpsLimiter = true;

        switch (profile) {
            case FPS:
                smartAnimations = true;
                limitDistantGlint = true;
                disableVignette = true;
                entityOcclusionCulling = true;
                entityDistanceCulling = true;
                maxEntityRenderDistance = 48;
                fastDroppedItems = true;
                tileEntityDistanceCulling = true;
                maxTileEntityRenderDistance = 32;
                fastNametags = true;
                nametagDistanceCulling = true;
                maxNametagDistance = 24;
                fastParticlePhysics = true;
                maxParticlesPerLayer = 250;
                fastBlockBreakParticles = true;
                skipClientSkylightUpdates = true;
                unfocusedFpsLimit = 10;
                break;

            case BALANCED:
                smartAnimations = true;
                limitDistantGlint = true;
                disableVignette = true;
                entityOcclusionCulling = true;
                entityDistanceCulling = true;
                maxEntityRenderDistance = 64;
                fastDroppedItems = true;
                tileEntityDistanceCulling = true;
                maxTileEntityRenderDistance = 48;
                fastNametags = true;
                nametagDistanceCulling = true;
                maxNametagDistance = 32;
                fastParticlePhysics = true;
                maxParticlesPerLayer = 1000;
                fastBlockBreakParticles = true;
                skipClientSkylightUpdates = false;
                unfocusedFpsLimit = 15;
                break;

            case QUALITY:
                smartAnimations = false;
                limitDistantGlint = false;
                disableVignette = false;
                entityOcclusionCulling = false;
                entityDistanceCulling = false;
                maxEntityRenderDistance = 128;
                fastDroppedItems = false;
                tileEntityDistanceCulling = false;
                maxTileEntityRenderDistance = 128;
                fastNametags = false;
                nametagDistanceCulling = false;
                maxNametagDistance = 64;
                fastParticlePhysics = false;
                maxParticlesPerLayer = 4000;
                fastBlockBreakParticles = false;
                skipClientSkylightUpdates = false;
                unfocusedFpsLimit = 30;
                break;

            default:
                break;
        }

        activeProfile = profile;

        if (syncVanillaGameSettings) {
            applyVanillaGraphicsForProfile(profile);
        }
    }

    private static void applyVanillaGraphicsForProfile(PureFPSProfile profile) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        GameSettings gs = mc.gameSettings;
        boolean prevFancy = gs.fancyGraphics;
        int prevAo = gs.ambientOcclusion;
        boolean prevVbo = gs.useVbo;
        boolean prevVsync = gs.enableVsync;

        if (OpenGlHelper.vboSupported) {
            gs.useVbo = true;
        }

        if (profile == PureFPSProfile.FPS) {
            gs.fancyGraphics = false;
            gs.ambientOcclusion = 0;
            gs.clouds = 0;
            gs.particleSetting = 2;
            gs.entityShadows = false;
            gs.enableVsync = false;
            gs.limitFramerate = 260;
        } else if (profile == PureFPSProfile.BALANCED) {
            gs.fancyGraphics = true;
            gs.ambientOcclusion = 1;
            gs.clouds = 1;
            gs.particleSetting = 1;
            gs.entityShadows = false;
            gs.enableVsync = false;
            gs.limitFramerate = 260;
        } else if (profile == PureFPSProfile.QUALITY) {
            gs.fancyGraphics = true;
            gs.ambientOcclusion = 2;
            gs.clouds = 2;
            gs.particleSetting = 0;
            gs.entityShadows = true;
        }

        if (prevVsync != gs.enableVsync && Display.isCreated()) {
            Display.setVSyncEnabled(gs.enableVsync);
        }
        if ((prevFancy != gs.fancyGraphics || prevAo != gs.ambientOcclusion || prevVbo != gs.useVbo)
                && mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
        gs.saveOptions();
    }

    public static void refreshProfileAfterManualEdit() {
        if (matchesCoreLosslessBase()) {
            if (smartAnimations && limitDistantGlint && disableVignette && entityOcclusionCulling && entityDistanceCulling
                    && maxEntityRenderDistance == 48 && fastDroppedItems && tileEntityDistanceCulling
                    && maxTileEntityRenderDistance == 32 && fastNametags && nametagDistanceCulling && maxNametagDistance == 24
                    && fastParticlePhysics && maxParticlesPerLayer == 250 && fastBlockBreakParticles
                    && skipClientSkylightUpdates && unfocusedFpsLimit == 10) {
                activeProfile = PureFPSProfile.FPS;
                return;
            }
            if (smartAnimations && limitDistantGlint && disableVignette && entityOcclusionCulling && entityDistanceCulling
                    && maxEntityRenderDistance == 64 && fastDroppedItems && tileEntityDistanceCulling
                    && maxTileEntityRenderDistance == 48 && fastNametags && nametagDistanceCulling && maxNametagDistance == 32
                    && fastParticlePhysics && maxParticlesPerLayer == 1000 && fastBlockBreakParticles
                    && !skipClientSkylightUpdates && unfocusedFpsLimit == 15) {
                activeProfile = PureFPSProfile.BALANCED;
                return;
            }
            if (!smartAnimations && !limitDistantGlint && !disableVignette && !entityOcclusionCulling && !entityDistanceCulling
                    && maxEntityRenderDistance == 128 && !fastDroppedItems && !tileEntityDistanceCulling
                    && maxTileEntityRenderDistance == 128 && !fastNametags && !nametagDistanceCulling && maxNametagDistance == 64
                    && !fastParticlePhysics && maxParticlesPerLayer == 4000 && !fastBlockBreakParticles
                    && !skipClientSkylightUpdates && unfocusedFpsLimit == 30) {
                activeProfile = PureFPSProfile.QUALITY;
                return;
            }
        }
        activeProfile = PureFPSProfile.CUSTOM;
    }

    private static boolean matchesCoreLosslessBase() {
        return enabled && disableGlErrorChecks && fastMath && fastWorldVertexSort && fastChunkVisGraph
                && lazyTerrainSetup && zeroAllocEnumCache
                && zeroAllocSmoothLighting && fastBiomeColors && multiThreadedChunkBuild && chunkWorkerThreads == 0
                && disableWorldLoadGC && skipVoidFogLoop && regionMultiDraw && hardwareRegionVao
                && fastFboBlit && fastFrustumPVertex
                && asyncSkinUploadPacing && fastItemRender && fastScoreboard
                && fastFontRenderer && fastSkullRendering && fastLightmapCoords
                && fastArmorStands && tileEntityFrustumCulling && particleFrustumCulling && unfocusedFpsLimiter;
    }

    public static void endFrameStats() {
        culledEntitiesLastFrame = frameEntitiesAccumulator;
        culledTileEntitiesLastFrame = frameTileEntitiesAccumulator;
        culledParticlesLastFrame = frameParticlesAccumulator;
        multiDrawsLastFrame = frameMultiDrawsAccumulator;
        chunksBatchedLastFrame = frameChunksBatchedAccumulator;

        frameEntitiesAccumulator = 0;
        frameTileEntitiesAccumulator = 0;
        frameParticlesAccumulator = 0;
        frameMultiDrawsAccumulator = 0;
        frameChunksBatchedAccumulator = 0;

        LightmapStateCache.invalidate();
        GpuUploadBudget.resetFrame();
    }

    public static int resolveChunkWorkerThreads() {
        if (!enabled || !multiThreadedChunkBuild) {
            return 2;
        }
        long maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int maxSafeForRam = maxMb < 900L ? 2 : (maxMb < 1600L ? 3 : 6);
        if (chunkWorkerThreads > 0) {
            return clamp(chunkWorkerThreads, 2, maxSafeForRam);
        }
        int logicalCores = Runtime.getRuntime().availableProcessors();
        int auto = Math.max(2, Math.min(3, logicalCores / 4));
        return Math.min(auto, maxSafeForRam);
    }

    public static void init(File mcDataDir) {
        File configDir = new File(mcDataDir, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        configFile = new File(configDir, "purefps.properties");
        load();
        save();
    }

    public static void load() {
        if (configFile == null || !configFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(configFile)) {
            props.load(in);
            activeProfile = PureFPSProfile.fromString(props.getProperty("activeProfile", activeProfile.getId()));
            enabled = getBool(props, "enabled", enabled);
            disableGlErrorChecks = getBool(props, "disableGlErrorChecks", disableGlErrorChecks);
            fastMath = getBool(props, "fastMath", fastMath);
            fastWorldVertexSort = getBool(props, "fastWorldVertexSort", fastWorldVertexSort);
            fastChunkVisGraph = getBool(props, "fastChunkVisGraph", fastChunkVisGraph);
            lazyTerrainSetup = getBool(props, "lazyTerrainSetup", lazyTerrainSetup);
            zeroAllocEnumCache = getBool(props, "zeroAllocEnumCache", zeroAllocEnumCache);
            zeroAllocSmoothLighting = getBool(props, "zeroAllocSmoothLighting", zeroAllocSmoothLighting);
            fastBiomeColors = getBool(props, "fastBiomeColors", fastBiomeColors);
            multiThreadedChunkBuild = getBool(props, "multiThreadedChunkBuild", multiThreadedChunkBuild);
            chunkWorkerThreads = clamp(getInt(props, "chunkWorkerThreads", chunkWorkerThreads), 0, 6);
            disableWorldLoadGC = getBool(props, "disableWorldLoadGC", disableWorldLoadGC);
            skipVoidFogLoop = getBool(props, "skipVoidFogLoop", skipVoidFogLoop);

            regionMultiDraw = getBool(props, "regionMultiDraw", regionMultiDraw);
            hardwareRegionVao = getBool(props, "hardwareRegionVao", hardwareRegionVao);
            fastFboBlit = getBool(props, "fastFboBlit", fastFboBlit);
            fastFrustumPVertex = getBool(props, "fastFrustumPVertex", fastFrustumPVertex);
            asyncSkinUploadPacing = getBool(props, "asyncSkinUploadPacing", asyncSkinUploadPacing);
            fastItemRender = getBool(props, "fastItemRender", fastItemRender);
            limitDistantGlint = getBool(props, "limitDistantGlint", limitDistantGlint);
            fastScoreboard = getBool(props, "fastScoreboard", fastScoreboard);
            smartAnimations = getBool(props, "smartAnimations", smartAnimations);
            fastFontRenderer = getBool(props, "fastFontRenderer", fastFontRenderer);
            fastSkullRendering = getBool(props, "fastSkullRendering", fastSkullRendering);
            fastLightmapCoords = getBool(props, "fastLightmapCoords", fastLightmapCoords);
            disableVignette = getBool(props, "disableVignette", disableVignette);

            entityOcclusionCulling = getBool(props, "entityOcclusionCulling", entityOcclusionCulling);
            entityDistanceCulling = getBool(props, "entityDistanceCulling", entityDistanceCulling);
            maxEntityRenderDistance = clamp(getInt(props, "maxEntityRenderDistance", maxEntityRenderDistance), 16, 160);
            fastDroppedItems = getBool(props, "fastDroppedItems", fastDroppedItems);

            tileEntityFrustumCulling = getBool(props, "tileEntityFrustumCulling", tileEntityFrustumCulling);
            tileEntityDistanceCulling = getBool(props, "tileEntityDistanceCulling", tileEntityDistanceCulling);
            maxTileEntityRenderDistance = clamp(getInt(props, "maxTileEntityRenderDistance", maxTileEntityRenderDistance), 16, 160);

            fastArmorStands = getBool(props, "fastArmorStands", fastArmorStands);
            fastNametags = getBool(props, "fastNametags", fastNametags);
            transparentNametags = getBool(props, "transparentNametags", transparentNametags);
            nametagDistanceCulling = getBool(props, "nametagDistanceCulling", nametagDistanceCulling);
            maxNametagDistance = clamp(getInt(props, "maxNametagDistance", maxNametagDistance), 8, 64);

            particleFrustumCulling = getBool(props, "particleFrustumCulling", particleFrustumCulling);
            fastParticlePhysics = getBool(props, "fastParticlePhysics", fastParticlePhysics);
            maxParticlesPerLayer = clamp(getInt(props, "maxParticlesPerLayer", maxParticlesPerLayer), 100, 4000);
            fastBlockBreakParticles = getBool(props, "fastBlockBreakParticles", fastBlockBreakParticles);
            skipClientSkylightUpdates = getBool(props, "skipClientSkylightUpdates", skipClientSkylightUpdates);

            unfocusedFpsLimiter = getBool(props, "unfocusedFpsLimiter", unfocusedFpsLimiter);
            unfocusedFpsLimit = clamp(getInt(props, "unfocusedFpsLimit", unfocusedFpsLimit), 5, 60);
            showF3Stats = getBool(props, "showF3Stats", showF3Stats);

            customMainMenu = getBool(props, "customMainMenu", customMainMenu);
            smoothClientFont = getBool(props, "smoothClientFont", smoothClientFont);
            hudEnabled = getBool(props, "hudEnabled", hudEnabled);
            hudFpsCps = getBool(props, "hudFpsCps", hudFpsCps);
            hudPing = getBool(props, "hudPing", hudPing);
            hudSprintStatus = getBool(props, "hudSprintStatus", hudSprintStatus);
            hudKeystrokes = getBool(props, "hudKeystrokes", hudKeystrokes);
            hudArmor = getBool(props, "hudArmor", hudArmor);

            toggleSprint = getBool(props, "toggleSprint", toggleSprint);
            sprintToggledActive = getBool(props, "sprintToggledActive", sprintToggledActive);
            zoomEnabled = getBool(props, "zoomEnabled", zoomEnabled);
            zoomSmoothCamera = getBool(props, "zoomSmoothCamera", zoomSmoothCamera);
            fullbright = getBool(props, "fullbright", fullbright);
            lowFire = getBool(props, "lowFire", lowFire);
            noHurtCam = getBool(props, "noHurtCam", noHurtCam);
            staticFov = getBool(props, "staticFov", staticFov);
            transparentChat = getBool(props, "transparentChat", transparentChat);

            hudFpsX = clamp(getInt(props, "hudFpsX", hudFpsX), 2, 3840);
            hudFpsY = clamp(getInt(props, "hudFpsY", hudFpsY), 2, 2160);
            hudPingX = clamp(getInt(props, "hudPingX", hudPingX), 2, 3840);
            hudPingY = clamp(getInt(props, "hudPingY", hudPingY), 2, 2160);
            hudSprintX = clamp(getInt(props, "hudSprintX", hudSprintX), 2, 3840);
            hudSprintY = clamp(getInt(props, "hudSprintY", hudSprintY), 2, 2160);
            hudKeystrokesX = clamp(getInt(props, "hudKeystrokesX", hudKeystrokesX), 2, 3840);
            hudKeystrokesY = clamp(getInt(props, "hudKeystrokesY", hudKeystrokesY), 2, 2160);

            if (props.getProperty("activeProfile") == null) {
                refreshProfileAfterManualEdit();
            }
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        if (configFile == null) {
            File dir = Minecraft.getMinecraft() != null ? Minecraft.getMinecraft().mcDataDir : new File(".");
            configFile = new File(new File(dir, "config"), "purefps.properties");
        }
        Properties props = new Properties();
        props.setProperty("activeProfile", activeProfile.getId());
        props.setProperty("enabled", Boolean.toString(enabled));
        props.setProperty("disableGlErrorChecks", Boolean.toString(disableGlErrorChecks));
        props.setProperty("fastMath", Boolean.toString(fastMath));
        props.setProperty("fastWorldVertexSort", Boolean.toString(fastWorldVertexSort));
        props.setProperty("fastChunkVisGraph", Boolean.toString(fastChunkVisGraph));
        props.setProperty("lazyTerrainSetup", Boolean.toString(lazyTerrainSetup));
        props.setProperty("zeroAllocEnumCache", Boolean.toString(zeroAllocEnumCache));
        props.setProperty("zeroAllocSmoothLighting", Boolean.toString(zeroAllocSmoothLighting));
        props.setProperty("fastBiomeColors", Boolean.toString(fastBiomeColors));
        props.setProperty("multiThreadedChunkBuild", Boolean.toString(multiThreadedChunkBuild));
        props.setProperty("chunkWorkerThreads", Integer.toString(chunkWorkerThreads));
        props.setProperty("disableWorldLoadGC", Boolean.toString(disableWorldLoadGC));
        props.setProperty("skipVoidFogLoop", Boolean.toString(skipVoidFogLoop));

        props.setProperty("regionMultiDraw", Boolean.toString(regionMultiDraw));
        props.setProperty("hardwareRegionVao", Boolean.toString(hardwareRegionVao));
        props.setProperty("fastFboBlit", Boolean.toString(fastFboBlit));
        props.setProperty("fastFrustumPVertex", Boolean.toString(fastFrustumPVertex));
        props.setProperty("asyncSkinUploadPacing", Boolean.toString(asyncSkinUploadPacing));
        props.setProperty("fastItemRender", Boolean.toString(fastItemRender));
        props.setProperty("limitDistantGlint", Boolean.toString(limitDistantGlint));
        props.setProperty("fastScoreboard", Boolean.toString(fastScoreboard));
        props.setProperty("smartAnimations", Boolean.toString(smartAnimations));
        props.setProperty("fastFontRenderer", Boolean.toString(fastFontRenderer));
        props.setProperty("fastSkullRendering", Boolean.toString(fastSkullRendering));
        props.setProperty("fastLightmapCoords", Boolean.toString(fastLightmapCoords));
        props.setProperty("disableVignette", Boolean.toString(disableVignette));

        props.setProperty("entityOcclusionCulling", Boolean.toString(entityOcclusionCulling));
        props.setProperty("entityDistanceCulling", Boolean.toString(entityDistanceCulling));
        props.setProperty("maxEntityRenderDistance", Integer.toString(maxEntityRenderDistance));
        props.setProperty("fastDroppedItems", Boolean.toString(fastDroppedItems));

        props.setProperty("tileEntityFrustumCulling", Boolean.toString(tileEntityFrustumCulling));
        props.setProperty("tileEntityDistanceCulling", Boolean.toString(tileEntityDistanceCulling));
        props.setProperty("maxTileEntityRenderDistance", Integer.toString(maxTileEntityRenderDistance));

        props.setProperty("fastArmorStands", Boolean.toString(fastArmorStands));
        props.setProperty("fastNametags", Boolean.toString(fastNametags));
        props.setProperty("transparentNametags", Boolean.toString(transparentNametags));
        props.setProperty("nametagDistanceCulling", Boolean.toString(nametagDistanceCulling));
        props.setProperty("maxNametagDistance", Integer.toString(maxNametagDistance));

        props.setProperty("particleFrustumCulling", Boolean.toString(particleFrustumCulling));
        props.setProperty("fastParticlePhysics", Boolean.toString(fastParticlePhysics));
        props.setProperty("maxParticlesPerLayer", Integer.toString(maxParticlesPerLayer));
        props.setProperty("fastBlockBreakParticles", Boolean.toString(fastBlockBreakParticles));
        props.setProperty("skipClientSkylightUpdates", Boolean.toString(skipClientSkylightUpdates));

        props.setProperty("unfocusedFpsLimiter", Boolean.toString(unfocusedFpsLimiter));
        props.setProperty("unfocusedFpsLimit", Integer.toString(unfocusedFpsLimit));
        props.setProperty("showF3Stats", Boolean.toString(showF3Stats));

        props.setProperty("customMainMenu", Boolean.toString(customMainMenu));
        props.setProperty("smoothClientFont", Boolean.toString(smoothClientFont));
        props.setProperty("hudEnabled", Boolean.toString(hudEnabled));
        props.setProperty("hudFpsCps", Boolean.toString(hudFpsCps));
        props.setProperty("hudPing", Boolean.toString(hudPing));
        props.setProperty("hudSprintStatus", Boolean.toString(hudSprintStatus));
        props.setProperty("hudKeystrokes", Boolean.toString(hudKeystrokes));
        props.setProperty("hudArmor", Boolean.toString(hudArmor));

        props.setProperty("toggleSprint", Boolean.toString(toggleSprint));
        props.setProperty("sprintToggledActive", Boolean.toString(sprintToggledActive));
        props.setProperty("zoomEnabled", Boolean.toString(zoomEnabled));
        props.setProperty("zoomSmoothCamera", Boolean.toString(zoomSmoothCamera));
        props.setProperty("fullbright", Boolean.toString(fullbright));
        props.setProperty("lowFire", Boolean.toString(lowFire));
        props.setProperty("noHurtCam", Boolean.toString(noHurtCam));
        props.setProperty("staticFov", Boolean.toString(staticFov));
        props.setProperty("transparentChat", Boolean.toString(transparentChat));

        props.setProperty("hudFpsX", Integer.toString(hudFpsX));
        props.setProperty("hudFpsY", Integer.toString(hudFpsY));
        props.setProperty("hudPingX", Integer.toString(hudPingX));
        props.setProperty("hudPingY", Integer.toString(hudPingY));
        props.setProperty("hudSprintX", Integer.toString(hudSprintX));
        props.setProperty("hudSprintY", Integer.toString(hudSprintY));
        props.setProperty("hudKeystrokesX", Integer.toString(hudKeystrokesX));
        props.setProperty("hudKeystrokesY", Integer.toString(hudKeystrokesY));

        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "PureClient 3.5.0 Configuration");
        } catch (IOException ignored) {
        }
    }

    private static boolean getBool(Properties props, String key, boolean def) {
        String val = props.getProperty(key);
        return val != null ? Boolean.parseBoolean(val.trim()) : def;
    }

    private static int getInt(Properties props, String key, int def) {
        String val = props.getProperty(key);
        if (val == null) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int val, int min, int max) {
        return val < min ? min : (val > max ? max : val);
    }
}
