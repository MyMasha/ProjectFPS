//! Auto-Updater GitHub Releases natif pour MashClient 4.0 — Singularity Edition.
//! - Compatible dépôts publics ET privés (`MyMasha/ProjectFPS`) via Fine-Grained PAT Read-Only (`Contents: Read`)
//! - Téléchargement binaire en 2 étapes (`Accept: application/octet-stream` sans redirection auto -> URL signée S3/Blob sans en-tête Authorization)
//! - Vérification d'intégrité des en-têtes binaires (`PK\x03\x04` pour le mod `.jar`, `MZ` pour le launcher `MashClient.exe`)
//! - Auto-remplacement atomique à chaud sous Windows (`MashClient.exe` -> `.exe.old`) avec restauration automatique en cas d'erreur.

use crate::config::app_storage_dir;
use crate::AppEvent;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;
use tao::event_loop::EventLoopProxy;

pub const LAUNCHER_VERSION: &str = env!("CARGO_PKG_VERSION");
const USER_AGENT: &str = "MashClient-Singularity-Updater/4.0";
const GH_API_VERSION: &str = "2022-11-28";

#[derive(Debug, Clone)]
pub struct ReleaseAssetPlan {
    pub name: String,
    pub api_url: String,
    pub browser_download_url: String,
    pub size: u64,
}

#[derive(Debug, Clone)]
pub struct PendingUpdate {
    pub tag_name: String,
    pub version: String,
    pub release_name: String,
    pub body: String,
    pub html_url: String,
    pub jar_asset: Option<ReleaseAssetPlan>,
    pub exe_asset: Option<ReleaseAssetPlan>,
}

#[derive(Debug, Clone)]
pub enum UpdateCheckOutcome {
    UpToDate {
        latest_version: String,
        installed_jar: Option<String>,
    },
    NoReleaseYet {
        repo: String,
    },
    Available(PendingUpdate),
}

#[derive(Debug, Clone)]
pub struct InstallOutcome {
    pub version: String,
    pub jar_installed: Option<String>,
    pub exe_staged_for_restart: bool,
}

/// Vue sérialisée vers l'interface WebView2 (aucun jeton secret n'y figure jamais).
#[derive(Debug, Clone, Serialize)]
pub struct UiUpdateView {
    pub status: String,
    pub current_version: String,
    pub installed_jar_version: Option<String>,
    pub latest_version: Option<String>,
    pub release_notes: Option<String>,
    pub download_percent: u8,
    pub message: String,
    pub has_exe_update: bool,
    pub has_jar_update: bool,
}

impl UiUpdateView {
    pub fn initial(game_dir: &str) -> Self {
        let installed_jar_version = installed_mod_version(game_dir).map(format_semver);
        Self {
            status: "idle".to_owned(),
            current_version: LAUNCHER_VERSION.to_owned(),
            installed_jar_version,
            latest_version: None,
            release_notes: None,
            download_percent: 0,
            message: "Prêt à vérifier les mises à jour GitHub Releases.".to_owned(),
            has_exe_update: false,
            has_jar_update: false,
        }
    }
}

#[derive(Deserialize)]
struct GhRelease {
    tag_name: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    assets: Vec<GhAsset>,
}

#[derive(Deserialize)]
struct GhAsset {
    name: String,
    url: String,
    #[serde(default)]
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

/// Analyse une version sémantique `Majeur.Mineur.Patch` depuis un tag (`v3.6.0`) ou un nom d'archive (`purefps-3.6.0.jar`).
pub fn parse_semver(raw: &str) -> Option<(u32, u32, u32)> {
    let mut s = raw.trim();
    for suffix in [".jar", ".exe", ".zip"] {
        if let Some(stripped) = s.strip_suffix(suffix) {
            s = stripped;
        }
    }
    let lower = s.to_ascii_lowercase();
    for prefix in [
        "mashclient-v",
        "mashclient-",
        "mashfps-v",
        "mashfps-",
        "purefps-v",
        "purefps-",
        "pureclient-launcher-v",
        "pureclient-launcher-",
        "pureclient-v",
        "pureclient-",
        "release-v",
        "release-",
        "v",
    ] {
        if lower.starts_with(prefix) {
            s = &s[prefix.len()..];
            break;
        }
    }

    // Recherche le premier motif numérique X.Y(.Z) dans la chaîne nettoyée
    let token = s
        .split(|c: char| !(c.is_ascii_digit() || c == '.'))
        .find(|part| part.contains('.') && part.chars().next().is_some_and(|c| c.is_ascii_digit()))?;

    let mut nums = token.split('.');
    let major = nums.next()?.parse::<u32>().ok()?;
    let minor = nums.next()?.parse::<u32>().ok()?;
    let patch = match nums.next() {
        Some(p) if !p.is_empty() => p.parse::<u32>().ok()?,
        _ => 0,
    };
    Some((major, minor, patch))
}

pub fn format_semver(v: (u32, u32, u32)) -> String {
    format!("{}.{}.{}", v.0, v.1, v.2)
}

pub fn extract_purefps_jar_semver(filename: &str) -> Option<(u32, u32, u32)> {
    let lower = filename.to_ascii_lowercase();
    if !lower.starts_with("purefps-") || !lower.ends_with(".jar") {
        return None;
    }
    if lower.ends_with("-sources.jar") || lower.ends_with("-dev.jar") {
        return None;
    }
    parse_semver(filename)
}

pub fn installed_mod_version(game_dir: &str) -> Option<(u32, u32, u32)> {
    let mut best: Option<(u32, u32, u32)> = None;
    let dirs = [
        Path::new(game_dir).join("mods"),
        app_storage_dir().join("updates"),
    ];
    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if let Some(ver) = extract_purefps_jar_semver(&name) {
                if best.is_none_or(|cur| ver > cur) {
                    best = Some(ver);
                }
            }
        }
    }
    best
}

pub fn sanitize_repo(raw: &str) -> Result<String, String> {
    let mut s = raw.trim();
    if let Some(rest) = s.strip_prefix("https://github.com/") {
        s = rest;
    } else if let Some(rest) = s.strip_prefix("http://github.com/") {
        s = rest;
    }
    s = s.trim_end_matches('/');
    if let Some(rest) = s.strip_suffix(".git") {
        s = rest;
    }

    let Some((owner, repo)) = s.split_once('/') else {
        return Err("Format du dépôt GitHub invalide (attendu : Propriétaire/Dépôt).".to_owned());
    };
    let valid_part = |p: &str| {
        !p.is_empty()
            && !p.contains('/')
            && p.chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
    };
    if !valid_part(owner) || !valid_part(repo) {
        return Err("Nom de dépôt GitHub invalide.".to_owned());
    }
    Ok(format!("{owner}/{repo}"))
}

fn build_agents() -> Result<(ureq::Agent, ureq::Agent), String> {
    let tls = Arc::new(
        native_tls::TlsConnector::new()
            .map_err(|e| format!("Initialisation TLS impossible: {e}"))?,
    );
    let standard = ureq::AgentBuilder::new()
        .tls_connector(Arc::clone(&tls))
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(90))
        .timeout_write(Duration::from_secs(10))
        .build();
    let no_redirect = ureq::AgentBuilder::new()
        .tls_connector(tls)
        .redirects(0)
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(30))
        .timeout_write(Duration::from_secs(10))
        .build();
    Ok((standard, no_redirect))
}

fn apply_gh_headers(req: ureq::Request, token: Option<&str>, accept: &str) -> ureq::Request {
    let req = req
        .set("Accept", accept)
        .set("User-Agent", USER_AGENT)
        .set("X-GitHub-Api-Version", GH_API_VERSION);
    match token.map(str::trim).filter(|t| !t.is_empty()) {
        Some(tok) => req.set("Authorization", &format!("Bearer {tok}")),
        None => req,
    }
}

/// Interroge `GET /repos/{owner}/{repo}/releases/latest` et détermine si une mise à jour du `.jar` ou du `.exe` est disponible.
pub fn check_for_updates(
    raw_repo: &str,
    token: Option<&str>,
    game_dir: &str,
) -> Result<UpdateCheckOutcome, String> {
    let repo = sanitize_repo(raw_repo)?;
    let (agent, _) = build_agents()?;

    let latest_url = format!("https://api.github.com/repos/{repo}/releases/latest");
    let req = apply_gh_headers(agent.get(&latest_url), token, "application/vnd.github+json");

    let rel: GhRelease = match req.call() {
        Ok(resp) => resp
            .into_json::<GhRelease>()
            .map_err(|e| format!("Réponse GitHub Release illisible: {e}"))?,
        Err(ureq::Error::Status(404, _)) => {
            // Distingue "0 release publiée sur un dépôt valide" de "dépôt privé sans jeton / introuvable"
            let repo_url = format!("https://api.github.com/repos/{repo}");
            let repo_check =
                apply_gh_headers(agent.get(&repo_url), token, "application/vnd.github+json").call();
            return match repo_check {
                Ok(_) => Ok(UpdateCheckOutcome::NoReleaseYet { repo }),
                Err(ureq::Error::Status(401 | 403, _)) => Err(
                    "Jeton GitHub refusé (401/403). Vérifiez la permission Read-Only sur Contents."
                        .to_owned(),
                ),
                Err(ureq::Error::Status(404, _)) => {
                    if token.map(str::trim).unwrap_or("").is_empty() {
                        Err(format!(
                            "Dépôt privé '{repo}' inaccessible sans jeton GitHub Read-Only."
                        ))
                    } else {
                        Err(format!(
                            "Dépôt '{repo}' introuvable ou non couvert par ce jeton Fine-Grained."
                        ))
                    }
                }
                Err(e) => Err(format!("Impossible de vérifier le dépôt GitHub: {e}")),
            };
        }
        Err(ureq::Error::Status(401, _)) => {
            return Err("Jeton GitHub invalide ou expiré (HTTP 401).".to_owned());
        }
        Err(ureq::Error::Status(403, _)) => {
            return Err(
                "Accès GitHub refusé (HTTP 403) : permission Contents:Read manquante ou quota atteint."
                    .to_owned(),
            );
        }
        Err(ureq::Error::Status(code, err_resp)) => {
            let detail = err_resp.into_string().unwrap_or_default();
            return Err(format!("Erreur GitHub API HTTP {code}: {detail}"));
        }
        Err(e) => return Err(format!("Connexion à GitHub impossible: {e}")),
    };

    let rel_ver = parse_semver(&rel.tag_name)
        .or_else(|| parse_semver(&rel.name))
        .ok_or_else(|| {
            format!(
                "Tag de release '{}' non reconnu (utilisez ex: v4.0.0).",
                rel.tag_name
            )
        })?;
    let rel_ver_str = format_semver(rel_ver);

    let jar_asset = rel
        .assets
        .iter()
        .find(|a| {
            let low = a.name.to_ascii_lowercase();
            low.ends_with(".jar")
                && !low.ends_with("-sources.jar")
                && !low.ends_with("-dev.jar")
                && (low.starts_with("purefps")
                    || low.starts_with("mashclient")
                    || low.starts_with("pureclient"))
        })
        .or_else(|| {
            rel.assets.iter().find(|a| {
                let low = a.name.to_ascii_lowercase();
                low.ends_with(".jar")
                    && !low.ends_with("-sources.jar")
                    && !low.ends_with("-dev.jar")
            })
        })
        .map(|a| ReleaseAssetPlan {
            name: a.name.clone(),
            api_url: a.url.clone(),
            browser_download_url: a.browser_download_url.clone(),
            size: a.size,
        });

    let exe_asset = rel
        .assets
        .iter()
        .find(|a| {
            let low = a.name.to_ascii_lowercase();
            low.ends_with(".exe") && low.contains("mash")
        })
        .or_else(|| {
            rel.assets
                .iter()
                .find(|a| a.name.to_ascii_lowercase().ends_with(".exe"))
        })
        .map(|a| ReleaseAssetPlan {
            name: a.name.clone(),
            api_url: a.url.clone(),
            browser_download_url: a.browser_download_url.clone(),
            size: a.size,
        });

    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((4, 0, 0));
    let mod_ver = installed_mod_version(game_dir);

    let needs_exe = exe_asset.is_some() && rel_ver > launcher_ver;
    let needs_jar = jar_asset.is_some() && mod_ver.map(|cur| rel_ver > cur).unwrap_or(true);

    if !needs_exe && !needs_jar {
        return Ok(UpdateCheckOutcome::UpToDate {
            latest_version: rel_ver_str,
            installed_jar: mod_ver.map(format_semver),
        });
    }

    Ok(UpdateCheckOutcome::Available(PendingUpdate {
        tag_name: rel.tag_name,
        version: rel_ver_str,
        release_name: if rel.name.trim().is_empty() {
            format!("MashClient {}", format_semver(rel_ver))
        } else {
            rel.name
        },
        body: rel.body,
        html_url: rel.html_url,
        jar_asset: if needs_jar { jar_asset } else { None },
        exe_asset: if needs_exe { exe_asset } else { None },
    }))
}

fn open_github_asset_stream(
    standard_agent: &ureq::Agent,
    no_redirect_agent: &ureq::Agent,
    asset: &ReleaseAssetPlan,
    token: Option<&str>,
) -> Result<ureq::Response, String> {
    let api_req = apply_gh_headers(
        no_redirect_agent.get(&asset.api_url),
        token,
        "application/octet-stream",
    );

    match api_req.call() {
        Ok(resp) => {
            if matches!(resp.status(), 301 | 302 | 303 | 307 | 308) {
                if let Some(loc) = resp.header("Location") {
                    // Important : ne JAMAIS transmettre l'en-tête Authorization GitHub vers l'URL pré-signée S3 / Azure Blob !
                    return standard_agent
                        .get(loc)
                        .set("User-Agent", USER_AGENT)
                        .call()
                        .map_err(|e| format!("Téléchargement du flux signé '{}' échoué: {e}", asset.name));
                }
            }
            Ok(resp)
        }
        Err(ureq::Error::Status(code @ (301 | 302 | 303 | 307 | 308), redirect_resp)) => {
            if let Some(loc) = redirect_resp.header("Location") {
                standard_agent
                    .get(loc)
                    .set("User-Agent", USER_AGENT)
                    .call()
                    .map_err(|e| {
                        format!(
                            "Téléchargement après redirection HTTP {code} '{}' échoué: {e}",
                            asset.name
                        )
                    })
            } else {
                Err(format!(
                    "Redirection GitHub HTTP {code} sans en-tête Location pour '{}'.",
                    asset.name
                ))
            }
        }
        Err(e) => {
            // Repli pour dépôts publics sans jeton si browser_download_url est disponible
            if token.map(str::trim).unwrap_or("").is_empty()
                && !asset.browser_download_url.is_empty()
            {
                standard_agent
                    .get(&asset.browser_download_url)
                    .set("User-Agent", USER_AGENT)
                    .call()
                    .map_err(|e2| format!("Téléchargement '{}' impossible: {e2}", asset.name))
            } else {
                Err(format!("Téléchargement API GitHub '{}' refusé: {e}", asset.name))
            }
        }
    }
}

fn stream_asset_to_part_file(
    standard_agent: &ureq::Agent,
    no_redirect_agent: &ureq::Agent,
    asset: &ReleaseAssetPlan,
    token: Option<&str>,
    part_path: &Path,
    expected_magic: &[u8],
    min_bytes: u64,
    base_done: u64,
    total_plan_bytes: u64,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<u64, String> {
    let resp = open_github_asset_stream(standard_agent, no_redirect_agent, asset, token)?;
    let mut reader = resp.into_reader();
    let mut out = File::create(part_path)
        .map_err(|e| format!("Création fichier temporaire {}: {e}", part_path.display()))?;

    let mut buf = [0u8; 32 * 1024];
    let mut header_probe = Vec::with_capacity(expected_magic.len());
    let mut written: u64 = 0;
    let mut last_pct: u8 = 255;

    loop {
        let n = reader
            .read(&mut buf)
            .map_err(|e| format!("Lecture réseau interrompue ({}): {e}", asset.name))?;
        if n == 0 {
            break;
        }
        if header_probe.len() < expected_magic.len() {
            let need = expected_magic.len() - header_probe.len();
            header_probe.extend_from_slice(&buf[..n.min(need)]);
        }
        out.write_all(&buf[..n])
            .map_err(|e| format!("Écriture disque ({}): {e}", asset.name))?;
        written += n as u64;

        let denom = total_plan_bytes.max(written + base_done).max(1);
        let pct = (((base_done + written) * 100) / denom).min(99) as u8;
        if pct != last_pct {
            last_pct = pct;
            let _ = proxy.send_event(AppEvent::UpdateProgress {
                percent: pct,
                message: format!("Téléchargement de {} ({pct}%)...", asset.name),
            });
        }
    }
    out.flush()
        .map_err(|e| format!("Validation écriture {}: {e}", asset.name))?;
    drop(out);

    if written < min_bytes || header_probe.as_slice() != expected_magic {
        let _ = std::fs::remove_file(part_path);
        return Err(format!(
            "Fichier téléchargé '{}' corrompu ou incomplet ({} octets).",
            asset.name, written
        ));
    }

    Ok(written)
}

/// Télécharge et installe atomiquement les composants mis à jour (`.jar` du mod et/ou `.exe` du Launcher).
pub fn download_and_install_update(
    plan: &PendingUpdate,
    token: Option<&str>,
    game_dir: &str,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<InstallOutcome, String> {
    let (standard_agent, no_redirect_agent) = build_agents()?;
    let updates_dir = app_storage_dir().join("updates");
    std::fs::create_dir_all(&updates_dir)
        .map_err(|e| format!("Création dossier updates: {e}"))?;

    let total_bytes = plan.jar_asset.as_ref().map_or(0, |a| a.size)
        + plan.exe_asset.as_ref().map_or(0, |a| a.size);
    let mut completed_bytes: u64 = 0;

    let mut jar_installed: Option<String> = None;
    let mut exe_staged_for_restart = false;

    // 1. Mise à jour du mod Forge 1.8.9 (`purefps-X.Y.Z.jar`)
    if let Some(ref jar) = plan.jar_asset {
        let canonical_name = if jar.name.to_ascii_lowercase().starts_with("purefps-") {
            jar.name.clone()
        } else {
            format!("purefps-{}.jar", plan.version)
        };
        let part_path = updates_dir.join(format!("{canonical_name}.part"));
        let final_cache_path = updates_dir.join(&canonical_name);

        let dl_bytes = stream_asset_to_part_file(
            &standard_agent,
            &no_redirect_agent,
            jar,
            token,
            &part_path,
            b"PK\x03\x04",
            4096,
            completed_bytes,
            total_bytes,
            proxy,
        )?;
        completed_bytes += dl_bytes;

        let _ = std::fs::remove_file(&final_cache_path);
        std::fs::rename(&part_path, &final_cache_path)
            .map_err(|e| format!("Validation archive {canonical_name}: {e}"))?;

        // Purge les anciennes versions dans le cache `updates/`
        if let Ok(entries) = std::fs::read_dir(&updates_dir) {
            for entry in entries.flatten() {
                let fname = entry.file_name().to_string_lossy().to_string();
                if fname.starts_with("purefps-")
                    && fname.ends_with(".jar")
                    && fname != canonical_name
                {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
        }

        // Déploie directement dans `<game_dir>/mods/` si le dossier d'instance est accessible
        let mods_dir = Path::new(game_dir).join("mods");
        if std::fs::create_dir_all(&mods_dir).is_ok() {
            let target_mod = mods_dir.join(&canonical_name);
            if std::fs::copy(&final_cache_path, &target_mod).is_ok() {
                if let Ok(entries) = std::fs::read_dir(&mods_dir) {
                    for entry in entries.flatten() {
                        let fname = entry.file_name().to_string_lossy().to_string();
                        if fname.starts_with("purefps-")
                            && fname.ends_with(".jar")
                            && fname != canonical_name
                        {
                            let _ = std::fs::remove_file(entry.path());
                        }
                    }
                }
            }
        }

        jar_installed = Some(canonical_name);
    }

    // 2. Auto-remplacement atomique de l'exécutable Windows (`pureclient-launcher.exe`)
    if let Some(ref exe) = plan.exe_asset {
        let current_exe = std::env::current_exe()
            .map_err(|e| format!("Chemin de l'exécutable courant inaccessible: {e}"))?;
        let new_exe = with_appended_ext(&current_exe, "new");
        let old_exe = with_appended_ext(&current_exe, "old");

        let _ = std::fs::remove_file(&new_exe);
        let _ = stream_asset_to_part_file(
            &standard_agent,
            &no_redirect_agent,
            exe,
            token,
            &new_exe,
            b"MZ",
            32 * 1024,
            completed_bytes,
            total_bytes,
            proxy,
        )?;

        let _ = std::fs::remove_file(&old_exe);
        std::fs::rename(&current_exe, &old_exe).map_err(|e| {
            let _ = std::fs::remove_file(&new_exe);
            format!("Impossible de renommer l'exécutable actif pour mise à jour: {e}")
        })?;

        if let Err(e) = std::fs::rename(&new_exe, &current_exe) {
            // Restauration immédiate si le déplacement final échoue
            let _ = std::fs::rename(&old_exe, &current_exe);
            let _ = std::fs::remove_file(&new_exe);
            return Err(format!("Échec de l'installation du nouvel exécutable: {e}"));
        }

        exe_staged_for_restart = true;
    }

    let _ = proxy.send_event(AppEvent::UpdateProgress {
        percent: 100,
        message: format!("Mise à jour v{} installée.", plan.version),
    });

    Ok(InstallOutcome {
        version: plan.version.clone(),
        jar_installed,
        exe_staged_for_restart,
    })
}

fn with_appended_ext(base: &Path, suffix: &str) -> PathBuf {
    let mut os = base.as_os_str().to_os_string();
    os.push(".");
    os.push(suffix);
    PathBuf::from(os)
}

/// Nettoie silencieusement au démarrage l'ancien binaire `.exe.old` laissé par une mise à jour précédente.
pub fn cleanup_old_launcher_binary() {
    if let Ok(current_exe) = std::env::current_exe() {
        let old_a = with_appended_ext(&current_exe, "old");
        let _ = std::fs::remove_file(old_a);
        let old_b = current_exe.with_extension("exe.old");
        let _ = std::fs::remove_file(old_b);
    }
}

/// Relance le nouvel exécutable PureClient installé à l'emplacement courant.
pub fn restart_launcher_process() -> Result<(), String> {
    let current_exe =
        std::env::current_exe().map_err(|e| format!("Exécutable introuvable: {e}"))?;
    Command::new(&current_exe)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("Impossible de relancer {}: {e}", current_exe.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_semver_variants() {
        assert_eq!(parse_semver("v4.0.0"), Some((4, 0, 0)));
        assert_eq!(parse_semver("mashclient-v4.0.0"), Some((4, 0, 0)));
        assert_eq!(parse_semver("MashClient v4.1.2"), Some((4, 1, 2)));
        assert_eq!(parse_semver("v3.5.0"), Some((3, 5, 0)));
        assert_eq!(parse_semver("3.6.2"), Some((3, 6, 2)));
        assert_eq!(parse_semver("purefps-4.0.0.jar"), Some((4, 0, 0)));
        assert_eq!(parse_semver("PureClient v4.0"), Some((4, 0, 0)));
        assert_eq!(
            extract_purefps_jar_semver("purefps-4.0.0.jar"),
            Some((4, 0, 0))
        );
        assert_eq!(extract_purefps_jar_semver("purefps-4.0.0-sources.jar"), None);
    }

    #[test]
    fn test_sanitize_repo() {
        assert_eq!(
            sanitize_repo("MyMasha/ProjectFPS").unwrap(),
            "MyMasha/ProjectFPS"
        );
        assert_eq!(
            sanitize_repo("https://github.com/MyMasha/ProjectFPS.git").unwrap(),
            "MyMasha/ProjectFPS"
        );
        assert!(sanitize_repo("invalid_repo_without_slash").is_err());
    }
}
