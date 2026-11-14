use std::path::Path;

fn main() {
    println!("cargo:rerun-if-changed=.github_token");
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
}
