//! Exécuteur autonome JVM 1.8.9 + Forge 11.15.1.2318 + PureClient 3.5.0.
//! - Déploie automatiquement `purefps-3.5.0.jar`
//! - Extrait les DLL natives LWJGL 2.9.4 / JInput / Twitch 64-bit
//! - Construit le classpath complet de 49 archives et diffuse les logs en temps réel hors du thread UI.

use crate::config::{app_storage_dir, prepare_seamless_launch_files, LauncherPrefs};
use crate::updater::extract_purefps_jar_semver;
use crate::AppEvent;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tao::event_loop::EventLoopProxy;

const FALLBACK_PURECLIENT_JAR_NAME: &str = "purefps-3.5.0.jar";

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

pub fn sync_pureclient_jar(game_dir: &str) -> Result<String, String> {
    let mods_dir = Path::new(game_dir).join("mods");
    std::fs::create_dir_all(&mods_dir).map_err(|e| format!("Création dossier mods: {e}"))?;

    let mut search_dirs: Vec<PathBuf> = vec![
        app_storage_dir().join("updates"),
        mods_dir.clone(),
    ];
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

    // Sélectionne automatiquement le `purefps-X.Y.Z.jar` le plus récent (Auto-Updater ou compilation locale)
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
                    // À version égale, privilégie une source hors `mods/` (ex: cache `updates/` ou `build/libs/`)
                    Some((cur_ver, _, cur_path)) => {
                        ver > *cur_ver || (ver == *cur_ver && cur_path.parent() == Some(mods_dir.as_path()) && path.parent() != Some(mods_dir.as_path()))
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
        "Archive {FALLBACK_PURECLIENT_JAR_NAME} introuvable (lancez une mise à jour ou placez le mod dans mods/)."
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

pub fn sync_prism_instance_cfg(prefs: &LauncherPrefs) {
    let Some(instance_root) = Path::new(&prefs.game_dir).parent() else {
        return;
    };
    let cfg_path = instance_root.join("instance.cfg");
    let Ok(existing) = std::fs::read_to_string(&cfg_path) else {
        return;
    };
    let mut updated_lines: Vec<String> = Vec::new();
    let mut saw_min = false;
    let mut saw_max = false;
    for line in existing.lines() {
        if line.starts_with("MinMemAlloc=") {
            updated_lines.push(format!("MinMemAlloc={}", prefs.ram_mb));
            saw_min = true;
        } else if line.starts_with("MaxMemAlloc=") {
            updated_lines.push(format!("MaxMemAlloc={}", prefs.ram_mb));
            saw_max = true;
        } else {
            updated_lines.push(line.to_owned());
        }
    }
    if !saw_min {
        updated_lines.push(format!("MinMemAlloc={}", prefs.ram_mb));
    }
    if !saw_max {
        updated_lines.push(format!("MaxMemAlloc={}", prefs.ram_mb));
    }
    let _ = std::fs::write(&cfg_path, updated_lines.join("\n") + "\n");
}

fn spawn_menu_ready_watcher(
    game_dir: String,
    proxy: EventLoopProxy<AppEvent>,
    stop_flag: Arc<AtomicBool>,
    fallback_reveal_after_timeout: bool,
) {
    std::thread::spawn(move || {
        let ready_path = Path::new(&game_dir).join("config").join(".purefps_menu_ready");
        let start = Instant::now();
        let timeout = Duration::from_secs(45);
        while !stop_flag.load(Ordering::Relaxed) && start.elapsed() < timeout {
            if ready_path.is_file() {
                let _ = std::fs::remove_file(&ready_path);
                let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                return;
            }
            std::thread::sleep(Duration::from_millis(40));
        }
        if fallback_reveal_after_timeout && !stop_flag.load(Ordering::Relaxed) {
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

    let jar_msg = sync_pureclient_jar(&prefs.game_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!("[PureLauncher] {jar_msg}")));

    prepare_seamless_launch_files(&prefs.game_dir);

    let natives_dir = extract_natives_if_needed(&prefs.libraries_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[PureLauncher] Natives 64-bit prêtes : {}",
        natives_dir.display()
    )));

    let classpath = build_classpath(&prefs.libraries_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[PureLauncher] Démarrage Singularity JVM ({} MiB G1GC, {}x{}, joueur: {})...",
        prefs.ram_mb, screen_w, screen_h, username
    )));

    let mut cmd = Command::new(&prefs.java_path);
    cmd.current_dir(&prefs.game_dir)
        .arg(format!("-Xms{}M", prefs.ram_mb))
        .arg(format!("-Xmx{}M", prefs.ram_mb))
        .arg("-XX:+UnlockExperimentalVMOptions")
        .arg("-XX:+UseG1GC")
        .arg("-XX:G1NewSizePercent=20")
        .arg("-XX:G1ReservePercent=20")
        .arg("-XX:MaxGCPauseMillis=25")
        .arg("-XX:G1HeapRegionSize=32M")
        .arg("-XX:+DisableExplicitGC")
        .arg("-XX:+AlwaysPreTouch")
        .arg("-XX:+ParallelRefProcEnabled")
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
    spawn_menu_ready_watcher(
        prefs.game_dir.clone(),
        proxy.clone(),
        Arc::clone(&stop_watcher),
        false,
    );

    if let Some(stdout) = child.stdout.take() {
        let p_out = proxy.clone();
        let stop_on_ready = Arc::clone(&stop_watcher);
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if line.contains("[PureClient-Ready] MENU_ACTIVE") {
                    stop_on_ready.store(true, Ordering::Relaxed);
                    let _ = p_out.send_event(AppEvent::MinecraftMenuReady);
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
        "[PureLauncher] Processus Minecraft terminé ({status})."
    )));
    let _ = proxy.send_event(AppEvent::GameRunning(false));
    Ok(())
}

pub fn spawn_prism_bridge_sync(
    prefs: &LauncherPrefs,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<(), String> {
    if !Path::new(&prefs.prism_exe).is_file() {
        return Err(format!("PrismLauncher introuvable : {}", prefs.prism_exe));
    }
    let jar_msg = sync_pureclient_jar(&prefs.game_dir)?;
    let _ = proxy.send_event(AppEvent::LogLine(format!("[PureLauncher] {jar_msg}")));
    sync_prism_instance_cfg(prefs);
    prepare_seamless_launch_files(&prefs.game_dir);

    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[PureLauncher] Pont Singularity Prism : lancement de '1.8.9 gemini fps' ({} MiB RAM)...",
        prefs.ram_mb
    )));

    let mut cmd = Command::new(&prefs.prism_exe);
    cmd.arg("--launch").arg("1.8.9 gemini fps");
    if prefs.quick_join_hypixel {
        cmd.arg("--server").arg("mc.hypixel.net");
    }

    cmd.spawn()
        .map_err(|e| format!("Échec lancement PrismLauncher : {e}"))?;

    let stop_watcher = Arc::new(AtomicBool::new(false));
    spawn_menu_ready_watcher(prefs.game_dir.clone(), proxy.clone(), stop_watcher, true);
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
