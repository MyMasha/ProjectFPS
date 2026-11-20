//! Pont autonome WebView2Loader pour cross-compilation Windows GNU/GNULLVM :
//! - Embarque la DLL officielle signée Microsoft `WebView2Loader.dll` directement dans `MashClient.exe`
//!   afin de produire un exécutable 100 % autonome (fichier unique sans aucune DLL externe requise).
//! - Sur MSVC (`windows-latest`), ce module n'est pas compilé car `WebView2LoaderStatic.lib` est utilisé.

use core::ffi::c_void;
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

const EMBEDDED_WEBVIEW2_LOADER_DLL: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/WebView2Loader.dll"));

const E_FAIL: i32 = 0x8000_4005u32 as i32;

type FnCompareBrowserVersions =
    unsafe extern "system" fn(*const u16, *const u16, *mut i32) -> i32;
type FnCreateCoreWebView2Environment = unsafe extern "system" fn(*mut c_void) -> i32;
type FnCreateCoreWebView2EnvironmentWithOptions =
    unsafe extern "system" fn(*const u16, *const u16, *mut c_void, *mut c_void) -> i32;
type FnGetAvailableCoreWebView2BrowserVersionString =
    unsafe extern "system" fn(*const u16, *mut *mut u16) -> i32;
type FnGetAvailableCoreWebView2BrowserVersionStringWithOptions =
    unsafe extern "system" fn(*const u16, *mut c_void, *mut *mut u16) -> i32;

struct WebView2Exports {
    compare: Option<FnCompareBrowserVersions>,
    create_env: Option<FnCreateCoreWebView2Environment>,
    create_env_opts: Option<FnCreateCoreWebView2EnvironmentWithOptions>,
    get_ver: Option<FnGetAvailableCoreWebView2BrowserVersionString>,
    get_ver_opts: Option<FnGetAvailableCoreWebView2BrowserVersionStringWithOptions>,
}

#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryW(lpLibFileName: *const u16) -> *mut c_void;
    fn GetProcAddress(hModule: *mut c_void, lpProcName: *const u8) -> *mut c_void;
}

static EXPORTS: OnceLock<WebView2Exports> = OnceLock::new();

fn matches_embedded_dll(path: &Path) -> bool {
    let expected_len = EMBEDDED_WEBVIEW2_LOADER_DLL.len() as u64;
    let is_regular_same_len = std::fs::symlink_metadata(path)
        .map(|m| m.file_type().is_file() && m.len() == expected_len)
        .unwrap_or(false);
    if !is_regular_same_len {
        return false;
    }
    std::fs::read(path)
        .map(|bytes| bytes.as_slice() == EMBEDDED_WEBVIEW2_LOADER_DLL)
        .unwrap_or(false)
}

fn ensure_extracted_dll_at(dir: &Path) -> Option<PathBuf> {
    std::fs::create_dir_all(dir).ok()?;
    let dll_path = dir.join("WebView2Loader_x64_0_39_1.dll");

    if !matches_embedded_dll(&dll_path) {
        let tmp_path = dir.join(format!(
            "WebView2Loader_x64_0_39_1.{}.tmp",
            std::process::id()
        ));
        if std::fs::write(&tmp_path, EMBEDDED_WEBVIEW2_LOADER_DLL).is_ok() {
            let _ = std::fs::remove_file(&dll_path);
            if std::fs::rename(&tmp_path, &dll_path).is_err() {
                let _ = std::fs::remove_file(&tmp_path);
            }
        }
    }

    if matches_embedded_dll(&dll_path) {
        Some(dll_path)
    } else {
        None
    }
}

fn load_exports() -> &'static WebView2Exports {
    EXPORTS.get_or_init(|| {
        let primary_dir = crate::config::app_storage_dir().join("bin");
        let fallback_dir = PathBuf::from(crate::config::resolve_user_local_appdata())
            .join(".PureFPS")
            .join("bin");

        let dll_path = ensure_extracted_dll_at(&primary_dir)
            .or_else(|| ensure_extracted_dll_at(&fallback_dir));

        let Some(path) = dll_path else {
            return WebView2Exports {
                compare: None,
                create_env: None,
                create_env_opts: None,
                get_ver: None,
                get_ver_opts: None,
            };
        };

        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe {
            let hmod = LoadLibraryW(wide.as_ptr());
            if hmod.is_null() {
                return WebView2Exports {
                    compare: None,
                    create_env: None,
                    create_env_opts: None,
                    get_ver: None,
                    get_ver_opts: None,
                };
            }

            let p_cmp = GetProcAddress(hmod, b"CompareBrowserVersions\0".as_ptr());
            let p_env = GetProcAddress(hmod, b"CreateCoreWebView2Environment\0".as_ptr());
            let p_env_opts =
                GetProcAddress(hmod, b"CreateCoreWebView2EnvironmentWithOptions\0".as_ptr());
            let p_ver = GetProcAddress(
                hmod,
                b"GetAvailableCoreWebView2BrowserVersionString\0".as_ptr(),
            );
            let p_ver_opts = GetProcAddress(
                hmod,
                b"GetAvailableCoreWebView2BrowserVersionStringWithOptions\0".as_ptr(),
            );

            WebView2Exports {
                compare: (!p_cmp.is_null()).then(|| core::mem::transmute(p_cmp)),
                create_env: (!p_env.is_null()).then(|| core::mem::transmute(p_env)),
                create_env_opts: (!p_env_opts.is_null()).then(|| core::mem::transmute(p_env_opts)),
                get_ver: (!p_ver.is_null()).then(|| core::mem::transmute(p_ver)),
                get_ver_opts: (!p_ver_opts.is_null()).then(|| core::mem::transmute(p_ver_opts)),
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "system" fn CompareBrowserVersions(
    version1: *const u16,
    version2: *const u16,
    result: *mut i32,
) -> i32 {
    match load_exports().compare {
        Some(f) => unsafe { f(version1, version2, result) },
        None => E_FAIL,
    }
}

#[no_mangle]
pub unsafe extern "system" fn CreateCoreWebView2Environment(
    environment_created_handler: *mut c_void,
) -> i32 {
    match load_exports().create_env {
        Some(f) => unsafe { f(environment_created_handler) },
        None => E_FAIL,
    }
}

#[no_mangle]
pub unsafe extern "system" fn CreateCoreWebView2EnvironmentWithOptions(
    browser_executable_folder: *const u16,
    user_data_folder: *const u16,
    environment_options: *mut c_void,
    environment_created_handler: *mut c_void,
) -> i32 {
    match load_exports().create_env_opts {
        Some(f) => unsafe {
            f(
                browser_executable_folder,
                user_data_folder,
                environment_options,
                environment_created_handler,
            )
        },
        None => E_FAIL,
    }
}

#[no_mangle]
pub unsafe extern "system" fn GetAvailableCoreWebView2BrowserVersionString(
    browser_executable_folder: *const u16,
    version_info: *mut *mut u16,
) -> i32 {
    match load_exports().get_ver {
        Some(f) => unsafe { f(browser_executable_folder, version_info) },
        None => E_FAIL,
    }
}

#[no_mangle]
pub unsafe extern "system" fn GetAvailableCoreWebView2BrowserVersionStringWithOptions(
    browser_executable_folder: *const u16,
    environment_options: *mut c_void,
    version_info: *mut *mut u16,
) -> i32 {
    match load_exports().get_ver_opts {
        Some(f) => unsafe { f(browser_executable_folder, environment_options, version_info) },
        None => E_FAIL,
    }
}
