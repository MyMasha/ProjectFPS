#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! PureClient 3.5 — Singularity Edition Native Desktop Launcher (WebView2 `tao` + `wry`)
//! - Rendu temps-réel WebGL2 d'un Trou Noir relativiste (lentille gravitationnelle de Schwarzschild + disque Doppler)
//! - Transition cinématique sans changement de fenêtre visible : aspiration dans l'Horizon des Événements ->
//!   plein écran noir sans bordure (#000000) -> émergence directe sur le menu PureClient 1.8.9.
//! - Connexion Microsoft OAuth2 Device Code 1-clic (`microsoft.com/link` + détection auto)

mod auth;
mod config;
mod runner;
mod updater;

use auth::{
    copy_to_windows_clipboard, open_verification_browser, poll_device_code_until_complete,
    refresh_microsoft_session, start_device_code_flow, DeviceCodeChallenge,
};
use config::{
    app_storage_dir, clear_microsoft_account, effective_github_token, load_launcher_prefs,
    load_microsoft_account, save_custom_github_token, save_launcher_prefs, save_microsoft_account,
    LaunchMode, LauncherPrefs, MicrosoftAccount, PublicAccountView, PureClientSettings,
    PureProfile,
};
use runner::{offline_uuid_hex, run_direct_jvm_blocking, spawn_prism_bridge_sync};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tao::dpi::{LogicalSize, Size};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::window::{Fullscreen, Window, WindowBuilder};
use updater::{
    check_for_updates, cleanup_old_launcher_binary, download_and_install_update, format_semver,
    installed_mod_version, restart_launcher_process, sanitize_repo, InstallOutcome, PendingUpdate,
    UiUpdateView, UpdateCheckOutcome,
};
use wry::WebViewBuilder;

const UI_HTML: &str = include_str!("ui.html");

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
    GameRunning(bool),
    MinecraftMenuReady,
    RevealMinecraftNow,
    LaunchAborted(String),
    LogLine(String),
    Toast { message: String, is_error: bool },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
enum IpcCommand {
    UiReady,
    DragWindow,
    Minimize,
    ToggleMaximize,
    Close,
    SetProfile {
        profile: String,
    },
    SetSetting {
        key: String,
        value: serde_json::Value,
    },
    SetRam {
        ram_mb: u32,
    },
    SetOfflineName {
        name: String,
    },
    SetLaunchMode {
        mode: LaunchMode,
    },
    ToggleQuickHypixel,
    StartMsLogin,
    CancelMsLogin,
    OpenMsLink,
    CopyCode {
        code: String,
    },
    RefreshMs,
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
    SaveGithubToken {
        token: String,
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
    prefs: &'a LauncherPrefs,
    account: Option<PublicAccountView>,
    game_running: bool,
    device_code: Option<UiDeviceCodeView>,
    has_github_token: bool,
    update: &'a UiUpdateView,
}

struct Controller {
    prefs: LauncherPrefs,
    settings: PureClientSettings,
    account: Option<MicrosoftAccount>,
    game_running: bool,
    curtain_active: bool,
    plunge_darkness_reached: bool,
    mc_menu_ready: bool,
    handoff_dispatched: bool,
    active_challenge: Option<DeviceCodeChallenge>,
    auth_cancel: Arc<AtomicBool>,
    update_view: UiUpdateView,
    pending_update: Option<PendingUpdate>,
    startup_update_checked: bool,
}

impl Controller {
    fn new() -> Self {
        let mut prefs = load_launcher_prefs();
        let settings = PureClientSettings::load_from_game_dir(&prefs.game_dir);
        let account = load_microsoft_account();
        if account.is_some() && prefs.launch_mode == LaunchMode::DirectOffline {
            prefs.launch_mode = LaunchMode::DirectMicrosoft;
        }
        let update_view = UiUpdateView::initial(&prefs.game_dir);
        Self {
            prefs,
            settings,
            account,
            game_running: false,
            curtain_active: false,
            plunge_darkness_reached: false,
            mc_menu_ready: false,
            handoff_dispatched: false,
            active_challenge: None,
            auth_cancel: Arc::new(AtomicBool::new(false)),
            update_view,
            pending_update: None,
            startup_update_checked: false,
        }
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
        let game_dir = self.prefs.game_dir.clone();
        let tok = effective_github_token();
        let p = proxy.clone();
        std::thread::spawn(move || {
            let result = check_for_updates(&repo, tok.as_deref(), &game_dir);
            let _ = p.send_event(AppEvent::UpdateChecked { manual, result });
        });
    }

    fn push_full_state(&self, webview: &wry::WebView) {
        let snap = UiStateSnapshot {
            settings: &self.settings,
            prefs: &self.prefs,
            account: self.account.as_ref().map(PublicAccountView::from),
            game_running: self.game_running,
            device_code: self.active_challenge.as_ref().map(|c| UiDeviceCodeView {
                user_code: c.user_code.clone(),
                verification_uri: c.verification_uri.clone(),
            }),
            has_github_token: effective_github_token().is_some(),
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

    fn enter_singularity_curtain(&mut self, window: &Window, webview: &wry::WebView) {
        self.curtain_active = true;
        self.plunge_darkness_reached = false;
        self.mc_menu_ready = false;
        self.handoff_dispatched = false;

        window.set_fullscreen(Some(Fullscreen::Borderless(None)));
        window.set_always_on_top(true);
        window.set_cursor_visible(false);
        Self::push_plunge_command(webview, "start");
    }

    fn restore_normal_window(&mut self, window: &Window, webview: &wry::WebView) {
        self.curtain_active = false;
        self.plunge_darkness_reached = false;
        self.mc_menu_ready = false;
        self.handoff_dispatched = false;

        window.set_always_on_top(false);
        window.set_fullscreen(None);
        window.set_cursor_visible(true);
        window.set_visible(true);
        window.set_focus();
        Self::push_plunge_command(webview, "reset");
    }

    fn try_schedule_seamless_handoff(&mut self, proxy: &EventLoopProxy<AppEvent>) {
        if self.curtain_active
            && self.plunge_darkness_reached
            && self.mc_menu_ready
            && !self.handoff_dispatched
        {
            self.handoff_dispatched = true;
            let p = proxy.clone();
            std::thread::spawn(move || {
                // Laisse 55 ms au compositeur DWM pour valider le tampon OpenGL #000000 de Minecraft
                std::thread::sleep(Duration::from_millis(55));
                let _ = p.send_event(AppEvent::RevealMinecraftNow);
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

fn handle_ipc(
    cmd: IpcCommand,
    ctrl: &mut Controller,
    window: &Window,
    webview: &wry::WebView,
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
            window.set_minimized(true);
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
        IpcCommand::SetProfile { profile } => {
            let target = PureProfile::from_id(&profile);
            ctrl.settings.apply_profile(target);
            if let Err(e) = ctrl.settings.save_to_game_dir(&ctrl.prefs.game_dir) {
                Controller::push_toast(webview, &e, true);
            } else {
                Controller::push_toast(
                    webview,
                    &format!("Profil '{}' synchronisé.", target.id().to_uppercase()),
                    false,
                );
            }
            ctrl.push_full_state(webview);
        }
        IpcCommand::SetSetting { key, value } => {
            if ctrl.settings.set_field(&key, &value) {
                let _ = ctrl.settings.save_to_game_dir(&ctrl.prefs.game_dir);
                ctrl.push_full_state(webview);
            }
        }
        IpcCommand::SetRam { ram_mb } => {
            ctrl.prefs.ram_mb = ram_mb.clamp(2048, 12288);
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
        IpcCommand::SetLaunchMode { mode } => {
            ctrl.prefs.launch_mode = mode;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::ToggleQuickHypixel => {
            ctrl.prefs.quick_join_hypixel = !ctrl.prefs.quick_join_hypixel;
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
                Controller::push_toast(webview, "Rafraîchissement de la session Microsoft...", false);
                std::thread::spawn(move || {
                    let res = refresh_microsoft_session(&existing);
                    let _ = p.send_event(AppEvent::AuthCompleted(res));
                });
            }
        }
        IpcCommand::LogoutMs => {
            clear_microsoft_account();
            ctrl.account = None;
            if ctrl.prefs.launch_mode == LaunchMode::DirectMicrosoft {
                ctrl.prefs.launch_mode = LaunchMode::PrismBridge;
                let _ = save_launcher_prefs(&ctrl.prefs);
            }
            Controller::push_toast(webview, "Compte Microsoft déconnecté.", false);
            ctrl.push_full_state(webview);
        }
        IpcCommand::OpenModsFolder => {
            let mods_dir = Path::new(&ctrl.prefs.game_dir).join("mods");
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
            ctrl.update_view.message = format!("Téléchargement de PureClient v{}...", plan.version);
            ctrl.push_full_state(webview);

            let tok = effective_github_token();
            let game_dir = ctrl.prefs.game_dir.clone();
            let p = proxy.clone();
            std::thread::spawn(move || {
                let res = download_and_install_update(&plan, tok.as_deref(), &game_dir, &p);
                let _ = p.send_event(AppEvent::UpdateInstalled(res));
            });
        }
        IpcCommand::RestartLauncher => {
            match restart_launcher_process() {
                Ok(()) => {
                    ctrl.auth_cancel.store(true, Ordering::Relaxed);
                    return true;
                }
                Err(e) => {
                    Controller::push_toast(webview, &e, true);
                }
            }
        }
        IpcCommand::SetAutoUpdate { enabled } => {
            ctrl.prefs.auto_update_check = enabled;
            let _ = save_launcher_prefs(&ctrl.prefs);
            ctrl.push_full_state(webview);
        }
        IpcCommand::SetGithubRepo { repo } => {
            match sanitize_repo(&repo) {
                Ok(clean_repo) => {
                    ctrl.prefs.github_repo = clean_repo;
                    let _ = save_launcher_prefs(&ctrl.prefs);
                    ctrl.push_full_state(webview);
                    ctrl.spawn_update_check(true, webview, proxy);
                }
                Err(e) => {
                    Controller::push_toast(webview, &e, true);
                }
            }
        }
        IpcCommand::SaveGithubToken { token } => {
            match save_custom_github_token(&token) {
                Ok(()) => {
                    let msg = if token.trim().is_empty() {
                        "Jeton personnalisé effacé (utilisation du jeton embarqué)."
                    } else {
                        "Jeton GitHub Read-Only enregistré localement."
                    };
                    Controller::push_toast(webview, msg, false);
                    ctrl.push_full_state(webview);
                    ctrl.spawn_update_check(true, webview, proxy);
                }
                Err(e) => {
                    Controller::push_toast(webview, &format!("Erreur sauvegarde jeton: {e}"), true);
                }
            }
        }
        IpcCommand::PlungeDarknessReached => {
            ctrl.plunge_darkness_reached = true;
            ctrl.try_schedule_seamless_handoff(proxy);
        }
        IpcCommand::AbortLaunchCurtain => {
            ctrl.restore_normal_window(window, webview);
        }
        IpcCommand::LaunchGame => {
            if ctrl.game_running || ctrl.curtain_active {
                Controller::push_toast(webview, "Séquence de lancement déjà active.", false);
                return false;
            }

            // Validation préalable avant de basculer en plein écran Trou Noir
            if ctrl.prefs.launch_mode == LaunchMode::DirectMicrosoft && ctrl.account.is_none() {
                Controller::push_toast(
                    webview,
                    "Connectez d'abord votre compte Microsoft en haut à droite.",
                    true,
                );
                return false;
            }
            if ctrl.prefs.launch_mode == LaunchMode::PrismBridge
                && !Path::new(&ctrl.prefs.prism_exe).is_file()
            {
                Controller::push_toast(
                    webview,
                    &format!("PrismLauncher introuvable : {}", ctrl.prefs.prism_exe),
                    true,
                );
                return false;
            }
            if ctrl.prefs.launch_mode != LaunchMode::PrismBridge
                && !Path::new(&ctrl.prefs.java_path).is_file()
            {
                Controller::push_toast(
                    webview,
                    &format!("Java 8 introuvable : {}", ctrl.prefs.java_path),
                    true,
                );
                return false;
            }

            let _ = ctrl.settings.save_to_game_dir(&ctrl.prefs.game_dir);
            let _ = save_launcher_prefs(&ctrl.prefs);

            let (screen_w, screen_h) = current_monitor_resolution(window);
            ctrl.enter_singularity_curtain(window, webview);

            match ctrl.prefs.launch_mode {
                LaunchMode::PrismBridge => {
                    if let Err(e) = spawn_prism_bridge_sync(&ctrl.prefs, proxy) {
                        ctrl.restore_normal_window(window, webview);
                        Controller::push_toast(webview, &e, true);
                    }
                }
                LaunchMode::DirectMicrosoft => {
                    let Some(acc) = ctrl.account.clone() else {
                        ctrl.restore_normal_window(window, webview);
                        return false;
                    };
                    let prefs_clone = ctrl.prefs.clone();
                    let p = proxy.clone();
                    std::thread::spawn(move || {
                        let active_acc = match refresh_microsoft_session(&acc) {
                            Ok(fresh) => {
                                let _ = save_microsoft_account(&fresh);
                                fresh
                            }
                            Err(_) => acc,
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
    }
    false
}

fn main() -> wry::Result<()> {
    cleanup_old_launcher_binary();

    let event_loop = EventLoopBuilder::<AppEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let window = WindowBuilder::new()
        .with_title("PureClient — Singularity")
        .with_decorations(false)
        .with_inner_size(Size::Logical(LogicalSize::new(1240.0, 780.0)))
        .with_min_inner_size(Size::Logical(LogicalSize::new(1024.0, 680.0)))
        .build(&event_loop)
        .expect("Création fenêtre PureClient Launcher");

    let webview_data_dir = app_storage_dir().join("webview2-cache");
    let _ = std::fs::create_dir_all(&webview_data_dir);
    let mut context = wry::WebContext::new(Some(webview_data_dir));

    let ipc_proxy = proxy.clone();
    let webview = WebViewBuilder::new_with_web_context(&mut context)
        .with_background_color((0, 0, 0, 255))
        .with_html(UI_HTML)
        .with_ipc_handler(move |req: wry::http::Request<String>| {
            let _ = ipc_proxy.send_event(AppEvent::IpcRaw(req.body().clone()));
        })
        .build(&window)?;

    let mut ctrl = Controller::new();

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;

        match event {
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                ctrl.auth_cancel.store(true, Ordering::Relaxed);
                *control_flow = ControlFlow::Exit;
            }
            Event::UserEvent(app_evt) => match app_evt {
                AppEvent::IpcRaw(raw) => {
                    if let Ok(cmd) = serde_json::from_str::<IpcCommand>(&raw) {
                        if handle_ipc(cmd, &mut ctrl, &window, &webview, &proxy) {
                            *control_flow = ControlFlow::Exit;
                        }
                    }
                }
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
                        ctrl.account = Some(acc);
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
                                format!("PureClient est à jour (v{latest_version}).");
                            Controller::push_log(
                                &webview,
                                &format!("[Auto-Updater] À jour sur GitHub Releases (v{latest_version})."),
                            );
                            if manual {
                                Controller::push_toast(
                                    &webview,
                                    &format!("PureClient v{latest_version} est déjà à jour."),
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
                                "Dépôt privé {repo} connecté — en attente d'une première GitHub Release."
                            );
                            Controller::push_log(
                                &webview,
                                &format!(
                                    "[Auto-Updater] Authentifié sur {repo} (aucune Release publiée pour l'instant)."
                                ),
                            );
                            if manual {
                                Controller::push_toast(
                                    &webview,
                                    &format!(
                                        "Connexion GitHub vérifiée ({repo}) : aucune Release publiée pour l'instant."
                                    ),
                                    false,
                                );
                            }
                        }
                        Ok(UpdateCheckOutcome::Available(plan)) => {
                            let ver = plan.version.clone();
                            ctrl.update_view.status = "available".to_owned();
                            ctrl.update_view.latest_version = Some(ver.clone());
                            ctrl.update_view.release_notes = Some(plan.body.clone())
                                .filter(|s| !s.trim().is_empty());
                            ctrl.update_view.has_exe_update = plan.exe_asset.is_some();
                            ctrl.update_view.has_jar_update = plan.jar_asset.is_some();
                            ctrl.update_view.download_percent = 0;
                            ctrl.update_view.message = format!(
                                "Nouvelle version v{ver} disponible sur GitHub Releases !"
                            );
                            ctrl.pending_update = Some(plan);
                            Controller::push_log(
                                &webview,
                                &format!("[Auto-Updater] Mise à jour disponible : v{ver}."),
                            );
                            Controller::push_toast(
                                &webview,
                                &format!("Mise à jour PureClient v{ver} disponible !"),
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
                            ctrl.update_view.installed_jar_version =
                                installed_mod_version(&ctrl.prefs.game_dir).map(format_semver);
                            if let Some(ref jar_name) = outcome.jar_installed {
                                Controller::push_log(
                                    &webview,
                                    &format!("[Auto-Updater] Archive {jar_name} déployée dans mods/."),
                                );
                            }
                            if outcome.exe_staged_for_restart {
                                ctrl.update_view.status = "ready_restart".to_owned();
                                ctrl.update_view.message = format!(
                                    "Mise à jour v{} prête — relancez PureClient pour activer le nouvel exécutable.",
                                    outcome.version
                                );
                                Controller::push_toast(
                                    &webview,
                                    &format!(
                                        "PureClient v{} installé ! Cliquez sur Relancer.",
                                        outcome.version
                                    ),
                                    false,
                                );
                            } else {
                                ctrl.update_view.status = "up_to_date".to_owned();
                                ctrl.update_view.has_jar_update = false;
                                ctrl.update_view.has_exe_update = false;
                                ctrl.update_view.message = format!(
                                    "Mod PureClient v{} installé dans mods/ et prêt à jouer !",
                                    outcome.version
                                );
                                Controller::push_toast(
                                    &webview,
                                    &format!("Mod PureClient v{} mis à jour avec succès !", outcome.version),
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
                    if !running && (ctrl.curtain_active || !window.is_visible()) {
                        ctrl.restore_normal_window(&window, &webview);
                    }
                    ctrl.push_full_state(&webview);
                }
                AppEvent::MinecraftMenuReady => {
                    ctrl.mc_menu_ready = true;
                    ctrl.try_schedule_seamless_handoff(&proxy);
                }
                AppEvent::RevealMinecraftNow => {
                    if ctrl.curtain_active {
                        // Masque instantanément le rideau #000000 au-dessus du menu #000000 de Minecraft déjà ouvert à (0, 0)
                        window.set_always_on_top(false);
                        window.set_visible(false);
                    }
                }
                AppEvent::LaunchAborted(err_msg) => {
                    ctrl.game_running = false;
                    ctrl.restore_normal_window(&window, &webview);
                    ctrl.push_full_state(&webview);
                    Controller::push_toast(&webview, &err_msg, true);
                }
                AppEvent::LogLine(line) => {
                    Controller::push_log(&webview, &line);
                }
                AppEvent::Toast { message, is_error } => {
                    Controller::push_toast(&webview, &message, is_error);
                }
            },
            _ => {}
        }
    });
}
