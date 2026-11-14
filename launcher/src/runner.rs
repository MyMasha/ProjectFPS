//! Exécuteur autonome JVM 1.8.9 + Forge 11.15.1.2318 + MashClient 4.4.0.
//! - Synchronise automatiquement `purefps-4.4.0.jar` (et le télécharge depuis GitHub Releases s'il manque ou est obsolète)
//! - Extrait les DLL natives LWJGL 2.9.4 / JInput / Twitch 64-bit
//! - Détecte instantanément l'ouverture de la fenêtre OpenGL (`DISPLAY_CREATED` / `.purefps_display_ready`)
//!   pour réduire le Launcher en bas à droite (System Tray) à l'instant exact où Minecraft prend sa place.

use crate::config::{
    app_storage_dir, effective_github_token, is_real_prism_instance, prepare_seamless_launch_files,
    resolve_prism_instance_game_dir, LauncherPrefs,
};
use crate::updater::{
    check_for_updates, download_and_install_update, extract_purefps_jar_semver,
    installed_mod_version, parse_semver, UpdateCheckOutcome, LAUNCHER_VERSION,
};
use crate::AppEvent;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tao::event_loop::EventLoopProxy;

const FALLBACK_PURECLIENT_JAR_NAME: &str = "purefps-4.4.0.jar";

const REQUIRED_LIBRARIES_189_FORGE: &[&str] = &[
    r"net\java\jinput\jinput\2.0.5\jinput-2.0.5.jar",
    r"net\java\jutils\jutils\1.0.0\jutils-1.0.0.jar",
    r"org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar",
    r"org\lwjgl\lwjgl\lwjgl_util\2.9.4-nightly-20150209\lwjgl_util-2.9.4-nightly-20150209.jar",
    r"com\mojang\netty\1.8.8\netty-1.8.8.jar",
    r"oshi-project\oshi-core\1.1\oshi-core-1.1.jar",
    r"net\java\dev\jna\jna\3.4.0\jna-3.4.0.jar",
    r"net\java\dev\jna\platform\3.4.0\platform-3.4.0.jar",
    r"com\ibm\icu\icu4j-core-mojang\51.2\icu4j-core-mojang-51.2.jar",
    r"net\sf\jopt-simple\jopt-simple\4.6\jopt-simple-4.6.jar",
    r"com\paulscode\codecjorbis\20101023\codecjorbis-20101023.jar",
    r"com\paulscode\codecwav\20101023\codecwav-20101023.jar",
    r"com\paulscode\libraryjavasound\20101123\libraryjavasound-20101123.jar",
    r"com\paulscode\librarylwjglopenal\20100824\librarylwjglopenal-20100824.jar",
    r"com\paulscode\soundsystem\20120107\soundsystem-20120107.jar",
    r"io\netty\netty-all\4.0.23.Final\netty-all-4.0.23.Final.jar",
    r"com\google\guava\guava\17.0\guava-17.0.jar",
    r"org\apache\commons\commons-lang3\3.3.2\commons-lang3-3.3.2.jar",
    r"commons-io\commons-io\2.4\commons-io-2.4.jar",
    r"commons-codec\commons-codec\1.9\commons-codec-1.9.jar",
    r"com\google\code\gson\gson\2.2.4\gson-2.2.4.jar",
    r"com\mojang\authlib\1.5.21\authlib-1.5.21.jar",
    r"com\mojang\realms\1.7.59\realms-1.7.59.jar",
    r"org\apache\commons\commons-compress\1.8.1\commons-compress-1.8.1.jar",
    r"org\apache\httpcomponents\httpclient\4.3.3\httpclient-4.3.3.jar",
    r"commons-logging\commons-logging\1.1.3\commons-logging-1.1.3.jar",
    r"org\apache\httpcomponents\httpcore\4.3.2\httpcore-4.3.2.jar",
    r"org\apache\logging\log4j\log4j-api\2.0-beta9-fixed\log4j-api-2.0-beta9-fixed.jar",
    r"org\apache\logging\log4j\log4j-core\2.0-beta9-fixed\log4j-core-2.0-beta9-fixed.jar",
    r"tv\twitch\twitch\6.5\twitch-6.5.jar",
    r"net\minecraftforge\forge\1.8.9-11.15.1.2318-1.8.9\forge-1.8.9-11.15.1.2318-1.8.9-universal.jar",
    r"net\minecraft\launchwrapper\1.12\launchwrapper-1.12.jar",
    r"org\ow2\asm\asm-all\5.0.3\asm-all-5.0.3.jar",
    r"jline\jline\2.13\jline-2.13.jar",
    r"com\typesafe\akka\akka-actor_2.11\2.3.3\akka-actor_2.11-2.3.3.jar",
    r"com\typesafe\config\1.2.1\config-1.2.1.jar",
    r"org\scala-lang\scala-actors-migration_2.11\1.1.0\scala-actors-migration_2.11-1.1.0.jar",
    r"org\scala-lang\scala-compiler\2.11.1\scala-compiler-2.11.1.jar",
    r"org\scala-lang\plugins\scala-continuations-library_2.11\1.0.2\scala-continuations-library_2.11-1.0.2.jar",
    r"org\scala-lang\plugins\scala-continuations-plugin_2.11.1\1.0.2\scala-continuations-plugin_2.11.1-1.0.2.jar",
    r"org\scala-lang\scala-library\2.11.1\scala-library-2.11.1.jar",
    r"org\scala-lang\scala-parser-combinators_2.11\1.0.1\scala-parser-combinators_2.11-1.0.1.jar",
    r"org\scala-lang\scala-reflect\2.11.1\scala-reflect-2.11.1.jar",
    r"org\scala-lang\scala-swing_2.11\1.0.1\scala-swing_2.11-1.0.1.jar",
    r"org\scala-lang\scala-xml_2.11\1.0.2\scala-xml_2.11-1.0.2.jar",
    r"lzma\lzma\0.0.1\lzma-0.0.1.jar",
    r"java3d\vecmath\1.5.2\vecmath-1.5.2.jar",
    r"net\sf\trove4j\trove4j\3.0.3\trove4j-3.0.3.jar",
    r"com\mojang\minecraft\1.8.9\minecraft-1.8.9-client.jar",
];

const NATIVE_JARS_64: &[&str] = &[
    r"org\lwjgl\lwjgl\lwjgl-platform\2.9.4-nightly-20150209\lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar",
    r"net\java\jinput\jinput-platform\2.0.5\jinput-platform-2.0.5-natives-windows.jar",
    r"tv\twitch\twitch-platform\6.5\twitch-platform-6.5-natives-windows-64.jar",
    r"tv\twitch\twitch-external-platform\4.5\twitch-external-platform-4.5-natives-windows-64.jar",
];

pub fn offline_uuid_hex(username: &str) -> String {
    let input = format!("OfflinePlayer:{username}");
    let mut bytes = md5::compute(input.as_bytes()).0;
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let mut hex = String::with_capacity(32);
    for b in bytes {
        hex.push_str(&format!("{b:02x}"));
    }
    hex
}

/// Si le mod `purefps-*.jar` est absent ou plus ancien que le Launcher, tente de le récupérer
/// automatiquement depuis GitHub Releases avant le lancement pour garantir le Trou Noir Mojang !
pub fn auto_fetch_mod_jar_if_needed(prefs: &LauncherPrefs, proxy: &EventLoopProxy<AppEvent>) {
    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((4, 0, 0));
    let cur_mod = installed_mod_version(&prefs.game_dir);
    if cur_mod.is_some_and(|v| v >= launcher_ver) {
        return;
    }

    let tok = effective_github_token();
    if let Ok(UpdateCheckOutcome::Available(mut plan)) =
        check_for_updates(&prefs.github_repo, tok.as_deref(), &prefs.game_dir)
    {
        if plan.jar_asset.is_some() {
            plan.exe_asset = None;
            let _ = proxy.send_event(AppEvent::LogLine(format!(
                "[MashClient] Téléchargement automatique du mod MashClient v{}...",
                plan.version
            )));
            let _ = download_and_install_update(&plan, tok.as_deref(), &prefs.game_dir, proxy);
        }
    }
}

pub fn sync_pureclient_jar(game_dir: &str) -> Result<String, String> {
    let mods_dir = Path::new(game_dir).join("mods");
    std::fs::create_dir_all(&mods_dir).map_err(|e| format!("Création dossier mods: {e}"))?;

    let mut search_dirs: Vec<PathBuf> = vec![app_storage_dir().join("updates"), mods_dir.clone()];
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            search_dirs.push(dir.to_path_buf());
            search_dirs.push(dir.join("build").join("libs"));
            if let Some(parent) = dir.parent().and_then(|p| p.parent()) {
                search_dirs.push(parent.to_path_buf());
                search_dirs.push(parent.join("build").join("libs"));
                if let Some(workspace_root) = parent.parent() {
                    search_dirs.push(workspace_root.to_path_buf());
                    search_dirs.push(workspace_root.join("build").join("libs"));
                }
            }
        }
    }
    search_dirs.push(PathBuf::from(r"build\libs"));
    search_dirs.push(PathBuf::from("."));

    let mut best_jar: Option<((u32, u32, u32), String, PathBuf)> = None;
    for dir in &search_dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            if let Some(ver) = extract_purefps_jar_semver(&name) {
                let replace = match &best_jar {
                    None => true,
                    Some((cur_ver, _, cur_path)) => {
                        ver > *cur_ver
                            || (ver == *cur_ver
                                && cur_path.parent() == Some(mods_dir.as_path())
                                && path.parent() != Some(mods_dir.as_path()))
                    }
                };
                if replace {
                    best_jar = Some((ver, name, path));
                }
            }
        }
    }

    if let Some((_, jar_name, src_path)) = best_jar {
        let target_jar = mods_dir.join(&jar_name);
        if src_path != target_jar {
            std::fs::copy(&src_path, &target_jar)
                .map_err(|e| format!("Copie de {jar_name}: {e}"))?;
        }
        if let Ok(entries) = std::fs::read_dir(&mods_dir) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                if extract_purefps_jar_semver(&name).is_some() && name != jar_name {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }
        return Ok(format!("{jar_name} actif ({})", src_path.display()));
    }

    Err(format!(
        "Archive {FALLBACK_PURECLIENT_JAR_NAME} introuvable (lancez une mise à jour dans l'onglet Système)."
    ))
}

pub fn extract_natives_if_needed(libraries_dir: &str) -> Result<PathBuf, String> {
    let natives_dir = app_storage_dir().join("natives-1.8.9");
    std::fs::create_dir_all(&natives_dir).map_err(|e| format!("Création dossier natives: {e}"))?;

    if natives_dir.join("lwjgl64.dll").is_file() && natives_dir.join("OpenAL64.dll").is_file() {
        return Ok(natives_dir);
    }

    for rel in NATIVE_JARS_64 {
        let jar_path = Path::new(libraries_dir).join(rel);
        if !jar_path.is_file() {
            continue;
        }
        let file =
            File::open(&jar_path).map_err(|e| format!("Ouverture {}: {e}", jar_path.display()))?;
        let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("Zip invalide: {e}"))?;
        for idx in 0..archive.len() {
            let mut entry = archive.by_index(idx).map_err(|e| e.to_string())?;
            let name = entry.name().to_owned();
            if entry.is_dir() || name.starts_with("META-INF") {
                continue;
            }
            if !name.to_ascii_lowercase().ends_with(".dll") {
                continue;
            }
            if matches!(
                name.as_str(),
                "lwjgl.dll" | "OpenAL32.dll" | "jinput-dx8.dll" | "jinput-raw.dll"
            ) {
                continue;
            }
            let Some(file_name) = Path::new(&name).file_name() else {
                continue;
            };
            let dst_path = natives_dir.join(file_name);
            let mut buf = Vec::with_capacity(entry.size() as usize);
            entry.read_to_end(&mut buf).map_err(|e| e.to_string())?;
            let mut out = File::create(&dst_path).map_err(|e| e.to_string())?;
            out.write_all(&buf).map_err(|e| e.to_string())?;
        }
    }

    if !natives_dir.join("lwjgl64.dll").is_file() {
        return Err("Impossible d'extraire lwjgl64.dll depuis libraries/.".to_owned());
    }
    Ok(natives_dir)
}

pub fn build_classpath(libraries_dir: &str) -> Result<String, String> {
    let mut paths: Vec<String> = Vec::with_capacity(REQUIRED_LIBRARIES_189_FORGE.len());
    for rel in REQUIRED_LIBRARIES_189_FORGE {
        let full = Path::new(libraries_dir).join(rel);
        if !full.is_file() {
            return Err(format!("Bibliothèque manquante : {}", full.display()));
        }
        paths.push(full.to_string_lossy().to_string());
    }
    Ok(paths.join(";"))
}

pub fn resolve_prism_instance_id(prefs: &LauncherPrefs) -> String {
    if let Some(inst_root) = Path::new(&prefs.game_dir).parent() {
        if is_real_prism_instance(inst_root) {
            if let Some(name) = inst_root.file_name() {
                return name.to_string_lossy().to_string();
            }
        }
        if let Some(instances_dir) = inst_root.parent() {
            if let Ok(entries) = std::fs::read_dir(instances_dir) {
                for entry in entries.flatten() {
                    let p = entry.path();
                    if p.is_dir() && is_real_prism_instance(&p) {
                        let fname = entry.file_name().to_string_lossy().to_string();
                        let mmc = std::fs::read_to_string(p.join("mmc-pack.json")).unwrap_or_default();
                        if mmc.contains("1.8.9") || fname.contains("1.8.9") {
                            return fname;
                        }
                    }
                }
            }
        }
    }
    "1.8.9 gemini fps".to_owned()
}

fn collect_watched_189_game_dirs(primary_game_dir: &str) -> Vec<PathBuf> {
    let mut dirs = vec![PathBuf::from(primary_game_dir)];
    if let Some(instances_dir) = Path::new(primary_game_dir)
        .parent()
        .and_then(|p| p.parent())
    {
        if let Ok(entries) = std::fs::read_dir(instances_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if !p.is_dir() || !is_real_prism_instance(&p) {
                    continue;
                }
                let mmc = std::fs::read_to_string(p.join("mmc-pack.json")).unwrap_or_default();
                let fname = entry.file_name().to_string_lossy().to_string();
                if mmc.contains("1.8.9") || fname.contains("1.8.9") {
                    let gdir = resolve_prism_instance_game_dir(&p);
                    if !dirs.contains(&gdir) {
                        dirs.push(gdir);
                    }
                }
            }
        }
    }
    dirs
}

pub fn sync_prism_instance_cfg(instance_game_dir: &Path, ram_mb: u32, screen_w: u32, screen_h: u32) {
    let Some(instance_root) = instance_game_dir.parent() else {
        return;
    };
    let cfg_path = instance_root.join("instance.cfg");
    let Ok(existing) = std::fs::read_to_string(&cfg_path) else {
        return;
    };

    let mut updated_lines: Vec<String> = Vec::new();
    let mut saw_min = false;
    let mut saw_max = false;
    let mut saw_override_win = false;
    let mut saw_win_w = false;
    let mut saw_win_h = false;

    for line in existing.lines() {
        if line.starts_with("MinMemAlloc=") {
            updated_lines.push(format!("MinMemAlloc={ram_mb}"));
            saw_min = true;
        } else if line.starts_with("MaxMemAlloc=") {
            updated_lines.push(format!("MaxMemAlloc={ram_mb}"));
            saw_max = true;
        } else if line.starts_with("OverrideWindow=") {
            updated_lines.push("OverrideWindow=true".to_owned());
            saw_override_win = true;
        } else if line.starts_with("WindowWidth=") {
            updated_lines.push(format!("WindowWidth={}", screen_w.max(854)));
            saw_win_w = true;
        } else if line.starts_with("WindowHeight=") {
            updated_lines.push(format!("WindowHeight={}", screen_h.max(480)));
            saw_win_h = true;
        } else {
            updated_lines.push(line.to_owned());
        }
    }
    if !saw_min {
        updated_lines.push(format!("MinMemAlloc={ram_mb}"));
    }
    if !saw_max {
        updated_lines.push(format!("MaxMemAlloc={ram_mb}"));
    }
    if !saw_override_win {
        updated_lines.push("OverrideWindow=true".to_owned());
    }
    if !saw_win_w {
        updated_lines.push(format!("WindowWidth={}", screen_w.max(854)));
    }
    if !saw_win_h {
        updated_lines.push(format!("WindowHeight={}", screen_h.max(480)));
    }
    let _ = std::fs::write(&cfg_path, updated_lines.join("\n") + "\n");
}

#[cfg(windows)]
fn detect_and_focus_new_lwjgl_window(existing_hwnd: usize) -> Option<usize> {
    use core::ffi::c_void;
    #[link(name = "user32")]
    extern "system" {
        fn FindWindowExW(
            hWndParent: *mut c_void,
            hWndChildAfter: *mut c_void,
            lpszClass: *const u16,
            lpszWindow: *const u16,
        ) -> *mut c_void;
        fn IsWindowVisible(hWnd: *mut c_void) -> i32;
        fn SetForegroundWindow(hWnd: *mut c_void) -> i32;
    }
    let cls: [u16; 6] = [b'L' as u16, b'W' as u16, b'J' as u16, b'G' as u16, b'L' as u16, 0];
    unsafe {
        let mut cur: *mut c_void = core::ptr::null_mut();
        loop {
            cur = FindWindowExW(core::ptr::null_mut(), cur, cls.as_ptr(), core::ptr::null());
            if cur.is_null() {
                break;
            }
            let addr = cur as usize;
            if addr != existing_hwnd && IsWindowVisible(cur) != 0 {
                let _ = SetForegroundWindow(cur);
                return Some(addr);
            }
        }
    }
    None
}

#[cfg(windows)]
fn current_visible_lwjgl_hwnd() -> usize {
    use core::ffi::c_void;
    #[link(name = "user32")]
    extern "system" {
        fn FindWindowW(lpClassName: *const u16, lpWindowName: *const u16) -> *mut c_void;
        fn IsWindowVisible(hWnd: *mut c_void) -> i32;
    }
    let cls: [u16; 6] = [b'L' as u16, b'W' as u16, b'J' as u16, b'G' as u16, b'L' as u16, 0];
    unsafe {
        let hwnd = FindWindowW(cls.as_ptr(), core::ptr::null());
        if !hwnd.is_null() && IsWindowVisible(hwnd) != 0 {
            hwnd as usize
        } else {
            0
        }
    }
}

#[cfg(not(windows))]
fn current_visible_lwjgl_hwnd() -> usize {
    0
}

#[cfg(not(windows))]
fn detect_and_focus_new_lwjgl_window(_existing_hwnd: usize) -> Option<usize> {
    None
}

fn spawn_display_ready_watcher(
    watched_game_dirs: Vec<PathBuf>,
    proxy: EventLoopProxy<AppEvent>,
    stop_flag: Arc<AtomicBool>,
) {
    let pre_existing_lwjgl = current_visible_lwjgl_hwnd();
    std::thread::spawn(move || {
        let start = Instant::now();
        let timeout = Duration::from_secs(26);
        while !stop_flag.load(Ordering::Relaxed) && start.elapsed() < timeout {
            for dir in &watched_game_dirs {
                let cfg = dir.join("config");
                let disp_ready = cfg.join(".purefps_display_ready");
                let menu_ready = cfg.join(".purefps_menu_ready");
                if disp_ready.is_file() || menu_ready.is_file() {
                    let _ = std::fs::remove_file(&disp_ready);
                    let _ = std::fs::remove_file(&menu_ready);
                    let _ = detect_and_focus_new_lwjgl_window(pre_existing_lwjgl);
                    stop_flag.store(true, Ordering::Relaxed);
                    let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                    return;
                }
            }
            if start.elapsed() >= Duration::from_millis(900)
                && detect_and_focus_new_lwjgl_window(pre_existing_lwjgl).is_some()
            {
                stop_flag.store(true, Ordering::Relaxed);
                let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                return;
            }
            std::thread::sleep(Duration::from_millis(25));
        }
        // Sécurité absolue : ne bloque jamais l'écran indéfiniment même si une instance externe met très longtemps
        if !stop_flag.load(Ordering::Relaxed) {
            let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
        }
    });
}

#[allow(clippy::too_many_arguments)]
pub fn run_direct_jvm_blocking(
    prefs: LauncherPrefs,
    username: String,
    uuid: String,
    access_token: String,
    user_type: String,
    screen_w: u32,
    screen_h: u32,
    proxy: EventLoopProxy<AppEvent>,
) -> Result<(), String> {
    if !Path::new(&prefs.java_path).is_file() {
        return Err(format!("Java introuvable : {}", prefs.java_path));
    }

    auto_fetch_mod_jar_if_needed(&prefs, &proxy);

    let jar_msg = sync_pureclient_jar(&prefs.game_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!("[MashClient] {jar_msg}")));

    prepare_seamless_launch_files(&prefs.game_dir);

    let natives_dir = extract_natives_if_needed(&prefs.libraries_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Natives 64-bit prêtes : {}",
        natives_dir.display()
    )));

    let classpath = build_classpath(&prefs.libraries_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Démarrage de Minecraft 1.8.9 ({} Mo, {}x{}, {})...",
        prefs.ram_mb, screen_w, screen_h, username
    )));

    let mut cmd = Command::new(&prefs.java_path);
    cmd.current_dir(&prefs.game_dir)
        .env("__GL_THREADED_OPTIMIZATIONS", "1")
        .env("SHIM_MCCOMPAT", "0x800000001")
        .arg(format!("-Xms{}M", prefs.ram_mb))
        .arg(format!("-Xmx{}M", prefs.ram_mb))
        .arg("-XX:+UnlockExperimentalVMOptions")
        .arg("-XX:+UseG1GC")
        .arg("-XX:+AlwaysPreTouch")
        .arg("-XX:G1NewSizePercent=20")
        .arg("-XX:G1ReservePercent=20")
        .arg("-XX:MaxGCPauseMillis=25")
        .arg("-XX:G1HeapRegionSize=32M")
        .arg("-XX:+DisableExplicitGC")
        .arg("-XX:+ParallelRefProcEnabled")
        .arg("-Djava.net.preferIPv4Stack=true")
        .arg("-Dfml.ignoreInvalidMinecraftCertificates=true")
        .arg("-Dfml.ignorePatchDiscrepancies=true")
        .arg("-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=false")
        .arg("-Dorg.lwjgl.opengl.Window.undecorated=true")
        .arg("-Dpurefps.seamlessLaunch=true")
        .arg(format!("-Djava.library.path={}", natives_dir.display()))
        .arg("-cp")
        .arg(classpath)
        .arg("net.minecraft.launchwrapper.Launch")
        .arg("--username")
        .arg(&username)
        .arg("--version")
        .arg("1.8.9")
        .arg("--gameDir")
        .arg(&prefs.game_dir)
        .arg("--assetsDir")
        .arg(&prefs.assets_dir)
        .arg("--assetIndex")
        .arg("1.8")
        .arg("--uuid")
        .arg(&uuid)
        .arg("--accessToken")
        .arg(&access_token)
        .arg("--userType")
        .arg(&user_type)
        .arg("--width")
        .arg(screen_w.max(854).to_string())
        .arg("--height")
        .arg(screen_h.max(480).to_string())
        .arg("--tweakClass")
        .arg("net.minecraftforge.fml.common.launcher.FMLTweaker");

    if prefs.quick_join_hypixel {
        cmd.arg("--server")
            .arg("mc.hypixel.net")
            .arg("--port")
            .arg("25565");
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("Impossible de lancer javaw.exe : {e}"))?;

    let _ = proxy.send_event(AppEvent::GameRunning(true));

    let stop_watcher = Arc::new(AtomicBool::new(false));
    spawn_display_ready_watcher(
        vec![PathBuf::from(&prefs.game_dir)],
        proxy.clone(),
        Arc::clone(&stop_watcher),
    );

    if let Some(stdout) = child.stdout.take() {
        let p_out = proxy.clone();
        let stop_on_ready = Arc::clone(&stop_watcher);
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if line.contains("[PureClient-Ready] DISPLAY_CREATED")
                    || line.contains("[PureClient-Ready] MENU_ACTIVE")
                {
                    stop_on_ready.store(true, Ordering::Relaxed);
                    let _ = p_out.send_event(AppEvent::MinecraftMenuReady);
                } else if line.contains("LWJGL Version:") && !stop_on_ready.load(Ordering::Relaxed) {
                    let p_fallback = p_out.clone();
                    let flag_clone = Arc::clone(&stop_on_ready);
                    std::thread::spawn(move || {
                        std::thread::sleep(Duration::from_millis(650));
                        if !flag_clone.swap(true, Ordering::Relaxed) {
                            let _ = p_fallback.send_event(AppEvent::MinecraftMenuReady);
                        }
                    });
                }
                let _ = p_out.send_event(AppEvent::LogLine(line));
            }
        });
    }

    if let Some(stderr) = child.stderr.take() {
        let p_err = proxy.clone();
        std::thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                let _ = p_err.send_event(AppEvent::LogLine(format!("[STDERR] {line}")));
            }
        });
    }

    let status = child.wait().map_err(|e| e.to_string())?;
    stop_watcher.store(true, Ordering::Relaxed);
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Processus Minecraft terminé ({status})."
    )));
    let _ = proxy.send_event(AppEvent::GameRunning(false));
    Ok(())
}

pub fn prepare_prism_bridge_files(
    prefs: &LauncherPrefs,
    screen_w: u32,
    screen_h: u32,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<(String, Vec<PathBuf>), String> {
    if !Path::new(&prefs.prism_exe).is_file() {
        return Err(format!("PrismLauncher introuvable : {}", prefs.prism_exe));
    }

    auto_fetch_mod_jar_if_needed(prefs, proxy);

    let instance_id = resolve_prism_instance_id(prefs);
    let watched_dirs = collect_watched_189_game_dirs(&prefs.game_dir);

    let mut synced_any = false;
    let mut last_err = String::new();
    for gdir in &watched_dirs {
        let gdir_str = gdir.to_string_lossy();
        match sync_pureclient_jar(&gdir_str) {
            Ok(msg) => {
                if !synced_any {
                    let _ = proxy.send_event(AppEvent::LogLine(format!("[MashClient] {msg}")));
                }
                synced_any = true;
            }
            Err(e) => last_err = e,
        }
        sync_prism_instance_cfg(gdir, prefs.ram_mb, screen_w, screen_h);
        prepare_seamless_launch_files(&gdir_str);
    }

    if !synced_any && !last_err.is_empty() {
        return Err(last_err);
    }

    Ok((instance_id, watched_dirs))
}

#[cfg(windows)]
pub fn focus_visible_lwjgl_window() {
    let _ = detect_and_focus_new_lwjgl_window(0);
}

#[cfg(not(windows))]
pub fn focus_visible_lwjgl_window() {}

pub fn spawn_prism_bridge_now(
    prefs: &LauncherPrefs,
    instance_id: &str,
    watched_dirs: Vec<PathBuf>,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<(), String> {
    let pre_existing_lwjgl = current_visible_lwjgl_hwnd();

    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] PrismLauncher : lancement de '{}' ({} Mo RAM)...",
        instance_id, prefs.ram_mb
    )));

    let mut cmd = Command::new(&prefs.prism_exe);
    cmd.env("__GL_THREADED_OPTIMIZATIONS", "1")
        .env("SHIM_MCCOMPAT", "0x800000001")
        .arg("--launch")
        .arg(instance_id);
    if prefs.quick_join_hypixel {
        cmd.arg("--server").arg("mc.hypixel.net");
    }

    cmd.spawn()
        .map_err(|e| format!("Échec lancement PrismLauncher : {e}"))?;

    let _ = proxy.send_event(AppEvent::GameRunning(true));

    let stop_watcher = Arc::new(AtomicBool::new(false));
    spawn_display_ready_watcher(watched_dirs, proxy.clone(), stop_watcher);

    let p_life = proxy.clone();
    std::thread::spawn(move || {
        let start = Instant::now();
        let mut tracked_hwnd: usize = 0;

        while start.elapsed() < Duration::from_secs(36) {
            if let Some(hwnd) = detect_and_focus_new_lwjgl_window(pre_existing_lwjgl) {
                tracked_hwnd = hwnd;
                break;
            }
            let any = current_visible_lwjgl_hwnd();
            if any != 0 && any != pre_existing_lwjgl {
                tracked_hwnd = any;
                break;
            }
            std::thread::sleep(Duration::from_millis(250));
        }

        if tracked_hwnd != 0 {
            loop {
                std::thread::sleep(Duration::from_millis(1200));
                if current_visible_lwjgl_hwnd() == 0 {
                    break;
                }
            }
        }
        let _ = p_life.send_event(AppEvent::GameRunning(false));
    });

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_offline_uuid_rfc4122_v3() {
        let uuid = offline_uuid_hex("PurePlayer");
        assert_eq!(uuid.len(), 32);
        assert_eq!(&uuid[12..13], "3");
    }
}
