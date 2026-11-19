//! Exécuteur autonome JVM 1.8.9 + Forge 11.15.1.2318 + MashClient 4.5.14 (OpenGL & Vulkan Bêta).
//! - Synchronise automatiquement `purefps-4.5.14.jar` (et le télécharge depuis GitHub Releases s'il manque ou est obsolète)
//! - Télécharge ou récupère automatiquement les bibliothèques Forge 1.8.9, `minecraft-1.8.9-client.jar` et l'index d'assets `1.8.json`
//! - Extrait les DLL natives LWJGL 2.9.4 / JInput / Twitch 64-bit
//! - Isolat dédié pour le mode Vulkan (Bêta) via Mesa3D Zink (`javaw.exe.local` + `GALLIUM_DRIVER=zink`)
//! - Émet une progression granulaire en 7 étapes (barre de chargement) et surveille tout blocage « Not Responding »
//!   via `IsHungAppWindow` + `ClientHangWatchdog.java` + chronologie de diagnostic détaillée.

use crate::config::{
    app_storage_dir, prepare_seamless_launch_files, resolve_user_appdata,
    split_server_host_port, write_or_clear_quick_server_file, InstanceFlavor, LauncherPrefs,
    PureClientSettings,
};
pub use crate::diagnostics::{CrashReportInfo, DiagnosticItem, DiagnosticTimeline, LaunchProgressInfo};
use crate::runtimes::{ensure_vulkan_instance_initialized, prepare_isolated_vulkan_jre};
use crate::updater::{
    check_for_updates, download_and_install_update, extract_purefps_jar_semver,
    installed_mod_version, parse_semver, UpdateCheckOutcome, LAUNCHER_VERSION,
};
use crate::AppEvent;
use std::collections::VecDeque;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime};
use tao::event_loop::EventLoopProxy;

const FALLBACK_PURECLIENT_JAR_NAME: &str = "purefps-4.5.14.jar";
const MAX_LIBRARY_DOWNLOAD_BYTES: u64 = 128 * 1024 * 1024;
const MAX_NATIVE_DLL_BYTES: u64 = 32 * 1024 * 1024;
const EXPECTED_MC_189_CLIENT_SHA1: &str = "3870888a6c3d349d3771a3e9d16c9bf5e076b908";
const EXPECTED_ASSET_INDEX_18_SHA1: &str = "f6ad102bcaa53b1a58358f16e376d548d44933ec";
const EXPECTED_LOG4J_API_FIXED_SHA1: &str = "b61eaf2e64d8b0277e188262a8b771bbfa1502b3";
const EXPECTED_LOG4J_CORE_FIXED_SHA1: &str = "677991ea2d7426f76309a73739cecf609679492c";

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

    if let Ok(UpdateCheckOutcome::Available(mut plan)) =
        check_for_updates(&prefs.github_repo, target_game_dir)
    {
        if plan.jar_asset.is_some() {
            plan.exe_asset = None;
            let _ = proxy.send_event(AppEvent::LogLine(format!(
                "[MashClient] Téléchargement automatique du mod MashClient v{}...",
                plan.version
            )));
            let _ = download_and_install_update(&plan, target_game_dir, proxy);
        }
    }
}

pub fn sync_pureclient_jar(game_dir: &str) -> Result<String, String> {
    let mods_dir = Path::new(game_dir).join("mods");
    std::fs::create_dir_all(&mods_dir).map_err(|e| format!("Création dossier mods: {e}"))?;

    #[allow(unused_mut)]
    let mut search_dirs: Vec<PathBuf> = vec![app_storage_dir().join("updates"), mods_dir.clone()];
    // En build Release, on interdit le sideloading depuis le dossier de l'exécutable (ex: Téléchargements/) ou CWD.
    #[cfg(debug_assertions)]
    {
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
    }

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

/// Implémentation FIPS 180-4 SHA-1 autonome (sans dépendance externe) pour vérifier les artefacts Mojang.
struct Sha1 {
    state: [u32; 5],
    buf: [u8; 64],
    buf_len: usize,
    total_bits: u64,
}

impl Sha1 {
    fn new() -> Self {
        Self {
            state: [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0],
            buf: [0u8; 64],
            buf_len: 0,
            total_bits: 0,
        }
    }

    fn compress(&mut self, block: &[u8; 64]) {
        let mut w = [0u32; 80];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                block[i * 4],
                block[i * 4 + 1],
                block[i * 4 + 2],
                block[i * 4 + 3],
            ]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let [mut a, mut b, mut c, mut d, mut e] = self.state;
        for (i, &wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | ((!b) & d), 0x5a827999u32),
                20..=39 => (b ^ c ^ d, 0x6ed9eba1u32),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8f1bbcdcu32),
                _ => (b ^ c ^ d, 0xca62c1d6u32),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(wi);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        self.state[0] = self.state[0].wrapping_add(a);
        self.state[1] = self.state[1].wrapping_add(b);
        self.state[2] = self.state[2].wrapping_add(c);
        self.state[3] = self.state[3].wrapping_add(d);
        self.state[4] = self.state[4].wrapping_add(e);
    }

    fn update(&mut self, mut data: &[u8]) {
        self.total_bits = self.total_bits.wrapping_add((data.len() as u64) * 8);
        if self.buf_len > 0 {
            let take = (64 - self.buf_len).min(data.len());
            self.buf[self.buf_len..self.buf_len + take].copy_from_slice(&data[..take]);
            self.buf_len += take;
            data = &data[take..];
            if self.buf_len == 64 {
                let block = self.buf;
                self.compress(&block);
                self.buf_len = 0;
            }
        }
        while data.len() >= 64 {
            let mut block = [0u8; 64];
            block.copy_from_slice(&data[..64]);
            self.compress(&block);
            data = &data[64..];
        }
        if !data.is_empty() {
            self.buf[..data.len()].copy_from_slice(data);
            self.buf_len = data.len();
        }
    }

    fn finalize_hex(mut self) -> String {
        let bit_len = self.total_bits;
        self.buf[self.buf_len] = 0x80;
        self.buf_len += 1;
        if self.buf_len > 56 {
            for b in &mut self.buf[self.buf_len..64] {
                *b = 0;
            }
            let block = self.buf;
            self.compress(&block);
            self.buf = [0u8; 64];
            self.buf_len = 0;
        } else {
            for b in &mut self.buf[self.buf_len..56] {
                *b = 0;
            }
        }
        self.buf[56..64].copy_from_slice(&bit_len.to_be_bytes());
        let block = self.buf;
        self.compress(&block);

        let mut out = String::with_capacity(40);
        for word in self.state {
            use std::fmt::Write as _;
            let _ = write!(&mut out, "{word:08x}");
        }
        out
    }
}

fn sha1_file_hex(path: &Path) -> Result<String, String> {
    let mut f = File::open(path).map_err(|e| format!("Lecture SHA-1 {}: {e}", path.display()))?;
    let mut hasher = Sha1::new();
    let mut buf = [0u8; 32 * 1024];
    loop {
        let n = f
            .read(&mut buf)
            .map_err(|e| format!("Calcul SHA-1 {}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize_hex())
}

fn is_valid_sha1_hex(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Vérifie qu'une archive `log4j-core` ne contient PAS d'implémentation JNDI active dans `JndiLookup.class` (CVE-2021-44228).
/// Les builds officiels corrigés MultiMC (`2.0-beta9-fixed`) conservent un stub inoffensif (`return null;`) sans aucune
/// référence à `javax/naming/InitialContext`, contrairement au JAR vulnérable d'origine.
/// Retourne `true` (rejet par sécurité) si `JndiLookup.class` référence JNDI ou si l'archive ZIP est illisible.
fn jar_has_vulnerable_jndi_lookup(path: &Path) -> bool {
    let Ok(f) = File::open(path) else {
        return true;
    };
    let Ok(mut archive) = zip::ZipArchive::new(f) else {
        return true;
    };
    for i in 0..archive.len() {
        let Ok(entry) = archive.by_index(i) else {
            return true;
        };
        if entry
            .name()
            .to_ascii_lowercase()
            .ends_with("jndilookup.class")
        {
            if entry.size() > 64 * 1024 {
                return true;
            }
            let mut bytes = Vec::new();
            if entry.take(64 * 1024 + 1).read_to_end(&mut bytes).is_err() || bytes.len() > 64 * 1024
            {
                return true;
            }
            let has_jndi = bytes.windows(b"javax/naming".len()).any(|w| w == b"javax/naming")
                || bytes
                    .windows(b"InitialContext".len())
                    .any(|w| w == b"InitialContext");
            if has_jndi {
                return true;
            }
        }
    }
    false
}

fn is_valid_library_file(path: &Path, is_log4j_core: bool, expected_sha1: Option<&str>) -> bool {
    if !path
        .metadata()
        .map(|m| m.is_file() && m.len() > 64)
        .unwrap_or(false)
    {
        return false;
    }
    if is_log4j_core && jar_has_vulnerable_jndi_lookup(path) {
        return false;
    }
    if let Some(want) = expected_sha1 {
        if !sha1_file_hex(path).is_ok_and(|got| got.eq_ignore_ascii_case(want)) {
            return false;
        }
    }
    true
}

fn build_download_agent() -> Result<ureq::Agent, String> {
    let tls = Arc::new(
        native_tls::TlsConnector::new()
            .map_err(|e| format!("Initialisation TLS impossible: {e}"))?,
    );
    Ok(ureq::AgentBuilder::new()
        .tls_connector(tls)
        .timeout_connect(Duration::from_secs(12))
        .timeout_read(Duration::from_secs(90))
        .timeout_write(Duration::from_secs(10))
        .build())
}

fn download_file_to_path(
    agent: &ureq::Agent,
    url: &str,
    dst: &Path,
    expected_sha1: Option<&str>,
) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err(format!("Refused non-HTTPS URL {url}"));
    }
    let resp = agent
        .get(url)
        .set("User-Agent", "MashClient/4.5.14")
        .call()
        .map_err(|e| format!("Download failed for {url}: {e}"))?;
    if let Some(cl) = resp
        .header("Content-Length")
        .and_then(|s| s.parse::<u64>().ok())
    {
        if cl > MAX_LIBRARY_DOWNLOAD_BYTES {
            return Err(format!("Remote file exceeds size limit for {url}"));
        }
    }
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let tmp = dst.with_extension("part");
    let mut reader = resp.into_reader().take(MAX_LIBRARY_DOWNLOAD_BYTES + 1);
    let mut out = File::create(&tmp).map_err(|e| e.to_string())?;
    let written = std::io::copy(&mut reader, &mut out).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        e.to_string()
    })?;
    out.flush().map_err(|e| e.to_string())?;
    drop(out);

    if written > MAX_LIBRARY_DOWNLOAD_BYTES {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Size limit exceeded while downloading {url}"));
    }

    let is_jar = dst
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| e.eq_ignore_ascii_case("jar"));
    if written == 0 || (is_jar && written < 64) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Empty or truncated download from {url}"));
    }
    if is_jar {
        let mut magic = [0u8; 4];
        let valid_zip = File::open(&tmp)
            .and_then(|mut f| f.read_exact(&mut magic))
            .map(|_| &magic == b"PK\x03\x04")
            .unwrap_or(false);
        if !valid_zip {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!("Invalid JAR header received from {url}"));
        }
    }
    if let Some(want_sha1) = expected_sha1 {
        let got_sha1 = sha1_file_hex(&tmp)?;
        if !got_sha1.eq_ignore_ascii_case(want_sha1) {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!(
                "SHA-1 integrity mismatch for {url} (expected {want_sha1}, got {got_sha1})"
            ));
        }
    }

    let _ = std::fs::remove_file(dst);
    std::fs::rename(&tmp, dst).map_err(|e| e.to_string())?;
    Ok(())
}

fn copy_dir_tree_missing(src_dir: &Path, dst_dir: &Path) {
    let Ok(entries) = std::fs::read_dir(src_dir) else {
        return;
    };
    let _ = std::fs::create_dir_all(dst_dir);
    for entry in entries.flatten() {
        let Ok(ty) = entry.file_type() else {
            continue;
        };
        if ty.is_symlink() {
            continue;
        }
        let src_path = entry.path();
        let dst_path = dst_dir.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_tree_missing(&src_path, &dst_path);
        } else if ty.is_file() && !dst_path.exists() {
            let _ = std::fs::copy(&src_path, &dst_path);
        }
    }
}

/// Ensures 100% standalone availability of all 1.8.9 + Forge 11.15.1.2318 libraries,
/// 64-bit natives, `minecraft-1.8.9-client.jar`, and `assets/indexes/1.8.json` + `assets/objects`.
pub fn ensure_standalone_189_files(
    libraries_dir: &str,
    assets_dir: &str,
    proxy: &EventLoopProxy<AppEvent>,
    timeline: &Arc<DiagnosticTimeline>,
) -> Result<(), String> {
    let lib_root = Path::new(libraries_dir);
    std::fs::create_dir_all(lib_root)
        .map_err(|e| format!("Failed to create libraries directory: {e}"))?;

    let appdata = resolve_user_appdata();
    let default_mc_root = PathBuf::from(&appdata).join(".minecraft");
    let local_lib_caches = [
        default_mc_root.join("libraries"),
        PathBuf::from(&appdata).join("MashClient").join("libraries"),
    ];

    let all_required: Vec<&str> = REQUIRED_LIBRARIES_189_FORGE
        .iter()
        .chain(NATIVE_JARS_64.iter())
        .copied()
        .collect();
    let total_libs = all_required.len().max(1);
    let mut downloaded_libs = 0usize;
    let mut cached_libs = 0usize;

    timeline.emit_progress(
        proxy,
        2,
        12,
        format!("Vérification des bibliothèques (0/{total_libs})"),
        "Contrôle des archives Minecraft 1.8.9 + Forge...",
        true,
    );

    let mut http_agent: Option<ureq::Agent> = None;

    for (lib_idx, rel) in all_required.iter().enumerate() {
        let rel_os = rel.replace('\\', std::path::MAIN_SEPARATOR_STR);
        let dst = lib_root.join(&rel_os);
        let file_label = Path::new(&rel_os)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| rel.to_string());
        let lib_pct = (12 + ((lib_idx + 1) * 30) / total_libs).min(42) as u8;

        let is_log4j_core = rel.to_ascii_lowercase().contains("log4j-core");
        let expected_sha1 = if rel.ends_with("minecraft-1.8.9-client.jar") {
            Some(EXPECTED_MC_189_CLIENT_SHA1)
        } else if rel.ends_with("log4j-api-2.0-beta9-fixed.jar") {
            Some(EXPECTED_LOG4J_API_FIXED_SHA1)
        } else if rel.ends_with("log4j-core-2.0-beta9-fixed.jar") {
            Some(EXPECTED_LOG4J_CORE_FIXED_SHA1)
        } else {
            None
        };

        if is_valid_library_file(&dst, is_log4j_core, expected_sha1) {
            cached_libs += 1;
            timeline.emit_progress(
                proxy,
                2,
                lib_pct,
                format!("Vérification des bibliothèques ({}/{total_libs})", lib_idx + 1),
                &file_label,
                false,
            );
            continue;
        }
        if dst.exists() {
            // Purge tout cache corrompu ou vulnérable (ex: log4j-core non patché)
            let _ = std::fs::remove_file(&dst);
        }

        // 1. Check existing local caches on disk (.minecraft/libraries)
        let mut copied_locally = false;
        for cache_root in &local_lib_caches {
            let local_candidate = cache_root.join(&rel_os);
            if local_candidate != dst
                && is_valid_library_file(&local_candidate, is_log4j_core, expected_sha1)
            {
                if let Some(parent) = dst.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if std::fs::copy(&local_candidate, &dst).is_ok() {
                    copied_locally = true;
                    break;
                }
            }
        }
        if !copied_locally && rel.ends_with("minecraft-1.8.9-client.jar") {
            let vanilla_jar = default_mc_root
                .join("versions")
                .join("1.8.9")
                .join("1.8.9.jar");
            if is_valid_library_file(&vanilla_jar, false, expected_sha1) {
                if let Some(parent) = dst.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if std::fs::copy(&vanilla_jar, &dst).is_ok() {
                    copied_locally = true;
                }
            }
        }
        if copied_locally {
            cached_libs += 1;
            timeline.emit_progress(
                proxy,
                2,
                lib_pct,
                format!("Copie locale des bibliothèques ({}/{total_libs})", lib_idx + 1),
                &file_label,
                false,
            );
            continue;
        }

        // 2. Direct HTTPS download from official Mojang/Forge/Maven Central/MultiMC mirrors only
        let agent = match &http_agent {
            Some(a) => a,
            None => {
                http_agent = Some(build_download_agent()?);
                http_agent.as_ref().unwrap()
            }
        };

        let rel_url = rel.replace('\\', "/");
        timeline.emit_progress(
            proxy,
            2,
            lib_pct.saturating_sub(1).max(12),
            format!("Téléchargement des bibliothèques ({}/{total_libs})", lib_idx + 1),
            format!("Téléchargement de {file_label}..."),
            true,
        );
        let _ = proxy.send_event(AppEvent::LogLine(format!(
            "[MashClient] Downloading {file_label}..."
        )));

        let mut candidate_urls: Vec<String> = Vec::new();
        if rel_url.ends_with("minecraft-1.8.9-client.jar") {
            candidate_urls.push(
                "https://piston-data.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar"
                    .to_owned(),
            );
            candidate_urls.push(
                "https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar"
                    .to_owned(),
            );
        } else if rel_url.ends_with("log4j-api-2.0-beta9-fixed.jar") {
            // Release officielle MultiMC/log4j-beta9 vérifiée par empreinte SHA-1 épinglée
            candidate_urls.push(
                "https://github.com/MultiMC/log4j-beta9/releases/download/fixed/log4j-api-2.0-beta9-fixed.jar"
                    .to_owned(),
            );
            candidate_urls.push(format!("https://files.multimc.org/maven/{rel_url}"));
        } else if rel_url.ends_with("log4j-core-2.0-beta9-fixed.jar") {
            // Release officielle MultiMC/log4j-beta9 (JndiLookup neutralisé) vérifiée par SHA-1 épinglée + contrôle bytecode
            candidate_urls.push(
                "https://github.com/MultiMC/log4j-beta9/releases/download/fixed/log4j-core-2.0-beta9-fixed.jar"
                    .to_owned(),
            );
            candidate_urls.push(format!("https://files.multimc.org/maven/{rel_url}"));
        } else {
            // Uniquement les dépôts officiels Mojang / Forge / Maven Central (aucun miroir tiers ni repli Log4Shell non corrigé)
            candidate_urls.push(format!("https://libraries.minecraft.net/{rel_url}"));
            candidate_urls.push(format!("https://maven.minecraftforge.net/{rel_url}"));
            candidate_urls.push(format!("https://repo1.maven.org/maven2/{rel_url}"));
        }

        let mut downloaded = false;
        let mut last_err = String::new();
        for url in &candidate_urls {
            match download_file_to_path(agent, url, &dst, expected_sha1) {
                Ok(()) => {
                    if is_log4j_core && jar_has_vulnerable_jndi_lookup(&dst) {
                        let _ = std::fs::remove_file(&dst);
                        last_err = format!(
                            "Vulnerable log4j-core archive (active JndiLookup detected) rejected from {url}"
                        );
                        continue;
                    }
                    downloaded = true;
                    break;
                }
                Err(e) => last_err = e,
            }
        }
        if !downloaded {
            timeline.record(
                "error",
                "DOWNLOAD",
                format!("Échec téléchargement bibliothèque {file_label}: {last_err}"),
            );
            return Err(format!(
                "Failed to download required library {file_label} ({last_err})"
            ));
        }
        downloaded_libs += 1;
        timeline.emit_progress(
            proxy,
            2,
            lib_pct,
            format!("Téléchargement des bibliothèques ({}/{total_libs})", lib_idx + 1),
            format!("{file_label} prêt"),
            false,
        );
    }

    timeline.record(
        "ok",
        "DOWNLOAD",
        format!(
            "Bibliothèques 1.8.9 prêtes ({total_libs} au total : {downloaded_libs} téléchargée(s), {cached_libs} en cache)"
        ),
    );

    timeline.emit_progress(
        proxy,
        3,
        43,
        "Vérification des assets Minecraft 1.8",
        "Contrôle de l'index assets/indexes/1.8.json...",
        true,
    );

    let assets_root = Path::new(assets_dir);
    let local_asset_caches = [
        default_mc_root.join("assets"),
        PathBuf::from(&appdata).join("MashClient").join("assets"),
    ];
    let index_dst = assets_root.join("indexes").join("1.8.json");
    let index_valid = index_dst.is_file()
        && sha1_file_hex(&index_dst)
            .is_ok_and(|h| h.eq_ignore_ascii_case(EXPECTED_ASSET_INDEX_18_SHA1));
    if !index_valid {
        let _ = std::fs::remove_file(&index_dst);
        for asset_cache in &local_asset_caches {
            let local_idx = asset_cache.join("indexes").join("1.8.json");
            if local_idx != index_dst
                && local_idx.is_file()
                && sha1_file_hex(&local_idx)
                    .is_ok_and(|h| h.eq_ignore_ascii_case(EXPECTED_ASSET_INDEX_18_SHA1))
            {
                if let Some(parent) = index_dst.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                if std::fs::copy(&local_idx, &index_dst).is_ok() {
                    break;
                }
            }
        }
        if !index_dst.is_file() {
            let agent = match &http_agent {
                Some(a) => a,
                None => {
                    http_agent = Some(build_download_agent()?);
                    http_agent.as_ref().unwrap()
                }
            };
            let _ = proxy.send_event(AppEvent::LogLine(
                "[MashClient] Downloading asset index 1.8.json...".to_owned(),
            ));
            timeline.emit_progress(
                proxy,
                3,
                44,
                "Téléchargement de l'index 1.8.json",
                "Récupération depuis piston-meta.mojang.com...",
                true,
            );
            if download_file_to_path(
                agent,
                "https://piston-meta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json",
                &index_dst,
                Some(EXPECTED_ASSET_INDEX_18_SHA1),
            )
            .is_err()
            {
                let _ = download_file_to_path(
                    agent,
                    "https://launchermeta.mojang.com/v1/packages/f6ad102bcaa53b1a58358f16e376d548d44933ec/1.8.json",
                    &index_dst,
                    Some(EXPECTED_ASSET_INDEX_18_SHA1),
                );
            }
        }
    }

    let dst_objects = assets_root.join("objects");
    let _ = std::fs::create_dir_all(&dst_objects);
    let objects_empty = std::fs::read_dir(&dst_objects)
        .map(|mut it| it.next().is_none())
        .unwrap_or(true);
    if objects_empty {
        for asset_cache in &local_asset_caches {
            let src_objects = asset_cache.join("objects");
            if src_objects != dst_objects && src_objects.is_dir() {
                timeline.emit_progress(
                    proxy,
                    3,
                    46,
                    "Copie des assets locaux Minecraft 1.8",
                    "Réutilisation du cache .minecraft/assets/objects...",
                    true,
                );
                copy_dir_tree_missing(&src_objects, &dst_objects);
                break;
            }
        }
    }

    // Verify all asset objects listed in 1.8.json and download any missing ones from Mojang CDN
    if let Ok(idx_raw) = std::fs::read_to_string(&index_dst) {
        if let Ok(idx_json) = serde_json::from_str::<serde_json::Value>(&idx_raw) {
            if let Some(objs) = idx_json.get("objects").and_then(serde_json::Value::as_object) {
                let mut missing_hashes: Vec<String> = Vec::new();
                let mut seen = std::collections::HashSet::new();
                for entry in objs.values() {
                    let Some(hash) = entry.get("hash").and_then(serde_json::Value::as_str) else {
                        continue;
                    };
                    // Validation stricte SHA-1 (40 hex ASCII uniquement) : empêche toute traversée de répertoire
                    if !is_valid_sha1_hex(hash) {
                        continue;
                    }
                    let hash_low = hash.to_ascii_lowercase();
                    if !seen.insert(hash_low.clone()) {
                        continue;
                    }
                    let sub = &hash_low[..2];
                    let obj_path = dst_objects.join(sub).join(&hash_low);
                    if !obj_path.is_file() {
                        // Try local caches first (avec vérification SHA-1)
                        let mut copied = false;
                        for asset_cache in &local_asset_caches {
                            let cand = asset_cache.join("objects").join(sub).join(&hash_low);
                            if cand.is_file()
                                && sha1_file_hex(&cand)
                                    .is_ok_and(|h| h.eq_ignore_ascii_case(&hash_low))
                            {
                                if let Some(p) = obj_path.parent() {
                                    let _ = std::fs::create_dir_all(p);
                                }
                                if std::fs::copy(&cand, &obj_path).is_ok() {
                                    copied = true;
                                    break;
                                }
                            }
                        }
                        if !copied {
                            missing_hashes.push(hash_low);
                        }
                    }
                }

                if !missing_hashes.is_empty() {
                    let total_missing = missing_hashes.len();
                    timeline.record(
                        "info",
                        "ASSETS",
                        format!("Téléchargement de {total_missing} assets Minecraft 1.8.9 manquants..."),
                    );
                    timeline.emit_progress(
                        proxy,
                        3,
                        46,
                        format!("Téléchargement des assets (0/{total_missing})"),
                        "Téléchargement des sons et langues Minecraft 1.8.9...",
                        true,
                    );
                    let _ = proxy.send_event(AppEvent::LogLine(format!(
                        "[MashClient] Downloading {total_missing} Minecraft 1.8.9 sound/lang assets..."
                    )));
                    let queue = Arc::new(Mutex::new(VecDeque::from(missing_hashes)));
                    let done_count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
                    let mut handles = Vec::new();
                    for _ in 0..8 {
                        let q = Arc::clone(&queue);
                        let done = Arc::clone(&done_count);
                        let objs_root = dst_objects.clone();
                        let p_log = proxy.clone();
                        let tl_worker = Arc::clone(timeline);
                        handles.push(std::thread::spawn(move || {
                            let Ok(worker_agent) = build_download_agent() else {
                                return;
                            };
                            loop {
                                let next_hash = {
                                    let Ok(mut guard) = q.lock() else { break };
                                    guard.pop_front()
                                };
                                let Some(hash) = next_hash else { break };
                                let sub = &hash[..2];
                                let target = objs_root.join(sub).join(&hash);
                                let url = format!("https://resources.download.minecraft.net/{sub}/{hash}");
                                let _ = download_file_to_path(&worker_agent, &url, &target, Some(&hash));
                                let cur = done.fetch_add(1, Ordering::Relaxed) + 1;
                                let asset_pct = (46 + ((cur * 22) / total_missing)).min(68) as u8;
                                if cur % 6 == 0 || cur == total_missing {
                                    tl_worker.emit_progress(
                                        &p_log,
                                        3,
                                        asset_pct,
                                        format!("Téléchargement des assets ({cur}/{total_missing})"),
                                        format!("Objet SHA-1 {sub}/{}...", &hash[..8]),
                                        cur == total_missing,
                                    );
                                }
                                if cur % 100 == 0 || cur == total_missing {
                                    let _ = p_log.send_event(AppEvent::LogLine(format!(
                                        "[MashClient] Assets 1.8.9: {cur}/{total_missing}..."
                                    )));
                                }
                            }
                        }));
                    }
                    for h in handles {
                        let _ = h.join();
                    }
                    timeline.record(
                        "ok",
                        "ASSETS",
                        format!("{total_missing} assets Minecraft 1.8.9 téléchargés"),
                    );
                } else {
                    timeline.record("ok", "ASSETS", "Tous les assets Minecraft 1.8.9 sont déjà présents");
                }
            }
        }
    }

    timeline.emit_progress(
        proxy,
        3,
        68,
        "Assets Minecraft 1.8 vérifiés",
        "Préparation des bibliothèques natives 64-bit...",
        true,
    );
    Ok(())
}

pub fn extract_natives_if_needed(libraries_dir: &str) -> Result<PathBuf, String> {
    extract_natives_if_needed_for_flavor(libraries_dir, false)
}

pub fn extract_natives_if_needed_for_flavor(
    libraries_dir: &str,
    is_vulkan: bool,
) -> Result<PathBuf, String> {
    let folder_name = if is_vulkan {
        "natives-1.8.9-vulkan"
    } else {
        "natives-1.8.9"
    };
    let natives_dir = app_storage_dir().join(folder_name);
    std::fs::create_dir_all(&natives_dir).map_err(|e| format!("Création dossier natives: {e}"))?;

    if !is_vulkan {
        // S'assurer qu'aucune DLL Zink ne reste dans le dossier OpenGL standard
        for stray_name in ["opengl32.dll", "zinkgl32.dll"] {
            let stray_gl = natives_dir.join(stray_name);
            if stray_gl.is_file() {
                let _ = std::fs::remove_file(stray_gl);
            }
        }
    }

    if natives_dir.join("lwjgl64.dll").is_file() && natives_dir.join("OpenAL64.dll").is_file() {
        return Ok(natives_dir);
    }

    for rel in NATIVE_JARS_64 {
        let rel_os = rel.replace('\\', std::path::MAIN_SEPARATOR_STR);
        let jar_path = Path::new(libraries_dir).join(rel_os);
        if !jar_path.is_file() {
            continue;
        }
        let file =
            File::open(&jar_path).map_err(|e| format!("Ouverture {}: {e}", jar_path.display()))?;
        let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("Zip invalide: {e}"))?;
        for idx in 0..archive.len() {
            let entry = archive.by_index(idx).map_err(|e| e.to_string())?;
            if entry
                .unix_mode()
                .is_some_and(|m| (m & 0o170000) == 0o120000)
            {
                continue;
            }
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
            if entry.size() > MAX_NATIVE_DLL_BYTES {
                return Err(format!("DLL native trop volumineuse dans l'archive : {name}"));
            }
            let Some(safe_path) = entry.enclosed_name().map(|p| p.to_path_buf()) else {
                continue;
            };
            let Some(file_name) = safe_path.file_name() else {
                continue;
            };
            let dst_path = natives_dir.join(file_name);
            let mut out = File::create(&dst_path).map_err(|e| e.to_string())?;
            let mut limited = entry.take(MAX_NATIVE_DLL_BYTES + 1);
            let written = std::io::copy(&mut limited, &mut out).map_err(|e| e.to_string())?;
            if written > MAX_NATIVE_DLL_BYTES {
                drop(out);
                let _ = std::fs::remove_file(&dst_path);
                return Err(format!("Décompression native interrompue (limite dépassée) : {name}"));
            }
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
        let rel_os = rel.replace('\\', std::path::MAIN_SEPARATOR_STR);
        let full = Path::new(libraries_dir).join(rel_os);
        if !full.is_file() {
            return Err(format!("Bibliothèque manquante : {}", full.display()));
        }
        paths.push(full.to_string_lossy().to_string());
    }
    Ok(paths.join(";"))
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

/// Active la fenêtre LWJGL de Minecraft sans JAMAIS bloquer le thread UI de MashClient.exe
/// (n'utilise ni `AttachThreadInput` ni `SetWindowPos` synchrone, et ignore immédiatement toute fenêtre figée).
#[cfg(windows)]
fn promote_and_focus_lwjgl_window(existing_hwnd: usize) -> Option<usize> {
    let hwnd = detect_new_lwjgl_window(existing_hwnd)?;
    if crate::win_compat::focus_window_nonblocking(hwnd) {
        Some(hwnd)
    } else {
        None
    }
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
    child_pid: u32,
    proxy: EventLoopProxy<AppEvent>,
    stop_flag: Arc<AtomicBool>,
    menu_ready_flag: Arc<AtomicBool>,
) {
    let pre_existing_lwjgl = current_visible_lwjgl_hwnd();
    std::thread::spawn(move || {
        let start = Instant::now();
        let timeout = Duration::from_secs(90);
        while !stop_flag.load(Ordering::Relaxed) && start.elapsed() < timeout {
            for dir in &watched_game_dirs {
                let cfg = dir.join("config");
                let menu_ready = cfg.join(".purefps_menu_ready");
                if menu_ready.is_file() {
                    let _ = std::fs::remove_file(cfg.join(".purefps_display_ready"));
                    let _ = std::fs::remove_file(&menu_ready);
                    menu_ready_flag.store(true, Ordering::Relaxed);
                    stop_flag.store(true, Ordering::Relaxed);
                    let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                    return;
                }
            }
            // Fallback tardif (20s) uniquement si la fenêtre LWJGL de ce PID est visible ET répond normalement
            if start.elapsed() >= Duration::from_secs(20) {
                let target_hwnd = crate::win_compat::find_visible_window_for_pid(child_pid)
                    .or_else(|| detect_new_lwjgl_window(pre_existing_lwjgl));
                if let Some(hwnd) = target_hwnd {
                    if !crate::win_compat::is_hwnd_hung(hwnd) {
                        menu_ready_flag.store(true, Ordering::Relaxed);
                        stop_flag.store(true, Ordering::Relaxed);
                        let _ = proxy.send_event(AppEvent::MinecraftMenuReady);
                        return;
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(40));
        }
    });
}

/// Surveille la fenêtre LWJGL de `javaw.exe` (`IsHungAppWindow`), le fichier `.purefps_hang_dump.txt`
/// produit par `ClientHangWatchdog.java` et l'absence prolongée d'activité au démarrage.
/// Dès qu'un vrai blocage « Not Responding » est détecté, envoie une longue liste diagnostique précise à l'UI.
fn spawn_game_hang_watchdog(
    child_pid: u32,
    game_dir: PathBuf,
    launch_time: SystemTime,
    timeline: Arc<DiagnosticTimeline>,
    proxy: EventLoopProxy<AppEvent>,
    stop_flag: Arc<AtomicBool>,
    menu_ready_flag: Arc<AtomicBool>,
    manual_diag_flag: Arc<AtomicBool>,
) {
    std::thread::spawn(move || {
        let mut consecutive_hung_secs: u64 = 0;
        let mut reported_for_current_stall = false;
        let mut warned_slow_startup = false;
        let hang_dump_path = game_dir.join("config").join(".purefps_hang_dump.txt");

        while !stop_flag.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_secs(1));
            if stop_flag.load(Ordering::Relaxed) {
                break;
            }

            // 1. Demande manuelle depuis le bouton « Diagnostic » de la barre de chargement
            if manual_diag_flag.swap(false, Ordering::AcqRel) {
                let silent = timeline.seconds_since_last_activity();
                let report = timeline.build_not_responding_report(
                    Some(child_pid),
                    "Diagnostic détaillé demandé pendant le chargement",
                    consecutive_hung_secs.max(silent),
                    true,
                );
                let _ = proxy.send_event(AppEvent::GameHangDetected(report));
                reported_for_current_stall = true;
                continue;
            }

            // 2. Surveillance directe Win32 IsHungAppWindow sur la fenêtre du processus javaw.exe
            if let Some(hwnd) = crate::win_compat::find_visible_window_for_pid(child_pid) {
                if crate::win_compat::is_hwnd_hung(hwnd) {
                    consecutive_hung_secs += 1;
                    if consecutive_hung_secs == 4 && !menu_ready_flag.load(Ordering::Relaxed) {
                        timeline.emit_stall_warning(
                            &proxy,
                            "Fenêtre Minecraft momentanément occupée — analyse en cours...".to_owned(),
                        );
                    }
                    if consecutive_hung_secs >= 8 && !reported_for_current_stall {
                        reported_for_current_stall = true;
                        let report = timeline.build_not_responding_report(
                            Some(child_pid),
                            "Fenêtre Minecraft ne répond plus (Windows IsHungAppWindow)",
                            consecutive_hung_secs,
                            true,
                        );
                        let _ = proxy.send_event(AppEvent::GameHangDetected(report));
                    }
                    continue;
                } else if consecutive_hung_secs > 0 {
                    consecutive_hung_secs = 0;
                    reported_for_current_stall = false;
                }
            }

            // 3. Surveillance du dump produit en temps réel par ClientHangWatchdog.java dans la JVM
            if !reported_for_current_stall {
                if let Ok(meta) = std::fs::metadata(&hang_dump_path) {
                    let is_fresh = meta
                        .modified()
                        .map(|m| m >= launch_time)
                        .unwrap_or(false);
                    if is_fresh && meta.len() > 32 {
                        reported_for_current_stall = true;
                        let report = timeline.build_not_responding_report(
                            Some(child_pid),
                            "Thread principal Minecraft (Client thread) figé — pile JVM capturée",
                            consecutive_hung_secs.max(8),
                            true,
                        );
                        let _ = proxy.send_event(AppEvent::GameHangDetected(report));
                        continue;
                    }
                }
            }

            // 4. Surveillance d'un blocage silencieux avant l'apparition du menu principal
            if !menu_ready_flag.load(Ordering::Relaxed) {
                let silent_secs = timeline.seconds_since_last_activity();
                if silent_secs >= 14 && !warned_slow_startup {
                    warned_slow_startup = true;
                    timeline.emit_stall_warning(
                        &proxy,
                        format!(
                            "Étape lente ({silent_secs}s sans nouvelle sortie Forge) — cliquez sur Diagnostic si le jeu semble bloqué"
                        ),
                    );
                }
                if silent_secs >= 30 && !reported_for_current_stall {
                    reported_for_current_stall = true;
                    let report = timeline.build_not_responding_report(
                        Some(child_pid),
                        "Démarrage Minecraft bloqué (aucune progression Forge depuis 30s)",
                        silent_secs,
                        true,
                    );
                    let _ = proxy.send_event(AppEvent::GameHangDetected(report));
                }
                if silent_secs < 5 {
                    warned_slow_startup = false;
                }
            }
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
            let crash_location = crate::diagnostics::extract_crash_location_from_text(&text);
            let thread_stack_lines = crate::diagnostics::extract_stack_lines_from_text(&text);
            return Some(CrashReportInfo {
                title: "Minecraft a planté".to_owned(),
                summary,
                details: text,
                exit_code,
                report_path: Some(report_path.to_string_lossy().to_string()),
                suspected_mod_file,
                is_not_responding: false,
                crash_location,
                environment_lines: Vec::new(),
                diagnostic_steps: Vec::new(),
                thread_stack_lines,
                can_force_kill: false,
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
                let crash_location = crate::diagnostics::extract_crash_location_from_text(&snippet);
                let thread_stack_lines = crate::diagnostics::extract_stack_lines_from_text(&snippet);
                return Some(CrashReportInfo {
                    title: "Erreur au lancement de Minecraft".to_owned(),
                    summary,
                    details: snippet,
                    exit_code,
                    report_path: Some(log_path.to_string_lossy().to_string()),
                    suspected_mod_file,
                    is_not_responding: false,
                    crash_location,
                    environment_lines: Vec::new(),
                    diagnostic_steps: Vec::new(),
                    thread_stack_lines,
                    can_force_kill: false,
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
        let crash_location = crate::diagnostics::extract_crash_location_from_text(&details);
        let thread_stack_lines = crate::diagnostics::extract_stack_lines_from_text(&details);

        return Some(CrashReportInfo {
            title: "Minecraft s'est fermé avec une erreur".to_owned(),
            summary,
            details,
            exit_code,
            report_path: fallback_log,
            suspected_mod_file,
            is_not_responding: false,
            crash_location,
            environment_lines: Vec::new(),
            diagnostic_steps: Vec::new(),
            thread_stack_lines,
            can_force_kill: false,
        });
    }

    None
}

pub fn build_low_latency_alsoft_ini() -> &'static str {
    "# MashClient 4.5.14 — Profil OpenAL Soft WASAPI Basse Latence\n\
[general]\n\
drivers = mmdevapi,dsound\n\
periods = 3\n\
period_size = 320\n\
sources = 256\n\
slots = 4\n\
cf_level = 0\n"
}

fn read_configured_audio_output_device(game_dir: &Path) -> Option<String> {
    let props_path = game_dir.join("config").join("purefps.properties");
    let text = std::fs::read_to_string(props_path).ok()?;
    for line in text.lines() {
        if let Some(rest) = line.trim().strip_prefix("audioOutputDevice=") {
            let unescaped = rest.trim().replace("\\:", ":").replace("\\=", "=");
            if !unescaped.is_empty() {
                return Some(unescaped);
            }
        }
    }
    None
}

fn ensure_low_latency_alsoft_ini(game_dir: &Path) -> Option<PathBuf> {
    let mut content = build_low_latency_alsoft_ini().to_owned();
    if let Some(dev) = read_configured_audio_output_device(game_dir) {
        let clean = dev.replace(['\r', '\n'], "");
        if !clean.is_empty() {
            content.push_str(&format!("device = {clean}\n"));
        }
    }
    let cfg_dir = game_dir.join("config");
    let _ = std::fs::create_dir_all(&cfg_dir);
    let game_ini = cfg_dir.join("mashclient_alsoft.ini");
    if std::fs::write(&game_ini, &content).is_ok() {
        return Some(game_ini);
    }
    let storage = app_storage_dir();
    let _ = std::fs::create_dir_all(&storage);
    let fallback_ini = storage.join("mashclient_alsoft.ini");
    std::fs::write(&fallback_ini, &content).ok()?;
    Some(fallback_ini)
}

#[allow(clippy::too_many_arguments)]
pub fn run_direct_jvm_blocking(
    mut prefs: LauncherPrefs,
    username: String,
    uuid: String,
    access_token: String,
    user_type: String,
    screen_w: u32,
    screen_h: u32,
    proxy: EventLoopProxy<AppEvent>,
    active_game_pid: Arc<AtomicU32>,
    manual_diag_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    let is_vulkan = prefs.instance_flavor == InstanceFlavor::VulkanBeta;
    let initial_dir = crate::runtimes::resolve_active_game_dir(&prefs);
    let timeline = Arc::new(DiagnosticTimeline::new(
        PathBuf::from(&initial_dir),
        PathBuf::from(&prefs.java_path),
        prefs.ram_mb,
        is_vulkan,
    ));

    let res = run_direct_jvm_inner(
        &mut prefs,
        username,
        uuid,
        access_token,
        user_type,
        screen_w,
        screen_h,
        &proxy,
        &active_game_pid,
        &manual_diag_flag,
        &timeline,
    );

    active_game_pid.store(0, Ordering::Relaxed);
    let _ = proxy.send_event(AppEvent::LaunchProgress(LaunchProgressInfo::inactive()));

    if let Err(err_msg) = res {
        let is_fr = prefs.language == "fr";
        let base_report = CrashReportInfo {
            title: if is_fr {
                "Échec du lancement de Minecraft".to_owned()
            } else {
                "Minecraft Launch Aborted".to_owned()
            },
            summary: err_msg.clone(),
            details: err_msg,
            exit_code: None,
            report_path: None,
            suspected_mod_file: None,
            is_not_responding: false,
            crash_location: None,
            environment_lines: Vec::new(),
            diagnostic_steps: Vec::new(),
            thread_stack_lines: Vec::new(),
            can_force_kill: false,
        };
        let enriched = timeline.enrich_crash_report(base_report);
        let _ = proxy.send_event(AppEvent::GameCrashed(enriched));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn run_direct_jvm_inner(
    prefs: &mut LauncherPrefs,
    username: String,
    uuid: String,
    access_token: String,
    user_type: String,
    screen_w: u32,
    screen_h: u32,
    proxy: &EventLoopProxy<AppEvent>,
    active_game_pid: &Arc<AtomicU32>,
    manual_diag_flag: &Arc<AtomicBool>,
    timeline: &Arc<DiagnosticTimeline>,
) -> Result<(), String> {
    timeline.emit_progress(
        proxy,
        1,
        4,
        "Vérification du runtime Java 8",
        "Contrôle de la version Java 64-bit...",
        true,
    );

    let java_exists = Path::new(&prefs.java_path).is_file();
    let cur_probe = crate::runtimes::probe_java8_executable(Path::new(&prefs.java_path));
    let outdated_tls_java = java_exists
        && ((cur_probe.is_java8 && cur_probe.update_version > 0 && cur_probe.update_version < 161)
            || !cur_probe.is_64bit);

    if !java_exists || outdated_tls_java {
        timeline.record(
            "info",
            "JAVA",
            if !java_exists {
                "Java 8 absent — installation automatique d'Eclipse Temurin JRE 8 x64"
            } else {
                "Java 8 ancien détecté — mise à niveau automatique vers Eclipse Temurin JRE 8 x64"
            },
        );
        timeline.emit_progress(
            proxy,
            1,
            6,
            "Installation d'Eclipse Temurin Java 8 (64-bit)",
            "Téléchargement et extraction du runtime Java 8 sécurisé...",
            true,
        );
        let _ = proxy.send_event(AppEvent::LogLine(
            if !java_exists {
                "[MashClient] Java 8 not found on this PC — automatically installing Eclipse Temurin Java 8 x64...".to_owned()
            } else {
                format!(
                    "[MashClient] Legacy {} detected (missing modern TLS 1.2/1.3 & Mojang Root CAs) — upgrading automatically to Eclipse Temurin Java 8 x64...",
                    cur_probe.label
                )
            },
        ));
        match crate::runtimes::install_temurin_jre8(proxy) {
            Ok((installed_javaw, label)) => {
                prefs.java_path = installed_javaw.clone();
                let _ = crate::config::save_launcher_prefs(prefs);
                timeline.record("ok", "JAVA", format!("Runtime configuré : {label}"));
                let _ = proxy.send_event(AppEvent::TemurinInstalled(Ok((installed_javaw, label))));
            }
            Err(e) => {
                if java_exists {
                    timeline.record("warn", "JAVA", format!("Mise à niveau Temurin ignorée ({e})"));
                    let _ = proxy.send_event(AppEvent::LogLine(format!(
                        "[MashClient] Temurin JRE 8 download unavailable ({e}); continuing with existing Java + embedded ModernTlsTrustHelper."
                    )));
                } else {
                    return Err(e);
                }
            }
        }
    } else {
        timeline.record("ok", "JAVA", format!("Runtime Java 8 prêt : {}", cur_probe.label));
    }

    let is_vulkan = prefs.instance_flavor == InstanceFlavor::VulkanBeta;
    let active_game_dir = if is_vulkan {
        ensure_vulkan_instance_initialized(prefs)?
    } else {
        prefs.game_dir.clone()
    };

    timeline.emit_progress(
        proxy,
        1,
        9,
        "Synchronisation du mod MashClient",
        "Vérification de purefps-*.jar dans mods/...",
        true,
    );

    let effective_java: PathBuf = if is_vulkan {
        timeline.record("info", "VULKAN", "Préparation du JRE isolé pour Mesa3D Zink...");
        prepare_isolated_vulkan_jre(Path::new(&prefs.java_path), prefs, proxy)?
    } else {
        PathBuf::from(&prefs.java_path)
    };

    auto_fetch_mod_jar_if_needed(prefs, &active_game_dir, proxy);

    let jar_msg = sync_pureclient_jar(&active_game_dir)?;
    timeline.record("ok", "MOD", jar_msg.clone());
    let _ = proxy.send_event(AppEvent::LogLine(format!("[MashClient] {jar_msg}")));

    prepare_seamless_launch_files(&active_game_dir);

    // Nettoie un éventuel ancien dump watchdog avant le démarrage
    let stale_hang_dump = Path::new(&active_game_dir)
        .join("config")
        .join(".purefps_hang_dump.txt");
    if stale_hang_dump.exists() {
        let _ = std::fs::remove_file(&stale_hang_dump);
    }

    let client_settings = PureClientSettings::load_from_game_dir(&active_game_dir);
    let use_quick_bridge_file = client_settings.enabled && client_settings.custom_main_menu;
    if use_quick_bridge_file {
        write_or_clear_quick_server_file(&active_game_dir, prefs.selected_server.as_ref());
    } else {
        write_or_clear_quick_server_file(&active_game_dir, None);
    }

    ensure_standalone_189_files(&prefs.libraries_dir, &prefs.assets_dir, proxy, timeline)?;

    timeline.emit_progress(
        proxy,
        4,
        70,
        "Extraction des bibliothèques natives 64-bit",
        "Extraction de lwjgl64.dll, OpenAL64.dll et jinput...",
        true,
    );

    let natives_dir = extract_natives_if_needed_for_flavor(&prefs.libraries_dir, is_vulkan)?;
    let vulkan_dll_in_natives = if is_vulkan {
        timeline.emit_progress(
            proxy,
            4,
            73,
            "Déploiement Vulkan Bêta (Mesa3D Zink)",
            "Installation de opengl32.dll Zink auprès de lwjgl64.dll...",
            true,
        );
        let dll_path = crate::runtimes::deploy_vulkan_zink_dlls_to_dir(&natives_dir, prefs, proxy)?;
        timeline.record(
            "ok",
            "VULKAN",
            format!("Mesa3D Zink déployé : {}", dll_path.display()),
        );
        let _ = proxy.send_event(AppEvent::LogLine(format!(
            "[Vulkan Beta] Mesa3D Zink (opengl32.dll) deployed next to lwjgl64.dll: {}",
            dll_path.display()
        )));
        Some(dll_path)
    } else {
        None
    };
    timeline.record(
        "ok",
        "NATIVES",
        format!("DLL natives 64-bit prêtes : {}", natives_dir.display()),
    );
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] 64-bit natives ready: {}",
        natives_dir.display()
    )));

    timeline.emit_progress(
        proxy,
        5,
        76,
        "Construction du classpath Forge 1.8.9",
        "Validation des 49 archives du classpath JVM...",
        true,
    );

    let classpath = build_classpath(&prefs.libraries_dir)?;
    let game_dir_path = Path::new(&active_game_dir);
    let alsoft_ini = ensure_low_latency_alsoft_ini(game_dir_path);
    let (win_w, win_h) = compute_launch_window_dimensions(game_dir_path, screen_w, screen_h);
    let flavor_tag = if is_vulkan { "Vulkan Beta" } else { "OpenGL" };
    timeline.record(
        "info",
        "JVM",
        format!(
            "Lancement javaw.exe [{flavor_tag}] ({} Mo, {}x{}, joueur: {username})",
            prefs.ram_mb, win_w, win_h
        ),
    );
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[MashClient] Starting Minecraft 1.8.9 [{}] ({} MB, {}x{}, {})...",
        flavor_tag, prefs.ram_mb, screen_w, screen_h, username
    )));

    let min_mem_mb = 512u32.min(prefs.ram_mb);
    let cpu_threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    let conc_gc_threads = (cpu_threads / 4).clamp(1, 4);
    let region_size_mb = if prefs.ram_mb > 4096 { 8 } else { 4 };

    let mut cmd = Command::new(&effective_java);
    cmd.current_dir(&active_game_dir)
        .env_remove("SHIM_MCCOMPAT")
        .env("__GL_THREADED_OPTIMIZATIONS", "0")
        .env("__GL_YIELD", "USLEEP")
        .env("ALSOFT_DRIVERS", "mmdevapi,dsound");
    if let Some(pref_dev) = read_configured_audio_output_device(game_dir_path) {
        cmd.env("ALSOFT_DEFAULT_DEVICE", pref_dev);
    }
    if is_vulkan {
        let orig_path = std::env::var("PATH").unwrap_or_default();
        let vk_bin_str = effective_java
            .parent()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        let new_path = format!("{};{vk_bin_str};{orig_path}", natives_dir.display());
        cmd.env("PATH", new_path)
            .env("GALLIUM_DRIVER", "zink")
            .env("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            .env("LIBGL_ALWAYS_SOFTWARE", "0")
            .env("MESA_GL_VERSION_OVERRIDE", "4.6COMPAT")
            .env("MESA_GLSL_VERSION_OVERRIDE", "460")
            .env("ZINK_DESCRIPTORS", "lazy")
            .env("ZINK_INLINE_UNIFORMS", "0")
            .env("zink_shader_object_enable", "true")
            .env("mesa_no_error", "true")
            .env("MESA_NO_ERROR", "1")
            .env("GALLIUM_THREAD", "1")
            .env("mesa_glthread", "true")
            .env("mesa_glthread_driver", "true")
            .env("WGL_SWAP_INTERVAL", "0")
            .env("wgl_swap_interval", "0")
            .env("wgl_frame_latency", "2")
            .env("vblank_mode", "0");
    }
    if let Some(ref ini_path) = alsoft_ini {
        cmd.env("ALSOFT_CONF", ini_path);
    }
    cmd.arg(format!("-Xms{}M", min_mem_mb))
        .arg(format!("-Xmx{}M", prefs.ram_mb))
        .arg("-XX:+IgnoreUnrecognizedVMOptions")
        .arg("-XX:+UnlockExperimentalVMOptions")
        .arg("-XX:+UseG1GC")
        .arg(format!("-XX:ConcGCThreads={conc_gc_threads}"))
        .arg("-XX:G1NewSizePercent=20")
        .arg("-XX:G1MaxNewSizePercent=40")
        .arg("-XX:MaxGCPauseMillis=20")
        .arg("-XX:InitiatingHeapOccupancyPercent=35")
        .arg("-XX:G1MixedGCCountTarget=8")
        .arg("-XX:G1MixedGCLiveThresholdPercent=65")
        .arg("-XX:G1HeapWastePercent=5")
        .arg("-XX:G1ReservePercent=20")
        .arg(format!("-XX:G1HeapRegionSize={region_size_mb}M"))
        .arg("-XX:+AlwaysPreTouch")
        .arg("-XX:+DisableExplicitGC")
        .arg("-XX:+ParallelRefProcEnabled")
        .arg("-Dsun.java2d.dpiaware=true")
        .arg("-Djava.net.preferIPv4Stack=true")
        .arg("-Dlog4j2.formatMsgNoLookups=true")
        .arg("-Dfml.ignoreInvalidMinecraftCertificates=true")
        .arg("-Dfml.ignorePatchDiscrepancies=true")
        .arg("-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=false")
        .arg("-Dorg.lwjgl.opengl.Window.undecorated=true")
        .arg("-Dpurefps.seamlessLaunch=true");
    if is_vulkan {
        cmd.arg("-Dpurefps.rendererBackend=vulkan");
        if let Some(ref vk_dll) = vulkan_dll_in_natives {
            cmd.arg(format!("-Dpurefps.vulkanDllPath={}", vk_dll.display()));
        }
    }
    struct EphemeralTokenGuard(Option<PathBuf>);
    impl Drop for EphemeralTokenGuard {
        fn drop(&mut self) {
            if let Some(ref path) = self.0 {
                if path.exists() {
                    if let Ok(meta) = std::fs::metadata(path) {
                        let zeros = vec![0u8; (meta.len() as usize).clamp(64, 16 * 1024)];
                        let _ = std::fs::write(path, zeros);
                    }
                    let _ = std::fs::remove_file(path);
                }
            }
        }
    }

    let supports_ephemeral_token = access_token != "0"
        && std::fs::read_dir(Path::new(&active_game_dir).join("mods"))
            .ok()
            .into_iter()
            .flatten()
            .flatten()
            .any(|entry| {
                let fname = entry.file_name().to_string_lossy().to_string();
                if extract_purefps_jar_semver(&fname).is_none() {
                    return false;
                }
                File::open(entry.path())
                    .ok()
                    .and_then(|f| zip::ZipArchive::new(f).ok())
                    .is_some_and(|mut z| {
                        z.by_name("dev/purefps/core/EphemeralSessionBridge.class")
                            .is_ok()
                    })
            });

    let mut _ephemeral_token_guard = EphemeralTokenGuard(None);
    let cli_access_token = if supports_ephemeral_token {
        let token_path = app_storage_dir().join(format!(".session_token_{}.tmp", std::process::id()));
        if std::fs::write(&token_path, access_token.as_bytes()).is_ok() {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&token_path, std::fs::Permissions::from_mode(0o600));
            }
            cmd.env("MASHCLIENT_SESSION_TOKEN_FILE", &token_path);
            _ephemeral_token_guard = EphemeralTokenGuard(Some(token_path));
            "__MASHCLIENT_EPHEMERAL__".to_owned()
        } else {
            access_token.clone()
        }
    } else {
        access_token.clone()
    };

    let clean_uuid = crate::auth::normalize_mc_uuid(&uuid);
    cmd.arg(format!("-Djava.library.path={}", natives_dir.display()))
        .arg(format!("-Dorg.lwjgl.librarypath={}", natives_dir.display()))
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
        .arg(&clean_uuid)
        .arg("--accessToken")
        .arg(&cli_access_token)
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

    timeline.emit_progress(
        proxy,
        5,
        80,
        "Démarrage de la JVM Minecraft 1.8.9",
        "Lancement du processus javaw.exe...",
        true,
    );

    let launch_time = SystemTime::now();
    let mut child = cmd
        .spawn()
        .map_err(|e| format!("Impossible de lancer javaw.exe : {e}"))?;
    let child_pid = child.id();
    active_game_pid.store(child_pid, Ordering::Relaxed);
    timeline.record("ok", "JVM", format!("Processus javaw.exe démarré (PID {child_pid})"));
    timeline.emit_progress(
        proxy,
        6,
        82,
        "Chargement de Forge 1.8.9 & Mods",
        format!("JVM active (PID {child_pid}) — initialisation de FML..."),
        true,
    );

    let _ = proxy.send_event(AppEvent::GameRunning(true));

    let stop_watcher = Arc::new(AtomicBool::new(false));
    let stop_hang_watchdog = Arc::new(AtomicBool::new(false));
    let menu_ready_flag = Arc::new(AtomicBool::new(false));

    spawn_display_ready_watcher(
        vec![PathBuf::from(&active_game_dir)],
        child_pid,
        proxy.clone(),
        Arc::clone(&stop_watcher),
        Arc::clone(&menu_ready_flag),
    );

    spawn_game_hang_watchdog(
        child_pid,
        PathBuf::from(&active_game_dir),
        launch_time,
        Arc::clone(timeline),
        proxy.clone(),
        Arc::clone(&stop_hang_watchdog),
        Arc::clone(&menu_ready_flag),
        Arc::clone(manual_diag_flag),
    );

    let console_tail: Arc<Mutex<VecDeque<String>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(180)));

    let out_handle = child.stdout.take().map(|stdout| {
        let p_out = proxy.clone();
        let stop_on_ready = Arc::clone(&stop_watcher);
        let ready_flag = Arc::clone(&menu_ready_flag);
        let tail_out = Arc::clone(&console_tail);
        let tl_out = Arc::clone(timeline);
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(Result::ok) {
                if let Some((pct, _stage, label)) = tl_out.observe_game_log_line(&line) {
                    let st_idx = if pct >= 99 { 7 } else { 6 };
                    tl_out.emit_progress(
                        &p_out,
                        st_idx,
                        pct,
                        if pct >= 99 {
                            "Ouverture de MashClient"
                        } else {
                            "Chargement de Forge 1.8.9 & Mods"
                        },
                        label,
                        true,
                    );
                }
                if line.contains("[PureClient-Ready] MENU_ACTIVE") {
                    ready_flag.store(true, Ordering::Relaxed);
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
        let tl_err = Arc::clone(timeline);
        std::thread::spawn(move || {
            let reader = BufReader::new(stderr);
            for line in reader.lines().map_while(Result::ok) {
                let tagged = format!("[STDERR] {line}");
                let _ = tl_err.observe_game_log_line(&tagged);
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
    stop_hang_watchdog.store(true, Ordering::Relaxed);
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
        let enriched = timeline.enrich_crash_report(crash_info);
        let _ = proxy.send_event(AppEvent::GameCrashed(enriched));
    } else {
        let _ = proxy.send_event(AppEvent::GameRunning(false));
    }
    Ok(())
}

#[cfg(windows)]
pub fn focus_visible_lwjgl_window() {
    let _ = promote_and_focus_lwjgl_window(0);
}

#[cfg(not(windows))]
pub fn focus_visible_lwjgl_window() {}

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

    #[test]
    fn test_log4j_vulnerable_jndi_detection() {
        let tmp = std::env::temp_dir().join("mashclient_log4j_jndi_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        let make_jar = |name: &str, entry_name: Option<&str>, payload: &[u8]| -> PathBuf {
            let p = tmp.join(name);
            let f = File::create(&p).unwrap();
            let mut z = zip::ZipWriter::new(f);
            let opts = zip::write::SimpleFileOptions::default();
            z.start_file("META-INF/MANIFEST.MF", opts).unwrap();
            z.write_all(b"Manifest-Version: 1.0\n").unwrap();
            if let Some(en) = entry_name {
                z.start_file(en, opts).unwrap();
                z.write_all(payload).unwrap();
            }
            z.finish().unwrap();
            p
        };

        let vuln = make_jar(
            "vuln.jar",
            Some("org/apache/logging/log4j/core/lookup/JndiLookup.class"),
            b"\xCA\xFE\xBA\xBE javax/naming/InitialContext java:comp/env/",
        );
        let patched_stub = make_jar(
            "patched.jar",
            Some("org/apache/logging/log4j/core/lookup/JndiLookup.class"),
            b"\xCA\xFE\xBA\xBE org/apache/logging/log4j/core/lookup/JndiLookup java:comp/env/",
        );
        let stripped = make_jar("stripped.jar", None, b"");

        assert!(jar_has_vulnerable_jndi_lookup(&vuln));
        assert!(!jar_has_vulnerable_jndi_lookup(&patched_stub));
        assert!(!jar_has_vulnerable_jndi_lookup(&stripped));

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
