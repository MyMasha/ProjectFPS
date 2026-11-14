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
 * Registre central des 200 Modules MashClient 4.0 (65 HUD + 30 PvP + 35 Visuel + 25 Hypixel + 20 Monde + 25 Moteur FPS) :
 * - Chaque module dispose de sous-paramètres riches accessibles par clic droit dans le menu RSHIFT.
 * - Synchronisation bidirectionnelle sans latence avec PureFPSConfig et MashFlags (O(1) dans les Mixins).
 * - Persistance JSON asynchrone non-bloquante dans `config/mashclient_modules.json`.
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

    // Références rapides aux modules phares
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
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(modulesFile), StandardCharsets.UTF_8)) {
            JsonElement rootEl = new JsonParser().parse(reader);
            if (rootEl != null && rootEl.isJsonObject()) {
                JsonObject root = rootEl.getAsJsonObject();
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
        for (int i = 0; i < ALL_MODULES.size(); i++) {
            pushModuleToPureConfig(ALL_MODULES.get(i));
        }
        pushEngineSettingsToPureConfig();
        syncRuntimeFlags();
    }

    public static String serializeToJsonString() {
        ensureRegistered();
        JsonObject root = new JsonObject();
        root.addProperty("client", "MashClient");
        root.addProperty("version", "4.3.0");
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

            setNumSilent("eng_entity_culling", "max_dist", PureFPSConfig.maxEntityRenderDistance);
            setNumSilent("eng_item_frame_opt", "max_dist", PureFPSConfig.maxItemFrameDistance);
            setNumSilent("eng_tile_culling", "max_dist", PureFPSConfig.maxTileEntityRenderDistance);
            setNumSilent("eng_particle_engine", "max_particles", PureFPSConfig.maxParticlesPerLayer);
            setNumSilent("eng_unfocused_saver", "fps_cap", PureFPSConfig.unfocusedFpsLimit);
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

    private static void pushModuleToPureConfig(MashModule m) {
        if (m == null) {
            return;
        }
        String id = m.getId();
        boolean en = m.isEnabled();
        switch (id) {
            case "pvp_toggle_sprint": PureFPSConfig.toggleSprint = en; break;
            case "pvp_zoom": PureFPSConfig.zoomEnabled = en; break;
            case "pvp_low_fire": PureFPSConfig.lowFire = en; break;
            case "pvp_no_hurtcam": PureFPSConfig.noHurtCam = en; break;
            case "vis_fullbright": PureFPSConfig.fullbright = en; break;
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

        // Curseur personnalisé
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

        // PvP FOV
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

        // Zoom Optique
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

        // Visuel & Hypixel & Monde
        MashFlags.fullbrightEnabled = isOn("vis_fullbright");
        MashFlags.fullbrightStrength = num("vis_fullbright", "brightness", 100.0F);
        PureFPSConfig.fullbright = MashFlags.fullbrightEnabled;

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
        m.addSetting(new MashSetting.BooleanSetting("show_prefix", "Afficher le libellé", "Affiche le nom de la métrique à côté de la valeur", defShowPrefix));
        m.addSetting(new MashSetting.BooleanSetting("brackets", "Style entre crochets", "Entoure la valeur [comme sur Lunar]", defBrackets));
        m.addSetting(new MashSetting.BooleanSetting("dot_indicator", "Pastille lumineuse", "Affiche le point coloré d'état à gauche", true));
    }

    // =========================================================================
    // 1. LES 65 MODULES HUD DÉPLAÇABLES & MAGNÉTIQUES
    // =========================================================================
    private static void registerAll65HudModules() {
        MashModule m;

        // 1..10
        m = regHud("hud_watermark", "Signature MashClient", "Badge MashClient discret", "watermark logo brand mash", true, 8, 8, 108, 19);
        m.addSetting(new MashSetting.ModeSetting("style", "Style du badge", "Format de la signature", 0, "MashClient", "MASH • FPS", "MASH"));
        m.addSetting(new MashSetting.BooleanSetting("show_fps_inline", "Inclure FPS en direct", "Ajoute les FPS dans la signature", false));

        m = regHud("hud_fps", "Compteur FPS", "Fréquence d'images instantanée sans allocation", "fps framerate images", true, 8, 30, 78, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.BooleanSetting("fast_color", "Couleur dynamique FPS", "Vert >144 FPS, Or >60 FPS, Rouge <60 FPS", false));

        m = regHud("hud_cps", "Compteur CPS", "Clics par seconde (clic gauche & clic droit)", "cps clicks clics", true, 8, 52, 86, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.BooleanSetting("right_cps", "Afficher Clic Droit", "Affiche LMB | RMB CPS", true));

        m = regHud("hud_ping", "Latence Ping", "Temps de réponse réseau en millisecondes", "ping ms latency latence", true, 8, 74, 78, 19);
        addCommonHudBadgeOptions(m, true, false);
        m.addSetting(new MashSetting.NumberSetting("warn_ms", "Seuil alerte jaune", "Colore le ping au-dessus de ce seuil", 120.0F, 40.0F, 300.0F, 10.0F, "ms"));

        m = regHud("hud_sprint", "Statut Sprint", "Affiche l'état actif de ToggleSprint / ToggleSneak", "sprint sneak status course", true, 8, 96, 98, 19);
        m.addSetting(new MashSetting.ModeSetting("format", "Format du texte", "Texte affiché en course", 0, "Sprint • Actif", "[Sprinting (Toggled)]", "Court (SPRINT)"));
        m.addSetting(new MashSetting.BooleanSetting("hide_idle", "Masquer à l'arrêt", "N'affiche la carte que lorsque Sprint est actif", false));

        m = regHud("hud_keystrokes", "Keystrokes Pro", "Touches ZQSD/WASD automatiques + LMB/RMB + barre Espace", "keystrokes touches clavier zqsd wasd", false, 8, 120, 78, 84);
        m.addSetting(new MashSetting.BooleanSetting("show_mouse", "Boutons souris LMB/RMB", "Affiche les deux boutons de clic sous les touches", true));
        m.addSetting(new MashSetting.BooleanSetting("show_cps_on_mouse", "CPS dans LMB/RMB", "Remplace le texte par le CPS en temps réel", true));
        m.addSetting(new MashSetting.BooleanSetting("show_space", "Barre d'espace", "Affiche la barre de saut en bas du pavé", true));
        m.addSetting(new MashSetting.ColorSetting("pressed_col", "Couleur touche pressée", "Couleur quand une touche est enfoncée", 0xE6F5B85C, false));

        m = regHud("hud_armor", "Armure HUD", "Pièces d'armure équipées et durabilité précise", "armor armure durabilite", true, 492, 468, 86, 22);
        m.addSetting(new MashSetting.ModeSetting("layout", "Orientation", "Disposition des 4 pièces d'armure", 0, "Horizontale", "Verticale"));
        m.addSetting(new MashSetting.ModeSetting("dura_mode", "Affichage durabilité", "Format d'usure des pièces", 0, "Barre Vanilla", "Points restants", "Pourcentage %"));
        m.addSetting(new MashSetting.BooleanSetting("include_hand", "Inclure l'objet en main", "Ajoute l'arme tenue à côté de l'armure", false));

        m = regHud("hud_potions", "Effets de Potion", "Liste active des potions avec durée restante", "potion effects effets speed", false, 8, 210, 112, 44);
        m.addSetting(new MashSetting.BooleanSetting("blink_low", "Clignoter < 8s", "Alerte visuelle avant expiration d'une potion", true));
        m.addSetting(new MashSetting.BooleanSetting("compact", "Mode compact", "Hauteur réduite par ligne", false));

        m = regHud("hud_coords", "Coordonnées XYZ", "Position exacte X, Y, Z dans le monde", "coords coordonnees xyz position", false, 8, 260, 118, 32);
        m.addSetting(new MashSetting.ModeSetting("layout", "Disposition", "Sur 1 ligne ou 3 lignes empilées", 0, "Compact 1 ligne", "3 lignes X/Y/Z"));
        m.addSetting(new MashSetting.NumberSetting("decimals", "Décimales", "Précision des coordonnées", 0.0F, 0.0F, 2.0F, 1.0F, ""));
        m.addSetting(new MashSetting.BooleanSetting("show_y", "Afficher altitude Y", "Inclure la coordonnée verticale Y", true));

        m = regHud("hud_direction", "Boussole & Direction", "Ruban azimutal N / E / S / O et cap exact", "direction compass boussole nord", false, 380, 8, 120, 20);
        m.addSetting(new MashSetting.BooleanSetting("show_deg", "Afficher l'angle en °", "Ajoute le cap numérique exact", true));
        m.addSetting(new MashSetting.ModeSetting("style", "Style boussole", "Ruban gradué ou badge compact", 0, "Ruban Lunar", "Badge Cardinal"));

        // 11..20
        m = regHud("hud_reach", "Reach Display", "Portée exacte en blocs de votre dernier coup PvP", "reach portee blocs hit", false, 92, 30, 84, 19);
        addCommonHudBadgeOptions(m, false, false);
        m.addSetting(new MashSetting.NumberSetting("decay_sec", "Délai de remise à zéro", "Temps avant affichage de 0.00 blocs", 3.5F, 1.0F, 10.0F, 0.5F, "s"));

        m = regHud("hud_combo", "Combo Counter", "Nombre de coups consécutifs portés sans être touché", "combo hits pvp", false, 92, 52, 84, 19);
        addCommonHudBadgeOptions(m, false, false);
        m.addSetting(new MashSetting.NumberSetting("reset_sec", "Expiration du combo", "Secondes sans coup avant réinitialisation", 4.0F, 1.5F, 10.0F, 0.5F, "s"));

        m = regHud("hud_speed", "Compteur de Vitesse", "Vitesse instantanée de déplacement du joueur", "speedometer vitesse bps kmh", false, 92, 74, 88, 19);
        m.addSetting(new MashSetting.ModeSetting("unit", "Unité de vitesse", "Unité affichée", 0, "m/s (BPS)", "km/h", "blocs/tick"));
        m.addSetting(new MashSetting.BooleanSetting("include_y", "Inclure vitesse verticale", "Prend en compte la chute et le saut", false));

        m = regHud("hud_clock", "Horloge Réelle", "Heure locale système en direct", "clock heure irl time", false, 92, 96, 76, 19);
        m.addSetting(new MashSetting.BooleanSetting("format_24h", "Format 24 heures", "Utilise 24h au lieu de AM/PM", true));
        m.addSetting(new MashSetting.BooleanSetting("show_sec", "Afficher les secondes", "Ajoute :ss", false));

        m = regHud("hud_game_time", "Heure Minecraft", "Heure du monde en jeu (Jour / Nuit)", "world time heure jeu soleil", false, 180, 30, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_memory", "Mémoire RAM JVM", "Occupation du tas Java G1GC en temps réel", "memory ram memoire jvm", false, 180, 52, 94, 19);
        m.addSetting(new MashSetting.ModeSetting("display", "Affichage", "Pourcentage ou Mégaoctets", 0, "RAM %", "Mo utilisés / Max"));

        m = regHud("hud_frametime", "Frametime (ms)", "Durée GPU/CPU par image en millisecondes", "frametime ms fluidite", false, 180, 74, 88, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_fps_graph", "Graphe FPS Live", "Courbe temps-réel de stabilité du framerate", "fps graph courbe", false, 8, 298, 112, 34);
        m.addSetting(new MashSetting.NumberSetting("target_fps", "Échelle cible FPS", "Plafond visuel du graphique", 360.0F, 120.0F, 1000.0F, 20.0F, " FPS"));

        m = regHud("hud_cps_graph", "Graphe CPS Live", "Courbe temps-réel de cadence de clic", "cps graph courbe clics", false, 8, 338, 112, 30);
        m.addSetting(new MashSetting.NumberSetting("max_cps", "Échelle max CPS", "Plafond visuel CPS", 20.0F, 10.0F, 30.0F, 2.0F, " CPS"));

        m = regHud("hud_server_ip", "Adresse Serveur", "Nom ou IP du serveur multijoueur actuel", "server ip serveur hypixel", false, 180, 96, 108, 19);
        addCommonHudBadgeOptions(m, false, false);

        // 21..30
        m = regHud("hud_tps", "TPS Serveur Estimé", "Cadence de ticks serveur mesurée côté client", "tps server lag", false, 292, 30, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pack_display", "Pack de Textures", "Nom du Resource Pack actif en haut de la pile", "pack resourcepack texture", false, 292, 52, 116, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_biome", "Biome Actuel", "Nom du biome dans lequel se trouve le joueur", "biome plaines desert", false, 292, 74, 104, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_held_item", "Objet en Main", "Icône, quantité et durabilité de l'objet tenu", "held item main epee", false, 292, 96, 106, 22);
        m.addSetting(new MashSetting.BooleanSetting("show_name", "Afficher le nom", "Affiche le nom de l'objet à droite de l'icône", true));

        m = regHud("hud_arrow_count", "Compteur de Flèches", "Total des flèches présentes dans votre inventaire", "arrow fleches arc bow", false, 404, 30, 74, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pot_count", "Compteur Potions Soin", "Nombre de potions Splash Instant Heal restantes", "potion count pots nodebuff", false, 404, 52, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_gapple_count", "Compteur Pommes d'Or", "Total des Golden Apples en inventaire", "gapple golden apple pommes or", false, 404, 74, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_pearl_count", "Compteur Ender Pearls", "Total des perles de l'End disponibles", "ender pearl perles", false, 404, 96, 80, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_soup_count", "Compteur de Soupes", "Nombre de soupes champignons prêtes (HG/KitPvP)", "soup soupes mushroom", false, 404, 118, 82, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_totem_block", "Compteur de Blocs", "Total des blocs posables (laine, pierre, grès) pour bridge", "blocks blocs laine rush bedwars", false, 404, 140, 86, 20);
        addCommonHudBadgeOptions(m, true, false);

        // 31..40
        m = regHud("hud_fireball_count", "Fireballs & TNT", "Stock d'explosifs BedWars dans l'inventaire", "fireball tnt bedwars", false, 404, 162, 88, 20);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_bw_resources", "Ressources BedWars", "Fer, Or, Diamant et Émeraude comptés en direct", "bedwars resources fer or diamant emeraude", false, 8, 374, 134, 21);
        m.addSetting(new MashSetting.BooleanSetting("hide_zero", "Masquer ressources à 0", "Affiche uniquement les minerais possédés", false));

        m = regHud("hud_session_timer", "Temps de Session", "Durée écoulée depuis l'ouverture de MashClient", "session time play timer", false, 492, 30, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_stopwatch", "Chronomètre Manuel", "Chronomètre précis pilotable par touche dédiée", "stopwatch chronometre speedrun", false, 492, 52, 92, 19);
        m.addSetting(new MashSetting.KeybindSetting("toggle_key", "Touche Start/Pause", "Démarre ou met en pause le chronomètre", Keyboard.KEY_P));
        m.addSetting(new MashSetting.KeybindSetting("reset_key", "Touche Remise à zéro", "Réinitialise le chronomètre à 00:00", Keyboard.KEY_O));

        m = regHud("hud_pitch_yaw", "Angles Yaw / Pitch", "Orientation angulaire précise de la caméra", "yaw pitch angle f3", false, 492, 74, 106, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_light_level", "Niveau de Lumière", "Éclairement du bloc aux pieds du joueur (0..15)", "light lumiere torches", false, 492, 96, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_saturation", "Jauge de Saturation", "Réserve de saturation alimentaire cachée", "saturation food faim", false, 492, 118, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_absorption", "Bouclier Absorption", "Points de vie dorés d'absorption actifs", "absorption coeurs or", false, 492, 140, 90, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_xp_info", "Progression XP", "Niveau et pourcentage précis jusqu'au prochain niveau", "xp experience niveau", false, 492, 162, 90, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_day_counter", "Jour du Monde", "Numéro du jour Minecraft actuel", "day jour monde survie", false, 492, 184, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        // 41..50
        m = regHud("hud_chunk_coords", "Chunk Actuel", "Indice du chunk [CX, CZ] et position locale 0..15", "chunk coords region", false, 492, 206, 104, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_entity_count", "Culling Entités", "Entités visibles et entités économisées par MashEngine", "entities culling entites", false, 492, 228, 108, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_vbo_stats", "Lots GPU MultiDraw", "Chunks regroupés et appels OpenGL par frame", "vbo gpu multidraw chunks", false, 492, 250, 112, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_cps_ratio", "Ratio Clics G/D", "Proportion entre clics d'attaque et de pose", "cps ratio butterfly", false, 492, 272, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_hit_counter", "Coups Portés", "Total d'attaques réussies durant la session", "hits coups attaques", false, 492, 294, 88, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_damage_taken", "Dernier Impact Subi", "Dégâts reçus sur le dernier coup en demi-cœurs", "damage degats coeurs", false, 492, 316, 94, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_target_hud", "Target Info (Viseur)", "Affiche le nom, la vie et la distance du joueur dans le viseur", "target hud cible vie", false, 380, 320, 128, 34);
        m.addSetting(new MashSetting.BooleanSetting("show_hearts", "Afficher barre de vie", "Dessine la jauge de santé de la cible visée", true));
        m.addSetting(new MashSetting.BooleanSetting("show_dist", "Afficher la distance", "Indique les mètres jusqu'à la cible", true));

        m = regHud("hud_mouse_stroker", "Radar Souris", "Visualise en direct le vecteur de déplacement de votre souris", "mouse stroker radar mouvement", false, 130, 120, 52, 52);
        m.addSetting(new MashSetting.NumberSetting("sens_vis", "Amplitude du point", "Échelle visuelle du déplacement", 1.0F, 0.3F, 3.0F, 0.1F, "x"));

        m = regHud("hud_wasd_mini", "Mini Pavé ZQSD", "Indicateur directionnel minimaliste ultra-compact", "mini wasd zqsd fleches", false, 190, 120, 44, 30);
        addCommonHudBadgeOptions(m, false, false);

        m = regHud("hud_sneak_badge", "Badge Accroupi", "Affiche clairement si vous êtes accroupi au bord d'un pont", "sneak bridge shift", false, 190, 156, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        // 51..65
        m = regHud("hud_fly_speed", "Vitesse de Vol", "Multiplicateur de vol actif en mode Créatif/Spectateur", "fly speed vol", false, 190, 178, 84, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_ping_graph", "Graphe Ping Réseau", "Courbe de stabilité de votre latence réseau", "ping graph reseau", false, 126, 298, 108, 30);
        m.addSetting(new MashSetting.NumberSetting("ceil_ms", "Échelle max ms", "Plafond du graphe de latence", 200.0F, 50.0F, 500.0F, 25.0F, "ms"));

        m = regHud("hud_packet_rate", "Stabilité Tick Client", "Cadence de simulation 20 TPS côté client", "tick rate stabilite", false, 190, 200, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_weather_badge", "Météo Monde", "État météorologique actuel du monde", "weather meteo pluie", false, 190, 222, 86, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_moon_phase", "Phase Lunaire", "Cycle lunaire actuel (utile pour les chunks à Slimes)", "moon lune slime", false, 190, 244, 98, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_helmet", "État Casque", "Points de durabilité exacts de votre casque", "helmet casque durabilite", false, 608, 30, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_chest", "État Plastron", "Points de durabilité exacts de votre plastron", "chestplate plastron durabilite", false, 608, 52, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_legs", "État Jambières", "Points de durabilité exacts de vos jambières", "leggings jambieres durabilite", false, 608, 74, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_durability_boots", "État Bottes", "Points de durabilité exacts de vos bottes", "boots bottes durabilite", false, 608, 96, 82, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_weapon_dura", "Durabilité Arme", "Usure restante sur l'arme ou outil tenu en main", "weapon sword durabilite epee", false, 608, 118, 92, 19);
        addCommonHudBadgeOptions(m, true, false);

        m = regHud("hud_hotbar_mirror", "Miroir Barre Rapide", "Aperçu déplaçable des 9 emplacements de la hotbar", "hotbar mirror barre rapide", false, 290, 410, 166, 22);
        m.addSetting(new MashSetting.BooleanSetting("highlight_slot", "Encadrer le slot actif", "Bordure dorée autour de l'objet sélectionné", true));

        m = regHud("hud_inventory_grid", "Vue Inventaire (9x3)", "Affiche le contenu de votre sac à dos en transparence", "inventory view inventaire sac", false, 8, 402, 166, 58);
        m.addSetting(new MashSetting.BooleanSetting("show_counts", "Afficher les quantités", "Dessine le nombre d'objets par pile", true));

        m = regHud("hud_pearl_cooldown", "Cooldown Ender Pearl", "Décompte automatique de 15s après un lancer de perle", "pearl cooldown perle timer", false, 608, 140, 96, 19);
        m.addSetting(new MashSetting.NumberSetting("duration", "Durée du cooldown", "Durée standard serveur", 15.0F, 5.0F, 30.0F, 1.0F, "s"));

        m = regHud("hud_gapple_cooldown", "Minuteur Régénération", "Décompte de la Régénération II après une Pomme d'Or", "gapple regen timer pomme", false, 608, 162, 98, 19);
        m.addSetting(new MashSetting.NumberSetting("duration", "Durée Régénération", "Secondes d'effet après consommation", 5.0F, 3.0F, 30.0F, 1.0F, "s"));

        m = regHud("hud_custom_label", "Badge Personnalisé", "Étiquette simple sur votre HUD", "custom text label badge", false, 608, 184, 108, 19);
        m.addSetting(new MashSetting.ModeSetting("preset_label", "Texte affiché", "Texte affiché dans le badge", 0, "MashClient", "MASH • FPS", "GG • FairPlay", "PvP"));
    }

    // =========================================================================
    // 2. LES 30 MODULES PVP & CAMÉRA
    // =========================================================================
    private static void registerAll30PvpModules() {
        MashModule m;

        MOD_CROSSHAIR = regMod("pvp_crosshair", "Curseur Personnalisé", "Viseur vectoriel haute précision (forme, écart, point, contour, chroma, dynamique)", "crosshair curseur viseur point croix", MashCategory.PVP, false);
        MOD_CROSSHAIR.addSetting(new MashSetting.ModeSetting("shape", "Forme du curseur", "Géométrie du viseur central", 1, "Croix", "Croix + Point", "Point seul", "Cercle", "Chevron ^", "Carré"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("size", "Longueur des branches", "Taille des segments ou rayon du cercle", 4.5F, 1.0F, 18.0F, 0.5F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("gap", "Écart central", "Espace vide au centre du viseur", 2.5F, 0.0F, 14.0F, 0.5F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("thickness", "Épaisseur du trait", "Finesse vectorielle des traits", 1.2F, 0.5F, 4.5F, 0.1F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("outline", "Contour sombre net", "Ajoute un liseré noir haute lisibilité", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.NumberSetting("outline_px", "Épaisseur du contour", "Largeur du contour noir", 1.0F, 0.5F, 2.5F, 0.25F, "px"));
        MOD_CROSSHAIR.addSetting(new MashSetting.ColorSetting("color", "Couleur du curseur", "Couleur principale ou arc-en-ciel Chroma", 0xFFFFFFFF, false));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("target_highlight", "Colorer sur cible", "Change de couleur quand vous visez un joueur/mob", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.ColorSetting("target_col", "Couleur sur cible", "Couleur d'alerte sur ennemi visé", 0xFFF87171, false));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("dynamic_spread", "Écartement dynamique", "S'ouvre légèrement lors des sauts/tirs à l'arc", true));
        MOD_CROSSHAIR.addSetting(new MashSetting.BooleanSetting("show_f5", "Afficher en vue F5", "Garde le viseur visible à la 3e personne", false));

        MOD_PVP_FOV = regMod("pvp_fov", "Champ de Vision PvP (FOV)", "Contrôle indépendant de votre FOV de base, Speed I/II, Sprint, Arc et Lenteur", "fov changer champ vision speed dynamic", MashCategory.PVP, false);
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("override_base", "Forcer ma FOV de base", "Remplace la FOV Vanilla par la valeur exacte ci-dessous", true));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("base_fov", "FOV de base PvP", "Angle exact de caméra (70=Normal, 90=PvP, 110=Quake Pro)", 90.0F, 30.0F, 150.0F, 1.0F, "°"));
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("dynamic_fov", "FOV Dynamique personnalisée", "Si désactivé, verrouille la FOV fixe en permanence", true));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("speed1_mult", "Multiplicateur Speed I", "Facteur FOV sous potion Rapidité I", 1.08F, 0.85F, 1.35F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("speed2_mult", "Multiplicateur Speed II", "Facteur FOV sous potion Rapidité II", 1.14F, 0.85F, 1.45F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("sprint_boost", "Boost FOV en Sprint", "Ouverture de caméra pendant la course", 0.07F, 0.00F, 0.25F, 0.01F, ""));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("bow_zoom", "Zoom Visée Arc", "Rétrécissement FOV en bandant un arc", 0.85F, 0.55F, 1.00F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.NumberSetting("fly_mult", "Multiplicateur en Vol", "Ouverture FOV en vol créatif/lobby", 1.04F, 0.90F, 1.25F, 0.01F, "x"));
        MOD_PVP_FOV.addSetting(new MashSetting.BooleanSetting("anti_slowness", "Bloquer rétrécissement Lenteur", "Empêche les effets de Lenteur d'écraser la FOV", true));

        MOD_ZOOM = regMod("pvp_zoom", "Zoom Optique Pro", "Zoom ultra-fluide avec choix d'utiliser la molette pour zoomer OU changer de slot hotbar", "zoom optique molette scroll c", MashCategory.PVP, true);
        MOD_ZOOM.addSetting(new MashSetting.KeybindSetting("keybind", "Touche de Zoom", "Touche à maintenir pour zoomer", Keyboard.KEY_C));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("base_zoom", "Facteur de zoom initial", "Grossissement dès l'appui sur la touche", 4.0F, 1.5F, 15.0F, 0.5F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("scroll_zoom", "Zoomer avec la molette", "ON = la molette ajuste le zoom • OFF = la molette change de slot dans la barre !", true));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("scroll_step", "Sensibilité molette zoom", "Vitesse d'agrandissement par cran de molette", 1.18F, 1.05F, 1.50F, 0.02F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("max_zoom", "Grossissement maximum", "Limite maximale du zoom molette", 18.0F, 4.0F, 35.0F, 1.0F, "x"));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("smooth_anim", "Animation fluide", "Interpolation exponentielle sans saccade", true));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("anim_speed", "Vitesse de transition", "Réactivité de l'entrée/sortie de zoom", 18.0F, 5.0F, 40.0F, 1.0F, ""));
        MOD_ZOOM.addSetting(new MashSetting.BooleanSetting("cinematic_cam", "Caméra cinématique lissée", "Active le lissage souris type OptiFine pendant le zoom", false));
        MOD_ZOOM.addSetting(new MashSetting.NumberSetting("mouse_sens", "Sensibilité souris en zoom", "Adapte la vitesse de visée pendant le zoom", 65.0F, 15.0F, 100.0F, 5.0F, "%"));

        MOD_TOGGLE_SPRINT = regMod("pvp_toggle_sprint", "Toggle Sprint & Sneak", "Maintient automatiquement la course ou l'accroupissement (100% Vanilla KeyBinding)", "toggle sprint sneak course auto", MashCategory.PVP, true);
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("sprint_on", "Activer Toggle Sprint", "Une pression sur votre touche Sprint bascule la course", true));
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("sneak_on", "Activer Toggle Sneak", "Désactivé automatiquement dès qu'un coffre/inventaire s'ouvre", false));
        MOD_TOGGLE_SPRINT.addSetting(new MashSetting.BooleanSetting("fly_keep", "Actif pendant le vol", "Maintient le sprint en vol créatif/spectateur", true));

        MOD_HIT_COLOR = regMod("pvp_hit_color", "Couleur d'Impact (Hit Color)", "Personnalise le flash rouge lorsqu'un joueur subit un coup", "hit color damage flash rouge impact", MashCategory.PVP, false);
        MOD_HIT_COLOR.addSetting(new MashSetting.ColorSetting("tint_col", "Couleur du flash", "Teinte et opacité lors d'un coup réussi", 0x66FF2A2A, false));
        MOD_HIT_COLOR.addSetting(new MashSetting.BooleanSetting("tint_armor", "Colorer aussi l'armure", "Applique la teinte sur les pièces d'armure", true));

        MOD_LOW_FIRE = regMod("pvp_low_fire", "Feu Bas (Low Fire)", "Abaisse les flammes en 1re personne pour dégager totalement la vue en PvP", "low fire feu bas flammes", MashCategory.PVP, true);
        MOD_LOW_FIRE.addSetting(new MashSetting.NumberSetting("fire_height", "Décalage vertical du feu", "Plus la valeur est basse, plus le feu descend", -0.26F, -0.55F, 0.00F, 0.02F, ""));
        MOD_LOW_FIRE.addSetting(new MashSetting.BooleanSetting("hide_creative", "Masquer en Créatif", "Cache les flammes d'écran en mode Créatif", true));

        MOD_NO_HURTCAM = regMod("pvp_no_hurtcam", "Contrôle HurtCam", "Supprime ou réduit le tremblement d'écran lorsque vous recevez des dégâts", "no hurtcam shake tremblement degats", MashCategory.PVP, false);
        MOD_NO_HURTCAM.addSetting(new MashSetting.NumberSetting("shake_pct", "Intensité de secousse", "0% = aucune secousse • 50% = réduite • 100% = Vanilla", 0.0F, 0.0F, 100.0F, 5.0F, "%"));

        MOD_VIEWMODEL = regMod("pvp_viewmodel", "Position de la Main (Viewmodel)", "Ajuste la position XYZ et la taille de votre épée/objet en vue subjective", "viewmodel hand item position main epee", MashCategory.PVP, false);
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_x", "Décalage Horizontal X", "Gauche <-> Droite", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_y", "Décalage Vertical Y", "Bas <-> Haut", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("offset_z", "Profondeur Z", "Proche <-> Éloigné", 0.0F, -0.80F, 0.80F, 0.02F, ""));
        MOD_VIEWMODEL.addSetting(new MashSetting.NumberSetting("item_scale", "Taille de l'objet", "Échelle de l'arme en main", 1.0F, 0.50F, 1.50F, 0.05F, "x"));

        m = regMod("pvp_old_animations", "Animations 1.7 Classiques", "Restaure le ressenti visuel fluide des animations PvP 1.7", "1.7 old animations blockhit", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("block_hit", "Parade d'épée fluide", "Animation de parade nette en combat", true));
        m.addSetting(new MashSetting.BooleanSetting("bow_17", "Posture d'arc 1.7", "Inclinaison classique de l'arc", true));
        m.addSetting(new MashSetting.BooleanSetting("eat_17", "Nourriture & Potions 1.7", "Animation de consommation classique", true));

        m = regMod("pvp_hitbox", "Hitboxes Personnalisées", "Personnalise l'affichage des boîtes de collision (F3+B)", "hitbox hitboxes collision f3b", MashCategory.PVP, false);
        m.addSetting(new MashSetting.ColorSetting("box_col", "Couleur de la Hitbox", "Couleur du cadre 3D", 0xFFFFFFFF, false));
        m.addSetting(new MashSetting.BooleanSetting("eye_line", "Ligne de hauteur des yeux", "Affiche l'anneau rouge des yeux", true));
        m.addSetting(new MashSetting.BooleanSetting("look_vec", "Vecteur de regard", "Affiche la direction dans laquelle regarde le joueur", false));

        // 11..30 PvP
        m = regMod("pvp_particles_mult", "Particules de Coup (Crit/Sharp)", "Accentue la lisibilité des particules d'impact en duel", "particles multiplier crit sharpness", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("mult", "Multiplicateur visuel", "Nombre de particules par coup", 2.0F, 1.0F, 4.0F, 1.0F, "x"));
        m.addSetting(new MashSetting.BooleanSetting("always_sharp", "Toujours afficher Sharpness", "Affiche les étoiles bleues sur chaque coup d'épée", false));

        MOD_FREELOOK = regMod("pvp_freelook", "Vue Libre 360° (Perspective)", "Observez autour de vous à 360° en courant tout droit (caméra 100% locale)", "freelook perspective 360 alt camera", MashCategory.PVP, true);
        MOD_FREELOOK.addSetting(new MashSetting.KeybindSetting("keybind", "Touche Freelook", "Maintenir pour tourner la caméra autour du joueur", Keyboard.KEY_LMENU));
        MOD_FREELOOK.addSetting(new MashSetting.BooleanSetting("invert_y", "Inverser l'axe vertical", "Inverse Haut/Bas pendant la vue libre", false));

        m = regMod("pvp_snaplook", "Coup d'Œil Arrière", "Vue arrière instantanée tant que la touche est maintenue", "snaplook rear view arriere", MashCategory.PVP, false);
        m.addSetting(new MashSetting.KeybindSetting("keybind", "Touche Vue Arrière", "Maintenir pour regarder derrière soi", Keyboard.KEY_V));

        MOD_BOW_CHARGE = regMod("pvp_bow_charge_tint", "Indicateur Charge d'Arc", "Affiche un arc lumineux Rouge -> Or -> Vert sous le viseur en bandant un arc", "bow charge arc tension", MashCategory.PVP, true);
        MOD_BOW_CHARGE.addSetting(new MashSetting.NumberSetting("radius", "Rayon de l'indicateur", "Distance autour du centre de l'écran", 10.0F, 5.0F, 24.0F, 1.0F, "px"));

        m = regMod("pvp_shield_sword_pos", "Angle de Parade Épée", "Ajuste la hauteur de l'épée pendant le clic droit de parade", "sword block parade angle", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("lower", "Abaissement parade", "Dégage le centre de l'écran en parant", 0.10F, 0.00F, 0.35F, 0.05F, ""));

        MOD_DAMAGE_TINT = regMod("pvp_damage_tint", "Alerte Vie Critique", "Halo rouge subtil sur les bords de l'écran lorsque vos PV sont bas", "damage tint low health vie critique", MashCategory.PVP, false);
        MOD_DAMAGE_TINT.addSetting(new MashSetting.NumberSetting("hearts_thresh", "Seuil d'alerte (cœurs)", "Active l'alerte sous ce nombre de cœurs", 3.5F, 1.0F, 8.0F, 0.5F, " cœurs"));

        m = regMod("pvp_rod_tracer_vis", "Hameçon Haute Visibilité", "Rend le flotteur de la canne à pêche plus net sur fond sombre", "fishing rod canne flotteur", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("scale", "Échelle flotteur", "Taille visuelle du bouchon", 1.15F, 0.8F, 1.6F, 0.05F, "x"));

        m = regMod("pvp_potion_glint_col", "Reflet Enchanté Custom", "Personnalise l'intensité et la couleur du reflet d'enchantement", "glint color enchantement reflet", MashCategory.PVP, false);
        m.addSetting(new MashSetting.ColorSetting("col", "Couleur du reflet", "Teinte de brillance magique", 0xFFA855F7, false));
        m.addSetting(new MashSetting.NumberSetting("strength", "Intensité brillance", "Opacité du reflet", 75.0F, 10.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_minimal_bobbing", "Minimal View Bobbing", "Conserve le balancement naturel de la main sans faire trembler le viseur !", "minimal bobbing tremblement marche", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_hand", "Animer la main", "Garde le mouvement d'arme en marchant", true));

        m = regMod("pvp_tall_grass_clear", "Visibilité Hautes Herbes", "Atténue le cadre de sélection des herbes en plein combat", "tall grass herbes fleurs", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("only_sword", "Uniquement épée en main", "Actif seulement avec une arme mêlée", true));

        m = regMod("pvp_sound_hit_confirm", "Clarté Audio Impacts", "Équilibre le volume des impacts PvP face aux bruits ambiants", "hit sound audio impact", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("pitch", "Tonalité audio", "Hauteur sonore", 1.0F, 0.7F, 1.4F, 0.05F, "x"));

        m = regMod("pvp_team_marker", "Repère Couleur d'Équipe", "Souligne les pseudos des joueurs portant la même couleur d'équipe", "team color equipe allie", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("match_armor", "Détecter plastron cuir", "Identifie la couleur du cuir teint BedWars", true));

        m = regMod("pvp_respawn_fast_ui", "Écran de Mort Clair", "Adoucit le filtre rouge de l'écran Game Over", "death screen mort respawn", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("opacity", "Opacité voile rouge", "Transparence de l'arrière-plan", 45.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_cursor_lock_fix", "Stabilisateur Curseur Coffre", "Évite les sauts brusques de caméra à la fermeture d'un inventaire", "raw mouse cursor jump fix", MashCategory.PVP, true);
        m.addSetting(new MashSetting.BooleanSetting("center_exact", "Centrage pixel exact", "Replace le curseur au centre exact de la fenêtre", true));

        m = regMod("pvp_sprint_particles", "Masquer Particules de Pas", "Cache vos propres particules de course au sol pour garder l'écran propre", "footstep sprint particles pas", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("first_person_only", "Uniquement en 1re personne", "Les réaffiche en vue F5", true));

        m = regMod("pvp_bow_trajectory_fov", "Visée Arc Stable", "Stabilise la vitesse angulaire de la souris lorsque l'arc est tendu", "bow aim sensibilite arc", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("damp", "Douceur visée", "Facteur d'amortissement", 85.0F, 50.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_hotbar_lock", "Protection Drop Épée", "Empêche de jeter son épée par erreur d'un simple appui", "slot lock drop sword protection", MashCategory.PVP, false);
        m.addSetting(new MashSetting.NumberSetting("delay_ms", "Fenêtre double appui", "Délai max pour confirmer le jet", 450.0F, 200.0F, 1000.0F, 50.0F, "ms"));

        m = regMod("pvp_clean_water_overlay", "Eau Claire (Clear Water)", "Allège le voile bleu sombre sous l'eau pour mieux voir en UHC/SG", "clear water eau claire", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("clarity", "Clarté sous-marine", "Réduction de l'opacité du masque", 70.0F, 10.0F, 100.0F, 5.0F, "%"));

        m = regMod("pvp_clean_pumpkin", "Citrouille Transparente", "Rend la vue dégagée même en portant une citrouille sur la tête", "pumpkin overlay citrouille", MashCategory.PVP, true);
        m.addSetting(new MashSetting.NumberSetting("opacity", "Opacité du masque", "0% = totalement invisible", 0.0F, 0.0F, 100.0F, 10.0F, "%"));

        m = regMod("pvp_low_shield_fire_ext", "Indicateur Fin de Brûlure", "Affiche un bref contour apaisé dès que le feu s'éteint sur vous", "fire extinguish brulure", MashCategory.PVP, false);
        m.addSetting(new MashSetting.BooleanSetting("flash_cool", "Halo bleu discret", "Indication visuelle subtile", true));
    }

    // =========================================================================
    // 3. LES 35 MODULES VISUEL & INTERFACE
    // =========================================================================
    private static void registerAll35VisualModules() {
        MashModule m;

        MOD_FULLBRIGHT = regMod("vis_fullbright", "Fullbright Pro", "Éclairage maximal dans les grottes et maps sombres sans altérer options.txt", "fullbright gamma lumiere grottes", MashCategory.VISUAL, true);
        MOD_FULLBRIGHT.addSetting(new MashSetting.NumberSetting("brightness", "Intensité lumineuse", "Puissance de l'éclairage maximal", 100.0F, 20.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_block_overlay", "Contour de Bloc Custom", "Personnalise la bordure du bloc visé (épaisseur, couleur, remplissage)", "block overlay outline contour bloc", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("line_width", "Épaisseur du contour", "Largeur des arêtes du bloc", 2.0F, 1.0F, 5.0F, 0.5F, "px"));
        m.addSetting(new MashSetting.ColorSetting("line_col", "Couleur du contour", "Couleur des arêtes ou Chroma", 0xCCF5B85C, false));
        m.addSetting(new MashSetting.BooleanSetting("fill_faces", "Remplir les faces", "Ajoute un voile translucide sur le bloc", false));
        m.addSetting(new MashSetting.ColorSetting("fill_col", "Couleur de remplissage", "Voile semi-transparent", 0x26F5B85C, false));

        MOD_TIME_CHANGER = regMod("vis_time_changer", "Contrôleur d'Heure (Time Changer)", "Fixe l'heure du ciel côté client (Midi, Coucher de soleil, Nuit étoilée)", "time changer heure ciel nuit jour", MashCategory.VISUAL, false);
        MOD_TIME_CHANGER.addSetting(new MashSetting.ModeSetting("preset", "Ambiance céleste", "Préréglage horaire client", 0, "Midi (Soleil haut)", "Coucher de soleil doré", "Minuit étoilé", "Aube claire", "Heure exacte"));
        MOD_TIME_CHANGER.addSetting(new MashSetting.NumberSetting("exact_time", "Heure personnalisée", "Utilisé lorsque le mode Heure exacte est choisi", 6000.0F, 0.0F, 24000.0F, 250.0F, "t"));

        MOD_WEATHER_CHANGER = regMod("vis_weather_changer", "Contrôleur Météo", "Force un ciel dégagé sans pluie gourmande en FPS ou choisit la météo client", "weather changer meteo pluie neige", MashCategory.VISUAL, false);
        MOD_WEATHER_CHANGER.addSetting(new MashSetting.ModeSetting("mode", "Météo forcée", "État météo local", 0, "Ciel Dégagé (0 Pluie)", "Pluie Douce", "Orage Dramatique"));

        m = regMod("vis_nametags", "Nametags Personnalisés", "Étiquettes de pseudos optimisées, ombre nette et pseudo personnel en F5", "nametags pseudos noms joueurs", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("own_tag_f5", "Voir mon pseudo en F5", "Affiche votre nametag à la 3e personne", true));
        m.addSetting(new MashSetting.BooleanSetting("transparent_bg", "Fond transparent", "Masque le rectangle gris derrière les noms", false));
        m.addSetting(new MashSetting.BooleanSetting("text_shadow", "Ombre portée du texte", "Améliore la netteté des pseudos", true));

        MOD_SCOREBOARD = regMod("vis_scoreboard", "Scoreboard Pro", "Scoreboard 1-Draw GPU sans numéros rouges à droite et position réglable", "scoreboard sidebar points rouge", MashCategory.VISUAL, true);
        MOD_SCOREBOARD.addSetting(new MashSetting.BooleanSetting("hide_red_nums", "Masquer les chiffres rouges", "Cache la colonne de scores rouges inutiles à droite", true));
        MOD_SCOREBOARD.addSetting(new MashSetting.NumberSetting("offset_y", "Décalage vertical Y", "Monte ou descend le scoreboard", 0.0F, -120.0F, 120.0F, 4.0F, "px"));
        MOD_SCOREBOARD.addSetting(new MashSetting.BooleanSetting("hide_all", "Masquer complètement", "Cache totalement le scoreboard", false));

        m = regMod("vis_bossbar", "Barre de Boss Pro", "Redimensionne ou masque la barre de boss en haut de l'écran", "bossbar boss dragon wither", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.ModeSetting("mode", "Affichage", "Complet, Texte seul ou Masqué", 0, "Barre + Texte", "Texte uniquement", "Masquer tout"));
        m.addSetting(new MashSetting.NumberSetting("scale", "Échelle Barre de Boss", "Taille de la barre supérieure", 1.0F, 0.5F, 1.3F, 0.05F, "x"));

        MOD_TAB_LIST = regMod("vis_tab_list", "Liste Tab Pro (Ping Chiffré)", "Remplace les barres vagues de la touche Tab par le vrai ping en ms coloré !", "tab list tablist ping chiffres joueurs", MashCategory.VISUAL, true);
        MOD_TAB_LIST.addSetting(new MashSetting.BooleanSetting("numeric_ping", "Ping en chiffres (ms)", "Affiche ex: 22ms en vert/or/rouge dans Tab", true));
        MOD_TAB_LIST.addSetting(new MashSetting.BooleanSetting("show_header", "Afficher l'en-tête serveur", "Conserve le titre supérieur du Tab", true));

        m = regMod("vis_tnt_timer", "Compte à Rebours TNT", "Affiche le temps exact avant explosion au-dessus des TNT amorcées", "tnt timer explosion bedwars", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("color_code", "Dégradé Vert -> Rouge", "Change de couleur à l'approche de 0.00s", true));

        m = regMod("vis_item_physics", "Objets au Sol Optimisés", "Rendu fluide et épuré des objets jetés au sol", "item physics drops objets sol", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("spin_speed", "Vitesse de rotation", "Rotation visuelle des drops", 1.0F, 0.0F, 2.0F, 0.1F, "x"));

        // 11..35 Visuel
        m = regMod("vis_motion_blur", "Fluidité Cinétique Douce", "Lissage temporel léger à haut taux de rafraîchissement", "motion blur flou fluidite", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("amount", "Intensité du flou", "Niveau de persistance", 25.0F, 5.0F, 70.0F, 5.0F, "%"));

        m = regMod("vis_menu_blur", "Fond Obsidienne des Menus", "Assombrissement élégant derrière les coffres et inventaires", "menu background blur inventaire", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("darkness", "Opacité du voile", "Assombrissement arrière-plan", 70.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_inv_shift_fix", "Inventaire Toujours Centré", "Empêche les potions actives de décaler votre inventaire sur le côté", "inventory shift potion centre", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_badges", "Garder les icônes potions", "Affiche toujours les potions à gauche sans pousser la grille", true));

        m = regMod("vis_scrollable_tooltips", "Infobulles Confort", "Améliore la lisibilité des longues descriptions d'objets enchantés", "scrollable tooltips descriptions skyblock", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("speed", "Pas de défilement", "Pixels par cran de molette", 14.0F, 6.0F, 32.0F, 2.0F, "px"));

        m = regMod("vis_clear_glass", "Vitres & Glaces Nettes", "Rendu net des contours de verre à moyenne et longue distance", "clear glass verre vitres", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("tinted", "Inclure verres teintés", "Applique aussi aux vitres colorées", true));

        m = regMod("vis_sky_color", "Teinte Céleste Custom", "Personnalise subtilement l'atmosphère du ciel", "sky color couleur ciel", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.ColorSetting("zenith", "Couleur Zénith", "Haut de la voûte céleste", 0xFF78A7FF, false));

        m = regMod("vis_fog_customizer", "Contrôle du Brouillard", "Ajuste ou recule le brouillard de distance pour une vue limpide", "fog brouillard distance", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("density", "Densité du brouillard", "Plus bas = horizon plus net", 65.0F, 0.0F, 120.0F, 5.0F, "%"));

        m = regMod("vis_hurt_flash_sky", "Anti-Flash Foudre", "Supprime l'éblouissement blanc soudain lors des coups de tonnerre", "lightning flash foudre orage", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_bolt", "Garder l'éclair 3D", "Affiche l'arc électrique sans flasher le ciel", true));

        m = regMod("vis_void_tint", "Horizon du Vide Propre", "Élimine le dôme noir artificiel sous Y=60 sur les îles SkyWars/BedWars", "void sky horizon vide noir", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("smooth_horizon", "Continuité du ciel", "Prolonge la couleur du ciel vers le bas", true));

        m = regMod("vis_glint_particles", "Particules Magiques Douces", "Régule la densité des runes d'enchantement et portails", "enchant portal particles", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("rate", "Densité d'émission", "Pourcentage de particules conservées", 50.0F, 10.0F, 100.0F, 10.0F, "%"));

        m = regMod("vis_saturation_color", "Éclat Visuel UI", "Rehausse le contraste des textes et icônes en jeu", "vibrance contrast eclat", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("contrast", "Contraste typographique", "Clarté des polices", 100.0F, 80.0F, 120.0F, 5.0F, "%"));

        m = regMod("vis_vignette_ctrl", "Suppression Vignette Sombre", "Économise une passe plein-écran GPU tout en gardant l'alerte WorldBorder", "vignette coins sombres fps", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_border_warn", "Garder alerte WorldBorder", "Affiche le halo rouge à l'approche de la bordure", true));

        m = regMod("vis_titles_scale", "Titres Centraux Redimensionnés", "Empêche les annonces géantes du serveur de masquer votre combat", "titles scale sous-titres annonces", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("scale", "Taille des titres", "Réduit l'encombrement central", 0.75F, 0.30F, 1.10F, 0.05F, "x"));

        m = regMod("vis_hotbar_style", "Barre Rapide Épurée", "Améliore la lisibilité du slot sélectionné dans la hotbar", "hotbar style slots", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("slot_numbers", "Afficher numéros 1..9", "Affiche discrètement le raccourci de chaque case", true));

        m = regMod("vis_xp_bar_clean", "Barre d'XP Claire", "Rendu net du chiffre de niveau d'expérience", "xp bar experience", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.ColorSetting("num_col", "Couleur du niveau", "Couleur du chiffre central", 0xFF80FF20, false));

        m = regMod("vis_crosshair_f3", "Repère 3D F3 Discret", "Évite que l'axe 3D géant en mode F3 ne cache votre cible", "f3 crosshair axes", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("scale", "Taille du repère", "Échelle des 3 axes RGB", 0.70F, 0.30F, 1.00F, 0.05F, "x"));

        m = regMod("vis_rain_splash", "Pluie au Sol Optimisée", "Réduit les éclaboussures coûteuses par temps d'orage", "rain splash gouttes sol", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("density", "Densité au sol", "Quantité de gouttelettes", 35.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("vis_cloud_height", "Altitude des Nuages", "Remonte les nuages au-dessus des tours de construction", "cloud height nuages hauteur", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.NumberSetting("offset", "Élévation nuages", "Blocs ajoutés à Y=128", 48.0F, 0.0F, 128.0F, 8.0F, "m"));

        m = regMod("vis_cape_physics", "Capes Haute Fréquence", "Mouvement fluide des capes sans tremblement à 300+ FPS", "cape physics manteau", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("cull_dist", "Distance max capes", "Masque les capes très lointaines pour le GPU", 48.0F, 16.0F, 96.0F, 4.0F, "m"));

        m = regMod("vis_armor_glint_toggle", "Lisibilité Armures d'Équipe", "Atténue le reflet violet sur les armures lointaines pour lire leur couleur", "armor glint team color", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("dist", "Portée reflet armure", "Distance au-delà de laquelle le reflet s'estompe", 20.0F, 8.0F, 64.0F, 4.0F, "m"));

        m = regMod("vis_bed_hide_plates", "Texte Panneaux Intelligent", "Ne dessine les 4 lignes de texte des pancartes qu'à portée de lecture", "signs text panneaux pancartes", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("read_dist", "Portée de lecture", "Distance d'affichage du texte", 24.0F, 8.0F, 64.0F, 2.0F, "m"));

        m = regMod("vis_beacon_beam", "Contrôle Faisceau Balise", "Allège le rayon lumineux des Beacons en fin de partie", "beacon beam balise rayon", MashCategory.VISUAL, false);
        m.addSetting(new MashSetting.BooleanSetting("hide_beam", "Masquer le faisceau", "Cache le faisceau vertical tout en gardant le bloc", false));

        m = regMod("vis_falling_blocks", "Blocs Tombants Fluides", "Optimise le rendu du sable, gravier et enclumes en chute", "falling sand gravel blocs", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_light", "Éclairage direct", "Évite le recalcul coûteux par tick", true));

        m = regMod("vis_fireworks_ctrl", "Anti-Lag Feux d'Artifice", "Plafonne les milliers d'étincelles lors des célébrations de victoire", "fireworks victoire lag particules", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.NumberSetting("cap_pct", "Plafond étincelles", "Proportion maximale affichée", 40.0F, 10.0F, 100.0F, 10.0F, "%"));

        m = regMod("vis_gui_font_hd", "Typographie HD MashClient", "Police vectorielle lissée Segoe UI 3x sur le HUD et les menus", "font hd police lisse", MashCategory.VISUAL, true);
        m.addSetting(new MashSetting.BooleanSetting("hud_smooth", "Activer sur le HUD", "Utilise la police HD sur tous les widgets", true));
    }

    // =========================================================================
    // 4. LES 25 MODULES HYPIXEL & CHAT PRO
    // =========================================================================
    private static void registerAll25HypixelModules() {
        MashModule m;

        MOD_CHAT_PRO = regMod("hyp_chat_pro", "Chat Pro (Lunar Style)", "Fond transparent, horodatage optionnel et fluidité totale du chat", "chat transparent timestamps horodatage", MashCategory.HYPIXEL, true);
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("transparent_bg", "Fond du chat transparent", "Supprime le bloc noir opaque derrière les messages", false));
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("timestamps", "Horodatage [HH:mm]", "Préfixe l'heure de réception des messages", false));
        MOD_CHAT_PRO.addSetting(new MashSetting.BooleanSetting("text_shadow", "Ombre nette des messages", "Garantit la lecture sur tous les terrains", true));

        m = regMod("hyp_autogg", "Auto GG Fair-Play", "Affiche un rappel fair-play en fin de partie sans spammer en combat", "autogg gg fin partie hypixel", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ModeSetting("phrase", "Formule de courtoisie", "Texte préféré", 0, "gg", "gf", "Bien joué !"));
        m.addSetting(new MashSetting.NumberSetting("delay_s", "Délai naturel", "Attente après la fin de manche", 1.2F, 0.5F, 3.0F, 0.1F, "s"));

        m = regMod("hyp_autotip_reminder", "Suivi Karma & Récompenses", "Indicateur discret des sessions longues sur réseaux mini-jeux", "autotip karma hypixel", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("interval_min", "Intervalle rappel", "Minutes entre deux rappels", 15.0F, 5.0F, 60.0F, 5.0F, "m"));

        MOD_NICK_HIDER = regMod("hyp_nick_hider", "Nick Hider (Mode Streamer)", "Remplace votre pseudo à l'écran pour protéger vos sessions ou streams", "nick hider streamer pseudo cache", MashCategory.HYPIXEL, false);
        MOD_NICK_HIDER.addSetting(new MashSetting.ModeSetting("alias", "Pseudo affiché", "Alias visuel local", 0, "MashPlayer", "Vous", "Mash", "Joueur"));

        m = regMod("hyp_levelhead_tab", "Mise en Valeur Ping Tab", "Code couleur instantané de la qualité de connexion des adversaires", "ping color tablist latence", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("good_ms", "Seuil Ping Excellent", "Affiché en vert émeraude", 55.0F, 20.0F, 100.0F, 5.0F, "ms"));

        m = regMod("hyp_bedwars_team_color", "Harmonie Équipe BedWars", "Synchronise les repères visuels avec la couleur de votre armure", "bedwars team sync couleur lit", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("auto_detect", "Détection automatique", "Lit la couleur du casque/plastron équipé", true));

        MOD_UHC_OVERLAY = regMod("hyp_uhc_overlay", "UHC Gold & Apple Overlay", "Agrandit légèrement les pommes d'or et lingots au sol pour mieux les voir", "uhc overlay golden apple or", MashCategory.HYPIXEL, false);
        MOD_UHC_OVERLAY.addSetting(new MashSetting.NumberSetting("scale", "Échelle objets précieux", "Facteur d'agrandissement au sol", 1.35F, 1.05F, 1.80F, 0.05F, "x"));

        m = regMod("hyp_skywars_perk_timer", "Indicateur Force / Bulldozer", "Met en avant les effets courts décisifs après une élimination", "skywars bulldozer strength force", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("warn_s", "Durée suivie", "Secondes d'effet", 5.0F, 2.0F, 10.0F, 1.0F, "s"));

        m = regMod("hyp_bridge_goal_alert", "Alerte Limite de Hauteur", "Prévient visuellement lorsque vous approchez du plafond de blocs", "build height limit bridge bedwars", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("warn_margin", "Marge avant limite", "Blocs sous le plafond avant alerte", 5.0F, 2.0F, 15.0F, 1.0F, "m"));

        m = regMod("hyp_chat_copy", "Sélection & Copie Chat", "Facilite la copie d'informations ou coordonnées depuis le chat", "chat copy copier presse-papiers", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("strip_colors", "Retirer les codes §", "Copie un texte propre sans symboles de couleur", true));

        m = regMod("hyp_compact_join_leave", "Lobby Sans Spam Connexions", "Allège les messages d'arrivée dans les grands halls d'attente", "lobby join spam filtre", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("keep_friends", "Toujours garder Amis/Party", "Ne masque jamais vos coéquipiers", true));

        m = regMod("hyp_friend_highlight", "Alerte Mention Pseudo", "Met en évidence lorsqu'un joueur écrit votre nom dans le chat", "mention highlight ping pseudo", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.ColorSetting("col", "Couleur de surbrillance", "Couleur d'accentuation", 0xFFF5B85C, false));

        m = regMod("hyp_party_chat_badge", "Clarté Canaux Party & Guild", "Distingue visuellement les messages d'équipe des messages globaux", "party guild chat canal", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("bold_tag", "Préfixe contrasté", "Rend le canal immédiatement identifiable", true));

        m = regMod("hyp_mystery_dust_mute", "Calme Audio dans les Lobbys", "Atténue les explosions sonores décoratives des lobbys bondés", "lobby sounds mute coffres", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume décoratif lobby", "Réduction sonore hors partie", 25.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("hyp_bed_destroy_alert", "Clarté Alerte Lit Détruit", "Garde l'alerte de destruction de lit lisible sans aveugler le centre", "bed destroyed lit detruit", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("y_shift", "Hauteur de l'alerte", "Décalage au-dessus du viseur", -28.0F, -60.0F, 0.0F, 4.0F, "px"));

        m = regMod("hyp_sumo_border_warn", "Repère Bord d'Arène Sumo", "Sensibilise à la proximité du vide lors des duels Sumo/Bridge", "sumo edge border vide", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.NumberSetting("dist", "Distance du bord", "Mètres avant le vide", 1.5F, 0.5F, 3.0F, 0.25F, "m"));

        m = regMod("hyp_quick_play_menu", "Confort Commandes Rapides", "Accès rapide au retour lobby hors combat", "quickplay lobby hub commande", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ModeSetting("default_cmd", "Commande favorite", "Action rapide", 0, "/lobby", "/rejoin", "/who"));

        m = regMod("hyp_hide_lobby_npcs_tags", "Optimisation Hologrammes Lobby", "Allège l'empilement massif de lignes flottantes au spawn des serveurs", "holograms armorstand lobby fps", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée hologrammes", "Distance maximale de rendu", 24.0F, 8.0F, 48.0F, 2.0F, "m"));

        m = regMod("hyp_kill_counter", "Suivi Éliminations Session", "Comptabilise vos éliminations et coups décisifs localement", "kills counter eliminations stats", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("reset_world", "Réinitialiser par carte", "Remet le compteur de manche à zéro au changement de map", false));

        m = regMod("hyp_winstreak_tracker", "Suivi Série de Parties", "Conserve le fil de votre session compétitive", "winstreak serie victoires", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("toast_milestone", "Célébrer paliers x5", "Indication discrète tous les 5 succès", true));

        m = regMod("hyp_chat_search", "Surligneur Mot-Clé Chat", "Repère instantanément les messages d'échange ou d'appel d'équipe", "chat filter search mot cle", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.ColorSetting("hl_col", "Couleur mot-clé", "Couleur d'accent", 0xFF38BDF8, false));

        m = regMod("hyp_anti_portal_trap_ui", "Menus Actifs dans les Portails", "Permet d'utiliser le chat même dans un portail du Nether", "portal chat gui nether", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.BooleanSetting("reduce_wobble", "Réduire distorsion violette", "Atténue la nausée visuelle du portail", true));

        m = regMod("hyp_tablist_ping_sort_col", "Seuils Couleur Ping Custom", "Personnalise les paliers Vert / Or / Rouge de latence", "ping thresholds seuils ms", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("red_ms", "Seuil Ping Élevé (Rouge)", "Au-dessus de cette latence", 150.0F, 80.0F, 300.0F, 10.0F, "ms"));

        m = regMod("hyp_guild_motd_clean", "Bannières Serveur Épurées", "Évite que les longs messages d'accueil n'effacent vos discussions", "motd clean banniere", MashCategory.HYPIXEL, false);
        m.addSetting(new MashSetting.BooleanSetting("trim_blank", "Fusionner lignes vides", "Supprime les triples sauts de ligne", true));

        m = regMod("hyp_gg_filter", "Anti-Flood Fin de Manche", "Garde le chat lisible lorsque 20 joueurs écrivent gg simultanément", "compact gg anti flood", MashCategory.HYPIXEL, true);
        m.addSetting(new MashSetting.NumberSetting("window_s", "Fenêtre de regroupement", "Secondes de fusion", 4.0F, 1.0F, 10.0F, 1.0F, "s"));
    }

    // =========================================================================
    // 5. LES 20 MODULES MONDE, FENÊTRE & AUDIO
    // =========================================================================
    private static void registerAll20WorldModules() {
        MashModule m;

        MOD_BORDERLESS = regMod("wld_borderless", "Plein Écran Sans Bordure (Borderless)", "Remplace le plein écran exclusif lent par un Borderless instantané (Alt-Tab & F11 fluides !)", "borderless fullscreen plein ecran sans bordure f11", MashCategory.WORLD, true);
        MOD_BORDERLESS.addSetting(new MashSetting.BooleanSetting("f11_instant", "F11 sans écran noir", "Bascule instantanément entre Borderless et Fenêtré via Win32", true));

        MOD_MUTE_UNFOCUSED = regMod("wld_mute_unfocused", "Audio Muet Hors Focus", "Coupe automatiquement le son du jeu lorsque vous faites Alt-Tab vers une autre fenêtre", "mute unfocused alt tab son audio", MashCategory.WORLD, false);
        MOD_MUTE_UNFOCUSED.addSetting(new MashSetting.NumberSetting("bg_vol", "Volume en arrière-plan", "0% = muet complet hors focus", 0.0F, 0.0F, 50.0F, 5.0F, "%"));

        m = regMod("wld_async_screenshots", "Captures d'Écran Sans Freeze", "Évite le blocage disque lors de la prise d'un screenshot F2", "screenshot f2 capture sans lag", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("chat_hint", "Message de confirmation", "Affiche le nom du fichier capturé", true));

        m = regMod("wld_sound_limiter", "Protection Saturation Audio", "Évite la saturation sonore lors de dizaines d'explosions simultanées", "sound limiter explosions tnt", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("max_same", "Plafond sons identiques", "Occurrences simultanées max par tick", 6.0F, 2.0F, 16.0F, 1.0F, ""));

        m = regMod("wld_footstep_vol", "Clarté Bruits de Pas", "Privilégie l'audibilité des déplacements proches", "footsteps pas audio", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("gain", "Équilibre pas", "Niveau relatif", 100.0F, 50.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_portal_sound_mute", "Portails Silencieux", "Atténue le bourdonnement répétitif des portails du Nether à proximité", "portal sound nether silence", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume portail", "Niveau résiduel", 15.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_wither_dragon_mute", "Cris de Boss Atténués", "Protège vos oreilles lors des apparitions de Wither ou fin d'Ender Dragon", "dragon wither boss sound", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("vol", "Volume cris globaux", "Niveau maximal autorisé", 35.0F, 0.0F, 100.0F, 5.0F, "%"));

        m = regMod("wld_f3_enhancer", "Télémétrie F3 MashEngine", "Enrichit l'écran F3 avec les compteurs MultiDraw, VAO et Culling temps-réel", "f3 debug stats mash", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("colored", "Statistiques colorées", "Met en valeur les gains GPU", true));

        m = regMod("wld_waypoints_local", "Mémorisation Dernier Décès", "Garde en mémoire les coordonnées XYZ de votre dernière mort en Survie", "death coords waypoint mort", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("chat_print", "Afficher XYZ dans le chat", "Note localement votre position de mort", true));

        m = regMod("wld_chunk_borders", "Repères Limites de Chunks", "Aide visuelle à l'alignement des fermes et constructions sur 16x16", "chunk borders grille 16x16", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.ColorSetting("grid_col", "Couleur de la grille", "Teinte des limites de chunk", 0xAA38BDF8, false));

        m = regMod("wld_lighting_debug", "Alerte Zones Sombres (Survie)", "Signale si le bloc sous vos pieds permet l'apparition de monstres (<=7)", "light spawn mobs survie", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.NumberSetting("thresh", "Seuil de lumière", "Niveau critique d'apparition", 7.0F, 0.0F, 11.0F, 1.0F, ""));

        m = regMod("wld_horse_stats", "Inspection Montures", "Évalue la vitesse et le saut des chevaux en Survie", "horse stats cheval vitesse", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("decimals", "Précision chiffres", "Décimales affichées", 2.0F, 1.0F, 2.0F, 1.0F, ""));

        m = regMod("wld_book_page_num", "Lecture Rapide des Livres", "Navigation fluide au clavier dans les guides et livres", "book pages livre", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("arrow_nav", "Flèches Gauche/Droite", "Tourne les pages avec les flèches", true));

        m = regMod("wld_sign_search", "Édition Panneaux Fluide", "Validation rapide des panneaux sans accroc", "sign edit panneau", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_close", "Fermeture directe Échap", "Valide immédiatement avec Échap", true));

        m = regMod("wld_disconnect_confirm", "Sécurité Bouton Déconnexion", "Évite un clic accidentel sur Déconnexion en ouvrant le menu Échap", "disconnect confirm securite", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.BooleanSetting("pvp_only", "Uniquement en multijoueur", "N'agit pas en monde Solo", true));

        m = regMod("wld_server_reconnect", "Reconnexion Serveur Rapide", "Permet de relancer rapidement la connexion au dernier serveur visité", "reconnect serveur deconnecte", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.NumberSetting("cooldown_s", "Délai sécurité anti-kick", "Temps minimal avant reconnexion", 2.0F, 1.0F, 10.0F, 1.0F, "s"));

        m = regMod("wld_memory_cleaner", "Purge Douce Changement Monde", "Libère les tampons régions et têtes obsolètes sans jamais appeler System.gc()", "memory cleaner cache purge", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("clear_skulls", "Vider cache crânes", "Purge les skins expirés entre deux lobbys", true));

        m = regMod("wld_custom_main_menu", "Menu Principal MashClient", "Active le menu d'accueil épuré avec le Trou Noir 3D", "main menu trou noir mashclient", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("mouse_parallax", "Parallaxe souris douce", "Légère inclinaison orbitale avec le curseur", true));

        m = regMod("wld_discord_status_fmt", "Confidentialité Serveur", "Masque l'adresse IP privée dans les affichages si souhaité", "privacy server ip", MashCategory.WORLD, false);
        m.addSetting(new MashSetting.BooleanSetting("hide_ip", "Masquer IP numériques", "Cache les adresses IP personnelles", true));

        m = regMod("wld_auto_sprint_resume", "Continuité Touches après GUI", "Rétablit immédiatement l'état de vos touches en fermant un coffre ou le chat", "inventory walk resume sprint", MashCategory.WORLD, true);
        m.addSetting(new MashSetting.BooleanSetting("sync_movement", "Resynchroniser ZQSD", "Lit l'état physique réel du clavier à la fermeture", true));
    }

    // =========================================================================
    // 6. LES 25 MODULES MOTEUR FPS / GPU / CPU (CONNECTÉS À PureFPSConfig)
    // =========================================================================
    private static void registerAll25EngineModules() {
        MashModule m;

        m = regMod("eng_region_multidraw", "Moteur GPU Region EBO", "Rend tous les chunks en GL_TRIANGLES indexés avec 1 seul bind d'indices par couche !", "region multidraw vbo ebo triangles gpu fps", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("anti_seam", "Soudure Anti-Fissures T-Junction", "Élimine les micro-lignes blanches entre chunks sur workers", true));

        m = regMod("eng_island_core_cull", "Déchargement Coeur des Îles", "Décharge à 100% les blocs enfouis au milieu des îles BedWars / SkyWars et grottes scellées", "island core cull bedwars skywars buried blocks", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("preserve_beds", "Protéger Lits & Coffres", "Conserve toujours les TileEntities et lits BedWars", true));

        m = regMod("eng_smart_chunk_updates", "Chunks Dynamiques 0ms / Statiques", "Actualise en 0ms les blocs joueurs, lits et générateurs ; raréfie la map statique", "smart chunk updates dynamic blocks beds generators", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("priority_beds_gens", "Priorité Lits & Générateurs", "Mise à jour instantanée des ponts, lits et minerais", true));

        m = regMod("eng_menu_world_unload", "Déchargement Maximal en Menu", "Décharge les chunks distants, particules, pluies et reflets d'inventaire dans les menus", "menu unload shop inventory fps boost", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("shop_glint_opt", "Alléger reflets boutiques 54 slots", "Coupe les passes matricielles non survolées", true));

        m = regMod("eng_lazy_terrain_bfs", "Lazy Terrain Setup (BFS)", "Supprime 85% des traversées CPU de graphe de visibilité lorsque vous marchez !", "lazy terrain setup bfs chunks fps", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("frustum_fast", "Recalcul caméra angulaire", "Ne met à jour que si bloc ou angle change", true));

        m = regMod("eng_frustum_pvertex", "Culling Frustum P-Vertex", "Teste uniquement le sommet positif de chaque boîte AABB (8x moins de calculs)", "frustum culling pvertex aabb", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("chunks_and_entities", "Chunks + Entités", "Actif sur tout le pipeline 3D", true));

        m = regMod("eng_fbo_blit_gl30", "Blit Framebuffer Matériel GL30", "Copie le framebuffer vers l'écran par DMA GPU direct (glBlitFramebuffer)", "fbo blit gl30 framebuffer", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("linear_scale", "Filtrage net", "Copie pixel-parfait sans flou", true));

        m = regMod("eng_no_gl_error", "Zéro Stall glGetError()", "Supprime les 2 barrières bloquantes CPU<->GPU par frame de Minecraft 1.8.9", "glgeterror stall driver nvidia amd", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("strict_zero", "Désactivation totale en jeu", "Aucun appel synchrone dans runGameLoop", true));

        m = regMod("eng_smart_animations", "Animations Textures Intelligentes", "Ne met à jour que l'eau, la lave et le feu réellement visibles à l'écran !", "smart animations lave eau feu textures", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("skip_hidden", "Ignorer sprites hors champ", "Zéro glTexSubImage2D inutile", true));

        m = regMod("eng_entity_culling", "Culling d'Entités & Occlusion", "Évite de dessiner les entités cachées derrière les murs ou trop éloignées", "entity culling occlusion murs distance", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Distance max entités", "Portée maximale de rendu des entités", 64.0F, 16.0F, 160.0F, 4.0F, "m"));
        m.addSetting(new MashSetting.BooleanSetting("occlusion", "Raymarching à travers murs", "Masque les entités totalement occultées par des blocs opaques", true));

        m = regMod("eng_item_frame_opt", "Optimisation Extrême Item Frames", "Corrige le gouffre de FPS des cadres d'objets (cône caméra + zéro upload boussole)", "item frames cadres cartes lag", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée max Item Frames", "Au-delà, le cadre et son objet sont ignorés", 48.0F, 16.0F, 128.0F, 4.0F, "m"));

        m = regMod("eng_player_opt", "Optimisation Joueurs Distants", "Simplifie le rendu des dizaines de joueurs éloignés dans les lobbys", "players rendering joueurs lobbys", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("detail_dist", "Portée détails complets", "Distance avec capes/objets secondaires complets", 32.0F, 16.0F, 64.0F, 4.0F, "m"));

        m = regMod("eng_baked_items", "Modèles d'Objets Pré-Compilés", "Fusionne les 7 listes de faces des objets 3D en un seul tampon plat", "baked item models objets 3d", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_flat", "Batching 1-passe", "Élimine les boucles EnumFacing par frame", true));

        m = regMod("eng_tile_culling", "Culling Coffres & TileEntities", "Applique un frustum et une portée stricte aux coffres, lits et balises", "tileentity chests coffres culling", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_dist", "Portée max TileEntities", "Distance maximale de rendu des coffres/crânes", 48.0F, 16.0F, 160.0F, 4.0F, "m"));

        m = regMod("eng_skull_cache", "Cache Atomique Têtes & Skins", "Élimine le décodage NBT/UUID répétitif des têtes décoratives sur Hypixel", "skulls heads tetes cache lobby", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("thread_safe", "Publication atomique immuable", "Zéro conflit avec les paquets réseau Netty", true));

        m = regMod("eng_fast_font", "Accélérateur FontRenderer O(1)", "Table directe des codes § et cache instantané des largeurs de texte", "fast font police cache texte", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("width_cache", "Cache largeurs O(1)", "Accélère le chat, les nametags et le Tab", true));

        m = regMod("eng_fast_scoreboard_gpu", "Batching Scoreboard 2-Quads", "Remplace les 17 appels OpenGL du scoreboard Vanilla par un lot unique", "fast scoreboard gpu batch", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("zero_alloc", "Zéro allocation Guava", "Supprime les listes temporaires à chaque frame", true));

        m = regMod("eng_particle_engine", "Moteur de Particules Rapide", "Frustum culling et plafond intelligent pour éviter les chutes à 40 FPS en combat", "particles engine particules lag", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("max_particles", "Plafond par couche", "Nombre maximal de particules simultanées", 1000.0F, 100.0F, 4000.0F, 100.0F, ""));

        m = regMod("eng_armor_stand_opt", "Court-Circuit ArmorStands", "Désactive la gravité et les collisions inutiles des hologrammes serveur", "armorstand physics hologrammes", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("skip_lookup", "Ignorer collisions entités", "Économise du CPU dans les lobbys denses", true));

        m = regMod("eng_biome_cache", "Cache Couleurs de Biomes", "Mémorise les teintes d'herbe, de feuillage et d'eau pour reconstruire les chunks 2x plus vite", "biome colors herbe eau cache", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("fast_blend", "Mélange rapide", "Zéro allocation BlockPos", true));

        m = regMod("eng_smooth_lighting_zero", "Smooth Lighting Zéro-Allocation", "Élimine des mégaoctets d'allocations temporaires par seconde dans AmbientOcclusionFace", "smooth lighting ao zero alloc", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("thread_local", "Tampons ThreadLocal", "100% sûr sur les workers multi-cœurs", true));

        m = regMod("eng_quad_radix_sort", "Tri Translucide Sans Autoboxing", "Trie l'eau et les vitres en tableaux primitifs directs (zéro Float[]/Integer[])", "translucent sort eau vitres", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("in_place", "Permutation directe NIO", "Zéro copie inutile", true));

        m = regMod("eng_chunk_threads", "Workers Chunks Multi-Cœurs", "Compile la géométrie et les soudures anti-seam hors du thread principal", "chunk threads workers coeurs cpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("threads", "Nombre de threads (0=Auto)", "0 détecte automatiquement la valeur optimale", 0.0F, 0.0F, 6.0F, 1.0F, ""));

        m = regMod("eng_skin_pacing", "Lissage Uploads Skins Joueurs", "Étale l'envoi des skins HD au GPU (1 par frame) pour supprimer les micro-freezes !", "skin upload pacing microfreeze", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("strict_budget", "1 skin max par frame en jeu", "Garantit un frametime plat en rejoignant un lobby", true));

        m = regMod("eng_distant_glint", "Culling Reflet Enchanté Distant", "Coupe la double passe matricielle glint.png sur les objets à plus de 20 blocs", "enchant glint distant gpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("keep_close", "Pleine qualité de près", "Aucun changement visuel en duel rapproché", true));

        m = regMod("eng_void_fog_skip", "Suppression Boucle Void Fog", "Élimine les 1000 sondages aléatoires de blocs par tick autour du joueur", "void fog ticks cpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("all_worlds", "Actif sur tous les mondes", "Économise du CPU chaque vingtième de seconde", true));

        m = regMod("eng_world_gc_guard", "Bouclier Anti-Freeze System.gc()", "Empêche Minecraft de figer la JVM pendant 400ms à chaque changement de serveur", "system gc freeze lobby", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.BooleanSetting("integrated_too", "Protéger aussi le mode Solo", "Fluide à l'ouverture d'un monde local", true));

        m = regMod("eng_unfocused_saver", "Économiseur GPU Hors Focus", "Réduit la consommation GPU/ventilateurs lorsque vous êtes sur une autre application", "unfocused fps economiseur gpu", MashCategory.ENGINE, true);
        m.addSetting(new MashSetting.NumberSetting("fps_cap", "Plafond FPS hors focus", "Images/s lorsque la fenêtre n'est pas active", 15.0F, 5.0F, 60.0F, 5.0F, " FPS"));
    }
}
