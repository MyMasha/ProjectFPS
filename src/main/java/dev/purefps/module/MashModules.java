package dev.purefps.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Registre central des 203 modules MashClient 4.4 (65 HUD + 30 PvP + 35 Visuel + 25 Hypixel + 20 Monde + 28 Performance).
 * Synchronisation directe O(1) avec PureFPSConfig et MashFlags.
 */
public final class MashModules {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<MashModule> ALL_MODULES = new ArrayList<>(208);
    private static final List<MashModule> HUD_MODULES = new ArrayList<>(72);
    private static final Map<String, MashModule> BY_ID = new LinkedHashMap<>(256);

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MashClient-ModulesIO");
        t.setDaemon(true);
        return t;
    });

    private static boolean registered = false;
    private static boolean suppressCallbacks = false;
    private static volatile boolean dirty = false;
    private static File modulesFile = null;

    // Références rapides aux modules principaux
    public static MashModule MOD_CROSSHAIR;
    public static MashModule MOD_PVP_FOV;
    public static MashModule MOD_ZOOM;
    public static MashModule MOD_TOGGLE_SPRINT;
    public static MashModule MOD_HIT_COLOR;
    public static MashModule MOD_LOW_FIRE;
    public static MashModule MOD_NO_HURTCAM;
    public static MashModule MOD_VIEWMODEL;
    public static MashModule MOD_FREELOOK;
    public static MashModule MOD_BOW_CHARGE;
    public static MashModule MOD_DAMAGE_TINT;
    public static MashModule MOD_FULLBRIGHT;
    public static MashModule MOD_TIME_CHANGER;
    public static MashModule MOD_WEATHER_CHANGER;
    public static MashModule MOD_TAB_LIST;
    public static MashModule MOD_SCOREBOARD;
    public static MashModule MOD_CHAT_PRO;
    public static MashModule MOD_NICK_HIDER;
    public static MashModule MOD_UHC_OVERLAY;
    public static MashModule MOD_BORDERLESS;
    public static MashModule MOD_MUTE_UNFOCUSED;

    private MashModules() {
    }

    public static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }
        suppressCallbacks = true;
        try {
            ALL_MODULES.clear();
            HUD_MODULES.clear();
            BY_ID.clear();

            registerAll65HudModules();
            registerAll30PvpModules();
            registerAll35VisualModules();
            registerAll25HypixelModules();
            registerAll20WorldModules();
            registerAll25EngineModules();

            registered = true;
        } finally {
            suppressCallbacks = false;
        }
        syncFromPureConfig();
        syncRuntimeFlags();
    }

    public static void init(File mcDataDir) {
        ensureRegistered();
        if (mcDataDir != null) {
            File cfgDir = new File(mcDataDir, "config");
            if (!cfgDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cfgDir.mkdirs();
            }
            modulesFile = new File(cfgDir, "mashclient_modules.json");
            loadNow();
            saveNow();
        }
    }

    public static List<MashModule> getAll() {
        ensureRegistered();
        return Collections.unmodifiableList(ALL_MODULES);
    }

    public static List<MashModule> getHudModules() {
        ensureRegistered();
        return Collections.unmodifiableList(HUD_MODULES);
    }

    public static MashModule getById(String id) {
        ensureRegistered();
        return BY_ID.get(id);
    }

    public static int getActiveCount() {
        ensureRegistered();
        int count = 0;
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            if (ALL_MODULES.get(i).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    public static List<MashModule> filter(MashCategory category, boolean onlyEnabled, boolean onlyFavorites, String rawQuery) {
        ensureRegistered();
        String normQ = MashModule.normalizeSearch(rawQuery);
        boolean hasQuery = !normQ.isEmpty();

        if (!hasQuery) {
            List<MashModule> out = new ArrayList<>(ALL_MODULES.size());
            for (int i = 0; i < ALL_MODULES.size(); i++) {
                MashModule m = ALL_MODULES.get(i);
                if (category != null && category != MashCategory.ALL && m.getCategory() != category) {
                    continue;
                }
                if (onlyEnabled && !m.isEnabled()) {
                    continue;
                }
                if (onlyFavorites && !m.isFavorite()) {
                    continue;
                }
                out.add(m);
            }
            return out;
        }

        final class Scored {
            final MashModule module;
            final int score;
            final int order;

            Scored(MashModule module, int score, int order) {
                this.module = module;
                this.score = score;
                this.order = order;
            }
        }

        List<Scored> scoredList = new ArrayList<>(64);
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            MashModule m = ALL_MODULES.get(i);
            if (category != null && category != MashCategory.ALL && m.getCategory() != category) {
                continue;
            }
            if (onlyEnabled && !m.isEnabled()) {
                continue;
            }
            if (onlyFavorites && !m.isFavorite()) {
                continue;
            }
            int sc = m.scoreQuery(rawQuery);
            if (sc > 0) {
                scoredList.add(new Scored(m, sc, i));
            }
        }

        scoredList.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.order, b.order);
        });

        List<MashModule> out = new ArrayList<>(scoredList.size());
        for (int i = 0; i < scoredList.size(); i++) {
            out.add(scoredList.get(i).module);
        }
        return out;
    }

    public static void markDirty() {
        if (suppressCallbacks) {
            return;
        }
        dirty = true;
    }

    static void onModuleToggled(MashModule module) {
        if (suppressCallbacks) {
            return;
        }
        pushModuleToPureConfig(module);
        syncRuntimeFlags();
        dirty = true;
    }

    static void onSettingMutated() {
        if (suppressCallbacks) {
            return;
        }
        pushEngineSettingsToPureConfig();
        syncRuntimeFlags();
        dirty = true;
    }

    public static void saveAsyncIfDirty() {
        if (!dirty || modulesFile == null) {
            return;
        }
        dirty = false;
        final String snapshot = serializeToJsonString();
        final File target = modulesFile;
        IO_EXECUTOR.execute(() -> writeStringToFile(target, snapshot));
        PureFPSConfig.saveIfDirty();
    }

    public static void saveNow() {
        dirty = false;
        if (modulesFile != null) {
            writeStringToFile(modulesFile, serializeToJsonString());
        }
    }

    public static void loadNow() {
        ensureRegistered();
        if (modulesFile == null || !modulesFile.isFile()) {
            syncFromPureConfig();
            syncRuntimeFlags();
            return;
        }
        suppressCallbacks = true;
        String loadedVersion = "";
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(modulesFile), StandardCharsets.UTF_8)) {
            JsonElement rootEl = new JsonParser().parse(reader);
            if (rootEl != null && rootEl.isJsonObject()) {
                JsonObject root = rootEl.getAsJsonObject();
                if (root.has("version") && root.get("version").isJsonPrimitive()) {
                    loadedVersion = root.get("version").getAsString();
                }
                JsonElement modsEl = root.get("modules");
                if (modsEl != null && modsEl.isJsonObject()) {
                    JsonObject modsObj = modsEl.getAsJsonObject();
                    for (int i = 0; i < ALL_MODULES.size(); i++) {
                        MashModule m = ALL_MODULES.get(i);
                        JsonElement mEl = modsObj.get(m.getId());
                        if (mEl != null && mEl.isJsonObject()) {
                            m.fromJson(mEl.getAsJsonObject());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            suppressCallbacks = false;
        }

        if (!"4.4.0".equals(loadedVersion)) {
            if (num("eng_unfocused_saver", "fps_cap", 30.0F) < 20.0F) {
                setNumSilent("eng_unfocused_saver", "fps_cap", 30.0F);
            }
            dirty = true;
        }

        long pureLoadedMtime = PureFPSConfig.getLoadedFileLastModified();
        boolean launcherSyncedNewer = pureLoadedMtime > 0L
                && pureLoadedMtime > modulesFile.lastModified() + 500L;
        if (launcherSyncedNewer) {
            syncFromPureConfig();
            dirty = true;
        } else {
            for (int i = 0; i < ALL_MODULES.size(); i++) {
                pushModuleToPureConfig(ALL_MODULES.get(i));
            }
            pushEngineSettingsToPureConfig();
            syncRuntimeFlags();
        }
        saveAsyncIfDirty();
    }

    public static String serializeToJsonString() {
        ensureRegistered();
        JsonObject root = new JsonObject();
        root.addProperty("client", "MashClient");
        root.addProperty("version", "4.4.0");
        JsonObject mods = new JsonObject();
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            MashModule m = ALL_MODULES.get(i);
            mods.add(m.getId(), m.toJson());
        }
        root.add("modules", mods);
        return GSON.toJson(root);
    }

    public static void deserializeFromJsonStringForTest(String json) {
        ensureRegistered();
        suppressCallbacks = true;
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject modsObj = root.getAsJsonObject("modules");
            for (int i = 0; i < ALL_MODULES.size(); i++) {
                MashModule m = ALL_MODULES.get(i);
                JsonElement el = modsObj.get(m.getId());
                if (el != null && el.isJsonObject()) {
                    m.fromJson(el.getAsJsonObject());
                }
            }
        } finally {
            suppressCallbacks = false;
        }
        syncRuntimeFlags();
    }

    private static void writeStringToFile(File file, String content) {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        } catch (Throwable ignored) {
        }
    }

    public static void syncFromPureConfig() {
        if (!registered) {
            return;
        }
        suppressCallbacks = true;
        try {
            setSilent("eng_region_multidraw", PureFPSConfig.regionMultiDraw);
            setSilent("eng_island_core_cull", PureFPSConfig.islandCoreCulling);
            setSilent("eng_smart_chunk_updates", PureFPSConfig.smartChunkUpdates);
            setSilent("eng_menu_world_unload", PureFPSConfig.menuWorldUnload);
            setSilent("eng_lazy_terrain_bfs", PureFPSConfig.lazyTerrainSetup);
            setSilent("eng_frustum_pvertex", PureFPSConfig.fastFrustumPVertex);
            setSilent("eng_fbo_blit_gl30", PureFPSConfig.fastFboBlit);
            setSilent("eng_no_gl_error", PureFPSConfig.disableGlErrorChecks);
            setSilent("eng_smart_animations", PureFPSConfig.smartAnimations);
            setSilent("eng_entity_culling", PureFPSConfig.entityDistanceCulling);
            setSilent("eng_item_frame_opt", PureFPSConfig.fastItemFrames);
            setSilent("eng_player_opt", PureFPSConfig.fastPlayerRendering);
            setSilent("eng_baked_items", PureFPSConfig.fastBakedItemModels);
            setSilent("eng_tile_culling", PureFPSConfig.tileEntityFrustumCulling);
            setSilent("eng_skull_cache", PureFPSConfig.fastSkullRendering);
            setSilent("eng_fast_font", PureFPSConfig.fastFontRenderer);
            setSilent("eng_fast_scoreboard_gpu", PureFPSConfig.fastScoreboard);
            setSilent("eng_particle_engine", PureFPSConfig.particleFrustumCulling);
            setSilent("eng_armor_stand_opt", PureFPSConfig.fastArmorStands);
            setSilent("eng_biome_cache", PureFPSConfig.fastBiomeColors);
            setSilent("eng_smooth_lighting_zero", PureFPSConfig.zeroAllocSmoothLighting);
            setSilent("eng_quad_radix_sort", PureFPSConfig.fastWorldVertexSort);
            setSilent("eng_chunk_threads", PureFPSConfig.multiThreadedChunkBuild);
            setSilent("eng_skin_pacing", PureFPSConfig.asyncSkinUploadPacing);
            setSilent("eng_distant_glint", PureFPSConfig.limitDistantGlint);
            setSilent("eng_void_fog_skip", PureFPSConfig.skipVoidFogLoop);
            setSilent("eng_world_gc_guard", PureFPSConfig.disableWorldLoadGC);
            setSilent("eng_unfocused_saver", PureFPSConfig.unfocusedFpsLimiter);

            setSilent("pvp_toggle_sprint", PureFPSConfig.toggleSprint);
            setSilent("pvp_zoom", PureFPSConfig.zoomEnabled);
            setSilent("pvp_low_fire", PureFPSConfig.lowFire);
            setSilent("pvp_no_hurtcam", PureFPSConfig.noHurtCam);
            setSilent("vis_fullbright", PureFPSConfig.fullbright);
            setSilent("vis_tnt_timer", PureFPSConfig.tntTimer);
            setSilent("vis_gui_font_hd", PureFPSConfig.smoothClientFont);
            setSilent("wld_borderless", PureFPSConfig.borderlessFullscreen);
            setSilent("wld_custom_main_menu", PureFPSConfig.customMainMenu);
            setSilent("hud_fps", PureFPSConfig.hudFpsCps);
            setSilent("hud_ping", PureFPSConfig.hudPing);
            setSilent("hud_keystrokes", PureFPSConfig.hudKeystrokes);
            setSilent("hud_armor", PureFPSConfig.hudArmor);

            setNumSilent("eng_entity_culling", "max_dist", PureFPSConfig.maxEntityRenderDistance);
            setNumSilent("eng_item_frame_opt", "max_dist", PureFPSConfig.maxItemFrameDistance);
            setNumSilent("eng_tile_culling", "max_dist", PureFPSConfig.maxTileEntityRenderDistance);
            setNumSilent("eng_particle_engine", "max_particles", PureFPSConfig.maxParticlesPerLayer);
            setNumSilent("eng_unfocused_saver", "fps_cap", PureFPSConfig.unfocusedFpsLimit);
            setBoolSilent("wld_custom_main_menu", "black_hole_sound", PureFPSConfig.blackHoleMenuSound);
        } finally {
            suppressCallbacks = false;
        }
        syncRuntimeFlags();
    }

    private static void setSilent(String modId, boolean en) {
        MashModule m = BY_ID.get(modId);
        if (m != null) {
            m.setEnabledSilent(en);
        }
    }

    private static void setNumSilent(String modId, String settingId, float val) {
        MashModule m = BY_ID.get(modId);
        if (m != null) {
            MashSetting s = m.findSetting(settingId);
            if (s instanceof MashSetting.NumberSetting) {
                ((MashSetting.NumberSetting) s).setSilent(val);
            }
        }
    }

    private static void setBoolSilent(String modId, String settingId, boolean val) {
        MashModule m = BY_ID.get(modId);
        if (m != null) {
            MashSetting s = m.findSetting(settingId);
            if (s instanceof MashSetting.BooleanSetting) {
                ((MashSetting.BooleanSetting) s).setSilent(val);
            }
        }
    }

    private static void pushModuleToPureConfig(MashModule m) {
        if (m == null) {
            return;
        }
        String id = m.getId();
        boolean en = m.isEnabled();
        switch (id) {
            case "hud_fps": PureFPSConfig.hudFpsCps = en; break;
            case "hud_ping": PureFPSConfig.hudPing = en; break;
            case "hud_keystrokes": PureFPSConfig.hudKeystrokes = en; break;
            case "hud_armor": PureFPSConfig.hudArmor = en; break;
            case "pvp_toggle_sprint": PureFPSConfig.toggleSprint = en; break;
            case "pvp_zoom": PureFPSConfig.zoomEnabled = en; break;
            case "pvp_low_fire": PureFPSConfig.lowFire = en; break;
            case "pvp_no_hurtcam": PureFPSConfig.noHurtCam = en; break;
            case "vis_fullbright": PureFPSConfig.fullbright = en; break;
            case "vis_tnt_timer": PureFPSConfig.tntTimer = en; break;
            case "vis_vignette_ctrl": PureFPSConfig.disableVignette = en; break;
            case "vis_gui_font_hd": PureFPSConfig.smoothClientFont = en; break;
            case "wld_borderless": PureFPSConfig.borderlessFullscreen = en; break;
            case "wld_custom_main_menu": PureFPSConfig.customMainMenu = en; break;
            case "wld_f3_enhancer": PureFPSConfig.showF3Stats = en; break;
            case "eng_region_multidraw":
                PureFPSConfig.regionMultiDraw = en;
                PureFPSConfig.hardwareRegionVao = en;
                reloadRenderersIfInWorld();
                break;
            case "eng_island_core_cull":
                PureFPSConfig.islandCoreCulling = en;
                reloadRenderersIfInWorld();
                break;
            case "eng_smart_chunk_updates":
                PureFPSConfig.smartChunkUpdates = en;
                break;
            case "eng_menu_world_unload":
                PureFPSConfig.menuWorldUnload = en;
                break;
            case "eng_lazy_terrain_bfs": PureFPSConfig.lazyTerrainSetup = en; break;
            case "eng_frustum_pvertex": PureFPSConfig.fastFrustumPVertex = en; break;
            case "eng_fbo_blit_gl30": PureFPSConfig.fastFboBlit = en; break;
            case "eng_no_gl_error": PureFPSConfig.disableGlErrorChecks = en; break;
            case "eng_smart_animations": PureFPSConfig.smartAnimations = en; break;
            case "eng_entity_culling":
                PureFPSConfig.entityDistanceCulling = en;
                PureFPSConfig.entityOcclusionCulling = en;
                break;
            case "eng_item_frame_opt": PureFPSConfig.fastItemFrames = en; break;
            case "eng_player_opt": PureFPSConfig.fastPlayerRendering = en; break;
            case "eng_baked_items":
                PureFPSConfig.fastBakedItemModels = en;
                PureFPSConfig.fastItemRender = en;
                break;
            case "eng_tile_culling":
                PureFPSConfig.tileEntityFrustumCulling = en;
                PureFPSConfig.tileEntityDistanceCulling = en;
                break;
            case "eng_skull_cache": PureFPSConfig.fastSkullRendering = en; break;
            case "eng_fast_font": PureFPSConfig.fastFontRenderer = en; break;
            case "eng_fast_scoreboard_gpu": PureFPSConfig.fastScoreboard = en; break;
            case "eng_particle_engine":
                PureFPSConfig.particleFrustumCulling = en;
                PureFPSConfig.fastParticlePhysics = en;
                PureFPSConfig.fastBlockBreakParticles = en;
                break;
            case "eng_armor_stand_opt": PureFPSConfig.fastArmorStands = en; break;
            case "eng_biome_cache": PureFPSConfig.fastBiomeColors = en; break;
            case "eng_smooth_lighting_zero": PureFPSConfig.zeroAllocSmoothLighting = en; break;
            case "eng_quad_radix_sort": PureFPSConfig.fastWorldVertexSort = en; break;
            case "eng_chunk_threads": PureFPSConfig.multiThreadedChunkBuild = en; break;
            case "eng_skin_pacing": PureFPSConfig.asyncSkinUploadPacing = en; break;
            case "eng_distant_glint": PureFPSConfig.limitDistantGlint = en; break;
            case "eng_void_fog_skip": PureFPSConfig.skipVoidFogLoop = en; break;
            case "eng_world_gc_guard": PureFPSConfig.disableWorldLoadGC = en; break;
            case "eng_unfocused_saver": PureFPSConfig.unfocusedFpsLimiter = en; break;
            default: break;
        }
        PureFPSConfig.markDirty();
    }

    private static void pushEngineSettingsToPureConfig() {
        PureFPSConfig.maxEntityRenderDistance = Math.round(num("eng_entity_culling", "max_dist", PureFPSConfig.maxEntityRenderDistance));
        PureFPSConfig.entityOcclusionCulling = bool("eng_entity_culling", "occlusion", PureFPSConfig.entityOcclusionCulling);
        PureFPSConfig.maxItemFrameDistance = Math.round(num("eng_item_frame_opt", "max_dist", PureFPSConfig.maxItemFrameDistance));
        PureFPSConfig.maxTileEntityRenderDistance = Math.round(num("eng_tile_culling", "max_dist", PureFPSConfig.maxTileEntityRenderDistance));
        PureFPSConfig.maxParticlesPerLayer = Math.round(num("eng_particle_engine", "max_particles", PureFPSConfig.maxParticlesPerLayer));
        PureFPSConfig.chunkWorkerThreads = Math.round(num("eng_chunk_threads", "threads", PureFPSConfig.chunkWorkerThreads));
        PureFPSConfig.unfocusedFpsLimit = Math.round(num("eng_unfocused_saver", "fps_cap", PureFPSConfig.unfocusedFpsLimit));
        PureFPSConfig.blackHoleMenuSound = bool("wld_custom_main_menu", "black_hole_sound", PureFPSConfig.blackHoleMenuSound);
        PureFPSConfig.markDirty();
    }

    private static void reloadRenderersIfInWorld() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.renderGlobal != null && mc.theWorld != null) {
                mc.renderGlobal.loadRenderers();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void syncRuntimeFlags() {
        if (!registered) {
            return;
        }
        long now = System.currentTimeMillis();

        // Réticule personnalisé
        MashFlags.customCrosshair = isOn("pvp_crosshair");
        MashFlags.crosshairShape = modeIdx("pvp_crosshair", "shape", 0);
        MashFlags.crosshairSize = num("pvp_crosshair", "size", 4.5F);
        MashFlags.crosshairGap = num("pvp_crosshair", "gap", 2.5F);
        MashFlags.crosshairThickness = num("pvp_crosshair", "thickness", 1.2F);
        MashFlags.crosshairOutline = bool("pvp_crosshair", "outline", true);
        MashFlags.crosshairOutlinePx = num("pvp_crosshair", "outline_px", 1.0F);
        MashFlags.crosshairColor = rawCol("pvp_crosshair", "color", 0xFFFFFFFF);
        MashFlags.crosshairChroma = isChroma("pvp_crosshair", "color");
        MashFlags.crosshairHighlightEntity = bool("pvp_crosshair", "target_highlight", true);
        MashFlags.crosshairTargetColor = col("pvp_crosshair", "target_col", now, 0xFFF87171);
        MashFlags.crosshairDynamicSpread = bool("pvp_crosshair", "dynamic_spread", true);
        MashFlags.crosshairShowInThirdPerson = bool("pvp_crosshair", "show_f5", false);

        // FOV
        MashFlags.pvpFovEnabled = isOn("pvp_fov");
        MashFlags.pvpFovOverrideBase = bool("pvp_fov", "override_base", true);
        MashFlags.pvpFovBase = num("pvp_fov", "base_fov", 90.0F);
        MashFlags.pvpFovDynamic = bool("pvp_fov", "dynamic_fov", true);
        MashFlags.pvpFovSpeed1Mult = num("pvp_fov", "speed1_mult", 1.10F);
        MashFlags.pvpFovSpeed2Mult = num("pvp_fov", "speed2_mult", 1.20F);
        MashFlags.pvpFovSprintBoost = num("pvp_fov", "sprint_boost", 0.08F);
        MashFlags.pvpFovBowZoom = num("pvp_fov", "bow_zoom", 0.85F);
        MashFlags.pvpFovFlyMult = num("pvp_fov", "fly_mult", 1.05F);
        MashFlags.pvpFovIgnoreSlowness = bool("pvp_fov", "anti_slowness", true);

        // Zoom
        MashFlags.zoomEnabled = isOn("pvp_zoom");
        MashFlags.zoomKeyCode = key("pvp_zoom", "keybind", Keyboard.KEY_C);
        MashFlags.zoomInitialDivisor = num("pvp_zoom", "base_zoom", 4.0F);
        MashFlags.zoomScrollEnabled = bool("pvp_zoom", "scroll_zoom", true);
        MashFlags.zoomScrollStep = num("pvp_zoom", "scroll_step", 1.18F);
        MashFlags.zoomMaxDivisor = num("pvp_zoom", "max_zoom", 18.0F);
        MashFlags.zoomSmoothTransition = bool("pvp_zoom", "smooth_anim", true);
        MashFlags.zoomTransitionSpeed = num("pvp_zoom", "anim_speed", 18.0F);
        MashFlags.zoomSmoothCamera = bool("pvp_zoom", "cinematic_cam", false);
        MashFlags.zoomSensitivityPct = num("pvp_zoom", "mouse_sens", 65.0F);
        PureFPSConfig.zoomEnabled = MashFlags.zoomEnabled;
        PureFPSConfig.zoomSmoothCamera = MashFlags.zoomSmoothCamera;

        // Toggle Sprint & Sneak
        MashFlags.toggleSprintEnabled = isOn("pvp_toggle_sprint") && bool("pvp_toggle_sprint", "sprint_on", true);
        MashFlags.toggleSneakEnabled = isOn("pvp_toggle_sprint") && bool("pvp_toggle_sprint", "sneak_on", false);
        PureFPSConfig.toggleSprint = MashFlags.toggleSprintEnabled;

        // Low Fire, HurtCam, Viewmodel, HitColor
        MashFlags.lowFireEnabled = isOn("pvp_low_fire");
        MashFlags.lowFireOffset = num("pvp_low_fire", "fire_height", -0.26F);
        PureFPSConfig.lowFire = MashFlags.lowFireEnabled;

        MashFlags.hurtCamCustom = isOn("pvp_no_hurtcam");
        MashFlags.hurtCamIntensity = num("pvp_no_hurtcam", "shake_pct", 0.0F) * 0.01F;
        PureFPSConfig.noHurtCam = MashFlags.hurtCamCustom && MashFlags.hurtCamIntensity <= 0.01F;

        MashFlags.viewmodelEnabled = isOn("pvp_viewmodel");
        MashFlags.viewmodelX = num("pvp_viewmodel", "offset_x", 0.0F);
        MashFlags.viewmodelY = num("pvp_viewmodel", "offset_y", 0.0F);
        MashFlags.viewmodelZ = num("pvp_viewmodel", "offset_z", 0.0F);
        MashFlags.viewmodelScale = num("pvp_viewmodel", "item_scale", 1.0F);

        MashFlags.hitColorEnabled = isOn("pvp_hit_color");
        MashFlags.hitColorArgb = rawCol("pvp_hit_color", "tint_col", 0x66FF2A2A);
        MashFlags.hitColorChroma = isChroma("pvp_hit_color", "tint_col");
        MashFlags.bowChargeIndicator = isOn("pvp_bow_charge_tint");
        MashFlags.criticalHealthVignette = isOn("pvp_damage_tint");
        MashFlags.criticalHealthThreshold = num("pvp_damage_tint", "hearts_thresh", 3.5F) * 2.0F;

        MashFlags.freelookEnabled = isOn("pvp_freelook");
        MashFlags.freelookKeyCode = key("pvp_freelook", "keybind", Keyboard.KEY_LMENU);
        MashFlags.freelookInvertY = bool("pvp_freelook", "invert_y", false);

        // Visuel, TNT Timer, Hypixel & Monde
        MashFlags.fullbrightEnabled = isOn("vis_fullbright");
        MashFlags.fullbrightStrength = num("vis_fullbright", "brightness", 100.0F);
        PureFPSConfig.fullbright = MashFlags.fullbrightEnabled;

        MashFlags.tntTimerEnabled = isOn("vis_tnt_timer");
        MashFlags.tntTimerDynamicColor = bool("vis_tnt_timer", "color_code", true);
        MashFlags.tntTimerFuseMode = modeIdx("vis_tnt_timer", "fuse_mode", 0);

        MashFlags.timeChangerEnabled = isOn("vis_time_changer");
        int timePreset = modeIdx("vis_time_changer", "preset", 0);
        if (timePreset == 0) MashFlags.customWorldTime = 6000L;
        else if (timePreset == 1) MashFlags.customWorldTime = 13000L;
        else if (timePreset == 2) MashFlags.customWorldTime = 18000L;
        else if (timePreset == 3) MashFlags.customWorldTime = 23250L;
        else MashFlags.customWorldTime = Math.round(num("vis_time_changer", "exact_time", 6000.0F));

        MashFlags.weatherChangerEnabled = isOn("vis_weather_changer");
        MashFlags.weatherMode = modeIdx("vis_weather_changer", "mode", 0);

        MashFlags.tabPingNumbers = isOn("vis_tab_list") && bool("vis_tab_list", "numeric_ping", true);
        MashFlags.scoreboardHidden = isOn("vis_scoreboard") && bool("vis_scoreboard", "hide_all", false);
        MashFlags.scoreboardHideNumbers = isOn("vis_scoreboard") && bool("vis_scoreboard", "hide_red_nums", true);
        MashFlags.scoreboardOffsetY = isOn("vis_scoreboard") ? Math.round(num("vis_scoreboard", "offset_y", 0.0F)) : 0;

        MashFlags.chatTransparent = isOn("hyp_chat_pro") && bool("hyp_chat_pro", "transparent_bg", true);
        MashFlags.chatTimestamps = isOn("hyp_chat_pro") && bool("hyp_chat_pro", "timestamps", false);
        PureFPSConfig.transparentChat = MashFlags.chatTransparent;

        MashFlags.nickHiderEnabled = isOn("hyp_nick_hider");
        int nickIdx = modeIdx("hyp_nick_hider", "alias", 0);
        String[] aliases = {"MashPlayer", "Vous", "Mash", "Joueur"};
        MashFlags.nickReplacement = aliases[Math.max(0, Math.min(aliases.length - 1, nickIdx))];

        MashFlags.uhcGoldScale = isOn("hyp_uhc_overlay");
        MashFlags.uhcGoldScaleFactor = num("hyp_uhc_overlay", "scale", 1.35F);
        MashFlags.muteUnfocusedAudio = isOn("wld_mute_unfocused");
    }

    public static boolean isOn(String modId) {
        MashModule m = BY_ID.get(modId);
        return m != null && m.isEnabled();
    }

    public static boolean bool(String modId, String settingId, boolean def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.BooleanSetting) ? ((MashSetting.BooleanSetting) s).get() : def;
    }

    public static float num(String modId, String settingId, float def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.NumberSetting) ? ((MashSetting.NumberSetting) s).get() : def;
    }

    public static int modeIdx(String modId, String settingId, int def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.ModeSetting) ? ((MashSetting.ModeSetting) s).getIndex() : def;
    }

    public static int col(String modId, String settingId, long nowMs, int def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.ColorSetting) ? ((MashSetting.ColorSetting) s).resolveArgb(nowMs, 0) : def;
    }

    public static int rawCol(String modId, String settingId, int def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.ColorSetting) ? ((MashSetting.ColorSetting) s).getRawArgb() : def;
    }

    public static boolean isChroma(String modId, String settingId) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return false;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.ColorSetting) && ((MashSetting.ColorSetting) s).isChroma();
    }

    public static int key(String modId, String settingId, int def) {
        MashModule m = BY_ID.get(modId);
        if (m == null) return def;
        MashSetting s = m.findSetting(settingId);
        return (s instanceof MashSetting.KeybindSetting) ? ((MashSetting.KeybindSetting) s).getKeyCode() : def;
    }

    private static MashModule regHud(
            String id, String name, String desc, String kw,
            boolean defEn, float x, float y, float w, float h
    ) {
        MashModule m = new MashModule(id, name, desc, kw, MashCategory.HUD, defEn, true, x, y, w, h);
        ALL_MODULES.add(m);
        HUD_MODULES.add(m);
        BY_ID.put(id, m);
        return m;
    }

    private static MashModule regMod(
            String id, String name, String desc, String kw,
            MashCategory cat, boolean defEn
    ) {
        MashModule m = new MashModule(id, name, desc, kw, cat, defEn, false, 0.0F, 0.0F, 0.0F, 0.0F);
        ALL_MODULES.add(m);
        BY_ID.put(id, m);
        return m;
    }

    private static void addCommonHudBadgeOptions(MashModule m, boolean defShowPrefix, boolean defBrackets) {
        m.addSetting(new MashSetting.BooleanSetting("show_prefix", "Afficher le libellé", "Affiche le nom à côté de la valeur", defShowPrefix));
        m.addSetting(new MashSetting.BooleanSetting("brackets", "Crochets", "Entoure le texte de crochets", defBrackets));
        m.addSetting(new MashSetting.BooleanSetting("dot_indicator", "Point d'accent", "Affiche un point coloré à gauche", true));
    }

    // =========================================================================
    // 1. 65 MODULES HUD
    // =========================================================================
    private static void registerAll65HudModules() {
        MashModule m;

        // 1..10
        m = regHud("hud_watermark", "Badge Client", "Affiche le nom du client en coin d'écran", "watermark logo brand mash", true, 8, 8, 108, 19);
        m.addSetting(new MashSetting.ModeSetting("style", "Format", "Texte affiché", 0, "MashClient", "MASH • FPS", "MASH"));
        m.addSetting(new MashSetting.BooleanSetting("show_fps_inline", "Afficher les FPS", "Ajoute les FPS à côté du nom", false));

        m = regHud("hud_fps", "FPS", "Images par seconde en direct", "fps framerate images", true, 8, 30, 78, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.BooleanSetting("fast_color", "Couleur selon FPS", "Vert >144, jaune >60, rouge <60", false));

        m = regHud("hud_cps", "CPS", "Clics par seconde gauche et droit", "cps clicks clics", true, 8, 52, 86, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.BooleanSetting("right_cps", "Clic droit", "Affiche aussi le CPS clic droit", true));

        m = regHud("hud_ping", "Ping", "Latence serveur en millisecondes", "ping ms latency latence", true, 8, 74, 78, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.NumberSetting("warn_ms", "Seuil jaune", "Colore le ping au-delà de ce seuil", 120.0F, 40.0F, 300.0F, 10.0F, "ms"));

        m = regHud("hud_sprint", "État du sprint", "Indique si le sprint ou sneak maintenu est actif", "sprint sneak status course", true, 8, 96, 98, 19);
        m.addSetting(new MashSetting.ModeSetting("format", "Format", "Texte affiché", 0, "Sprint • Actif", "[Sprinting (Toggled)]", "SPRINT"));
        m.addSetting(new MashSetting.BooleanSetting("hide_idle", "Masquer à l'arrêt", "Visible uniquement en courant", false));

        m = regHud("hud_keystrokes", "Keystrokes", "Pavé ZQSD/WASD, clics souris et barre espace", "keystrokes touches clavier zqsd wasd", false, 8, 120, 78, 84);
        m.addSetting(new MashSetting.BooleanSetting("show_mouse", "Boutons souris", "Affiche LMB et RMB sous les touches", true));
        m.addSetting(new MashSetting.BooleanSetting("show_cps_on_mouse", "CPS sur les boutons", "Affiche le CPS dans LMB et RMB", true));
        m.addSetting(new MashSetting.BooleanSetting("show_space", "Barre espace", "Affiche la barre de saut", true));
        m.addSetting(new MashSetting.ColorSetting("pressed_col", "Couleur d'appui", "Couleur d'une touche pressée", 0xE6F5B85C, false));

        m = regHud("hud_armor", "Armure", "Pièces équipées et usure restante", "armor armure durabilite", true, 492, 468, 86, 22);
        m.addSetting(new MashSetting.ModeSetting("layout", "Disposition", "Orientation des pièces", 0, "Horizontale", "Verticale"));
        m.addSetting(new MashSetting.ModeSetting("dura_mode", "Durabilité", "Format d'affichage", 0, "Barre", "Points", "Pourcentage"));
        m.addSetting(new MashSetting.BooleanSetting("include_hand", "Objet en main", "Ajoute l'objet tenu", false));

        m = regHud("hud_potions", "Effets actifs", "Potions en cours et durée restante", "potion effects effets speed", false, 8, 210, 112, 44);
        m.addSetting(new MashSetting.BooleanSetting("blink_low", "Clignoter en fin", "Clignote sous 8 secondes", true));
        m.addSetting(new MashSetting.BooleanSetting("compact", "Mode compact", "Réduit l'espacement vertical", false));

        m = regHud("hud_coords", "Coordonnées", "Position X, Y et Z dans le monde", "coords coordonnees xyz position", false, 8, 260, 118, 32);
        m.addSetting(new MashSetting.ModeSetting("layout", "Disposition", "Sur 1 ou 3 lignes", 0, "1 ligne", "3 lignes"));
        m.addSetting(new MashSetting.NumberSetting("decimals", "Décimales", "Précision affichée", 0.0F, 0.0F, 2.0F, 1.0F, ""));
        m.addSetting(new MashSetting.BooleanSetting("show_y", "Hauteur Y", "Affiche la coordonnée verticale", true));

        m = regHud("hud_direction", "Boussole", "Cap cardinal N, E, S, O et angle", "direction compass boussole nord", false, 380, 8, 120, 20);
        m.addSetting(new MashSetting.BooleanSetting("show_deg", "Angle en degrés", "Ajoute l'angle numérique", true));
        m.addSetting(new MashSetting.ModeSetting("style", "Style", "Ruban ou badge simple", 0, "Ruban", "Cardinal"));

        // 11..20
        m = regHud("hud_reach", "Portée (Reach)", "Distance exacte du dernier coup porté", "reach portee blocs hit", false, 92, 30, 84, 19);
        addCommonHudBadgeOptions(m, false, false);
        m.addSetting(new MashSetting.NumberSetting("decay_sec", "Remise à zéro", "Délai d'effacement", 3.5F, 1.0F, 10.0F, 0.5F, "s"));

        m = regHud("hud_combo", "Combo", "Coups d'affilée portés sans être touché", "combo hits pvp", false, 92, 52, 84, 19);
        addCommonHudBadgeOptions(m, false, false);
        m.addSetting(new MashSetting.NumberSetting("reset_sec", "Expiration", "Délai sans coup avant remise à zéro", 4.0F, 1.5F, 10.0F, 0.5F, "s"));

        m = regHud("hud_speed", "Vitesse", "Vitesse de déplacement en blocs/s", "speedometer vitesse bps kmh", false, 92, 74, 88, 19);
        m.addSetting(new MashSetting.ModeSetting("unit", "Unité", "Unité affichée", 0, "m/s (BPS)", "km/h", "blocs/tick"));
        m.addSetting(new MashSetting.BooleanSetting("include_y", "Axe vertical", "Inclut la vitesse de chute et saut", false));

        m = regHud("hud_clock", "Heure système", "Heure locale de l'ordinateur", "clock heure irl time", false, 92, 96, 76, 19);
        m.addSetting(new MashSetting.BooleanSetting("format_24h", "Format 24h", "Affiche sur 24 heures", true));
        m.addSetting(new MashSetting.BooleanSetting("show_sec", "Secondes", "Affiche les secondes", false));

        m = regHud("hud_game_time", "Heure du monde", "Cycle jour/nuit de la carte", "world time heure jeu soleil", false, 180, 30, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_memory", "Mémoire RAM", "Mémoire Java utilisée", "memory ram memoire jvm", false, 180, 52, 94, 19);
        m.addSetting(new MashSetting.ModeSetting("display", "Format", "Pourcentage ou mégaoctets", 0, "RAM %", "Mo / Max"));

        m = regHud("hud_frametime", "Frametime", "Durée de rendu d'une image en ms", "frametime ms fluidite", false, 180, 74, 88, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_fps_graph", "Courbe FPS", "Historique récent des FPS", "fps graph courbe", false, 8, 298, 112, 34);
        m.addSetting(new MashSetting.NumberSetting("target_fps", "Plafond du graphe", "Échelle verticale", 360.0F, 120.0F, 1000.0F, 20.0F, " FPS"));

        m = regHud("hud_cps_graph", "Courbe CPS", "Historique de la cadence de clic", "cps graph courbe clics", false, 8, 338, 112, 30);
        m.addSetting(new MashSetting.NumberSetting("max_cps", "Plafond CPS", "Échelle verticale", 20.0F, 10.0F, 30.0F, 2.0F, " CPS"));

        m = regHud("hud_server_ip", "Serveur", "Adresse du serveur connecté", "server ip serveur hypixel", false, 180, 96, 108, 19);
        addCommonHudBadgeOptions(m, false, false);

        // 21..30
        m = regHud("hud_tps", "TPS Serveur", "Estimation des ticks serveur par seconde", "tps server lag", false, 292, 30, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pack_display", "Pack actif", "Nom du pack de textures équipé", "pack resourcepack texture", false, 292, 52, 116, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_biome", "Biome", "Biome à la position actuelle", "biome plaines desert", false, 292, 74, 104, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_held_item", "Objet tenu", "Icône et durabilité de l'objet en main", "held item main epee", false, 292, 96, 106, 22);
        m.addSetting(new MashSetting.BooleanSetting("show_name", "Nom de l'objet", "Affiche le nom à droite de l'icône", true));

        m = regHud("hud_arrow_count", "Flèches", "Nombre total de flèches en inventaire", "arrow fleches arc bow", false, 404, 30, 74, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pot_count", "Potions de soin", "Potions Splash Instant Heal restantes", "potion count pots nodebuff", false, 404, 52, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_gapple_count", "Pommes d'or", "Pommes dorées dans l'inventaire", "gapple golden apple pommes or", false, 404, 74, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pearl_count", "Ender Pearls", "Perles disponibles dans l'inventaire", "ender pearl perles", false, 404, 96, 80, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_soup_count", "Soupes", "Soupes prêtes en inventaire", "soup soupes mushroom", false, 404, 118, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_totem_block", "Blocs", "Total des blocs posables en inventaire", "blocks blocs laine rush bedwars", false, 404, 140, 86, 20);
        addCommonHudBadgeOptions(m, true, false);

        // 31..40
        m = regHud("hud_fireball_count", "Fireballs & TNT", "Explosifs BedWars en inventaire", "fireball tnt bedwars", false, 404, 162, 88, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_bw_resources", "Ressources BedWars", "Fer, or, diamant et émeraude portés", "bedwars resources fer or diamant emeraude", false, 8, 374, 134, 21);
        m.addSetting(new MashSetting.BooleanSetting("hide_zero", "Masquer à zéro", "Cache les ressources absentes", false));

        m = regHud("hud_session_timer", "Session", "Temps écoulé depuis le lancement", "session time play timer", false, 492, 30, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_stopwatch", "Chronomètre", "Chronomètre déclenché au clavier", "stopwatch chronometre speedrun", false, 492, 52, 92, 19);
        m.addSetting(new MashSetting.KeybindSetting("toggle_key", "Démarrer / Pause", "Lance ou met en pause", Keyboard.KEY_P));
        m.addSetting(new MashSetting.KeybindSetting("reset_key", "Remise à zéro", "Réinitialise à 00:00", Keyboard.KEY_O));

        m = regHud("hud_pitch_yaw", "Angles caméra", "Angles Yaw et Pitch de visée", "yaw pitch angle f3", false, 492, 74, 106, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_light_level", "Luminosité", "Niveau de lumière sur le bloc actuel (0-15)", "light lumiere torches", false, 492, 96, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_saturation", "Saturation", "Réserve de saturation alimentaire", "saturation food faim", false, 492, 118, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_absorption", "Absorption", "Demi-cœurs dorés actifs", "absorption coeurs or", false, 492, 140, 90, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_xp_info", "Niveau XP", "Niveau et progression d'expérience", "xp experience niveau", false, 492, 162, 90, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_day_counter", "Jour", "Jour écoulé dans le monde", "day jour monde survie", false, 492, 184, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        // 41..50
        m = regHud("hud_chunk_coords", "Position chunk", "Indices de chunk et position locale", "chunk coords region", false, 492, 206, 104, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_entity_count", "Entités visibles", "Entités rendues et ignorées", "entities culling entites", false, 492, 228, 108, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_vbo_stats", "Régions GPU", "Lots de chunks envoyés au GPU", "vbo gpu multidraw chunks", false, 492, 250, 112, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_cps_ratio", "Ratio G/D", "Répartition clics gauche et droit", "cps ratio butterfly", false, 492, 272, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_hit_counter", "Coups portés", "Nombre de coups réussis en session", "hits coups attaques", false, 492, 294, 88, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_damage_taken", "Dernier dégât", "Dégâts reçus sur le dernier impact", "damage degats coeurs", false, 492, 316, 94, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_target_hud", "Cible", "Nom, vie et distance du joueur visé", "target hud cible vie", false, 380, 320, 128, 34);
        m.addSetting(new MashSetting.BooleanSetting("show_hearts", "Barre de vie", "Affiche les PV de la cible", true));
        m.addSetting(new MashSetting.BooleanSetting("show_dist", "Distance", "Affiche la distance en blocs", true));

        m = regHud("hud_mouse_stroker", "Mouvement souris", "Trace le déplacement instantané de la souris", "mouse stroker radar mouvement", false, 130, 120, 52, 52);
        m.addSetting(new MashSetting.NumberSetting("sens_vis", "Amplitude", "Échelle du tracé", 1.0F, 0.3F, 3.0F, 0.1F, "x"));

        m = regHud("hud_wasd_mini", "Touches compactes", "Touches directionnelles en format réduit", "mini wasd zqsd fleches", false, 190, 120, 44, 30);
        addCommonHudBadgeOptions(m, false, false);

        m = regHud("hud_sneak_badge", "Accroupi", "Indique l'état Sneak actif", "sneak bridge shift", false, 190, 156, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        // 51..65
        m = regHud("hud_fly_speed", "Vitesse de vol", "Multiplicateur de vol créatif ou spectateur", "fly speed vol", false, 190, 178, 84, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_ping_graph", "Courbe Ping", "Évolution récente de la latence", "ping graph reseau", false, 126, 298, 108, 30);
        m.addSetting(new MashSetting.NumberSetting("ceil_ms", "Plafond ms", "Échelle maximale du graphe", 200.0F, 50.0F, 500.0F, 25.0F, "ms"));

        m = regHud("hud_packet_rate", "Cadence client", "Régularité des 20 ticks client", "tick rate stabilite", false, 190, 200, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_weather_badge", "Météo", "Météo active sur la carte", "weather meteo pluie", false, 190, 222, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_moon_phase", "Phase lunaire", "Phase actuelle de la lune", "moon lune slime", false, 190, 244, 98, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_helmet", "Casque", "Durabilité restante du casque", "helmet casque durabilite", false, 608, 30, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_chest", "Plastron", "Durabilité restante du plastron", "chestplate plastron durabilite", false, 608, 52, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_legs", "Jambières", "Durabilité restante des jambières", "leggings jambieres durabilite", false, 608, 74, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_boots", "Bottes", "Durabilité restante des bottes", "boots bottes durabilite", false, 608, 96, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_weapon_dura", "Arme", "Durabilité de l'objet tenu en main", "weapon sword durabilite epee", false, 608, 118, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_hotbar_mirror", "Barre rapide", "Répète les 9 slots de la hotbar", "hotbar mirror barre rapide", false, 290, 410, 166, 22);
        m.addSetting(new MashSetting.BooleanSetting("highlight_slot", "Encadrer le slot", "Met en valeur le slot sélectionné", true));

        m = regHud("hud_inventory_grid", "Inventaire (9x3)", "Aperçu des 27 cases d'inventaire", "inventory view inventaire sac", false, 8, 402, 166, 58);
        m.addSetting(new MashSetting.BooleanSetting("show_counts", "Quantités", "Affiche la taille des piles", true));

        m = regHud("hud_pearl_cooldown", "Délai Ender Pearl", "Décompte après un lancer de perle", "pearl cooldown perle timer", false, 608, 140, 96, 19);
        m.addSetting(new MashSetting.NumberSetting("duration", "Durée", "Temps du décompte", 15.0F, 5.0F, 30.0F, 1.0F, "s"));

        m = regHud("hud_gapple_cooldown", "Régénération Pomme", "Durée restante après une pomme d'or", "gapple regen timer pomme", false, 608, 162, 98, 19);
        m.addSetting(new MashSetting.NumberSetting("duration", "Durée", "Secondes de régénération", 5.0F, 3.0F, 30.0F, 1.0F, "s"));

        m = regHud("hud_custom_label", "Texte libre", "Étiquette personnalisable à l'écran", "custom text label badge", false, 608, 184, 108, 19);
        m.addSetting(new MashSetting.ModeSetting("preset_label", "Texte", "Libellé affiché", 0, "MashClient", "MASH • FPS", "GG • FairPlay", "PvP"));
    }

    // =========================================================================
    // 2. 30 MODULES PVP & CAMÉRA
    // =========================================================================
    private static void registerAll30PvpModules() {
        MashModule m;

        MOD_CROSSHAIR = regMod("pvp_crosshair", "Réticule", "Viseur personnalisé : forme, taille, écart, contour et couleur sur cible", "crosshair curseur viseur point croix", MashCategory.PVP, false);
        MOD_CROSSHAIR.addSetting(new MashSetting.ModeSetting("shape", "Forme", "Style du viseur", 1, "Croix", "Croix + Point", "Point", "Cercle", "Chevron", "Carré"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("size", "Taille", "Longueur des traits ou rayon", 4.5F, 1.0F, 18.0F, 0.5F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("gap", "Écart central", "Espace au centre", 2.5F, 0.0F, 14.0F, 0.5F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("thickness", "Épaisseur", "Largeur des traits", 1.2F, 0.5F, 4.5F, 0.1F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("outline", "Contour noir", "Bordure sombre autour du viseur", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("outline_px", "Largeur contour", "Épaisseur de la bordure", 1.0F, 0.5F, 2.5F, 0.25F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.ColorSetting("color", "Couleur", "Couleur principale ou Chroma", 0xFFFFFFFF, false));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("target_highlight", "Couleur sur cible", "Change de teinte sur une entité", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.ColorSetting("target_col", "Teinte sur cible", "Couleur quand une entité est visée", 0xFFF87171, false));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("dynamic_spread", "Écart dynamique", "S'écarte légèrement en sautant ou à l'arc", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("show_f5", "Visible en F5", "Affiche le viseur à la 3e personne", false));

        MOD_PVP_FOV = regMod("pvp_fov", "Champ de vision (FOV)", "Réglage séparé du FOV de base, Speed, sprint, arc et lenteur", "fov changer champ vision speed dynamic", MashCategory.PVP, false);
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("override_base", "Remplacer le FOV", "Utilise la valeur ci-dessous", true));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("base_fov", "FOV de base", "Angle de vue par défaut", 90.0F, 30.0F, 150.0F, 1.0F, "°"));
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("dynamic_fov", "FOV dynamique", "Désactiver verrouille un FOV fixe", true));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("speed1_mult", "Speed I", "Multiplicateur sous Speed I", 1.08F, 0.85F, 1.35F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("speed2_mult", "Speed II", "Multiplicateur sous Speed II", 1.14F, 0.85F, 1.45F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("sprint_boost", "Boost sprint", "Ouverture de caméra en sprint", 0.07F, 0.00F, 0.25F, 0.01F, ""));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("bow_zoom", "Visée à l'arc", "Facteur FOV arc tendu", 0.85F, 0.55F, 1.00F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("fly_mult", "En vol", "Multiplicateur en vol", 1.04F, 0.90F, 1.25F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("anti_slowness", "Ignorer Lenteur", "Empêche Lenteur de réduire le FOV", true));

        MOD_ZOOM = regMod("pvp_zoom", "Zoom", "Zoom progressif à la touche avec réglage molette optionnel", "zoom optique molette scroll c", MashCategory.PVP, true);
        MOD_ZOOM.addSetting(new MashSetting.KeybindSetting("keybind", "Touche", "Maintenir pour zoomer", Keyboard.KEY_C));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("base_zoom", "Niveau initial", "Facteur de zoom de départ", 4.0F, 1.5F, 15.0F, 0.5F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("scroll_zoom", "Molette de zoom", "Ajuste le zoom à la molette au lieu de la hotbar", true));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("scroll_step", "Pas de molette", "Facteur par cran de molette", 1.18F, 1.05F, 1.50F, 0.02F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("max_zoom", "Zoom max", "Grossissement maximal", 18.0F, 4.0F, 35.0F, 1.0F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("smooth_anim", "Transition douce", "Interpolation à l'entrée et sortie", true));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("anim_speed", "Vitesse", "Rapidité de la transition", 18.0F, 5.0F, 40.0F, 1.0F, ""));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("cinematic_cam", "Caméra lissée", "Lissage de la souris pendant le zoom", false));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("mouse_sens", "Sensibilité", "Sensibilité souris pendant le zoom", 65.0F, 15.0F, 100.0F, 5.0F, "%"));

        MOD_TOGGLE_SPRINT = regMod("pvp_toggle_sprint", "Toggle Sprint", "Maintient la course ou le sneak d'une simple pression", "toggle sprint sneak course auto", MashCategory.PVP, true);
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("sprint_on", "Sprint maintenu", "Active la course par bascule", true));
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("sneak_on", "Sneak maintenu", "Relâché automatiquement à l'ouverture d'un menu", false));
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("fly_keep", "Actif en vol", "Conserve le sprint en vol", true));

        MOD_HIT_COLOR = regMod("pvp_hit_color", "Couleur de coup", "Modifie la teinte sur les entités touchées", "hit color damage flash rouge impact", MashCategory.PVP, false);
        MOD_HIT_COLOR.addSetting(new MashSetting.ColorSetting("tint_col", "Couleur", "Teinte et opacité à l'impact", 0x66FF2A2A, false));
        MOD_HIT_COLOR.addSetting(new MashSetting.BooleanSetting("tint_armor", "Colorer l'armure", "Applique la teinte aux armures", true));

        MOD_LOW_FIRE = regMod("pvp_low_fire", "Feu bas", "Abaisse les flammes en vue subjective", "low fire feu bas flammes", MashCategory.PVP, true);
        MOD_LOW_FIRE.addSetting(new MashSetting.NumberSetting("fire_height", "Hauteur", "Décalage vertical des flammes", -0.26F, -0.55F, 0.00F, 0.02F, ""));
        MOD_LOW_FIRE.addSetting(new MashSetting.BooleanSetting("hide_creative", "Masquer en Créatif", "Cache le feu en mode Créatif", true));

        MOD_NO_HURTCAM = regMod("pvp_no_hurtcam", "HurtCam", "Réduit ou désactive l'inclinaison d'écran lors d'un dégât", "no hurtcam shake tremblement degats", MashCategory.PVP, false);
        MOD_NO_HURTCAM.addSetting(new MashSetting.NumberSetting("shake_pct", "Intensité", "0% désactive la secousse, 100% garde Vanilla", 0.0F, 0.0F, 100.0F, 5.0F, "%"));

        MOD_VIEWMODEL = regMod("pvp_viewmodel", "Position en main", "Règle le décalage XYZ et la taille de l'objet tenu", "viewmodel hand item position main epee", MashCategory.PVP, false);
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_x", "Position X", "Décalage horizontal", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_y", "Position Y", "Décalage vertical", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_z", "Profondeur Z", "Distance caméra", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("item_scale", "Échelle", "Taille de l'objet", 1.0F, 0.50F, 1.50F, 0.05F, "x"));

        m = regMod("pvp_old_animations", "Animations 1.7", "Animations classiques de parade, d'arc et de consommation", "1.7 old animations blockhit", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("block_hit", "Block-hit 1.7", "Animation classique d'épée en parade", true));
        m.addSetting(new MashSetting.BooleanSetting("bow_17", "Arc 1.7", "Tenue d'arc style 1.7", true));
        m.addSetting(new MashSetting.BooleanSetting("eat_17", "Nourriture 1.7", "Animation classique en mangeant", true));

        m = regMod("pvp_hitbox", "Hitboxes", "Personnalise le cadre des boîtes de collision (F3+B)", "hitbox hitboxes collision f3b", MashCategory.PVP, false);
        m.addSetting(new MashSetting.ColorSetting("box_col", "Couleur", "Couleur du cadre 3D", 0xFFFFFFFF, false));
        m.addSetting(new MashSetting.BooleanSetting("eye_line", "Hauteur des yeux", "Affiche la ligne rouge des yeux", true));
        m.addSetting(new MashSetting.BooleanSetting("look_vec", "Direction du regard", "Affiche le vecteur de visée", false));

        // 11..30 PvP
        m = regMod("pvp_particles_mult", "Particules de coup", "Augmente le nombre de particules Crit et Sharpness", "particles multiplier crit sharpness", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("mult", "Multiplicateur", "Facteur de particules par coup", 2.0F, 1.0F, 4.0F, 1.0F, "x"));
        m.addSetting(new MashSetting.BooleanSetting("always_sharp", "Sharpness constant", "Affiche les particules bleues sur chaque coup", false));

        MOD_FREELOOK = regMod("pvp_freelook", "Freelook", "Rotation libre à 360° autour du joueur sans changer de cap", "freelook perspective 360 alt camera", MashCategory.PVP, true);
        MOD_FREELOOK.addSetting(new MashSetting.KeybindSetting("keybind", "Touche", "Maintenir pour activer la vue libre", Keyboard.KEY_LMENU));
        MOD_FREELOOK.addSetting(new MashSetting.BooleanSetting("invert_y", "Inverser l'axe Y", "Inverse le mouvement vertical", false));

        m = regMod("pvp_snaplook", "Vue arrière", "Regarde derrière soi tant que la touche est maintenue", "snaplook rear view arriere", MashCategory.PVP, false);
        m.addSetting(new MashSetting.KeybindSetting("keybind", "Touche", "Maintenir pour voir derrière", Keyboard.KEY_V));

        MOD_BOW_CHARGE = regMod("pvp_bow_charge_tint", "Jauge d'arc", "Indicateur de tension sous le viseur en bandant un arc", "bow charge arc tension", MashCategory.PVP, true);
        MOD_BOW_CHARGE.addSetting(new MashSetting.NumberSetting("radius", "Rayon", "Écart autour du viseur", 10.0F, 5.0F, 24.0F, 1.0F, "px"));

        m = regMod("pvp_shield_sword_pos", "Hauteur de parade", "Abaisse légèrement l'épée lors du clic droit", "sword block parade angle", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("lower", "Abaissement", "Décalage vers le bas", 0.10F, 0.00F, 0.35F, 0.05F, ""));

        MOD_DAMAGE_TINT = regMod("pvp_damage_tint", "Alerte PV bas", "Vignette rouge discrète sous un seuil de vie", "damage tint low health vie critique", MashCategory.PVP, false);
        MOD_DAMAGE_TINT.addSetting(new MashSetting.NumberSetting("hearts_thresh", "Seuil", "Nombre de cœurs déclenchant l'alerte", 3.5F, 1.0F, 8.0F, 0.5F, " cœurs"));

        m = regMod("pvp_rod_tracer_vis", "Flotteur de canne", "Ajuste la taille du bouchon de canne à pêche", "fishing rod canne flotteur", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("scale", "Taille", "Échelle du flotteur", 1.15F, 0.8F, 1.6F, 0.05F, "x"));

        m = regMod("pvp_potion_glint_col", "Reflet d'enchantement", "Couleur et intensité du reflet sur les objets enchantés", "glint color enchantement reflet", MashCategory.PVP, false);
        m.addSetting(new MashSetting.ColorSetting("col", "Couleur", "Teinte du reflet", 0xFFA855F7, false));
        m.addSetting(new MashSetting.NumberSetting("strength", "Opacité", "Intensité du reflet", 75.0F, 10.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_minimal_bobbing", "Mouvement de marche réduit", "Garde le mouvement du bras sans secouer le viseur", "minimal bobbing tremblement marche", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_hand", "Animer la main", "Conserve le balancement du bras", true));

        m = regMod("pvp_tall_grass_clear", "Contour herbes atténué", "Estompe la sélection des herbes hautes l'épée en main", "tall grass herbes fleurs", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("only_sword", "Épée uniquement", "Actif seulement avec une épée", true));

        m = regMod("pvp_sound_hit_confirm", "Son d'impact", "Ajuste la hauteur sonore des coups reçus et portés", "hit sound audio impact", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("pitch", "Tonalité", "Hauteur du son", 1.0F, 0.7F, 1.4F, 0.05F, "x"));

        m = regMod("pvp_team_marker", "Repère d'équipe", "Colore les indicateurs selon le cuir équipé", "team color equipe allie", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("match_armor", "Lire l'armure cuir", "Détecte la teinte BedWars", true));

        m = regMod("pvp_respawn_fast_ui", "Écran de mort atténué", "Réduit l'opacité rouge de l'écran de défaite", "death screen mort respawn", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("opacity", "Opacité", "Intensité du filtre rouge", 45.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_cursor_lock_fix", "Recentrage souris", "Évite les à-coups caméra en fermant un inventaire", "raw mouse cursor jump fix", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("center_exact", "Centrage strict", "Replace le curseur au centre exact", true));

        m = regMod("pvp_sprint_particles", "Masquer particules de course", "Cache vos propres particules de pas en 1re personne", "footstep sprint particles pas", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("first_person_only", "1re personne seule", "Les garde visibles en F5", true));

        m = regMod("pvp_bow_trajectory_fov", "Sensibilité à l'arc", "Adoucit la vitesse de souris quand l'arc est tendu", "bow aim sensibilite arc", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("damp", "Sensibilité", "Facteur appliqué en visée", 85.0F, 50.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_hotbar_lock", "Anti-drop d'arme", "Demande un double appui avant de jeter une épée", "slot lock drop sword protection", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("delay_ms", "Délai double appui", "Fenêtre de confirmation", 450.0F, 200.0F, 1000.0F, 50.0F, "ms"));

        m = regMod("pvp_clean_water_overlay", "Eau claire", "Allège le filtre bleu sous l'eau", "clear water eau claire", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("clarity", "Clarté", "Réduction de l'opacité sous-marine", 70.0F, 10.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_clean_pumpkin", "Masque citrouille", "Masque ou atténue la texture de citrouille équipée", "pumpkin overlay citrouille", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("opacity", "Opacité", "0% rend le masque invisible", 0.0F, 0.0F, 100.0F, 10.0F, "%"));

        m = regMod("pvp_low_shield_fire_ext", "Fin de brûlure", "Bref repère visuel lorsque le feu s'éteint", "fire extinguish brulure", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("flash_cool", "Repère bleu", "Affiche un léger contour bleu", true));
    }

    // =========================================================================
    // 3. 35 MODULES VISUEL & INTERFACE
    // =========================================================================
    private static void registerAll35VisualModules() {
        MashModule m;

        MOD_FULLBRIGHT = regMod("vis_fullbright", "Fullbright", "Luminosité maximale dans les zones sombres", "fullbright gamma lumiere grottes", MashCategory.VISUAL, true);
        MOD_FULLBRIGHT.addSetting(new MashSetting.NumberSetting("brightness", "Intensité", "Niveau d'éclairage", 100.0F, 20.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_block_overlay", "Contour de bloc", "Personnalise la bordure et le remplissage du bloc visé", "block overlay outline contour bloc", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("line_width", "Épaisseur", "Largeur des arêtes", 2.0F, 1.0F, 5.0F, 0.5F, "px"));
        m.addSetting(new MashSetting.ColorSetting("line_col", "Couleur du trait", "Couleur des arêtes", 0xCCF5B85C, false));
        m.addSetting(new MashSetting.BooleanSetting("fill_faces", "Remplir les faces", "Ajoute un aplat translucide", false));
        m.addSetting(new MashSetting.ColorSetting("fill_col", "Couleur des faces", "Teinte de remplissage", 0x26F5B85C, false));

        MOD_TIME_CHANGER = regMod("vis_time_changer", "Heure client", "Fixe l'heure du ciel localement", "time changer heure ciel nuit jour", MashCategory.VISUAL, false);
        MOD_TIME_CHANGER.addSetting(new MashSetting.ModeSetting("preset", "Horaire", "Moment de la journée", 0, "Midi", "Coucher de soleil", "Minuit", "Aube", "Personnalisé"));
        MOD_TIME_CHANGER.addSetting(new MashSetting.NumberSetting("exact_time", "Ticks (0-24000)", "Utilisé en mode Personnalisé", 6000.0F, 0.0F, 24000.0F, 250.0F, "t"));

        MOD_WEATHER_CHANGER = regMod("vis_weather_changer", "Météo client", "Contrôle l'affichage local de la pluie et de l'orage", "weather changer meteo pluie neige", MashCategory.VISUAL, false);
        MOD_WEATHER_CHANGER.addSetting(new MashSetting.ModeSetting("mode", "Météo", "État local du ciel", 0, "Dégagé", "Pluie", "Orage"));

        m = regMod("vis_nametags", "Pseudos (Nametags)", "Affiche votre pseudo en F5 et règle le fond des étiquettes", "nametags pseudos noms joueurs", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("own_tag_f5", "Mon pseudo en F5", "Visible à la 3e personne", true));
        m.addSetting(new MashSetting.BooleanSetting("transparent_bg", "Sans fond", "Retire le rectangle sombre", false));
        m.addSetting(new MashSetting.BooleanSetting("text_shadow", "Ombre du texte", "Ajoute une ombre aux lettres", true));

        MOD_SCOREBOARD = regMod("vis_scoreboard", "Scoreboard", "Masque les chiffres rouges et ajuste la position verticale", "scoreboard sidebar points rouge", MashCategory.VISUAL, true);
        MOD_SCOREBOARD.addSetting(new MashSetting.BooleanSetting("hide_red_nums", "Sans chiffres rouges", "Cache la colonne de scores à droite", true));
        MOD_SCOREBOARD.addSetting(new MashSetting.NumberSetting("offset_y", "Position Y", "Décalage vertical en pixels", 0.0F, -120.0F, 120.0F, 4.0F, "px"));
        MOD_SCOREBOARD.addSetting(new MashSetting.BooleanSetting("hide_all", "Masquer tout", "Cache complètement le panneau", false));

        m = regMod("vis_bossbar", "Barre de boss", "Ajuste la taille ou masque la barre de boss", "bossbar boss dragon wither", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.ModeSetting("mode", "Affichage", "Éléments visibles", 0, "Barre + Texte", "Texte seul", "Masqué"));
        m.addSetting(new MashSetting.NumberSetting("scale", "Échelle", "Taille de la barre", 1.0F, 0.5F, 1.3F, 0.05F, "x"));

        MOD_TAB_LIST = regMod("vis_tab_list", "Liste Tab", "Affiche le ping numérique en ms dans la liste des joueurs", "tab list tablist ping chiffres joueurs", MashCategory.VISUAL, true);
        MOD_TAB_LIST.addSetting(new MashSetting.BooleanSetting("numeric_ping", "Ping en ms", "Remplace les barres par la latence chiffrée", true));
        MOD_TAB_LIST.addSetting(new MashSetting.BooleanSetting("show_header", "En-tête serveur", "Affiche le texte supérieur du serveur", true));

        m = regMod("vis_tnt_timer", "TNT Timer", "Affiche le compte à rebours en secondes au-dessus des TNT amorcées", "tnt timer explosion bedwars", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("color_code", "Couleur dynamique", "Passe du vert au rouge avant l'explosion", true));
        m.addSetting(new MashSetting.ModeSetting("fuse_mode", "Délai TNT", "Auto détecte BedWars (2.6s) ou Vanilla (4.0s)", 0, "Auto", "Vanilla 4s", "BedWars 2.6s"));

        m = regMod("vis_item_physics", "Objets au sol", "Règle la rotation des objets jetés au sol", "item physics drops objets sol", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("spin_speed", "Vitesse de rotation", "Facteur de rotation", 1.0F, 0.0F, 2.0F, 0.1F, "x"));

        // 11..35 Visuel
        m = regMod("vis_motion_blur", "Motion Blur", "Flou de mouvement léger lors des rotations rapides", "motion blur flou fluidite", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("amount", "Intensité", "Force du flou", 25.0F, 5.0F, 70.0F, 5.0F, "%"));

        m = regMod("vis_menu_blur", "Fond des menus", "Assombrit l'arrière-plan dans les coffres et inventaires", "menu background blur inventaire", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("darkness", "Opacité", "Niveau d'assombrissement", 70.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_inv_shift_fix", "Inventaire centré", "Empêche les effets de potion de décaler l'inventaire", "inventory shift potion centre", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_badges", "Afficher les effets", "Garde la liste des potions à gauche", true));

        m = regMod("vis_scrollable_tooltips", "Infobulles défilantes", "Fait défiler les longues descriptions d'objets à la molette", "scrollable tooltips descriptions skyblock", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("speed", "Vitesse", "Pixels par cran de molette", 14.0F, 6.0F, 32.0F, 2.0F, "px"));

        m = regMod("vis_clear_glass", "Verre net", "Améliore la netteté des vitres à distance", "clear glass verre vitres", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("tinted", "Verres teintés", "Inclut les vitres colorées", true));

        m = regMod("vis_sky_color", "Couleur du ciel", "Personnalise la teinte supérieure du ciel", "sky color couleur ciel", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.ColorSetting("zenith", "Couleur", "Teinte au zénith", 0xFF78A7FF, false));

        m = regMod("vis_fog_customizer", "Brouillard", "Recule ou réduit le brouillard de distance", "fog brouillard distance", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("density", "Densité", "0% supprime le brouillard", 65.0F, 0.0F, 120.0F, 5.0F, "%"));

        m = regMod("vis_hurt_flash_sky", "Sans flash d'orage", "Coupe les éclairs blancs plein écran pendant l'orage", "lightning flash foudre orage", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_bolt", "Garder l'éclair", "Affiche toujours l'éclair 3D", true));

        m = regMod("vis_void_tint", "Horizon du vide", "Supprime la bande noire sous Y=60 en SkyWars et BedWars", "void sky horizon vide noir", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("smooth_horizon", "Ciel continu", "Unifie la couleur de l'horizon", true));

        m = regMod("vis_glint_particles", "Particules ambiantes", "Réduit les particules de portails et tables d'enchantement", "enchant portal particles", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("rate", "Quantité", "Particules conservées", 50.0F, 10.0F, 100.0F, 10.0F, "%"));

        m = regMod("vis_saturation_color", "Contraste UI", "Ajuste la netteté des textes d'interface", "vibrance contrast eclat", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("contrast", "Contraste", "Niveau de contraste", 100.0F, 80.0F, 120.0F, 5.0F, "%"));

        m = regMod("vis_vignette_ctrl", "Sans vignette", "Désactive l'assombrissement des coins d'écran", "vignette coins sombres fps", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_border_warn", "Alerte bordure", "Garde l'indicateur rouge WorldBorder", true));

        m = regMod("vis_titles_scale", "Taille des titres", "Réduit les annonces centrales du serveur", "titles scale sous-titres annonces", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("scale", "Échelle", "Facteur de taille", 0.75F, 0.30F, 1.10F, 0.05F, "x"));

        m = regMod("vis_hotbar_style", "Numéros de slots", "Affiche les raccourcis 1 à 9 sur la hotbar", "hotbar style slots", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("slot_numbers", "Numéros 1-9", "Affiche le chiffre dans chaque case", true));

        m = regMod("vis_xp_bar_clean", "Couleur niveau XP", "Personnalise la couleur du numéro d'expérience", "xp bar experience", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.ColorSetting("num_col", "Couleur", "Couleur du chiffre", 0xFF80FF20, false));

        m = regMod("vis_crosshair_f3", "Axes F3 réduits", "Réduit la taille du repère XYZ en mode F3", "f3 crosshair axes", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("scale", "Échelle", "Taille des axes", 0.70F, 0.30F, 1.00F, 0.05F, "x"));

        m = regMod("vis_rain_splash", "Pluie allégée", "Réduit les éclaboussures de pluie au sol", "rain splash gouttes sol", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("density", "Quantité", "Densité au sol", 35.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_cloud_height", "Hauteur des nuages", "Rehausse la couche de nuages", "cloud height nuages hauteur", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("offset", "Décalage", "Blocs ajoutés à Y=128", 48.0F, 0.0F, 128.0F, 8.0F, "m"));

        m = regMod("vis_cape_physics", "Portée des capes", "Masque les capes au-delà d'une certaine distance", "cape physics manteau", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("cull_dist", "Distance max", "Portée d'affichage des capes", 48.0F, 16.0F, 96.0F, 4.0F, "m"));

        m = regMod("vis_armor_glint_toggle", "Reflet d'armure limité", "Coupe le reflet enchanté sur les armures éloignées", "armor glint team color", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("dist", "Distance max", "Portée du reflet d'armure", 20.0F, 8.0F, 64.0F, 4.0F, "m"));

        m = regMod("vis_bed_hide_plates", "Portée des panneaux", "Affiche le texte des pancartes uniquement de près", "signs text panneaux pancartes", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("read_dist", "Distance de lecture", "Portée du texte", 24.0F, 8.0F, 64.0F, 2.0F, "m"));

        m = regMod("vis_beacon_beam", "Faisceau de balise", "Permet de masquer le rayon vertical des balises", "beacon beam balise rayon", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("hide_beam", "Masquer le rayon", "Cache uniquement le faisceau", false));

        m = regMod("vis_falling_blocks", "Blocs en chute", "Allège le rendu du sable et du gravier en chute", "falling sand gravel blocs", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_light", "Lumière directe", "Évite le recalcul par tick", true));

        m = regMod("vis_fireworks_ctrl", "Feux d'artifice limités", "Plafonne les particules de feux d'artifice", "fireworks victoire lag particules", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("cap_pct", "Plafond", "Particules affichées", 40.0F, 10.0F, 100.0F, 10.0F, "%"));

        m = regMod("vis_gui_font_hd", "Police lissée", "Utilise une police nette sur le HUD et les menus", "font hd police lisse", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("hud_smooth", "Actif sur le HUD", "Applique la police aux widgets", true));
    }

    // =========================================================================
    // 4. 25 MODULES HYPIXEL & CHAT
    // =========================================================================
    private static void registerAll25HypixelModules() {
        MashModule m;

        MOD_CHAT_PRO = regMod("hyp_chat_pro", "Chat", "Fond transparent, horodatage et ombre du texte", "chat transparent timestamps horodatage", MashCategory.HYPIXEL, true);
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("transparent_bg", "Fond transparent", "Retire le rectangle noir du chat", false));
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("timestamps", "Horodatage [HH:mm]", "Ajoute l'heure devant chaque message", false));
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("text_shadow", "Ombre du texte", "Facilite la lecture sur fond clair", true));

        m = regMod("hyp_autogg", "Auto GG", "Rappel discret de fin de partie", "autogg gg fin partie hypixel", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ModeSetting("phrase", "Message", "Formule affichée", 0, "gg", "gf", "Bien joué"));
        m.addSetting(new MashSetting.NumberSetting("delay_s", "Délai", "Attente après la fin de partie", 1.2F, 0.5F, 3.0F, 0.1F, "s"));

        m = regMod("hyp_autotip_reminder", "Rappel Karma", "Notification périodique discrète en session longue", "autotip karma hypixel", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("interval_min", "Intervalle", "Minutes entre deux rappels", 15.0F, 5.0F, 60.0F, 5.0F, "m"));

        MOD_NICK_HIDER = regMod("hyp_nick_hider", "Nick Hider", "Remplace votre pseudo à l'écran", "nick hider streamer pseudo cache", MashCategory.HYPIXEL, false);
        MOD_NICK_HIDER.addSetting(new MashSetting.ModeSetting("alias", "Alias", "Nom affiché localement", 0, "MashPlayer", "Vous", "Mash", "Joueur"));

        m = regMod("hyp_levelhead_tab", "Couleur Ping Tab", "Colore le ping des joueurs selon leur latence", "ping color tablist latence", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("good_ms", "Seuil vert", "Latence affichée en vert", 55.0F, 20.0F, 100.0F, 5.0F, "ms"));

        m = regMod("hyp_bedwars_team_color", "Couleur d'équipe BedWars", "Aligne les repères HUD sur la couleur de votre équipe", "bedwars team sync couleur lit", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("auto_detect", "Détection auto", "Lit la couleur de l'armure équipée", true));

        MOD_UHC_OVERLAY = regMod("hyp_uhc_overlay", "UHC Overlay", "Agrandit légèrement les pommes d'or et lingots au sol", "uhc overlay golden apple or", MashCategory.HYPIXEL, false);
        MOD_UHC_OVERLAY.addSetting(new MashSetting.NumberSetting("scale", "Échelle", "Taille au sol", 1.35F, 1.05F, 1.80F, 0.05F, "x"));

        m = regMod("hyp_skywars_perk_timer", "Minuteur Force", "Met en valeur l'effet Force après une élimination", "skywars bulldozer strength force", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("warn_s", "Durée", "Secondes suivies", 5.0F, 2.0F, 10.0F, 1.0F, "s"));

        m = regMod("hyp_bridge_goal_alert", "Limite de hauteur", "Avertit à l'approche de la hauteur maximale de blocs", "build height limit bridge bedwars", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("warn_margin", "Marge", "Blocs avant le plafond", 5.0F, 2.0F, 15.0F, 1.0F, "m"));

        m = regMod("hyp_chat_copy", "Copie du chat", "Copie proprement un message dans le presse-papiers", "chat copy copier presse-papiers", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("strip_colors", "Sans codes couleur", "Retire les préfixes de formatage", true));

        m = regMod("hyp_compact_join_leave", "Filtre connexions lobby", "Masque les annonces d'entrée dans les lobbys", "lobby join spam filtre", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("keep_friends", "Garder amis et groupe", "Affiche toujours vos coéquipiers", true));

        m = regMod("hyp_friend_highlight", "Mention du pseudo", "Surligne les messages contenant votre nom", "mention highlight ping pseudo", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.ColorSetting("col", "Couleur", "Couleur de mise en valeur", 0xFFF5B85C, false));

        m = regMod("hyp_party_chat_badge", "Canaux Groupe & Guilde", "Distingue les messages d'équipe du chat global", "party guild chat canal", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("bold_tag", "Préfixe marqué", "Accentue le nom du canal", true));

        m = regMod("hyp_mystery_dust_mute", "Sons de lobby réduits", "Baisse le volume des effets cosmétiques dans les lobbys", "lobby sounds mute coffres", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume", "Niveau sonore en lobby", 25.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("hyp_bed_destroy_alert", "Alerte lit détruit", "Décale l'annonce de lit détruit hors du viseur", "bed destroyed lit detruit", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("y_shift", "Décalage Y", "Position au-dessus du centre", -28.0F, -60.0F, 0.0F, 4.0F, "px"));

        m = regMod("hyp_sumo_border_warn", "Bordure d'arène", "Repère visuel près du bord en Sumo et Bridge", "sumo edge border vide", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("dist", "Distance", "Blocs avant le vide", 1.5F, 0.5F, 3.0F, 0.25F, "m"));

        m = regMod("hyp_quick_play_menu", "Commandes rapides", "Raccourci vers les commandes fréquentes hors combat", "quickplay lobby hub commande", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ModeSetting("default_cmd", "Commande", "Action sélectionnée", 0, "/lobby", "/rejoin", "/who"));

        m = regMod("hyp_hide_lobby_npcs_tags", "Portée hologrammes", "Limite la distance d'affichage des textes flottants en lobby", "holograms armorstand lobby fps", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée max", "Distance en blocs", 24.0F, 8.0F, 48.0F, 2.0F, "m"));

        m = regMod("hyp_kill_counter", "Compteur de kills", "Suit vos éliminations durant la session", "kills counter eliminations stats", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("reset_world", "Par partie", "Remet à zéro au changement de carte", false));

        m = regMod("hyp_winstreak_tracker", "Série de victoires", "Suit votre série de parties gagnées en local", "winstreak serie victoires", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("toast_milestone", "Palier tous les 5", "Affiche un repère tous les 5 gains", true));

        m = regMod("hyp_chat_search", "Filtre mot-clé", "Met en couleur les messages ciblés dans le chat", "chat filter search mot cle", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ColorSetting("hl_col", "Couleur", "Teinte du mot-clé", 0xFF38BDF8, false));

        m = regMod("hyp_anti_portal_trap_ui", "Chat dans les portails", "Garde le chat et l'inventaire utilisables dans un portail", "portal chat gui nether", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("reduce_wobble", "Réduire la distorsion", "Atténue l'ondulation violette", true));

        m = regMod("hyp_tablist_ping_sort_col", "Seuil ping élevé", "Règle la limite au-delà de laquelle le ping passe en rouge", "ping thresholds seuils ms", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("red_ms", "Seuil rouge", "Latence haute en ms", 150.0F, 80.0F, 300.0F, 10.0F, "ms"));

        m = regMod("hyp_guild_motd_clean", "Messages d'accueil compacts", "Réduit les lignes vides consécutives à la connexion", "motd clean banniere", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("trim_blank", "Fusionner les vides", "Supprime les sauts de ligne répétés", true));

        m = regMod("hyp_gg_filter", "Regrouper les GG", "Condense les messages identiques en fin de manche", "compact gg anti flood", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("window_s", "Durée", "Fenêtre de regroupement", 4.0F, 1.0F, 10.0F, 1.0F, "s"));
    }

    // =========================================================================
    // 5. 20 MODULES MONDE, FENÊTRE & AUDIO
    // =========================================================================
    private static void registerAll20WorldModules() {
        MashModule m;

        MOD_BORDERLESS = regMod("wld_borderless", "Plein écran sans bordure", "Bascule instantanément avec F11 et Alt-Tab sans écran noir", "borderless fullscreen plein ecran sans bordure f11", MashCategory.WORLD, true);
        MOD_BORDERLESS.addSetting(new MashSetting.BooleanSetting("f11_instant", "F11 direct", "Bascule Win32 sans recréer le contexte OpenGL", true));

        MOD_MUTE_UNFOCUSED = regMod("wld_mute_unfocused", "Muet en arrière-plan", "Coupe le son du jeu quand la fenêtre perd le focus", "mute unfocused alt tab son audio", MashCategory.WORLD, false);
        MOD_MUTE_UNFOCUSED.addSetting(new MashSetting.NumberSetting("bg_vol", "Volume hors focus", "0% coupe totalement le son", 0.0F, 0.0F, 50.0F, 5.0F, "%"));

        m = regMod("wld_async_screenshots", "Captures F2 asynchrones", "Enregistre les captures d'écran hors du thread de rendu", "screenshot f2 capture sans lag", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("chat_hint", "Lien dans le chat", "Affiche la confirmation de capture", true));

        m = regMod("wld_sound_limiter", "Limite de sons simultanés", "Évite la saturation lors de nombreuses explosions", "sound limiter explosions tnt", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("max_same", "Plafond par son", "Occurrences max sur un même tick", 6.0F, 2.0F, 16.0F, 1.0F, ""));

        m = regMod("wld_footstep_vol", "Volume des pas", "Ajuste le niveau relatif des bruits de pas", "footsteps pas audio", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("gain", "Volume", "Niveau sonore", 100.0F, 50.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_portal_sound_mute", "Volume des portails", "Baisse le bruit ambiant des portails du Nether", "portal sound nether silence", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume", "Niveau résiduel", 15.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_wither_dragon_mute", "Sons de boss réduits", "Atténue le volume du Wither et de l'Ender Dragon", "dragon wither boss sound", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume", "Niveau maximal", 35.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_f3_enhancer", "Infos GPU dans F3", "Ajoute les compteurs de lots et de culling à l'écran F3", "f3 debug stats mash", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("colored", "Couleurs", "Colore les valeurs clés", true));

        m = regMod("wld_waypoints_local", "Coordonnées de mort", "Mémorise la position XYZ du dernier décès en Survie", "death coords waypoint mort", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("chat_print", "Noter dans le chat", "Affiche les coordonnées localement", true));

        m = regMod("wld_chunk_borders", "Bordures de chunks", "Affiche les limites 16x16 autour du joueur", "chunk borders grille 16x16", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.ColorSetting("grid_col", "Couleur", "Couleur des lignes", 0xAA38BDF8, false));

        m = regMod("wld_lighting_debug", "Seuil d'apparition mobs", "Indique les blocs assez sombres pour faire apparaître des monstres", "light spawn mobs survie", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.NumberSetting("thresh", "Seuil lumière", "Niveau maximal signalé", 7.0F, 0.0F, 11.0F, 1.0F, ""));

        m = regMod("wld_horse_stats", "Stats des chevaux", "Affiche la vitesse et la hauteur de saut des montures", "horse stats cheval vitesse", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("decimals", "Décimales", "Précision des nombres", 2.0F, 1.0F, 2.0F, 1.0F, ""));

        m = regMod("wld_book_page_num", "Navigation livres", "Tourne les pages d'un livre avec les flèches du clavier", "book pages livre", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("arrow_nav", "Touches fléchées", "Gauche et droite changent de page", true));

        m = regMod("wld_sign_search", "Fermeture rapide panneaux", "Valide un panneau directement avec Échap", "sign edit panneau", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_close", "Validation Échap", "Ferme et enregistre d'un coup", true));

        m = regMod("wld_disconnect_confirm", "Confirmation déconnexion", "Évite de quitter un serveur par erreur depuis Échap", "disconnect confirm securite", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.BooleanSetting("pvp_only", "Multijoueur seul", "Désactivé en Solo", true));

        m = regMod("wld_server_reconnect", "Bouton Reconnecter", "Permet de rejoindre à nouveau le dernier serveur", "reconnect serveur deconnecte", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("cooldown_s", "Délai", "Attente minimale avant reconnexion", 2.0F, 1.0F, 10.0F, 1.0F, "s"));

        m = regMod("wld_memory_cleaner", "Vider les caches", "Libère les tampons obsolètes en changeant de monde", "memory cleaner cache purge", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("clear_skulls", "Cache des têtes", "Purge les skins inutilisés entre deux serveurs", true));

        m = regMod("wld_custom_main_menu", "Menu principal MashClient", "Écran d'accueil trou noir et ambiance sonore", "main menu trou noir mashclient", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("mouse_parallax", "Parallaxe souris", "Léger suivi du curseur", true));
        m.addSetting(new MashSetting.BooleanSetting("black_hole_sound", "Son du trou noir", "Ambiance gravitationnelle dans le menu (activable/désactivable)", true));

        m = regMod("wld_discord_status_fmt", "Masquer les IP privées", "Cache les adresses chiffrées dans les widgets serveur", "privacy server ip", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.BooleanSetting("hide_ip", "Masquer IP chiffrées", "Remplace les IP personnelles", true));

        m = regMod("wld_auto_sprint_resume", "Reprise des touches", "Rétablit ZQSD dès la fermeture d'un coffre ou du chat", "inventory walk resume sprint", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("sync_movement", "Resynchroniser ZQSD", "Lit l'état réel du clavier", true));
    }

    // =========================================================================
    // 6. 28 MODULES PERFORMANCE (CONNECTÉS À PureFPSConfig)
    // =========================================================================
    private static void registerAll25EngineModules() {
        MashModule m;

        m = regMod("eng_region_multidraw", "Régions GPU (EBO)", "Regroupe le rendu des chunks en triangles indexés sur le GPU", "region multidraw vbo ebo triangles gpu fps", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("anti_seam", "Anti-fissures", "Supprime les jonctions visibles entre chunks", true));

        m = regMod("eng_island_core_cull", "Intérieur des îles", "Ignore les blocs enfouis au cœur des îles BedWars et SkyWars", "island core cull bedwars skywars buried blocks", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("preserve_beds", "Garder lits et coffres", "Ne touche jamais aux lits ni aux coffres", true));

        m = regMod("eng_smart_chunk_updates", "Priorité des chunks", "Met à jour aussitôt les blocs joueurs, lits et générateurs avant le décor", "smart chunk updates dynamic blocks beds generators", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("priority_beds_gens", "Lits et générateurs", "Priorité immédiate aux zones de jeu", true));

        m = regMod("eng_menu_world_unload", "Allègement en menu", "Réduit la distance de rendu et les effets quand un menu est ouvert", "menu unload shop inventory fps boost", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("shop_glint_opt", "Reflets en boutique", "Allège les reflets dans les grands coffres", true));

        m = regMod("eng_lazy_terrain_bfs", "Visibilité terrain", "Évite de recalculer les chunks visibles tant que la caméra bouge peu", "lazy terrain setup bfs chunks fps", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("frustum_fast", "Seuil angulaire", "Recalcule uniquement sur rotation notable", true));

        m = regMod("eng_frustum_pvertex", "Frustum rapide", "Teste un seul sommet par boîte de visibilité au lieu de huit", "frustum culling pvertex aabb", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("chunks_and_entities", "Chunks et entités", "Appliqué à toute la scène 3D", true));

        m = regMod("eng_fbo_blit_gl30", "Copie FBO directe", "Transfère l'image finale par copie matérielle OpenGL 3.0", "fbo blit gl30 framebuffer", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("linear_scale", "Rendu net", "Sans flou de redimensionnement", true));

        m = regMod("eng_no_gl_error", "Sans glGetError", "Supprime les vérifications OpenGL bloquantes à chaque image", "glgeterror stall driver nvidia amd", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("strict_zero", "Désactiver en jeu", "Aucune synchronisation pilote superflue", true));

        m = regMod("eng_smart_animations", "Textures visibles", "Anime uniquement l'eau, la lave et le feu visibles à l'écran", "smart animations lave eau feu textures", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("skip_hidden", "Ignorer hors champ", "Évite les envois GPU inutiles", true));

        m = regMod("eng_entity_culling", "Culling d'entités", "Masque les entités trop loin ou cachées derrière des murs", "entity culling occlusion murs distance", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Distance max", "Portée d'affichage des entités", 64.0F, 16.0F, 160.0F, 4.0F, "m"));
        m.addSetting(new MashSetting.BooleanSetting("occlusion", "Cachées par les murs", "Test d'occlusion derrière les blocs opaques", true));

        m = regMod("eng_item_frame_opt", "Cadres d'objets", "Limite la portée et le coût des cadres d'objets", "item frames cadres cartes lag", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée max", "Distance d'affichage des cadres", 48.0F, 16.0F, 128.0F, 4.0F, "m"));

        m = regMod("eng_player_opt", "Joueurs distants", "Allège les couches secondaires sur les joueurs éloignés en lobby", "players rendering joueurs lobbys", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("detail_dist", "Distance détails", "Portée avec rendu complet", 32.0F, 16.0F, 64.0F, 4.0F, "m"));

        m = regMod("eng_baked_items", "Modèles d'objets", "Fusionne les faces des objets 3D en une seule liste", "baked item models objets 3d", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_flat", "Passe unique", "Réduit les boucles par image", true));

        m = regMod("eng_tile_culling", "Coffres et blocs", "Masque les coffres et blocs spéciaux hors champ ou lointains", "tileentity chests coffres culling", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée max", "Distance d'affichage des coffres", 48.0F, 16.0F, 160.0F, 4.0F, "m"));

        m = regMod("eng_skull_cache", "Cache des têtes", "Met en cache les skins de têtes décoratives sur les serveurs", "skulls heads tetes cache lobby", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("thread_safe", "Cache partagé", "Évite le décodage répété", true));

        m = regMod("eng_fast_font", "Police rapide", "Met en cache la largeur des textes du chat, Tab et pseudos", "fast font police cache texte", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("width_cache", "Cache des largeurs", "Lecture directe sans recalcul", true));

        m = regMod("eng_fast_scoreboard_gpu", "Scoreboard groupé", "Dessine le fond du scoreboard en un seul appel OpenGL", "fast scoreboard gpu batch", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("zero_alloc", "Sans allocations", "Réutilise la même liste entre les frames", true));

        m = regMod("eng_particle_engine", "Limite de particules", "Ignore les particules hors caméra et plafonne leur nombre", "particles engine particules lag", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_particles", "Plafond par couche", "Particules simultanées max", 1000.0F, 100.0F, 4000.0F, 100.0F, ""));

        m = regMod("eng_armor_stand_opt", "Supports d'armure", "Coupe la physique inutile sur les hologrammes et ArmorStands", "armorstand physics hologrammes", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("skip_lookup", "Sans collisions", "Allège le CPU dans les lobbys", true));

        m = regMod("eng_biome_cache", "Couleurs de biomes", "Met en cache la teinte de l'herbe, du feuillage et de l'eau", "biome colors herbe eau cache", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_blend", "Mélange direct", "Accélère la compilation des chunks", true));

        m = regMod("eng_smooth_lighting_zero", "Lumière sans alloc", "Réutilise les tampons d'éclairage ambiant sans créer d'objets", "smooth lighting ao zero alloc", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("thread_local", "Par thread", "Compatible multi-cœurs", true));

        m = regMod("eng_quad_radix_sort", "Tri translucide", "Trie l'eau et le verre directement sur tableaux primitifs", "translucent sort eau vitres", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("in_place", "Tri sur place", "Sans copies intermédiaires", true));

        m = regMod("eng_chunk_threads", "Threads de chunks", "Compile les chunks en parallèle sur plusieurs cœurs CPU", "chunk threads workers coeurs cpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("threads", "Threads (0 = Auto)", "0 choisit selon le processeur", 0.0F, 0.0F, 6.0F, 1.0F, ""));

        m = regMod("eng_skin_pacing", "Chargement des skins", "Envoie au maximum 1 skin joueur par frame vers le GPU", "skin upload pacing microfreeze", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("strict_budget", "1 skin / frame", "Évite les saccades en entrant au lobby", true));

        m = regMod("eng_distant_glint", "Reflet à distance", "Désactive le reflet d'enchantement au-delà de 20 blocs", "enchant glint distant gpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_close", "Complet de près", "Aucun changement à courte portée", true));

        m = regMod("eng_void_fog_skip", "Sans brouillard vide", "Supprime les 1000 tests de particules du vide par tick", "void fog ticks cpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("all_worlds", "Tous les mondes", "Économise du temps CPU à chaque tick", true));

        m = regMod("eng_world_gc_guard", "Sans pause GC", "Bloque les appels System.gc() forcés en changeant de monde", "system gc freeze lobby", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("integrated_too", "Actif en Solo", "S'applique aussi aux mondes locaux", true));

        m = regMod("eng_unfocused_saver", "Limite hors focus", "Réduit les FPS quand la fenêtre est réduite ou en pause", "unfocused fps economiseur gpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("fps_cap", "FPS hors focus", "Plafond fenêtre inactive", 30.0F, 5.0F, 60.0F, 5.0F, " FPS"));
    }
}
