//! Gestionnaire des mods tiers (`.jar` / `.jar.disabled`) par instance (`1.8.9 PureFPS` ou `1.8.9 PureFPS Vulkan`) :
//! - Lecture des métadonnées Forge `mcmod.info` dans les archives `.jar`
//! - Importation de `.jar`, activation/désactivation instantanée et suppression sécurisée (protège `purefps-*.jar`)
//! - Diagnostic anti-crash croisant les rapports d'erreur Forge/JVM avec les mods tiers installés.

use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::fs::File;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledModEntry {
    pub file_name: String,
    pub display_name: String,
    pub modid: Option<String>,
    pub version: Option<String>,
    pub enabled: bool,
    pub is_core: bool,
    pub size_kb: u64,
}

#[derive(Default)]
struct JarMetadata {
    modid: Option<String>,
    name: Option<String>,
    version: Option<String>,
    class_packages: BTreeSet<String>,
}

pub fn is_core_mashclient_jar(file_name: &str) -> bool {
    let low = file_name.trim().to_ascii_lowercase();
    let base = low.strip_suffix(".disabled").unwrap_or(&low);
    base.ends_with(".jar")
        && (base.starts_with("purefps-")
            || base.starts_with("mashclient-")
            || base.starts_with("pureclient-")
            || base == "purefps.jar")
}

fn is_safe_single_filename(file_name: &str) -> bool {
    if file_name.is_empty()
        || file_name != file_name.trim()
        || file_name.bytes().any(|b| b < 0x20 || b == 0x7F)
        || file_name.contains('/')
        || file_name.contains('\\')
        || file_name.contains(':')
        || file_name.contains("..")
    {
        return false;
    }
    let mut comps = Path::new(file_name).components();
    let is_single_normal = matches!(comps.next(), Some(Component::Normal(_))) && comps.next().is_none();
    if !is_single_normal {
        return false;
    }
    let low = file_name.to_ascii_lowercase();
    low.ends_with(".jar") || low.ends_with(".jar.disabled")
}

fn has_valid_zip_magic(path: &Path) -> bool {
    let mut magic = [0u8; 4];
    File::open(path)
        .and_then(|mut f| f.read_exact(&mut magic))
        .map(|_| &magic == b"PK\x03\x04")
        .unwrap_or(false)
}

fn resolve_confined_mod_file(mods_dir: &Path, file_name: &str) -> Result<PathBuf, String> {
    let target = mods_dir.join(file_name);
    let meta = std::fs::symlink_metadata(&target)
        .map_err(|_| format!("Mod introuvable : {file_name}"))?;
    if !meta.file_type().is_file() {
        return Err("Opération refusée : seuls les fichiers réguliers dans mods/ sont autorisés.".to_owned());
    }
    let canon_dir = std::fs::canonicalize(mods_dir)
        .map_err(|e| format!("Dossier mods invalide : {e}"))?;
    let canon_target = std::fs::canonicalize(&target)
        .map_err(|e| format!("Chemin mod invalide : {e}"))?;
    if canon_target.parent() != Some(canon_dir.as_path()) {
        return Err("Chemin mod hors du dossier mods/ refusé par sécurité.".to_owned());
    }
    Ok(canon_target)
}

fn humanize_jar_stem(file_name: &str) -> String {
    let base = file_name
        .strip_suffix(".disabled")
        .or_else(|| file_name.strip_suffix(".DISABLED"))
        .unwrap_or(file_name);
    let stem = base
        .strip_suffix(".jar")
        .or_else(|| base.strip_suffix(".JAR"))
        .unwrap_or(base);
    stem.replace('_', " ")
}

fn sanitize_mcmod_json(raw: &str) -> String {
    // Certains mods 1.8.9 anciens contiennent des retours à la ligne littéraux dans les chaînes JSON de `mcmod.info`
    raw.chars()
        .map(|c| match c {
            '\r' | '\n' | '\t' => ' ',
            other => other,
        })
        .collect()
}

fn parse_mcmod_info_value(val: &serde_json::Value) -> Option<(Option<String>, Option<String>, Option<String>)> {
    let first_obj = if let Some(arr) = val.as_array() {
        arr.first()?.as_object()?
    } else if let Some(obj) = val.as_object() {
        if let Some(list) = obj.get("modList").and_then(serde_json::Value::as_array) {
            list.first()?.as_object()?
        } else {
            obj
        }
    } else {
        return None;
    };

    let str_field = |k: &str| -> Option<String> {
        first_obj
            .get(k)
            .and_then(serde_json::Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty() && !s.starts_with("${"))
            .map(ToOwned::to_owned)
    };

    Some((str_field("modid"), str_field("name"), str_field("version")))
}

fn inspect_jar_archive(path: &Path, collect_packages: bool) -> JarMetadata {
    let mut meta = JarMetadata::default();
    let Ok(file) = File::open(path) else {
        return meta;
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return meta;
    };

    if let Ok(mcmod) = archive.by_name("mcmod.info") {
        let mut raw = String::new();
        let _ = mcmod.take(131_072).read_to_string(&mut raw);
        let parsed = serde_json::from_str::<serde_json::Value>(&raw)
            .or_else(|_| serde_json::from_str::<serde_json::Value>(&sanitize_mcmod_json(&raw)));
        if let Ok(val) = parsed {
            if let Some((modid, name, version)) = parse_mcmod_info_value(&val) {
                meta.modid = modid;
                meta.name = name;
                meta.version = version;
            }
        }
    }

    if collect_packages {
        let len = archive.len().min(4000);
        for i in 0..len {
            let Ok(entry) = archive.by_index_raw(i) else {
                continue;
            };
            let name = entry.name();
            if !name.ends_with(".class") || name.contains('$') {
                continue;
            }
            let parts: Vec<&str> = name.split('/').collect();
            if parts.len() >= 3 {
                let pkg2 = format!("{}.{}", parts[0], parts[1]);
                let pkg3 = if parts.len() >= 4 {
                    Some(format!("{}.{}.{}", parts[0], parts[1], parts[2]))
                } else {
                    None
                };
                let ignored = matches!(
                    pkg2.as_str(),
                    "net.minecraft"
                        | "net.minecraftforge"
                        | "cpw.mods"
                        | "org.spongepowered"
                        | "org.apache"
                        | "org.lwjgl"
                        | "com.google"
                        | "com.mojang"
                        | "io.netty"
                        | "dev.purefps"
                        | "javax.annotation"
                        | "kotlin.Metadata"
                );
                if !ignored {
                    if let Some(p3) = pkg3 {
                        meta.class_packages.insert(p3);
                    } else {
                        meta.class_packages.insert(pkg2);
                    }
                }
            }
        }
    }

    meta
}

pub fn list_installed_mods(game_dir: &str) -> Vec<InstalledModEntry> {
    let mods_dir = Path::new(game_dir).join("mods");
    let Ok(entries) = std::fs::read_dir(&mods_dir) else {
        return Vec::new();
    };

    let mut list = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let file_name = entry.file_name().to_string_lossy().to_string();
        let low = file_name.to_ascii_lowercase();
        let enabled = if low.ends_with(".jar") {
            true
        } else if low.ends_with(".jar.disabled") {
            false
        } else {
            continue;
        };

        let is_core = is_core_mashclient_jar(&file_name);
        let size_kb = entry.metadata().map(|m| (m.len() + 1023) / 1024).unwrap_or(0);
        let meta = inspect_jar_archive(&path, false);

        let display_name = if is_core {
            "MashClient Core (PureFPS)".to_owned()
        } else {
            meta.name.unwrap_or_else(|| humanize_jar_stem(&file_name))
        };

        let version = meta.version.or_else(|| {
            if is_core {
                crate::updater::extract_purefps_jar_semver(&file_name)
                    .map(crate::updater::format_semver)
            } else {
                None
            }
        });

        list.push(InstalledModEntry {
            file_name,
            display_name,
            modid: meta.modid,
            version,
            enabled,
            is_core,
            size_kb,
        });
    }

    list.sort_by(|a, b| {
        b.is_core.cmp(&a.is_core).then_with(|| {
            a.display_name
                .to_ascii_lowercase()
                .cmp(&b.display_name.to_ascii_lowercase())
        })
    });
    list
}

pub fn import_mod_jars(game_dir: &str, source_paths: &[PathBuf]) -> Result<usize, String> {
    let mods_dir = Path::new(game_dir).join("mods");
    std::fs::create_dir_all(&mods_dir).map_err(|e| format!("Création du dossier mods impossible: {e}"))?;

    let mut imported = 0usize;
    for src in source_paths {
        let is_regular = std::fs::symlink_metadata(src)
            .map(|m| m.file_type().is_file())
            .unwrap_or(false);
        if !is_regular {
            continue;
        }
        let Some(fname_os) = src.file_name() else {
            continue;
        };
        let fname = fname_os.to_string_lossy().to_string();
        if !is_safe_single_filename(&fname) || !fname.to_ascii_lowercase().ends_with(".jar") {
            continue;
        }
        if is_core_mashclient_jar(&fname) {
            return Err("Le moteur principal PureFPS est déjà géré automatiquement par MashClient.".to_owned());
        }
        if !has_valid_zip_magic(src) {
            return Err(format!("Le fichier '{fname}' n'est pas une archive JAR/ZIP valide."));
        }
        let dst = mods_dir.join(&fname);
        let same_file = match (std::fs::canonicalize(src), std::fs::canonicalize(&dst)) {
            (Ok(a), Ok(b)) => a == b,
            _ => src.as_path() == dst.as_path(),
        };
        if !same_file {
            std::fs::copy(src, &dst)
                .map_err(|e| format!("Impossible de copier '{fname}' dans mods/ : {e}"))?;
        }
        imported += 1;
    }
    Ok(imported)
}

pub fn toggle_mod_file(game_dir: &str, file_name: &str) -> Result<(String, bool), String> {
    if !is_safe_single_filename(file_name) {
        return Err("Nom de fichier mod invalide.".to_owned());
    }
    if is_core_mashclient_jar(file_name) {
        return Err("Le moteur principal MashClient ne peut pas être désactivé ici.".to_owned());
    }

    let mods_dir = Path::new(game_dir).join("mods");
    let src = resolve_confined_mod_file(&mods_dir, file_name)?;

    let (new_name, now_enabled) = if let Some(stripped) = file_name
        .strip_suffix(".disabled")
        .or_else(|| file_name.strip_suffix(".DISABLED"))
    {
        (stripped.to_owned(), true)
    } else {
        (format!("{file_name}.disabled"), false)
    };

    if !is_safe_single_filename(&new_name) {
        return Err("Nom de fichier cible invalide.".to_owned());
    }

    let dst = mods_dir.join(&new_name);
    if std::fs::symlink_metadata(&dst).is_ok() {
        return Err(format!(
            "Un fichier nommé '{new_name}' existe déjà dans mods/ (opération annulée par sécurité)."
        ));
    }
    std::fs::rename(&src, &dst).map_err(|e| format!("Impossible de renommer '{file_name}' : {e}"))?;
    Ok((new_name, now_enabled))
}

pub fn delete_mod_file(game_dir: &str, file_name: &str) -> Result<(), String> {
    if !is_safe_single_filename(file_name) {
        return Err("Nom de fichier mod invalide.".to_owned());
    }
    if is_core_mashclient_jar(file_name) {
        return Err("Le moteur principal MashClient ne peut pas être supprimé.".to_owned());
    }

    let mods_dir = Path::new(game_dir).join("mods");
    let raw_target = mods_dir.join(file_name);
    if std::fs::symlink_metadata(&raw_target).is_err() {
        return Ok(());
    }
    let safe_target = resolve_confined_mod_file(&mods_dir, file_name)?;
    std::fs::remove_file(&safe_target)
        .map_err(|e| format!("Impossible de supprimer '{file_name}' : {e}"))?;
    Ok(())
}

/// Analyse un rapport de crash ou journal Forge et identifie le fichier `.jar` tiers le plus probable
/// dans `<game_dir>/mods/` afin de proposer sa désactivation en 1 clic.
pub fn identify_suspected_crash_mod(game_dir: &str, crash_text: &str) -> Option<String> {
    if crash_text.trim().is_empty() {
        return None;
    }
    let mods_dir = Path::new(game_dir).join("mods");
    let entries = std::fs::read_dir(&mods_dir).ok()?;

    struct Candidate {
        file_name: String,
        modid_low: Option<String>,
        packages: BTreeSet<String>,
    }

    let mut candidates = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let fname = entry.file_name().to_string_lossy().to_string();
        if !fname.to_ascii_lowercase().ends_with(".jar") || is_core_mashclient_jar(&fname) {
            continue;
        }
        let meta = inspect_jar_archive(&path, true);
        candidates.push(Candidate {
            file_name: fname,
            modid_low: meta.modid.map(|m| m.to_ascii_lowercase()),
            packages: meta.class_packages,
        });
    }

    if candidates.is_empty() {
        return None;
    }

    let crash_low = crash_text.to_ascii_lowercase();

    // 1. Mention explicite Forge : `Caught exception from <modid>` ou état d'erreur `UE` / `UCE` dans la table FML
    for line in crash_text.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("Caught exception from ") {
            let blamed = rest
                .split(|c: char| c.is_whitespace() || c == '(' || c == ':')
                .next()
                .unwrap_or("")
                .to_ascii_lowercase();
            if !blamed.is_empty() {
                if let Some(hit) = candidates
                    .iter()
                    .find(|c| c.modid_low.as_deref() == Some(blamed.as_str()))
                {
                    return Some(hit.file_name.clone());
                }
            }
        }
        // Table FML 1.8.9 : `| UCE | brokenmod | 1.0 | BrokenMod.jar |` ou `UCE\tbrokenmod{1.0} [BrokenMod] (BrokenMod.jar)`
        if trimmed.starts_with("UCE") || trimmed.starts_with("UE") || trimmed.contains("| UCE ") || trimmed.contains("| UE ") {
            let line_low = trimmed.to_ascii_lowercase();
            for c in &candidates {
                if line_low.contains(&c.file_name.to_ascii_lowercase()) {
                    return Some(c.file_name.clone());
                }
                if let Some(ref mid) = c.modid_low {
                    if mid.len() >= 3 && line_low.contains(mid) {
                        return Some(c.file_name.clone());
                    }
                }
            }
        }
    }

    // 2. Correspondance directe dans la pile d'appels Java (`at com.example.mod.Class.method`)
    for line in crash_text.lines() {
        let t = line.trim().strip_prefix("[STDERR] ").unwrap_or(line.trim()).trim();
        let Some(frame) = t.strip_prefix("at ") else {
            continue;
        };
        for c in &candidates {
            if c.packages.iter().any(|pkg| frame.starts_with(pkg)) {
                return Some(c.file_name.clone());
            }
        }
    }

    // 3. Mention explicite du nom d'archive `.jar` sur une ligne d'erreur Mixin / CoreMod
    for line in crash_low.lines() {
        if line.contains("mixin") || line.contains("coremod") || line.contains("transformer") {
            for c in &candidates {
                if line.contains(&c.file_name.to_ascii_lowercase()) {
                    return Some(c.file_name.clone());
                }
            }
        }
    }

    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_dummy_mod_jar(path: &Path, modid: &str, name: &str, version: &str, class_entry: &str) {
        let f = File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(f);
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("mcmod.info", opts).unwrap();
        write!(
            zip,
            r#"[{{"modid":"{modid}","name":"{name}","version":"{version}"}}]"#
        )
        .unwrap();
        zip.start_file(class_entry, opts).unwrap();
        zip.write_all(&[0xCA, 0xFE, 0xBA, 0xBE]).unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn test_mods_list_toggle_core_protection_and_crash_blame() {
        let tmp = std::env::temp_dir().join("mashclient_mods_mgr_test");
        let _ = std::fs::remove_dir_all(&tmp);
        let mods_dir = tmp.join("mods");
        std::fs::create_dir_all(&mods_dir).unwrap();
        let game_dir_str = tmp.to_string_lossy().to_string();

        write_dummy_mod_jar(
            &mods_dir.join("purefps-4.5.1.jar"),
            "purefps",
            "PureFPS",
            "4.5.1",
            "dev/purefps/PureFPSMod.class",
        );
        write_dummy_mod_jar(
            &mods_dir.join("BrokenOverlay-1.2.jar"),
            "brokenoverlay",
            "Broken Overlay",
            "1.2",
            "me/author/brokenoverlay/RenderHook.class",
        );

        let listed = list_installed_mods(&game_dir_str);
        assert_eq!(listed.len(), 2);
        assert!(listed[0].is_core);
        assert_eq!(listed[1].display_name, "Broken Overlay");
        assert!(listed[1].enabled);

        // Protection du mod core
        assert!(toggle_mod_file(&game_dir_str, "purefps-4.5.1.jar").is_err());
        assert!(delete_mod_file(&game_dir_str, "purefps-4.5.1.jar").is_err());

        // Diagnostic de crash par stacktrace package
        let crash_trace = "java.lang.NullPointerException: Ticking screen\n\tat me.author.brokenoverlay.RenderHook.onTick(RenderHook.java:42)\n\tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1800)\n";
        let blamed = identify_suspected_crash_mod(&game_dir_str, crash_trace);
        assert_eq!(blamed.as_deref(), Some("BrokenOverlay-1.2.jar"));

        // Désactivation puis réactivation
        let (disabled_name, en1) = toggle_mod_file(&game_dir_str, "BrokenOverlay-1.2.jar").unwrap();
        assert_eq!(disabled_name, "BrokenOverlay-1.2.jar.disabled");
        assert!(!en1);
        assert_eq!(identify_suspected_crash_mod(&game_dir_str, crash_trace), None);

        let (reenabled_name, en2) = toggle_mod_file(&game_dir_str, &disabled_name).unwrap();
        assert_eq!(reenabled_name, "BrokenOverlay-1.2.jar");
        assert!(en2);

        delete_mod_file(&game_dir_str, &reenabled_name).unwrap();
        assert_eq!(list_installed_mods(&game_dir_str).len(), 1);

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
