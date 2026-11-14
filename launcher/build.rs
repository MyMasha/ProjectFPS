use std::path::Path;

fn main() {
    println!("cargo:rerun-if-changed=.github_token");
    println!("cargo:rerun-if-changed=assets/mashclient.ico");
    println!("cargo:rerun-if-env-changed=PURECLIENT_GITHUB_TOKEN");

    let env_tok = std::env::var("PURECLIENT_GITHUB_TOKEN")
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty());

    let file_tok = std::fs::read_to_string(Path::new(".github_token"))
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty());

    let resolved = env_tok.or(file_tok).unwrap_or_default();
    println!("cargo:rustc-env=PURECLIENT_EMBEDDED_GH_TOKEN={resolved}");

    #[cfg(windows)]
    embed_windows_exe_icon();
}

#[cfg(windows)]
fn embed_windows_exe_icon() {
    use std::path::PathBuf;
    use std::process::Command;

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    let Ok(manifest_dir) = std::env::var("CARGO_MANIFEST_DIR") else {
        return;
    };
    let Ok(out_dir) = std::env::var("OUT_DIR") else {
        return;
    };

    let ico_path = Path::new(&manifest_dir).join("assets").join("mashclient.ico");
    if !ico_path.is_file() {
        return;
    }

    let Some(rc_exe) = find_windows_rc_exe() else {
        println!("cargo:warning=rc.exe introuvable sur l'hôte Windows, icône .exe ignorée");
        return;
    };

    let ico_escaped = ico_path.to_string_lossy().replace('\\', "/");
    let out_path = PathBuf::from(&out_dir);
    let rc_script_full = out_path.join("mashclient_res.rc");
    let res_output = out_path.join("mashclient_res.res");

    let full_rc = format!(
        "#pragma code_page(65001)\r\n\
         1 ICON \"{ico_escaped}\"\r\n\
         1 VERSIONINFO\r\n\
         FILEVERSION 4,0,0,0\r\n\
         PRODUCTVERSION 4,0,0,0\r\n\
         FILEFLAGSMASK 0x3fL\r\n\
         FILEFLAGS 0x0L\r\n\
         FILEOS 0x40004L\r\n\
         FILETYPE 0x1L\r\n\
         FILESUBTYPE 0x0L\r\n\
         BEGIN\r\n\
           BLOCK \"StringFileInfo\"\r\n\
           BEGIN\r\n\
             BLOCK \"040904b0\"\r\n\
             BEGIN\r\n\
               VALUE \"CompanyName\", \"MashClient\\0\"\r\n\
               VALUE \"FileDescription\", \"MashClient 4.0 - Singularity\\0\"\r\n\
               VALUE \"FileVersion\", \"4.0.0\\0\"\r\n\
               VALUE \"InternalName\", \"MashClient\\0\"\r\n\
               VALUE \"OriginalFilename\", \"MashClient.exe\\0\"\r\n\
               VALUE \"ProductName\", \"MashClient\\0\"\r\n\
               VALUE \"ProductVersion\", \"4.0.0\\0\"\r\n\
             END\r\n\
           END\r\n\
           BLOCK \"VarFileInfo\"\r\n\
           BEGIN\r\n\
             VALUE \"Translation\", 0x0409, 1200\r\n\
           END\r\n\
         END\r\n"
    );

    let _ = std::fs::write(&rc_script_full, full_rc);
    let compiled = Command::new(&rc_exe)
        .args(["/nologo", "/fo"])
        .arg(&res_output)
        .arg(&rc_script_full)
        .status()
        .is_ok_and(|s| s.success());

    if !compiled || !res_output.is_file() {
        let minimal_rc = format!("1 ICON \"{ico_escaped}\"\r\n");
        let _ = std::fs::write(&rc_script_full, minimal_rc);
        let _ = Command::new(&rc_exe)
            .args(["/nologo", "/fo"])
            .arg(&res_output)
            .arg(&rc_script_full)
            .status();
    }

    if res_output.is_file() {
        println!(
            "cargo:rustc-link-arg-bin=MashClient={}",
            res_output.display()
        );
    }
}

#[cfg(windows)]
fn find_windows_rc_exe() -> Option<std::path::PathBuf> {
    use std::path::PathBuf;
    use std::process::Command;

    if Command::new("rc.exe").arg("/?").output().is_ok() {
        return Some(PathBuf::from("rc.exe"));
    }

    let kits_roots = [
        r"C:\Program Files (x86)\Windows Kits\10\bin",
        r"C:\Program Files\Windows Kits\10\bin",
    ];
    for root in kits_roots {
        let Ok(entries) = std::fs::read_dir(root) else {
            continue;
        };
        let mut versions: Vec<PathBuf> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.is_dir()
                    && p.file_name()
                        .and_then(|n| n.to_str())
                        .is_some_and(|s| s.starts_with("10."))
            })
            .collect();
        versions.sort();
        versions.reverse();
        for ver_dir in versions {
            let candidate = ver_dir.join("x64").join("rc.exe");
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}
