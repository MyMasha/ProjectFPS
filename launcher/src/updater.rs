//! Auto-Updater GitHub Releases natif pour MashClient.
//! - Interroge directement l'API publique GitHub Releases de `MyMasha/ProjectFPS` (sans aucun jeton)
//! - Téléchargement binaire via redirections HTTPS strictement vérifiées + contrôle d'empreintes SHA-256
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
pub const OFFICIAL_GITHUB_REPO: &str = "MyMasha/ProjectFPS";
const USER_AGENT: &str = "MashClient-Updater/1.0.0";
const GH_API_VERSION: &str = "2022-11-28";
const MAX_UPDATE_ASSET_BYTES: u64 = 256 * 1024 * 1024;
const MAX_CHECKSUMS_BYTES: u64 = 64 * 1024;

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
    pub checksums_asset: Option<ReleaseAssetPlan>,
}

#[derive(Debug, Clone)]
#[allow(clippy::large_enum_variant)]
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
            message: "Ready to check for updates.".to_owned(),
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

/// Les versions de développement pré-v1.0.0 portaient une numérotation 3.x / 4.x.
/// Tant que le launcher public est en branche 1.x / 2.x, on ignore ces anciens JAR hérités
/// afin que l'Auto-Updater télécharge automatiquement `purefps-1.x.y.jar`.
pub fn is_legacy_pre_v1_mod_version(
    mod_ver: (u32, u32, u32),
    reference_ver: (u32, u32, u32),
) -> bool {
    reference_ver.0 < 3 && matches!(mod_ver.0, 3 | 4)
}

pub fn installed_mod_version(game_dir: &str) -> Option<(u32, u32, u32)> {
    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((1, 0, 0));
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
                if is_legacy_pre_v1_mod_version(ver, launcher_ver) {
                    continue;
                }
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
            && p != "."
            && p != ".."
            && p.chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
    };
    if !valid_part(owner) || !valid_part(repo) {
        return Err("Nom de dépôt GitHub invalide.".to_owned());
    }
    let normalized = format!("{owner}/{repo}");
    #[cfg(debug_assertions)]
    let allow_custom = std::env::var("MASHCLIENT_ALLOW_CUSTOM_REPO").as_deref() == Ok("1");
    #[cfg(not(debug_assertions))]
    let allow_custom = false;
    if !normalized.eq_ignore_ascii_case(OFFICIAL_GITHUB_REPO) && !allow_custom {
        return Err(format!(
            "Dépôt non autorisé par sécurité : seul le dépôt officiel '{OFFICIAL_GITHUB_REPO}' est accepté."
        ));
    }
    if normalized.eq_ignore_ascii_case(OFFICIAL_GITHUB_REPO) {
        Ok(OFFICIAL_GITHUB_REPO.to_owned())
    } else {
        Ok(normalized)
    }
}

/// Extrait et valide strictement le nom d'hôte ASCII d'une URL `https://` :
/// rejette les antislashs (`\`, différentiel WHATWG), `@`, ports autres que 443,
/// caractères de contrôle/espaces, labels vides et caractères hors `[a-z0-9.-]`.
pub fn extract_valid_https_host(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://")?;
    if url.bytes().any(|b| b <= 0x20 || b == 0x7F || b == b'\\') {
        return None;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    if authority.is_empty() || authority.contains('@') {
        return None;
    }
    let host_raw = match authority.rsplit_once(':') {
        Some((h, "443")) => h,
        Some(_) => return None,
        None => authority,
    };
    if host_raw.is_empty()
        || host_raw.len() > 253
        || host_raw.starts_with('.')
        || host_raw.ends_with('.')
        || host_raw.contains("..")
    {
        return None;
    }
    let host = host_raw.to_ascii_lowercase();
    if !host
        .bytes()
        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-')
    {
        return None;
    }
    if host
        .split('.')
        .any(|lbl| lbl.is_empty() || lbl.starts_with('-') || lbl.ends_with('-'))
    {
        return None;
    }
    Some(host)
}

pub fn is_trusted_github_api_url(url: &str) -> bool {
    if !url.starts_with("https://api.github.com/repos/") {
        return false;
    }
    extract_valid_https_host(url).is_some_and(|h| h == "api.github.com")
}

pub fn is_trusted_download_url(url: &str) -> bool {
    let Some(host) = extract_valid_https_host(url) else {
        return false;
    };

    const ALLOWED_SUFFIXES: &[&str] = &[
        "github.com",
        "api.github.com",
        "githubusercontent.com",
        "adoptium.net",
    ];
    ALLOWED_SUFFIXES
        .iter()
        .any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

/// Implémentation FIPS 180-4 SHA-256 autonome (sans dépendance externe) pour vérifier l'intégrité des binaires.
pub struct Sha256 {
    state: [u32; 8],
    buf: [u8; 64],
    buf_len: usize,
    total_bits: u64,
}

impl Sha256 {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    pub fn new() -> Self {
        Self {
            state: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
            ],
            buf: [0u8; 64],
            buf_len: 0,
            total_bits: 0,
        }
    }

    fn compress(&mut self, block: &[u8; 64]) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                block[i * 4],
                block[i * 4 + 1],
                block[i * 4 + 2],
                block[i * 4 + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.state;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = h
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(Self::K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);

            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        self.state[0] = self.state[0].wrapping_add(a);
        self.state[1] = self.state[1].wrapping_add(b);
        self.state[2] = self.state[2].wrapping_add(c);
        self.state[3] = self.state[3].wrapping_add(d);
        self.state[4] = self.state[4].wrapping_add(e);
        self.state[5] = self.state[5].wrapping_add(f);
        self.state[6] = self.state[6].wrapping_add(g);
        self.state[7] = self.state[7].wrapping_add(h);
    }

    pub fn update(&mut self, mut data: &[u8]) {
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

    pub fn finalize_hex(mut self) -> String {
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

        let mut out = String::with_capacity(64);
        for word in self.state {
            use std::fmt::Write as _;
            let _ = write!(&mut out, "{word:08x}");
        }
        out
    }
}

pub fn sha256_file_hex(path: &Path) -> Result<String, String> {
    let mut f = File::open(path).map_err(|e| format!("Lecture SHA-256 {}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 32 * 1024];
    loop {
        let n = f
            .read(&mut buf)
            .map_err(|e| format!("Calcul SHA-256 {}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize_hex())
}

pub fn parse_sha256sums(raw: &str) -> std::collections::BTreeMap<String, String> {
    let mut map = std::collections::BTreeMap::new();
    for line in raw.lines() {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') {
            continue;
        }
        let mut parts = t.split_whitespace();
        let Some(hash) = parts.next() else { continue };
        let Some(fname) = parts.next() else { continue };
        let clean_name = fname.trim_start_matches('*');
        if hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()) && !clean_name.is_empty() {
            map.insert(clean_name.to_ascii_lowercase(), hash.to_ascii_lowercase());
        }
    }
    map
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
        .timeout_read(Duration::from_secs(90))
        .timeout_write(Duration::from_secs(10))
        .build();
    Ok((standard, no_redirect))
}

fn apply_gh_headers(req: ureq::Request, accept: &str) -> ureq::Request {
    req.set("Accept", accept)
        .set("User-Agent", USER_AGENT)
        .set("X-GitHub-Api-Version", GH_API_VERSION)
}

fn is_installer_exe_name(lower_name: &str) -> bool {
    lower_name.contains("setup")
        || lower_name.contains("installer")
        || lower_name.contains("unins")
}

fn select_portable_exe_asset(assets: &[GhAsset]) -> Option<ReleaseAssetPlan> {
    let to_plan = |a: &GhAsset| ReleaseAssetPlan {
        name: a.name.clone(),
        api_url: a.url.clone(),
        browser_download_url: a.browser_download_url.clone(),
        size: a.size,
    };

    // 1. Priorité absolue à l'exécutable Windows Portable dédié aux mises à jour
    assets
        .iter()
        .find(|a| {
            let low = a.name.to_ascii_lowercase();
            low.ends_with(".exe") && low.contains("portable") && !is_installer_exe_name(&low)
        })
        // 2. Repli rétrocompatible exact sur MashClient.exe
        .or_else(|| {
            assets
                .iter()
                .find(|a| a.name.eq_ignore_ascii_case("MashClient.exe"))
        })
        // 3. Repli sur tout binaire MashClient/PureFPS qui n'est PAS un installateur Setup
        .or_else(|| {
            assets.iter().find(|a| {
                let low = a.name.to_ascii_lowercase();
                low.ends_with(".exe")
                    && (low.contains("mash") || low.contains("pure"))
                    && !is_installer_exe_name(&low)
            })
        })
        // 4. Dernier repli sur tout .exe non-installateur
        .or_else(|| {
            assets.iter().find(|a| {
                let low = a.name.to_ascii_lowercase();
                low.ends_with(".exe") && !is_installer_exe_name(&low)
            })
        })
        .map(to_plan)
}

/// Interroge `GET /repos/{owner}/{repo}/releases/latest` (API publique, sans jeton) et détermine si une mise à jour du `.jar` ou du `.exe` est disponible.
pub fn check_for_updates(
    raw_repo: &str,
    game_dir: &str,
) -> Result<UpdateCheckOutcome, String> {
    let repo = sanitize_repo(raw_repo)?;
    let (agent, _) = build_agents()?;

    let latest_url = format!("https://api.github.com/repos/{repo}/releases/latest");
    let req = apply_gh_headers(
        agent.get(&latest_url),
        "application/vnd.github+json",
    );

    let rel: GhRelease = match req.call() {
        Ok(resp) => resp
            .into_json::<GhRelease>()
            .map_err(|e| format!("Réponse GitHub Release illisible: {e}"))?,
        Err(ureq::Error::Status(404, _)) => {
            let repo_url = format!("https://api.github.com/repos/{repo}");
            let repo_check = apply_gh_headers(
                agent.get(&repo_url),
                "application/vnd.github+json",
            )
            .call();
            return match repo_check {
                Ok(_) => Ok(UpdateCheckOutcome::NoReleaseYet { repo }),
                Err(ureq::Error::Status(401 | 403, _)) => Err(
                    "Accès à l'API publique GitHub refusé ou limité (HTTP 401/403).".to_owned(),
                ),
                Err(ureq::Error::Status(404, _)) => {
                    Err(format!("Dépôt GitHub '{repo}' introuvable ou inaccessible."))
                }
                Err(e) => Err(format!("Impossible de vérifier le dépôt GitHub: {e}")),
            };
        }
        Err(ureq::Error::Status(403, _)) => {
            return Err("Quota API publique GitHub temporairement atteint (HTTP 403). Réessayez plus tard.".to_owned());
        }
        Err(ureq::Error::Status(code, _)) => {
            return Err(format!("Erreur GitHub API HTTP {code}."));
        }
        Err(e) => return Err(format!("Connexion à GitHub impossible: {e}")),
    };

    let rel_ver = parse_semver(&rel.tag_name)
        .or_else(|| parse_semver(&rel.name))
        .ok_or_else(|| {
            format!(
                "Tag de release '{}' non reconnu (utilisez ex: v1.0.0).",
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

    let exe_asset = select_portable_exe_asset(&rel.assets);

    let checksums_asset = rel
        .assets
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case("SHA256SUMS.txt"))
        .map(|a| ReleaseAssetPlan {
            name: a.name.clone(),
            api_url: a.url.clone(),
            browser_download_url: a.browser_download_url.clone(),
            size: a.size,
        });

    let launcher_ver = parse_semver(LAUNCHER_VERSION).unwrap_or((1, 0, 0));
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
        checksums_asset,
    }))
}

pub fn fetch_trusted_redirect_chain(
    no_redirect_agent: &ureq::Agent,
    initial_url: &str,
) -> Result<ureq::Response, String> {
    let mut current = initial_url.to_owned();
    for _ in 0..5 {
        if !is_trusted_download_url(&current) {
            return Err(format!("URL de téléchargement refusée (domaine ou protocole non fiable) : {current}"));
        }
        match no_redirect_agent
            .get(&current)
            .set("User-Agent", USER_AGENT)
            .call()
        {
            Ok(resp) if matches!(resp.status(), 301 | 302 | 303 | 307 | 308) => {
                let loc = resp
                    .header("Location")
                    .ok_or_else(|| "Redirection HTTPS sans en-tête Location.".to_owned())?;
                current = loc.to_owned();
            }
            Ok(resp) => return Ok(resp),
            Err(ureq::Error::Status(301 | 302 | 303 | 307 | 308, redirect_resp)) => {
                let loc = redirect_resp
                    .header("Location")
                    .ok_or_else(|| "Redirection HTTPS sans en-tête Location.".to_owned())?;
                current = loc.to_owned();
            }
            Err(e) => return Err(format!("Téléchargement échoué: {e}")),
        }
    }
    Err("Trop de redirections HTTPS lors du téléchargement.".to_owned())
}

fn open_github_asset_stream(
    _standard_agent: &ureq::Agent,
    no_redirect_agent: &ureq::Agent,
    asset: &ReleaseAssetPlan,
) -> Result<ureq::Response, String> {
    if is_trusted_github_api_url(&asset.api_url) {
        let api_req = apply_gh_headers(
            no_redirect_agent.get(&asset.api_url),
            "application/octet-stream",
        );

        match api_req.call() {
            Ok(resp) => {
                if matches!(resp.status(), 301 | 302 | 303 | 307 | 308) {
                    if let Some(loc) = resp.header("Location") {
                        // Valide strictement chaque redirection HTTPS
                        return fetch_trusted_redirect_chain(no_redirect_agent, loc);
                    }
                }
                return Ok(resp);
            }
            Err(ureq::Error::Status(code @ (301 | 302 | 303 | 307 | 308), redirect_resp)) => {
                if let Some(loc) = redirect_resp.header("Location") {
                    return fetch_trusted_redirect_chain(no_redirect_agent, loc);
                }
                return Err(format!(
                    "Redirection GitHub HTTP {code} sans en-tête Location pour '{}'.",
                    asset.name
                ));
            }
            Err(_) => {}
        }
    }

    // Repli direct via browser_download_url sur hôte HTTPS vérifié uniquement
    if !asset.browser_download_url.is_empty() {
        fetch_trusted_redirect_chain(no_redirect_agent, &asset.browser_download_url)
            .map_err(|e2| format!("Téléchargement '{}' impossible: {e2}", asset.name))
    } else {
        Err(format!("URL API GitHub non fiable ou inaccessible pour '{}'.", asset.name))
    }
}

fn fetch_release_checksums(
    standard_agent: &ureq::Agent,
    no_redirect_agent: &ureq::Agent,
    asset: &ReleaseAssetPlan,
) -> Result<std::collections::BTreeMap<String, String>, String> {
    let resp = open_github_asset_stream(standard_agent, no_redirect_agent, asset)?;
    let mut raw = String::new();
    resp.into_reader()
        .take(MAX_CHECKSUMS_BYTES)
        .read_to_string(&mut raw)
        .map_err(|e| format!("Lecture SHA256SUMS.txt impossible: {e}"))?;
    Ok(parse_sha256sums(&raw))
}

#[allow(clippy::too_many_arguments)]
fn stream_asset_to_part_file(
    standard_agent: &ureq::Agent,
    no_redirect_agent: &ureq::Agent,
    asset: &ReleaseAssetPlan,
    part_path: &Path,
    expected_magic: &[u8],
    min_bytes: u64,
    expected_sha256: &str,
    base_done: u64,
    total_plan_bytes: u64,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<u64, String> {
    if asset.size > MAX_UPDATE_ASSET_BYTES {
        return Err(format!("Archive '{}' dépasse la taille maximale autorisée.", asset.name));
    }
    let resp = open_github_asset_stream(standard_agent, no_redirect_agent, asset)?;
    let mut reader = resp.into_reader();
    let mut out = File::create(part_path)
        .map_err(|e| format!("Création fichier temporaire {}: {e}", part_path.display()))?;

    let mut buf = [0u8; 32 * 1024];
    let mut header_probe = Vec::with_capacity(expected_magic.len());
    let mut hasher = Sha256::new();
    let mut written: u64 = 0;
    let mut last_pct: u8 = 255;

    loop {
        let n = reader
            .read(&mut buf)
            .map_err(|e| format!("Lecture réseau interrompue ({}): {e}", asset.name))?;
        if n == 0 {
            break;
        }
        written = written.saturating_add(n as u64);
        if written > MAX_UPDATE_ASSET_BYTES {
            drop(out);
            let _ = std::fs::remove_file(part_path);
            return Err(format!("Téléchargement '{}' interrompu : taille maximale dépassée.", asset.name));
        }
        if header_probe.len() < expected_magic.len() {
            let need = expected_magic.len() - header_probe.len();
            header_probe.extend_from_slice(&buf[..n.min(need)]);
        }
        hasher.update(&buf[..n]);
        out.write_all(&buf[..n])
            .map_err(|e| format!("Écriture disque ({}): {e}", asset.name))?;

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

    let size_mismatch = asset.size > 0 && written != asset.size;
    if written < min_bytes || size_mismatch || header_probe.as_slice() != expected_magic {
        let _ = std::fs::remove_file(part_path);
        return Err(format!(
            "Fichier téléchargé '{}' corrompu ou incomplet ({} octets).",
            asset.name, written
        ));
    }

    let actual_hash = hasher.finalize_hex();
    if expected_sha256.len() != 64 || !actual_hash.eq_ignore_ascii_case(expected_sha256) {
        let _ = std::fs::remove_file(part_path);
        return Err(format!(
            "Vérification d'intégrité SHA-256 échouée pour '{}' (fichier rejeté).",
            asset.name
        ));
    }

    Ok(written)
}

/// Télécharge et installe atomiquement les composants mis à jour (`.jar` du mod et/ou `.exe` du Launcher).
pub fn download_and_install_update(
    plan: &PendingUpdate,
    game_dir: &str,
    proxy: &EventLoopProxy<AppEvent>,
) -> Result<InstallOutcome, String> {
    let (standard_agent, no_redirect_agent) = build_agents()?;
    let updates_dir = app_storage_dir().join("updates");
    std::fs::create_dir_all(&updates_dir)
        .map_err(|e| format!("Création dossier updates: {e}"))?;

    let sums_asset = plan.checksums_asset.as_ref().ok_or_else(|| {
        "Mise à jour refusée : fichier SHA256SUMS.txt absent de cette GitHub Release.".to_owned()
    })?;
    let checksums = fetch_release_checksums(&standard_agent, &no_redirect_agent, sums_asset)?;

    let total_bytes = plan.jar_asset.as_ref().map_or(0, |a| a.size)
        + plan.exe_asset.as_ref().map_or(0, |a| a.size);
    let mut completed_bytes: u64 = 0;

    let mut jar_installed: Option<String> = None;
    let mut exe_staged_for_restart = false;

    // 1. Mise à jour du mod Forge 1.8.9 (`purefps-X.Y.Z.jar` — nom généré uniquement depuis le SemVer validé)
    if let Some(ref jar) = plan.jar_asset {
        let ver_tuple = parse_semver(&plan.version)
            .ok_or_else(|| format!("Version de mise à jour invalide : '{}'", plan.version))?;
        let canonical_name = format!("purefps-{}.jar", format_semver(ver_tuple));
        let part_path = updates_dir.join(format!("{canonical_name}.part"));
        let final_cache_path = updates_dir.join(&canonical_name);
        let expected_jar_sha = checksums
            .get(&jar.name.to_ascii_lowercase())
            .or_else(|| checksums.get(&canonical_name.to_ascii_lowercase()))
            .map(String::as_str)
            .ok_or_else(|| {
                format!(
                    "Mise à jour refusée : empreinte SHA-256 absente dans SHA256SUMS.txt pour '{}'.",
                    jar.name
                )
            })?;

        let dl_bytes = stream_asset_to_part_file(
            &standard_agent,
            &no_redirect_agent,
            jar,
            &part_path,
            b"PK\x03\x04",
            4096,
            expected_jar_sha,
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

    // 2. Auto-remplacement atomique de l'exécutable Windows (`MashClient.exe`)
    if let Some(ref exe) = plan.exe_asset {
        let current_exe = std::env::current_exe()
            .map_err(|e| format!("Chemin de l'exécutable courant inaccessible: {e}"))?;
        let new_exe = with_appended_ext(&current_exe, "new");
        let old_exe = with_appended_ext(&current_exe, "old");
        let expected_exe_sha = checksums
            .get(&exe.name.to_ascii_lowercase())
            .or_else(|| checksums.get("mashclient-windows-portable.exe"))
            .or_else(|| checksums.get("mashclient.exe"))
            .map(String::as_str)
            .ok_or_else(|| {
                format!(
                    "Mise à jour refusée : empreinte SHA-256 absente dans SHA256SUMS.txt pour '{}'.",
                    exe.name
                )
            })?;

        let _ = std::fs::remove_file(&new_exe);
        let _ = stream_asset_to_part_file(
            &standard_agent,
            &no_redirect_agent,
            exe,
            &new_exe,
            b"MZ",
            32 * 1024,
            expected_exe_sha,
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

/// Relance le nouvel exécutable MashClient installé à l'emplacement courant.
pub fn restart_launcher_process() -> Result<(), String> {
    let current_exe =
        std::env::current_exe().map_err(|e| format!("Exécutable introuvable: {e}"))?;
    Command::new(&current_exe)
        .arg("--after-update")
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

    #[test]
    fn test_trusted_url_rejects_backslash_and_broad_clouds() {
        assert!(is_trusted_download_url(
            "https://objects.githubusercontent.com/github-production-release-asset/foo"
        ));
        assert!(is_trusted_download_url(
            "https://github.com/MyMasha/ProjectFPS/releases/download/v1.0.0/MashClient.exe"
        ));
        assert!(!is_trusted_download_url(
            r"https://evil.example\.github.com/payload.exe"
        ));
        assert!(!is_trusted_download_url(
            "https://attacker.s3.amazonaws.com/payload.exe"
        ));
        assert!(!is_trusted_download_url(
            "https://attacker.blob.core.windows.net/payload.exe"
        ));
        assert!(!is_trusted_github_api_url(
            r"https://api.github.com\evil.example/repos/MyMasha/ProjectFPS"
        ));
    }

    #[test]
    fn test_select_portable_exe_never_picks_setup_installer() {
        let mk = |name: &str| GhAsset {
            name: name.to_owned(),
            url: format!("https://api.github.com/repos/MyMasha/ProjectFPS/releases/assets/{name}"),
            browser_download_url: format!(
                "https://github.com/MyMasha/ProjectFPS/releases/download/v1.0.0/{name}"
            ),
            size: 1_000_000,
        };

        // Même si Setup apparaît AVANT Portable, l'Auto-Updater choisit strictement Portable
        let both = vec![
            mk("MashClient-Windows-Setup.exe"),
            mk("MashClient-Windows-Portable.exe"),
            mk("purefps-1.0.0.jar"),
        ];
        let picked = select_portable_exe_asset(&both).expect("must pick portable exe");
        assert_eq!(picked.name, "MashClient-Windows-Portable.exe");

        // Rétrocompatibilité avec un ancien MashClient.exe
        let legacy = vec![mk("MashClient-Windows-Setup.exe"), mk("MashClient.exe")];
        let picked_legacy = select_portable_exe_asset(&legacy).expect("must pick legacy exe");
        assert_eq!(picked_legacy.name, "MashClient.exe");

        // Ne doit JAMAIS sélectionner un installateur seul
        let only_setup = vec![
            mk("MashClient-Windows-Setup.exe"),
            mk("MashClient-Installer.exe"),
        ];
        assert!(select_portable_exe_asset(&only_setup).is_none());
    }

    #[test]
    fn test_legacy_pre_v1_mod_version_handling() {
        // Sur branche publique 1.x / 2.x, un ancien purefps-4.5.14.jar hérité ne doit pas bloquer purefps-1.0.1.jar
        assert!(is_legacy_pre_v1_mod_version((4, 5, 14), (1, 0, 1)));
        assert!(is_legacy_pre_v1_mod_version((3, 6, 0), (1, 0, 1)));
        assert!(!is_legacy_pre_v1_mod_version((1, 0, 0), (1, 0, 1)));
        assert!(!is_legacy_pre_v1_mod_version((2, 0, 0), (1, 0, 1)));
        // Sur une future branche 3.x+, la règle legacy ne s'applique plus
        assert!(!is_legacy_pre_v1_mod_version((4, 0, 0), (3, 0, 0)));
    }
}
