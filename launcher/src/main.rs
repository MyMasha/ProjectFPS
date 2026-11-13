#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! PureClient 3.5 — Launcher Bureau Natif Windows (WebView2 `tao` + `wry`)
//! - UI Prosumer Gaming Dark Glass (Lunar / Modrinth / Linear Standard, zéro emoji)
//! - Connexion Microsoft OAuth2 Device Code 1-clic (`microsoft.com/link` + détection auto)
//! - Compilation ultra-rapide (zéro `eframe`/`wgpu`/`tokio`/`ring`, ~60 crates mises en cache)

mod auth;
mod config;
mod runner;

use auth::{
    copy_to_windows_clipboard, open_verification_browser, poll_device_code_until_complete,
    refresh_microsoft_session, start_device_code_flow, DeviceCodeChallenge,
};
use config::{
    app_storage_dir, clear_microsoft_account, load_launcher_prefs, load_microsoft_account,
    save_launcher_prefs, save_microsoft_account, LaunchMode, LauncherPrefs, MicrosoftAccount,
    PublicAccountView, PureClientSettings, PureProfile,
};
use runner::{offline_uuid_hex, run_direct_jvm_blocking, spawn_prism_bridge_sync};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tao::dpi::{LogicalSize, Size};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::window::WindowBuilder;
use wry::WebViewBuilder;

const UI_HTML: &str = include_str!("ui.html");

#[derive(Debug)]
pub enum AppEvent {
    IpcRaw(String),
    DeviceCodeStarted(Result<DeviceCodeChallenge, String>),
    AuthCompleted(Result<MicrosoftAccount, String>),
    GameRunning(bool),
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
    LaunchGame,
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
}

struct Controller {
    prefs: LauncherPrefs,
    settings: PureClientSettings,
    account: Option<MicrosoftAccount>,
    game_running: bool,
    active_challenge: Option<DeviceCodeChallenge>,
    auth_cancel: Arc<AtomicBool>,
}

impl Controller {
    fn new() -> Self {
        let mut prefs = load_launcher_prefs();
        let settings = PureClientSettings::load_from_game_dir(&prefs.game_dir);
        let account = load_microsoft_account();
        if account.is_some() && prefs.launch_mode == LaunchMode::DirectOffline {
            prefs.launch_mode = LaunchMode::DirectMicrosoft;
        }
        Self {
            prefs,
            settings,
            account,
            game_running: false,
            active_challenge: None,
            auth_cancel: Arc::new(AtomicBool::new(false)),
        }
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
}

fn handle_ipc(
    cmd: IpcCommand,
    ctrl: &mut Controller,
    window: &tao::window::Window,
    webview: &wry::WebView,
    proxy: &EventLoopProxy<AppEvent>,
) -> bool {
    match cmd {
        IpcCommand::UiReady => {
            ctrl.push_full_state(webview);
        }
        IpcCommand::DragWindow => {
            let _ = window.drag_window();
        }
        IpcCommand::Minimize => {
            window.set_minimized(true);
        }
        IpcCommand::ToggleMaximize => {
            window.set_maximized(!window.is_maximized());
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
                    &format!("Profil '{}' enregistré dans purefps.properties.", target.id().to_uppercase()),
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
        IpcCommand::LaunchGame => {
            if ctrl.game_running {
                Controller::push_toast(webview, "Minecraft 1.8.9 est déjà en cours d'exécution.", false);
                return false;
            }
            let _ = ctrl.settings.save_to_game_dir(&ctrl.prefs.game_dir);
            let _ = save_launcher_prefs(&ctrl.prefs);

            match ctrl.prefs.launch_mode {
                LaunchMode::PrismBridge => {
                    if let Err(e) = spawn_prism_bridge_sync(&ctrl.prefs, proxy) {
                        Controller::push_toast(webview, &e, true);
                    }
                }
                LaunchMode::DirectMicrosoft => {
                    let Some(acc) = ctrl.account.clone() else {
                        Controller::push_toast(
                            webview,
                            "Connectez d'abord votre compte Microsoft dans la barre latérale (1 clic).",
                            true,
                        );
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
                            p.clone(),
                        ) {
                            let _ = p.send_event(AppEvent::Toast {
                                message: e,
                                is_error: true,
                            });
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
                            p.clone(),
                        ) {
                            let _ = p.send_event(AppEvent::Toast {
                                message: e,
                                is_error: true,
                            });
                        }
                    });
                }
            }
        }
    }
    false
}

fn main() -> wry::Result<()> {
    let event_loop = EventLoopBuilder::<AppEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let window = WindowBuilder::new()
        .with_title("PureClient 3.5 Launcher")
        .with_decorations(false)
        .with_inner_size(Size::Logical(LogicalSize::new(1180.0, 760.0)))
        .with_min_inner_size(Size::Logical(LogicalSize::new(980.0, 660.0)))
        .build(&event_loop)
        .expect("Création fenêtre PureClient Launcher");

    let webview_data_dir = app_storage_dir().join("webview2-cache");
    let _ = std::fs::create_dir_all(&webview_data_dir);
    let mut context = wry::WebContext::new(Some(webview_data_dir));

    let ipc_proxy = proxy.clone();
    let webview = WebViewBuilder::new_with_web_context(&mut context)
        .with_background_color((8, 10, 16, 255))
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
                            &format!("Connecté à Microsoft : {uname} (Prêt à lancer)"),
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
                AppEvent::GameRunning(running) => {
                    ctrl.game_running = running;
                    ctrl.push_full_state(&webview);
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
