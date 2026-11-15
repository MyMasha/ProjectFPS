//! Exécuteur autonome JVM 1.8.9 + Forge 11.15.1.2318 + MashClient 4.5.1 (OpenGL & Vulkan Bêta).
//! - Synchronise automatiquement `purefps-4.5.1.jar` (et le télécharge depuis GitHub Releases s'il manque ou est obsolète)
//! - Extrait les DLL natives LWJGL 2.9.4 / JInput / Twitch 64-bit
//! - Isolat dédié pour `1.8.9 PureFPS Vulkan` (Bêta) via Mesa3D Zink (`javaw.exe.local` + `GALLIUM_DRIVER=zink`)
//! - Détecte instantanément l'ouverture de la fenêtre (`DISPLAY_CREATED` / `.purefps_display_ready`)
//!   pour réduire le Launcher en bas à droite (System Tray) à l'instant exact où Minecraft prend sa place.

use crate::config::{
    app_storage_dir, effective_github_token, is_real_prism_instance, prepare_seamless_launch_files,
    resolve_prism_instance_game_dir, split_server_host_port, write_or_clear_quick_server_file,
    InstanceFlavor, LauncherPrefs, PureClientSettings,
};
use crate::runtimes::{
    ensure_vulkan_instance_initialized, prepare_isolated_vulkan_jre, resolve_active_game_dir,
    PRISM_VULKAN_INSTANCE_NAME,
};
use crate::updater::{
    check_for_updates, download_and_install_update, extract_purefps_jar_semver,
    installed_mod_version, parse_semver, UpdateCheckOutcome, LAUNCHER_VERSION,
};
use crate::AppEvent;
use serde::Serialize;
use std::collections::VecDeque;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime};
use tao::event_loop::EventLoopProxy;

const FALLBACK_PURECLIENT_JAR_NAME: &str = "purefps-4.5.1.jar";

#[derive(Debug, Clone, Serialize)]
pub struct CrashReportInfo {
    pub title: String,
    pub summary: String,
    pub details: String,
    pub exit_code: Option<i32>,
    pub report_path: Option<String>,
    pub suspected_mod_file: Option<String>,
}

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

/// Si le mod `purefps-*.jar` est absent ou plus ancien que le Launcher dans l'instance active,
/// tente de le récupérer automatiquement depuis GitHub Releases avant le lancement !
pub fn auto_fetch_mod_jar_if_needed(
    prefs: &LauncherPrefs,
    target_game_dir: &str,
    proxy: &EventLoopProxy<AppEvent>,
) {
    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((4, 5, 0));
    let cur_mod = installed_mod_version(target_game_dir);
    if cur_mod.is_some_and(|v| v >= launcher_ver) {
        return;
    }

    let tok = effective_github_token();
    if let Ok(UpdateCheckOutcome::Available(mut plan)) =
        check_for_updates(&prefs.github_repo, tok.as_deref(), target_game_dir)
    {
        if plan.jar_asset.is_some() {
            plan.exe_asset = None;
            let _ = proxy.send_event(AppEvent::LogLine(format!(
                "[MashClient] Téléchargement automatique du mod MashClient v{}...",
                plan.version
            )));
            let _ = download_and_install_update(&plan, tok.as_deref(), target_game_dir, proxy);
        }
    }
}

pub fn sync_pureclient_jar(game_dir: &str) -> Result<String, String> {
    let mods_dir = Path::new(game_dir).join("mods");
    std::fs::create_dir_all(&mods_dir).map_err(|e| format!("Création dossier mods: {e}"))?;

    let mut search_dirs: Vec<PathBuf> = vec![app_storage_dir().join("updates"), mods_dir.clone()];
    // Permet aussi à l'instance `1.8.9 PureFPS Vulkan` de récupérer le jar moteur depuis l'instance sœur hors-ligne
    if let Some(instances_dir) = Path::new(game_dir).parent().and_then(|p| p.parent()) {
        if let Ok(entries) = std::fs::read_dir(instances_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_dir() {
                    let sib_mods = resolve_prism_instance_game_dir(&p).join("mods");
                    if sib_mods != mods_dir && sib_mods.is_dir() {
                        search_dirs.push(sib_mods);
                    }
                }
            }
        }
    }
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
        "Archive {FALLBACK_PURECLIENT_JAR_NAME} introuvable (lancez une mise à jour dans l'onglet Paramètres)."
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
    if prefs.instance_flavor == InstanceFlavor::VulkanBeta {
        return PRISM_VULKAN_INSTANCE_NAME.to_owned();
    }
    if let Some(inst_root) = Path::new(&prefs.game_dir).parent() {
        if is_real_prism_instance(inst_root) {
            if let Some(name) = inst_root.file_name() {
                let s = name.to_string_lossy().to_string();
                if !s.to_ascii_lowercase().contains("vulkan") {
                    return s;
                }
            }
        }
        if let Some(instances_dir) = inst_root.parent() {
            if let Ok(entries) = std::fs::read_dir(instances_dir) {
                for entry in entries.flatten() {
                    let p = entry.path();
                    if p.is_dir() && is_real_prism_instance(&p) {
                        let fname = entry.file_name().to_string_lossy().to_string();
                        if fname.to_ascii_lowercase().contains("vulkan") {
                            continue;
                        }
                        let mmc = std::fs::read_to_string(p.join("mmc-pack.json")).unwrap_or_default();
                        if mmc.contains("1.8.9") || fname.contains("1.8.9") {
                            return fname;
                        }
                    }
                }
            }
        }
    }
    "1.8.9".to_owned()
}

pub fn collect_watched_189_game_dirs(prefs: &LauncherPrefs, active_game_dir: &str) -> Vec<PathBuf> {
    let mut dirs = vec![PathBuf::from(active_game_dir)];
    if prefs.instance_flavor == InstanceFlavor::VulkanBeta {
        return dirs;
    }
    if let Some(instances_dir) = Path::new(active_game_dir)
        .parent()
        .and_then(|p| p.parent())
    {
        if let Ok(entries) = std::fs::read_dir(instances_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if !p.is_dir() || !is_real_prism_instance(&p) {
                    continue;
                }
                let fname = entry.file_name().to_string_lossy().to_string();
                if fname.to_ascii_lowercase().contains("vulkan") {
                    continue;
                }
                let mmc = std::fs::read_to_string(p.join("mmc-pack.json")).unwrap_or_default();
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

/// Étend d'1 pixel le côté externe sans moniteur adjacent afin d'empêcher Windows 10/11 et
/// nvoglv64.dll de basculer wglSwapBuffers en Legacy Exclusive Flip (qui fige les captures
/// Win+Shift+S sur une ancienne frame, masque le pointeur coloré Windows 11 et bloque les streams Discord),
/// tout en couvrant 100 % de l'écran principal pour masquer automatiquement la barre des tâches.
pub fn resolve_dwm_safe_borderless_size(
    screen_w: u32,
    screen_h: u32,
    virt_x: i32,
    virt_y: i32,
    virt_w: i32,
    virt_h: i32,
) -> (u32, u32) {
    let w = screen_w.max(854);
    let h = screen_h.max(480);
    if virt_w <= 0 || virt_h <= 0 {
        return (w, h + 1);
    }
    let virt_right = i64::from(virt_x) + i64::from(virt_w);
    let virt_bottom = i64::from(virt_y) + i64::from(virt_h);
    if virt_bottom <= i64::from(h) || virt_y >= 0 {
        (w, h + 1)
    } else if virt_right <= i64::from(w) || virt_x >= 0 {
        (w + 1, h)
    } else {
        (w, h + 1)
    }
}

#[cfg(windows)]
fn query_virtual_screen_metrics() -> (i32, i32, i32, i32) {
    #[link(name = "user32")]
    extern "system" {
        fn GetSystemMetrics(nIndex: i32) -> i32;
    }
    unsafe {
        (
            GetSystemMetrics(76),
            GetSystemMetrics(77),
            GetSystemMetrics(78),
            GetSystemMetrics(79),
        )
    }
}

#[cfg(not(windows))]
fn query_virtual_screen_metrics() -> (i32, i32, i32, i32) {
    (0, 0, 0, 0)
}

fn is_borderless_enabled_for_game_dir(game_dir: &Path) -> bool {
    let props = game_dir.join("config").join("purefps.properties");
    if let Ok(content) = std::fs::read_to_string(props) {
        for line in content.lines() {
            if let Some(rest) = line.trim().strip_prefix("borderlessFullscreen=") {
                return !rest.trim().eq_ignore_ascii_case("false");
            }
        }
    }
    true
}

fn compute_launch_window_dimensions(game_dir: &Path, screen_w: u32, screen_h: u32) -> (u32, u32) {
    if is_borderless_enabled_for_game_dir(game_dir) {
        let (vx, vy, vw, vh) = query_virtual_screen_metrics();
        resolve_dwm_safe_borderless_size(screen_w, screen_h, vx, vy, vw, vh)
    } else {
        (screen_w.max(854), screen_h.max(480))
    }
}

pub fn sync_prism_instance_cfg(
    instance_game_dir: &Path,
    ram_mb: u32,
    screen_w: u32,
    screen_h: u32,
    vulkan_javaw: Option<&Path>,
) {
    let Some(instance_root) = instance_game_dir.parent() else {
        return;
    };
    let cfg_path = instance_root.join("instance.cfg");
    let Ok(existing) = std::fs::read_to_string(&cfg_path) else {
        return;
    };

    let min_mem = ram_mb.min(8192);
    let (win_w, win_h) = compute_launch_window_dimensions(instance_game_dir, screen_w, screen_h);
    let vk_java_fwd = vulkan_javaw.map(|p| p.to_string_lossy().replace('\\', "/"));
    const HIGH_FPS_JVM_BASE: &str =
        "-XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:ConcGCThreads=4 -XX:+AlwaysPreTouch -XX:G1NewSizePercent=35 -XX:G1MaxNewSizePercent=60 -XX:G1ReservePercent=15 -XX:MaxGCPauseMillis=15 -XX:G1HeapRegionSize=32M -XX:MaxInlineLevel=15 -XX:MaxInlineSize=64 -XX:FreqInlineSize=512 -XX:AllocatePrefetchStyle=1 -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -Djava.net.preferIPv4Stack=true -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=false -Dorg.lwjgl.opengl.Window.undecorated=true -Dpurefps.seamlessLaunch=true";
    let target_jvm_args = if vk_java_fwd.is_some() {
        format!("{HIGH_FPS_JVM_BASE} -Dpurefps.rendererBackend=vulkan")
    } else {
        HIGH_FPS_JVM_BASE.to_owned()
    };
    const VK_ENV_INI: &str =
        "\"{\\\"GALLIUM_DRIVER\\\":\\\"zink\\\",\\\"MESA_GL_VERSION_OVERRIDE\\\":\\\"4.6COMPAT\\\",\\\"MESA_GLSL_VERSION_OVERRIDE\\\":\\\"460\\\",\\\"ZINK_DESCRIPTORS\\\":\\\"lazy\\\",\\\"vblank_mode\\\":\\\"0\\\",\\\"MESA_NO_ERROR\\\":\\\"1\\\"}\"";

    let has_custom_user_jvm_args = vk_java_fwd.is_none()
        && existing.lines().any(|l| {
            if let Some(rest) = l.strip_prefix("JvmArgs=") {
                let t = rest.trim();
                !t.is_empty() && !t.contains("-Dpurefps.")
            } else {
                false
            }
        });

    let mut updated_lines: Vec<String> = Vec::new();
    let mut saw_min = false;
    let mut saw_max = false;
    let mut saw_override_win = false;
    let mut saw_win_w = false;
    let mut saw_win_h = false;
    let mut saw_override_java = false;
    let mut saw_java_path = false;
    let mut saw_override_args = false;
    let mut saw_jvm_args = false;
    let mut saw_override_env = false;
    let mut saw_env = false;

    for line in existing.lines() {
        if line.starts_with("MinMemAlloc=") {
            updated_lines.push(format!("MinMemAlloc={min_mem}"));
            saw_min = true;
        } else if line.starts_with("MaxMemAlloc=") {
            updated_lines.push(format!("MaxMemAlloc={ram_mb}"));
            saw_max = true;
        } else if line.starts_with("OverrideWindow=") {
            updated_lines.push("OverrideWindow=true".to_owned());
            saw_override_win = true;
        } else if line.starts_with("WindowWidth=") {
            updated_lines.push(format!("WindowWidth={win_w}"));
            saw_win_w = true;
        } else if line.starts_with("WindowHeight=") {
            updated_lines.push(format!("WindowHeight={win_h}"));
            saw_win_h = true;
        } else if !has_custom_user_jvm_args && line.starts_with("OverrideJavaArgs=") {
            updated_lines.push("OverrideJavaArgs=true".to_owned());
            saw_override_args = true;
        } else if !has_custom_user_jvm_args && line.starts_with("JvmArgs=") {
            updated_lines.push(format!("JvmArgs={target_jvm_args}"));
            saw_jvm_args = true;
        } else if vk_java_fwd.is_some() && line.starts_with("OverrideJavaLocation=") {
            updated_lines.push("OverrideJavaLocation=true".to_owned());
            saw_override_java = true;
        } else if let Some(ref jp) = vk_java_fwd {
            if line.starts_with("JavaPath=") {
                updated_lines.push(format!("JavaPath={jp}"));
                saw_java_path = true;
            } else if line.starts_with("OverrideEnv=") {
                updated_lines.push("OverrideEnv=true".to_owned());
                saw_override_env = true;
            } else if line.starts_with("Env=") {
                updated_lines.push(format!("Env={VK_ENV_INI}"));
                saw_env = true;
            } else {
                updated_lines.push(line.to_owned());
            }
        } else {
            updated_lines.push(line.to_owned());
        }
    }
    if !saw_min {
        updated_lines.push(format!("MinMemAlloc={min_mem}"));
    }
    if !saw_max {
        updated_lines.push(format!("MaxMemAlloc={ram_mb}"));
    }
    if !saw_override_win {
        updated_lines.push("OverrideWindow=true".to_owned());
    }
    if !saw_win_w {
        updated_lines.push(format!("WindowWidth={win_w}"));
    }
    if !saw_win_h {
        updated_lines.push(format!("WindowHeight={win_h}"));
    }
    if !has_custom_user_jvm_args {
        if !saw_override_args {
            updated_lines.push("OverrideJavaArgs=true".to_owned());
        }
        if !saw_jvm_args {
            updated_lines.push(format!("JvmArgs={target_jvm_args}"));
        }
    }
    if let Some(ref jp) = vk_java_fwd {
        if !saw_override_java {
            updated_lines.push("OverrideJavaLocation=true".to_owned());
        }
        if !saw_java_path {
            updated_lines.push(format!("JavaPath={jp}"));
        }
        if !saw_override_env {
            updated_lines.push("OverrideEnv=true".to_owned());
        }
        if !saw_env {
            updated_lines.push(format!("Env={VK_ENV_INI}"));
        }
    }
    let _ = std::fs::write(&cfg_path, updated_lines.join("\n") + "\n");
}

#[cfg(windows)]
fn detect_new_lwjgl_window(existing_hwnd: usize) -> Option<usize> {
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
                return Some(addr);
            }
        }
    }
    None
}

#[cfg(windows)]
fn promote_and_focus_lwjgl_window(existing_hwnd: usize) -> Option<usize> {
    use core::ffi::c_void;
    const GCLP_HCURSOR: i32 = -12;
    const IDC_ARROW_ID: usize = 32512;
    const HWND_TOPMOST: *mut c_void = -1isize as *mut c_void;
    const HWND_NOTOPMOST: *mut c_void = -2isize as *mut c_void;
    const SWP_NOMOVE: u32 = 0x0002;
    const SWP_NOSIZE: u32 = 0x0001;
    const SWP_SHOWWINDOW: u32 = 0x0040;

    #[link(name = "user32")]
    extern "system" {
        fn FindWindowExW(
            hWndParent: *mut c_void,
            hWndChildAfter: *mut c_void,
            lpszClass: *const u16,
            lpszWindow: *const u16,
        ) -> *mut c_void;
        fn IsWindowVisible(hWnd: *mut c_void) -> i32;
        fn GetForegroundWindow() -> *mut c_void;
        fn GetWindowThreadProcessId(hWnd: *mut c_void, lpdwProcessId: *mut u32) -> u32;
        fn AttachThreadInput(idAttach: u32, idAttachTo: u32, fAttach: i32) -> i32;
        fn SetWindowPos(
            hWnd: *mut c_void,
            hWndInsertAfter: *mut c_void,
            X: i32,
            Y: i32,
            cx: i32,
            cy: i32,
            uFlags: u32,
        ) -> i32;
        fn BringWindowToTop(hWnd: *mut c_void) -> i32;
        fn SetForegroundWindow(hWnd: *mut c_void) -> i32;
        fn SetFocus(hWnd: *mut c_void) -> *mut c_void;
        fn LoadCursorW(hInstance: *mut c_void, lpCursorName: *const u16) -> *mut c_void;
        fn SetCursor(hCursor: *mut c_void) -> *mut c_void;
        fn SetClassLongPtrW(hWnd: *mut c_void, nIndex: i32, dwNewLong: isize) -> usize;
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn GetCurrentThreadId() -> u32;
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
                let fg = GetForegroundWindow();
                let cur_tid = GetCurrentThreadId();
                let fg_tid = if !fg.is_null() {
                    GetWindowThreadProcessId(fg, core::ptr::null_mut())
                } else {
                    0
                };
                let target_tid = GetWindowThreadProcessId(cur, core::ptr::null_mut());

                if fg_tid != 0 && fg_tid != cur_tid {
                    let _ = AttachThreadInput(cur_tid, fg_tid, 1);
                }
                if target_tid != 0 && target_tid != cur_tid {
                    let _ = AttachThreadInput(cur_tid, target_tid, 1);
                }

                let arrow = LoadCursorW(core::ptr::null_mut(), IDC_ARROW_ID as *const u16);
                if !arrow.is_null() {
                    let _ = SetClassLongPtrW(cur, GCLP_HCURSOR, arrow as isize);
                    let _ = SetCursor(arrow);
                }

                let flags = SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW;
                let _ = SetWindowPos(cur, HWND_TOPMOST, 0, 0, 0, 0, flags);
                let _ = SetWindowPos(cur, HWND_NOTOPMOST, 0, 0, 0, 0, flags);
                let _ = BringWindowToTop(cur);
                let _ = SetForegroundWindow(cur);
                let _ = SetFocus(cur);

                if target_tid != 0 && target_tid != cur_tid {
                    let _ = AttachThreadInput(cur_tid, target_tid, 0);
                }
                if fg_tid != 0 && fg_tid != cur_tid {
                    let _ = AttachThreadInput(cur_tid, fg_tid, 0);
                }
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
fn detect_new_lwjgl_window(_existing_hwnd: usize) -> Option<usize> {
    None
}

pub fn has_visible_lwjgl_window() -> bool {
    current_visible_lwjgl_hwnd() != 0
}

fn spawn_display_ready_watcher(
    watched_game_dirs: Vec<PathBuf>,
    proxy: EventLoopProxy<AppEvent>,
    stop_flag: Arc<AtomicBool>,
) {
    let pre_existing_lwjgl = current_visible_lwjgl_hwnd();
    std::thread::spawn(move || {
        let start = Instant::now();
        let timeout = Duration::from_secs(28);
        while !stop_flag.load(Ordering::Relaxed) && start.elapsed() < timeout {
            for dir in &watched_game_dirs {
                let cfg = dir.join("config");
                let menu_ready = cfg.join(".purefps_menu_ready");
                if menu_ready.is_file() {
                    let _ = std::fs::remove_file(cfg.join(".purefps_display_ready"));
                    let _ = std::fs::remove_file(&menu_ready);
                    stop_flag.store(true, Ordering::Relaxed);
                    let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                    return;
                }
            }
            // Fallback tardif (14s) uniquement si une vraie nouvelle fenêtre LWJGL est visible
            if start.elapsed() >= Duration::from_secs(14)
                && detect_new_lwjgl_window(pre_existing_lwjgl).is_some()
            {
                stop_flag.store(true, Ordering::Relaxed);
                let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        if !stop_flag.load(Ordering::Relaxed)
            && detect_new_lwjgl_window(pre_existing_lwjgl).is_some()
        {
            let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
        }
    });
}

fn read_file_tail_lossy(path: &Path, max_bytes: u64) -> Option<String> {
    let mut f = File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    if len > max_bytes {
        let _ = f.seek(SeekFrom::Start(len - max_bytes));
    }
    let mut buf = Vec::with_capacity(len.min(max_bytes) as usize);
    f.read_to_end(&mut buf).ok()?;
    Some(String::from_utf8_lossy(&buf).into_owned())
}

fn read_file_head_lossy(path: &Path, max_bytes: usize) -> Option<String> {
    let mut f = File::open(path).ok()?;
    let mut buf = vec![0u8; max_bytes];
    let n = f.read(&mut buf).ok()?;
    buf.truncate(n);
    Some(String::from_utf8_lossy(&buf).into_owned())
}

fn extract_crash_summary_from_report(text: &str) -> String {
    for line in text.lines() {
        let t = line.trim();
        if let Some(desc) = t.strip_prefix("Description:") {
            let d = desc.trim();
            if !d.is_empty() {
                return d.to_owned();
            }
        }
        if let Some(frame) = t.strip_prefix("# Problematic frame:") {
            let f = frame.trim();
            if !f.is_empty() {
                return format!("Crash natif JVM ({f})");
            }
        }
    }
    for line in text.lines() {
        let t = line.trim();
        if (t.contains("Exception") || t.contains("Error")) && !t.starts_with("at ") {
            return t.chars().take(180).collect();
        }
    }
    "Minecraft s'est arrêté suite à une erreur inattendue.".to_owned()
}

fn find_newest_matching_file(
    dir: &Path,
    cutoff: SystemTime,
    predicate: impl Fn(&str) -> bool,
) -> Option<(SystemTime, PathBuf)> {
    let entries = std::fs::read_dir(dir).ok()?;
    let mut newest: Option<(SystemTime, PathBuf)> = None;
    for entry in entries.flatten() {
        let p = entry.path();
        if !p.is_file() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        if !predicate(&name) {
            continue;
        }
        let Ok(mtime) = entry.metadata().and_then(|m| m.modified()) else {
            continue;
        };
        if mtime < cutoff {
            continue;
        }
        if newest.as_ref().is_none_or(|(cur_t, _)| mtime > *cur_t) {
            newest = Some((mtime, p));
        }
    }
    newest
}

const FATAL_LOG_MARKERS: &[&str] = &[
    "---- Minecraft Crash Report ----",
    "#@!@# Game crashed!",
    "MixinTransformerError",
    "InvalidInjectionException",
    "MixinApplyError",
    "Unable to launch",
    "Pixel format not accelerated",
    "java.lang.OutOfMemoryError",
    "Exception in thread \"main\"",
    "Exception in thread \"Client thread\"",
    "A fatal error has been detected by the Java Runtime Environment",
];

pub fn detect_recent_crash_in_dirs(
    dirs: &[PathBuf],
    since: SystemTime,
    console_tail: &[String],
    exit_code: Option<i32>,
) -> Option<CrashReportInfo> {
    let cutoff = since.checked_sub(Duration::from_secs(2)).unwrap_or(since);

    // 1. Rapports officiels Minecraft (`crash-reports/crash-*.txt`) et JVM (`hs_err_pid*.log`)
    let mut newest_report: Option<(SystemTime, PathBuf)> = None;
    for dir in dirs {
        if let Some(cand) = find_newest_matching_file(&dir.join("crash-reports"), cutoff, |n| {
            n.starts_with("crash-") && n.ends_with(".txt")
        }) {
            if newest_report.as_ref().is_none_or(|(t, _)| cand.0 > *t) {
                newest_report = Some(cand);
            }
        }
        if let Some(cand) = find_newest_matching_file(dir, cutoff, |n| {
            n.starts_with("hs_err_pid") && n.ends_with(".log")
        }) {
            if newest_report.as_ref().is_none_or(|(t, _)| cand.0 > *t) {
                newest_report = Some(cand);
            }
        }
    }

    if let Some((_, report_path)) = newest_report {
        if let Some(text) = read_file_head_lossy(&report_path, 24_576) {
            let summary = extract_crash_summary_from_report(&text);
            let suspected_mod_file = dirs.iter().find_map(|d| {
                crate::mods_manager::identify_suspected_crash_mod(&d.to_string_lossy(), &text)
            });
            return Some(CrashReportInfo {
                title: "Minecraft a planté".to_owned(),
                summary,
                details: text,
                exit_code,
                report_path: Some(report_path.to_string_lossy().to_string()),
                suspected_mod_file,
            });
        }
    }

    // 2. Journaux Forge / Minecraft (`logs/fml-client-latest.log`, `logs/latest.log`)
    for dir in dirs {
        for rel in ["fml-client-latest.log", "latest.log"] {
            let log_path = dir.join("logs").join(rel);
            let Ok(mtime) = std::fs::metadata(&log_path).and_then(|m| m.modified()) else {
                continue;
            };
            if mtime < cutoff {
                continue;
            }
            let Some(tail_text) = read_file_tail_lossy(&log_path, 32_768) else {
                continue;
            };
            let lines: Vec<&str> = tail_text.lines().collect();
            let hit_idx = lines
                .iter()
                .position(|line| FATAL_LOG_MARKERS.iter().any(|m| line.contains(m)));
            if let Some(idx) = hit_idx {
                let start_line = idx.saturating_sub(4);
                let end_line = (idx + 75).min(lines.len());
                let snippet = lines[start_line..end_line].join("\n");
                let summary = lines[idx..]
                    .iter()
                    .map(|l| l.trim())
                    .find(|l| {
                        l.starts_with("Description:")
                            || l.contains("Exception")
                            || l.contains("Error")
                            || l.contains("Unable to launch")
                            || l.contains("Pixel format not accelerated")
                    })
                    .map(|s| {
                        s.strip_prefix("Description:")
                            .map(str::trim)
                            .filter(|d| !d.is_empty())
                            .unwrap_or(s)
                            .chars()
                            .take(180)
                            .collect::<String>()
                    })
                    .unwrap_or_else(|| {
                        "Erreur critique détectée pendant le chargement de Forge 1.8.9.".to_owned()
                    });

                let suspected_mod_file = crate::mods_manager::identify_suspected_crash_mod(
                    &dir.to_string_lossy(),
                    &tail_text,
                );
                return Some(CrashReportInfo {
                    title: "Erreur au lancement de Minecraft".to_owned(),
                    summary,
                    details: snippet,
                    exit_code,
                    report_path: Some(log_path.to_string_lossy().to_string()),
                    suspected_mod_file,
                });
            }
        }
    }

    // 3. Code de sortie JVM anormal ou trace fatale sur stdout/stderr
    let fatal_in_console = console_tail
        .iter()
        .any(|line| FATAL_LOG_MARKERS.iter().any(|m| line.contains(m)));
    if exit_code.is_some_and(|c| c != 0) || fatal_in_console {
        let code_str = exit_code
            .map(|c| format!(" (code {c})"))
            .unwrap_or_default();
        let summary = console_tail
            .iter()
            .rev()
            .map(|l| l.trim())
            .find(|l| {
                (l.contains("Exception")
                    || l.contains("Error")
                    || l.contains("Invalid")
                    || l.contains("Could not create the Java Virtual Machine")
                    || l.contains("Unrecognized VM option"))
                    && !l.starts_with("at ")
                    && !l.starts_with("[STDERR] \tat ")
            })
            .map(|s| {
                s.strip_prefix("[STDERR] ")
                    .unwrap_or(s)
                    .chars()
                    .take(180)
                    .collect::<String>()
            })
            .unwrap_or_else(|| {
                format!("Le processus Minecraft s'est arrêté de manière inattendue{code_str}.")
            });

        let start = console_tail.len().saturating_sub(90);
        let details = if console_tail.is_empty() {
            summary.clone()
        } else {
            console_tail[start..].join("\n")
        };

        let fallback_log = dirs
            .first()
            .map(|d| d.join("logs").join("latest.log"))
            .filter(|p| p.is_file())
            .map(|p| p.to_string_lossy().to_string());

        let suspected_mod_file = dirs.iter().find_map(|d| {
            crate::mods_manager::identify_suspected_crash_mod(&d.to_string_lossy(), &details)
        });

        return Some(CrashReportInfo {
            title: "Minecraft s'est fermé avec une erreur".to_owned(),
            summary,
            details,
            exit_code,
            report_path: fallback_log,
            suspected_mod_file,
        });
    }

    None
}

pub fn build_low_latency_alsoft_ini() -> &'static str {
    "# MashClient 4.5.1 — Profil OpenAL Soft WASAPI Basse Latence\n\
[general]\n\
drivers = mmdevapi,dsound\n\
periods = 3\n\
period_size = 320\n\
sources = 256\n\
slots = 4\n\
cf_level = 0\n"
}

fn ensure_low_latency_alsoft_ini(game_dir: &Path) -> Option<PathBuf> {
    let content = build_low_latency_alsoft_ini();
    let cfg_dir = game_dir.join("config");
    let _ = std::fs::create_dir_all(&cfg_dir);
    let game_ini = cfg_dir.join("mashclient_alsoft.ini");
    if std::fs::write(&game_ini, content).is_ok() {
        return Some(game_ini);
    }
    let storage = app_storage_dir();
    let _ = std::fs::create_dir_all(&storage);
    let fallback_ini = storage.join("mashclient_alsoft.ini");
    std::fs::write(&fallback_ini, content).ok()?;
    Some(fallback_ini)
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

    let is_vulkan = prefs.instance_flavor == InstanceFlavor::VulkanBeta;
    let active_game_dir = if is_vulkan {
        ensure_vulkan_instance_initialized(&prefs)?
    } else {
        prefs.game_dir.clone()
    };

    let effective_java: PathBuf = if is_vulkan {
        prepare_isolated_vulkan_jre(Path::new(&prefs.java_path), &prefs, &proxy)?
    } else {
        PathBuf::from(&prefs.java_path)
    };

    auto_fetch_mod_jar_if_needed(&prefs, &active_game_dir, &proxy);

    let jar_msg = sync_pureclient_jar(&active_game_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!("[MashClient] {jar_msg}")));

    prepare_seamless_launch_files(&active_game_dir);

    let client_settings = PureClientSettings::load_from_game_dir(&active_game_dir);
    let use_quick_bridge_file = client_settings.enabled && client_settings.custom_main_menu;
    if use_quick_bridge_file {
        write_or_clear_quick_server_file(&active_game_dir, prefs.selected_server.as_ref());
    } else {
        write_or_clear_quick_server_file(&active_game_dir, None);
    }

    let natives_dir = extract_natives_if_needed(&prefs.libraries_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Natives 64-bit prêtes : {}",
        natives_dir.display()
    )));

    let classpath = build_classpath(&prefs.libraries_dir)?;
    let game_dir_path = Path::new(&active_game_dir);
    let alsoft_ini = ensure_low_latency_alsoft_ini(game_dir_path);
    let (win_w, win_h) = compute_launch_window_dimensions(game_dir_path, screen_w, screen_h);
    let flavor_tag = if is_vulkan { "Vulkan Bêta" } else { "OpenGL" };
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Démarrage de Minecraft 1.8.9 [{}] ({} Mo, {}x{}, {})...",
        flavor_tag, prefs.ram_mb, screen_w, screen_h, username
    )));

    let min_mem_mb = prefs.ram_mb.min(8192);
    let mut cmd = Command::new(&effective_java);
    cmd.current_dir(&active_game_dir)
        .env_remove("SHIM_MCCOMPAT")
        .env("__GL_THREADED_OPTIMIZATIONS", "0")
        .env("__GL_YIELD", "USLEEP")
        .env("ALSOFT_DRIVERS", "mmdevapi,dsound");
    if is_vulkan {
        cmd.env("GALLIUM_DRIVER", "zink")
            .env("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT")
            .env("MESA_GLSL_VERSION_OVERRIDE", "460")
            .env("ZINK_DESCRIPTORS", "lazy")
            .env("vblank_mode", "0")
            .env("MESA_NO_ERROR", "1");
    }
    if let Some(ref ini_path) = alsoft_ini {
        cmd.env("ALSOFT_CONF", ini_path);
    }
    cmd.arg(format!("-Xms{}M", min_mem_mb))
        .arg(format!("-Xmx{}M", prefs.ram_mb))
        .arg("-XX:+IgnoreUnrecognizedVMOptions")
        .arg("-XX:+UnlockExperimentalVMOptions")
        .arg("-XX:+UseG1GC")
        .arg("-XX:ConcGCThreads=4")
        .arg("-XX:+AlwaysPreTouch")
        .arg("-XX:G1NewSizePercent=35")
        .arg("-XX:G1MaxNewSizePercent=60")
        .arg("-XX:G1ReservePercent=15")
        .arg("-XX:MaxGCPauseMillis=15")
        .arg("-XX:G1HeapRegionSize=32M")
        .arg("-XX:MaxInlineLevel=15")
        .arg("-XX:MaxInlineSize=64")
        .arg("-XX:FreqInlineSize=512")
        .arg("-XX:AllocatePrefetchStyle=1")
        .arg("-XX:+DisableExplicitGC")
        .arg("-XX:+ParallelRefProcEnabled")
        .arg("-Djava.net.preferIPv4Stack=true")
        .arg("-Dfml.ignoreInvalidMinecraftCertificates=true")
        .arg("-Dfml.ignorePatchDiscrepancies=true")
        .arg("-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=false")
        .arg("-Dorg.lwjgl.opengl.Window.undecorated=true")
        .arg("-Dpurefps.seamlessLaunch=true");
    if is_vulkan {
        cmd.arg("-Dpurefps.rendererBackend=vulkan");
    }
    cmd.arg(format!("-Djava.library.path={}", natives_dir.display()))
        .arg("-cp")
        .arg(classpath)
        .arg("net.minecraft.launchwrapper.Launch")
        .arg("--username")
        .arg(&username)
        .arg("--version")
        .arg("1.8.9")
        .arg("--gameDir")
        .arg(&active_game_dir)
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
        .arg(win_w.to_string())
        .arg("--height")
        .arg(win_h.to_string())
        .arg("--tweakClass")
        .arg("net.minecraftforge.fml.common.launcher.FMLTweaker");

    if !use_quick_bridge_file {
        if let Some(ref srv) = prefs.selected_server {
            let (host, port) = split_server_host_port(&srv.address);
            if !host.is_empty() {
                cmd.arg("--server").arg(host).arg("--port").arg(port.to_string());
            }
        }
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());

    let launch_time = SystemTime::now();
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("Impossible de lancer javaw.exe : {e}"))?;

    let _ = proxy.send_event(AppEvent::GameRunning(true));

    let stop_watcher = Arc::new(AtomicBool::new(false));
    spawn_display_ready_watcher(
        vec![PathBuf::from(&active_game_dir)],
        proxy.clone(),
        Arc::clone(&stop_watcher),
    );

    let console_tail: Arc<Mutex<VecDeque<String>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(180)));

    let out_handle = child.stdout.take().map(|stdout| {
        let p_out = proxy.clone();
        let stop_on_ready = Arc::clone(&stop_watcher);
        let tail_out = Arc::clone(&console_tail);
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if line.contains("[PureClient-Ready] MENU_ACTIVE") {
                    stop_on_ready.store(true, Ordering::Relaxed);
                    let _ = p_out.send_event(AppEvent::MinecraftMenuReady);
                }
                if let Ok(mut guard) = tail_out.lock() {
                    if guard.len() >= 180 {
                        guard.pop_front();
                    }
                    guard.push_back(line.clone());
                }
                let _ = p_out.send_event(AppEvent::LogLine(line));
            }
        })
    });

    let err_handle = child.stderr.take().map(|stderr| {
        let p_err = proxy.clone();
        let tail_err = Arc::clone(&console_tail);
        std::thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                let tagged = format!("[STDERR] {line}");
                if let Ok(mut guard) = tail_err.lock() {
                    if guard.len() >= 180 {
                        guard.pop_front();
                    }
                    guard.push_back(tagged.clone());
                }
                let _ = p_err.send_event(AppEvent::LogLine(tagged));
            }
        })
    });

    let status = child.wait().map_err(|e| e.to_string())?;
    stop_watcher.store(true, Ordering::Relaxed);
    if let Some(h) = out_handle {
        let _ = h.join();
    }
    if let Some(h) = err_handle {
        let _ = h.join();
    }

    let exit_code = status.code().or(if status.success() { Some(0) } else { Some(-1) });
    if !status.success() {
        std::thread::sleep(Duration::from_millis(120));
    }

    let tail_lines: Vec<String> = console_tail
        .lock()
        .map(|g| g.iter().cloned().collect())
        .unwrap_or_default();

    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Processus Minecraft terminé ({status})."
    )));

    if let Some(crash_info) = detect_recent_crash_in_dirs(
        &[PathBuf::from(&active_game_dir)],
        launch_time,
        &tail_lines,
        if status.success() { None } else { exit_code },
    ) {
        let _ = proxy.send_event(AppEvent::GameCrashed(crash_info));
    } else {
        let _ = proxy.send_event(AppEvent::GameRunning(false));
    }
    Ok(())
}

pub fn sync_configs_across_watched_189_dirs(prefs: &LauncherPrefs, primary_game_dir: &str) {
    if prefs.instance_flavor == InstanceFlavor::VulkanBeta {
        return;
    }
    let primary_cfg = Path::new(primary_game_dir).join("config");
    let src_props = primary_cfg.join("purefps.properties");
    let src_mods = primary_cfg.join("mashclient_modules.json");
    for gdir in collect_watched_189_game_dirs(prefs, primary_game_dir) {
        if gdir == Path::new(primary_game_dir) {
            continue;
        }
        let dst_cfg = gdir.join("config");
        let _ = std::fs::create_dir_all(&dst_cfg);
        if src_props.is_file() {
            let _ = std::fs::copy(&src_props, dst_cfg.join("purefps.properties"));
        }
        if src_mods.is_file() {
            let _ = std::fs::copy(&src_mods, dst_cfg.join("mashclient_modules.json"));
        }
    }
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

    let is_vulkan = prefs.instance_flavor == InstanceFlavor::VulkanBeta;
    let active_game_dir = if is_vulkan {
        ensure_vulkan_instance_initialized(prefs)?
    } else {
        prefs.game_dir.clone()
    };

    let vulkan_javaw: Option<PathBuf> = if is_vulkan {
        Some(prepare_isolated_vulkan_jre(
            Path::new(&prefs.java_path),
            prefs,
            proxy,
        )?)
    } else {
        None
    };

    auto_fetch_mod_jar_if_needed(prefs, &active_game_dir, proxy);

    let instance_id = resolve_prism_instance_id(prefs);
    let watched_dirs = collect_watched_189_game_dirs(prefs, &active_game_dir);
    sync_configs_across_watched_189_dirs(prefs, &active_game_dir);

    let client_settings = PureClientSettings::load_from_game_dir(&active_game_dir);
    let use_quick_bridge_file = client_settings.enabled && client_settings.custom_main_menu;

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
        sync_prism_instance_cfg(
            gdir,
            prefs.ram_mb,
            screen_w,
            screen_h,
            vulkan_javaw.as_deref(),
        );
        let _ = ensure_low_latency_alsoft_ini(gdir);
        prepare_seamless_launch_files(&gdir_str);
        if use_quick_bridge_file {
            write_or_clear_quick_server_file(&gdir_str, prefs.selected_server.as_ref());
        } else {
            write_or_clear_quick_server_file(&gdir_str, None);
        }
    }

    if !synced_any && !last_err.is_empty() {
        return Err(last_err);
    }

    Ok((instance_id, watched_dirs))
}

#[cfg(windows)]
pub fn focus_visible_lwjgl_window() {
    let _ = promote_and_focus_lwjgl_window(0);
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
    let launch_time = SystemTime::now();
    let is_vulkan = prefs.instance_flavor == InstanceFlavor::VulkanBeta;

    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] PrismLauncher : lancement de '{}' ({} Mo RAM)...",
        instance_id, prefs.ram_mb
    )));

    let active_dir = resolve_active_game_dir(prefs);
    let client_settings = PureClientSettings::load_from_game_dir(&active_dir);
    let use_quick_bridge_file = client_settings.enabled && client_settings.custom_main_menu;

    let alsoft_ini = watched_dirs
        .first()
        .and_then(|d| ensure_low_latency_alsoft_ini(d))
        .or_else(|| ensure_low_latency_alsoft_ini(Path::new(&active_dir)));

    let mut cmd = Command::new(&prefs.prism_exe);
    cmd.env_remove("SHIM_MCCOMPAT")
        .env("__GL_THREADED_OPTIMIZATIONS", "0")
        .env("__GL_YIELD", "USLEEP")
        .env("ALSOFT_DRIVERS", "mmdevapi,dsound");
    if is_vulkan {
        cmd.env("GALLIUM_DRIVER", "zink")
            .env("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT")
            .env("MESA_GLSL_VERSION_OVERRIDE", "460")
            .env("ZINK_DESCRIPTORS", "lazy")
            .env("vblank_mode", "0")
            .env("MESA_NO_ERROR", "1");
    }
    if let Some(ref ini_path) = alsoft_ini {
        cmd.env("ALSOFT_CONF", ini_path);
    }
    cmd.arg("--launch").arg(instance_id);
    if !use_quick_bridge_file {
        if let Some(ref srv) = prefs.selected_server {
            if !srv.address.trim().is_empty() {
                cmd.arg("--server").arg(srv.address.trim());
            }
        }
    }

    let mut prism_child = cmd
        .spawn()
        .map_err(|e| format!("Échec lancement PrismLauncher : {e}"))?;

    let _ = proxy.send_event(AppEvent::GameRunning(true));

    let stop_watcher = Arc::new(AtomicBool::new(false));
    let crash_dirs = watched_dirs.clone();
    spawn_display_ready_watcher(watched_dirs, proxy.clone(), Arc::clone(&stop_watcher));

    let inst_label = instance_id.to_owned();
    let p_life = proxy.clone();
    std::thread::spawn(move || {
        let start = Instant::now();
        let mut tracked_hwnd: usize = 0;

        while start.elapsed() < Duration::from_secs(34) {
            if let Some(hwnd) = detect_new_lwjgl_window(pre_existing_lwjgl) {
                tracked_hwnd = hwnd;
                break;
            }
            let any = current_visible_lwjgl_hwnd();
            if any != 0 && any != pre_existing_lwjgl {
                tracked_hwnd = any;
                break;
            }

            if let Some(crash_info) =
                detect_recent_crash_in_dirs(&crash_dirs, launch_time, &[], None)
            {
                stop_watcher.store(true, Ordering::Relaxed);
                let _ = p_life.send_event(AppEvent::GameCrashed(crash_info));
                return;
            }

            if let Ok(Some(exit_status)) = prism_child.try_wait() {
                if !exit_status.success() {
                    std::thread::sleep(Duration::from_millis(200));
                    stop_watcher.store(true, Ordering::Relaxed);
                    let code = exit_status.code();
                    let info = detect_recent_crash_in_dirs(&crash_dirs, launch_time, &[], code)
                        .unwrap_or_else(|| CrashReportInfo {
                            title: "Échec du lancement via PrismLauncher".to_owned(),
                            summary: format!(
                                "PrismLauncher s'est arrêté avant l'ouverture de l'instance '{inst_label}'."
                            ),
                            details: format!(
                                "Instance Prism : {inst_label}\nStatut de sortie : {exit_status}\nVérifiez que l'instance 1.8.9 existe et qu'un compte est sélectionné dans PrismLauncher."
                            ),
                            exit_code: code,
                            report_path: None,
                            suspected_mod_file: None,
                        });
                    let _ = p_life.send_event(AppEvent::GameCrashed(info));
                    return;
                }
            }

            std::thread::sleep(Duration::from_millis(300));
        }

        if tracked_hwnd != 0 {
            loop {
                std::thread::sleep(Duration::from_millis(1000));
                if current_visible_lwjgl_hwnd() == 0 {
                    break;
                }
            }
            std::thread::sleep(Duration::from_millis(250));
            if let Some(crash_info) =
                detect_recent_crash_in_dirs(&crash_dirs, launch_time, &[], None)
            {
                let _ = p_life.send_event(AppEvent::GameCrashed(crash_info));
                return;
            }
            let _ = p_life.send_event(AppEvent::GameRunning(false));
        } else {
            stop_watcher.store(true, Ordering::Relaxed);
            let fallback_log = crash_dirs
                .first()
                .map(|d| d.join("logs").join("latest.log"))
                .filter(|p| p.is_file())
                .map(|p| p.to_string_lossy().to_string());
            let info = detect_recent_crash_in_dirs(&crash_dirs, launch_time, &[], None)
                .unwrap_or_else(|| CrashReportInfo {
                    title: "Minecraft ne s'est pas ouvert".to_owned(),
                    summary: format!(
                        "Aucune fenêtre Minecraft 1.8.9 détectée après le lancement de '{inst_label}'."
                    ),
                    details: format!(
                        "Instance ciblée : {inst_label}\nMode : PrismLauncher Bridge\n\nCauses possibles :\n- Aucun compte actif sélectionné dans PrismLauncher\n- Chemin Java 8 invalide dans la configuration de l'instance\n- Conflit avec un autre mod dans le dossier mods/"
                    ),
                    exit_code: None,
                    report_path: fallback_log,
                    suspected_mod_file: None,
                });
            let _ = p_life.send_event(AppEvent::GameCrashed(info));
        }
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

    #[test]
    fn test_dwm_safe_borderless_size_prevents_equal_rect_flip() {
        assert_eq!(
            resolve_dwm_safe_borderless_size(1920, 1080, 0, 0, 1920, 1080),
            (1920, 1081)
        );
        assert_eq!(
            resolve_dwm_safe_borderless_size(1920, 1080, 0, 0, 3840, 1080),
            (1920, 1081)
        );
        assert_eq!(
            resolve_dwm_safe_borderless_size(1920, 1080, 0, 0, 1920, 2160),
            (1920, 1081)
        );
        assert_eq!(
            resolve_dwm_safe_borderless_size(1920, 1080, 0, -1080, 1920, 3240),
            (1921, 1080)
        );
    }

    #[test]
    fn test_low_latency_alsoft_ini_profile() {
        let ini = build_low_latency_alsoft_ini();
        assert!(ini.contains("[general]"));
        assert!(ini.contains("drivers = mmdevapi,dsound"));
        assert!(ini.contains("periods = 3"));
        assert!(ini.contains("period_size = 320"));
        assert!(ini.contains("sources = 256"));
    }

    #[test]
    fn test_sync_prism_instance_cfg_supports_50gb_heap_and_vulkan_env() {
        let tmp = std::env::temp_dir().join("mashclient_prism_50gb_test");
        let _ = std::fs::remove_dir_all(&tmp);
        let mc_dir = tmp.join("minecraft");
        std::fs::create_dir_all(&mc_dir).unwrap();
        std::fs::write(tmp.join("instance.cfg"), "InstanceType=OneSix\n").unwrap();

        let vk_java = Path::new(r"C:\MashClient\runtimes\vulkan-jre8\bin\javaw.exe");
        sync_prism_instance_cfg(&mc_dir, 51_200, 1920, 1080, Some(vk_java));

        let synced = std::fs::read_to_string(tmp.join("instance.cfg")).unwrap();
        assert!(synced.contains("MinMemAlloc=8192"));
        assert!(synced.contains("MaxMemAlloc=51200"));
        assert!(synced.contains("-XX:FreqInlineSize=512"));
        assert!(synced.contains("-Dpurefps.rendererBackend=vulkan"));
        assert!(synced.contains("vblank_mode"));
        assert!(synced.contains("MESA_NO_ERROR"));

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_detect_recent_crash_from_fml_log_and_crash_report() {
        let tmp = std::env::temp_dir().join("mashclient_crash_detect_test");
        let _ = std::fs::remove_dir_all(&tmp);
        let logs_dir = tmp.join("logs");
        let crash_dir = tmp.join("crash-reports");
        std::fs::create_dir_all(&logs_dir).unwrap();
        std::fs::create_dir_all(&crash_dir).unwrap();

        let since = SystemTime::now();
        std::fs::write(
            logs_dir.join("fml-client-latest.log"),
            "[Client thread/INFO]: Loading...\norg.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure\n\tat dev.purefps.Test\n",
        )
        .unwrap();

        let detected_log =
            detect_recent_crash_in_dirs(&[tmp.clone()], since, &[], None).expect("doit détecter fml log");
        assert!(detected_log.summary.contains("InvalidInjectionException"));

        std::fs::write(
            crash_dir.join("crash-2026-11-15_client.txt"),
            "---- Minecraft Crash Report ----\nTime: 11/15/26\nDescription: Initializing game\n\njava.lang.RuntimeException: Boom\n",
        )
        .unwrap();

        let detected_rep =
            detect_recent_crash_in_dirs(&[tmp.clone()], since, &[], Some(-1)).expect("doit détecter crash-report");
        assert_eq!(detected_rep.summary, "Initializing game");
        assert_eq!(detected_rep.exit_code, Some(-1));

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
