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
    public static String language = "en";
    public static String audioOutputDevice = "";

    public static boolean isEnglish() {
        return language == null || !language.trim().toLowerCase(java.util.Locale.ROOT).startsWith("fr");
    }

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
    public static boolean islandCoreCulling = true;
    public static boolean smartChunkUpdates = true;
    public static boolean menuWorldUnload = true;

    // GPU VAO GL30 + EBO GL_TRIANGLES, Zéro glGetError, FBO Blit GL30, Modèles compilés & UI
    public static boolean disableGlErrorChecks = true;
    public static boolean regionMultiDraw = true;
    public static boolean hardwareRegionVao = true;
    public static boolean fastFboBlit = true;
    public static boolean fastFrustumPVertex = true;
    public static boolean asyncSkinUploadPacing = true;
    public static boolean fastItemRender = true;
    public static boolean fastBakedItemModels = true;
    public static boolean limitDistantGlint = true;
    public static boolean fastScoreboard = true;
    public static boolean smartAnimations = true;
    public static boolean fastFontRenderer = true;
    public static boolean fastSkullRendering = true;
    public static boolean fastLightmapCoords = true;
    public static boolean disableVignette = true;

    // Culling, Joueurs, Item Frames & TileEntities
    public static boolean entityOcclusionCulling = true;
    public static boolean entityDistanceCulling = true;
    public static int maxEntityRenderDistance = 64;
    public static boolean fastDroppedItems = true;
    public static boolean fastItemFrames = true;
    public static int maxItemFrameDistance = 48;
    public static boolean fastPlayerRendering = true;
    public static boolean fastSignText = true;

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
    public static boolean borderlessFullscreen = true;
    public static boolean unfocusedFpsLimiter = true;
    public static int unfocusedFpsLimit = 30;
    public static boolean showF3Stats = true;
    public static boolean fpsPacingMigrated = false;
    public static boolean fpsUncapped451Migrated = false;

    // Modules MashClient (synchronisés avec MashModules)
    public static boolean customMainMenu = true;
    public static boolean smoothClientFont = true;
    public static boolean sfxEnabled = true;
    public static boolean blackHoleMenuSound = true;
    public static boolean tntTimer = true;
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

    // Positions HUD héritées
    public static int hudFpsX = 8;
    public static int hudFpsY = 8;
    public static int hudPingX = 8;
    public static int hudPingY = 44;
    public static int hudSprintX = 8;
    public static int hudSprintY = 66;
    public static int hudKeystrokesX = 8;
    public static int hudKeystrokesY = 92;

    // Dernier serveur rejoint (reconnexion rapide menu principal & écran déconnexion)
    public static String lastServerIp = "";
    public static String lastServerName = "";

    public static void recordJoinedServer(String name, String ip) {
        if (ip == null) {
            return;
        }
        String cleanIp = ip.trim();
        if (cleanIp.isEmpty()) {
            return;
        }
        String cleanName = (name != null && !name.trim().isEmpty() && !"Minecraft Server".equalsIgnoreCase(name.trim()))
                ? name.trim()
                : cleanIp;
        if (!cleanIp.equals(lastServerIp) || !cleanName.equals(lastServerName)) {
            lastServerIp = cleanIp;
            lastServerName = cleanName;
            markDirty();
            saveIfDirty();
        }
    }

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
    public static int unloadedIslandCoresTotal = 0;
    public static int throttledStaticUpdatesTotal = 0;

    private static File configFile;
    private static long loadedFileLastModified = 0L;
    private static volatile boolean dirty = false;
    private static final java.util.concurrent.ExecutorService SAVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MashClient-ConfigIO");
                t.setDaemon(true);
                return t;
            });

    private PureFPSConfig() {
    }

    public static void markDirty() {
        dirty = true;
    }

    public static void saveIfDirty() {
        if (dirty) {
            dirty = false;
            SAVE_EXECUTOR.execute(PureFPSConfig::save);
        }
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
        fastBakedItemModels = true;
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
                islandCoreCulling = true;
                smartChunkUpdates = true;
                menuWorldUnload = true;
                smartAnimations = true;
                limitDistantGlint = true;
                disableVignette = true;
                entityOcclusionCulling = true;
                entityDistanceCulling = true;
                maxEntityRenderDistance = 48;
                fastDroppedItems = true;
                fastItemFrames = true;
                maxItemFrameDistance = 36;
                fastPlayerRendering = true;
                fastSignText = true;
                tileEntityDistanceCulling = true;
                maxTileEntityRenderDistance = 32;
                fastNametags = true;
                nametagDistanceCulling = true;
                maxNametagDistance = 24;
                fastParticlePhysics = true;
                maxParticlesPerLayer = 250;
                fastBlockBreakParticles = true;
                skipClientSkylightUpdates = true;
                unfocusedFpsLimit = 30;
                break;

            case BALANCED:
                islandCoreCulling = true;
                smartChunkUpdates = true;
                menuWorldUnload = true;
                smartAnimations = true;
                limitDistantGlint = true;
                disableVignette = true;
                entityOcclusionCulling = true;
                entityDistanceCulling = true;
                maxEntityRenderDistance = 64;
                fastDroppedItems = true;
                fastItemFrames = true;
                maxItemFrameDistance = 48;
                fastPlayerRendering = true;
                fastSignText = true;
                tileEntityDistanceCulling = true;
                maxTileEntityRenderDistance = 48;
                fastNametags = true;
                nametagDistanceCulling = true;
                maxNametagDistance = 32;
                fastParticlePhysics = true;
                maxParticlesPerLayer = 1000;
                fastBlockBreakParticles = true;
                skipClientSkylightUpdates = false;
                unfocusedFpsLimit = 30;
                break;

            case QUALITY:
                islandCoreCulling = false;
                smartChunkUpdates = false;
                menuWorldUnload = false;
                smartAnimations = false;
                limitDistantGlint = false;
                disableVignette = false;
                entityOcclusionCulling = false;
                entityDistanceCulling = false;
                maxEntityRenderDistance = 128;
                fastDroppedItems = false;
                fastItemFrames = true;
                maxItemFrameDistance = 96;
                fastPlayerRendering = false;
                fastSignText = false;
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

    /**
     * Synchronise VBO, VSync et limitFramerate au démarrage du client lorsque le profil FPS ou BALANCED
     * est actif, afin d'éviter qu'un ancien plafond 120/160 FPS ou VSync dans options.txt ne bride PureClient.
     */
    public static void syncStartupGameSettingsIfNeeded() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        GameSettings gs = mc.gameSettings;
        boolean changed = false;

        if (OpenGlHelper.vboSupported && !gs.useVbo) {
            gs.useVbo = true;
            changed = true;
        }

        if (activeProfile != PureFPSProfile.QUALITY) {
            if (gs.enableVsync) {
                gs.enableVsync = false;
                if (Display.isCreated()) {
                    Display.setVSyncEnabled(false);
                }
                changed = true;
            }
            fpsPacingMigrated = true;
            if (!fpsUncapped451Migrated) {
                if (gs.limitFramerate <= 240) {
                    gs.limitFramerate = 260;
                    changed = true;
                }
                fpsUncapped451Migrated = true;
                markDirty();
                saveIfDirty();
            } else if (gs.limitFramerate < 60) {
                gs.limitFramerate = 260;
                changed = true;
            }
        }

        if (changed) {
            gs.saveOptions();
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
            gs.fancyGraphics = false;
            gs.ambientOcclusion = 1;
            gs.clouds = 0;
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
            if (islandCoreCulling && smartChunkUpdates && menuWorldUnload
                    && smartAnimations && limitDistantGlint && disableVignette && entityOcclusionCulling && entityDistanceCulling
                    && maxEntityRenderDistance == 48 && fastDroppedItems && fastItemFrames && maxItemFrameDistance == 36
                    && fastPlayerRendering && fastSignText && tileEntityDistanceCulling
                    && maxTileEntityRenderDistance == 32 && fastNametags && nametagDistanceCulling && maxNametagDistance == 24
                    && fastParticlePhysics && maxParticlesPerLayer == 250 && fastBlockBreakParticles
                    && skipClientSkylightUpdates && unfocusedFpsLimit == 30) {
                activeProfile = PureFPSProfile.FPS;
                return;
            }
            if (islandCoreCulling && smartChunkUpdates && menuWorldUnload
                    && smartAnimations && limitDistantGlint && disableVignette && entityOcclusionCulling && entityDistanceCulling
                    && maxEntityRenderDistance == 64 && fastDroppedItems && fastItemFrames && maxItemFrameDistance == 48
                    && fastPlayerRendering && fastSignText && tileEntityDistanceCulling
                    && maxTileEntityRenderDistance == 48 && fastNametags && nametagDistanceCulling && maxNametagDistance == 32
                    && fastParticlePhysics && maxParticlesPerLayer == 1000 && fastBlockBreakParticles
                    && !skipClientSkylightUpdates && unfocusedFpsLimit == 30) {
                activeProfile = PureFPSProfile.BALANCED;
                return;
            }
            if (!islandCoreCulling && !smartChunkUpdates && !menuWorldUnload
                    && !smartAnimations && !limitDistantGlint && !disableVignette && !entityOcclusionCulling && !entityDistanceCulling
                    && maxEntityRenderDistance == 128 && !fastDroppedItems && fastItemFrames && maxItemFrameDistance == 96
                    && !fastPlayerRendering && !fastSignText && !tileEntityDistanceCulling
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
                && asyncSkinUploadPacing && fastItemRender && fastBakedItemModels && fastScoreboard
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
        int maxSafeForRam = maxMb < 900L ? 2 : (maxMb < 1600L ? 3 : (maxMb < 4096L ? 6 : 8));
        if (chunkWorkerThreads > 0) {
            return clamp(chunkWorkerThreads, 2, maxSafeForRam);
        }
        int logicalCores = Runtime.getRuntime().availableProcessors();
        int auto = Math.max(2, Math.min(6, logicalCores / 2));
        return Math.min(auto, maxSafeForRam);
    }

    public static long getLoadedFileLastModified() {
        return loadedFileLastModified;
    }

    public static void init(File mcDataDir) {
        File configDir = new File(mcDataDir, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        configFile = new File(configDir, "purefps.properties");
        loadedFileLastModified = configFile.isFile() ? configFile.lastModified() : 0L;
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
            chunkWorkerThreads = clamp(getInt(props, "chunkWorkerThreads", chunkWorkerThreads), 0, 8);
            disableWorldLoadGC = getBool(props, "disableWorldLoadGC", disableWorldLoadGC);
            skipVoidFogLoop = getBool(props, "skipVoidFogLoop", skipVoidFogLoop);
            islandCoreCulling = getBool(props, "islandCoreCulling", islandCoreCulling);
            smartChunkUpdates = getBool(props, "smartChunkUpdates", smartChunkUpdates);
            menuWorldUnload = getBool(props, "menuWorldUnload", menuWorldUnload);

            regionMultiDraw = getBool(props, "regionMultiDraw", regionMultiDraw);
            hardwareRegionVao = getBool(props, "hardwareRegionVao", hardwareRegionVao);
            fastFboBlit = getBool(props, "fastFboBlit", fastFboBlit);
            fastFrustumPVertex = getBool(props, "fastFrustumPVertex", fastFrustumPVertex);
            asyncSkinUploadPacing = getBool(props, "asyncSkinUploadPacing", asyncSkinUploadPacing);
            fastItemRender = getBool(props, "fastItemRender", fastItemRender);
            fastBakedItemModels = getBool(props, "fastBakedItemModels", fastBakedItemModels);
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
            fastItemFrames = getBool(props, "fastItemFrames", fastItemFrames);
            maxItemFrameDistance = clamp(getInt(props, "maxItemFrameDistance", maxItemFrameDistance), 16, 128);
            fastPlayerRendering = getBool(props, "fastPlayerRendering", fastPlayerRendering);
            fastSignText = getBool(props, "fastSignText", fastSignText);

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

            borderlessFullscreen = getBool(props, "borderlessFullscreen", borderlessFullscreen);
            unfocusedFpsLimiter = getBool(props, "unfocusedFpsLimiter", unfocusedFpsLimiter);
            unfocusedFpsLimit = clamp(getInt(props, "unfocusedFpsLimit", unfocusedFpsLimit), 5, 60);
            showF3Stats = getBool(props, "showF3Stats", showF3Stats);
            fpsPacingMigrated = getBool(props, "fpsPacingMigrated", fpsPacingMigrated);
            fpsUncapped451Migrated = getBool(props, "fpsUncapped451Migrated", fpsUncapped451Migrated);

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
            sfxEnabled = getBool(props, "sfxEnabled", sfxEnabled);
            blackHoleMenuSound = getBool(props, "blackHoleMenuSound", blackHoleMenuSound);
            tntTimer = getBool(props, "tntTimer", tntTimer);

            hudFpsX = clamp(getInt(props, "hudFpsX", hudFpsX), 2, 3840);
            hudFpsY = clamp(getInt(props, "hudFpsY", hudFpsY), 2, 2160);
            hudPingX = clamp(getInt(props, "hudPingX", hudPingX), 2, 3840);
            hudPingY = clamp(getInt(props, "hudPingY", hudPingY), 2, 2160);
            hudSprintX = clamp(getInt(props, "hudSprintX", hudSprintX), 2, 3840);
            hudSprintY = clamp(getInt(props, "hudSprintY", hudSprintY), 2, 2160);
            hudKeystrokesX = clamp(getInt(props, "hudKeystrokesX", hudKeystrokesX), 2, 3840);
            hudKeystrokesY = clamp(getInt(props, "hudKeystrokesY", hudKeystrokesY), 2, 2160);
            lastServerIp = props.getProperty("lastServerIp", lastServerIp).trim();
            lastServerName = props.getProperty("lastServerName", lastServerName).trim();
            String rawLang = props.getProperty("language", language).trim().toLowerCase();
            language = rawLang.startsWith("fr") ? "fr" : "en";
            audioOutputDevice = props.getProperty("audioOutputDevice", audioOutputDevice != null ? audioOutputDevice : "").trim();

            if (props.getProperty("activeProfile") == null) {
                refreshProfileAfterManualEdit();
            }
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        dirty = false;
        if (configFile == null) {
            File dir = Minecraft.getMinecraft() != null ? Minecraft.getMinecraft().mcDataDir : new File(".");
            configFile = new File(new File(dir, "config"), "purefps.properties");
        }
        Properties props = new Properties();
        props.setProperty("activeProfile", activeProfile.getId());
        props.setProperty("enabled", Boolean.toString(enabled));
        props.setProperty("language", "fr".equalsIgnoreCase(language) ? "fr" : "en");
        props.setProperty("audioOutputDevice", audioOutputDevice != null ? audioOutputDevice.trim() : "");
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
        props.setProperty("islandCoreCulling", Boolean.toString(islandCoreCulling));
        props.setProperty("smartChunkUpdates", Boolean.toString(smartChunkUpdates));
        props.setProperty("menuWorldUnload", Boolean.toString(menuWorldUnload));

        props.setProperty("regionMultiDraw", Boolean.toString(regionMultiDraw));
        props.setProperty("hardwareRegionVao", Boolean.toString(hardwareRegionVao));
        props.setProperty("fastFboBlit", Boolean.toString(fastFboBlit));
        props.setProperty("fastFrustumPVertex", Boolean.toString(fastFrustumPVertex));
        props.setProperty("asyncSkinUploadPacing", Boolean.toString(asyncSkinUploadPacing));
        props.setProperty("fastItemRender", Boolean.toString(fastItemRender));
        props.setProperty("fastBakedItemModels", Boolean.toString(fastBakedItemModels));
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
        props.setProperty("fastItemFrames", Boolean.toString(fastItemFrames));
        props.setProperty("maxItemFrameDistance", Integer.toString(maxItemFrameDistance));
        props.setProperty("fastPlayerRendering", Boolean.toString(fastPlayerRendering));
        props.setProperty("fastSignText", Boolean.toString(fastSignText));

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

        props.setProperty("borderlessFullscreen", Boolean.toString(borderlessFullscreen));
        props.setProperty("unfocusedFpsLimiter", Boolean.toString(unfocusedFpsLimiter));
        props.setProperty("unfocusedFpsLimit", Integer.toString(unfocusedFpsLimit));
        props.setProperty("showF3Stats", Boolean.toString(showF3Stats));
        props.setProperty("fpsPacingMigrated", Boolean.toString(fpsPacingMigrated));
        props.setProperty("fpsUncapped451Migrated", Boolean.toString(fpsUncapped451Migrated));

        props.setProperty("customMainMenu", Boolean.toString(customMainMenu));
        props.setProperty("smoothClientFont", Boolean.toString(smoothClientFont));
        props.setProperty("sfxEnabled", Boolean.toString(sfxEnabled));
        props.setProperty("blackHoleMenuSound", Boolean.toString(blackHoleMenuSound));
        props.setProperty("tntTimer", Boolean.toString(tntTimer));
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
        props.setProperty("lastServerIp", lastServerIp != null ? lastServerIp : "");
        props.setProperty("lastServerName", lastServerName != null ? lastServerName : "");

        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "MashClient 4.5.12 Configuration");
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
