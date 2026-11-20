//! Gestionnaire des runtimes MashClient 1.0.0 :
//! 1. Détection, classement et installation automatique d'Eclipse Temurin Java 8 x64 récent (`8u400+`)
//! 2. Déploiement du traducteur OpenGL -> Vulkan **Mesa3D Zink** (`opengl32.dll` + `javaw.exe.local`)
//!    tout en conservant le même dossier `.minecraft` unifié (resourcepacks, mondes, serveurs, configs).

use crate::config::{app_storage_dir, LauncherPrefs};
use crate::updater::{
    fetch_trusted_redirect_chain, is_trusted_github_api_url, parse_sha256sums, sanitize_repo,
    sha256_file_hex,
};
use crate::AppEvent;
use serde::Deserialize;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tao::event_loop::EventLoopProxy;

pub const VULKAN_ZINK_ASSET_NAME: &str = "mashclient-vulkan-zink-win64.zip";
pub const PINNED_ZINK_ZIP_SHA256: &str =
    "d3f71e2ef11c032a6d7b507ee2f6bab6ca3fb20543944af70982fc24b92604cb";
pub const VULKAN_CORE_DLL: &str = "opengl32.dll";
pub const VULKAN_OPTIONAL_COMPANION_DLLS: &[&str] = &[
    "libgallium_wgl.dll",
    "libglapi.dll",
    "zink_dri.dll",
];

const USER_AGENT: &str = "MashClient-Runtimes/1.0.0";
const MAX_RUNTIME_DOWNLOAD_BYTES: u64 = 256 * 1024 * 1024;
const MAX_ZIP_ENTRY_BYTES: u64 = 128 * 1024 * 1024;
const MAX_ZIP_TOTAL_BYTES: u64 = 512 * 1024 * 1024;
const MAX_ZIP_ENTRIES: usize = 8_192;
const MAX_CHECKSUM_TEXT_BYTES: u64 = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Java8Probe {
    pub is_java8: bool,
    pub is_64bit: bool,
    pub update_version: u32,
    pub label: String,
}

/// Extrait le numéro d'update Java 8 à partir d'une chaîne de version ou de dossier
/// (ex: `"1.8.0_442"` -> `442`, `"jre-8.0.432.6-hotspot"` -> `432`, `"jdk8u412-b08-jre"` -> `412`, `"jre-legacy"` -> `51`).
pub fn parse_java8_update_number(raw: &str) -> Option<u32> {
    let s = raw.trim().trim_matches('"');
    let low = s.to_ascii_lowercase();

    if let Some(idx) = low.find("1.8.0_") {
        let rest = &low[idx + 6..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(u) = digits.parse::<u32>() {
            return Some(u);
        }
    }
    if let Some(idx) = low.find("8.0.") {
        let rest = &low[idx + 4..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(u) = digits.parse::<u32>() {
            return Some(u);
        }
    }
    for (idx, _) in low.match_indices("8u") {
        let rest = &low[idx + 2..];
        let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if !digits.is_empty() {
            if let Ok(u) = digits.parse::<u32>() {
                return Some(u);
            }
        }
    }
    if low.contains("jre-legacy") {
        return Some(51);
    }
    None
}

/// Inspecte une installation Java à partir du chemin `.../bin/javaw.exe` (sans lancer de sous-processus bloquant).
pub fn probe_java8_executable(java_path: &Path) -> Java8Probe {
    let path_str = java_path.to_string_lossy();
    let jre_root = java_path.parent().and_then(|b| b.parent());

    let mut is_64 = !path_str.contains("Program Files (x86)") || path_str.contains("windows-x64");
    let mut update: Option<u32> = None;
    let mut is_j8 = false;

    if let Some(root) = jre_root {
        let release_file = root.join("release");
        if let Ok(rel_text) = std::fs::read_to_string(release_file) {
            for line in rel_text.lines() {
                if let Some(v) = line.strip_prefix("JAVA_VERSION=") {
                    let clean = v.trim().trim_matches('"');
                    if clean.starts_with("1.8") {
                        is_j8 = true;
                        update = parse_java8_update_number(clean).or(Some(0));
                    }
                } else if let Some(arch) = line.strip_prefix("OS_ARCH=") {
                    let clean = arch.trim().trim_matches('"').to_ascii_lowercase();
                    is_64 = clean.contains("64");
                }
            }
        }
    }

    if update.is_none() {
        if let Some(u) = parse_java8_update_number(&path_str) {
            is_j8 = true;
            update = Some(u);
        } else if path_str.to_ascii_lowercase().contains("temurin-jre8") {
            is_j8 = true;
            update = Some(440);
            is_64 = true;
        }
    }

    let upd = update.unwrap_or(0);
    let label = if !java_path.is_file() {
        "Java 8 non détecté".to_owned()
    } else if is_j8 && upd >= 250 {
        format!("Java 8u{upd} récent (64-bit)")
    } else if is_j8 && upd > 0 {
        format!("Java 8u{upd} ancien (mise à jour conseillée)")
    } else if is_j8 {
        "Java 8 (64-bit)".to_owned()
    } else {
        "Java personnalisé".to_owned()
    };

    Java8Probe {
        is_java8: is_j8,
        is_64bit: is_64,
        update_version: upd,
        label,
    }
}

pub fn managed_temurin_jre8_dir() -> PathBuf {
    app_storage_dir().join("runtimes").join("temurin-jre8")
}

pub fn find_javaw_inside_dir(root: &Path) -> Option<PathBuf> {
    let direct = root.join("bin").join("javaw.exe");
    if direct.is_file() {
        return Some(direct);
    }
    let entries = std::fs::read_dir(root).ok()?;
    for entry in entries.flatten() {
        let p = entry.path();
        if p.is_dir() {
            let nested = p.join("bin").join("javaw.exe");
            if nested.is_file() {
                return Some(nested);
            }
            let nested_jre = p.join("jre").join("bin").join("javaw.exe");
            if nested_jre.is_file() {
                return Some(nested_jre);
            }
        }
    }
    None
}

fn build_http_agents() -> Result<(ureq::Agent, ureq::Agent), String> {
    let tls = Arc::new(
        native_tls::TlsConnector::new()
            .map_err(|e| format!("Initialisation TLS impossible: {e}"))?,
    );
    let standard = ureq::AgentBuilder::new()
        .tls_connector(Arc::clone(&tls))
        .timeout_connect(Duration::from_secs(12))
        .timeout_read(Duration::from_secs(120))
        .timeout_write(Duration::from_secs(12))
        .build();
    let no_redirect = ureq::AgentBuilder::new()
        .tls_connector(tls)
        .redirects(0)
        .timeout_connect(Duration::from_secs(12))
        .timeout_read(Duration::from_secs(120))
        .timeout_write(Duration::from_secs(12))
        .build();
    Ok((standard, no_redirect))
}

#[derive(Deserialize)]
struct GhReleaseSimple {
    #[serde(default)]
    tag_name: String,
    #[serde(default)]
    assets: Vec<GhAssetSimple>,
}

#[derive(Deserialize)]
struct GhAssetSimple {
    name: String,
    url: String,
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

fn fetch_trusted_text_asset(
    no_redirect_agent: &ureq::Agent,
    api_url: Option<&str>,
    browser_url: &str,
) -> Result<String, String> {
    let resp = open_trusted_stream(no_redirect_agent, api_url, browser_url)?;
    let mut raw = String::new();
    resp.into_reader()
        .take(MAX_CHECKSUM_TEXT_BYTES)
        .read_to_string(&mut raw)
        .map_err(|e| format!("Lecture des empreintes SHA-256 impossible: {e}"))?;
    Ok(raw)
}

fn open_trusted_stream(
    no_redirect_agent: &ureq::Agent,
    api_url: Option<&str>,
    browser_url: &str,
) -> Result<ureq::Response, String> {
    if let Some(aurl) = api_url {
        if is_trusted_github_api_url(aurl) {
            let req = no_redirect_agent
                .get(aurl)
                .set("Accept", "application/octet-stream")
                .set("User-Agent", USER_AGENT)
                .set("X-GitHub-Api-Version", "2022-11-28");
            match req.call() {
                Ok(r) if matches!(r.status(), 301 | 302 | 303 | 307 | 308) => {
                    if let Some(loc) = r.header("Location") {
                        return fetch_trusted_redirect_chain(no_redirect_agent, loc);
                    }
                    return Ok(r);
                }
                Ok(r) => return Ok(r),
                Err(ureq::Error::Status(301 | 302 | 303 | 307 | 308, redirect_resp)) => {
                    if let Some(loc) = redirect_resp.header("Location") {
                        return fetch_trusted_redirect_chain(no_redirect_agent, loc);
                    }
                }
                Err(_) => {}
            }
        }
    }
    fetch_trusted_redirect_chain(no_redirect_agent, browser_url)
}

fn stream_download_to_file(
    no_redirect_agent: &ureq::Agent,
    api_url: Option<&str>,
    browser_url: &str,
    dest: &Path,
    mut on_progress: impl FnMut(u8),
) -> Result<(), String> {
    let resp = open_trusted_stream(no_redirect_agent, api_url, browser_url)?;

    let total = resp
        .header("Content-Length")
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);
    if total > MAX_RUNTIME_DOWNLOAD_BYTES {
        return Err("Archive distante refusée : taille supérieure à la limite autorisée.".to_owned());
    }

    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let tmp = dest.with_extension("part");
    let mut out = File::create(&tmp).map_err(|e| format!("Création fichier temporaire: {e}"))?;
    let mut reader = resp.into_reader();
    let mut buf = [0u8; 65_536];
    let mut downloaded = 0u64;
    let mut last_pct = 0u8;

    loop {
        let n = reader.read(&mut buf).map_err(|e| {
            let _ = std::fs::remove_file(&tmp);
            format!("Interruption téléchargement: {e}")
        })?;
        if n == 0 {
            break;
        }
        downloaded = downloaded.saturating_add(n as u64);
        if downloaded > MAX_RUNTIME_DOWNLOAD_BYTES {
            drop(out);
            let _ = std::fs::remove_file(&tmp);
            return Err("Téléchargement interrompu : taille maximale autorisée dépassée.".to_owned());
        }
        out.write_all(&buf[..n]).map_err(|e| {
            let _ = std::fs::remove_file(&tmp);
            format!("Écriture disque: {e}")
        })?;
        if total > 0 {
            let pct = ((downloaded * 100) / total).min(99) as u8;
            if pct >= last_pct + 10 {
                last_pct = pct;
                on_progress(pct);
            }
        }
    }
    out.flush().map_err(|e| e.to_string())?;
    drop(out);

    if dest.exists() {
        let _ = std::fs::remove_file(dest);
    }
    std::fs::rename(&tmp, dest).map_err(|e| format!("Validation archive téléchargée: {e}"))
}

fn extract_zip_to_dir(zip_path: &Path, target_dir: &Path) -> Result<(), String> {
    let f = File::open(zip_path).map_err(|e| format!("Ouverture archive zip: {e}"))?;
    let mut archive = zip::ZipArchive::new(f).map_err(|e| format!("Archive zip invalide: {e}"))?;
    if archive.len() > MAX_ZIP_ENTRIES {
        return Err("Archive zip refusée : nombre excessif d'entrées.".to_owned());
    }
    std::fs::create_dir_all(target_dir).map_err(|e| e.to_string())?;

    let mut total_extracted: u64 = 0;
    for i in 0..archive.len() {
        let entry = archive.by_index(i).map_err(|e| e.to_string())?;
        // Refuse les liens symboliques à l'intérieur des archives ZIP
        if entry
            .unix_mode()
            .is_some_and(|m| (m & 0o170000) == 0o120000)
        {
            continue;
        }
        if entry.size() > MAX_ZIP_ENTRY_BYTES {
            return Err("Archive zip refusée : entrée décompressée trop volumineuse.".to_owned());
        }
        let Some(safe_rel) = entry.enclosed_name().map(|p| p.to_path_buf()) else {
            continue;
        };
        let out_path = target_dir.join(safe_rel);
        if entry.is_dir() {
            let _ = std::fs::create_dir_all(&out_path);
        } else {
            if let Some(p) = out_path.parent() {
                let _ = std::fs::create_dir_all(p);
            }
            let mut out_f = File::create(&out_path)
                .map_err(|e| format!("Extraction '{}' échouée: {e}", out_path.display()))?;
            let mut limited = entry.take(MAX_ZIP_ENTRY_BYTES + 1);
            let written = std::io::copy(&mut limited, &mut out_f)
                .map_err(|e| format!("Copie '{}' échouée: {e}", out_path.display()))?;
            if written > MAX_ZIP_ENTRY_BYTES {
                drop(out_f);
                let _ = std::fs::remove_file(&out_path);
                return Err("Archive zip refusée : limite de décompression par fichier dépassée.".to_owned());
            }
            total_extracted = total_extracted.saturating_add(written);
            if total_extracted > MAX_ZIP_TOTAL_BYTES {
                drop(out_f);
                let _ = std::fs::remove_file(&out_path);
                return Err("Archive zip refusée : limite totale de décompression dépassée.".to_owned());
            }
        }
    }
    Ok(())
}

/// Télécharge et installe automatiquement la dernière version d'Eclipse Temurin JRE 8 x64 Windows (`8u400+`)
/// dans `%APPDATA%\MashClient\runtimes\temurin-jre8\`.
pub fn install_temurin_jre8(proxy: &EventLoopProxy<AppEvent>) -> Result<(String, String), String> {
    let install_root = managed_temurin_jre8_dir();
    if let Some(existing_javaw) = find_javaw_inside_dir(&install_root) {
        let probe = probe_java8_executable(&existing_javaw);
        if probe.is_java8 && probe.update_version >= 300 {
            return Ok((existing_javaw.to_string_lossy().to_string(), probe.label));
        }
    }

    let (standard, no_redirect) = build_http_agents()?;
    let _ = proxy.send_event(AppEvent::LogLine(
        "[Java 8] Recherche de la dernière version Eclipse Temurin JRE 8 x64...".to_owned(),
    ));

    let api_url = "https://api.github.com/repos/adoptium/temurin8-binaries/releases/latest";
    let rel: GhReleaseSimple = standard
        .get(api_url)
        .set("Accept", "application/vnd.github+json")
        .set("User-Agent", USER_AGENT)
        .call()
        .map_err(|e| format!("Impossible de joindre Adoptium GitHub Releases: {e}"))?
        .into_json()
        .map_err(|e| format!("Réponse Adoptium illisible: {e}"))?;

    let asset = rel
        .assets
        .iter()
        .find(|a| {
            let low = a.name.to_ascii_lowercase();
            low.starts_with("openjdk8u-jre_x64_windows_hotspot_") && low.ends_with(".zip")
        })
        .ok_or_else(|| "Archive Windows x64 JRE 8 introuvable dans la release Adoptium.".to_owned())?;

    let expected_sha256_name = format!("{}.sha256.txt", asset.name);
    let sha_asset = rel
        .assets
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(&expected_sha256_name))
        .ok_or_else(|| {
            format!(
                "Installation Eclipse Temurin JRE 8 refusée : fichier '{expected_sha256_name}' absent de la release Adoptium."
            )
        })?;
    let raw_sha = fetch_trusted_text_asset(
        &no_redirect,
        Some(sha_asset.url.as_str()),
        &sha_asset.browser_download_url,
    )
    .map_err(|e| format!("Impossible de récupérer l'empreinte SHA-256 Adoptium : {e}"))?;
    let parsed_sha = parse_sha256sums(&raw_sha);
    let expected_sha256 = parsed_sha
        .get(&asset.name.to_ascii_lowercase())
        .cloned()
        .or_else(|| parsed_sha.values().next().cloned())
        .filter(|h| h.len() == 64)
        .ok_or_else(|| {
            "Installation Eclipse Temurin JRE 8 refusée : empreinte SHA-256 Adoptium invalide."
                .to_owned()
        })?;

    let zip_dest = app_storage_dir()
        .join("runtimes")
        .join("temurin8-win-x64.zip");

    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[Java 8] Téléchargement de {} ({} Mo)...",
        asset.name,
        asset.size / (1024 * 1024)
    )));

    stream_download_to_file(
        &no_redirect,
        None,
        &asset.browser_download_url,
        &zip_dest,
        |pct| {
            let _ = proxy.send_event(AppEvent::LogLine(format!(
                "[Java 8] Téléchargement Temurin JRE 8 : {pct}%..."
            )));
        },
    )?;

    let got_sha = sha256_file_hex(&zip_dest)?;
    if !got_sha.eq_ignore_ascii_case(&expected_sha256) {
        let _ = std::fs::remove_file(&zip_dest);
        return Err("Intégrité SHA-256 invalide pour l'archive Eclipse Temurin JRE 8.".to_owned());
    }

    let _ = proxy.send_event(AppEvent::LogLine(
        "[Java 8] Extraction du runtime Temurin Java 8 64-bit...".to_owned(),
    ));
    let _ = std::fs::remove_dir_all(&install_root);
    extract_zip_to_dir(&zip_dest, &install_root)?;
    let _ = std::fs::remove_file(&zip_dest);

    let javaw = find_javaw_inside_dir(&install_root)
        .ok_or_else(|| "javaw.exe introuvable après extraction de Temurin JRE 8.".to_owned())?;
    let probe = probe_java8_executable(&javaw);
    let _ = proxy.send_event(AppEvent::LogLine(format!(
        "[Java 8] {} installé avec succès ({}).",
        probe.label, rel.tag_name
    )));
    Ok((javaw.to_string_lossy().to_string(), probe.label))
}

/// Résout le dossier `.minecraft` actif (partagé entre les moteurs OpenGL et Vulkan Bêta
/// afin que les Resource Packs, mondes, serveurs et réglages restent toujours présents).
pub fn resolve_active_game_dir(prefs: &LauncherPrefs) -> String {
    prefs.game_dir.clone()
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        if ty.is_symlink() {
            continue;
        }
        let dst_path = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &dst_path)?;
        } else if ty.is_file() {
            std::fs::copy(entry.path(), dst_path)?;
        }
    }
    Ok(())
}

/// Vérifie et prépare les sous-dossiers requis (`config`, `mods`, `resourcepacks`, `shaderpacks`)
/// dans le répertoire `.minecraft` actif.
pub fn ensure_vulkan_instance_initialized(prefs: &LauncherPrefs) -> Result<String, String> {
    let game_dir_str = resolve_active_game_dir(prefs);
    let game_dir = Path::new(&game_dir_str);

    for sub in ["config", "mods", "resourcepacks", "shaderpacks"] {
        std::fs::create_dir_all(game_dir.join(sub))
            .map_err(|e| format!("Création dossier Minecraft ({sub}) : {e}"))?;
    }

    Ok(game_dir_str)
}

fn vulkan_zink_cache_dir() -> PathBuf {
    app_storage_dir().join("runtimes").join("vulkan-zink")
}

fn has_valid_vulkan_zink_bundle(dir: &Path) -> bool {
    std::fs::metadata(dir.join(VULKAN_CORE_DLL))
        .map(|m| m.is_file() && m.len() > 65_536)
        .unwrap_or(false)
}

fn find_dll_recursively(root: &Path, dll_name: &str) -> Option<PathBuf> {
    let direct = root.join(dll_name);
    if direct.is_file() {
        return Some(direct);
    }
    let x64_sub = root.join("x64").join(dll_name);
    if x64_sub.is_file() {
        return Some(x64_sub);
    }
    let entries = std::fs::read_dir(root).ok()?;
    for entry in entries.flatten() {
        let p = entry.path();
        if p.is_dir() {
            if let Some(hit) = find_dll_recursively(&p, dll_name) {
                return Some(hit);
            }
        }
    }
    None
}

fn copy_zink_dlls_from_staging(staging: &Path, cache_dir: &Path) -> Result<(), String> {
    let core_found = find_dll_recursively(staging, VULKAN_CORE_DLL).ok_or_else(|| {
        format!(
            "Bibliothèque Vulkan '{VULKAN_CORE_DLL}' absente de {VULKAN_ZINK_ASSET_NAME}."
        )
    })?;
    std::fs::copy(&core_found, cache_dir.join(VULKAN_CORE_DLL))
        .map_err(|e| format!("Installation '{VULKAN_CORE_DLL}' impossible : {e}"))?;

    for opt_dll in VULKAN_OPTIONAL_COMPANION_DLLS {
        if let Some(found) = find_dll_recursively(staging, opt_dll) {
            let _ = std::fs::copy(&found, cache_dir.join(opt_dll));
        }
    }
    Ok(())
}

fn ensure_vulkan_zink_dlls_downloaded(
    prefs: &LauncherPrefs,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<PathBuf, String> {
    let cache_dir = vulkan_zink_cache_dir();
    if has_valid_vulkan_zink_bundle(&cache_dir) {
        return Ok(cache_dir);
    }
    std::fs::create_dir_all(&cache_dir).map_err(|e| e.to_string())?;

    // 1. Recherche locale sécurisée (dossier updates/ uniquement en Release ; exe_dir autorisé uniquement en Debug)
    //    Chaque archive locale doit passer le contrôle d'empreinte SHA-256 épinglée avant extraction.
    #[allow(unused_mut)]
    let mut local_candidates = vec![app_storage_dir().join("updates").join(VULKAN_ZINK_ASSET_NAME)];
    #[cfg(debug_assertions)]
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            local_candidates.push(dir.join(VULKAN_ZINK_ASSET_NAME));
        }
    }
    for cand in local_candidates {
        if cand.is_file() {
            let valid_local_sha = sha256_file_hex(&cand)
                .is_ok_and(|got| got.eq_ignore_ascii_case(PINNED_ZINK_ZIP_SHA256));
            if !valid_local_sha {
                continue;
            }
            let staging = cache_dir.join("staging");
            let _ = std::fs::remove_dir_all(&staging);
            if extract_zip_to_dir(&cand, &staging).is_ok() {
                let _ = copy_zink_dlls_from_staging(&staging, &cache_dir);
            }
            let _ = std::fs::remove_dir_all(&staging);
            if has_valid_vulkan_zink_bundle(&cache_dir) {
                return Ok(cache_dir);
            }
        }
    }

    // 2. Téléchargement depuis la GitHub Release officielle publique du dépôt MashClient (sans aucun jeton)
    let repo = sanitize_repo(&prefs.github_repo)?;
    let (standard, no_redirect) = build_http_agents()?;

    let _ = proxy.send_event(AppEvent::LogLine(
        "[Vulkan Bêta] Téléchargement de la couche Vulkan Zink (Mesa3D x64)...".to_owned(),
    ));

    let latest_api = format!("https://api.github.com/repos/{repo}/releases/latest");
    let mut api_asset_url: Option<String> = None;
    let mut browser_url = format!(
        "https://github.com/{repo}/releases/latest/download/{VULKAN_ZINK_ASSET_NAME}"
    );
    let mut expected_sha256: Option<String> = None;

    let req = standard
        .get(&latest_api)
        .set("Accept", "application/vnd.github+json")
        .set("User-Agent", USER_AGENT);
    if let Ok(resp) = req.call() {
        if let Ok(rel) = resp.into_json::<GhReleaseSimple>() {
            if let Some(a) = rel
                .assets
                .iter()
                .find(|a| a.name.eq_ignore_ascii_case(VULKAN_ZINK_ASSET_NAME))
            {
                api_asset_url = Some(a.url.clone());
                browser_url = a.browser_download_url.clone();
            }
            if let Some(sum_asset) = rel
                .assets
                .iter()
                .find(|a| a.name.eq_ignore_ascii_case("SHA256SUMS.txt"))
            {
                if let Ok(raw_sums) = fetch_trusted_text_asset(
                    &no_redirect,
                    Some(sum_asset.url.as_str()),
                    &sum_asset.browser_download_url,
                ) {
                    let parsed = parse_sha256sums(&raw_sums);
                    expected_sha256 = parsed
                        .get(&VULKAN_ZINK_ASSET_NAME.to_ascii_lowercase())
                        .filter(|h| h.len() == 64)
                        .cloned();
                }
            }
        }
    }

    // Repli fail-closed sur l'empreinte SHA-256 épinglée à la compilation si l'API GitHub est limitée (60 req/h)
    let want_sha = expected_sha256.unwrap_or_else(|| PINNED_ZINK_ZIP_SHA256.to_owned());

    let zip_dest = cache_dir.join(VULKAN_ZINK_ASSET_NAME);
    stream_download_to_file(
        &no_redirect,
        api_asset_url.as_deref(),
        &browser_url,
        &zip_dest,
        |pct| {
            let _ = proxy.send_event(AppEvent::LogLine(format!(
                "[Vulkan Bêta] Téléchargement du traducteur Vulkan Zink : {pct}%..."
            )));
        },
    )
    .map_err(|e| {
        format!(
            "Impossible de télécharger le module Vulkan Bêta ({VULKAN_ZINK_ASSET_NAME}) depuis {repo} : {e}"
        )
    })?;

    let got_sha = sha256_file_hex(&zip_dest)?;
    if !got_sha.eq_ignore_ascii_case(&want_sha) {
        let _ = std::fs::remove_file(&zip_dest);
        return Err(format!(
            "Intégrité SHA-256 invalide pour {VULKAN_ZINK_ASSET_NAME} (attendu {want_sha}, reçu {got_sha})."
        ));
    }

    let staging = cache_dir.join("staging");
    let _ = std::fs::remove_dir_all(&staging);
    extract_zip_to_dir(&zip_dest, &staging)?;
    let _ = std::fs::remove_file(&zip_dest);

    let copy_res = copy_zink_dlls_from_staging(&staging, &cache_dir);
    let _ = std::fs::remove_dir_all(&staging);
    copy_res?;

    let _ = proxy.send_event(AppEvent::LogLine(
        "[Vulkan Bêta] Pilote Mesa3D Zink (OpenGL -> Vulkan) prêt.".to_owned(),
    ));
    Ok(cache_dir)
}

fn mirror_directory_fast(src: &Path, dst: &Path) -> Result<(), String> {
    if dst.exists() {
        return Ok(());
    }
    // Copie native directe sans invoquer cmd.exe /C mklink (élimine tout risque d'injection de commande shell)
    copy_dir_recursive(src, dst)
        .map_err(|e| format!("Copie du dossier runtime '{}' impossible : {e}", src.display()))
}

/// Prépare un JRE Java 8 dédié et isolé (`%APPDATA%\MashClient\runtimes\vulkan-jre8\bin\javaw.exe`)
/// équipé de la redirection DotLocal Windows (`javaw.exe.local`) et des DLL Mesa3D Zink afin que
/// **seule** l'instance `1.8.9 PureFPS Vulkan` utilise Vulkan sans jamais toucher à l'instance OpenGL normale.
pub fn prepare_isolated_vulkan_jre(
    base_java_path: &Path,
    prefs: &LauncherPrefs,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<PathBuf, String> {
    if !base_java_path.is_file() {
        return Err(format!(
            "Exécutable Java 8 introuvable ({}) — installez Java 8 récent dans Paramètres.",
            base_java_path.display()
        ));
    }
    let bin_dir = base_java_path
        .parent()
        .ok_or_else(|| "Dossier bin/ Java 8 invalide.".to_owned())?;
    let base_root = bin_dir
        .parent()
        .ok_or_else(|| "Racine JRE Java 8 invalide.".to_owned())?;

    // Si base_root est un JDK contenant un sous-dossier `jre/`, on cible directement la racine JRE
    let effective_jre_root = if base_root.join("lib").join("rt.jar").is_file() {
        base_root.to_path_buf()
    } else if base_root.join("jre").join("lib").join("rt.jar").is_file() {
        base_root.join("jre")
    } else {
        base_root.to_path_buf()
    };

    let zink_dir = ensure_vulkan_zink_dlls_downloaded(prefs, proxy)?;
    let vk_jre_root = app_storage_dir().join("runtimes").join("vulkan-jre8");
    let stamp_path = vk_jre_root.join(".source_jre.txt");
    let expected_stamp = effective_jre_root.to_string_lossy().to_string();
    let current_stamp = std::fs::read_to_string(&stamp_path).unwrap_or_default();

    let vk_bin = vk_jre_root.join("bin");
    let vk_javaw = vk_bin.join("javaw.exe");
    let vk_rt_jar = vk_jre_root.join("lib").join("rt.jar");
    let vk_cacerts = vk_jre_root.join("lib").join("security").join("cacerts");

    if current_stamp.trim() != expected_stamp.trim()
        || !vk_javaw.is_file()
        || !vk_rt_jar.is_file()
        || !vk_cacerts.is_file()
    {
        let _ = proxy.send_event(AppEvent::LogLine(
            "[Vulkan Bêta] Préparation de l'environnement Java 8 isolé Vulkan...".to_owned(),
        ));
        if vk_jre_root.exists() {
            // Retire proprement toute jonction `lib` avant nettoyage pour ne jamais toucher au JRE source
            let vk_lib = vk_jre_root.join("lib");
            if vk_lib.exists() {
                let _ = std::fs::remove_dir(&vk_lib);
            }
            let _ = std::fs::remove_dir_all(&vk_jre_root);
        }
        std::fs::create_dir_all(&vk_bin).map_err(|e| e.to_string())?;

        copy_dir_recursive(&effective_jre_root.join("bin"), &vk_bin)
            .map_err(|e| format!("Copie bin/ JRE Vulkan : {e}"))?;
        mirror_directory_fast(&effective_jre_root.join("lib"), &vk_jre_root.join("lib"))?;

        let rel_src = effective_jre_root.join("release");
        if rel_src.is_file() {
            let _ = std::fs::copy(&rel_src, vk_jre_root.join("release"));
        }
        let _ = std::fs::write(&stamp_path, &expected_stamp);
    }

    // Active la redirection DotLocal Windows (`javaw.exe.local`) pour contourner KnownDLLs\opengl32.dll
    for dotlocal in ["javaw.exe.local", "java.exe.local"] {
        let dl_path = vk_bin.join(dotlocal);
        if !dl_path.is_file() {
            std::fs::write(&dl_path, b"").map_err(|e| format!("Création {dotlocal} : {e}"))?;
        }
    }

    deploy_vulkan_zink_to_target_dir(&zink_dir, &vk_bin)?;

    Ok(vk_javaw)
}

pub fn patch_opengl32_dll_references_in_place(buf: &mut [u8]) -> usize {
    const TARGET: &[u8; 13] = b"opengl32.dll\0";
    const REPL: &[u8; 13] = b"zinkgl32.dll\0";
    if buf.len() < REPL.len() {
        return 0;
    }
    let mut count = 0usize;
    let mut i = 0usize;
    let limit = buf.len() - REPL.len();
    while i <= limit {
        if buf[i + 12] == 0 && buf[i..i + 12].eq_ignore_ascii_case(&TARGET[..12]) {
            buf[i..i + REPL.len()].copy_from_slice(REPL);
            count += 1;
            i += REPL.len();
        } else {
            i += 1;
        }
    }
    count
}

fn deploy_vulkan_zink_to_target_dir(zink_dir: &Path, target_dir: &Path) -> Result<PathBuf, String> {
    std::fs::create_dir_all(target_dir).map_err(|e| e.to_string())?;
    for dll in std::iter::once(&VULKAN_CORE_DLL).chain(VULKAN_OPTIONAL_COMPANION_DLLS.iter()) {
        let src_dll = zink_dir.join(dll);
        if !src_dll.is_file() {
            if *dll == VULKAN_CORE_DLL {
                return Err(format!(
                    "Bibliothèque principale Vulkan '{VULKAN_CORE_DLL}' introuvable dans le cache Zink."
                ));
            }
            continue;
        }
        let dst_dll = target_dir.join(dll);
        let need_copy = match (std::fs::metadata(&src_dll), std::fs::metadata(&dst_dll)) {
            (Ok(sm), Ok(dm)) => sm.len() != dm.len(),
            (Ok(_), Err(_)) => true,
            _ => false,
        };
        if need_copy {
            std::fs::copy(&src_dll, &dst_dll)
                .map_err(|e| format!("Déploiement Vulkan '{dll}' : {e}"))?;
        }
    }

    // Génère zinkgl32.dll (hors Windows KnownDLLs) et patche lwjgl64.dll dans natives-1.8.9-vulkan
    let core_dst = target_dir.join(VULKAN_CORE_DLL);
    let alias_dst = target_dir.join("zinkgl32.dll");
    let need_alias = match (std::fs::metadata(&core_dst), std::fs::metadata(&alias_dst)) {
        (Ok(sm), Ok(dm)) => sm.len() != dm.len(),
        (Ok(_), Err(_)) => true,
        _ => false,
    };
    if need_alias {
        if let Ok(mut bytes) = std::fs::read(&core_dst) {
            patch_opengl32_dll_references_in_place(&mut bytes);
            let _ = std::fs::write(&alias_dst, &bytes);
        }
    }

    let lwjgl_dst = target_dir.join("lwjgl64.dll");
    if lwjgl_dst.is_file() && alias_dst.is_file() {
        if let Ok(mut lwjgl_bytes) = std::fs::read(&lwjgl_dst) {
            if patch_opengl32_dll_references_in_place(&mut lwjgl_bytes) > 0 {
                let _ = std::fs::write(&lwjgl_dst, &lwjgl_bytes);
            }
        }
    }

    Ok(core_dst)
}

/// Copie `opengl32.dll` (Mesa3D Zink) et ses DLL compagnes directement dans le dossier `natives-1.8.9-vulkan`
/// à côté de `lwjgl64.dll` afin que `LoadLibraryExW(..., LOAD_WITH_ALTERED_SEARCH_PATH)` charge Zink
/// en priorité absolue au lieu de `C:\Windows\System32\opengl32.dll`.
pub fn deploy_vulkan_zink_dlls_to_dir(
    target_dir: &Path,
    prefs: &LauncherPrefs,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<PathBuf, String> {
    let zink_dir = ensure_vulkan_zink_dlls_downloaded(prefs, proxy)?;
    deploy_vulkan_zink_to_target_dir(&zink_dir, target_dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_java8_update_numbers() {
        assert_eq!(parse_java8_update_number("1.8.0_442"), Some(442));
        assert_eq!(
            parse_java8_update_number(r"C:\Program Files\Eclipse Adoptium\jre-8.0.432.6-hotspot\bin\javaw.exe"),
            Some(432)
        );
        assert_eq!(parse_java8_update_number("OpenJDK8U-jdk8u412-b08"), Some(412));
        assert_eq!(
            parse_java8_update_number(r"C:\Minecraft\runtime\jre-legacy\bin\javaw.exe"),
            Some(51)
        );
    }

    #[test]
    fn test_vulkan_unified_standalone_game_dir() {
        let tmp = std::env::temp_dir().join("mashclient_vk_standalone_test");
        let _ = std::fs::remove_dir_all(&tmp);
        let base_mc = tmp.join(".minecraft");
        std::fs::create_dir_all(base_mc.join("config")).unwrap();
        std::fs::write(base_mc.join("options.txt"), "fov:0.5\n").unwrap();

        let mut prefs = LauncherPrefs::default();
        prefs.game_dir = base_mc.to_string_lossy().to_string();
        prefs.instance_flavor = crate::config::InstanceFlavor::Standard;
        assert_eq!(resolve_active_game_dir(&prefs), prefs.game_dir);

        prefs.instance_flavor = crate::config::InstanceFlavor::VulkanBeta;
        let vk_dir = ensure_vulkan_instance_initialized(&prefs).unwrap();
        assert_eq!(vk_dir, prefs.game_dir);
        assert!(Path::new(&vk_dir).join("resourcepacks").is_dir());
        assert!(Path::new(&vk_dir).join("mods").is_dir());
        assert!(Path::new(&vk_dir).join("options.txt").is_file());

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_vulkan_zink_bundle_staging_and_validation() {
        let tmp = std::env::temp_dir().join("mashclient_vk_zink_bundle_test");
        let _ = std::fs::remove_dir_all(&tmp);
        let staging = tmp.join("staging");
        let cache = tmp.join("cache");
        std::fs::create_dir_all(&staging).unwrap();
        std::fs::create_dir_all(&cache).unwrap();

        assert!(!has_valid_vulkan_zink_bundle(&cache));
        let dummy_dll = vec![0x90u8; 80_000];
        std::fs::write(staging.join("opengl32.dll"), &dummy_dll).unwrap();

        copy_zink_dlls_from_staging(&staging, &cache).unwrap();
        assert!(has_valid_vulkan_zink_bundle(&cache));
        assert_eq!(std::fs::metadata(cache.join("opengl32.dll")).unwrap().len(), 80_000);

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_patch_opengl32_dll_references_in_place() {
        let mut sample = b"prefix\0OPENGL32.dll\0middle\0opengl32.dll\0suffix".to_vec();
        let patched = patch_opengl32_dll_references_in_place(&mut sample);
        assert_eq!(patched, 2);
        assert_eq!(
            &sample[..],
            b"prefix\0zinkgl32.dll\0middle\0zinkgl32.dll\0suffix"
        );
    }
}
