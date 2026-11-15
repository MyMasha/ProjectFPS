//! Boîtes de dialogue natives Windows (`comdlg32.dll` — `GetOpenFileNameW` / `GetSaveFileNameW`)
//! pour l'importation de mods `.jar` (sélection multiple) et l'import/export de profils `.json`,
//! sans aucune dépendance externe supplémentaire.

use std::path::PathBuf;

/// Décode un tampon UTF-16 `OPENFILENAMEW` (`OFN_EXPLORER | OFN_ALLOWMULTISELECT`) :
/// - Sélection simple : `"C:\\dossier\\fichier.jar\0\0"`
/// - Sélection multiple : `"C:\\dossier\0mod1.jar\0mod2.jar\0\0"`
pub fn parse_ofn_multiselect_buffer(buf: &[u16]) -> Vec<PathBuf> {
    let mut parts: Vec<String> = Vec::new();
    let mut start = 0usize;

    while start < buf.len() {
        let mut end = start;
        while end < buf.len() && buf[end] != 0 {
            end += 1;
        }
        if end == start {
            break;
        }
        let s = String::from_utf16_lossy(&buf[start..end]);
        parts.push(s);
        start = end + 1;
    }

    if parts.is_empty() {
        return Vec::new();
    }
    if parts.len() == 1 {
        return vec![PathBuf::from(&parts[0])];
    }

    let dir = PathBuf::from(&parts[0]);
    parts[1..].iter().map(|name| dir.join(name)).collect()
}

#[cfg(windows)]
mod win32_dlg {
    use super::*;
    use core::ffi::c_void;

    const OFN_NOCHANGEDIR: u32 = 0x0000_0008;
    const OFN_OVERWRITEPROMPT: u32 = 0x0000_0002;
    const OFN_ALLOWMULTISELECT: u32 = 0x0000_0200;
    const OFN_PATHMUSTEXIST: u32 = 0x0000_0800;
    const OFN_FILEMUSTEXIST: u32 = 0x0000_1000;
    const OFN_EXPLORER: u32 = 0x0008_0000;

    #[repr(C)]
    struct OPENFILENAMEW {
        l_struct_size: u32,
        hwnd_owner: *mut c_void,
        h_instance: *mut c_void,
        lpstr_filter: *const u16,
        lpstr_custom_filter: *mut u16,
        n_max_cust_filter: u32,
        n_filter_index: u32,
        lpstr_file: *mut u16,
        n_max_file: u32,
        lpstr_file_title: *mut u16,
        n_max_file_title: u32,
        lpstr_initial_dir: *const u16,
        lpstr_title: *const u16,
        flags: u32,
        n_file_offset: u16,
        n_file_extension: u16,
        lpstr_def_ext: *const u16,
        l_cust_data: isize,
        lpfn_hook: *const c_void,
        lp_template_name: *const u16,
        pv_reserved: *mut c_void,
        dw_reserved: u32,
        flags_ex: u32,
    }

    #[link(name = "comdlg32")]
    extern "system" {
        fn GetOpenFileNameW(lpofn: *mut OPENFILENAMEW) -> i32;
        fn GetSaveFileNameW(lpofn: *mut OPENFILENAMEW) -> i32;
    }

    #[link(name = "user32")]
    extern "system" {
        fn GetForegroundWindow() -> *mut c_void;
    }

    #[link(name = "ole32")]
    extern "system" {
        fn CoInitializeEx(pvReserved: *mut c_void, dwCoInit: u32) -> i32;
    }

    fn ensure_com_sta() {
        const COINIT_APARTMENTTHREADED: u32 = 0x2;
        unsafe {
            let _ = CoInitializeEx(core::ptr::null_mut(), COINIT_APARTMENTTHREADED);
        }
    }

    fn wide_z(s: &str) -> Vec<u16> {
        let mut v: Vec<u16> = s.encode_utf16().collect();
        v.push(0);
        v
    }

    pub fn open_jar_files() -> Vec<PathBuf> {
        ensure_com_sta();
        let filter: Vec<u16> = "Mods Minecraft 1.8.9 (*.jar)\0*.jar\0Tous les fichiers (*.*)\0*.*\0\0"
            .encode_utf16()
            .collect();
        let title = wide_z("Importer des mods Minecraft 1.8.9 (.jar)");
        let mut file_buf = vec![0u16; 32_768];

        unsafe {
            let mut ofn: OPENFILENAMEW = core::mem::zeroed();
            ofn.l_struct_size = core::mem::size_of::<OPENFILENAMEW>() as u32;
            ofn.hwnd_owner = GetForegroundWindow();
            ofn.lpstr_filter = filter.as_ptr();
            ofn.n_filter_index = 1;
            ofn.lpstr_file = file_buf.as_mut_ptr();
            ofn.n_max_file = file_buf.len() as u32;
            ofn.lpstr_title = title.as_ptr();
            ofn.flags = OFN_EXPLORER
                | OFN_FILEMUSTEXIST
                | OFN_PATHMUSTEXIST
                | OFN_ALLOWMULTISELECT
                | OFN_NOCHANGEDIR;

            if GetOpenFileNameW(&mut ofn) != 0 {
                parse_ofn_multiselect_buffer(&file_buf)
            } else {
                Vec::new()
            }
        }
    }

    pub fn open_json_profile() -> Option<PathBuf> {
        ensure_com_sta();
        let filter: Vec<u16> = "Profil MashClient (*.json)\0*.json\0Tous les fichiers (*.*)\0*.*\0\0"
            .encode_utf16()
            .collect();
        let title = wide_z("Importer un profil MashClient (.json)");
        let mut file_buf = vec![0u16; 4096];

        unsafe {
            let mut ofn: OPENFILENAMEW = core::mem::zeroed();
            ofn.l_struct_size = core::mem::size_of::<OPENFILENAMEW>() as u32;
            ofn.hwnd_owner = GetForegroundWindow();
            ofn.lpstr_filter = filter.as_ptr();
            ofn.n_filter_index = 1;
            ofn.lpstr_file = file_buf.as_mut_ptr();
            ofn.n_max_file = file_buf.len() as u32;
            ofn.lpstr_title = title.as_ptr();
            ofn.flags = OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;

            if GetOpenFileNameW(&mut ofn) != 0 {
                parse_ofn_multiselect_buffer(&file_buf).into_iter().next()
            } else {
                None
            }
        }
    }

    pub fn save_json_profile(default_name: &str) -> Option<PathBuf> {
        ensure_com_sta();
        let filter: Vec<u16> = "Profil MashClient (*.json)\0*.json\0\0"
            .encode_utf16()
            .collect();
        let title = wide_z("Exporter le profil MashClient (.json)");
        let def_ext = wide_z("json");
        let mut file_buf = vec![0u16; 4096];
        for (i, ch) in default_name.encode_utf16().take(240).enumerate() {
            file_buf[i] = ch;
        }

        unsafe {
            let mut ofn: OPENFILENAMEW = core::mem::zeroed();
            ofn.l_struct_size = core::mem::size_of::<OPENFILENAMEW>() as u32;
            ofn.hwnd_owner = GetForegroundWindow();
            ofn.lpstr_filter = filter.as_ptr();
            ofn.n_filter_index = 1;
            ofn.lpstr_file = file_buf.as_mut_ptr();
            ofn.n_max_file = file_buf.len() as u32;
            ofn.lpstr_title = title.as_ptr();
            ofn.lpstr_def_ext = def_ext.as_ptr();
            ofn.flags = OFN_EXPLORER | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;

            if GetSaveFileNameW(&mut ofn) != 0 {
                parse_ofn_multiselect_buffer(&file_buf).into_iter().next()
            } else {
                None
            }
        }
    }
}

#[cfg(windows)]
pub fn pick_jar_files_dialog() -> Vec<PathBuf> {
    win32_dlg::open_jar_files()
}

#[cfg(not(windows))]
pub fn pick_jar_files_dialog() -> Vec<PathBuf> {
    Vec::new()
}

#[cfg(windows)]
pub fn pick_open_json_dialog() -> Option<PathBuf> {
    win32_dlg::open_json_profile()
}

#[cfg(not(windows))]
pub fn pick_open_json_dialog() -> Option<PathBuf> {
    None
}

#[cfg(windows)]
pub fn pick_save_json_dialog(default_name: &str) -> Option<PathBuf> {
    win32_dlg::save_json_profile(default_name)
}

#[cfg(not(windows))]
pub fn pick_save_json_dialog(_default_name: &str) -> Option<PathBuf> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode_multi(parts: &[&str]) -> Vec<u16> {
        let mut out = Vec::new();
        for p in parts {
            out.extend(p.encode_utf16());
            out.push(0);
        }
        out.push(0);
        out
    }

    #[test]
    fn test_parse_ofn_single_and_multiselect() {
        let single = encode_multi(&[r"C:\Mods\OptiFine_1.8.9.jar"]);
        let res_single = parse_ofn_multiselect_buffer(&single);
        assert_eq!(res_single, vec![PathBuf::from(r"C:\Mods\OptiFine_1.8.9.jar")]);

        let multi = encode_multi(&[r"C:\Mods", "Patcher-1.8.9.jar", "ReplayMod.jar"]);
        let res_multi = parse_ofn_multiselect_buffer(&multi);
        assert_eq!(
            res_multi,
            vec![
                PathBuf::from(r"C:\Mods").join("Patcher-1.8.9.jar"),
                PathBuf::from(r"C:\Mods").join("ReplayMod.jar"),
            ]
        );
    }
}
