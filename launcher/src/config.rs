//! Configuration unifiée PureClient 3.5 (`purefps.properties` + `launcher.json` + `account.json`).

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PureProfile {
    Fps,
    Balanced,
    Quality,
    Custom,
}

impl PureProfile {
    pub fn id(self) -> &'static str {
        match self {
            Self::Fps => "fps",
            Self::Balanced => "balanced",
            Self::Quality => "quality",
            Self::Custom => "custom",
        }
    }

    pub fn from_id(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "fps" => Self::Fps,
            "balanced" => Self::Balanced,
            "quality" => Self::Quality,
            _ => Self::Custom,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum LaunchMode {
    PrismBridge,
    DirectMicrosoft,
    DirectOffline,
}

fn default_github_repo() -> String {
    "MyMasha/ProjectFPS".to_owned()
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LauncherPrefs {
    pub ram_mb: u32,
    pub offline_username: String,
    pub quick_join_hypixel: bool,
    pub launch_mode: LaunchMode,
    pub java_path: String,
    pub game_dir: String,
    pub libraries_dir: String,
    pub assets_dir: String,
    pub prism_exe: String,
    #[serde(default = "default_github_repo")]
    pub github_repo: String,
    #[serde(default = "default_true")]
    pub auto_update_check: bool,
}

impl Default for LauncherPrefs {
    fn default() -> Self {
        let appdata = std::env::var("APPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());
        let local_appdata =
            std::env::var("LOCALAPPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());

        let temurin = r"C:\Program Files\Eclipse Adoptium\jre-8.0.504.1-hotspot\bin\javaw.exe";
        let fallback_java = format!(r"{appdata}\PrismLauncher\java\jre-legacy\bin\javaw.exe");
        let java_path = if Path::new(temurin).is_file() {
            temurin.to_owned()
        } else {
            fallback_java
        };

        Self {
            ram_mb: 3072,
            offline_username: "PurePlayer".to_owned(),
            quick_join_hypixel: false,
            launch_mode: LaunchMode::PrismBridge,
            java_path,
            game_dir: format!(r"{appdata}\PrismLauncher\instances\1.8.9 gemini fps\minecraft"),
            libraries_dir: format!(r"{appdata}\PrismLauncher\libraries"),
            assets_dir: format!(r"{appdata}\PrismLauncher\assets"),
            prism_exe: format!(r"{local_appdata}\Programs\PrismLauncher\prismlauncher.exe"),
            github_repo: default_github_repo(),
            auto_update_check: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MicrosoftAccount {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
    pub refresh_token: String,
    pub obtained_epoch_secs: u64,
}

/// Vue publique sans jetons secrets transmise à l'interface WebView2.
#[derive(Debug, Clone, Serialize)]
pub struct PublicAccountView {
    pub username: String,
    pub uuid: String,
}

impl From<&MicrosoftAccount> for PublicAccountView {
    fn from(acc: &MicrosoftAccount) -> Self {
        Self {
            username: acc.username.clone(),
            uuid: acc.uuid.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PureClientSettings {
    pub active_profile: PureProfile,
    pub enabled: bool,

    // Modules Client 3.5
    pub custom_main_menu: bool,
    pub smooth_client_font: bool,
    pub hud_enabled: bool,
    pub hud_fps_cps: bool,
    pub hud_ping: bool,
    pub hud_sprint_status: bool,
    pub hud_keystrokes: bool,
    pub hud_armor: bool,
    pub toggle_sprint: bool,
    pub sprint_toggled_active: bool,
    pub zoom_enabled: bool,
    pub zoom_smooth_camera: bool,
    pub fullbright: bool,
    pub low_fire: bool,
    pub no_hurt_cam: bool,
    pub static_fov: bool,
    pub transparent_chat: bool,

    // Moteur GPU & CPU v3.5
    pub disable_gl_error_checks: bool,
    pub hardware_region_vao: bool,
    pub region_multi_draw: bool,
    pub lazy_terrain_setup: bool,
    pub limit_distant_glint: bool,
    pub fast_item_render: bool,
    pub fast_baked_item_models: bool,
    pub skip_void_fog_loop: bool,
    pub fast_fbo_blit: bool,
    pub fast_frustum_pvertex: bool,
    pub async_skin_upload_pacing: bool,
    pub fast_scoreboard: bool,
    pub fast_nametags: bool,
    pub fast_armor_stands: bool,
    pub fast_item_frames: bool,
    pub max_item_frame_distance: i32,
    pub fast_player_rendering: bool,
    pub fast_sign_text: bool,
    pub multi_threaded_chunk_build: bool,
    pub entity_occlusion_culling: bool,
    pub smart_animations: bool,
    pub max_entity_render_distance: i32,
    pub max_tile_entity_render_distance: i32,
    pub max_particles_per_layer: i32,
    pub skip_client_skylight_updates: bool,
    pub unfocused_fps_limit: i32,

    #[serde(skip)]
    extra_props: BTreeMap<String, String>,
}

impl Default for PureClientSettings {
    fn default() -> Self {
        Self {
            active_profile: PureProfile::Balanced,
            enabled: true,
            custom_main_menu: true,
            smooth_client_font: true,
            hud_enabled: true,
            hud_fps_cps: true,
            hud_ping: true,
            hud_sprint_status: true,
            hud_keystrokes: false,
            hud_armor: true,
            toggle_sprint: true,
            sprint_toggled_active: true,
            zoom_enabled: true,
            zoom_smooth_camera: false,
            fullbright: true,
            low_fire: true,
            no_hurt_cam: false,
            static_fov: false,
            transparent_chat: false,
            disable_gl_error_checks: true,
            hardware_region_vao: true,
            region_multi_draw: true,
            lazy_terrain_setup: true,
            limit_distant_glint: true,
            fast_item_render: true,
            fast_baked_item_models: true,
            skip_void_fog_loop: true,
            fast_fbo_blit: true,
            fast_frustum_pvertex: true,
            async_skin_upload_pacing: true,
            fast_scoreboard: true,
            fast_nametags: true,
            fast_armor_stands: true,
            fast_item_frames: true,
            max_item_frame_distance: 48,
            fast_player_rendering: true,
            fast_sign_text: true,
            multi_threaded_chunk_build: true,
            entity_occlusion_culling: true,
            smart_animations: true,
            max_entity_render_distance: 64,
            max_tile_entity_render_distance: 48,
            max_particles_per_layer: 1000,
            skip_client_skylight_updates: false,
            unfocused_fps_limit: 15,
            extra_props: BTreeMap::new(),
        }
    }
}

impl PureClientSettings {
    pub fn apply_profile(&mut self, profile: PureProfile) {
        if profile == PureProfile::Custom {
            self.active_profile = PureProfile::Custom;
            return;
        }
        self.enabled = true;
        self.disable_gl_error_checks = true;
        self.hardware_region_vao = true;
        self.region_multi_draw = true;
        self.lazy_terrain_setup = true;
        self.fast_item_render = true;
        self.fast_baked_item_models = true;
        self.skip_void_fog_loop = true;
        self.fast_fbo_blit = true;
        self.fast_frustum_pvertex = true;
        self.async_skin_upload_pacing = true;
        self.fast_scoreboard = true;
        self.fast_armor_stands = true;
        self.fast_item_frames = true;
        self.multi_threaded_chunk_build = true;

        match profile {
            PureProfile::Fps => {
                self.smart_animations = true;
                self.limit_distant_glint = true;
                self.entity_occlusion_culling = true;
                self.fast_nametags = true;
                self.max_item_frame_distance = 36;
                self.fast_player_rendering = true;
                self.fast_sign_text = true;
                self.max_entity_render_distance = 48;
                self.max_tile_entity_render_distance = 32;
                self.max_particles_per_layer = 250;
                self.skip_client_skylight_updates = true;
                self.unfocused_fps_limit = 10;
            }
            PureProfile::Balanced => {
                self.smart_animations = true;
                self.limit_distant_glint = true;
                self.entity_occlusion_culling = true;
                self.fast_nametags = true;
                self.max_item_frame_distance = 48;
                self.fast_player_rendering = true;
                self.fast_sign_text = true;
                self.max_entity_render_distance = 64;
                self.max_tile_entity_render_distance = 48;
                self.max_particles_per_layer = 1000;
                self.skip_client_skylight_updates = false;
                self.unfocused_fps_limit = 15;
            }
            PureProfile::Quality => {
                self.smart_animations = false;
                self.limit_distant_glint = false;
                self.entity_occlusion_culling = false;
                self.fast_nametags = false;
                self.max_item_frame_distance = 96;
                self.fast_player_rendering = false;
                self.fast_sign_text = false;
                self.max_entity_render_distance = 128;
                self.max_tile_entity_render_distance = 128;
                self.max_particles_per_layer = 4000;
                self.skip_client_skylight_updates = false;
                self.unfocused_fps_limit = 30;
            }
            PureProfile::Custom => {}
        }
        self.active_profile = profile;
    }

    pub fn set_field(&mut self, key: &str, value: &serde_json::Value) -> bool {
        let as_bool = value.as_bool().unwrap_or(false);
        let as_i32 = value.as_i64().unwrap_or(0) as i32;
        let mut engine_edited = false;

        match key {
            "enabled" => { self.enabled = as_bool; engine_edited = true; }
            "custom_main_menu" => self.custom_main_menu = as_bool,
            "smooth_client_font" => self.smooth_client_font = as_bool,
            "hud_enabled" => self.hud_enabled = as_bool,
            "hud_fps_cps" => self.hud_fps_cps = as_bool,
            "hud_ping" => self.hud_ping = as_bool,
            "hud_sprint_status" => self.hud_sprint_status = as_bool,
            "hud_keystrokes" => self.hud_keystrokes = as_bool,
            "hud_armor" => self.hud_armor = as_bool,
            "toggle_sprint" => self.toggle_sprint = as_bool,
            "sprint_toggled_active" => self.sprint_toggled_active = as_bool,
            "zoom_enabled" => self.zoom_enabled = as_bool,
            "zoom_smooth_camera" => self.zoom_smooth_camera = as_bool,
            "fullbright" => self.fullbright = as_bool,
            "low_fire" => self.low_fire = as_bool,
            "no_hurt_cam" => self.no_hurt_cam = as_bool,
            "static_fov" => self.static_fov = as_bool,
            "transparent_chat" => self.transparent_chat = as_bool,

            "disable_gl_error_checks" => { self.disable_gl_error_checks = as_bool; engine_edited = true; }
            "hardware_region_vao" => { self.hardware_region_vao = as_bool; engine_edited = true; }
            "region_multi_draw" => { self.region_multi_draw = as_bool; engine_edited = true; }
            "lazy_terrain_setup" => { self.lazy_terrain_setup = as_bool; engine_edited = true; }
            "limit_distant_glint" => { self.limit_distant_glint = as_bool; engine_edited = true; }
            "fast_item_render" => { self.fast_item_render = as_bool; engine_edited = true; }
            "fast_baked_item_models" => { self.fast_baked_item_models = as_bool; engine_edited = true; }
            "skip_void_fog_loop" => { self.skip_void_fog_loop = as_bool; engine_edited = true; }
            "fast_fbo_blit" => { self.fast_fbo_blit = as_bool; engine_edited = true; }
            "fast_frustum_pvertex" => { self.fast_frustum_pvertex = as_bool; engine_edited = true; }
            "async_skin_upload_pacing" => { self.async_skin_upload_pacing = as_bool; engine_edited = true; }
            "fast_scoreboard" => { self.fast_scoreboard = as_bool; engine_edited = true; }
            "fast_nametags" => { self.fast_nametags = as_bool; engine_edited = true; }
            "fast_armor_stands" => { self.fast_armor_stands = as_bool; engine_edited = true; }
            "fast_item_frames" => { self.fast_item_frames = as_bool; engine_edited = true; }
            "fast_player_rendering" => { self.fast_player_rendering = as_bool; engine_edited = true; }
            "fast_sign_text" => { self.fast_sign_text = as_bool; engine_edited = true; }
            "multi_threaded_chunk_build" => { self.multi_threaded_chunk_build = as_bool; engine_edited = true; }
            "entity_occlusion_culling" => { self.entity_occlusion_culling = as_bool; engine_edited = true; }
            "smart_animations" => { self.smart_animations = as_bool; engine_edited = true; }
            "skip_client_skylight_updates" => { self.skip_client_skylight_updates = as_bool; engine_edited = true; }
            "max_item_frame_distance" => {
                self.max_item_frame_distance = as_i32.clamp(16, 160);
                engine_edited = true;
            }
            "max_entity_render_distance" => {
                self.max_entity_render_distance = as_i32.clamp(16, 160);
                engine_edited = true;
            }
            "max_tile_entity_render_distance" => {
                self.max_tile_entity_render_distance = as_i32.clamp(16, 160);
                engine_edited = true;
            }
            "max_particles_per_layer" => {
                self.max_particles_per_layer = as_i32.clamp(100, 4000);
                engine_edited = true;
            }
            "unfocused_fps_limit" => {
                self.unfocused_fps_limit = as_i32.clamp(5, 60);
                engine_edited = true;
            }
            _ => return false,
        }

        if engine_edited {
            self.active_profile = PureProfile::Custom;
        }
        true
    }

    pub fn load_from_game_dir(game_dir: &str) -> Self {
        let mut s = Self::default();
        let path = Path::new(game_dir).join("config").join("purefps.properties");
        let Ok(raw) = std::fs::read_to_string(&path) else {
            return s;
        };

        let mut map = BTreeMap::new();
        for line in raw.lines() {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
                continue;
            }
            if let Some((k, v)) = trimmed.split_once('=') {
                map.insert(k.trim().to_owned(), v.trim().to_owned());
            }
        }

        let b = |m: &BTreeMap<String, String>, key: &str, def: bool| -> bool {
            m.get(key)
                .map(|v| v.eq_ignore_ascii_case("true"))
                .unwrap_or(def)
        };
        let i = |m: &BTreeMap<String, String>, key: &str, def: i32| -> i32 {
            m.get(key).and_then(|v| v.parse::<i32>().ok()).unwrap_or(def)
        };

        if let Some(prof) = map.get("activeProfile") {
            s.active_profile = PureProfile::from_id(prof);
        }
        s.enabled = b(&map, "enabled", s.enabled);
        s.custom_main_menu = b(&map, "customMainMenu", s.custom_main_menu);
        s.smooth_client_font = b(&map, "smoothClientFont", s.smooth_client_font);
        s.hud_enabled = b(&map, "hudEnabled", s.hud_enabled);
        s.hud_fps_cps = b(&map, "hudFpsCps", s.hud_fps_cps);
        s.hud_ping = b(&map, "hudPing", s.hud_ping);
        s.hud_sprint_status = b(&map, "hudSprintStatus", s.hud_sprint_status);
        s.hud_keystrokes = b(&map, "hudKeystrokes", s.hud_keystrokes);
        s.hud_armor = b(&map, "hudArmor", s.hud_armor);
        s.toggle_sprint = b(&map, "toggleSprint", s.toggle_sprint);
        s.sprint_toggled_active = b(&map, "sprintToggledActive", s.sprint_toggled_active);
        s.zoom_enabled = b(&map, "zoomEnabled", s.zoom_enabled);
        s.zoom_smooth_camera = b(&map, "zoomSmoothCamera", s.zoom_smooth_camera);
        s.fullbright = b(&map, "fullbright", s.fullbright);
        s.low_fire = b(&map, "lowFire", s.low_fire);
        s.no_hurt_cam = b(&map, "noHurtCam", s.no_hurt_cam);
        s.static_fov = b(&map, "staticFov", s.static_fov);
        s.transparent_chat = b(&map, "transparentChat", s.transparent_chat);

        s.disable_gl_error_checks = b(&map, "disableGlErrorChecks", s.disable_gl_error_checks);
        s.hardware_region_vao = b(&map, "hardwareRegionVao", s.hardware_region_vao);
        s.region_multi_draw = b(&map, "regionMultiDraw", s.region_multi_draw);
        s.lazy_terrain_setup = b(&map, "lazyTerrainSetup", s.lazy_terrain_setup);
        s.limit_distant_glint = b(&map, "limitDistantGlint", s.limit_distant_glint);
        s.fast_item_render = b(&map, "fastItemRender", s.fast_item_render);
        s.fast_baked_item_models = b(&map, "fastBakedItemModels", s.fast_baked_item_models);
        s.skip_void_fog_loop = b(&map, "skipVoidFogLoop", s.skip_void_fog_loop);
        s.fast_fbo_blit = b(&map, "fastFboBlit", s.fast_fbo_blit);
        s.fast_frustum_pvertex = b(&map, "fastFrustumPVertex", s.fast_frustum_pvertex);
        s.async_skin_upload_pacing = b(&map, "asyncSkinUploadPacing", s.async_skin_upload_pacing);
        s.fast_scoreboard = b(&map, "fastScoreboard", s.fast_scoreboard);
        s.fast_nametags = b(&map, "fastNametags", s.fast_nametags);
        s.fast_armor_stands = b(&map, "fastArmorStands", s.fast_armor_stands);
        s.fast_item_frames = b(&map, "fastItemFrames", s.fast_item_frames);
        s.max_item_frame_distance = i(&map, "maxItemFrameDistance", s.max_item_frame_distance);
        s.fast_player_rendering = b(&map, "fastPlayerRendering", s.fast_player_rendering);
        s.fast_sign_text = b(&map, "fastSignText", s.fast_sign_text);
        s.multi_threaded_chunk_build = b(&map, "multiThreadedChunkBuild", s.multi_threaded_chunk_build);
        s.entity_occlusion_culling = b(&map, "entityOcclusionCulling", s.entity_occlusion_culling);
        s.smart_animations = b(&map, "smartAnimations", s.smart_animations);
        s.max_entity_render_distance = i(&map, "maxEntityRenderDistance", s.max_entity_render_distance);
        s.max_tile_entity_render_distance =
            i(&map, "maxTileEntityRenderDistance", s.max_tile_entity_render_distance);
        s.max_particles_per_layer = i(&map, "maxParticlesPerLayer", s.max_particles_per_layer);
        s.skip_client_skylight_updates =
            b(&map, "skipClientSkylightUpdates", s.skip_client_skylight_updates);
        s.unfocused_fps_limit = i(&map, "unfocusedFpsLimit", s.unfocused_fps_limit);

        s.extra_props = map;
        s
    }

    pub fn save_to_game_dir(&self, game_dir: &str) -> Result<(), String> {
        let config_dir = Path::new(game_dir).join("config");
        std::fs::create_dir_all(&config_dir).map_err(|e| format!("Création dossier config: {e}"))?;
        let path = config_dir.join("purefps.properties");

        let mut map = self.extra_props.clone();
        map.insert("activeProfile".into(), self.active_profile.id().into());
        map.insert("enabled".into(), self.enabled.to_string());
        map.insert("customMainMenu".into(), self.custom_main_menu.to_string());
        map.insert("smoothClientFont".into(), self.smooth_client_font.to_string());
        map.insert("hudEnabled".into(), self.hud_enabled.to_string());
        map.insert("hudFpsCps".into(), self.hud_fps_cps.to_string());
        map.insert("hudPing".into(), self.hud_ping.to_string());
        map.insert("hudSprintStatus".into(), self.hud_sprint_status.to_string());
        map.insert("hudKeystrokes".into(), self.hud_keystrokes.to_string());
        map.insert("hudArmor".into(), self.hud_armor.to_string());
        map.insert("toggleSprint".into(), self.toggle_sprint.to_string());
        map.insert("sprintToggledActive".into(), self.sprint_toggled_active.to_string());
        map.insert("zoomEnabled".into(), self.zoom_enabled.to_string());
        map.insert("zoomSmoothCamera".into(), self.zoom_smooth_camera.to_string());
        map.insert("fullbright".into(), self.fullbright.to_string());
        map.insert("lowFire".into(), self.low_fire.to_string());
        map.insert("noHurtCam".into(), self.no_hurt_cam.to_string());
        map.insert("staticFov".into(), self.static_fov.to_string());
        map.insert("transparentChat".into(), self.transparent_chat.to_string());

        map.insert("disableGlErrorChecks".into(), self.disable_gl_error_checks.to_string());
        map.insert("hardwareRegionVao".into(), self.hardware_region_vao.to_string());
        map.insert("regionMultiDraw".into(), self.region_multi_draw.to_string());
        map.insert("lazyTerrainSetup".into(), self.lazy_terrain_setup.to_string());
        map.insert("limitDistantGlint".into(), self.limit_distant_glint.to_string());
        map.insert("fastItemRender".into(), self.fast_item_render.to_string());
        map.insert("fastBakedItemModels".into(), self.fast_baked_item_models.to_string());
        map.insert("skipVoidFogLoop".into(), self.skip_void_fog_loop.to_string());
        map.insert("fastFboBlit".into(), self.fast_fbo_blit.to_string());
        map.insert("fastFrustumPVertex".into(), self.fast_frustum_pvertex.to_string());
        map.insert("asyncSkinUploadPacing".into(), self.async_skin_upload_pacing.to_string());
        map.insert("fastScoreboard".into(), self.fast_scoreboard.to_string());
        map.insert("fastNametags".into(), self.fast_nametags.to_string());
        map.insert("fastArmorStands".into(), self.fast_armor_stands.to_string());
        map.insert("fastItemFrames".into(), self.fast_item_frames.to_string());
        map.insert("maxItemFrameDistance".into(), self.max_item_frame_distance.to_string());
        map.insert("fastPlayerRendering".into(), self.fast_player_rendering.to_string());
        map.insert("fastSignText".into(), self.fast_sign_text.to_string());
        map.insert("multiThreadedChunkBuild".into(), self.multi_threaded_chunk_build.to_string());
        map.insert("entityOcclusionCulling".into(), self.entity_occlusion_culling.to_string());
        map.insert("smartAnimations".into(), self.smart_animations.to_string());
        map.insert("maxEntityRenderDistance".into(), self.max_entity_render_distance.to_string());
        map.insert(
            "maxTileEntityRenderDistance".into(),
            self.max_tile_entity_render_distance.to_string(),
        );
        map.insert("maxParticlesPerLayer".into(), self.max_particles_per_layer.to_string());
        map.insert(
            "skipClientSkylightUpdates".into(),
            self.skip_client_skylight_updates.to_string(),
        );
        map.insert("unfocusedFpsLimit".into(), self.unfocused_fps_limit.to_string());

        let mut out = String::from("# PureClient 3.5.0 Configuration\n");
        for (k, v) in &map {
            out.push_str(k);
            out.push('=');
            out.push_str(v);
            out.push('\n');
        }
        std::fs::write(&path, out).map_err(|e| format!("Écriture purefps.properties: {e}"))?;

        if self.enabled && matches!(self.active_profile, PureProfile::Fps | PureProfile::Balanced) {
            self.sync_vanilla_options_txt(game_dir);
        }
        Ok(())
    }

    /// Débride automatiquement les réglages bloquants de Minecraft Vanilla (`options.txt`) :
    /// `useVbo:true`, `enableVsync:false` et `maxFps:260` (Illimité en 1.8.9, au lieu du plafond Vanilla 120 FPS).
    pub fn sync_vanilla_options_txt(&self, game_dir: &str) {
        let options_path = Path::new(game_dir).join("options.txt");
        let Ok(existing) = std::fs::read_to_string(&options_path) else {
            return;
        };

        let mut updated_lines: Vec<String> = Vec::new();
        let mut saw_vbo = false;
        let mut saw_vsync = false;
        let mut saw_max_fps = false;
        let mut changed = false;

        for line in existing.lines() {
            if line.starts_with("useVbo:") {
                saw_vbo = true;
                if line.trim() != "useVbo:true" {
                    updated_lines.push("useVbo:true".to_owned());
                    changed = true;
                } else {
                    updated_lines.push(line.to_owned());
                }
            } else if line.starts_with("enableVsync:") {
                saw_vsync = true;
                if line.trim() != "enableVsync:false" {
                    updated_lines.push("enableVsync:false".to_owned());
                    changed = true;
                } else {
                    updated_lines.push(line.to_owned());
                }
            } else if let Some(raw_fps) = line.strip_prefix("maxFps:") {
                saw_max_fps = true;
                let parsed = raw_fps.trim().parse::<i32>().unwrap_or(120);
                if parsed < 255 {
                    updated_lines.push("maxFps:260".to_owned());
                    changed = true;
                } else {
                    updated_lines.push(line.to_owned());
                }
            } else {
                updated_lines.push(line.to_owned());
            }
        }

        if !saw_vbo {
            updated_lines.push("useVbo:true".to_owned());
            changed = true;
        }
        if !saw_vsync {
            updated_lines.push("enableVsync:false".to_owned());
            changed = true;
        }
        if !saw_max_fps {
            updated_lines.push("maxFps:260".to_owned());
            changed = true;
        }

        if changed {
            let _ = std::fs::write(&options_path, updated_lines.join("\n") + "\n");
        }
    }
}

pub fn app_storage_dir() -> PathBuf {
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".to_owned());
    Path::new(&base).join("PureClient")
}

pub fn load_launcher_prefs() -> LauncherPrefs {
    let path = app_storage_dir().join("launcher.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<LauncherPrefs>(&s).ok())
        .unwrap_or_default()
}

pub fn save_launcher_prefs(prefs: &LauncherPrefs) -> Result<(), String> {
    let dir = app_storage_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let json = serde_json::to_string_pretty(prefs).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("launcher.json"), json).map_err(|e| e.to_string())
}

pub fn load_microsoft_account() -> Option<MicrosoftAccount> {
    let path = app_storage_dir().join("account.json");
    std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<MicrosoftAccount>(&s).ok())
}

pub fn save_microsoft_account(acc: &MicrosoftAccount) -> Result<(), String> {
    let dir = app_storage_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let json = serde_json::to_string_pretty(acc).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("account.json"), json).map_err(|e| e.to_string())
}

pub fn clear_microsoft_account() {
    let _ = std::fs::remove_file(app_storage_dir().join("account.json"));
}

pub fn save_custom_github_token(raw_token: &str) -> Result<(), String> {
    let dir = app_storage_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join("updater_token.txt");
    let trimmed = raw_token.trim();
    if trimmed.is_empty() {
        let _ = std::fs::remove_file(&path);
        return Ok(());
    }
    std::fs::write(&path, format!("{trimmed}\n")).map_err(|e| e.to_string())
}

/// Résout le jeton GitHub Read-Only dans l'ordre de priorité :
/// 1. Jeton personnalisé enregistré dans `%APPDATA%\PureClient\updater_token.txt`
/// 2. Variable d'environnement `PURECLIENT_GITHUB_TOKEN`
/// 3. Jeton embarqué à la compilation depuis `launcher/.github_token` (ignoré par Git) via `build.rs`
pub fn effective_github_token() -> Option<String> {
    if let Ok(custom) = std::fs::read_to_string(app_storage_dir().join("updater_token.txt")) {
        let t = custom.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    if let Ok(env_t) = std::env::var("PURECLIENT_GITHUB_TOKEN") {
        let t = env_t.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    if let Some(embedded) = option_env!("PURECLIENT_EMBEDDED_GH_TOKEN") {
        let t = embedded.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    None
}

/// Prépare les fichiers de synchronisation sans couture entre le Launcher (rideau noir Trou Noir)
/// et Minecraft 1.8.9 : désactive l'écran blanc de chargement Forge (`splash.properties`) et dépose
/// le jeton `.purefps_handoff` lu par `SeamlessLaunchTransition` avant `Display.create()`.
pub fn prepare_seamless_launch_files(game_dir: &str) {
    let cfg_dir = Path::new(game_dir).join("config");
    let _ = std::fs::create_dir_all(&cfg_dir);

    let ready_file = cfg_dir.join(".purefps_menu_ready");
    let _ = std::fs::remove_file(&ready_file);

    let handoff_file = cfg_dir.join(".purefps_handoff");
    let _ = std::fs::write(&handoff_file, "SINGULARITY_HANDOFF\n");

    let splash_path = cfg_dir.join("splash.properties");
    let splash_black = "\
# PureClient Singularity Seamless Black Curtain\n\
enabled=false\n\
background=0x000000\n\
font=0x000000\n\
barBackground=0x000000\n\
barBorder=0x000000\n\
bar=0x000000\n\
rotate=false\n";
    let _ = std::fs::write(&splash_path, splash_black);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_profile_roundtrip_and_options_txt_unlock() {
        let tmp = std::env::temp_dir().join("pureclient_cfg_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        let opts_path = tmp.join("options.txt");
        std::fs::write(&opts_path, "fov:0.5\nmaxFps:120\nenableVsync:true\nuseVbo:false\n").unwrap();

        let mut cfg = PureClientSettings::default();
        cfg.apply_profile(PureProfile::Fps);
        cfg.save_to_game_dir(&tmp.to_string_lossy()).unwrap();

        let reloaded = PureClientSettings::load_from_game_dir(&tmp.to_string_lossy());
        assert_eq!(reloaded.active_profile, PureProfile::Fps);
        assert!(reloaded.fast_baked_item_models);
        assert!(reloaded.fast_item_frames);
        assert_eq!(reloaded.max_item_frame_distance, 36);
        assert!(reloaded.fast_player_rendering);
        assert!(reloaded.fast_sign_text);

        let patched_opts = std::fs::read_to_string(&opts_path).unwrap();
        assert!(patched_opts.contains("useVbo:true"));
        assert!(patched_opts.contains("enableVsync:false"));
        assert!(patched_opts.contains("maxFps:260"));

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
