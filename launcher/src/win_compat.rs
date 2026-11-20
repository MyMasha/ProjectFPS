//! Couche de compatibilité Windows 10 & Windows 11 pour MashClient :
//! - Détection réelle de version Windows (`RtlGetVersion` : distingue Windows 10 et Windows 11 / 22H2 / 23H2 / 24H2)
//! - DPI Awareness Per-Monitor V2 (`SetProcessDpiAwarenessContext` + repli `shcore.dll`)
//! - Style natif DWM Windows 10/11 (`DWMWA_USE_IMMERSIVE_DARK_MODE`, coins arrondis Win11 en mode fenêtré, zéro flash blanc)
//! - Détection du runtime Microsoft Edge WebView2 + boîte d'erreur native Win32 en cas d'absence sur Windows 10
//! - Presse-papiers Unicode natif (`CF_UNICODETEXT`) sans sous-processus bloquant `clip.exe` et sans corruption UTF-8
//! - Activation de fenêtre LWJGL 100 % non-bloquante (`SWP_ASYNCWINDOWPOS`, zéro `AttachThreadInput`)
//!   + détection Win32 `IsHungAppWindow` et collecte d'environnement matériel (GPU, RAM, DPI).

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct WindowsVersionInfo {
    pub major: u32,
    pub minor: u32,
    pub build: u32,
    pub is_windows_11: bool,
    pub is_windows_10: bool,
    pub display_name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct SystemHardwareInfo {
    pub os_label: String,
    pub os_build: u32,
    pub is_windows_11: bool,
    pub total_ram_mb: u64,
    pub avail_ram_mb: u64,
    pub cpu_threads: usize,
    pub gpu_name: String,
    pub dpi_scale_pct: u32,
    pub webview2_detected: bool,
}

/// Formate un libellé Windows lisible et précis à partir du numéro `(major, minor, build)` retourné par `RtlGetVersion`.
pub fn classify_windows_build(major: u32, minor: u32, build: u32) -> WindowsVersionInfo {
    let is_windows_11 = major > 10 || (major == 10 && build >= 22000);
    let is_windows_10 = major == 10 && build < 22000;

    let display_name = if is_windows_11 {
        let release = match build {
            26100.. => "24H2+",
            22631..=26099 => "23H2",
            22621..=22630 => "22H2",
            _ => "21H2",
        };
        format!("Windows 11 {release} (Build {build})")
    } else if is_windows_10 {
        let release = match build {
            19045.. => "22H2",
            19044 => "21H2",
            19043 => "21H1",
            19042 => "20H2",
            19041 => "2004",
            18363 => "1909",
            18362 => "1903",
            17763 => "1809",
            _ => "10",
        };
        if release == "10" {
            format!("Windows 10 (Build {build})")
        } else {
            format!("Windows 10 {release} (Build {build})")
        }
    } else if major == 6 && minor == 3 {
        format!("Windows 8.1 (Build {build})")
    } else if major == 6 && minor == 2 {
        format!("Windows 8 (Build {build})")
    } else if major == 6 && minor == 1 {
        format!("Windows 7 (Build {build})")
    } else if major == 0 {
        std::env::consts::OS.to_owned()
    } else {
        format!("Windows {major}.{minor} (Build {build})")
    };

    WindowsVersionInfo {
        major,
        minor,
        build,
        is_windows_11,
        is_windows_10,
        display_name,
    }
}

#[cfg(windows)]
mod imp {
    use super::{classify_windows_build, SystemHardwareInfo, WindowsVersionInfo};
    use core::ffi::c_void;
    use std::ptr::{null, null_mut};

    type HWND = *mut c_void;
    type HMODULE = *mut c_void;
    type HANDLE = *mut c_void;
    type HKEY = *mut c_void;

    const GMEM_MOVEABLE: u32 = 0x0002;
    const CF_UNICODETEXT: u32 = 13;
    const MB_OK: u32 = 0x0000_0000;
    const MB_ICONERROR: u32 = 0x0000_0010;
    const MB_TOPMOST: u32 = 0x0004_0000;
    const PROCESS_TERMINATE: u32 = 0x0001;
    const DISPLAY_DEVICE_PRIMARY_DEVICE: u32 = 0x0000_0004;
    const DISPLAY_DEVICE_ACTIVE: u32 = 0x0000_0001;

    const HKEY_CURRENT_USER: HKEY = 0x8000_0001usize as HKEY;
    const HKEY_LOCAL_MACHINE: HKEY = 0x8000_0002usize as HKEY;
    const KEY_READ: u32 = 0x0002_0019;

    const DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1: u32 = 19;
    const DWMWA_USE_IMMERSIVE_DARK_MODE: u32 = 20;
    const DWMWA_WINDOW_CORNER_PREFERENCE: u32 = 33;
    const DWMWA_BORDER_COLOR: u32 = 34;
    const DWMWCP_DONOTROUND: u32 = 1;
    const DWMWCP_ROUND: u32 = 2;

    const HWND_TOPMOST: HWND = -1isize as HWND;
    const HWND_NOTOPMOST: HWND = -2isize as HWND;
    const SWP_NOSIZE: u32 = 0x0001;
    const SWP_NOMOVE: u32 = 0x0002;
    const SWP_NOACTIVATE: u32 = 0x0010;
    const SWP_SHOWWINDOW: u32 = 0x0040;
    const SWP_ASYNCWINDOWPOS: u32 = 0x4000;
    const GCLP_HBRBACKGROUND: i32 = -10;
    const LOAD_LIBRARY_SEARCH_SYSTEM32: u32 = 0x0000_0800;

    #[repr(C)]
    struct OsVersionInfoExW {
        dw_os_version_info_size: u32,
        dw_major_version: u32,
        dw_minor_version: u32,
        dw_build_number: u32,
        dw_platform_id: u32,
        sz_csd_version: [u16; 128],
        w_service_pack_major: u16,
        w_service_pack_minor: u16,
        w_suite_mask: u16,
        w_product_type: u8,
        w_reserved: u8,
    }

    #[repr(C)]
    struct MemoryStatusEx {
        dw_length: u32,
        dw_memory_load: u32,
        ull_total_phys: u64,
        ull_avail_phys: u64,
        ull_total_page_file: u64,
        ull_avail_page_file: u64,
        ull_total_virtual: u64,
        ull_avail_virtual: u64,
        ull_avail_extended_virtual: u64,
    }

    #[repr(C)]
    struct DisplayDeviceW {
        cb: u32,
        device_name: [u16; 32],
        device_string: [u16; 128],
        state_flags: u32,
        device_id: [u16; 128],
        device_key: [u16; 128],
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GetModuleHandleW(lpModuleName: *const u16) -> HMODULE;
        fn LoadLibraryExW(
            lpLibFileName: *const u16,
            hFile: HANDLE,
            dwFlags: u32,
        ) -> HMODULE;
        fn FreeLibrary(hLibModule: HMODULE) -> i32;
        fn GetProcAddress(hModule: HMODULE, lpProcName: *const u8) -> *const c_void;
        fn GlobalMemoryStatusEx(lpBuffer: *mut MemoryStatusEx) -> i32;
        fn GlobalAlloc(uFlags: u32, dwBytes: usize) -> HANDLE;
        fn GlobalLock(hMem: HANDLE) -> *mut c_void;
        fn GlobalUnlock(hMem: HANDLE) -> i32;
        fn GlobalFree(hMem: HANDLE) -> HANDLE;
        fn OpenProcess(dwDesiredAccess: u32, bInheritHandle: i32, dwProcessId: u32) -> HANDLE;
        fn TerminateProcess(hProcess: HANDLE, uExitCode: u32) -> i32;
        fn CloseHandle(hObject: HANDLE) -> i32;
    }

    #[link(name = "user32")]
    extern "system" {
        fn OpenClipboard(hWndNewOwner: HWND) -> i32;
        fn EmptyClipboard() -> i32;
        fn SetClipboardData(uFormat: u32, hMem: HANDLE) -> HANDLE;
        fn CloseClipboard() -> i32;
        fn MessageBoxW(
            hWnd: HWND,
            lpText: *const u16,
            lpCaption: *const u16,
            uType: u32,
        ) -> i32;
        fn EnumDisplayDevicesW(
            lpDevice: *const u16,
            iDevNum: u32,
            lpDisplayDevice: *mut DisplayDeviceW,
            dwFlags: u32,
        ) -> i32;
        fn IsHungAppWindow(hWnd: HWND) -> i32;
        fn IsWindow(hWnd: HWND) -> i32;
        fn IsWindowVisible(hWnd: HWND) -> i32;
        fn EnumWindows(
            lpEnumFunc: unsafe extern "system" fn(HWND, isize) -> i32,
            lParam: isize,
        ) -> i32;
        fn GetWindowThreadProcessId(hWnd: HWND, lpdwProcessId: *mut u32) -> u32;
        fn GetClassNameW(hWnd: HWND, lpClassName: *mut u16, nMaxCount: i32) -> i32;
        fn AllowSetForegroundWindow(dwProcessId: u32) -> i32;
        fn SetWindowPos(
            hWnd: HWND,
            hWndInsertAfter: HWND,
            X: i32,
            Y: i32,
            cx: i32,
            cy: i32,
            uFlags: u32,
        ) -> i32;
        fn SetForegroundWindow(hWnd: HWND) -> i32;
        fn SetClassLongPtrW(hWnd: HWND, nIndex: i32, dwNewLong: isize) -> usize;
    }

    #[link(name = "gdi32")]
    extern "system" {
        fn GetStockObject(i: i32) -> *mut c_void;
    }

    #[link(name = "advapi32")]
    extern "system" {
        fn RegOpenKeyExW(
            hKey: HKEY,
            lpSubKey: *const u16,
            ulOptions: u32,
            samDesired: u32,
            phkResult: *mut HKEY,
        ) -> i32;
        fn RegCloseKey(hKey: HKEY) -> i32;
    }

    fn wide_z(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(core::iter::once(0)).collect()
    }

    fn utf16_slice_to_string(buf: &[u16]) -> String {
        let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..len]).trim().to_owned()
    }

    pub fn detect_windows_version() -> WindowsVersionInfo {
        unsafe {
            let ntdll_name = wide_z("ntdll.dll");
            let h_ntdll = GetModuleHandleW(ntdll_name.as_ptr());
            if !h_ntdll.is_null() {
                let proc_ptr = GetProcAddress(h_ntdll, b"RtlGetVersion\0".as_ptr());
                if !proc_ptr.is_null() {
                    type RtlGetVersionFn = unsafe extern "system" fn(*mut OsVersionInfoExW) -> i32;
                    let rtl_get_version: RtlGetVersionFn = core::mem::transmute(proc_ptr);
                    let mut info: OsVersionInfoExW = core::mem::zeroed();
                    info.dw_os_version_info_size = core::mem::size_of::<OsVersionInfoExW>() as u32;
                    if rtl_get_version(&mut info) == 0 {
                        return classify_windows_build(
                            info.dw_major_version,
                            info.dw_minor_version,
                            info.dw_build_number,
                        );
                    }
                }
            }
        }
        classify_windows_build(10, 0, 19045)
    }

    pub fn enable_process_dpi_awareness() {
        unsafe {
            let user32_name = wide_z("user32.dll");
            let h_user32 = GetModuleHandleW(user32_name.as_ptr());
            if !h_user32.is_null() {
                let p = GetProcAddress(h_user32, b"SetProcessDpiAwarenessContext\0".as_ptr());
                if !p.is_null() {
                    type SetDpiCtxFn = unsafe extern "system" fn(isize) -> i32;
                    let func: SetDpiCtxFn = core::mem::transmute(p);
                    // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
                    if func(-4) != 0 {
                        return;
                    }
                    // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE = -3
                    if func(-3) != 0 {
                        return;
                    }
                }
            }
            let shcore_name = wide_z("shcore.dll");
            let h_shcore =
                LoadLibraryExW(shcore_name.as_ptr(), null_mut(), LOAD_LIBRARY_SEARCH_SYSTEM32);
            if !h_shcore.is_null() {
                let p = GetProcAddress(h_shcore, b"SetProcessDpiAwareness\0".as_ptr());
                if !p.is_null() {
                    type SetDpiAwareFn = unsafe extern "system" fn(i32) -> i32;
                    let func: SetDpiAwareFn = core::mem::transmute(p);
                    // PROCESS_PER_MONITOR_DPI_AWARE = 2
                    let _ = func(2);
                }
                let _ = FreeLibrary(h_shcore);
            }
        }
    }

    pub fn apply_window_win10_11_styling(hwnd_bits: usize, fullscreen: bool) {
        if hwnd_bits == 0 {
            return;
        }
        let hwnd = hwnd_bits as HWND;
        let ver = detect_windows_version();
        unsafe {
            // Fond noir natif Win32 (BLACK_BRUSH = 4) : zéro flash blanc au démarrage ou redimensionnement
            let black_brush = GetStockObject(4);
            if !black_brush.is_null() {
                let _ = SetClassLongPtrW(hwnd, GCLP_HBRBACKGROUND, black_brush as isize);
            }

            let dwm_name = wide_z("dwmapi.dll");
            let h_dwm =
                LoadLibraryExW(dwm_name.as_ptr(), null_mut(), LOAD_LIBRARY_SEARCH_SYSTEM32);
            if h_dwm.is_null() {
                return;
            }
            let p = GetProcAddress(h_dwm, b"DwmSetWindowAttribute\0".as_ptr());
            if p.is_null() {
                let _ = FreeLibrary(h_dwm);
                return;
            }
            type DwmSetAttrFn = unsafe extern "system" fn(HWND, u32, *const c_void, u32) -> i32;
            let dwm_set: DwmSetAttrFn = core::mem::transmute(p);

            let dark_mode: i32 = 1;
            let hr = dwm_set(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                (&dark_mode as *const i32).cast(),
                core::mem::size_of::<i32>() as u32,
            );
            if hr != 0 {
                let _ = dwm_set(
                    hwnd,
                    DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1,
                    (&dark_mode as *const i32).cast(),
                    core::mem::size_of::<i32>() as u32,
                );
            }

            if ver.is_windows_11 {
                let corner_pref: u32 = if fullscreen {
                    DWMWCP_DONOTROUND
                } else {
                    DWMWCP_ROUND
                };
                let _ = dwm_set(
                    hwnd,
                    DWMWA_WINDOW_CORNER_PREFERENCE,
                    (&corner_pref as *const u32).cast(),
                    core::mem::size_of::<u32>() as u32,
                );
                let border_col: u32 = if fullscreen { 0xFFFF_FFFE } else { 0x001E_1A16 };
                let _ = dwm_set(
                    hwnd,
                    DWMWA_BORDER_COLOR,
                    (&border_col as *const u32).cast(),
                    core::mem::size_of::<u32>() as u32,
                );
            }
            let _ = FreeLibrary(h_dwm);
        }
    }

    pub fn is_webview2_runtime_installed() -> bool {
        let subkeys = [
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            ),
            (
                HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            ),
            (
                HKEY_CURRENT_USER,
                r"Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}",
            ),
        ];
        unsafe {
            for (root, path) in subkeys {
                let wide = wide_z(path);
                let mut hkey: HKEY = null_mut();
                if RegOpenKeyExW(root, wide.as_ptr(), 0, KEY_READ, &mut hkey) == 0 {
                    let _ = RegCloseKey(hkey);
                    return true;
                }
            }
        }
        false
    }

    pub fn set_clipboard_unicode(text: &str) -> bool {
        let utf16: Vec<u16> = text.encode_utf16().chain(core::iter::once(0)).collect();
        let bytes = utf16.len() * core::mem::size_of::<u16>();
        unsafe {
            if OpenClipboard(null_mut()) == 0 {
                return false;
            }
            let _ = EmptyClipboard();
            let hmem = GlobalAlloc(GMEM_MOVEABLE, bytes);
            if hmem.is_null() {
                let _ = CloseClipboard();
                return false;
            }
            let dst = GlobalLock(hmem) as *mut u16;
            if dst.is_null() {
                let _ = GlobalFree(hmem);
                let _ = CloseClipboard();
                return false;
            }
            core::ptr::copy_nonoverlapping(utf16.as_ptr(), dst, utf16.len());
            let _ = GlobalUnlock(hmem);
            let res = SetClipboardData(CF_UNICODETEXT, hmem);
            if res.is_null() {
                let _ = GlobalFree(hmem);
                let _ = CloseClipboard();
                return false;
            }
            let _ = CloseClipboard();
            true
        }
    }

    pub fn show_native_error_box(title: &str, message: &str) {
        let t = wide_z(title);
        let m = wide_z(message);
        unsafe {
            let _ = MessageBoxW(null_mut(), m.as_ptr(), t.as_ptr(), MB_OK | MB_ICONERROR | MB_TOPMOST);
        }
    }

    pub fn is_hwnd_hung(hwnd_bits: usize) -> bool {
        if hwnd_bits == 0 {
            return false;
        }
        unsafe { IsHungAppWindow(hwnd_bits as HWND) != 0 }
    }

    pub fn is_hwnd_valid(hwnd_bits: usize) -> bool {
        if hwnd_bits == 0 {
            return false;
        }
        unsafe { IsWindow(hwnd_bits as HWND) != 0 && IsWindowVisible(hwnd_bits as HWND) != 0 }
    }

    struct EnumPidCtx {
        target_pid: u32,
        lwjgl_hwnd: usize,
        fallback_hwnd: usize,
    }

    unsafe extern "system" fn enum_pid_window_proc(hwnd: HWND, lparam: isize) -> i32 {
        let ctx = &mut *(lparam as *mut EnumPidCtx);
        let mut pid: u32 = 0;
        let _ = GetWindowThreadProcessId(hwnd, &mut pid);
        if pid != ctx.target_pid || IsWindowVisible(hwnd) == 0 {
            return 1;
        }
        let mut class_buf = [0u16; 64];
        let n = GetClassNameW(hwnd, class_buf.as_mut_ptr(), class_buf.len() as i32);
        if n > 0 {
            let cls = String::from_utf16_lossy(&class_buf[..n as usize]);
            if cls == "LWJGL" {
                ctx.lwjgl_hwnd = hwnd as usize;
                return 0;
            }
        }
        if ctx.fallback_hwnd == 0 {
            ctx.fallback_hwnd = hwnd as usize;
        }
        1
    }

    pub fn find_visible_window_for_pid(target_pid: u32) -> Option<usize> {
        if target_pid == 0 {
            return None;
        }
        let mut ctx = EnumPidCtx {
            target_pid,
            lwjgl_hwnd: 0,
            fallback_hwnd: 0,
        };
        unsafe {
            let _ = EnumWindows(enum_pid_window_proc, (&mut ctx as *mut EnumPidCtx) as isize);
        }
        if ctx.lwjgl_hwnd != 0 {
            Some(ctx.lwjgl_hwnd)
        } else if ctx.fallback_hwnd != 0 {
            Some(ctx.fallback_hwnd)
        } else {
            None
        }
    }

    /// Active une fenêtre LWJGL de manière 100 % non-bloquante :
    /// - N'appelle JAMAIS `AttachThreadInput` ni `SetWindowPos` synchrone (cause classique du freeze "Not Responding"
    ///   de `MashClient.exe` lorsque `javaw.exe` ne pompe pas encore sa file de messages Win32).
    /// - Refuse immédiatement d'interagir si la fenêtre cible est déjà marquée `IsHungAppWindow`.
    pub fn focus_window_nonblocking(hwnd_bits: usize) -> bool {
        if hwnd_bits == 0 {
            return false;
        }
        let hwnd = hwnd_bits as HWND;
        unsafe {
            if IsWindowVisible(hwnd) == 0 || IsHungAppWindow(hwnd) != 0 {
                return false;
            }
            let mut pid: u32 = 0;
            let _ = GetWindowThreadProcessId(hwnd, &mut pid);
            if pid != 0 {
                let _ = AllowSetForegroundWindow(pid);
            }
            let async_flags = SWP_ASYNCWINDOWPOS | SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW | SWP_NOACTIVATE;
            let _ = SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, async_flags);
            let _ = SetWindowPos(
                hwnd,
                HWND_NOTOPMOST,
                0,
                0,
                0,
                0,
                SWP_ASYNCWINDOWPOS | SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW,
            );
            let _ = SetForegroundWindow(hwnd);
            true
        }
    }

    pub fn terminate_pid_forcefully(pid: u32) -> bool {
        if pid == 0 {
            return false;
        }
        unsafe {
            let handle = OpenProcess(PROCESS_TERMINATE, 0, pid);
            if handle.is_null() {
                return false;
            }
            let ok = TerminateProcess(handle, 1) != 0;
            let _ = CloseHandle(handle);
            ok
        }
    }

    fn detect_primary_gpu_name() -> String {
        unsafe {
            let mut fallback = String::new();
            for dev_idx in 0..8u32 {
                let mut dd: DisplayDeviceW = core::mem::zeroed();
                dd.cb = core::mem::size_of::<DisplayDeviceW>() as u32;
                if EnumDisplayDevicesW(null(), dev_idx, &mut dd, 0) == 0 {
                    break;
                }
                let name = utf16_slice_to_string(&dd.device_string);
                if name.is_empty() {
                    continue;
                }
                if (dd.state_flags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0 {
                    return name;
                }
                if fallback.is_empty() && (dd.state_flags & DISPLAY_DEVICE_ACTIVE) != 0 {
                    fallback = name;
                }
            }
            if fallback.is_empty() {
                "GPU Windows inconnu".to_owned()
            } else {
                fallback
            }
        }
    }

    fn detect_system_dpi_scale_pct() -> u32 {
        unsafe {
            let user32_name = wide_z("user32.dll");
            let h_user32 = GetModuleHandleW(user32_name.as_ptr());
            if !h_user32.is_null() {
                let p = GetProcAddress(h_user32, b"GetDpiForSystem\0".as_ptr());
                if !p.is_null() {
                    type GetDpiFn = unsafe extern "system" fn() -> u32;
                    let func: GetDpiFn = core::mem::transmute(p);
                    let dpi = func();
                    if dpi >= 48 && dpi <= 576 {
                        return (dpi * 100) / 96;
                    }
                }
            }
        }
        100
    }

    pub fn collect_system_hardware_info() -> SystemHardwareInfo {
        let ver = detect_windows_version();
        let mut total_ram_mb = 0u64;
        let mut avail_ram_mb = 0u64;
        unsafe {
            let mut mem: MemoryStatusEx = core::mem::zeroed();
            mem.dw_length = core::mem::size_of::<MemoryStatusEx>() as u32;
            if GlobalMemoryStatusEx(&mut mem) != 0 {
                total_ram_mb = mem.ull_total_phys / (1024 * 1024);
                avail_ram_mb = mem.ull_avail_phys / (1024 * 1024);
            }
        }
        let cpu_threads = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        let gpu_name = detect_primary_gpu_name();
        let dpi_scale_pct = detect_system_dpi_scale_pct();
        let webview2_detected = is_webview2_runtime_installed();

        SystemHardwareInfo {
            os_label: ver.display_name,
            os_build: ver.build,
            is_windows_11: ver.is_windows_11,
            total_ram_mb,
            avail_ram_mb,
            cpu_threads,
            gpu_name,
            dpi_scale_pct,
            webview2_detected,
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use super::{classify_windows_build, SystemHardwareInfo, WindowsVersionInfo};

    pub fn detect_windows_version() -> WindowsVersionInfo {
        classify_windows_build(0, 0, 0)
    }

    pub fn enable_process_dpi_awareness() {}

    pub fn apply_window_win10_11_styling(_hwnd_bits: usize, _fullscreen: bool) {}

    pub fn is_webview2_runtime_installed() -> bool {
        true
    }

    pub fn set_clipboard_unicode(_text: &str) -> bool {
        false
    }

    pub fn show_native_error_box(title: &str, message: &str) {
        eprintln!("[{title}] {message}");
    }

    pub fn is_hwnd_hung(_hwnd_bits: usize) -> bool {
        false
    }

    pub fn is_hwnd_valid(_hwnd_bits: usize) -> bool {
        false
    }

    pub fn find_visible_window_for_pid(_target_pid: u32) -> Option<usize> {
        None
    }

    pub fn focus_window_nonblocking(_hwnd_bits: usize) -> bool {
        false
    }

    pub fn terminate_pid_forcefully(_pid: u32) -> bool {
        false
    }

    pub fn collect_system_hardware_info() -> SystemHardwareInfo {
        let cpu_threads = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        SystemHardwareInfo {
            os_label: std::env::consts::OS.to_owned(),
            os_build: 0,
            is_windows_11: false,
            total_ram_mb: 0,
            avail_ram_mb: 0,
            cpu_threads,
            gpu_name: "N/A".to_owned(),
            dpi_scale_pct: 100,
            webview2_detected: true,
        }
    }
}

pub use imp::{
    apply_window_win10_11_styling, collect_system_hardware_info, detect_windows_version,
    enable_process_dpi_awareness, find_visible_window_for_pid, focus_window_nonblocking,
    is_hwnd_hung, is_hwnd_valid, is_webview2_runtime_installed, set_clipboard_unicode,
    show_native_error_box, terminate_pid_forcefully,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_classify_windows_10_and_11_builds() {
        let w10 = classify_windows_build(10, 0, 19045);
        assert!(w10.is_windows_10);
        assert!(!w10.is_windows_11);
        assert_eq!(w10.display_name, "Windows 10 22H2 (Build 19045)");

        let w11_23h2 = classify_windows_build(10, 0, 22631);
        assert!(w11_23h2.is_windows_11);
        assert!(!w11_23h2.is_windows_10);
        assert_eq!(w11_23h2.display_name, "Windows 11 23H2 (Build 22631)");

        let w11_24h2 = classify_windows_build(10, 0, 26100);
        assert!(w11_24h2.is_windows_11);
        assert_eq!(w11_24h2.display_name, "Windows 11 24H2+ (Build 26100)");
    }

    #[test]
    fn test_windows_10_11_manifest_xml_contains_required_settings() {
        let manifest = include_str!("../assets/mashclient.manifest");
        assert!(manifest.contains("{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"));
        assert!(manifest.contains("PerMonitorV2"));
        assert!(manifest.contains("longPathAware"));
        assert!(manifest.contains("Microsoft.Windows.Common-Controls"));
        assert!(manifest.contains("asInvoker"));
    }
}
