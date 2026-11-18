//! Configuration unifiée MashClient 4.5 (`purefps.properties` + `launcher.json` + `accounts.json` + `custom_profiles.json`).

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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum LaunchMode {
    #[default]
    #[serde(alias = "Microsoft")]
    DirectMicrosoft,
    #[serde(alias = "Offline")]
    DirectOffline,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum InstanceFlavor {
    #[default]
    Standard,
    VulkanBeta,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuickServerEntry {
    pub id: String,
    pub name: String,
    pub address: String,
}

pub fn builtin_quick_servers() -> Vec<QuickServerEntry> {
    vec![
        QuickServerEntry {
            id: "hypixel".to_owned(),
            name: "Hypixel".to_owned(),
            address: "mc.hypixel.net".to_owned(),
        },
        QuickServerEntry {
            id: "pikanetwork".to_owned(),
            name: "PikaNetwork".to_owned(),
            address: "play.pikanetwork.net".to_owned(),
        },
        QuickServerEntry {
            id: "jartex".to_owned(),
            name: "JartexNetwork".to_owned(),
            address: "play.jartexnetwork.com".to_owned(),
        },
        QuickServerEntry {
            id: "minemen".to_owned(),
            name: "Minemen Club".to_owned(),
            address: "eu.minemen.club".to_owned(),
        },
        QuickServerEntry {
            id: "blocksmc".to_owned(),
            name: "BlocksMC".to_owned(),
            address: "blocksmc.com".to_owned(),
        },
    ]
}

pub fn split_server_host_port(address: &str) -> (String, u16) {
    let trimmed = address.trim();
    if let Some((host, port_str)) = trimmed.rsplit_once(':') {
        if !host.is_empty() && !host.contains(':') {
            if let Ok(port) = port_str.parse::<u16>() {
                return (host.to_owned(), port);
            }
        }
    }
    (trimmed.to_owned(), 25565)
}

fn default_github_repo() -> String {
    "MyMasha/ProjectFPS".to_owned()
}

fn default_true() -> bool {
    true
}

pub fn default_language() -> String {
    "en".to_owned()
}

pub fn normalize_language(raw: &str) -> String {
    if raw.trim().eq_ignore_ascii_case("fr") || raw.trim().to_ascii_lowercase().starts_with("fr") {
        "fr".to_owned()
    } else {
        "en".to_owned()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LauncherPrefs {
    pub ram_mb: u32,
    pub offline_username: String,
    #[serde(default)]
    pub quick_join_hypixel: bool,
    pub launch_mode: LaunchMode,
    pub java_path: String,
    pub game_dir: String,
    pub libraries_dir: String,
    pub assets_dir: String,
    #[serde(default = "default_github_repo")]
    pub github_repo: String,
    #[serde(default = "default_true")]
    pub auto_update_check: bool,
    #[serde(default)]
    pub instance_flavor: InstanceFlavor,
    #[serde(default)]
    pub selected_server: Option<QuickServerEntry>,
    #[serde(default)]
    pub custom_servers: Vec<QuickServerEntry>,
    #[serde(default = "default_language")]
    pub language: String,
}

/// Detects and ranks all available 64-bit Java 8 installations on the machine,
/// prioritizing modern Hotspot builds (`8u300+` / Temurin managed in `.PureFPS`)
/// over legacy `jre-legacy` (`1.8.0_51`).
pub fn detect_java8_path(appdata: &str, local_appdata: &str) -> String {
    let mut candidates: Vec<PathBuf> = Vec::new();

    // 1. Managed Temurin Java 8 runtime inside %APPDATA%\.PureFPS (or legacy MashClient)
    for folder in [".PureFPS", "MashClient"] {
        let managed_root = Path::new(appdata)
            .join(folder)
            .join("runtimes")
            .join("temurin-jre8");
        if let Some(javaw) = crate::runtimes::find_javaw_inside_dir(&managed_root) {
            candidates.push(javaw);
        }
    }

    // 2. JAVA_HOME if set on the machine
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let jh = PathBuf::from(java_home);
        if let Some(javaw) = crate::runtimes::find_javaw_inside_dir(&jh) {
            candidates.push(javaw);
        }
    }

    // 3. Standard 64-bit Java 8 distribution folders (+ PrismLauncher managed Java)
    let search_roots = [
        PathBuf::from(r"C:\Program Files\Eclipse Adoptium"),
        PathBuf::from(r"C:\Program Files\AdoptOpenJDK"),
        PathBuf::from(r"C:\Program Files\Zulu"),
        PathBuf::from(r"C:\Program Files\BellSoft"),
        PathBuf::from(r"C:\Program Files\Java"),
        PathBuf::from(r"C:\Program Files\Amazon Corretto"),
        PathBuf::from(r"C:\Program Files\Microsoft"),
        Path::new(appdata).join("PrismLauncher").join("java"),
        Path::new(local_appdata).join("Programs").join("PrismLauncher").join("java"),
    ];
    for root in &search_roots {
        let Ok(entries) = std::fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let folder_name = entry.file_name().to_string_lossy().to_ascii_lowercase();
            if folder_name.contains("8") || folder_name.contains("1.8") || folder_name.contains("legacy") {
                if let Some(javaw) = crate::runtimes::find_javaw_inside_dir(&entry.path()) {
                    candidates.push(javaw);
                }
            }
        }
    }

    // 4. Known exact fallback locations (Mojang runtime)
    let exact_fallbacks = [
        format!(
            r"{local_appdata}\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\jre-legacy\windows-x64\jre-legacy\bin\javaw.exe"
        ),
        r"C:\Program Files (x86)\Minecraft Launcher\runtime\jre-legacy\windows-x64\jre-legacy\bin\javaw.exe"
            .to_owned(),
    ];
    for raw in &exact_fallbacks {
        let p = PathBuf::from(raw);
        if p.is_file() {
            candidates.push(p);
        }
    }

    let mut best: Option<(i64, PathBuf)> = None;
    for cand in candidates {
        if !cand.is_file() {
            continue;
        }
        let probe = crate::runtimes::probe_java8_executable(&cand);
        if !probe.is_java8 {
            continue;
        }
        let mut score: i64 = 10_000;
        if probe.is_64bit {
            score += 5_000;
        }
        if probe.update_version > 0 && probe.update_version < 161 {
            // Legacy Java 8 (< 8u161) lacks modern TLS 1.2 default & DigiCert Global Root G2
            score -= 3_000;
        }
        score += i64::from(probe.update_version) * 10;
        let low = cand.to_string_lossy().to_ascii_lowercase();
        if low.contains(".purefps") {
            score += 300;
        } else if low.contains("temurin-jre8") {
            score += 250;
        }
        if best.as_ref().is_none_or(|(cur, _)| score > *cur) {
            best = Some((score, cand));
        }
    }

    best.map(|(_, p)| p.to_string_lossy().to_string())
        .unwrap_or_default()
}

pub fn detect_minecraft_189_dirs(appdata: &str, _local_appdata: &str) -> (String, String, String) {
    let purefps_root = Path::new(appdata).join(".PureFPS");
    let game_dir = purefps_root.to_string_lossy().to_string();
    let libs_dir = purefps_root.join("libraries").to_string_lossy().to_string();
    let assets_dir = purefps_root.join("assets").to_string_lossy().to_string();
    (game_dir, libs_dir, assets_dir)
}

impl Default for LauncherPrefs {
    fn default() -> Self {
        let appdata = std::env::var("APPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());
        let local_appdata =
            std::env::var("LOCALAPPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());

        let java_path = detect_java8_path(&appdata, &local_appdata);
        let (game_dir, libraries_dir, assets_dir) = detect_minecraft_189_dirs(&appdata, &local_appdata);

        Self {
            ram_mb: 3072,
            offline_username: "MashPlayer".to_owned(),
            quick_join_hypixel: false,
            launch_mode: LaunchMode::DirectMicrosoft,
            java_path,
            game_dir,
            libraries_dir,
            assets_dir,
            github_repo: default_github_repo(),
            auto_update_check: true,
            instance_flavor: InstanceFlavor::Standard,
            selected_server: None,
            custom_servers: Vec::new(),
            language: default_language(),
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

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AccountStore {
    pub active_uuid: Option<String>,
    #[serde(default)]
    pub accounts: Vec<MicrosoftAccount>,
}

/// Vue publique sans jetons secrets transmise à l'interface WebView2.
#[derive(Debug, Clone, Serialize)]
pub struct PublicAccountView {
    pub username: String,
    pub uuid: String,
    pub active: bool,
}

impl From<&MicrosoftAccount> for PublicAccountView {
    fn from(acc: &MicrosoftAccount) -> Self {
        Self {
            username: acc.username.clone(),
            uuid: acc.uuid.clone(),
            active: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomProfile {
    pub id: String,
    pub name: String,
    pub settings: PureClientSettings,
    pub modules: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomProfileSummary {
    pub id: String,
    pub name: String,
}

fn default_client_name() -> String {
    "MashClient".to_owned()
}

fn default_bundle_version() -> String {
    "4.5.1".to_owned()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportedProfileBundle {
    #[serde(default = "default_client_name")]
    pub client: String,
    #[serde(default = "default_bundle_version")]
    pub version: String,
    pub name: String,
    pub settings: PureClientSettings,
    pub modules: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PureClientSettings {
    pub active_profile: PureProfile,
    pub enabled: bool,

    // Modules MashClient 4.4
    pub custom_main_menu: bool,
    #[serde(default = "default_true")]
    pub black_hole_menu_sound: bool,
    #[serde(default = "default_true")]
    pub tnt_timer: bool,
    #[serde(default = "default_true")]
    pub borderless_fullscreen: bool,
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

    // Moteur GPU & CPU v4.0
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
    #[serde(default = "default_true")]
    pub island_core_culling: bool,
    #[serde(default = "default_true")]
    pub smart_chunk_updates: bool,
    #[serde(default = "default_true")]
    pub menu_world_unload: bool,
    #[serde(default = "default_language")]
    pub language: String,

    #[serde(skip)]
    extra_props: BTreeMap<String, String>,
}

impl Default for PureClientSettings {
    fn default() -> Self {
        Self {
            active_profile: PureProfile::Balanced,
            enabled: true,
            custom_main_menu: true,
            black_hole_menu_sound: true,
            tnt_timer: true,
            borderless_fullscreen: true,
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
            unfocused_fps_limit: 30,
            island_core_culling: true,
            smart_chunk_updates: true,
            menu_world_unload: true,
            language: default_language(),
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
                self.unfocused_fps_limit = 30;
                self.island_core_culling = true;
                self.smart_chunk_updates = true;
                self.menu_world_unload = true;
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
                self.unfocused_fps_limit = 30;
                self.island_core_culling = true;
                self.smart_chunk_updates = true;
                self.menu_world_unload = true;
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
                self.island_core_culling = false;
                self.smart_chunk_updates = false;
                self.menu_world_unload = false;
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
            "black_hole_menu_sound" => self.black_hole_menu_sound = as_bool,
            "tnt_timer" => self.tnt_timer = as_bool,
            "borderless_fullscreen" => self.borderless_fullscreen = as_bool,
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
            "island_core_culling" => { self.island_core_culling = as_bool; engine_edited = true; }
            "smart_chunk_updates" => { self.smart_chunk_updates = as_bool; engine_edited = true; }
            "menu_world_unload" => { self.menu_world_unload = as_bool; engine_edited = true; }
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
            "language" => {
                if let Some(s) = value.as_str() {
                    self.language = normalize_language(s);
                }
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
        if let Some(lang) = map.get("language") {
            s.language = normalize_language(lang);
        }
        s.enabled = b(&map, "enabled", s.enabled);
        s.custom_main_menu = b(&map, "customMainMenu", s.custom_main_menu);
        s.black_hole_menu_sound = b(&map, "blackHoleMenuSound", s.black_hole_menu_sound);
        s.tnt_timer = b(&map, "tntTimer", s.tnt_timer);
        s.borderless_fullscreen = b(&map, "borderlessFullscreen", s.borderless_fullscreen);
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
        s.island_core_culling = b(&map, "islandCoreCulling", s.island_core_culling);
        s.smart_chunk_updates = b(&map, "smartChunkUpdates", s.smart_chunk_updates);
        s.menu_world_unload = b(&map, "menuWorldUnload", s.menu_world_unload);

        s.extra_props = map;
        s
    }

    pub fn save_to_game_dir(&self, game_dir: &str) -> Result<(), String> {
        let config_dir = Path::new(game_dir).join("config");
        std::fs::create_dir_all(&config_dir).map_err(|e| format!("Création dossier config: {e}"))?;
        let path = config_dir.join("purefps.properties");

        let mut map = self.extra_props.clone();
        map.insert("activeProfile".into(), self.active_profile.id().into());
        map.insert("language".into(), normalize_language(&self.language));
        map.insert("enabled".into(), self.enabled.to_string());
        map.insert("customMainMenu".into(), self.custom_main_menu.to_string());
        map.insert(
            "blackHoleMenuSound".into(),
            self.black_hole_menu_sound.to_string(),
        );
        map.insert("tntTimer".into(), self.tnt_timer.to_string());
        map.insert("borderlessFullscreen".into(), self.borderless_fullscreen.to_string());
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
        map.insert("islandCoreCulling".into(), self.island_core_culling.to_string());
        map.insert("smartChunkUpdates".into(), self.smart_chunk_updates.to_string());
        map.insert("menuWorldUnload".into(), self.menu_world_unload.to_string());

        let mut out = String::from("# MashClient 4.5.1 Configuration\n");
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
    /// `useVbo:true`, `enableVsync:false`, `maxFps:260` (FPS illimités)
    /// et désactive le plein écran exclusif lorsque `borderless_fullscreen` est actif.
    pub fn sync_vanilla_options_txt(&self, game_dir: &str) {
        let options_path = Path::new(game_dir).join("options.txt");
        let Ok(existing) = std::fs::read_to_string(&options_path) else {
            return;
        };

        let mut updated_lines: Vec<String> = Vec::new();
        let mut saw_vbo = false;
        let mut saw_vsync = false;
        let mut saw_max_fps = false;
        let mut saw_fullscreen = false;
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
                if parsed <= 240 {
                    updated_lines.push("maxFps:260".to_owned());
                    changed = true;
                } else {
                    updated_lines.push(line.to_owned());
                }
            } else if self.borderless_fullscreen && line.starts_with("fullscreen:") {
                saw_fullscreen = true;
                if line.trim() != "fullscreen:false" {
                    updated_lines.push("fullscreen:false".to_owned());
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
        if self.borderless_fullscreen && !saw_fullscreen {
            updated_lines.push("fullscreen:false".to_owned());
            changed = true;
        }

        if changed {
            let _ = std::fs::write(&options_path, updated_lines.join("\n") + "\n");
        }
    }
}

pub fn empty_modules_json() -> serde_json::Value {
    serde_json::json!({
        "client": "MashClient",
        "version": "4.5.9",
        "modules": {}
    })
}

pub fn load_modules_json(game_dir: &str) -> serde_json::Value {
    let path = Path::new(game_dir).join("config").join("mashclient_modules.json");
    let mut root = std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
        .filter(|v| v.is_object())
        .unwrap_or_else(empty_modules_json);

    if let Some(obj) = root.as_object_mut() {
        obj.insert("client".to_owned(), serde_json::Value::String("MashClient".to_owned()));
        obj.insert("version".to_owned(), serde_json::Value::String("4.5.9".to_owned()));
        if !obj.get("modules").is_some_and(serde_json::Value::is_object) {
            obj.insert("modules".to_owned(), serde_json::json!({}));
        }
    }
    root
}

pub fn save_modules_json(game_dir: &str, data: &serde_json::Value) -> Result<(), String> {
    let config_dir = Path::new(game_dir).join("config");
    std::fs::create_dir_all(&config_dir).map_err(|e| format!("Création dossier config: {e}"))?;
    let mut normalized = if data.is_object() {
        data.clone()
    } else {
        empty_modules_json()
    };
    if let Some(obj) = normalized.as_object_mut() {
        obj.insert("client".to_owned(), serde_json::Value::String("MashClient".to_owned()));
        obj.insert("version".to_owned(), serde_json::Value::String("4.5.9".to_owned()));
        if !obj.get("modules").is_some_and(serde_json::Value::is_object) {
            obj.insert("modules".to_owned(), serde_json::json!({}));
        }
    }
    let json = serde_json::to_string_pretty(&normalized)
        .map_err(|e| format!("Sérialisation mashclient_modules.json: {e}"))?;
    std::fs::write(config_dir.join("mashclient_modules.json"), json)
        .map_err(|e| format!("Écriture mashclient_modules.json: {e}"))
}

fn ensure_mod_obj<'a>(
    mods_map: &'a mut serde_json::Map<String, serde_json::Value>,
    mod_id: &str,
) -> &'a mut serde_json::Map<String, serde_json::Value> {
    if !mods_map.get(mod_id).is_some_and(serde_json::Value::is_object) {
        mods_map.insert(mod_id.to_owned(), serde_json::json!({}));
    }
    mods_map
        .get_mut(mod_id)
        .and_then(serde_json::Value::as_object_mut)
        .expect("objet module valide")
}

fn set_mod_enabled_in_map(
    mods_map: &mut serde_json::Map<String, serde_json::Value>,
    mod_id: &str,
    enabled: bool,
) {
    let m = ensure_mod_obj(mods_map, mod_id);
    m.insert("enabled".to_owned(), serde_json::Value::Bool(enabled));
}

fn set_mod_setting_in_map(
    mods_map: &mut serde_json::Map<String, serde_json::Value>,
    mod_id: &str,
    setting_key: &str,
    val: serde_json::Value,
) {
    let m = ensure_mod_obj(mods_map, mod_id);
    if !m.get("settings").is_some_and(serde_json::Value::is_object) {
        m.insert("settings".to_owned(), serde_json::json!({}));
    }
    if let Some(s_obj) = m.get_mut("settings").and_then(serde_json::Value::as_object_mut) {
        s_obj.insert(setting_key.to_owned(), val);
    }
}

/// Synchronise les réglages `PureClientSettings` (`purefps.properties`) vers l'arbre JSON des modules (`mashclient_modules.json`).
pub fn sync_pure_settings_to_modules_json(
    settings: &PureClientSettings,
    root: &mut serde_json::Value,
) {
    if !root.is_object() {
        *root = empty_modules_json();
    }
    let Some(root_obj) = root.as_object_mut() else {
        return;
    };
    root_obj.insert("client".to_owned(), serde_json::Value::String("MashClient".to_owned()));
    root_obj.insert("version".to_owned(), serde_json::Value::String("4.5.1".to_owned()));
    if !root_obj.get("modules").is_some_and(serde_json::Value::is_object) {
        root_obj.insert("modules".to_owned(), serde_json::json!({}));
    }
    let Some(mods) = root_obj.get_mut("modules").and_then(serde_json::Value::as_object_mut) else {
        return;
    };

    set_mod_enabled_in_map(mods, "eng_region_multidraw", settings.region_multi_draw);
    set_mod_enabled_in_map(mods, "eng_island_core_cull", settings.island_core_culling);
    set_mod_enabled_in_map(mods, "eng_smart_chunk_updates", settings.smart_chunk_updates);
    set_mod_enabled_in_map(mods, "eng_menu_world_unload", settings.menu_world_unload);
    set_mod_enabled_in_map(mods, "eng_lazy_terrain_bfs", settings.lazy_terrain_setup);
    set_mod_enabled_in_map(mods, "eng_frustum_pvertex", settings.fast_frustum_pvertex);
    set_mod_enabled_in_map(mods, "eng_fbo_blit_gl30", settings.fast_fbo_blit);
    set_mod_enabled_in_map(mods, "eng_no_gl_error", settings.disable_gl_error_checks);
    set_mod_enabled_in_map(mods, "eng_smart_animations", settings.smart_animations);
    set_mod_enabled_in_map(mods, "eng_entity_culling", settings.entity_occlusion_culling);
    set_mod_enabled_in_map(mods, "eng_item_frame_opt", settings.fast_item_frames);
    set_mod_enabled_in_map(mods, "eng_player_opt", settings.fast_player_rendering);
    set_mod_enabled_in_map(mods, "eng_baked_items", settings.fast_baked_item_models);
    set_mod_enabled_in_map(mods, "eng_fast_scoreboard_gpu", settings.fast_scoreboard);
    set_mod_enabled_in_map(mods, "eng_armor_stand_opt", settings.fast_armor_stands);
    set_mod_enabled_in_map(mods, "eng_chunk_threads", settings.multi_threaded_chunk_build);
    set_mod_enabled_in_map(mods, "eng_skin_pacing", settings.async_skin_upload_pacing);
    set_mod_enabled_in_map(mods, "eng_distant_glint", settings.limit_distant_glint);
    set_mod_enabled_in_map(mods, "eng_void_fog_skip", settings.skip_void_fog_loop);

    set_mod_enabled_in_map(mods, "pvp_toggle_sprint", settings.toggle_sprint);
    set_mod_enabled_in_map(mods, "pvp_zoom", settings.zoom_enabled);
    set_mod_enabled_in_map(mods, "pvp_low_fire", settings.low_fire);
    set_mod_enabled_in_map(mods, "pvp_no_hurtcam", settings.no_hurt_cam);
    set_mod_enabled_in_map(mods, "vis_fullbright", settings.fullbright);
    set_mod_enabled_in_map(mods, "vis_tnt_timer", settings.tnt_timer);
    set_mod_enabled_in_map(mods, "vis_gui_font_hd", settings.smooth_client_font);
    set_mod_enabled_in_map(mods, "wld_borderless", settings.borderless_fullscreen);
    set_mod_enabled_in_map(mods, "wld_custom_main_menu", settings.custom_main_menu);
    set_mod_enabled_in_map(mods, "hud_fps", settings.hud_fps_cps);
    set_mod_enabled_in_map(mods, "hud_ping", settings.hud_ping);
    set_mod_enabled_in_map(mods, "hud_keystrokes", settings.hud_keystrokes);
    set_mod_enabled_in_map(mods, "hud_armor", settings.hud_armor);

    set_mod_setting_in_map(
        mods,
        "eng_entity_culling",
        "max_dist",
        serde_json::json!(settings.max_entity_render_distance),
    );
    set_mod_setting_in_map(
        mods,
        "eng_entity_culling",
        "occlusion",
        serde_json::json!(settings.entity_occlusion_culling),
    );
    set_mod_setting_in_map(
        mods,
        "eng_item_frame_opt",
        "max_dist",
        serde_json::json!(settings.max_item_frame_distance),
    );
    set_mod_setting_in_map(
        mods,
        "eng_tile_culling",
        "max_dist",
        serde_json::json!(settings.max_tile_entity_render_distance),
    );
    set_mod_setting_in_map(
        mods,
        "eng_particle_engine",
        "max_particles",
        serde_json::json!(settings.max_particles_per_layer),
    );
    set_mod_setting_in_map(
        mods,
        "eng_unfocused_saver",
        "fps_cap",
        serde_json::json!(settings.unfocused_fps_limit),
    );
    set_mod_setting_in_map(
        mods,
        "wld_custom_main_menu",
        "black_hole_sound",
        serde_json::json!(settings.black_hole_menu_sound),
    );
    set_mod_setting_in_map(
        mods,
        "pvp_zoom",
        "cinematic_cam",
        serde_json::json!(settings.zoom_smooth_camera),
    );
}

/// Répercute la modification d'un module (`mashclient_modules.json`) vers `PureClientSettings` (`purefps.properties`).
pub fn apply_module_entry_to_pure_settings(
    mod_id: &str,
    entry: &serde_json::Value,
    settings: &mut PureClientSettings,
) {
    let mut engine_edited = false;
    if let Some(en) = entry.get("enabled").and_then(serde_json::Value::as_bool) {
        match mod_id {
            "hud_fps" => settings.hud_fps_cps = en,
            "hud_ping" => settings.hud_ping = en,
            "hud_keystrokes" => settings.hud_keystrokes = en,
            "hud_armor" => settings.hud_armor = en,
            "pvp_toggle_sprint" => settings.toggle_sprint = en,
            "pvp_zoom" => settings.zoom_enabled = en,
            "pvp_low_fire" => settings.low_fire = en,
            "pvp_no_hurtcam" => settings.no_hurt_cam = en,
            "vis_fullbright" => settings.fullbright = en,
            "vis_tnt_timer" => settings.tnt_timer = en,
            "vis_gui_font_hd" => settings.smooth_client_font = en,
            "wld_borderless" => settings.borderless_fullscreen = en,
            "wld_custom_main_menu" => settings.custom_main_menu = en,
            "eng_region_multidraw" => {
                settings.region_multi_draw = en;
                settings.hardware_region_vao = en;
                engine_edited = true;
            }
            "eng_island_core_cull" => {
                settings.island_core_culling = en;
                engine_edited = true;
            }
            "eng_smart_chunk_updates" => {
                settings.smart_chunk_updates = en;
                engine_edited = true;
            }
            "eng_menu_world_unload" => {
                settings.menu_world_unload = en;
                engine_edited = true;
            }
            "eng_lazy_terrain_bfs" => {
                settings.lazy_terrain_setup = en;
                engine_edited = true;
            }
            "eng_frustum_pvertex" => {
                settings.fast_frustum_pvertex = en;
                engine_edited = true;
            }
            "eng_fbo_blit_gl30" => {
                settings.fast_fbo_blit = en;
                engine_edited = true;
            }
            "eng_no_gl_error" => {
                settings.disable_gl_error_checks = en;
                engine_edited = true;
            }
            "eng_smart_animations" => {
                settings.smart_animations = en;
                engine_edited = true;
            }
            "eng_entity_culling" => {
                settings.entity_occlusion_culling = en;
                engine_edited = true;
            }
            "eng_item_frame_opt" => {
                settings.fast_item_frames = en;
                engine_edited = true;
            }
            "eng_player_opt" => {
                settings.fast_player_rendering = en;
                engine_edited = true;
            }
            "eng_baked_items" => {
                settings.fast_baked_item_models = en;
                settings.fast_item_render = en;
                engine_edited = true;
            }
            "eng_fast_scoreboard_gpu" => {
                settings.fast_scoreboard = en;
                engine_edited = true;
            }
            "eng_armor_stand_opt" => {
                settings.fast_armor_stands = en;
                engine_edited = true;
            }
            "eng_chunk_threads" => {
                settings.multi_threaded_chunk_build = en;
                engine_edited = true;
            }
            "eng_skin_pacing" => {
                settings.async_skin_upload_pacing = en;
                engine_edited = true;
            }
            "eng_distant_glint" => {
                settings.limit_distant_glint = en;
                engine_edited = true;
            }
            "eng_void_fog_skip" => {
                settings.skip_void_fog_loop = en;
                engine_edited = true;
            }
            _ => {}
        }
    }

    if let Some(cfg) = entry.get("settings").and_then(serde_json::Value::as_object) {
        let num_i32 = |k: &str| -> Option<i32> {
            cfg.get(k)
                .and_then(serde_json::Value::as_f64)
                .map(|v| v.round() as i32)
        };
        match mod_id {
            "eng_entity_culling" => {
                if let Some(d) = num_i32("max_dist") {
                    settings.max_entity_render_distance = d.clamp(16, 160);
                    engine_edited = true;
                }
                if let Some(occ) = cfg.get("occlusion").and_then(serde_json::Value::as_bool) {
                    settings.entity_occlusion_culling = occ;
                    engine_edited = true;
                }
            }
            "eng_item_frame_opt" => {
                if let Some(d) = num_i32("max_dist") {
                    settings.max_item_frame_distance = d.clamp(16, 160);
                    engine_edited = true;
                }
            }
            "eng_tile_culling" => {
                if let Some(d) = num_i32("max_dist") {
                    settings.max_tile_entity_render_distance = d.clamp(16, 160);
                    engine_edited = true;
                }
            }
            "eng_particle_engine" => {
                if let Some(p) = num_i32("max_particles") {
                    settings.max_particles_per_layer = p.clamp(100, 4000);
                    engine_edited = true;
                }
            }
            "eng_unfocused_saver" => {
                if let Some(fps) = num_i32("fps_cap") {
                    settings.unfocused_fps_limit = fps.clamp(5, 60);
                    engine_edited = true;
                }
            }
            "wld_custom_main_menu" => {
                if let Some(bh) = cfg.get("black_hole_sound").and_then(serde_json::Value::as_bool) {
                    settings.black_hole_menu_sound = bh;
                }
            }
            "pvp_zoom" => {
                if let Some(cam) = cfg.get("cinematic_cam").and_then(serde_json::Value::as_bool) {
                    settings.zoom_smooth_camera = cam;
                }
            }
            _ => {}
        }
    }

    if engine_edited {
        settings.active_profile = PureProfile::Custom;
    }
}

/// Sauvegarde `purefps.properties` PUIS `mashclient_modules.json` afin que l'horodatage de
/// `mashclient_modules.json` soit toujours >= `purefps.properties` au chargement de `MashModules`.
pub fn save_all_client_configs(
    game_dir: &str,
    settings: &PureClientSettings,
    modules_json: &serde_json::Value,
) -> Result<(), String> {
    settings.save_to_game_dir(game_dir)?;
    save_modules_json(game_dir, modules_json)
}

pub fn app_storage_dir() -> PathBuf {
    static MIGRATE_ONCE: std::sync::Once = std::sync::Once::new();
    let base = std::env::var("APPDATA").unwrap_or_else(|_| ".".to_owned());
    let purefps_dir = Path::new(&base).join(".PureFPS");
    MIGRATE_ONCE.call_once(|| {
        let _ = std::fs::create_dir_all(&purefps_dir);
        for legacy_name in ["MashClient", "PureClient"] {
            let legacy_dir = Path::new(&base).join(legacy_name);
            if legacy_dir.is_dir() {
                for fname in [
                    "launcher.json",
                    "accounts.json",
                    "account.json",
                    "custom_profiles.json",
                    "updater_token.txt",
                    "ms_client_id.txt",
                ] {
                    let src = legacy_dir.join(fname);
                    let dst = purefps_dir.join(fname);
                    if src.is_file() && !dst.exists() {
                        let _ = std::fs::copy(&src, &dst);
                    }
                }
                let src_runtimes = legacy_dir.join("runtimes");
                if src_runtimes.is_dir() {
                    copy_dir_recursive_if_missing(&src_runtimes, &purefps_dir.join("runtimes"));
                }
            }
        }
    });
    purefps_dir
}

fn copy_dir_recursive_if_missing(src: &Path, dst: &Path) {
    let Ok(entries) = std::fs::read_dir(src) else {
        return;
    };
    let _ = std::fs::create_dir_all(dst);
    for entry in entries.flatten() {
        let p = entry.path();
        let d = dst.join(entry.file_name());
        if p.is_dir() {
            copy_dir_recursive_if_missing(&p, &d);
        } else if p.is_file() && !d.exists() {
            let _ = std::fs::copy(&p, &d);
        }
    }
}

fn migrate_legacy_game_dir_contents(src_game_dir: &Path, dst_game_dir: &Path) {
    if !src_game_dir.is_dir() || src_game_dir == dst_game_dir {
        return;
    }
    let _ = std::fs::create_dir_all(dst_game_dir.join("config"));
    let _ = std::fs::create_dir_all(dst_game_dir.join("mods"));
    let _ = std::fs::create_dir_all(dst_game_dir.join("resourcepacks"));

    for rel in ["config/purefps.properties", "config/mashclient_modules.json", "options.txt", "servers.dat"] {
        let s = src_game_dir.join(rel);
        let d = dst_game_dir.join(rel);
        if s.is_file() && !d.exists() {
            let _ = std::fs::copy(&s, &d);
        }
    }

    copy_dir_recursive_if_missing(
        &src_game_dir.join("resourcepacks"),
        &dst_game_dir.join("resourcepacks"),
    );

    if let Ok(entries) = std::fs::read_dir(src_game_dir.join("mods")) {
        let dst_mods = dst_game_dir.join("mods");
        for entry in entries.flatten() {
            let p = entry.path();
            let dst_p = dst_mods.join(entry.file_name());
            if p.is_file() && !dst_p.exists() {
                let _ = std::fs::copy(&p, &dst_p);
            }
        }
    }
}

pub fn load_launcher_prefs() -> LauncherPrefs {
    let path = app_storage_dir().join("launcher.json");
    let mut prefs = std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<LauncherPrefs>(&s).ok())
        .unwrap_or_default();
    prefs.ram_mb = prefs.ram_mb.clamp(2048, 65536);
    prefs.language = normalize_language(&prefs.language);

    if prefs.quick_join_hypixel && prefs.selected_server.is_none() {
        prefs.selected_server = builtin_quick_servers().into_iter().next();
        prefs.quick_join_hypixel = false;
    }

    let appdata = std::env::var("APPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());
    let local_appdata =
        std::env::var("LOCALAPPDATA").unwrap_or_else(|_| r"C:\Users\Public".to_owned());

    let (std_game, std_libs, std_assets) = detect_minecraft_189_dirs(&appdata, &local_appdata);
    let low_game = prefs.game_dir.to_ascii_lowercase();
    let needs_purefps_migration = prefs.game_dir.trim().is_empty()
        || !low_game.contains(".purefps");
    if needs_purefps_migration {
        let old_game_path = PathBuf::from(&prefs.game_dir);
        migrate_legacy_game_dir_contents(&old_game_path, Path::new(&std_game));
        prefs.game_dir = std_game;
        prefs.libraries_dir = std_libs;
        prefs.assets_dir = std_assets;
        let _ = save_launcher_prefs(&prefs);
    }

    let game_path = Path::new(&prefs.game_dir);
    let _ = std::fs::create_dir_all(game_path.join("config"));
    let _ = std::fs::create_dir_all(game_path.join("mods"));
    let _ = std::fs::create_dir_all(game_path.join("resourcepacks"));

    // Seed options.txt, servers.dat, and resourcepacks from %APPDATA%\.minecraft if not yet present in .PureFPS
    let vanilla_mc = Path::new(&appdata).join(".minecraft");
    if vanilla_mc.is_dir() && vanilla_mc != game_path {
        migrate_legacy_game_dir_contents(&vanilla_mc, game_path);
    }

    let cur_java_file = Path::new(&prefs.java_path);
    let cur_probe = crate::runtimes::probe_java8_executable(cur_java_file);
    let cur_java_legacy = (cur_probe.is_java8 && cur_probe.update_version > 0 && cur_probe.update_version < 161)
        || !cur_probe.is_64bit;
    if !cur_java_file.is_file() || cur_java_legacy {
        let detected_java = detect_java8_path(&appdata, &local_appdata);
        let det_path = Path::new(&detected_java);
        if det_path.is_file() {
            let det_probe = crate::runtimes::probe_java8_executable(det_path);
            if !cur_java_file.is_file()
                || (det_probe.is_64bit && !cur_probe.is_64bit)
                || det_probe.update_version > cur_probe.update_version
            {
                prefs.java_path = detected_java;
            }
        }
    }

    prefs
}

pub fn save_launcher_prefs(prefs: &LauncherPrefs) -> Result<(), String> {
    let dir = app_storage_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let json = serde_json::to_string_pretty(prefs).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("launcher.json"), json).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// Multi-comptes Microsoft (`accounts.json` + rétro-compatibilité `account.json`)
// ---------------------------------------------------------------------------

pub fn load_account_store_in_dir(dir: &Path) -> AccountStore {
    let mut store: AccountStore = std::fs::read_to_string(dir.join("accounts.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<AccountStore>(&s).ok())
        .unwrap_or_default();

    if let Some(legacy) = std::fs::read_to_string(dir.join("account.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<MicrosoftAccount>(&s).ok())
    {
        if !store
            .accounts
            .iter()
            .any(|a| a.uuid.eq_ignore_ascii_case(&legacy.uuid))
        {
            if store.active_uuid.is_none() {
                store.active_uuid = Some(legacy.uuid.clone());
            }
            store.accounts.push(legacy);
        }
    }

    if !store.accounts.is_empty() {
        let valid_active = store.active_uuid.as_ref().is_some_and(|act| {
            store
                .accounts
                .iter()
                .any(|a| a.uuid.eq_ignore_ascii_case(act))
        });
        if !valid_active {
            store.active_uuid = Some(store.accounts[0].uuid.clone());
        }
    } else {
        store.active_uuid = None;
    }

    store
}

pub fn save_account_store_in_dir(dir: &Path, store: &AccountStore) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let json = serde_json::to_string_pretty(store).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("accounts.json"), json).map_err(|e| e.to_string())?;

    let active_acc = store
        .active_uuid
        .as_ref()
        .and_then(|u| store.accounts.iter().find(|a| a.uuid.eq_ignore_ascii_case(u)))
        .or_else(|| store.accounts.first());

    if let Some(acc) = active_acc {
        if let Ok(legacy_json) = serde_json::to_string_pretty(acc) {
            let _ = std::fs::write(dir.join("account.json"), legacy_json);
        }
    } else {
        let _ = std::fs::remove_file(dir.join("account.json"));
    }
    Ok(())
}

pub fn load_account_store() -> AccountStore {
    load_account_store_in_dir(&app_storage_dir())
}

pub fn public_account_views(store: &AccountStore) -> Vec<PublicAccountView> {
    let active_id = store.active_uuid.as_deref().unwrap_or("");
    store
        .accounts
        .iter()
        .map(|a| PublicAccountView {
            username: a.username.clone(),
            uuid: a.uuid.clone(),
            active: a.uuid.eq_ignore_ascii_case(active_id),
        })
        .collect()
}

pub fn load_microsoft_account() -> Option<MicrosoftAccount> {
    let store = load_account_store();
    if let Some(ref act) = store.active_uuid {
        if let Some(found) = store
            .accounts
            .iter()
            .find(|a| a.uuid.eq_ignore_ascii_case(act))
        {
            return Some(found.clone());
        }
    }
    store.accounts.into_iter().next()
}

pub fn upsert_microsoft_account_in_dir(dir: &Path, acc: &MicrosoftAccount) -> Result<(), String> {
    let mut store = load_account_store_in_dir(dir);
    if let Some(existing) = store
        .accounts
        .iter_mut()
        .find(|a| a.uuid.eq_ignore_ascii_case(&acc.uuid))
    {
        *existing = acc.clone();
    } else {
        store.accounts.push(acc.clone());
    }
    store.active_uuid = Some(acc.uuid.clone());
    save_account_store_in_dir(dir, &store)
}

pub fn save_microsoft_account(acc: &MicrosoftAccount) -> Result<(), String> {
    upsert_microsoft_account_in_dir(&app_storage_dir(), acc)
}

pub fn switch_microsoft_account_in_dir(dir: &Path, uuid: &str) -> Result<MicrosoftAccount, String> {
    let mut store = load_account_store_in_dir(dir);
    let found = store
        .accounts
        .iter()
        .find(|a| a.uuid.eq_ignore_ascii_case(uuid))
        .cloned()
        .ok_or_else(|| "Compte Microsoft introuvable.".to_owned())?;
    store.active_uuid = Some(found.uuid.clone());
    save_account_store_in_dir(dir, &store)?;
    Ok(found)
}

pub fn switch_microsoft_account(uuid: &str) -> Result<MicrosoftAccount, String> {
    switch_microsoft_account_in_dir(&app_storage_dir(), uuid)
}

pub fn remove_microsoft_account_in_dir(
    dir: &Path,
    uuid: &str,
) -> Result<Option<MicrosoftAccount>, String> {
    let mut store = load_account_store_in_dir(dir);
    store
        .accounts
        .retain(|a| !a.uuid.eq_ignore_ascii_case(uuid));
    if store
        .active_uuid
        .as_ref()
        .is_some_and(|act| act.eq_ignore_ascii_case(uuid))
    {
        store.active_uuid = store.accounts.first().map(|a| a.uuid.clone());
    }
    save_account_store_in_dir(dir, &store)?;
    let next_active = store
        .active_uuid
        .as_ref()
        .and_then(|act| store.accounts.iter().find(|a| a.uuid.eq_ignore_ascii_case(act)))
        .cloned();
    Ok(next_active)
}

pub fn remove_microsoft_account(uuid: &str) -> Result<Option<MicrosoftAccount>, String> {
    remove_microsoft_account_in_dir(&app_storage_dir(), uuid)
}

pub fn clear_microsoft_account() {
    let dir = app_storage_dir();
    if let Some(cur) = load_microsoft_account() {
        let _ = remove_microsoft_account_in_dir(&dir, &cur.uuid);
    } else {
        let _ = std::fs::remove_file(dir.join("account.json"));
    }
}

// ---------------------------------------------------------------------------
// Profils personnalisés + Import / Export `.json`
// ---------------------------------------------------------------------------

fn slugify_profile_id(name: &str) -> String {
    let mut out = String::new();
    for ch in name.trim().chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
        } else if (ch == ' ' || ch == '-' || ch == '_') && !out.ends_with('_') && !out.is_empty() {
            out.push('_');
        }
    }
    let trimmed = out.trim_matches('_');
    if trimmed.is_empty() {
        "profil_perso".to_owned()
    } else {
        trimmed.chars().take(32).collect()
    }
}

pub fn load_custom_profiles_in_dir(dir: &Path) -> Vec<CustomProfile> {
    std::fs::read_to_string(dir.join("custom_profiles.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<CustomProfile>>(&s).ok())
        .unwrap_or_default()
}

pub fn save_custom_profiles_in_dir(dir: &Path, list: &[CustomProfile]) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let json = serde_json::to_string_pretty(list).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("custom_profiles.json"), json).map_err(|e| e.to_string())
}

pub fn load_custom_profiles() -> Vec<CustomProfile> {
    load_custom_profiles_in_dir(&app_storage_dir())
}

pub fn save_custom_profile_entry_in_dir(
    dir: &Path,
    name: &str,
    settings: &PureClientSettings,
    modules: &serde_json::Value,
) -> Result<CustomProfile, String> {
    let clean_name = name.trim();
    if clean_name.is_empty() {
        return Err("Veuillez saisir un nom de profil.".to_owned());
    }
    let mut list = load_custom_profiles_in_dir(dir);
    let mut saved_settings = settings.clone();
    saved_settings.active_profile = PureProfile::Custom;

    let normalized_mods = if modules.is_object() {
        modules.clone()
    } else {
        empty_modules_json()
    };

    if let Some(existing) = list
        .iter_mut()
        .find(|p| p.name.eq_ignore_ascii_case(clean_name))
    {
        existing.name = clean_name.to_owned();
        existing.settings = saved_settings;
        existing.modules = normalized_mods;
        let updated = existing.clone();
        save_custom_profiles_in_dir(dir, &list)?;
        return Ok(updated);
    }

    let base_id = slugify_profile_id(clean_name);
    let mut candidate_id = base_id.clone();
    let mut suffix = 2u32;
    while list.iter().any(|p| p.id == candidate_id) {
        candidate_id = format!("{base_id}_{suffix}");
        suffix += 1;
    }

    let created = CustomProfile {
        id: candidate_id,
        name: clean_name.to_owned(),
        settings: saved_settings,
        modules: normalized_mods,
    };
    list.push(created.clone());
    save_custom_profiles_in_dir(dir, &list)?;
    Ok(created)
}

pub fn save_custom_profile_entry(
    name: &str,
    settings: &PureClientSettings,
    modules: &serde_json::Value,
) -> Result<CustomProfile, String> {
    save_custom_profile_entry_in_dir(&app_storage_dir(), name, settings, modules)
}

pub fn delete_custom_profile_entry(id: &str) -> Result<(), String> {
    let dir = app_storage_dir();
    let mut list = load_custom_profiles_in_dir(&dir);
    list.retain(|p| p.id != id);
    save_custom_profiles_in_dir(&dir, &list)
}

pub fn export_profile_bundle_to_path(
    path: &Path,
    name: &str,
    settings: &PureClientSettings,
    modules: &serde_json::Value,
) -> Result<(), String> {
    let bundle = ExportedProfileBundle {
        client: "MashClient".to_owned(),
        version: "4.5.9".to_owned(),
        name: if name.trim().is_empty() {
            "Profil MashClient".to_owned()
        } else {
            name.trim().to_owned()
        },
        settings: settings.clone(),
        modules: if modules.is_object() {
            modules.clone()
        } else {
            empty_modules_json()
        },
    };
    let json = serde_json::to_string_pretty(&bundle)
        .map_err(|e| format!("Sérialisation du profil impossible: {e}"))?;
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    std::fs::write(path, json).map_err(|e| format!("Écriture du profil impossible: {e}"))
}

pub fn import_profile_bundle_from_path_in_dir(
    storage_dir: &Path,
    path: &Path,
) -> Result<CustomProfile, String> {
    let raw = std::fs::read_to_string(path)
        .map_err(|e| format!("Impossible de lire le fichier profil : {e}"))?;
    let bundle: ExportedProfileBundle = serde_json::from_str(&raw)
        .map_err(|e| format!("Format JSON de profil MashClient invalide : {e}"))?;
    let fallback_name = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "Profil importé".to_owned());
    let name = if bundle.name.trim().is_empty() {
        fallback_name
    } else {
        bundle.name
    };
    save_custom_profile_entry_in_dir(storage_dir, &name, &bundle.settings, &bundle.modules)
}

pub fn import_profile_bundle_from_path(path: &Path) -> Result<CustomProfile, String> {
    import_profile_bundle_from_path_in_dir(&app_storage_dir(), path)
}

/// Résout le jeton GitHub de l'Auto-Updater dans l'ordre de priorité :
/// 1. Jeton local optionnel dans `%APPDATA%\MashClient\updater_token.txt`
/// 2. Variable d'environnement `PURECLIENT_GITHUB_TOKEN`
/// 3. Secret injecté à la compilation (`PURECLIENT_UPDATER_TOKEN` via GitHub Actions ou `launcher/.github_token` ignoré par Git)
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

/// Résout le Client ID Microsoft OAuth2 (Device Code Flow) sans clé en dur dans le dépôt public :
/// 1. Fichier local optionnel `%APPDATA%\MashClient\ms_client_id.txt`
/// 2. Variable d'environnement `MASHCLIENT_MS_CLIENT_ID`
/// 3. Secret injecté à la compilation (`MASHCLIENT_MS_CLIENT_ID` via GitHub Actions ou `launcher/.ms_client_id` ignoré par Git)
pub fn effective_ms_client_id() -> Option<String> {
    if let Ok(custom) = std::fs::read_to_string(app_storage_dir().join("ms_client_id.txt")) {
        let t = custom.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    if let Ok(env_id) = std::env::var("MASHCLIENT_MS_CLIENT_ID") {
        let t = env_id.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    if let Some(embedded) = option_env!("MASHCLIENT_EMBEDDED_MS_CLIENT_ID") {
        let t = embedded.trim();
        if !t.is_empty() {
            return Some(t.to_owned());
        }
    }
    None
}

/// Écrit ou nettoie le fichier pont `config/.purefps_quick_server` consommé par `GuiPureMainMenu`.
pub fn write_or_clear_quick_server_file(game_dir: &str, server: Option<&QuickServerEntry>) {
    let cfg_dir = Path::new(game_dir).join("config");
    let _ = std::fs::create_dir_all(&cfg_dir);
    let qs_path = cfg_dir.join(".purefps_quick_server");
    if let Some(srv) = server.filter(|s| !s.address.trim().is_empty()) {
        let payload = format!("{}\n{}\n", srv.address.trim(), srv.name.trim());
        let _ = std::fs::write(&qs_path, payload);
    } else {
        let _ = std::fs::remove_file(&qs_path);
    }
}

/// Prépare les fichiers de synchronisation sans couture entre MashClient (rideau noir Trou Noir)
/// et Minecraft 1.8.9 : désactive `SplashProgress` (`enabled=false`) pour éviter que Forge ne crée
/// un `SharedDrawable` (`wglShareLists`) qui désactive la Threaded Optimization du pilote NVIDIA.
pub fn prepare_seamless_launch_files(game_dir: &str) {
    let cfg_dir = Path::new(game_dir).join("config");
    let _ = std::fs::create_dir_all(&cfg_dir);

    let _ = std::fs::remove_file(cfg_dir.join(".purefps_display_ready"));
    let _ = std::fs::remove_file(cfg_dir.join(".purefps_menu_ready"));

    let handoff_file = cfg_dir.join(".purefps_handoff");
    let _ = std::fs::write(&handoff_file, "SINGULARITY_HANDOFF\n");

    let splash_path = cfg_dir.join("splash.properties");
    let splash_black = "\
# MashClient 4.5.1 — NVIDIA Threaded GL (No SharedDrawable)\n\
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
        let tmp = std::env::temp_dir().join("mashclient_cfg_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        let opts_path = tmp.join("options.txt");
        std::fs::write(
            &opts_path,
            "fov:0.5\nmaxFps:120\nenableVsync:true\nuseVbo:false\nfullscreen:true\n",
        )
        .unwrap();

        let mut cfg = PureClientSettings::default();
        cfg.apply_profile(PureProfile::Fps);
        cfg.black_hole_menu_sound = false;
        cfg.tnt_timer = true;
        cfg.save_to_game_dir(&tmp.to_string_lossy()).unwrap();

        let reloaded = PureClientSettings::load_from_game_dir(&tmp.to_string_lossy());
        assert_eq!(reloaded.active_profile, PureProfile::Fps);
        assert!(!reloaded.black_hole_menu_sound);
        assert!(reloaded.tnt_timer);
        assert_eq!(reloaded.unfocused_fps_limit, 30);
        assert!(reloaded.borderless_fullscreen);
        assert!(reloaded.fast_baked_item_models);
        assert!(reloaded.fast_item_frames);
        assert_eq!(reloaded.max_item_frame_distance, 36);
        assert!(reloaded.fast_player_rendering);
        assert!(reloaded.fast_sign_text);
        assert!(reloaded.island_core_culling);
        assert!(reloaded.smart_chunk_updates);
        assert!(reloaded.menu_world_unload);

        let patched_opts = std::fs::read_to_string(&opts_path).unwrap();
        assert!(patched_opts.contains("useVbo:true"));
        assert!(patched_opts.contains("enableVsync:false"));
        assert!(patched_opts.contains("maxFps:260"));
        assert!(patched_opts.contains("fullscreen:false"));

        prepare_seamless_launch_files(&tmp.to_string_lossy());
        let splash = std::fs::read_to_string(tmp.join("config").join("splash.properties")).unwrap();
        assert!(splash.contains("enabled=false"));
        assert!(splash.contains("background=0x000000"));

        let srv = QuickServerEntry {
            id: "pika".into(),
            name: "PikaNetwork".into(),
            address: "play.pikanetwork.net".into(),
        };
        write_or_clear_quick_server_file(&tmp.to_string_lossy(), Some(&srv));
        let qs = std::fs::read_to_string(tmp.join("config").join(".purefps_quick_server")).unwrap();
        assert_eq!(qs, "play.pikanetwork.net\nPikaNetwork\n");
        write_or_clear_quick_server_file(&tmp.to_string_lossy(), None);
        assert!(!tmp.join("config").join(".purefps_quick_server").exists());

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_modules_json_bidirectional_sync() {
        let tmp = std::env::temp_dir().join("mashclient_mods_sync_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();
        let dir_str = tmp.to_string_lossy().to_string();

        let mut cfg = PureClientSettings::default();
        let mut mods = load_modules_json(&dir_str);
        sync_pure_settings_to_modules_json(&cfg, &mut mods);

        let item_frame_entry = serde_json::json!({
            "enabled": false,
            "settings": { "max_dist": 72 }
        });
        mods["modules"]["eng_item_frame_opt"] = item_frame_entry.clone();
        apply_module_entry_to_pure_settings("eng_item_frame_opt", &item_frame_entry, &mut cfg);

        assert!(!cfg.fast_item_frames);
        assert_eq!(cfg.max_item_frame_distance, 72);
        assert_eq!(cfg.active_profile, PureProfile::Custom);

        save_all_client_configs(&dir_str, &cfg, &mods).unwrap();
        let reloaded_mods = load_modules_json(&dir_str);
        assert_eq!(reloaded_mods["version"], "4.5.9");
        assert_eq!(reloaded_mods["modules"]["eng_item_frame_opt"]["enabled"], false);
        assert_eq!(reloaded_mods["modules"]["eng_item_frame_opt"]["settings"]["max_dist"], 72);

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_multi_account_migration_switch_and_remove() {
        let tmp = std::env::temp_dir().join("mashclient_accounts_store_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        let legacy = MicrosoftAccount {
            username: "FirstUser".into(),
            uuid: "aaaa1111bbbb2222cccc3333dddd4444".into(),
            access_token: "tok1".into(),
            refresh_token: "ref1".into(),
            obtained_epoch_secs: 100,
        };
        std::fs::write(
            tmp.join("account.json"),
            serde_json::to_string(&legacy).unwrap(),
        )
        .unwrap();

        let store = load_account_store_in_dir(&tmp);
        assert_eq!(store.accounts.len(), 1);
        assert_eq!(store.active_uuid.as_deref(), Some(legacy.uuid.as_str()));

        let second = MicrosoftAccount {
            username: "AltBedWars".into(),
            uuid: "ffff9999eeee8888dddd7777cccc6666".into(),
            access_token: "tok2".into(),
            refresh_token: "ref2".into(),
            obtained_epoch_secs: 200,
        };
        upsert_microsoft_account_in_dir(&tmp, &second).unwrap();
        let store2 = load_account_store_in_dir(&tmp);
        assert_eq!(store2.accounts.len(), 2);
        assert_eq!(store2.active_uuid.as_deref(), Some(second.uuid.as_str()));

        let switched = switch_microsoft_account_in_dir(&tmp, &legacy.uuid).unwrap();
        assert_eq!(switched.username, "FirstUser");

        let remaining = remove_microsoft_account_in_dir(&tmp, &legacy.uuid).unwrap();
        assert_eq!(remaining.unwrap().username, "AltBedWars");

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_custom_profile_save_export_and_import() {
        let tmp = std::env::temp_dir().join("mashclient_custom_prof_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        let mut cfg = PureClientSettings::default();
        cfg.apply_profile(PureProfile::Fps);
        cfg.no_hurt_cam = true;
        let mods = serde_json::json!({
            "client": "MashClient",
            "version": "4.5.1",
            "modules": {
                "hud_fps": { "enabled": true, "x": 120.0, "y": 40.0 }
            }
        });

        let saved = save_custom_profile_entry_in_dir(&tmp, "Ranked BedWars", &cfg, &mods).unwrap();
        assert_eq!(saved.id, "ranked_bedwars");
        assert_eq!(saved.settings.active_profile, PureProfile::Custom);

        let exported_file = tmp.join("exported_bedwars.json");
        export_profile_bundle_to_path(&exported_file, "Tournoi PvP", &saved.settings, &saved.modules).unwrap();

        let imported = import_profile_bundle_from_path_in_dir(&tmp, &exported_file).unwrap();
        assert_eq!(imported.name, "Tournoi PvP");
        assert!(imported.settings.no_hurt_cam);
        assert_eq!(imported.modules["modules"]["hud_fps"]["x"], 120.0);
        assert_eq!(load_custom_profiles_in_dir(&tmp).len(), 2);

        let _ = std::fs::remove_dir_all(&tmp);
    }
}

