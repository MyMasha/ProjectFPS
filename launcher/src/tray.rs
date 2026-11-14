//! Icône native de la zone de notification Windows (System Tray) et garde d'instance unique pour MashClient.
//! - Empêche l'ouverture de plusieurs instances `MashClient.exe` : réveille et remet au premier plan l'instance existante
//! - Réduit proprement le Launcher en bas à droite dès que Minecraft démarre (zéro encombrement dans la barre des tâches)
//! - Clic gauche / double-clic : restaure instantanément MashClient
//! - Clic droit : menu contextuel natif ("Ouvrir MashClient" / "Quitter MashClient")
//! - Nettoyage garanti `NIM_DELETE` à la fermeture pour ne jamais laisser d'icône fantôme.

#[cfg(windows)]
mod imp {
    use crate::AppEvent;
    use core::ffi::c_void;
    use std::ptr::{null, null_mut};
    use std::sync::mpsc;
    use std::thread::JoinHandle;
    use std::time::Duration;
    use tao::event_loop::EventLoopProxy;

    type HWND = *mut c_void;
    type HANDLE = *mut c_void;
    type HINSTANCE = *mut c_void;
    type HICON = *mut c_void;
    type HCURSOR = *mut c_void;
    type HBRUSH = *mut c_void;
    type HMENU = *mut c_void;

    const ERROR_ALREADY_EXISTS: u32 = 183;

    const WM_DESTROY: u32 = 0x0002;
    const WM_NULL: u32 = 0x0000;
    const WM_LBUTTONUP: u32 = 0x0202;
    const WM_LBUTTONDBLCLK: u32 = 0x0203;
    const WM_RBUTTONUP: u32 = 0x0205;
    const WM_CONTEXTMENU: u32 = 0x007B;
    const WM_USER: u32 = 0x0400;
    const WM_APP: u32 = 0x8000;

    const WM_TRAY_CALLBACK: u32 = WM_USER + 42;
    const WM_TRAY_SET_GAME_RUNNING: u32 = WM_APP + 1;
    const WM_TRAY_SET_IDLE: u32 = WM_APP + 2;
    const WM_TRAY_WAKE_EXISTING: u32 = WM_APP + 3;
    const WM_TRAY_SHUTDOWN: u32 = WM_APP + 9;
    const NIN_BALLOONUSERCLICK: u32 = WM_USER + 5;

    const NIM_ADD: u32 = 0x0000_0000;
    const NIM_MODIFY: u32 = 0x0000_0001;
    const NIM_DELETE: u32 = 0x0000_0002;

    const NIF_MESSAGE: u32 = 0x0000_0001;
    const NIF_ICON: u32 = 0x0000_0002;
    const NIF_TIP: u32 = 0x0000_0004;

    const IMAGE_ICON: u32 = 1;
    const LR_DEFAULTSIZE: u32 = 0x0000_0040;

    const MF_STRING: u32 = 0x0000_0000;
    const MF_SEPARATOR: u32 = 0x0000_0800;
    const TPM_RIGHTBUTTON: u32 = 0x0002;
    const TPM_NONOTIFY: u32 = 0x0080;
    const TPM_RETURNCMD: u32 = 0x0100;
    const TPM_BOTTOMALIGN: u32 = 0x0020;

    const CMD_RESTORE: usize = 1001;
    const CMD_QUIT: usize = 1002;
    const TRAY_ICON_ID: u32 = 1;

    const TRAY_HOST_CLASS: &str = "MashClientTrayHiddenClass";
    const TRAY_HOST_TITLE: &str = "MashClientTrayHost";
    const SINGLE_INSTANCE_MUTEX: &str = "Local\\MashClient_SingleInstance_Mutex";

    static EMBEDDED_ICO: &[u8] = include_bytes!("../assets/mashclient.ico");

    #[repr(C)]
    #[derive(Copy, Clone)]
    struct GUID {
        data1: u32,
        data2: u16,
        data3: u16,
        data4: [u8; 8],
    }

    #[repr(C)]
    struct NOTIFYICONDATAW {
        cb_size: u32,
        h_wnd: HWND,
        u_id: u32,
        u_flags: u32,
        u_callback_message: u32,
        h_icon: HICON,
        sz_tip: [u16; 128],
        dw_state: u32,
        dw_state_mask: u32,
        sz_info: [u16; 256],
        u_timeout_or_version: u32,
        sz_info_title: [u16; 64],
        dw_info_flags: u32,
        guid_item: GUID,
        h_balloon_icon: HICON,
    }

    #[repr(C)]
    struct WNDCLASSEXW {
        cb_size: u32,
        style: u32,
        lpfn_wnd_proc: Option<unsafe extern "system" fn(HWND, u32, usize, isize) -> isize>,
        cb_cls_extra: i32,
        cb_wnd_extra: i32,
        h_instance: HINSTANCE,
        h_icon: HICON,
        h_cursor: HCURSOR,
        hbr_background: HBRUSH,
        lpsz_menu_name: *const u16,
        lpsz_class_name: *const u16,
        h_icon_sm: HICON,
    }

    #[repr(C)]
    #[derive(Copy, Clone)]
    struct POINT {
        x: i32,
        y: i32,
    }

    #[repr(C)]
    struct MSG {
        hwnd: HWND,
        message: u32,
        w_param: usize,
        l_param: isize,
        time: u32,
        pt: POINT,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GetModuleHandleW(lpModuleName: *const u16) -> HINSTANCE;
        fn CreateMutexW(
            lpMutexAttributes: *mut c_void,
            bInitialOwner: i32,
            lpName: *const u16,
        ) -> HANDLE;
        fn GetLastError() -> u32;
        fn CloseHandle(hObject: HANDLE) -> i32;
    }

    #[link(name = "user32")]
    extern "system" {
        fn RegisterClassExW(lpWndClass: *const WNDCLASSEXW) -> u16;
        fn CreateWindowExW(
            dwExStyle: u32,
            lpClassName: *const u16,
            lpWindowName: *const u16,
            dwStyle: u32,
            X: i32,
            Y: i32,
            nWidth: i32,
            nHeight: i32,
            hWndParent: HWND,
            hMenu: HMENU,
            hInstance: HINSTANCE,
            lpParam: *mut c_void,
        ) -> HWND;
        fn FindWindowW(lpClassName: *const u16, lpWindowName: *const u16) -> HWND;
        fn GetWindowThreadProcessId(hWnd: HWND, lpdwProcessId: *mut u32) -> u32;
        fn AllowSetForegroundWindow(dwProcessId: u32) -> i32;
        fn DefWindowProcW(hWnd: HWND, Msg: u32, wParam: usize, lParam: isize) -> isize;
        fn GetMessageW(lpMsg: *mut MSG, hWnd: HWND, wMsgFilterMin: u32, wMsgFilterMax: u32) -> i32;
        fn TranslateMessage(lpMsg: *const MSG) -> i32;
        fn DispatchMessageW(lpMsg: *const MSG) -> isize;
        fn PostMessageW(hWnd: HWND, Msg: u32, wParam: usize, lParam: isize) -> i32;
        fn PostQuitMessage(nExitCode: i32);
        fn DestroyWindow(hWnd: HWND) -> i32;
        fn SetWindowLongPtrW(hWnd: HWND, nIndex: i32, dwNewLong: isize) -> isize;
        fn GetWindowLongPtrW(hWnd: HWND, nIndex: i32) -> isize;
        fn LoadImageW(
            hInst: HINSTANCE,
            name: *const u16,
            type_: u32,
            cx: i32,
            cy: i32,
            fuLoad: u32,
        ) -> HICON;
        fn CreateIconFromResourceEx(
            presbits: *const u8,
            dwResSize: u32,
            fIcon: i32,
            dwVer: u32,
            cxDesired: i32,
            cyDesired: i32,
            Flags: u32,
        ) -> HICON;
        fn LoadIconW(hInstance: HINSTANCE, lpIconName: *const u16) -> HICON;
        fn DestroyIcon(hIcon: HICON) -> i32;
        fn CreatePopupMenu() -> HMENU;
        fn AppendMenuW(hMenu: HMENU, uFlags: u32, uIDNewItem: usize, lpNewItem: *const u16) -> i32;
        fn DestroyMenu(hMenu: HMENU) -> i32;
        fn GetCursorPos(lpPoint: *mut POINT) -> i32;
        fn SetForegroundWindow(hWnd: HWND) -> i32;
        fn TrackPopupMenu(
            hMenu: HMENU,
            uFlags: u32,
            x: i32,
            y: i32,
            nReserved: i32,
            hWnd: HWND,
            prcRect: *const c_void,
        ) -> i32;
        fn RegisterWindowMessageW(lpString: *const u16) -> u32;
    }

    #[link(name = "shell32")]
    extern "system" {
        fn Shell_NotifyIconW(dwMessage: u32, lpData: *mut NOTIFYICONDATAW) -> i32;
    }

    const GWLP_USERDATA: i32 = -21;

    struct TrayWndState {
        proxy: EventLoopProxy<AppEvent>,
        h_icon: HICON,
        owns_icon: bool,
        taskbar_created_msg: u32,
        current_tip: String,
    }

    fn encode_wide_null(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(core::iter::once(0)).collect()
    }

    fn write_tip(dst: &mut [u16; 128], text: &str) {
        dst.fill(0);
        for (i, ch) in text.encode_utf16().take(127).enumerate() {
            dst[i] = ch;
        }
    }

    /// Réveille l'instance MashClient déjà ouverte (même masquée dans le System Tray en bas à droite),
    /// lui transfère le droit de premier plan Windows (`AllowSetForegroundWindow`) et lui demande de s'afficher.
    unsafe fn wake_existing_instance() {
        let class_name = encode_wide_null(TRAY_HOST_CLASS);
        let win_title = encode_wide_null(TRAY_HOST_TITLE);

        for _ in 0..16 {
            let hwnd = FindWindowW(class_name.as_ptr(), win_title.as_ptr());
            if !hwnd.is_null() {
                let mut pid: u32 = 0;
                let _ = GetWindowThreadProcessId(hwnd, &mut pid);
                if pid != 0 {
                    let _ = AllowSetForegroundWindow(pid);
                }
                let _ = PostMessageW(hwnd, WM_TRAY_WAKE_EXISTING, 0, 0);
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    /// Garde d'instance unique basée sur un Mutex nommé Windows (`Local\MashClient_SingleInstance_Mutex`).
    pub struct SingleInstanceGuard {
        handle_bits: usize,
    }

    impl SingleInstanceGuard {
        /// Tente d'acquérir le verrou d'instance unique.
        /// Retourne `None` si une instance de `MashClient.exe` tourne déjà (après l'avoir réveillée).
        pub fn acquire(wait_for_restart: bool) -> Option<Self> {
            let mutex_name = encode_wide_null(SINGLE_INSTANCE_MUTEX);
            let attempts = if wait_for_restart { 80 } else { 1 };

            for attempt in 0..attempts {
                let handle = unsafe { CreateMutexW(null_mut(), 0, mutex_name.as_ptr()) };
                let last_err = unsafe { GetLastError() };

                if handle.is_null() {
                    return Some(Self { handle_bits: 0 });
                }

                if last_err != ERROR_ALREADY_EXISTS {
                    return Some(Self {
                        handle_bits: handle as usize,
                    });
                }

                unsafe {
                    let _ = CloseHandle(handle);
                }

                if wait_for_restart && attempt + 1 < attempts {
                    std::thread::sleep(Duration::from_millis(50));
                    continue;
                }

                unsafe {
                    wake_existing_instance();
                }
                return None;
            }

            None
        }

        /// Libère explicitement le mutex juste avant un auto-redémarrage suite à une mise à jour.
        pub fn release(&mut self) {
            if self.handle_bits != 0 {
                unsafe {
                    let _ = CloseHandle(self.handle_bits as HANDLE);
                }
                self.handle_bits = 0;
            }
        }
    }

    impl Drop for SingleInstanceGuard {
        fn drop(&mut self) {
            self.release();
        }
    }

    unsafe fn load_mash_tray_icon(h_instance: HINSTANCE) -> (HICON, bool) {
        let embedded_res = LoadImageW(
            h_instance,
            1usize as *const u16,
            IMAGE_ICON,
            0,
            0,
            LR_DEFAULTSIZE,
        );
        if !embedded_res.is_null() {
            return (embedded_res, true);
        }

        if EMBEDDED_ICO.len() >= 22 {
            let count = u16::from_le_bytes([EMBEDDED_ICO[4], EMBEDDED_ICO[5]]) as usize;
            let mut chosen_offset = 0usize;
            let mut chosen_size = 0usize;
            for idx in 0..count {
                let base = 6 + idx * 16;
                if base + 16 > EMBEDDED_ICO.len() {
                    break;
                }
                let w = EMBEDDED_ICO[base];
                let size = u32::from_le_bytes([
                    EMBEDDED_ICO[base + 8],
                    EMBEDDED_ICO[base + 9],
                    EMBEDDED_ICO[base + 10],
                    EMBEDDED_ICO[base + 11],
                ]) as usize;
                let offset = u32::from_le_bytes([
                    EMBEDDED_ICO[base + 12],
                    EMBEDDED_ICO[base + 13],
                    EMBEDDED_ICO[base + 14],
                    EMBEDDED_ICO[base + 15],
                ]) as usize;
                if offset + size <= EMBEDDED_ICO.len() && (w == 32 || chosen_size == 0) {
                    chosen_offset = offset;
                    chosen_size = size;
                    if w == 32 {
                        break;
                    }
                }
            }
            if chosen_size > 0 {
                let icon = CreateIconFromResourceEx(
                    EMBEDDED_ICO[chosen_offset..].as_ptr(),
                    chosen_size as u32,
                    1,
                    0x0003_0000,
                    32,
                    32,
                    0,
                );
                if !icon.is_null() {
                    return (icon, true);
                }
            }
        }

        (LoadIconW(null_mut(), 32512usize as *const u16), false)
    }

    unsafe fn make_nid(hwnd: HWND, h_icon: HICON, tip: &str) -> NOTIFYICONDATAW {
        let mut nid: NOTIFYICONDATAW = core::mem::zeroed();
        nid.cb_size = core::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.h_wnd = hwnd;
        nid.u_id = TRAY_ICON_ID;
        nid.u_flags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.u_callback_message = WM_TRAY_CALLBACK;
        nid.h_icon = h_icon;
        write_tip(&mut nid.sz_tip, tip);
        nid
    }

    unsafe fn show_tray_context_menu(hwnd: HWND, proxy: &EventLoopProxy<AppEvent>) {
        let h_menu = CreatePopupMenu();
        if h_menu.is_null() {
            return;
        }
        let open_lbl = encode_wide_null("Ouvrir MashClient");
        let quit_lbl = encode_wide_null("Quitter MashClient");
        let _ = AppendMenuW(h_menu, MF_STRING, CMD_RESTORE, open_lbl.as_ptr());
        let _ = AppendMenuW(h_menu, MF_SEPARATOR, 0, null());
        let _ = AppendMenuW(h_menu, MF_STRING, CMD_QUIT, quit_lbl.as_ptr());

        let mut pt = POINT { x: 0, y: 0 };
        let _ = GetCursorPos(&mut pt);
        let _ = SetForegroundWindow(hwnd);
        let selected = TrackPopupMenu(
            h_menu,
            TPM_RIGHTBUTTON | TPM_BOTTOMALIGN | TPM_NONOTIFY | TPM_RETURNCMD,
            pt.x,
            pt.y,
            0,
            hwnd,
            null(),
        ) as usize;
        let _ = PostMessageW(hwnd, WM_NULL, 0, 0);
        let _ = DestroyMenu(h_menu);

        match selected {
            CMD_RESTORE => {
                let _ = proxy.send_event(AppEvent::TrayRestore);
            }
            CMD_QUIT => {
                let _ = proxy.send_event(AppEvent::TrayQuit);
            }
            _ => {}
        }
    }

    unsafe extern "system" fn tray_wnd_proc(
        hwnd: HWND,
        msg: u32,
        w_param: usize,
        l_param: isize,
    ) -> isize {
        let state_ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut TrayWndState;
        if !state_ptr.is_null() {
            let st = &mut *state_ptr;
            if msg == WM_TRAY_CALLBACK {
                let evt = (l_param as u32) & 0xFFFF;
                match evt {
                    WM_LBUTTONUP | WM_LBUTTONDBLCLK | NIN_BALLOONUSERCLICK => {
                        let _ = st.proxy.send_event(AppEvent::TrayRestore);
                    }
                    WM_RBUTTONUP | WM_CONTEXTMENU => {
                        show_tray_context_menu(hwnd, &st.proxy);
                    }
                    _ => {}
                }
                return 0;
            } else if msg == WM_TRAY_WAKE_EXISTING {
                let _ = st.proxy.send_event(AppEvent::TrayRestore);
                return 0;
            } else if msg == WM_TRAY_SET_GAME_RUNNING {
                st.current_tip = "MashClient — En jeu (clic pour ouvrir)".to_owned();
                let mut nid = make_nid(hwnd, st.h_icon, &st.current_tip);
                let _ = Shell_NotifyIconW(NIM_MODIFY, &mut nid);
                return 0;
            } else if msg == WM_TRAY_SET_IDLE {
                st.current_tip = "MashClient".to_owned();
                let mut nid = make_nid(hwnd, st.h_icon, &st.current_tip);
                let _ = Shell_NotifyIconW(NIM_MODIFY, &mut nid);
                return 0;
            } else if msg == WM_TRAY_SHUTDOWN {
                let mut nid = make_nid(hwnd, st.h_icon, "");
                let _ = Shell_NotifyIconW(NIM_DELETE, &mut nid);
                let _ = DestroyWindow(hwnd);
                return 0;
            } else if st.taskbar_created_msg != 0 && msg == st.taskbar_created_msg {
                let mut nid = make_nid(hwnd, st.h_icon, &st.current_tip);
                let _ = Shell_NotifyIconW(NIM_ADD, &mut nid);
                return 0;
            } else if msg == WM_DESTROY {
                PostQuitMessage(0);
                return 0;
            }
        }
        DefWindowProcW(hwnd, msg, w_param, l_param)
    }

    pub struct SystemTray {
        hwnd_bits: usize,
        worker: Option<JoinHandle<()>>,
    }

    impl SystemTray {
        pub fn new(proxy: EventLoopProxy<AppEvent>) -> Self {
            let (tx, rx) = mpsc::channel::<usize>();
            let worker = std::thread::spawn(move || unsafe {
                let h_instance = GetModuleHandleW(null());
                let class_name = encode_wide_null(TRAY_HOST_CLASS);
                let win_title = encode_wide_null(TRAY_HOST_TITLE);
                let taskbar_msg_name = encode_wide_null("TaskbarCreated");
                let taskbar_created_msg = RegisterWindowMessageW(taskbar_msg_name.as_ptr());

                let mut wc: WNDCLASSEXW = core::mem::zeroed();
                wc.cb_size = core::mem::size_of::<WNDCLASSEXW>() as u32;
                wc.lpfn_wnd_proc = Some(tray_wnd_proc);
                wc.h_instance = h_instance;
                wc.lpsz_class_name = class_name.as_ptr();
                let _ = RegisterClassExW(&wc);

                let hwnd = CreateWindowExW(
                    0,
                    class_name.as_ptr(),
                    win_title.as_ptr(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    null_mut(),
                    null_mut(),
                    h_instance,
                    null_mut(),
                );
                if hwnd.is_null() {
                    let _ = tx.send(0);
                    return;
                }

                let (h_icon, owns_icon) = load_mash_tray_icon(h_instance);
                let initial_tip = "MashClient".to_owned();
                let state = Box::new(TrayWndState {
                    proxy,
                    h_icon,
                    owns_icon,
                    taskbar_created_msg,
                    current_tip: initial_tip.clone(),
                });
                let state_raw = Box::into_raw(state);
                let _ = SetWindowLongPtrW(hwnd, GWLP_USERDATA, state_raw as isize);

                let mut nid = make_nid(hwnd, h_icon, &initial_tip);
                let _ = Shell_NotifyIconW(NIM_ADD, &mut nid);

                let _ = tx.send(hwnd as usize);

                let mut msg: MSG = core::mem::zeroed();
                while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
                    let _ = TranslateMessage(&msg);
                    let _ = DispatchMessageW(&msg);
                }

                let _ = SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                let boxed = Box::from_raw(state_raw);
                if boxed.owns_icon && !boxed.h_icon.is_null() {
                    let _ = DestroyIcon(boxed.h_icon);
                }
            });

            let hwnd_bits = rx.recv().unwrap_or(0);
            Self {
                hwnd_bits,
                worker: Some(worker),
            }
        }

        pub fn set_game_running_mode(&self, running: bool) {
            if self.hwnd_bits != 0 {
                let msg = if running {
                    WM_TRAY_SET_GAME_RUNNING
                } else {
                    WM_TRAY_SET_IDLE
                };
                unsafe {
                    let _ = PostMessageW(self.hwnd_bits as HWND, msg, 0, 0);
                }
            }
        }
    }

    impl Drop for SystemTray {
        fn drop(&mut self) {
            if self.hwnd_bits != 0 {
                unsafe {
                    let _ = PostMessageW(self.hwnd_bits as HWND, WM_TRAY_SHUTDOWN, 0, 0);
                }
                self.hwnd_bits = 0;
            }
            if let Some(handle) = self.worker.take() {
                let _ = handle.join();
            }
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use crate::AppEvent;
    use tao::event_loop::EventLoopProxy;

    pub struct SingleInstanceGuard;

    impl SingleInstanceGuard {
        pub fn acquire(_wait_for_restart: bool) -> Option<Self> {
            Some(Self)
        }

        pub fn release(&mut self) {}
    }

    pub struct SystemTray;

    impl SystemTray {
        pub fn new(_proxy: EventLoopProxy<AppEvent>) -> Self {
            Self
        }

        pub fn set_game_running_mode(&self, _running: bool) {}
    }
}

pub use imp::{SingleInstanceGuard, SystemTray};
