#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! MashClient 4.5 — Native Desktop Launcher (`MashClient.exe`, WebView2 `tao` + `wry`)
//! - Rendu temps-réel WebGL2 3D Gargantua de Schwarzschild (texture FBM pré-calculée + RK2 adaptatif 120+ FPS)
//! - Transition cinématique sans changement de fenêtre visible et sans jamais rétrécir la fenêtre :
//!   aspiration dans l'Horizon des Événements -> écran noir #000000 maintenu jusqu'à l'ouverture OpenGL/Vulkan de Minecraft ->
//!   fermeture complète de la fenêtre Launcher vers la zone de notification en bas à droite (System Tray Windows, 0% GPU) ->
//!   remplacement de l'écran "Mojang" par la caméra du joueur qui sort du Trou Noir à 100+ FPS constants.

mod auth;
mod config;
mod dialog;
mod mods_manager;
mod runner;
mod runtimes;
mod tray;
mod updater;
#[cfg(all(target_os = "windows", not(target_env = "msvc")))]
mod webview2_gnu_shim;

use auth::{
    copy_to_windows_clipboard, open_verification_browser, poll_device_code_until_complete,
    refresh_microsoft_session, start_device_code_flow, DeviceCodeChallenge,
};
use config::{
    apply_module_entry_to_pure_settings, app_storage_dir, builtin_quick_servers,
    clear_microsoft_account, delete_custom_profile_entry, effective_github_token,
    export_profile_bundle_to_path, import_profile_bundle_from_path, load_account_store,
    load_custom_profiles, load_launcher_prefs, load_microsoft_account, load_modules_json,
    public_account_views, remove_microsoft_account, save_all_client_configs,
    save_custom_profile_entry, save_launcher_prefs, save_microsoft_account,
    switch_microsoft_account, sync_pure_settings_to_modules_json, CustomProfile,
    CustomProfileSummary, InstanceFlavor, LaunchMode, LauncherPrefs, MicrosoftAccount,
    PublicAccountView, PureClientSettings, PureProfile, QuickServerEntry,
};
use mods_manager::InstalledModEntry;
use runner::{
    auto_fetch_mod_jar_if_needed, collect_watched_189_game_dirs, focus_visible_lwjgl_window,
    has_visible_lwjgl_window, offline_uuid_hex, prepare_prism_bridge_files,
    run_direct_jvm_blocking, spawn_prism_bridge_now, sync_configs_across_watched_189_dirs,
    CrashReportInfo,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::VecDeque;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tao::dpi::{LogicalSize, Size};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopProxy, EventLoopBuilder};
use tao::window::{Fullscreen, Icon, Window, WindowBuilder};
use tray::{SingleInstanceGuard, SystemTray};
use updater::{
    check_for_updates, cleanup_old_launcher_binary, download_and_install_update, format_semver,
    installed_mod_version, parse_semver, restart_launcher_process, sanitize_repo, InstallOutcome,
    PendingUpdate, UiUpdateView, UpdateCheckOutcome, LAUNCHER_VERSION,
};
use wry::WebViewBuilder;
#[cfg(windows)]
use wry::WebViewBuilderExtWindows;

const UI_HTML: &str = include_str!("ui.html");
const WINDOW_ICON_64_RGBA: &[u8] = include_bytes!("../assets/icon_64.rgba");

#[cfg(windows)]
fn allow_any_foreground_window() {
    #[link(name = "user32")]
    extern "system" {
        fn AllowSetForegroundWindow(dwProcessId: u32) -> i32;
    }
    unsafe {
        let _ = AllowSetForegroundWindow(0xFFFF_FFFF);
    }
}

#[cfg(not(windows))]
fn allow_any_foreground_window() {}

#[cfg(windows)]
fn user_pressing_task_switch_keys() -> bool {
    const VK_MENU: i32 = 0x12; // Alt (Alt+Tab / Alt+Esc)
    const VK_LWIN: i32 = 0x5B; // Touche Windows gauche
    const VK_RWIN: i32 = 0x5C; // Touche Windows droite
    #[link(name = "user32")]
    extern "system" {
        fn GetAsyncKeyState(vKey: i32) -> i16;
    }
    unsafe {
        (GetAsyncKeyState(VK_MENU) as u16 & 0x8000) != 0
            || (GetAsyncKeyState(VK_LWIN) as u16 & 0x8000) != 0
            || (GetAsyncKeyState(VK_RWIN) as u16 & 0x8000) != 0
    }
}

#[cfg(not(windows))]
fn user_pressing_task_switch_keys() -> bool {
    false
}

/// Masque instantanément le HWND sous Windows SANS quitter le plein écran au préalable
/// (évite à 100% le flash/rétrécissement vers 1240×780) et retire le bouton de la barre des tâches
/// pour ne laisser que l'icône MashClient dans la zone de notification en bas à droite (System Tray).
#[cfg(windows)]
fn win32_hide_window_to_tray(window: &Window) {
    use core::ffi::c_void;
    use tao::platform::windows::WindowExtWindows;

    const SW_HIDE: i32 = 0;
    const GWL_EXSTYLE: i32 = -20;
    const WS_EX_APPWINDOW: isize = 0x0004_0000;
    const WS_EX_TOOLWINDOW: isize = 0x0000_0080;

    #[link(name = "user32")]
    extern "system" {
        fn ShowWindow(hWnd: *mut c_void, nCmdShow: i32) -> i32;
        fn GetWindowLongPtrW(hWnd: *mut c_void, nIndex: i32) -> isize;
        fn SetWindowLongPtrW(hWnd: *mut c_void, nIndex: i32, dwNewLong: isize) -> isize;
    }

    let hwnd = window.hwnd() as *mut c_void;
    if !hwnd.is_null() {
        unsafe {
            let _ = ShowWindow(hwnd, SW_HIDE);
            let ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
            let _ = SetWindowLongPtrW(hwnd, GWL_EXSTYLE, (ex & !WS_EX_APPWINDOW) | WS_EX_TOOLWINDOW);
            let _ = ShowWindow(hwnd, SW_HIDE);
        }
    }
    let _ = window.set_skip_taskbar(true);
    window.set_visible(false);
}

#[cfg(not(windows))]
fn win32_hide_window_to_tray(window: &Window) {
    window.set_visible(false);
}

#[cfg(windows)]
fn win32_restore_window_from_tray(window: &Window) {
    use core::ffi::c_void;
    use tao::platform::windows::WindowExtWindows;

    const SW_SHOW: i32 = 5;
    const SW_RESTORE: i32 = 9;
    const GWL_EXSTYLE: i32 = -20;
    const WS_EX_APPWINDOW: isize = 0x0004_0000;
    const WS_EX_TOOLWINDOW: isize = 0x0000_0080;

    #[link(name = "user32")]
    extern "system" {
        fn ShowWindow(hWnd: *mut c_void, nCmdShow: i32) -> i32;
        fn IsWindowVisible(hWnd: *mut c_void) -> i32;
        fn IsIconic(hWnd: *mut c_void) -> i32;
        fn GetWindowLongPtrW(hWnd: *mut c_void, nIndex: i32) -> isize;
        fn SetWindowLongPtrW(hWnd: *mut c_void, nIndex: i32, dwNewLong: isize) -> isize;
        fn BringWindowToTop(hWnd: *mut c_void) -> i32;
        fn SetForegroundWindow(hWnd: *mut c_void) -> i32;
    }

    let _ = window.set_skip_taskbar(false);
    window.set_fullscreen(None);
    let hwnd = window.hwnd() as *mut c_void;
    if !hwnd.is_null() {
        unsafe {
            let was_hidden = IsWindowVisible(hwnd) == 0;
            let ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
            let _ = SetWindowLongPtrW(hwnd, GWL_EXSTYLE, (ex & !WS_EX_TOOLWINDOW) | WS_EX_APPWINDOW);
            if was_hidden || IsIconic(hwnd) != 0 {
                let _ = ShowWindow(hwnd, SW_RESTORE);
            }
            let _ = ShowWindow(hwnd, SW_SHOW);
            let _ = BringWindowToTop(hwnd);
            let _ = SetForegroundWindow(hwnd);
        }
    }
    window.set_visible(true);
    window.set_minimized(false);
    window.set_focus();
}

#[cfg(not(windows))]
fn win32_restore_window_from_tray(window: &Window) {
    window.set_fullscreen(None);
    window.set_visible(true);
    window.set_minimized(false);
    window.set_focus();
}

#[derive(Debug)]
pub enum AppEvent {
    IpcRaw(String),
    DeviceCodeStarted(Result<DeviceCodeChallenge, String>),
    AuthCompleted(Result<MicrosoftAccount, String>),
    UpdateChecked {
        manual: bool,
        result: Result<UpdateCheckOutcome, String>,
    },
    UpdateProgress {
        percent: u8,
        message: String,
    },
    UpdateInstalled(Result<InstallOutcome, String>),
    TemurinInstalled(Result<(String, String), String>),
    ModsImported(Result<usize, String>),
    ProfileImported(Result<CustomProfile, String>),
    PreLaunchModSynced,
    GameRunning(bool),
    GameCrashed(CrashReportInfo),
    MinecraftMenuReady,
    RevealMinecraftNow(u64),
    CurtainSafetyTimeout(u64),
    LaunchAborted(String),
    TrayRestore,
    TrayQuit,
    LogLine(String),
    Toast {
        message: String,
        is_error: bool,
    },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
enum IpcCommand {
    UiReady,
    DragWindow,
    Minimize,
    MinimizeToTray,
    ToggleMaximize,
    Close,
    SetInstanceFlavor {
        flavor: InstanceFlavor,
    },
    SetProfile {
        profile: String,
    },
    SaveCustomProfile {
        name: String,
    },
    ApplyCustomProfile {
        id: String,
    },
    DeleteCustomProfile {
        id: String,
    },
    ExportProfileJson {
        #[serde(default)]
        id: Option<String>,
    },
    ImportProfileJson,
    SetSetting {
        key: String,
        value: serde_json::Value,
    },
    SaveModuleEntry {
        mod_id: String,
        entry: serde_json::Value,
        #[serde(default)]
        setting_key: Option<String>,
    },
    ResetModuleEntry {
        mod_id: String,
        default_entry: serde_json::Value,
        #[serde(default)]
        setting_key: Option<String>,
    },
    SaveHudLayout {
        positions: std::collections::BTreeMap<String, [f32; 2]>,
    },
    ResetHudLayout {
        defaults: std::collections::BTreeMap<String, [f32; 2]>,
    },
    RefreshModsList,
    ImportModsDialog,
    ToggleThirdPartyMod {
        file_name: String,
    },
    DeleteThirdPartyMod {
        file_name: String,
    },
    OpenCrashReport {
        path: String,
    },
    SetRam {
        ram_mb: u32,
    },
    SetOfflineName {
        name: String,
    },
    SetJavaPath {
        path: String,
    },
    InstallTemurinJava8,
    SetGameDir {
        path: String,
    },
    SetLaunchMode {
        mode: LaunchMode,
    },
    ToggleQuickHypixel,
    SelectQuickServer {
        #[serde(default)]
        server: Option<QuickServerEntry>,
    },
    QuickConnectServer {
        id: String,
        name: String,
        address: String,
    },
    AddCustomServer {
        name: String,
        address: String,
    },
    RemoveCustomServer {
        id: String,
    },
    StartMsLogin,
    CancelMsLogin,
    OpenMsLink,
    CopyCode {
        code: String,
    },
    RefreshMs,
    SwitchMsAccount {
        uuid: String,
    },
    RemoveMsAccount {
        uuid: String,
    },
    LogoutMs,
    OpenModsFolder,
    CheckUpdates {
        #[serde(default)]
        manual: bool,
    },
    InstallUpdate,
    RestartLauncher,
    SetAutoUpdate {
        enabled: bool,
    },
    SetGithubRepo {
        repo: String,
    },
    LaunchGame,
    PlungeDarknessReached,
    AbortLaunchCurtain,
}

#[derive(Serialize)]
struct UiDeviceCodeView {
    user_code: String,
    verification_uri: String,
}

#[derive(Serialize)]
struct UiStateSnapshot<'a> {
    settings: &'a PureClientSettings,
    modules: &'a serde_json::Value,
    prefs: &'a LauncherPrefs,
    active_game_dir: &'a str,
    account: Option<PublicAccountView>,
    accounts: &'a [PublicAccountView],
    active_custom_profile_id: Option<&'a str>,
    custom_profiles: &'a [CustomProfileSummary],
    installed_mods: &'a [InstalledModEntry],
    builtin_servers: &'a [QuickServerEntry],
    java_status_label: &'a str,
    java_needs_upgrade: bool,
    java_installing: bool,
    game_running: bool,
    device_code: Option<UiDeviceCodeView>,
    update: &'a UiUpdateView,
}

fn load_synced_configs(game_dir: &str) -> (PureClientSettings, serde_json::Value) {
    let mut settings = PureClientSettings::load_from_game_dir(game_dir);
    let cfg_dir = Path::new(game_dir).join("config");
    let mods_path = cfg_dir.join("mashclient_modules.json");
    let props_path = cfg_dir.join("purefps.properties");
    let had_mods = mods_path.is_file();
    let mut modules_json = load_modules_json(game_dir);

    if !had_mods {
        sync_pure_settings_to_modules_json(&settings, &mut modules_json);
    } else {
        let mods_mtime = std::fs::metadata(&mods_path).and_then(|m| m.modified()).ok();
        let props_mtime = std::fs::metadata(&props_path).and_then(|m| m.modified()).ok();
        let props_strictly_newer = match (props_mtime, mods_mtime) {
            (Some(pt), Some(mt)) => pt > mt,
            _ => false,
        };
        if props_strictly_newer {
            sync_pure_settings_to_modules_json(&settings, &mut modules_json);
        } else if let Some(mods_map) = modules_json.get("modules").and_then(serde_json::Value::as_object) {
            for (mod_id, entry) in mods_map {
                apply_module_entry_to_pure_settings(mod_id, entry, &mut settings);
            }
        }
    }
    (settings, modules_json)
}

fn merge_module_entry(
    modules_json: &mut serde_json::Value,
    mod_id: &str,
    incoming: &serde_json::Value,
    replace_settings: bool,
) -> serde_json::Value {
    if !modules_json.is_object() {
        *modules_json = json!({ "client": "MashClient", "version": "4.5.1", "modules": {} });
    }
    let root_obj = modules_json.as_object_mut().expect("objet racine modules_json");
    if !root_obj.get("modules").is_some_and(serde_json::Value::is_object) {
        root_obj.insert("modules".to_owned(), json!({}));
    }
    let mods_map = root_obj
        .get_mut("modules")
        .and_then(serde_json::Value::as_object_mut)
        .expect("map modules");
    if !mods_map.get(mod_id).is_some_and(serde_json::Value::is_object) {
        mods_map.insert(mod_id.to_owned(), json!({}));
    }
    let target = mods_map
        .get_mut(mod_id)
        .and_then(serde_json::Value::as_object_mut)
        .expect("objet module");

    if let Some(inc_obj) = incoming.as_object() {
        for (k, v) in inc_obj {
            if k == "settings" && v.is_object() {
                if replace_settings || !target.get("settings").is_some_and(serde_json::Value::is_object) {
                    target.insert("settings".to_owned(), v.clone());
                } else if let (Some(t_set), Some(v_set)) = (
                    target.get_mut("settings").and_then(serde_json::Value::as_object_mut),
                    v.as_object(),
                ) {
                    for (sk, sv) in v_set {
                        t_set.insert(sk.clone(), sv.clone());
                    }
                }
            } else {
                target.insert(k.clone(), v.clone());
            }
        }
    }
    serde_json::Value::Object(target.clone())
}

struct Controller {
    prefs: LauncherPrefs,
    settings: PureClientSettings,
    modules_json: serde_json::Value,
    account: Option<MicrosoftAccount>,
    accounts: Vec<PublicAccountView>,
    active_custom_profile_id: Option<String>,
    custom_profiles: Vec<CustomProfileSummary>,
    installed_mods: Vec<InstalledModEntry>,
    builtin_servers: Vec<QuickServerEntry>,
    java_status_label: String,
    java_needs_upgrade: bool,
    java_installing: bool,
    game_running: bool,
    prelaunch_syncing: bool,
    hidden_in_tray: bool,
    curtain_id: u64,
    curtain_active: bool,
    plunge_darkness_reached: bool,
    mc_menu_ready: bool,
    handoff_dispatched: bool,
    active_challenge: Option<DeviceCodeChallenge>,
    auth_cancel: Arc<AtomicBool>,
    update_view: UiUpdateView,
    pending_update: Option<PendingUpdate>,
    startup_update_checked: bool,
    deferred_logs: VecDeque<String>,
}

impl Controller {
    fn new() -> Self {
        let mut prefs = load_launcher_prefs();
        if prefs.instance_flavor == InstanceFlavor::VulkanBeta {
            let _ = runtimes::ensure_vulkan_instance_initialized(&prefs);
        }
        let active_dir = runtimes::resolve_active_game_dir(&prefs);
        let (settings, modules_json) = load_synced_configs(&active_dir);
        let store = load_account_store();
        let accounts = public_account_views(&store);
        let account = load_microsoft_account();
        if account.is_some() && prefs.launch_mode == LaunchMode::DirectOffline {
            prefs.launch_mode = LaunchMode::DirectMicrosoft;
        }
        let custom_profiles = load_custom_profiles()
            .into_iter()
            .map(|p| CustomProfileSummary {
                id: p.id,
                name: p.name,
            })
            .collect();
        let installed_mods = mods_manager::list_installed_mods(&active_dir);
        let builtin_servers = builtin_quick_servers();

        let java_file = Path::new(&prefs.java_path);
        let probe = runtimes::probe_java8_executable(java_file);
        let java_needs_upgrade =
            !java_file.is_file() || !probe.is_64bit || !probe.is_java8 || probe.update_version < 250;
        let java_status_label = probe.label;

        let update_view = UiUpdateView::initial(&active_dir);
        Self {
            prefs,
            settings,
            modules_json,
            account,
            accounts,
            active_custom_profile_id: None,
            custom_profiles,
            installed_mods,
            builtin_servers,
            java_status_label,
            java_needs_upgrade,
            java_installing: false,
            game_running: false,
            prelaunch_syncing: false,
            hidden_in_tray: false,
            curtain_id: 0,
            curtain_active: false,
            plunge_darkness_reached: false,
            mc_menu_ready: false,
            handoff_dispatched: false,
            active_challenge: None,
            auth_cancel: Arc::new(AtomicBool::new(false)),
            update_view,
            pending_update: None,
            startup_update_checked: false,
            deferred_logs: VecDeque::with_capacity(200),
        }
    }

    fn active_game_dir(&self) -> String {
        runtimes::resolve_active_game_dir(&self.prefs)
    }

    fn reload_active_instance_state(&mut self) {
        if self.prefs.instance_flavor == InstanceFlavor::VulkanBeta {
            let _ = runtimes::ensure_vulkan_instance_initialized(&self.prefs);
            let vk_dir = self.active_game_dir();
            if installed_mod_version(&vk_dir).is_none() {
                let _ = runner::sync_pureclient_jar(&vk_dir);
            }
        }
        let active_dir = self.active_game_dir();
        let (settings, modules_json) = load_synced_configs(&active_dir);
        self.settings = settings;
        self.modules_json = modules_json;
        self.installed_mods = mods_manager::list_installed_mods(&active_dir);
        self.update_view.installed_jar_version =
            installed_mod_version(&active_dir).map(format_semver);
    }

    fn refresh_accounts_cache(&mut self) {
        let store = load_account_store();
        self.accounts = public_account_views(&store);
        self.account = load_microsoft_account();
    }

    fn refresh_custom_profiles_cache(&mut self) {
        self.custom_profiles = load_custom_profiles()
            .into_iter()
            .map(|p| CustomProfileSummary {
                id: p.id,
                name: p.name,
            })
            .collect();
    }

    fn refresh_java_probe(&mut self) {
        let java_file = Path::new(&self.prefs.java_path);
        let probe = runtimes::probe_java8_executable(java_file);
        self.java_needs_upgrade =
            !java_file.is_file() || !probe.is_64bit || !probe.is_java8 || probe.update_version < 250;
        self.java_status_label = probe.label;
    }

    fn pull_newer_watched_configs_and_reload(&mut self) {
        let active_dir_str = self.active_game_dir();
        let primary_dir = Path::new(&active_dir_str);
        let primary_cfg = primary_dir.join("config");
        let watched = collect_watched_189_game_dirs(&self.prefs, &active_dir_str);
        for fname in ["mashclient_modules.json", "purefps.properties"] {
            let dst = primary_cfg.join(fname);
            let dst_mtime = std::fs::metadata(&dst).and_then(|m| m.modified()).ok();
            let mut newest: Option<(std::time::SystemTime, std::path::PathBuf)> = None;
            for dir in &watched {
                if dir == primary_dir {
                    continue;
                }
                let cand = dir.join("config").join(fname);
                let Ok(mt) = std::fs::metadata(&cand).and_then(|m| m.modified()) else {
                    continue;
                };
                if dst_mtime.is_none_or(|dmt| mt > dmt)
                    && newest.as_ref().is_none_or(|(cur_t, _)| mt > *cur_t)
                {
                    newest = Some((mt, cand));
                }
            }
            if let Some((_, src)) = newest {
                let src_bytes = std::fs::read(&src).ok();
                let dst_bytes = std::fs::read(&dst).ok();
                if src_bytes.is_some() && src_bytes != dst_bytes {
                    let _ = std::fs::create_dir_all(&primary_cfg);
                    let _ = std::fs::copy(&src, &dst);
                }
            }
        }
        let (settings, modules_json) = load_synced_configs(&active_dir_str);
        self.settings = settings;
        self.modules_json = modules_json;
        self.installed_mods = mods_manager::list_installed_mods(&active_dir_str);
    }

    fn spawn_update_check(
        &mut self,
        manual: bool,
        webview: &wry::WebView,
        proxy: &EventLoopProxy<AppEvent>,
    ) {
        if self.update_view.status == "checking" || self.update_view.status == "downloading" {
            return;
        }
        self.update_view.status = "checking".to_owned();
        self.update_view.message = format!(
            "Vérification des GitHub Releases sur {}...",
            self.prefs.github_repo
        );
        self.push_full_state(webview);

        let repo = self.prefs.github_repo.clone();
        let game_dir = self.active_game_dir();
        let tok = effective_github_token();
        let p = proxy.clone();
        std::thread::spawn(move || {
            let result = check_for_updates(&repo, tok.as_deref(), &game_dir);
            let _ = p.send_event(AppEvent::UpdateChecked { manual, result });
        });
    }

    fn push_full_state(&self, webview: &wry::WebView) {
        if self.hidden_in_tray {
            return;
        }
        static EMPTY_OBJ: std::sync::OnceLock<serde_json::Value> = std::sync::OnceLock::new();
        let fallback = EMPTY_OBJ.get_or_init(|| json!({}));
        let mods_ref = self.modules_json.get("modules").unwrap_or(fallback);
        let active_dir = self.active_game_dir();
        let snap = UiStateSnapshot {
            settings: &self.settings,
            modules: mods_ref,
            prefs: &self.prefs,
            active_game_dir: &active_dir,
            account: self.account.as_ref().map(PublicAccountView::from),
            accounts: &self.accounts,
            active_custom_profile_id: self.active_custom_profile_id.as_deref(),
            custom_profiles: &self.custom_profiles,
            installed_mods: &self.installed_mods,
            builtin_servers: &self.builtin_servers,
            java_status_label: &self.java_status_label,
            java_needs_upgrade: self.java_needs_upgrade,
            java_installing: self.java_installing,
            game_running: self.game_running,
            device_code: self.active_challenge.as_ref().map(|c| UiDeviceCodeView {
                user_code: c.user_code.clone(),
                verification_uri: c.verification_uri.clone(),
            }),
            update: &self.update_view,
        };
        let msg = json!({
            "kind": "state",
            "data": snap
        });
        if let Ok(serialized) = serde_json::to_string(&msg) {
            let _ = webview.evaluate_script(&format!("window.__purePush({serialized});"));
        }
    }

    fn push_crash(webview: &wry::WebView, report: &CrashReportInfo) {
        let msg = json!({
            "kind": "crash",
            "report": report
        });
        if let Ok(serialized) = serde_json::to_string(&msg) {
            let _ = webview.evaluate_script(&format!("window.__purePush({serialized});"));
        }
    }

    fn push_toast(webview: &wry::WebView, message: &str, is_error: bool) {
        let msg = json!({
            "kind": "toast",
            "message": message,
            "is_error": is_error
        });
        if let Ok(serialized) = serde_json::to_string(&msg) {
            let _ = webview.evaluate_script(&format!("window.__purePush({serialized});"));
        }
    }

    fn append_or_buffer_log(&mut self, webview: &wry::WebView, line: String) {
        if self.hidden_in_tray {
            if self.deferred_logs.len() >= 200 {
                self.deferred_logs.pop_front();
            }
            self.deferred_logs.push_back(line);
        } else {
            Self::push_log(webview, &line);
        }
    }

    fn flush_deferred_logs(&mut self, webview: &wry::WebView) {
        if self.deferred_logs.is_empty() {
            return;
        }
        let mut combined = String::new();
        while let Some(line) = self.deferred_logs.pop_front() {
            if !combined.is_empty() {
                combined.push('\n');
            }
            combined.push_str(&line);
        }
        Self::push_log(webview, &combined);
    }

    fn push_log(webview: &wry::WebView, line: &str) {
        let msg = json!({
            "kind": "log",
            "line": line
        });
        if let Ok(serialized) = serde_json::to_string(&msg) {
            let _ = webview.evaluate_script(&format!("window.__purePush({serialized});"));
        }
    }

    fn push_plunge_command(webview: &wry::WebView, action: &str) {
        let msg = json!({
            "kind": "plunge",
            "action": action
        });
        if let Ok(serialized) = serde_json::to_string(&msg) {
            let _ = webview.evaluate_script(&format!("window.__purePush({serialized});"));
        }
    }

    fn set_webview_suspended(webview: &wry::WebView, suspended: bool) {
        let flag = if suspended { "true" } else { "false" };
        let _ = webview.evaluate_script(&format!(
            "if(window.__mashSetSuspended)window.__mashSetSuspended({flag});"
        ));
    }

    fn enter_singularity_curtain(&mut self, window: &Window, webview: &wry::WebView) {
        self.curtain_id = self.curtain_id.wrapping_add(1);
        self.curtain_active = true;
        self.hidden_in_tray = false;
        self.plunge_darkness_reached = false;
        self.mc_menu_ready = false;
        self.handoff_dispatched = false;

        Self::set_webview_suspended(webview, false);

        // Maintient l'écran noir #000000 au premier plan pendant l'initialisation Forge/PrismLauncher
        // sans appeler ShowCursor(FALSE) Win32 (le masquage visuel du curseur est géré en CSS).
        window.set_minimized(false);
        window.set_visible(true);
        window.set_fullscreen(Some(Fullscreen::Borderless(None)));
        window.set_always_on_top(true);
        window.set_focus();
        Self::push_plunge_command(webview, "start");
    }

    /// Ferme complètement la fenêtre du Launcher du bureau et de la barre des tâches SANS appeler
    /// `window.set_fullscreen(None)` (qui déclenche un `SetWindowPlacement(SW_SHOWNORMAL)` asynchrone
    /// dans `tao` et faisait flasher la fenêtre 1240×780 par-dessus l'écran noir !).
    fn suspend_to_system_tray(
        &mut self,
        window: &Window,
        webview: &wry::WebView,
        tray: &SystemTray,
    ) {
        self.curtain_id = self.curtain_id.wrapping_add(1);
        self.curtain_active = false;
        self.hidden_in_tray = true;
        self.plunge_darkness_reached = false;
        self.mc_menu_ready = false;
        self.handoff_dispatched = false;

        Self::set_webview_suspended(webview, true);
        window.set_always_on_top(false);
        win32_hide_window_to_tray(window);
        tray.set_game_running_mode(true);
    }

    /// Masque MashClient directement dans la zone de notification en bas à droite (System Tray)
    /// dès que la fenêtre Borderless OpenGL #000000 de Minecraft a pris sa place à (0, 0).
    fn dismiss_curtain_for_game(
        &mut self,
        window: &Window,
        webview: &wry::WebView,
        tray: &SystemTray,
    ) {
        if !self.curtain_active {
            return;
        }
        self.suspend_to_system_tray(window, webview, tray);
    }

    fn restore_normal_window(
        &mut self,
        window: &Window,
        webview: &wry::WebView,
        tray: &SystemTray,
    ) {
        self.curtain_id = self.curtain_id.wrapping_add(1);
        self.curtain_active = false;
        self.hidden_in_tray = false;
        self.plunge_darkness_reached = false;
        self.mc_menu_ready = false;
        self.handoff_dispatched = false;

        Self::set_webview_suspended(webview, false);
        Self::push_plunge_command(webview, "reset");
        self.push_full_state(webview);
        self.flush_deferred_logs(webview);
        tray.set_game_running_mode(self.game_running);

        window.set_always_on_top(false);
        window.set_cursor_visible(true);
        win32_restore_window_from_tray(window);
    }

    fn try_schedule_seamless_handoff(
        &mut self,
        window: &Window,
        proxy: &EventLoopProxy<AppEvent>,
    ) {
        if self.curtain_active
            && self.plunge_darkness_reached
            && self.mc_menu_ready
            && !self.handoff_dispatched
        {
            self.handoff_dispatched = true;
            // Relâche `always_on_top` et place atomiquement la fenêtre OpenGL LWJGL #000000 devant le rideau #000000
            window.set_always_on_top(false);
            allow_any_foreground_window();
            focus_visible_lwjgl_window();

            let expected_id = self.curtain_id;
            let p = proxy.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(60));
                let _ = p.send_event(AppEvent::RevealMinecraftNow(expected_id));
            });
        }
    }
}

fn current_monitor_resolution(window: &Window) -> (u32, u32) {
    if let Some(mon) = window.current_monitor().or_else(|| window.primary_monitor()) {
        let sz = mon.size();
        if sz.width > 0 && sz.height > 0 {
            return (sz.width, sz.height);
        }
    }
    (1920, 1080)
}

fn begin_singularity_launch_now(
    ctrl: &mut Controller,
    window: &Window,
    webview: &wry::WebView,
    tray: &SystemTray,
    proxy: &EventLoopProxy<AppEvent>,
) {
    let active_dir = ctrl.active_game_dir();
    let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
    sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
    let _ = save_launcher_prefs(&ctrl.prefs);

    let (screen_w, screen_h) = current_monitor_resolution(window);

    match ctrl.prefs.launch_mode {
        LaunchMode::PrismBridge => {
            match prepare_prism_bridge_files(&ctrl.prefs, screen_w, screen_h, proxy) {
                Ok((inst_id, watched_dirs)) => {
                    ctrl.enter_singularity_curtain(window, webview);
                    if let Err(e) =
                        spawn_prism_bridge_now(&ctrl.prefs, &inst_id, watched_dirs, proxy)
                    {
                        ctrl.restore_normal_window(window, webview, tray);
                        Controller::push_crash(
                            webview,
                            &CrashReportInfo {
                                title: "Impossible de lancer Minecraft".to_owned(),
                                summary: e.clone(),
                                details: e,
                                exit_code: None,
                                report_path: None,
                                suspected_mod_file: None,
                            },
                        );
                    }
                }
                Err(e) => {
                    Controller::push_crash(
                        webview,
                        &CrashReportInfo {
                            title: "Impossible de préparer le lancement".to_owned(),
                            summary: e.clone(),
                            details: e,
                            exit_code: None,
                            report_path: None,
                            suspected_mod_file: None,
                        },
                    );
                }
            }
        }
        LaunchMode::DirectMicrosoft => {
            let Some(acc) = ctrl.account.clone() else {
                Controller::push_toast(
                    webview,
                    "Connectez d'abord votre compte Microsoft en haut à droite.",
                    true,
                );
                return;
            };
            ctrl.enter_singularity_curtain(window, webview);
            let prefs_clone = ctrl.prefs.clone();
            let p = proxy.clone();
            std::thread::spawn(move || {
                // Double tentative de rafraîchissement OAuth2 Xbox/Minecraft pour éviter toute session expirée sur Hypixel
                let active_acc = match refresh_microsoft_session(&acc) {
                    Ok(fresh) => {
                        let _ = save_microsoft_account(&fresh);
                        fresh
                    }
                    Err(_) => {
                        std::thread::sleep(Duration::from_millis(400));
                        match refresh_microsoft_session(&acc) {
                            Ok(fresh2) => {
                                let _ = save_microsoft_account(&fresh2);
                                fresh2
                            }
                            Err(err_msg) => {
                                let _ = p.send_event(AppEvent::LogLine(format!(
                                    "[MashClient] Avertissement session Microsoft (utilisation du jeton en cache) : {err_msg}"
                                )));
                                acc
                            }
                        }
                    }
                };
                if let Err(e) = run_direct_jvm_blocking(
                    prefs_clone,
                    active_acc.username,
                    active_acc.uuid,
                    active_acc.access_token,
                    "msa".to_owned(),
                    screen_w,
                    screen_h,
                    p.clone(),
                ) {
                    let _ = p.send_event(AppEvent::LaunchAborted(e));
                }
            });
        }
        LaunchMode::DirectOffline => {
            ctrl.enter_singularity_curtain(window, webview);
            let prefs_clone = ctrl.prefs.clone();
            let uname = prefs_clone.offline_username.clone();
            let uuid = offline_uuid_hex(&uname);
            let p = proxy.clone();
            std::thread::spawn(move || {
                if let Err(e) = run_direct_jvm_blocking(
                    prefs_clone,
                    uname,
                    uuid,
                    "0".to_owned(),
                    "legacy".to_owned(),
                    screen_w,
                    screen_h,
                    p.clone(),
                ) {
                    let _ = p.send_event(AppEvent::LaunchAborted(e));
                }
            });
        }
    }
}

fn try_trigger_game_launch(
    ctrl: &mut Controller,
    window: &Window,
    webview: &wry::WebView,
    tray: &SystemTray,
    proxy: &EventLoopProxy<AppEvent>,
) {
    if ctrl.game_running || ctrl.curtain_active || ctrl.prelaunch_syncing {
        Controller::push_toast(webview, "Lancement déjà en cours.", false);
        return;
    }

    if ctrl.prefs.launch_mode == LaunchMode::DirectMicrosoft && ctrl.account.is_none() {
        Controller::push_toast(
            webview,
            "Connectez d'abord votre compte Microsoft en haut à droite.",
            true,
        );
        return;
    }
    if ctrl.prefs.launch_mode == LaunchMode::PrismBridge
        && !Path::new(&ctrl.prefs.prism_exe).is_file()
    {
        let err_msg = format!("PrismLauncher introuvable : {}", ctrl.prefs.prism_exe);
        Controller::push_crash(
            webview,
            &CrashReportInfo {
                title: "PrismLauncher introuvable".to_owned(),
                summary: err_msg.clone(),
                details: format!(
                    "{err_msg}\n\nVérifiez l'installation de PrismLauncher ou passez en mode Lancement Direct (Microsoft / Hors-ligne) dans l'onglet Paramètres."
                ),
                exit_code: None,
                report_path: None,
                suspected_mod_file: None,
            },
        );
        return;
    }
    let needs_java = ctrl.prefs.launch_mode != LaunchMode::PrismBridge
        || ctrl.prefs.instance_flavor == InstanceFlavor::VulkanBeta;
    if needs_java && !Path::new(&ctrl.prefs.java_path).is_file() {
        let err_msg = format!("Java 8 introuvable : {}", ctrl.prefs.java_path);
        Controller::push_crash(
            webview,
            &CrashReportInfo {
                title: "Java 8 introuvable".to_owned(),
                summary: err_msg.clone(),
                details: format!(
                    "{err_msg}\n\nOuvrez l'onglet Paramètres et cliquez sur « Installer Java 8 récent (Temurin) » en 1 clic."
                ),
                exit_code: None,
                report_path: None,
                suspected_mod_file: None,
            },
        );
        return;
    }

    let active_dir = if ctrl.prefs.instance_flavor == InstanceFlavor::VulkanBeta {
        match runtimes::ensure_vulkan_instance_initialized(&ctrl.prefs) {
            Ok(d) => d,
            Err(e) => {
                Controller::push_toast(webview, &e, true);
                return;
            }
        }
    } else {
        ctrl.prefs.game_dir.clone()
    };

    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((4, 5, 0));
    let mod_up_to_date =
        installed_mod_version(&active_dir).is_some_and(|cur| cur >= launcher_ver);

    if !mod_up_to_date {
        ctrl.prelaunch_syncing = true;
        Controller::push_toast(webview, "Préparation du moteur MashClient...", false);
        let prefs_clone = ctrl.prefs.clone();
        let p = proxy.clone();
        std::thread::spawn(move || {
            let _ = runner::sync_pureclient_jar(&active_dir);
            auto_fetch_mod_jar_if_needed(&prefs_clone, &active_dir, &p);
            let _ = p.send_event(AppEvent::PreLaunchModSynced);
        });
    } else {
        begin_singularity_launch_now(ctrl, window, webview, tray, proxy);
    }
}

fn apply_hud_positions_map(
    modules_json: &mut serde_json::Value,
    positions: &std::collections::BTreeMap<String, [f32; 2]>,
) {
    if !modules_json.is_object() {
        *modules_json = json!({ "client": "MashClient", "version": "4.5.1", "modules": {} });
    }
    let root_obj = modules_json.as_object_mut().expect("objet racine modules_json");
    if !root_obj.get("modules").is_some_and(serde_json::Value::is_object) {
        root_obj.insert("modules".to_owned(), json!({}));
    }
    let Some(mods_map) = root_obj
        .get_mut("modules")
        .and_then(serde_json::Value::as_object_mut)
    else {
        return;
    };

    for (mod_id, [x, y]) in positions {
        if !mods_map.get(mod_id).is_some_and(serde_json::Value::is_object) {
            mods_map.insert(mod_id.clone(), json!({}));
        }
        if let Some(m) = mods_map
            .get_mut(mod_id)
            .and_then(serde_json::Value::as_object_mut)
        {
            let rx = (*x * 10.0).round() / 10.0;
            let ry = (*y * 10.0).round() / 10.0;
            m.insert("x".to_owned(), json!(rx));
            m.insert("y".to_owned(), json!(ry));
        }
    }
}

fn handle_ipc(
    cmd: IpcCommand,
    ctrl: &mut Controller,
    window: &Window,
    webview: &wry::WebView,
    tray: &SystemTray,
    single_instance: &mut SingleInstanceGuard,
    proxy: &EventLoopProxy<AppEvent>,
) -> bool {
    match cmd {
        IpcCommand::UiReady => {
            ctrl.push_full_state(webview);
            if !ctrl.startup_update_checked {
                ctrl.startup_update_checked = true;
                if ctrl.prefs.auto_update_check {
                    ctrl.spawn_update_check(false, webview, proxy);
                }
            }
        }
        IpcCommand::DragWindow => {
            if !ctrl.curtain_active {
                let _ = window.drag_window();
            }
        }
        IpcCommand::Minimize => {
            if ctrl.game_running {
                ctrl.suspend_to_system_tray(window, webview, tray);
            } else {
                window.set_minimized(true);
            }
        }
        IpcCommand::MinimizeToTray => {
            ctrl.suspend_to_system_tray(window, webview, tray);
        }
        IpcCommand::ToggleMaximize => {
            if !ctrl.curtain_active {
                window.set_maximized(!window.is_maximized());
            }
        }
        IpcCommand::Close => {
            ctrl.auth_cancel.store(true, Ordering::Relaxed);
            return true;
        }
        IpcCommand::SetInstanceFlavor { flavor } => {
            if ctrl.prefs.instance_flavor != flavor {
                ctrl.prefs.instance_flavor = flavor;
                let _ = save_launcher_prefs(&ctrl.prefs);
                ctrl.reload_active_instance_state();
                let label = match flavor {
                    InstanceFlavor::Standard => "Instance 1.8.9 PureFPS active.",
                    InstanceFlavor::VulkanBeta => "Instance 1.8.9 PureFPS Vulkan (Bêta) active.",
                };
                Controller::push_toast(webview, label, false);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::SetProfile { profile } => {
            let target = PureProfile::from_id(&profile);
            ctrl.settings.apply_profile(target);
            ctrl.active_custom_profile_id = None;
            sync_pure_settings_to_modules_json(&ctrl.settings, &mut ctrl.modules_json);
            let active_dir = ctrl.active_game_dir();
            if let Err(e) =
                save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json)
            {
                Controller::push_toast(webview, &e, true);
            } else {
                sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
                Controller::push_toast(
                    webview,
                    &format!("Profil '{}' synchronisé.", target.id().to_uppercase()),
                    false,
                );
            }
            ctrl.push_full_state(webview);
        }
        IpcCommand::SaveCustomProfile { name } => {
            match save_custom_profile_entry(&name, &ctrl.settings, &ctrl.modules_json) {
                Ok(saved) => {
                    ctrl.settings.active_profile = PureProfile::Custom;
                    ctrl.active_custom_profile_id = Some(saved.id.clone());
                    ctrl.refresh_custom_profiles_cache();
                    let active_dir = ctrl.active_game_dir();
                    let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
                    sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
                    Controller::push_toast(
                        webview,
                        &format!("Profil '{}' enregistré.", saved.name),
                        false,
                    );
                    ctrl.push_full_state(webview);
                }
                Err(e) => Controller::push_toast(webview, &e, true),
            }
        }
        IpcCommand::ApplyCustomProfile { id } => {
            if let Some(found) = load_custom_profiles().into_iter().find(|p| p.id == id) {
                ctrl.settings = found.settings;
                ctrl.settings.active_profile = PureProfile::Custom;
                ctrl.modules_json = found.modules;
                ctrl.active_custom_profile_id = Some(found.id);
                let active_dir = ctrl.active_game_dir();
                let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
                sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
                Controller::push_toast(
                    webview,
                    &format!("Profil '{}' appliqué.", found.name),
                    false,
                );
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::DeleteCustomProfile { id } => {
            if let Err(e) = delete_custom_profile_entry(&id) {
                Controller::push_toast(webview, &e, true);
            } else {
                if ctrl.active_custom_profile_id.as_deref() == Some(id.as_str()) {
                    ctrl.active_custom_profile_id = None;
                }
                ctrl.refresh_custom_profiles_cache();
                Controller::push_toast(webview, "Profil personnalisé supprimé.", false);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::ExportProfileJson { id } => {
            let (prof_name, prof_settings, prof_modules) = if let Some(target_id) = id {
                if let Some(p) = load_custom_profiles().into_iter().find(|x| x.id == target_id) {
                    (p.name, p.settings, p.modules)
                } else {
                    (
                        "MashClient_Profil".to_owned(),
                        ctrl.settings.clone(),
                        ctrl.modules_json.clone(),
                    )
                }
            } else {
                let current_name = ctrl
                    .active_custom_profile_id
                    .as_ref()
                    .and_then(|cid| {
                        ctrl.custom_profiles
                            .iter()
                            .find(|p| &p.id == cid)
                            .map(|p| p.name.clone())
                    })
                    .unwrap_or_else(|| format!("MashClient_{}", ctrl.settings.active_profile.id()));
                (
                    current_name,
                    ctrl.settings.clone(),
                    ctrl.modules_json.clone(),
                )
            };
            let suggested_file = format!(
                "{}.json",
                prof_name
                    .chars()
                    .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                        c
                    } else {
                        '_'
                    })
                    .collect::<String>()
            );
            let p = proxy.clone();
            std::thread::spawn(move || {
                if let Some(dest) = dialog::pick_save_json_dialog(&suggested_file) {
                    match export_profile_bundle_to_path(&dest, &prof_name, &prof_settings, &prof_modules) {
                        Ok(()) => {
                            let _ = p.send_event(AppEvent::Toast {
                                message: format!("Profil exporté : {}", dest.display()),
                                is_error: false,
                            });
                        }
                        Err(e) => {
                            let _ = p.send_event(AppEvent::Toast {
                                message: e,
                                is_error: true,
                            });
                        }
                    }
                }
            });
        }
        IpcCommand::ImportProfileJson => {
            let p = proxy.clone();
            std::thread::spawn(move || {
                if let Some(src) = dialog::pick_open_json_dialog() {
                    let res = import_profile_bundle_from_path(&src);
                    let _ = p.send_event(AppEvent::ProfileImported(res));
                }
            });
        }
        IpcCommand::SetSetting { key, value } => {
            if ctrl.settings.set_field(&key, &value) {
                if ctrl.settings.active_profile == PureProfile::Custom {
                    ctrl.active_custom_profile_id = None;
                }
                sync_pure_settings_to_modules_json(&ctrl.settings, &mut ctrl.modules_json);
                let active_dir = ctrl.active_game_dir();
                let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
                sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::SaveModuleEntry {
            mod_id,
            entry,
            setting_key,
        } => {
            let merged = merge_module_entry(&mut ctrl.modules_json, &mod_id, &entry, false);
            apply_module_entry_to_pure_settings(&mod_id, &merged, &mut ctrl.settings);
            if let (Some(sk), Some(en)) = (setting_key.as_deref(), merged.get("enabled")) {
                let _ = ctrl.settings.set_field(sk, en);
            }
            let active_dir = ctrl.active_game_dir();
            let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
            sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
            ctrl.push_full_state(webview);
        }
        IpcCommand::ResetModuleEntry {
            mod_id,
            default_entry,
            setting_key,
        } => {
            let merged = merge_module_entry(&mut ctrl.modules_json, &mod_id, &default_entry, true);
            apply_module_entry_to_pure_settings(&mod_id, &merged, &mut ctrl.settings);
            if let (Some(sk), Some(en)) = (setting_key.as_deref(), merged.get("enabled")) {
                let _ = ctrl.settings.set_field(sk, en);
            }
            let active_dir = ctrl.active_game_dir();
            let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
            sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
            Controller::push_toast(webview, "Réglages du module réinitialisés.", false);
            ctrl.push_full_state(webview);
        }
        IpcCommand::SaveHudLayout { positions } => {
            apply_hud_positions_map(&mut ctrl.modules_json, &positions);
            let active_dir = ctrl.active_game_dir();
            let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
            sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
            ctrl.push_full_state(webview);
        }
        IpcCommand::ResetHudLayout { defaults } => {
            apply_hud_positions_map(&mut ctrl.modules_json, &defaults);
            let active_dir = ctrl.active_game_dir();
            let _ = save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
            sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
            Controller::push_toast(webview, "Positions du HUD réinitialisées.", false);
            ctrl.push_full_state(webview);
        }
        IpcCommand::RefreshModsList => {
            ctrl.installed_mods = mods_manager::list_installed_mods(&ctrl.active_game_dir());
            ctrl.push_full_state(webview);
        }
        IpcCommand::ImportModsDialog => {
            let active_dir = ctrl.active_game_dir();
            let p = proxy.clone();
            std::thread::spawn(move || {
                let picked = dialog::pick_jar_files_dialog();
                if !picked.is_empty() {
                    let res = mods_manager::import_mod_jars(&active_dir, &picked);
                    let _ = p.send_event(AppEvent::ModsImported(res));
                }
            });
        }
        IpcCommand::ToggleThirdPartyMod { file_name } => {
            let active_dir = ctrl.active_game_dir();
            match mods_manager::toggle_mod_file(&active_dir, &file_name) {
                Ok((_, now_enabled)) => {
                    ctrl.installed_mods = mods_manager::list_installed_mods(&active_dir);
                    Controller::push_toast(
                        webview,
                        if now_enabled {
                            "Mod activé."
                        } else {
                            "Mod désactivé."
                        },
                        false,
                    );
                    ctrl.push_full_state(webview);
                }
                Err(e) => Controller::push_toast(webview, &e, true),
            }
        }
        IpcCommand::DeleteThirdPartyMod { file_name } => {
            let active_dir = ctrl.active_game_dir();
            match mods_manager::delete_mod_file(&active_dir, &file_name) {
                Ok(()) => {
                    ctrl.installed_mods = mods_manager::list_installed_mods(&active_dir);
                    Controller::push_toast(webview, "Mod supprimé du dossier mods/.", false);
                    ctrl.push_full_state(webview);
                }
                Err(e) => Controller::push_toast(webview, &e, true),
            }
        }
        IpcCommand::OpenCrashReport { path } => {
            let p = Path::new(&path);
            if p.is_file() {
                if Command::new("notepad.exe").arg(p).spawn().is_err() {
                    let _ = Command::new("explorer.exe")
                        .arg(format!("/select,{}", p.display()))
                        .spawn();
                }
            }
        }
        IpcCommand::SetRam { ram_mb } => {
            ctrl.prefs.ram_mb = ram_mb.clamp(2048, 65536);
            let _ = save_launcher_prefs(&ctrl.prefs);
        }
        IpcCommand::SetOfflineName { name } => {
            let clean = name.trim();
            if !clean.is_empty() {
                ctrl.prefs.offline_username = clean.chars().take(16).collect();
                let _ = save_launcher_prefs(&ctrl.prefs);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::SetJavaPath { path } => {
            let clean = path.trim();
            if !clean.is_empty() {
                ctrl.prefs.java_path = clean.to_owned();
                ctrl.refresh_java_probe();
                let _ = save_launcher_prefs(&ctrl.prefs);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::InstallTemurinJava8 => {
            if ctrl.java_installing {
                return false;
            }
            ctrl.java_installing = true;
            ctrl.push_full_state(webview);
            Controller::push_toast(
                webview,
                "Téléchargement d'Eclipse Temurin Java 8 64-bit...",
                false,
            );
            let p = proxy.clone();
            std::thread::spawn(move || {
                let res = runtimes::install_temurin_jre8(&p);
                let _ = p.send_event(AppEvent::TemurinInstalled(res));
            });
        }
        IpcCommand::SetGameDir { path } => {
            let clean = path.trim();
            if !clean.is_empty() {
                ctrl.prefs.game_dir = clean.to_owned();
                let p = Path::new(clean);
                if p.join("libraries").is_dir() && p.join("assets").is_dir() {
                    ctrl.prefs.libraries_dir = p.join("libraries").to_string_lossy().to_string();
                    ctrl.prefs.assets_dir = p.join("assets").to_string_lossy().to_string();
                } else if let Some(prism_root) =
                    p.parent().and_then(|i| i.parent()).and_then(|r| r.parent())
                {
                    if prism_root.join("libraries").is_dir() {
                        ctrl.prefs.libraries_dir =
                            prism_root.join("libraries").to_string_lossy().to_string();
                        ctrl.prefs.assets_dir =
                            prism_root.join("assets").to_string_lossy().to_string();
                    }
                }
                ctrl.reload_active_instance_state();
                let _ = save_launcher_prefs(&ctrl.prefs);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::SetLaunchMode { mode } => {
            ctrl.prefs.launch_mode = mode;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::ToggleQuickHypixel => {
            if ctrl.prefs.selected_server.as_ref().is_some_and(|s| s.id == "hypixel") {
                ctrl.prefs.selected_server = None;
            } else {
                ctrl.prefs.selected_server = builtin_quick_servers().into_iter().next();
            }
            ctrl.prefs.quick_join_hypixel = false;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::SelectQuickServer { server } => {
            ctrl.prefs.selected_server = server.filter(|s| !s.address.trim().is_empty());
            ctrl.prefs.quick_join_hypixel = false;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::QuickConnectServer { id, name, address } => {
            let clean_addr = address.trim();
            if clean_addr.is_empty() {
                return false;
            }
            ctrl.prefs.selected_server = Some(QuickServerEntry {
                id,
                name: if name.trim().is_empty() {
                    clean_addr.to_owned()
                } else {
                    name.trim().to_owned()
                },
                address: clean_addr.to_owned(),
            });
            ctrl.prefs.quick_join_hypixel = false;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
            try_trigger_game_launch(ctrl, window, webview, tray, proxy);
        }
        IpcCommand::AddCustomServer { name, address } => {
            let clean_addr = address
                .trim()
                .trim_start_matches("http://")
                .trim_start_matches("https://")
                .trim_end_matches('/')
                .to_owned();
            if clean_addr.is_empty() {
                Controller::push_toast(webview, "Adresse IP du serveur requise.", true);
                return false;
            }
            let clean_name = if name.trim().is_empty() {
                clean_addr.clone()
            } else {
                name.trim().to_owned()
            };
            let id = format!(
                "srv_{}_{}",
                clean_addr
                    .chars()
                    .filter(|c| c.is_ascii_alphanumeric())
                    .take(12)
                    .collect::<String>()
                    .to_ascii_lowercase(),
                ctrl.prefs.custom_servers.len() + 1
            );
            let entry = QuickServerEntry {
                id,
                name: clean_name.clone(),
                address: clean_addr,
            };
            ctrl.prefs.custom_servers.push(entry.clone());
            ctrl.prefs.selected_server = Some(entry);
            let _ = save_launcher_prefs(&ctrl.prefs);
            Controller::push_toast(webview, &format!("Serveur '{}' ajouté.", clean_name), false);
            ctrl.push_full_state(webview);
        }
        IpcCommand::RemoveCustomServer { id } => {
            ctrl.prefs.custom_servers.retain(|s| s.id != id);
            if ctrl.prefs.selected_server.as_ref().is_some_and(|s| s.id == id) {
                ctrl.prefs.selected_server = None;
            }
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::StartMsLogin => {
            ctrl.auth_cancel.store(true, Ordering::Relaxed);
            ctrl.auth_cancel = Arc::new(AtomicBool::new(false));
            let p = proxy.clone();
            Controller::push_toast(
                webview,
                "Génération du code de connexion Microsoft sécurisé...",
                false,
            );
            std::thread::spawn(move || {
                let res = start_device_code_flow();
                let _ = p.send_event(AppEvent::DeviceCodeStarted(res));
            });
        }
        IpcCommand::CancelMsLogin => {
            ctrl.auth_cancel.store(true, Ordering::Relaxed);
            ctrl.active_challenge = None;
            ctrl.push_full_state(webview);
        }
        IpcCommand::OpenMsLink => {
            let url = ctrl
                .active_challenge
                .as_ref()
                .map(|c| c.verification_uri.as_str())
                .unwrap_or("https://www.microsoft.com/link");
            open_verification_browser(url);
        }
        IpcCommand::CopyCode { code } => {
            copy_to_windows_clipboard(&code);
        }
        IpcCommand::RefreshMs => {
            if let Some(existing) = ctrl.account.clone() {
                let p = proxy.clone();
                Controller::push_toast(
                    webview,
                    "Rafraîchissement de la session Microsoft...",
                    false,
                );
                std::thread::spawn(move || {
                    let res = refresh_microsoft_session(&existing);
                    let _ = p.send_event(AppEvent::AuthCompleted(res));
                });
            }
        }
        IpcCommand::SwitchMsAccount { uuid } => match switch_microsoft_account(&uuid) {
            Ok(switched) => {
                let uname = switched.username.clone();
                ctrl.refresh_accounts_cache();
                if ctrl.prefs.launch_mode == LaunchMode::DirectOffline {
                    ctrl.prefs.launch_mode = LaunchMode::DirectMicrosoft;
                    let _ = save_launcher_prefs(&ctrl.prefs);
                }
                Controller::push_toast(
                    webview,
                    &format!("Compte Microsoft actif : {uname}"),
                    false,
                );
                ctrl.push_full_state(webview);
            }
            Err(e) => Controller::push_toast(webview, &e, true),
        },
        IpcCommand::RemoveMsAccount { uuid } => match remove_microsoft_account(&uuid) {
            Ok(_) => {
                ctrl.refresh_accounts_cache();
                if ctrl.account.is_none() && ctrl.prefs.launch_mode == LaunchMode::DirectMicrosoft {
                    ctrl.prefs.launch_mode = LaunchMode::PrismBridge;
                    let _ = save_launcher_prefs(&ctrl.prefs);
                }
                Controller::push_toast(webview, "Compte Microsoft retiré.", false);
                ctrl.push_full_state(webview);
            }
            Err(e) => Controller::push_toast(webview, &e, true),
        },
        IpcCommand::LogoutMs => {
            clear_microsoft_account();
            ctrl.refresh_accounts_cache();
            if ctrl.account.is_none() && ctrl.prefs.launch_mode == LaunchMode::DirectMicrosoft {
                ctrl.prefs.launch_mode = LaunchMode::PrismBridge;
                let _ = save_launcher_prefs(&ctrl.prefs);
            }
            Controller::push_toast(webview, "Compte Microsoft déconnecté.", false);
            ctrl.push_full_state(webview);
        }
        IpcCommand::OpenModsFolder => {
            let mods_dir = Path::new(&ctrl.active_game_dir()).join("mods");
            let _ = std::fs::create_dir_all(&mods_dir);
            let _ = Command::new("explorer.exe").arg(mods_dir).spawn();
        }
        IpcCommand::CheckUpdates { manual } => {
            ctrl.spawn_update_check(manual, webview, proxy);
        }
        IpcCommand::InstallUpdate => {
            if ctrl.update_view.status == "downloading" {
                return false;
            }
            let Some(plan) = ctrl.pending_update.clone() else {
                Controller::push_toast(webview, "Aucune mise à jour en attente.", false);
                return false;
            };
            ctrl.update_view.status = "downloading".to_owned();
            ctrl.update_view.download_percent = 0;
            ctrl.update_view.message = format!("Téléchargement de MashClient v{}...", plan.version);
            ctrl.push_full_state(webview);

            let tok = effective_github_token();
            let game_dir = ctrl.active_game_dir();
            let p = proxy.clone();
            std::thread::spawn(move || {
                let res = download_and_install_update(&plan, tok.as_deref(), &game_dir, &p);
                let _ = p.send_event(AppEvent::UpdateInstalled(res));
            });
        }
        IpcCommand::RestartLauncher => {
            single_instance.release();
            match restart_launcher_process() {
                Ok(()) => {
                    ctrl.auth_cancel.store(true, Ordering::Relaxed);
                    return true;
                }
                Err(e) => {
                    if let Some(reacquired) = SingleInstanceGuard::acquire(false) {
                        *single_instance = reacquired;
                    }
                    Controller::push_toast(webview, &e, true);
                }
            }
        }
        IpcCommand::SetAutoUpdate { enabled } => {
            ctrl.prefs.auto_update_check = enabled;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::SetGithubRepo { repo } => match sanitize_repo(&repo) {
            Ok(clean_repo) => {
                ctrl.prefs.github_repo = clean_repo;
                let _ = save_launcher_prefs(&ctrl.prefs);
                ctrl.push_full_state(webview);
                ctrl.spawn_update_check(true, webview, proxy);
            }
            Err(e) => {
                Controller::push_toast(webview, &e, true);
            }
        },
        IpcCommand::PlungeDarknessReached => {
            ctrl.plunge_darkness_reached = true;
            ctrl.try_schedule_seamless_handoff(window, proxy);

            // Garde-fou matériel large (28s) : laisse le temps à Forge 1.8.9 d'ouvrir sa fenêtre OpenGL
            // tout en garantissant que l'écran noir #000000 ne reste jamais bloqué si le lancement échoue
            let expected_id = ctrl.curtain_id;
            let p_safety = proxy.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(28_000));
                let _ = p_safety.send_event(AppEvent::CurtainSafetyTimeout(expected_id));
            });
        }
        IpcCommand::AbortLaunchCurtain => {
            ctrl.restore_normal_window(window, webview, tray);
        }
        IpcCommand::LaunchGame => {
            try_trigger_game_launch(ctrl, window, webview, tray, proxy);
        }
    }
    false
}

fn main() -> wry::Result<()> {
    let after_update = std::env::args().any(|arg| arg == "--after-update");
    let Some(mut single_instance) = SingleInstanceGuard::acquire(after_update) else {
        // Une instance de MashClient est déjà ouverte en arrière-plan : elle a été réveillée et ramenée au premier plan.
        return Ok(());
    };

    cleanup_old_launcher_binary();

    if std::env::var_os("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS").is_none() {
        std::env::set_var(
            "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
            "--autoplay-policy=no-user-gesture-required",
        );
    }

    let event_loop = EventLoopBuilder::<AppEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let tray = SystemTray::new(proxy.clone());
    let win_icon = Icon::from_rgba(WINDOW_ICON_64_RGBA.to_vec(), 64, 64).ok();

    let window = WindowBuilder::new()
        .with_title("MashClient")
        .with_window_icon(win_icon)
        .with_decorations(false)
        .with_inner_size(Size::Logical(LogicalSize::new(1240.0, 780.0)))
        .with_min_inner_size(Size::Logical(LogicalSize::new(1024.0, 680.0)))
        .build(&event_loop)
        .expect("Création fenêtre MashClient");

    let webview_data_dir = app_storage_dir().join("webview2-cache");
    let _ = std::fs::create_dir_all(&webview_data_dir);
    let mut context = wry::WebContext::new(Some(webview_data_dir));

    let ipc_proxy = proxy.clone();
    let builder = WebViewBuilder::new_with_web_context(&mut context)
        .with_autoplay(true)
        .with_devtools(false)
        .with_background_color((0, 0, 0, 255))
        .with_html(UI_HTML)
        .with_ipc_handler(move |req: wry::http::Request<String>| {
            let _ = ipc_proxy.send_event(AppEvent::IpcRaw(req.body().clone()));
        });
    #[cfg(windows)]
    let builder = builder
        .with_default_context_menus(false)
        .with_browser_accelerator_keys(false);
    let webview = builder.build(&window)?;

    let mut ctrl = Controller::new();

    event_loop.run(move |event, _, control_flow| {
        // Maintient `tray` et `single_instance` en vie pendant toute l'exécution de l'application
        let _keep_tray_alive = &tray;
        let _keep_single_instance = &single_instance;
        *control_flow = ControlFlow::Wait;

        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                ctrl.auth_cancel.store(true, Ordering::Relaxed);
                *control_flow = ControlFlow::Exit;
            }
            Event::WindowEvent {
                event: WindowEvent::Focused(false),
                ..
            } => {
                if ctrl.curtain_active && user_pressing_task_switch_keys() {
                    window.set_always_on_top(false);
                }
            }
            Event::UserEvent(app_evt) => match app_evt {
                AppEvent::IpcRaw(raw) => {
                    if let Ok(cmd) = serde_json::from_str::<IpcCommand>(&raw) {
                        if handle_ipc(
                            cmd,
                            &mut ctrl,
                            &window,
                            &webview,
                            &tray,
                            &mut single_instance,
                            &proxy,
                        ) {
                            *control_flow = ControlFlow::Exit;
                        }
                    }
                }
                AppEvent::PreLaunchModSynced => {
                    ctrl.prelaunch_syncing = false;
                    let active_dir = ctrl.active_game_dir();
                    ctrl.update_view.installed_jar_version =
                        installed_mod_version(&active_dir).map(format_semver);
                    ctrl.installed_mods = mods_manager::list_installed_mods(&active_dir);
                    ctrl.push_full_state(&webview);
                    begin_singularity_launch_now(&mut ctrl, &window, &webview, &tray, &proxy);
                }
                AppEvent::TemurinInstalled(res) => {
                    ctrl.java_installing = false;
                    match res {
                        Ok((javaw_path, label)) => {
                            ctrl.prefs.java_path = javaw_path;
                            ctrl.refresh_java_probe();
                            let _ = save_launcher_prefs(&ctrl.prefs);
                            Controller::push_toast(
                                &webview,
                                &format!("{label} configuré automatiquement."),
                                false,
                            );
                        }
                        Err(e) => {
                            Controller::push_toast(&webview, &e, true);
                        }
                    }
                    ctrl.push_full_state(&webview);
                }
                AppEvent::ModsImported(res) => match res {
                    Ok(count) => {
                        ctrl.installed_mods =
                            mods_manager::list_installed_mods(&ctrl.active_game_dir());
                        if count > 0 {
                            Controller::push_toast(
                                &webview,
                                &format!("{count} mod(s) .jar importé(s)."),
                                false,
                            );
                        }
                        ctrl.push_full_state(&webview);
                    }
                    Err(e) => {
                        Controller::push_toast(&webview, &e, true);
                    }
                },
                AppEvent::ProfileImported(res) => match res {
                    Ok(imported) => {
                        ctrl.settings = imported.settings;
                        ctrl.settings.active_profile = PureProfile::Custom;
                        ctrl.modules_json = imported.modules;
                        ctrl.active_custom_profile_id = Some(imported.id);
                        ctrl.refresh_custom_profiles_cache();
                        let active_dir = ctrl.active_game_dir();
                        let _ =
                            save_all_client_configs(&active_dir, &ctrl.settings, &ctrl.modules_json);
                        sync_configs_across_watched_189_dirs(&ctrl.prefs, &active_dir);
                        Controller::push_toast(
                            &webview,
                            &format!("Profil '{}' importé et activé.", imported.name),
                            false,
                        );
                        ctrl.push_full_state(&webview);
                    }
                    Err(e) => {
                        Controller::push_toast(&webview, &e, true);
                    }
                },
                AppEvent::DeviceCodeStarted(res) => match res {
                    Ok(challenge) => {
                        copy_to_windows_clipboard(&challenge.user_code);
                        open_verification_browser(&challenge.verification_uri);
                        ctrl.active_challenge = Some(challenge.clone());
                        ctrl.push_full_state(&webview);

                        let cancel = Arc::clone(&ctrl.auth_cancel);
                        let p = proxy.clone();
                        std::thread::spawn(move || {
                            let poll_res = poll_device_code_until_complete(&challenge, &cancel);
                            let _ = p.send_event(AppEvent::AuthCompleted(poll_res));
                        });
                    }
                    Err(e) => {
                        Controller::push_toast(&webview, &e, true);
                    }
                },
                AppEvent::AuthCompleted(res) => match res {
                    Ok(acc) => {
                        let _ = save_microsoft_account(&acc);
                        let uname = acc.username.clone();
                        ctrl.refresh_accounts_cache();
                        ctrl.active_challenge = None;
                        ctrl.prefs.launch_mode = LaunchMode::DirectMicrosoft;
                        let _ = save_launcher_prefs(&ctrl.prefs);
                        ctrl.push_full_state(&webview);
                        Controller::push_toast(
                            &webview,
                            &format!("Connecté à Microsoft : {uname}"),
                            false,
                        );
                    }
                    Err(e) => {
                        ctrl.active_challenge = None;
                        ctrl.push_full_state(&webview);
                        if !e.contains("annulée") {
                            Controller::push_toast(&webview, &e, true);
                        }
                    }
                },
                AppEvent::UpdateChecked { manual, result } => {
                    match result {
                        Ok(UpdateCheckOutcome::UpToDate {
                            latest_version,
                            installed_jar,
                        }) => {
                            ctrl.pending_update = None;
                            ctrl.update_view.status = "up_to_date".to_owned();
                            ctrl.update_view.latest_version = Some(latest_version.clone());
                            ctrl.update_view.installed_jar_version = installed_jar;
                            ctrl.update_view.has_exe_update = false;
                            ctrl.update_view.has_jar_update = false;
                            ctrl.update_view.download_percent = 0;
                            ctrl.update_view.message =
                                format!("MashClient est à jour (v{latest_version}).");
                            Controller::push_log(
                                &webview,
                                &format!(
                                    "[Auto-Updater] À jour sur GitHub Releases (v{latest_version})."
                                ),
                            );
                            if manual {
                                Controller::push_toast(
                                    &webview,
                                    &format!("MashClient v{latest_version} est déjà à jour."),
                                    false,
                                );
                            }
                        }
                        Ok(UpdateCheckOutcome::NoReleaseYet { repo }) => {
                            ctrl.pending_update = None;
                            ctrl.update_view.status = "no_release".to_owned();
                            ctrl.update_view.latest_version = None;
                            ctrl.update_view.has_exe_update = false;
                            ctrl.update_view.has_jar_update = false;
                            ctrl.update_view.download_percent = 0;
                            ctrl.update_view.message = format!(
                                "Aucune GitHub Release publiée sur {repo} pour l'instant."
                            );
                            Controller::push_log(
                                &webview,
                                &format!(
                                    "[Auto-Updater] Dépôt {repo} joint (aucune Release publiée pour l'instant)."
                                ),
                            );
                            if manual {
                                Controller::push_toast(
                                    &webview,
                                    &format!(
                                        "Dépôt {repo} joint : aucune Release publiée pour l'instant."
                                    ),
                                    false,
                                );
                            }
                        }
                        Ok(UpdateCheckOutcome::Available(plan)) => {
                            let ver = plan.version.clone();
                            ctrl.update_view.status = "available".to_owned();
                            ctrl.update_view.latest_version = Some(ver.clone());
                            ctrl.update_view.release_notes =
                                Some(plan.body.clone()).filter(|s| !s.trim().is_empty());
                            ctrl.update_view.has_exe_update = plan.exe_asset.is_some();
                            ctrl.update_view.has_jar_update = plan.jar_asset.is_some();
                            ctrl.update_view.download_percent = 0;
                            ctrl.update_view.message = format!(
                                "Nouvelle version v{ver} disponible sur GitHub Releases."
                            );
                            ctrl.pending_update = Some(plan);
                            ctrl.append_or_buffer_log(
                                &webview,
                                format!("[Auto-Updater] Mise à jour disponible : v{ver}."),
                            );
                            Controller::push_toast(
                                &webview,
                                &format!("Mise à jour MashClient v{ver} disponible."),
                                false,
                            );
                        }
                        Err(err_msg) => {
                            ctrl.update_view.status = "error".to_owned();
                            ctrl.update_view.message = err_msg.clone();
                            Controller::push_log(
                                &webview,
                                &format!("[Auto-Updater] Erreur : {err_msg}"),
                            );
                            if manual {
                                Controller::push_toast(&webview, &err_msg, true);
                            }
                        }
                    }
                    ctrl.push_full_state(&webview);
                }
                AppEvent::UpdateProgress { percent, message } => {
                    ctrl.update_view.status = "downloading".to_owned();
                    ctrl.update_view.download_percent = percent;
                    ctrl.update_view.message = message;
                    ctrl.push_full_state(&webview);
                }
                AppEvent::UpdateInstalled(res) => {
                    match res {
                        Ok(outcome) => {
                            ctrl.pending_update = None;
                            ctrl.update_view.download_percent = 100;
                            let active_dir = ctrl.active_game_dir();
                            ctrl.update_view.installed_jar_version =
                                installed_mod_version(&active_dir).map(format_semver);
                            ctrl.installed_mods = mods_manager::list_installed_mods(&active_dir);
                            if let Some(ref jar_name) = outcome.jar_installed {
                                Controller::push_log(
                                    &webview,
                                    &format!(
                                        "[Auto-Updater] Archive {jar_name} déployée dans mods/."
                                    ),
                                );
                            }
                            if outcome.exe_staged_for_restart {
                                ctrl.update_view.status = "ready_restart".to_owned();
                                ctrl.update_view.message = format!(
                                    "Mise à jour v{} prête — relancez MashClient pour activer le nouvel exécutable.",
                                    outcome.version
                                );
                                Controller::push_toast(
                                    &webview,
                                    &format!(
                                        "MashClient v{} installé. Cliquez sur Relancer.",
                                        outcome.version
                                    ),
                                    false,
                                );
                            } else {
                                ctrl.update_view.status = "up_to_date".to_owned();
                                ctrl.update_view.has_jar_update = false;
                                ctrl.update_view.has_exe_update = false;
                                ctrl.update_view.message = format!(
                                    "Mod MashClient v{} installé dans mods/.",
                                    outcome.version
                                );
                                Controller::push_toast(
                                    &webview,
                                    &format!(
                                        "Mod MashClient v{} mis à jour.",
                                        outcome.version
                                    ),
                                    false,
                                );
                            }
                        }
                        Err(err_msg) => {
                            ctrl.update_view.status = "error".to_owned();
                            ctrl.update_view.message = err_msg.clone();
                            Controller::push_log(
                                &webview,
                                &format!("[Auto-Updater] Échec installation : {err_msg}"),
                            );
                            Controller::push_toast(&webview, &err_msg, true);
                        }
                    }
                    ctrl.push_full_state(&webview);
                }
                AppEvent::GameRunning(running) => {
                    ctrl.game_running = running;
                    tray.set_game_running_mode(running);
                    if !running {
                        ctrl.pull_newer_watched_configs_and_reload();
                        if ctrl.curtain_active
                            || ctrl.hidden_in_tray
                            || window.is_minimized()
                            || !window.is_visible()
                        {
                            ctrl.restore_normal_window(&window, &webview, &tray);
                        }
                    }
                    ctrl.push_full_state(&webview);
                }
                AppEvent::GameCrashed(report) => {
                    ctrl.game_running = false;
                    ctrl.pull_newer_watched_configs_and_reload();
                    ctrl.restore_normal_window(&window, &webview, &tray);
                    ctrl.push_full_state(&webview);
                    Controller::push_log(
                        &webview,
                        &format!("[ERREUR] {} : {}", report.title, report.summary),
                    );
                    Controller::push_crash(&webview, &report);
                }
                AppEvent::MinecraftMenuReady => {
                    ctrl.mc_menu_ready = true;
                    ctrl.try_schedule_seamless_handoff(&window, &proxy);
                    if ctrl.curtain_active && !ctrl.handoff_dispatched {
                        let expected_id = ctrl.curtain_id;
                        let p_quick = proxy.clone();
                        std::thread::spawn(move || {
                            std::thread::sleep(Duration::from_millis(3200));
                            let _ = p_quick.send_event(AppEvent::CurtainSafetyTimeout(expected_id));
                        });
                    }
                }
                AppEvent::RevealMinecraftNow(id) => {
                    if ctrl.curtain_active && ctrl.curtain_id == id {
                        ctrl.dismiss_curtain_for_game(&window, &webview, &tray);
                    }
                }
                AppEvent::CurtainSafetyTimeout(id) => {
                    if ctrl.curtain_active && ctrl.curtain_id == id {
                        if ctrl.mc_menu_ready || has_visible_lwjgl_window() {
                            ctrl.plunge_darkness_reached = true;
                            ctrl.mc_menu_ready = true;
                            if !ctrl.handoff_dispatched {
                                ctrl.try_schedule_seamless_handoff(&window, &proxy);
                            } else {
                                ctrl.dismiss_curtain_for_game(&window, &webview, &tray);
                            }
                        } else {
                            ctrl.restore_normal_window(&window, &webview, &tray);
                        }
                    }
                }
                AppEvent::LaunchAborted(err_msg) => {
                    ctrl.game_running = false;
                    ctrl.restore_normal_window(&window, &webview, &tray);
                    ctrl.push_full_state(&webview);
                    Controller::push_log(&webview, &format!("[ERREUR] {err_msg}"));
                    Controller::push_crash(
                        &webview,
                        &CrashReportInfo {
                            title: "Échec du lancement de Minecraft".to_owned(),
                            summary: err_msg.clone(),
                            details: err_msg,
                            exit_code: None,
                            report_path: None,
                            suspected_mod_file: None,
                        },
                    );
                }
                AppEvent::TrayRestore => {
                    ctrl.restore_normal_window(&window, &webview, &tray);
                }
                AppEvent::TrayQuit => {
                    ctrl.auth_cancel.store(true, Ordering::Relaxed);
                    *control_flow = ControlFlow::Exit;
                }
                AppEvent::LogLine(line) => {
                    ctrl.append_or_buffer_log(&webview, line);
                }
                AppEvent::Toast { message, is_error } => {
                    Controller::push_toast(&webview, &message, is_error);
                }
            },
            _ => {}
        }
    });
}
