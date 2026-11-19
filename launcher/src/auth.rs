//! Authentification Microsoft Officielle par Device Code Flow OAuth2 v2.0
//! (`login.microsoftonline.com/consumers/oauth2/v2.0/devicecode` -> Xbox Live -> XSTS -> Minecraft Services).
//! Zéro URL cassée, zéro copier-coller d'URL de redirection : code 8 caractères copié automatiquement
//! dans le presse-papiers Windows + ouverture propre de `https://www.microsoft.com/link` + détection auto !

use crate::config::{effective_ms_client_id, MicrosoftAccount};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::io::Write;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const MS_SCOPE: &str = "XboxLive.signin offline_access";
const MS_DEVICE_ENDPOINT: &str =
    "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const MS_TOKEN_ENDPOINT: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

fn require_ms_client_id() -> Result<String, String> {
    effective_ms_client_id().ok_or_else(|| {
        "Client ID Microsoft OAuth2 non configuré dans cette compilation open-source (définissez MASHCLIENT_MS_CLIENT_ID ou utilisez la release officielle GitHub).".to_owned()
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceCodeChallenge {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

#[derive(Deserialize)]
struct OAuthTokenSuccess {
    access_token: String,
    #[serde(default)]
    refresh_token: String,
}

#[derive(Deserialize)]
struct OAuthTokenError {
    #[serde(default)]
    error: String,
}

fn sanitize_oauth_error_code(raw: &str) -> String {
    let clean: String = raw
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .take(40)
        .collect();
    if clean.is_empty() {
        "unknown_error".to_owned()
    } else {
        clean
    }
}

fn format_http_failure(service: &str, err: ureq::Error) -> String {
    match err {
        ureq::Error::Status(code, _) => format!("{service} indisponible (HTTP {code})."),
        ureq::Error::Transport(_) => format!("Impossible de joindre {service} (erreur réseau/TLS)."),
    }
}

#[derive(Deserialize)]
struct XblDisplayClaims {
    xui: Vec<XblUhs>,
}

#[derive(Deserialize)]
struct XblUhs {
    uhs: String,
}

#[derive(Deserialize)]
struct XblAuthResp {
    #[serde(rename = "Token")]
    token: String,
    #[serde(rename = "DisplayClaims")]
    display_claims: XblDisplayClaims,
}

#[derive(Deserialize)]
struct XstsErrorBody {
    #[serde(rename = "XErr", default)]
    xerr: u64,
}

#[derive(Deserialize)]
struct McLoginResp {
    access_token: String,
}

#[derive(Deserialize)]
struct McProfileResp {
    id: String,
    name: String,
}

pub fn start_device_code_flow() -> Result<DeviceCodeChallenge, String> {
    let client_id = require_ms_client_id()?;
    let agent = http_agent()?;
    let body = encode_form(&[("client_id", client_id.as_str()), ("scope", MS_SCOPE)]);

    let resp = match agent
        .post(MS_DEVICE_ENDPOINT)
        .set("Content-Type", "application/x-www-form-urlencoded")
        .set("Accept", "application/json")
        .send_string(&body)
    {
        Ok(r) => r,
        Err(ureq::Error::Status(code, err_resp)) => {
            let err_code = err_resp
                .into_json::<OAuthTokenError>()
                .ok()
                .map(|p| sanitize_oauth_error_code(&p.error))
                .filter(|s| s != "unknown_error");
            return match err_code {
                Some(ec) => Err(format!("Service Microsoft OAuth2 refusé (HTTP {code}, {ec}).")),
                None => Err(format!("Service Microsoft OAuth2 indisponible (HTTP {code}).")),
            };
        }
        Err(e) => return Err(format_http_failure("Microsoft OAuth2", e)),
    };

    resp.into_json::<DeviceCodeChallenge>()
        .map_err(|_| "Réponse Device Code Microsoft invalide.".to_owned())
}

pub fn poll_device_code_until_complete(
    challenge: &DeviceCodeChallenge,
    cancel_flag: &Arc<AtomicBool>,
) -> Result<MicrosoftAccount, String> {
    let client_id = require_ms_client_id()?;
    let agent = http_agent()?;
    let mut poll_secs = challenge.interval.clamp(3, 15);
    let deadline = Instant::now() + Duration::from_secs(challenge.expires_in.min(900));

    while Instant::now() < deadline {
        if cancel_flag.load(Ordering::Relaxed) {
            return Err("Connexion Microsoft annulée.".to_owned());
        }

        // Attente fractionnée 250ms pour répondre instantanément au bouton Annuler
        let steps = poll_secs * 4;
        for _ in 0..steps {
            if cancel_flag.load(Ordering::Relaxed) {
                return Err("Connexion Microsoft annulée.".to_owned());
            }
            std::thread::sleep(Duration::from_millis(250));
        }

        let form = encode_form(&[
            ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ("client_id", client_id.as_str()),
            ("device_code", challenge.device_code.as_str()),
        ]);

        match agent
            .post(MS_TOKEN_ENDPOINT)
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("Accept", "application/json")
            .send_string(&form)
        {
            Ok(ok_resp) => {
                let tok = ok_resp
                    .into_json::<OAuthTokenSuccess>()
                    .map_err(|_| "Jeton OAuth2 Microsoft illisible.".to_owned())?;
                return complete_xbox_and_mc_chain(&agent, &tok.access_token, tok.refresh_token);
            }
            Err(ureq::Error::Status(_code, err_resp)) => {
                let parsed = err_resp
                    .into_json::<OAuthTokenError>()
                    .unwrap_or(OAuthTokenError {
                        error: String::new(),
                    });
                match parsed.error.as_str() {
                    "authorization_pending" => continue,
                    "slow_down" => {
                        poll_secs = (poll_secs + 5).min(20);
                        continue;
                    }
                    "authorization_declined" | "access_denied" => {
                        return Err("Autorisation refusée sur le compte Microsoft.".to_owned());
                    }
                    "expired_token" => {
                        return Err("Le code Microsoft a expiré. Relancez la connexion.".to_owned());
                    }
                    other => {
                        let safe_code = sanitize_oauth_error_code(other);
                        return Err(format!(
                            "Erreur d'authentification Microsoft ({safe_code}). Réessayez la connexion."
                        ));
                    }
                }
            }
            Err(ureq::Error::Transport(t)) => {
                // Tolérance aux micro-coupures réseau pendant l'attente navigateur
                let _ = t;
                continue;
            }
        }
    }

    Err("Délai dépassé : validez le code sur microsoft.com/link puis réessayez.".to_owned())
}

pub fn normalize_mc_uuid(raw: &str) -> String {
    raw.trim().chars().filter(|c| *c != '-').collect()
}

pub fn refresh_microsoft_session(existing: &MicrosoftAccount) -> Result<MicrosoftAccount, String> {
    if existing.refresh_token.trim().is_empty() {
        return Err("Aucun jeton de rafraîchissement enregistré, reconnectez-vous.".to_owned());
    }
    let client_id = require_ms_client_id()?;
    let agent = http_agent()?;
    let form = encode_form(&[
        ("client_id", client_id.as_str()),
        ("refresh_token", existing.refresh_token.as_str()),
        ("grant_type", "refresh_token"),
        ("scope", MS_SCOPE),
    ]);

    let resp = match agent
        .post(MS_TOKEN_ENDPOINT)
        .set("Content-Type", "application/x-www-form-urlencoded")
        .set("Accept", "application/json")
        .send_string(&form)
    {
        Ok(r) => r,
        Err(ureq::Error::Status(400 | 401, err_resp)) => {
            let parsed = err_resp
                .into_json::<OAuthTokenError>()
                .unwrap_or(OAuthTokenError {
                    error: String::new(),
                });
            if parsed.error.is_empty() {
                return Err(
                    "Session Microsoft expirée : cliquez sur votre compte en haut à droite pour vous reconnecter."
                        .to_owned(),
                );
            }
            let safe_code = sanitize_oauth_error_code(&parsed.error);
            return Err(format!(
                "Session Microsoft expirée ({safe_code}) : cliquez sur votre compte en haut à droite pour vous reconnecter."
            ));
        }
        Err(e) => return Err(format_http_failure("Serveur Microsoft OAuth2", e)),
    };

    let tok = resp
        .into_json::<OAuthTokenSuccess>()
        .map_err(|_| "Réponse de rafraîchissement Microsoft invalide.".to_owned())?;

    let next_refresh = if tok.refresh_token.is_empty() {
        existing.refresh_token.clone()
    } else {
        tok.refresh_token
    };
    complete_xbox_and_mc_chain(&agent, &tok.access_token, next_refresh)
}

/// Vérifie qu'un jeton `access_token` Minecraft en cache est réellement accepté par Mojang
/// (`GET https://api.minecraftservices.com/minecraft/profile`) avant d'autoriser un repli hors-rafraîchissement.
/// Empêche de lancer Minecraft avec un jeton expiré qui provoquerait la fausse erreur Mojang 1.8.9
/// `"The authentication servers are currently down for maintenance"` (HTTP 401 corps vide sur `sessionserver`).
pub fn is_cached_mc_token_usable(acc: &MicrosoftAccount) -> bool {
    let tok = acc.access_token.trim();
    if tok.is_empty() || tok == "0" {
        return false;
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let fresh_by_clock = acc.obtained_epoch_secs > 0
        && now.saturating_sub(acc.obtained_epoch_secs) < 20 * 3600;

    let Ok(agent) = http_agent() else {
        return fresh_by_clock;
    };
    match agent
        .get("https://api.minecraftservices.com/minecraft/profile")
        .set("Authorization", &format!("Bearer {tok}"))
        .set("Accept", "application/json")
        .call()
    {
        Ok(_) => true,
        Err(ureq::Error::Status(401 | 403, _)) => false,
        Err(_) => fresh_by_clock,
    }
}

fn complete_xbox_and_mc_chain(
    agent: &ureq::Agent,
    ms_access_token: &str,
    refresh_token: String,
) -> Result<MicrosoftAccount, String> {
    // 1. Xbox Live Authenticate (préfixe `d=` requis pour les jetons OAuth2 v2.0 consommateurs)
    let xbl = match request_xbl_token(agent, &format!("d={ms_access_token}")) {
        Ok(resp) => resp,
        Err(_) => request_xbl_token(agent, ms_access_token)?,
    };

    let uhs = xbl
        .display_claims
        .xui
        .first()
        .map(|x| x.uhs.clone())
        .ok_or_else(|| "Identifiant Xbox UHS introuvable.".to_owned())?;

    // 2. XSTS Authorize
    let xsts_payload = json!({
        "Properties": {
            "SandboxId": "RETAIL",
            "UserTokens": [xbl.token]
        },
        "RelyingParty": "rp://api.minecraftservices.com/",
        "TokenType": "JWT"
    });

    let xsts_resp = match agent
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .send_json(xsts_payload)
    {
        Ok(r) => r,
        Err(ureq::Error::Status(401, err_r)) => {
            let xerr = err_r
                .into_json::<XstsErrorBody>()
                .map(|b| b.xerr)
                .unwrap_or(0);
            let msg = match xerr {
                2_148_916_233 => {
                    "Ce compte Microsoft n'a pas encore de profil Xbox (connectez-vous une fois sur xbox.com)."
                }
                2_148_916_238 => {
                    "Compte mineur Microsoft : l'autorisation multijoueur d'un groupe familial Xbox est requise."
                }
                _ => "Autorisation Xbox XSTS refusée pour ce compte.",
            };
            return Err(msg.to_owned());
        }
        Err(e) => return Err(format_http_failure("Xbox XSTS", e)),
    };

    let xsts: XblAuthResp = xsts_resp
        .into_json()
        .map_err(|_| "Réponse Xbox XSTS illisible.".to_owned())?;

    // 3. Minecraft Services Login
    let mc_login_resp = agent
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .send_json(json!({
            "identityToken": format!("XBL3.0 x={};{}", uhs, xsts.token)
        }))
        .map_err(|e| format_http_failure("Minecraft Services", e))?;

    let mc_login: McLoginResp = mc_login_resp
        .into_json()
        .map_err(|_| "Réponse Minecraft Services illisible.".to_owned())?;

    // 4. Profil Minecraft Java (Pseudo + UUID)
    let profile_resp = agent
        .get("https://api.minecraftservices.com/minecraft/profile")
        .set("Authorization", &format!("Bearer {}", mc_login.access_token))
        .set("Accept", "application/json")
        .call()
        .map_err(|_| {
            "Ce compte Microsoft ne possède pas de licence Minecraft: Java Edition.".to_owned()
        })?;

    let profile: McProfileResp = profile_resp
        .into_json()
        .map_err(|_| "Profil Minecraft illisible.".to_owned())?;

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    Ok(MicrosoftAccount {
        username: profile.name,
        uuid: normalize_mc_uuid(&profile.id),
        access_token: mc_login.access_token,
        refresh_token,
        obtained_epoch_secs: now,
    })
}

fn request_xbl_token(agent: &ureq::Agent, ticket: &str) -> Result<XblAuthResp, String> {
    let payload = json!({
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": ticket
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT"
    });

    let resp = agent
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .send_json(payload)
        .map_err(|e| format_http_failure("Xbox Live", e))?;

    resp.into_json::<XblAuthResp>()
        .map_err(|_| "Réponse Xbox Live invalide.".to_owned())
}

fn http_agent() -> Result<ureq::Agent, String> {
    let tls = native_tls::TlsConnector::new()
        .map_err(|e| format!("Initialisation TLS Windows Schannel impossible: {e}"))?;
    Ok(ureq::AgentBuilder::new()
        .tls_connector(Arc::new(tls))
        .timeout_connect(Duration::from_secs(10))
        .timeout_read(Duration::from_secs(20))
        .timeout_write(Duration::from_secs(10))
        .build())
}

pub fn is_trusted_ms_verification_uri(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("https://") else {
        return false;
    };
    if url.bytes().any(|b| b <= 0x20 || b == 0x7F) {
        return false;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    if authority.is_empty() || authority.contains('@') {
        return false;
    }
    let host = match authority.rsplit_once(':') {
        Some((h, "443")) => h,
        Some(_) => return false,
        None => authority,
    }
    .to_ascii_lowercase();

    const ALLOWED_MS_HOSTS: &[&str] = &["microsoft.com", "microsoftonline.com", "live.com"];
    ALLOWED_MS_HOSTS
        .iter()
        .any(|d| host == *d || host.ends_with(&format!(".{d}")))
}

fn windows_system_tool(relative: &str) -> std::path::PathBuf {
    let root = std::env::var("SystemRoot")
        .or_else(|_| std::env::var("WINDIR"))
        .unwrap_or_else(|_| r"C:\Windows".to_owned());
    std::path::Path::new(&root).join(relative)
}

pub fn open_verification_browser(url: &str) {
    let safe_url = if is_trusted_ms_verification_uri(url) {
        url
    } else {
        "https://www.microsoft.com/link"
    };
    // Ouverture propre via API ShellExecute Windows (`webbrowser`), sans troncature de caractères `&`
    if webbrowser::open(safe_url).is_ok() {
        return;
    }
    let explorer = windows_system_tool("explorer.exe");
    let _ = Command::new(&explorer).arg(safe_url).spawn();
}

pub fn copy_to_windows_clipboard(text: &str) {
    if crate::win_compat::set_clipboard_unicode(text) {
        return;
    }
    let clip = windows_system_tool(r"System32\clip.exe");
    let mut cmd = Command::new(&clip);
    cmd.stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    if let Ok(mut child) = cmd.spawn() {
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(text.as_bytes());
        }
        let _ = child.wait();
    }
}

pub fn encode_form(pairs: &[(&str, &str)]) -> String {
    let mut out = String::new();
    for (idx, &(k, v)) in pairs.iter().enumerate() {
        if idx > 0 {
            out.push('&');
        }
        out.push_str(&percent_encode(k));
        out.push('=');
        out.push_str(&percent_encode(v));
    }
    out
}

fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 8);
    for b in input.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_percent_encode_form() {
        let encoded = encode_form(&[
            ("client_id", "00000000-0000-0000-0000-000000000000"),
            ("scope", MS_SCOPE),
        ]);
        assert_eq!(
            encoded,
            "client_id=00000000-0000-0000-0000-000000000000&scope=XboxLive.signin%20offline_access"
        );
    }

    #[test]
    fn test_live_microsoft_device_code_endpoint() {
        if effective_ms_client_id().is_none() {
            println!("Skipping live Microsoft OAuth2 test (MASHCLIENT_MS_CLIENT_ID not configured)");
            return;
        }
        let challenge = start_device_code_flow()
            .expect("Live HTTPS request to Microsoft OAuth2 devicecode endpoint must succeed");
        println!(
            "LIVE MS DEVICE CODE OK -> user_code={} verification_uri={} expires_in={}",
            challenge.user_code, challenge.verification_uri, challenge.expires_in
        );
        assert!(!challenge.user_code.is_empty());
        assert!(!challenge.device_code.is_empty());
        assert!(challenge.verification_uri.starts_with("https://"));
    }

    #[test]
    fn test_normalize_mc_uuid_and_empty_token_guard() {
        assert_eq!(
            normalize_mc_uuid(" 550e8400-e29b-41d4-a716-446655440000 "),
            "550e8400e29b41d4a716446655440000"
        );
        assert_eq!(
            normalize_mc_uuid("550e8400e29b41d4a716446655440000"),
            "550e8400e29b41d4a716446655440000"
        );
        let offline_acc = MicrosoftAccount {
            username: "OfflineUser".into(),
            uuid: "00000000000000000000000000000000".into(),
            access_token: "0".into(),
            refresh_token: String::new(),
            obtained_epoch_secs: 0,
        };
        assert!(!is_cached_mc_token_usable(&offline_acc));
        let empty_acc = MicrosoftAccount {
            access_token: "   ".into(),
            ..offline_acc
        };
        assert!(!is_cached_mc_token_usable(&empty_acc));
    }
}
