//! Système complet de progression de lancement, chronologie de diagnostic et surveillance
//! anti-blocage ("Not Responding") pour MashClient (Launcher + Minecraft 1.8.9 JVM).
//!
//! Fonctionnalités :
//! - Progression structurée en 7 étapes (0 % -> 100 %) transmise à la barre de chargement UI
//! - Journal chronologique horodaté de chaque opération (téléchargements, natives, JVM, Forge, LWJGL, audio, GPU)
//! - Capture de localisation exacte en cas de crash ou de gel "Not Responding" :
//!   1. Lecture du dump `config/.purefps_hang_dump.txt` produit en direct par `ClientHangWatchdog.java`
//!   2. Repli `jstack.exe` / `jcmd.exe` (avec délai strict de 3,5 s) si un JDK est installé
//!   3. Analyse des logs `fml-client-latest.log` / `latest.log` + sortie stdout/stderr + mods suspects
//! - Watchdog dédié du thread UI de `MashClient.exe` : détecte tout gel > 6 s de la fenêtre Launcher,
//!   enregistre un rapport sur disque et l'affiche à la reprise ou au démarrage suivant.

use crate::config::app_storage_dir;
use crate::win_compat::{collect_system_hardware_info, SystemHardwareInfo};
use crate::AppEvent;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tao::event_loop::EventLoopProxy;

const MAX_TIMELINE_ITEMS: usize = 240;
const MAX_RECENT_STEPS: usize = 5;
const MAX_CONSOLE_RING: usize = 160;
const UI_HANG_THRESHOLD_MS: u64 = 6_000;

#[derive(Debug, Clone, Serialize)]
pub struct LaunchProgressInfo {
    pub active: bool,
    pub percent: u8,
    pub stage_index: u8,
    pub total_steps: u8,
    pub stage_title: String,
    pub detail: String,
    pub elapsed_secs: u64,
    pub is_stalled: bool,
    pub stall_reason: Option<String>,
    pub recent_steps: Vec<String>,
}

impl LaunchProgressInfo {
    pub fn inactive() -> Self {
        Self {
            active: false,
            percent: 0,
            stage_index: 0,
            total_steps: 7,
            stage_title: String::new(),
            detail: String::new(),
            elapsed_secs: 0,
            is_stalled: false,
            stall_reason: None,
            recent_steps: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosticItem {
    pub time: String,
    pub level: String,
    pub stage: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrashReportInfo {
    pub title: String,
    pub summary: String,
    pub details: String,
    pub exit_code: Option<i32>,
    pub report_path: Option<String>,
    pub suspected_mod_file: Option<String>,
    #[serde(default)]
    pub is_not_responding: bool,
    #[serde(default)]
    pub crash_location: Option<String>,
    #[serde(default)]
    pub environment_lines: Vec<String>,
    #[serde(default)]
    pub diagnostic_steps: Vec<DiagnosticItem>,
    #[serde(default)]
    pub thread_stack_lines: Vec<String>,
    #[serde(default)]
    pub can_force_kill: bool,
}

#[derive(Debug, Default)]
pub struct ParsedHangDump {
    pub phase: Option<String>,
    pub frozen_secs: Option<String>,
    pub thread_state: Option<String>,
    pub pinpoint_location: Option<String>,
    pub memory_info: Option<String>,
    pub stack_lines: Vec<String>,
}

struct TimelineInner {
    started_at: Instant,
    last_activity_at: Instant,
    last_progress_emit: Instant,
    stage_index: u8,
    percent: u8,
    stage_title: String,
    detail: String,
    last_forge_phase: Option<String>,
    last_log_line: Option<String>,
    entries: VecDeque<DiagnosticItem>,
    recent_steps: VecDeque<String>,
    console_ring: VecDeque<String>,
}

pub struct DiagnosticTimeline {
    game_dir: PathBuf,
    java_path: PathBuf,
    ram_mb: u32,
    is_vulkan: bool,
    hw: SystemHardwareInfo,
    inner: Mutex<TimelineInner>,
}

fn format_elapsed_tag(elapsed: Duration) -> String {
    let total_ms = elapsed.as_millis();
    let secs = total_ms / 1000;
    let tenths = (total_ms % 1000) / 100;
    let mins = secs / 60;
    let rem_secs = secs % 60;
    format!("+{mins:02}:{rem_secs:02}.{tenths}")
}

fn epoch_secs_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn epoch_ms_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

impl DiagnosticTimeline {
    pub fn new(game_dir: PathBuf, java_path: PathBuf, ram_mb: u32, is_vulkan: bool) -> Self {
        let now = Instant::now();
        let hw = collect_system_hardware_info();
        let tl = Self {
            game_dir,
            java_path,
            ram_mb,
            is_vulkan,
            hw,
            inner: Mutex::new(TimelineInner {
                started_at: now,
                last_activity_at: now,
                last_progress_emit: now.checked_sub(Duration::from_secs(1)).unwrap_or(now),
                stage_index: 1,
                percent: 2,
                stage_title: "Préparation du lancement".to_owned(),
                detail: "Initialisation de la session MashClient...".to_owned(),
                last_forge_phase: None,
                last_log_line: None,
                entries: VecDeque::with_capacity(MAX_TIMELINE_ITEMS),
                recent_steps: VecDeque::with_capacity(MAX_RECENT_STEPS),
                console_ring: VecDeque::with_capacity(MAX_CONSOLE_RING),
            }),
        };
        tl.record(
            "info",
            "SYSTEM",
            format!(
                "{} | GPU: {} | RAM: {} Mo libre / {} Mo total | DPI: {}%",
                tl.hw.os_label,
                tl.hw.gpu_name,
                tl.hw.avail_ram_mb,
                tl.hw.total_ram_mb,
                tl.hw.dpi_scale_pct
            ),
        );
        tl.record(
            "info",
            "CONFIG",
            format!(
                "Backend: {} | Allocation JVM: {} Mo | Dossier: {}",
                if is_vulkan {
                    "Vulkan Bêta (Mesa3D Zink)"
                } else {
                    "OpenGL natif"
                },
                ram_mb,
                tl.game_dir.display()
            ),
        );
        tl
    }

    pub fn record(&self, level: &str, stage: &str, message: impl Into<String>) {
        let msg = message.into();
        if let Ok(mut g) = self.inner.lock() {
            let now = Instant::now();
            g.last_activity_at = now;
            let time = format_elapsed_tag(now.duration_since(g.started_at));
            if g.entries.len() >= MAX_TIMELINE_ITEMS {
                g.entries.pop_front();
            }
            g.entries.push_back(DiagnosticItem {
                time: time.clone(),
                level: level.to_owned(),
                stage: stage.to_owned(),
                message: msg.clone(),
            });
            if level != "debug" {
                let short = format!("[{stage}] {msg}");
                if g.recent_steps.back().map(String::as_str) != Some(short.as_str()) {
                    if g.recent_steps.len() >= MAX_RECENT_STEPS {
                        g.recent_steps.pop_front();
                    }
                    g.recent_steps.push_back(short);
                }
            }
        }
    }

    pub fn emit_progress(
        &self,
        proxy: &EventLoopProxy<AppEvent>,
        stage_index: u8,
        percent: u8,
        stage_title: impl Into<String>,
        detail: impl Into<String>,
        force: bool,
    ) {
        let stage_title = stage_title.into();
        let detail = detail.into();
        let payload = {
            let Ok(mut g) = self.inner.lock() else {
                return;
            };
            let now = Instant::now();
            g.last_activity_at = now;
            let stage_changed = g.stage_index != stage_index || g.stage_title != stage_title;
            let clamped_pct = percent.min(100);
            g.stage_index = stage_index;
            g.percent = clamped_pct;
            g.stage_title = stage_title.clone();
            g.detail = detail.clone();

            if !force
                && !stage_changed
                && clamped_pct < 100
                && now.duration_since(g.last_progress_emit) < Duration::from_millis(75)
            {
                return;
            }
            g.last_progress_emit = now;

            LaunchProgressInfo {
                active: true,
                percent: clamped_pct,
                stage_index,
                total_steps: 7,
                stage_title,
                detail,
                elapsed_secs: now.duration_since(g.started_at).as_secs(),
                is_stalled: false,
                stall_reason: None,
                recent_steps: g.recent_steps.iter().cloned().collect(),
            }
        };
        let _ = proxy.send_event(AppEvent::LaunchProgress(payload));
    }

    pub fn emit_stall_warning(&self, proxy: &EventLoopProxy<AppEvent>, reason: String) {
        let payload = {
            let Ok(g) = self.inner.lock() else {
                return;
            };
            LaunchProgressInfo {
                active: true,
                percent: g.percent,
                stage_index: g.stage_index,
                total_steps: 7,
                stage_title: g.stage_title.clone(),
                detail: g.detail.clone(),
                elapsed_secs: g.started_at.elapsed().as_secs(),
                is_stalled: true,
                stall_reason: Some(reason),
                recent_steps: g.recent_steps.iter().cloned().collect(),
            }
        };
        let _ = proxy.send_event(AppEvent::LaunchProgress(payload));
    }

    pub fn seconds_since_last_activity(&self) -> u64 {
        self.inner
            .lock()
            .map(|g| g.last_activity_at.elapsed().as_secs())
            .unwrap_or(0)
    }

    pub fn current_stage_summary(&self) -> (String, String, Option<String>) {
        self.inner
            .lock()
            .map(|g| {
                (
                    g.stage_title.clone(),
                    g.detail.clone(),
                    g.last_forge_phase.clone(),
                )
            })
            .unwrap_or_else(|_| ("Inconnu".to_owned(), String::new(), None))
    }

    /// Analyse une ligne de log stdout/stderr de la JVM Minecraft/Forge en temps réel,
    /// met à jour la chronologie de diagnostic et renvoie une éventuelle mise à jour `(percent, étape)`.
    pub fn observe_game_log_line(&self, line: &str) -> Option<(u8, String, String)> {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return None;
        }

        if let Ok(mut g) = self.inner.lock() {
            g.last_activity_at = Instant::now();
            g.last_log_line = Some(trimmed.chars().take(220).collect());
            if g.console_ring.len() >= MAX_CONSOLE_RING {
                g.console_ring.pop_front();
            }
            g.console_ring.push_back(trimmed.to_owned());
        }

        if let Some((pct, stage, label)) = classify_forge_log_milestone(trimmed) {
            if let Ok(mut g) = self.inner.lock() {
                g.last_forge_phase = Some(label.clone());
            }
            let level = if stage == "ERROR" {
                "error"
            } else if stage == "WATCHDOG" {
                "hang"
            } else if pct == 100 {
                "ok"
            } else {
                "info"
            };
            self.record(level, stage, label.clone());
            if pct > 0 {
                return Some((pct, stage.to_owned(), label));
            }
        }
        None
    }

    pub fn environment_lines(&self) -> Vec<String> {
        let mods_list = crate::mods_manager::list_installed_mods(&self.game_dir.to_string_lossy());
        let active_mods: Vec<String> = mods_list
            .iter()
            .filter(|m| m.enabled)
            .map(|m| m.file_name.clone())
            .collect();
        let probe = crate::runtimes::probe_java8_executable(&self.java_path);

        vec![
            format!("Système : {}", self.hw.os_label),
            format!("Carte graphique : {}", self.hw.gpu_name),
            format!(
                "Mémoire RAM : {} Mo alloués JVM (système : {} Mo libres / {} Mo total)",
                self.ram_mb, self.hw.avail_ram_mb, self.hw.total_ram_mb
            ),
            format!(
                "Processeur & Affichage : {} threads logiques · Mise à l'échelle DPI {}%",
                self.hw.cpu_threads, self.hw.dpi_scale_pct
            ),
            format!(
                "Runtime Java : {} ({})",
                probe.label,
                sanitize_diagnostic_text(&self.java_path.display().to_string())
            ),
            format!(
                "Backend graphique : {}",
                if self.is_vulkan {
                    "Vulkan Bêta (Mesa3D Zink)"
                } else {
                    "OpenGL 1.8.9 natif"
                }
            ),
            format!(
                "Mods actifs ({}) : {}",
                active_mods.len(),
                if active_mods.is_empty() {
                    "aucun".to_owned()
                } else {
                    active_mods.join(", ")
                }
            ),
        ]
    }

    pub fn build_not_responding_report(
        &self,
        child_pid: Option<u32>,
        reason: &str,
        hung_secs: u64,
        can_force_kill: bool,
    ) -> CrashReportInfo {
        self.record(
            "hang",
            "WATCHDOG",
            format!("Blocage « Not Responding » confirmé ({hung_secs}s) — {reason}"),
        );

        let mut stack_lines: Vec<String> = Vec::new();
        let mut pinpoint: Option<String> = None;

        // 1. Lecture prioritaire du dump interne généré par ClientHangWatchdog.java dans la JVM
        let hang_dump_path = self.game_dir.join("config").join(".purefps_hang_dump.txt");
        if let Ok(raw_dump) = std::fs::read_to_string(&hang_dump_path) {
            let parsed = parse_in_mod_hang_dump(&raw_dump);
            if let Some(loc) = parsed.pinpoint_location {
                let phase_suffix = parsed
                    .phase
                    .as_deref()
                    .map(|p| format!(" (phase : {p})"))
                    .unwrap_or_default();
                pinpoint = Some(format!("{loc}{phase_suffix}"));
            } else if let Some(phase) = parsed.phase {
                pinpoint = Some(format!("Phase interne Minecraft : {phase}"));
            }
            if let Some(st) = parsed.thread_state {
                self.record("hang", "JVM_THREAD", format!("État du Client thread : {st}"));
            }
            if let Some(mem) = parsed.memory_info {
                self.record("info", "JVM_MEM", mem);
            }
            stack_lines = parsed.stack_lines;
        }

        // 2. Repli jstack/jcmd si le mod n'a pas encore pu écrire de dump (ex: blocage avant preInit / pilote OpenGL)
        if stack_lines.is_empty() {
            if let Some(pid) = child_pid {
                if let Some(ext_stack) = try_capture_external_jstack(&self.java_path, pid) {
                    if pinpoint.is_none() {
                        pinpoint = extract_pinpoint_from_stack(&ext_stack);
                    }
                    stack_lines = ext_stack;
                }
            }
        }

        // 3. Repli logs FML / console
        let console_lines: Vec<String> = self
            .inner
            .lock()
            .map(|g| g.console_ring.iter().cloned().collect())
            .unwrap_or_default();
        let fml_tail = read_fml_log_tail(&self.game_dir, 45);
        if pinpoint.is_none() {
            let (stage_title, detail, forge_phase) = self.current_stage_summary();
            pinpoint = Some(match forge_phase {
                Some(fp) => format!("{stage_title} → {fp}"),
                None if !detail.is_empty() => format!("{stage_title} → {detail}"),
                None => stage_title,
            });
        }

        let env_lines = self.environment_lines();
        let mut steps: Vec<DiagnosticItem> = self
            .inner
            .lock()
            .map(|g| g.entries.iter().cloned().collect())
            .unwrap_or_default();

        // Ajoute un résumé diagnostique précis en fin de liste
        let loc_display = pinpoint
            .clone()
            .unwrap_or_else(|| "Étape non identifiée".to_owned());
        steps.push(DiagnosticItem {
            time: format_elapsed_tag(
                self.inner
                    .lock()
                    .map(|g| g.started_at.elapsed())
                    .unwrap_or_default(),
            ),
            level: "hang".to_owned(),
            stage: "DIAGNOSTIC".to_owned(),
            message: format!("Emplacement précis du blocage : {loc_display}"),
        });

        let mut search_corpus = stack_lines.join("\n");
        search_corpus.push('\n');
        search_corpus.push_str(&console_lines.join("\n"));
        search_corpus.push('\n');
        search_corpus.push_str(&fml_tail);
        let suspected_mod_file = crate::mods_manager::identify_suspected_crash_mod(
            &self.game_dir.to_string_lossy(),
            &search_corpus,
        );
        if let Some(ref smod) = suspected_mod_file {
            steps.push(DiagnosticItem {
                time: "+FINAL".to_owned(),
                level: "warn".to_owned(),
                stage: "MOD_SUSPECT".to_owned(),
                message: format!("Mod tiers détecté dans la pile d'appels : {smod}"),
            });
        }

        let full_text = format_diagnostic_report_text(
            "Minecraft / MashClient — Blocage « Not Responding »",
            reason,
            Some(&loc_display),
            &env_lines,
            &steps,
            &stack_lines,
            &console_lines,
            &fml_tail,
        );

        let report_path = save_diagnostic_report_file(
            &self.game_dir.join("crash-reports"),
            "mashclient-not-responding",
            &full_text,
        );

        CrashReportInfo {
            title: "Blocage détecté (Not Responding)".to_owned(),
            summary: format!(
                "Le client ne répond plus depuis {hung_secs}s ({reason}). Localisation : {loc_display}"
            ),
            details: full_text,
            exit_code: None,
            report_path,
            suspected_mod_file,
            is_not_responding: true,
            crash_location: Some(loc_display),
            environment_lines: env_lines,
            diagnostic_steps: steps,
            thread_stack_lines: stack_lines,
            can_force_kill,
        }
    }

    /// Enrichit un rapport de crash classique (`CrashReportInfo`) avec la chronologie complète,
    /// l'environnement Windows 10/11 et la localisation exacte (classe/méthode/ligne).
    pub fn enrich_crash_report(&self, mut report: CrashReportInfo) -> CrashReportInfo {
        self.record("error", "CRASH", report.summary.clone());

        if report.environment_lines.is_empty() {
            report.environment_lines = self.environment_lines();
        }
        if report.thread_stack_lines.is_empty() {
            report.thread_stack_lines = extract_stack_lines_from_text(&report.details);
        }
        if report.crash_location.is_none() {
            report.crash_location = extract_crash_location_from_text(&report.details).or_else(|| {
                let (st, det, fp) = self.current_stage_summary();
                Some(match fp {
                    Some(f) => format!("{st} → {f}"),
                    None if !det.is_empty() => format!("{st} → {det}"),
                    None => st,
                })
            });
        }
        let mut steps: Vec<DiagnosticItem> = self
            .inner
            .lock()
            .map(|g| g.entries.iter().cloned().collect())
            .unwrap_or_default();
        if let Some(ref loc) = report.crash_location {
            steps.push(DiagnosticItem {
                time: "+CRASH".to_owned(),
                level: "error".to_owned(),
                stage: "LOCALISATION".to_owned(),
                message: loc.clone(),
            });
        }
        report.summary = sanitize_diagnostic_text(&report.summary);
        report.details = sanitize_diagnostic_text(&report.details);
        for step in &mut steps {
            step.message = sanitize_diagnostic_text(&step.message);
        }
        report.diagnostic_steps = steps;
        report
    }
}

/// Détecte les jalons importants de Forge 1.8.9 / LWJGL / OpenGL / Audio / Watchdog.
pub fn classify_forge_log_milestone(line: &str) -> Option<(u8, &'static str, String)> {
    if line.contains("[PureClient-Ready] MENU_ACTIVE") {
        return Some((100, "READY", "Menu principal MashClient prêt".to_owned()));
    }
    if line.contains("[PureClient-Ready] DISPLAY_CREATED") {
        return Some((
            88,
            "LWJGL",
            "Fenêtre OpenGL/LWJGL créée — chargement des textures et mods".to_owned(),
        ));
    }
    if let Some(idx) = line.find("[MashClient-Watchdog]") {
        return Some((0, "WATCHDOG", line[idx..].chars().take(200).collect()));
    }
    if line.contains("Loading tweak class name") {
        return Some((83, "FORGE", "Chargement de FMLTweaker (Forge 1.8.9)".to_owned()));
    }
    if line.contains("Forge Mod Loader version") {
        return Some((84, "FORGE", "Initialisation de Forge Mod Loader 1.8.9".to_owned()));
    }
    if line.contains("Searching ") && line.contains("mods") {
        return Some((85, "FORGE", "Analyse du dossier mods/".to_owned()));
    }
    if line.contains("LWJGL Version:") {
        let clean = line
            .split("LWJGL Version:")
            .nth(1)
            .map(str::trim)
            .unwrap_or("2.9.4");
        return Some((87, "LWJGL", format!("LWJGL initialisé (version {clean})")));
    }
    if line.contains("Reloading ResourceManager:") {
        return Some((
            90,
            "ASSETS",
            "Chargement des packs de ressources et textures...".to_owned(),
        ));
    }
    if line.contains("Starting up SoundSystem") {
        return Some((92, "AUDIO", "Initialisation du moteur audio OpenAL...".to_owned()));
    }
    if line.contains("OpenAL initialized") {
        return Some((93, "AUDIO", "OpenAL initialisé avec succès".to_owned()));
    }
    if line.contains("Applying holder lookups") {
        return Some((94, "FORGE", "Initialisation des mods Forge (PreInit/Init)".to_owned()));
    }
    if line.contains("Created:") && line.contains("textures-atlas") {
        return Some((96, "GPU", "Atlas de textures GPU compilé".to_owned()));
    }
    if line.contains("Forge Mod Loader has successfully loaded") {
        return Some((98, "FORGE", "Tous les mods Forge 1.8.9 sont chargés".to_owned()));
    }
    if line.contains("Exception")
        || line.contains("MixinApplyError")
        || line.contains("InvalidInjectionException")
        || line.contains("Pixel format not accelerated")
        || line.contains("OutOfMemoryError")
        || line.contains("A fatal error has been detected")
    {
        return Some((0, "ERROR", line.chars().take(200).collect()));
    }
    None
}

pub fn parse_in_mod_hang_dump(text: &str) -> ParsedHangDump {
    let mut out = ParsedHangDump::default();
    let mut in_stack = false;
    for line in text.lines() {
        let t = line.trim();
        if let Some(v) = t.strip_prefix("Phase:") {
            out.phase = Some(v.trim().to_owned());
        } else if let Some(v) = t.strip_prefix("FrozenSeconds:") {
            out.frozen_secs = Some(v.trim().to_owned());
        } else if let Some(v) = t.strip_prefix("ThreadState:") {
            out.thread_state = Some(v.trim().to_owned());
        } else if let Some(v) = t.strip_prefix("PinpointLocation:") {
            let clean = v.trim();
            if !clean.is_empty() {
                out.pinpoint_location = Some(clean.to_owned());
            }
        } else if let Some(v) = t.strip_prefix("Memory:") {
            out.memory_info = Some(v.trim().to_owned());
        } else if t.starts_with("--- ") {
            in_stack = true;
            out.stack_lines.push(t.to_owned());
        } else if in_stack && !t.is_empty() && out.stack_lines.len() < 120 {
            out.stack_lines.push(line.to_owned());
        }
    }
    out
}

pub fn extract_stack_lines_from_text(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut take_next_native_frame = false;
    for line in text.lines() {
        let t = line.trim();
        if take_next_native_frame {
            take_next_native_frame = false;
            if !t.is_empty() {
                out.push(line.to_owned());
                if out.len() >= 80 {
                    break;
                }
                continue;
            }
        }
        if t.starts_with("# Problematic frame:") {
            out.push(line.to_owned());
            if t.trim_start_matches("# Problematic frame:").trim().is_empty() {
                take_next_native_frame = true;
            }
            if out.len() >= 80 {
                break;
            }
            continue;
        }
        if t.starts_with("at ")
            || t.starts_with("Caused by:")
            || t.starts_with("Description:")
            || ((t.contains("Exception") || t.contains("Error")) && t.len() < 260)
        {
            out.push(line.to_owned());
            if out.len() >= 80 {
                break;
            }
        }
    }
    out
}

pub fn extract_crash_location_from_text(text: &str) -> Option<String> {
    let mut first_exc: Option<String> = None;
    let mut first_frame: Option<String> = None;
    let mut expect_next_problematic_frame = false;
    for line in text.lines() {
        let t = line.trim();
        if expect_next_problematic_frame {
            expect_next_problematic_frame = false;
            let cleaned = t.trim_start_matches('#').trim();
            if !cleaned.is_empty() {
                return Some(format!("Crash natif JVM/Pilote : {cleaned}"));
            }
        }
        if let Some(frame) = t.strip_prefix("# Problematic frame:") {
            let f = frame.trim();
            if !f.is_empty() {
                return Some(format!("Crash natif JVM/Pilote : {f}"));
            }
            expect_next_problematic_frame = true;
            continue;
        }
        if first_exc.is_none()
            && !t.starts_with("at ")
            && (t.contains("Exception") || t.contains("Error"))
        {
            first_exc = Some(t.chars().take(140).collect());
        }
        if first_frame.is_none() && t.starts_with("at ") {
            let frame = t.trim_start_matches("at ").trim();
            if !frame.starts_with("java.") && !frame.starts_with("sun.") {
                first_frame = Some(frame.chars().take(140).collect());
            }
        }
    }
    match (first_exc, first_frame) {
        (Some(e), Some(f)) => Some(format!("{f} — {e}")),
        (None, Some(f)) => Some(f),
        (Some(e), None) => Some(e),
        (None, None) => None,
    }
}

fn extract_pinpoint_from_stack(lines: &[String]) -> Option<String> {
    let mut in_client = false;
    for line in lines {
        let t = line.trim();
        if t.starts_with("\"Client thread\"") {
            in_client = true;
            continue;
        }
        if in_client && t.starts_with('"') {
            break;
        }
        if in_client && t.starts_with("at ") {
            let frame = t.trim_start_matches("at ").trim();
            if !frame.starts_with("java.lang.Object.wait")
                && !frame.starts_with("sun.misc.Unsafe.park")
            {
                return Some(format!("Client thread → {frame}"));
            }
        }
    }
    lines
        .iter()
        .map(|l| l.trim())
        .find(|l| l.starts_with("at "))
        .map(|f| f.trim_start_matches("at ").to_owned())
}

fn try_capture_external_jstack(java_path: &Path, pid: u32) -> Option<Vec<String>> {
    let bin_dir = java_path.parent()?;
    let candidates: Vec<(PathBuf, Vec<String>)> = vec![
        (bin_dir.join("jstack.exe"), vec!["-l".to_owned(), pid.to_string()]),
        (
            bin_dir.join("jcmd.exe"),
            vec![pid.to_string(), "Thread.print".to_owned()],
        ),
        (bin_dir.join("jstack"), vec!["-l".to_owned(), pid.to_string()]),
    ];

    for (tool, args) in candidates {
        if !tool.is_file() {
            continue;
        }
        let mut cmd = Command::new(&tool);
        cmd.args(&args).stdout(Stdio::piped()).stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let Ok(mut child) = cmd.spawn() else {
            continue;
        };
        let start = Instant::now();
        let finished = loop {
            match child.try_wait() {
                Ok(Some(_)) => break true,
                Ok(None) if start.elapsed() < Duration::from_millis(3500) => {
                    std::thread::sleep(Duration::from_millis(80));
                }
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break false;
                }
            }
        };
        if !finished {
            continue;
        }
        if let Some(mut stdout) = child.stdout.take() {
            let mut raw = String::new();
            let _ = stdout.take(64 * 1024).read_to_string(&mut raw);
            let lines: Vec<String> = raw.lines().take(120).map(ToOwned::to_owned).collect();
            if !lines.is_empty() {
                return Some(lines);
            }
        }
    }
    None
}

fn read_fml_log_tail(game_dir: &Path, max_lines: usize) -> String {
    for rel in ["fml-client-latest.log", "latest.log"] {
        let p = game_dir.join("logs").join(rel);
        let Ok(mut f) = File::open(&p) else {
            continue;
        };
        let Ok(len) = f.metadata().map(|m| m.len()) else {
            continue;
        };
        let max_bytes = 20_480u64;
        if len > max_bytes {
            let _ = f.seek(SeekFrom::Start(len - max_bytes));
        }
        let mut buf = Vec::new();
        if f.read_to_end(&mut buf).is_ok() {
            let text = String::from_utf8_lossy(&buf);
            let lines: Vec<&str> = text.lines().collect();
            let start = lines.len().saturating_sub(max_lines);
            if !lines.is_empty() {
                return lines[start..].join("\n");
            }
        }
    }
    String::new()
}

/// Anonymise automatiquement un rapport de diagnostic avant affichage, sauvegarde ou copie :
/// - Masque le nom d'utilisateur dans les chemins (`C:\Users\<USER>\`, `/home/<USER>/`, `/Users/<USER>/`)
/// - Supprime les jetons d'accès (`--accessToken`, `Bearer`, `github_pat_`, `ghp_`, `gho_`, `ghu_`, `ghs_`)
/// - Retire la section `Environment Variables:` des journaux `hs_err_pid*.log`
pub fn sanitize_diagnostic_text(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut skip_env_section = false;
    for (idx, line) in raw.lines().enumerate() {
        let trimmed = line.trim();
        if trimmed.eq_ignore_ascii_case("Environment Variables:") {
            skip_env_section = true;
            if idx > 0 {
                out.push('\n');
            }
            out.push_str("Environment Variables: [REDACTED FOR PRIVACY]");
            continue;
        }
        if skip_env_section {
            if trimmed.is_empty()
                || trimmed.starts_with("---")
                || trimmed.starts_with("===")
                || (trimmed.ends_with(':') && !trimmed.contains('='))
            {
                skip_env_section = false;
            } else {
                continue;
            }
        }
        if idx > 0 {
            out.push('\n');
        }
        out.push_str(&sanitize_diagnostic_line(line));
    }
    if raw.ends_with('\n') {
        out.push('\n');
    }
    out
}

fn sanitize_diagnostic_line(line: &str) -> String {
    let mut s = redact_user_paths(line);
    s = redact_prefixed_secret(&s, "--accessToken ");
    s = redact_prefixed_secret(&s, "--accessToken=");
    s = redact_prefixed_secret(&s, "Bearer ");
    for pat in ["github_pat_", "ghp_", "gho_", "ghu_", "ghs_"] {
        s = redact_token_prefix(&s, pat);
    }
    s
}

fn redact_user_paths(input: &str) -> String {
    let mut s = input.to_owned();
    for marker in [r":\Users\", ":/Users/", "/home/", "/Users/"] {
        let mut cursor = 0usize;
        while cursor < s.len() {
            let lower_slice = s[cursor..].to_ascii_lowercase();
            let marker_low = marker.to_ascii_lowercase();
            let Some(rel) = lower_slice.find(&marker_low) else {
                break;
            };
            let start_user = cursor + rel + marker.len();
            if start_user >= s.len() {
                break;
            }
            let end_user = s[start_user..]
                .find(|c: char| matches!(c, '\\' | '/' | ' ' | '"' | '\'' | ')' | ']' | ',' | ';' | '\t'))
                .map(|off| start_user + off)
                .unwrap_or(s.len());
            if end_user > start_user && &s[start_user..end_user] != "<USER>" {
                s.replace_range(start_user..end_user, "<USER>");
                cursor = start_user + "<USER>".len();
            } else if end_user > start_user {
                cursor = end_user;
            } else {
                cursor = start_user + s[start_user..].chars().next().map_or(1, char::len_utf8);
            }
        }
    }
    s
}

fn redact_prefixed_secret(input: &str, prefix: &str) -> String {
    let mut s = input.to_owned();
    let prefix_low = prefix.to_ascii_lowercase();
    let mut cursor = 0usize;
    while cursor < s.len() {
        let lower_slice = s[cursor..].to_ascii_lowercase();
        let Some(rel) = lower_slice.find(&prefix_low) else {
            break;
        };
        let start_tok = cursor + rel + prefix.len();
        if start_tok >= s.len() {
            break;
        }
        let end_tok = s[start_tok..]
            .find(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | ')' | ']' | ',' | ';'))
            .map(|off| start_tok + off)
            .unwrap_or(s.len());
        if end_tok > start_tok {
            s.replace_range(start_tok..end_tok, "[REDACTED]");
            cursor = start_tok + "[REDACTED]".len();
        } else {
            cursor = start_tok + s[start_tok..].chars().next().map_or(1, char::len_utf8);
        }
    }
    s
}

fn redact_token_prefix(input: &str, prefix: &str) -> String {
    let mut s = input.to_owned();
    let mut cursor = 0usize;
    while cursor < s.len() {
        let Some(rel) = s[cursor..].find(prefix) else {
            break;
        };
        let start = cursor + rel;
        let end = s[start..]
            .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
            .map(|off| start + off)
            .unwrap_or(s.len());
        if end > start + prefix.len() {
            s.replace_range(start..end, "[REDACTED_TOKEN]");
            cursor = start + "[REDACTED_TOKEN]".len();
        } else {
            cursor = start + prefix.len();
        }
    }
    s
}

#[allow(clippy::too_many_arguments)]
fn format_diagnostic_report_text(
    header: &str,
    summary: &str,
    location: Option<&str>,
    env_lines: &[String],
    steps: &[DiagnosticItem],
    stack_lines: &[String],
    console_lines: &[String],
    fml_tail: &str,
) -> String {
    let mut out = String::with_capacity(4096);
    out.push_str("=================================================================\n");
    out.push_str(&format!("  {header}\n"));
    out.push_str("=================================================================\n");
    out.push_str(&format!("Résumé       : {summary}\n"));
    if let Some(loc) = location {
        out.push_str(&format!("Emplacement  : {loc}\n"));
    }
    out.push('\n');

    out.push_str("--- 1. ENVIRONNEMENT WINDOWS 10/11 & MATÉRIEL ---\n");
    for line in env_lines {
        out.push_str("  • ");
        out.push_str(line);
        out.push('\n');
    }
    out.push('\n');

    out.push_str("--- 2. CHRONOLOGIE DÉTAILLÉE ÉTAPE PAR ÉTAPE ---\n");
    for (idx, item) in steps.iter().enumerate() {
        out.push_str(&format!(
            "  {:03}. [{}] [{}] [{}] {}\n",
            idx + 1,
            item.time,
            item.level.to_ascii_uppercase(),
            item.stage,
            item.message
        ));
    }
    out.push('\n');

    if !stack_lines.is_empty() {
        out.push_str("--- 3. PILE D'APPELS EXACTE (THREAD DUMP JVM) ---\n");
        for line in stack_lines {
            out.push_str(line);
            out.push('\n');
        }
        out.push('\n');
    }

    if !console_lines.is_empty() {
        out.push_str("--- 4. DERNIÈRES LIGNES CONSOLE MINECRAFT ---\n");
        let start = console_lines.len().saturating_sub(60);
        for line in &console_lines[start..] {
            out.push_str(line);
            out.push('\n');
        }
        out.push('\n');
    }

    if !fml_tail.trim().is_empty() {
        out.push_str("--- 5. EXTRAIT LOG FORGE (fml-client-latest.log) ---\n");
        out.push_str(fml_tail);
        out.push('\n');
    }

    sanitize_diagnostic_text(&out)
}

pub fn save_diagnostic_report_file(dir: &Path, prefix: &str, content: &str) -> Option<String> {
    let _ = std::fs::create_dir_all(dir);
    let ts = epoch_secs_now();
    let path = dir.join(format!("{prefix}-{ts}.txt"));
    let clean = sanitize_diagnostic_text(content);
    std::fs::write(&path, clean).ok()?;
    Some(path.to_string_lossy().to_string())
}

/// Watchdog surveillant en continu la réactivité du thread UI Win32 de `MashClient.exe`.
/// Si le thread UI reste bloqué plus de 6 secondes ("Not Responding"), un rapport détaillé
/// identifiant l'opération exacte en cours est immédiatement écrit sur disque et remonté à l'utilisateur.
pub struct UiHangWatchdog {
    last_heartbeat_ms: Arc<AtomicU64>,
    stopped: Arc<AtomicBool>,
    current_op: Arc<Mutex<String>>,
    breadcrumbs: Arc<Mutex<VecDeque<DiagnosticItem>>>,
    started_at: Instant,
}

impl UiHangWatchdog {
    pub fn start(proxy: EventLoopProxy<AppEvent>) -> Self {
        let now_ms = epoch_ms_now();
        let last_heartbeat_ms = Arc::new(AtomicU64::new(now_ms));
        let stopped = Arc::new(AtomicBool::new(false));
        let current_op = Arc::new(Mutex::new("Initialisation du Launcher MashClient".to_owned()));
        let breadcrumbs: Arc<Mutex<VecDeque<DiagnosticItem>>> =
            Arc::new(Mutex::new(VecDeque::with_capacity(80)));
        let started_at = Instant::now();

        let hb_watch = Arc::clone(&last_heartbeat_ms);
        let stop_watch = Arc::clone(&stopped);
        let op_watch = Arc::clone(&current_op);
        let bc_watch = Arc::clone(&breadcrumbs);

        std::thread::spawn(move || {
            let mut reported_for_stall = false;
            while !stop_watch.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(1000));
                if stop_watch.load(Ordering::Relaxed) {
                    break;
                }
                let last = hb_watch.load(Ordering::Relaxed);
                let now = epoch_ms_now();
                let silent_ms = now.saturating_sub(last);
                if silent_ms >= UI_HANG_THRESHOLD_MS {
                    if !reported_for_stall {
                        reported_for_stall = true;
                        let op_name = op_watch
                            .lock()
                            .map(|g| g.clone())
                            .unwrap_or_else(|_| "Opération UI inconnue".to_owned());
                        let steps: Vec<DiagnosticItem> = bc_watch
                            .lock()
                            .map(|g| g.iter().cloned().collect())
                            .unwrap_or_default();
                        let report = build_launcher_ui_hang_report(
                            &op_name,
                            silent_ms / 1000,
                            steps,
                        );
                        persist_pending_ui_hang_report(&report);
                        let _ = proxy.send_event(AppEvent::LauncherHangRecovered(report));
                    }
                } else {
                    reported_for_stall = false;
                }
            }
        });

        Self {
            last_heartbeat_ms,
            stopped,
            current_op,
            breadcrumbs,
            started_at,
        }
    }

    pub fn heartbeat(&self) {
        self.last_heartbeat_ms
            .store(epoch_ms_now(), Ordering::Relaxed);
    }

    pub fn set_op(&self, op: impl Into<String>) {
        let op_str = op.into();
        self.heartbeat();
        if let Ok(mut g) = self.current_op.lock() {
            *g = op_str.clone();
        }
        if let Ok(mut bc) = self.breadcrumbs.lock() {
            if bc.len() >= 80 {
                bc.pop_front();
            }
            bc.push_back(DiagnosticItem {
                time: format_elapsed_tag(self.started_at.elapsed()),
                level: "info".to_owned(),
                stage: "LAUNCHER_UI".to_owned(),
                message: op_str,
            });
        }
    }

    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Relaxed);
    }
}

fn pending_ui_hang_marker_path() -> PathBuf {
    app_storage_dir().join(".pending_ui_hang.json")
}

fn persist_pending_ui_hang_report(report: &CrashReportInfo) {
    let path = pending_ui_hang_marker_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(json) = serde_json::to_string_pretty(report) {
        let _ = std::fs::write(path, json);
    }
}

/// Récupère et consomme un éventuel rapport « Not Responding » enregistré juste avant un arrêt forcé précédent.
pub fn take_previous_session_ui_hang() -> Option<CrashReportInfo> {
    let path = pending_ui_hang_marker_path();
    let raw = std::fs::read_to_string(&path).ok()?;
    let _ = std::fs::remove_file(&path);
    serde_json::from_str::<CrashReportInfo>(&raw).ok()
}

pub fn clear_pending_ui_hang_marker() {
    let _ = std::fs::remove_file(pending_ui_hang_marker_path());
}

fn build_launcher_ui_hang_report(
    op_name: &str,
    silent_secs: u64,
    mut steps: Vec<DiagnosticItem>,
) -> CrashReportInfo {
    let hw = collect_system_hardware_info();
    let env_lines = vec![
        format!("Système : {}", hw.os_label),
        format!("Carte graphique : {}", hw.gpu_name),
        format!(
            "Mémoire RAM : {} Mo libres / {} Mo total",
            hw.avail_ram_mb, hw.total_ram_mb
        ),
        format!(
            "DPI Windows : {}% | Edge WebView2 détecté : {}",
            hw.dpi_scale_pct,
            if hw.webview2_detected { "Oui" } else { "Non" }
        ),
    ];
    let loc = format!("Thread UI MashClient.exe bloqué dans : {op_name}");
    steps.push(DiagnosticItem {
        time: format!("+{silent_secs}s"),
        level: "hang".to_owned(),
        stage: "UI_WATCHDOG".to_owned(),
        message: format!(
            "Fenêtre MashClient figée (Not Responding) pendant {silent_secs}s sur « {op_name} »"
        ),
    });

    let details = format_diagnostic_report_text(
        "MashClient.exe — Blocage de l'interface (Not Responding)",
        &format!("Le thread principal du Launcher n'a pas répondu pendant {silent_secs}s."),
        Some(&loc),
        &env_lines,
        &steps,
        &[],
        &[],
        "",
    );

    let report_path = save_diagnostic_report_file(
        &app_storage_dir().join("crash-reports"),
        "launcher-not-responding",
        &details,
    );

    CrashReportInfo {
        title: "MashClient (Not Responding) détecté".to_owned(),
        summary: format!(
            "L'interface MashClient s'est figée pendant {silent_secs}s lors de l'étape : {op_name}."
        ),
        details,
        exit_code: None,
        report_path,
        suspected_mod_file: None,
        is_not_responding: true,
        crash_location: Some(loc),
        environment_lines: env_lines,
        diagnostic_steps: steps,
        thread_stack_lines: Vec::new(),
        can_force_kill: false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_classify_forge_log_milestones() {
        let m1 = classify_forge_log_milestone(
            "[12:00:01] [Client thread/INFO]: LWJGL Version: 2.9.4",
        )
        .expect("doit reconnaître LWJGL");
        assert_eq!(m1.0, 87);
        assert_eq!(m1.1, "LWJGL");

        let m2 = classify_forge_log_milestone(
            "[12:00:04] [Client thread/INFO]: Reloading ResourceManager: Default, FMLFileResourcePack:Forge Mod Loader",
        )
        .expect("doit reconnaître ResourceManager");
        assert_eq!(m2.0, 90);
        assert_eq!(m2.1, "ASSETS");

        let m3 = classify_forge_log_milestone("[PureClient-Ready] MENU_ACTIVE")
            .expect("doit reconnaître MENU_ACTIVE");
        assert_eq!(m3.0, 100);
    }

    #[test]
    fn test_parse_in_mod_hang_dump_extracts_exact_location() {
        let sample = "\
Phase: Reloading ResourceManager
FrozenSeconds: 11
ThreadState: RUNNABLE
PinpointLocation: net.minecraft.client.renderer.texture.TextureMap.loadTextureAtlas(TextureMap.java:184)
Memory: Used 912 MB / Max 3072 MB
--- Client Thread Stack ---
\tat net.minecraft.client.renderer.texture.TextureMap.loadTextureAtlas(TextureMap.java:184)
\tat net.minecraft.client.Minecraft.startGame(Minecraft.java:512)
";
        let parsed = parse_in_mod_hang_dump(sample);
        assert_eq!(parsed.phase.as_deref(), Some("Reloading ResourceManager"));
        assert_eq!(parsed.frozen_secs.as_deref(), Some("11"));
        assert_eq!(parsed.thread_state.as_deref(), Some("RUNNABLE"));
        assert_eq!(
            parsed.pinpoint_location.as_deref(),
            Some("net.minecraft.client.renderer.texture.TextureMap.loadTextureAtlas(TextureMap.java:184)")
        );
        assert!(parsed.stack_lines.len() >= 3);
    }

    #[test]
    fn test_extract_crash_location_from_java_and_native_reports() {
        let java_rep = "java.lang.NoClassDefFoundError: com/foo/Bar\n\tat java.lang.Class.forName(Class.java:100)\n\tat com.example.mod.MyMod.init(MyMod.java:42)\n";
        let loc = extract_crash_location_from_text(java_rep).expect("doit trouver la ligne exacte");
        assert!(loc.contains("com.example.mod.MyMod.init(MyMod.java:42)"));

        let native_rep = "# A fatal error has been detected\n# Problematic frame:\n# C  [nvoglv64.dll+0x12345]\n";
        let nloc = extract_crash_location_from_text(native_rep).expect("doit trouver le frame natif");
        assert!(nloc.contains("nvoglv64.dll"));
    }

    #[test]
    fn test_diagnostic_timeline_builds_detailed_hang_report() {
        let tmp = std::env::temp_dir().join("mashclient_diag_timeline_test");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(tmp.join("config")).unwrap();
        std::fs::write(
            tmp.join("config").join(".purefps_hang_dump.txt"),
            "Phase: startGame:createDisplay\nFrozenSeconds: 9\nThreadState: RUNNABLE\nPinpointLocation: org.lwjgl.opengl.WindowsDisplay.nCreateWindow(Native Method)\n--- Client Thread Stack ---\n\tat org.lwjgl.opengl.WindowsDisplay.nCreateWindow(Native Method)\n",
        )
        .unwrap();

        let tl = DiagnosticTimeline::new(
            tmp.clone(),
            PathBuf::from("javaw.exe"),
            3072,
            false,
        );
        tl.record("ok", "DOWNLOAD", "53 bibliothèques vérifiées");
        tl.observe_game_log_line("[Client thread/INFO]: LWJGL Version: 2.9.4");

        let report = tl.build_not_responding_report(None, "Fenêtre LWJGL figée", 9, true);
        assert!(report.is_not_responding);
        assert!(report.can_force_kill);
        assert!(report
            .crash_location
            .as_deref()
            .unwrap_or("")
            .contains("WindowsDisplay.nCreateWindow"));
        assert!(!report.diagnostic_steps.is_empty());
        assert!(!report.environment_lines.is_empty());
        assert!(!report.thread_stack_lines.is_empty());
        assert!(report.report_path.is_some());

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn test_sanitize_diagnostic_text_redacts_paths_tokens_and_env() {
        let raw = "Runtime Java : C:\\Users\\Alice\\AppData\\Roaming\\.PureFPS\\javaw.exe\n\
                   Linux path : /home/bob/minecraft\n\
                   Args : --accessToken eyJhbGciOiJIUzI1NiJ9.secret --userType msa\n\
                   Auth : Bearer super_secret_token github_pat_11ABCDEF123456\n\
                   Environment Variables:\n\
                   USERNAME=Alice\n\
                   SECRET_KEY=topsecret\n\
                   \n\
                   --- NEXT SECTION ---\n\
                   Système : Windows 11\n";
        let clean = sanitize_diagnostic_text(raw);
        assert!(!clean.contains("Alice"));
        assert!(!clean.contains("bob"));
        assert!(!clean.contains("eyJhbGciOiJIUzI1NiJ9"));
        assert!(!clean.contains("super_secret_token"));
        assert!(!clean.contains("github_pat_11ABCDEF123456"));
        assert!(!clean.contains("SECRET_KEY=topsecret"));
        assert!(clean.contains(r"C:\Users\<USER>\AppData"));
        assert!(clean.contains("/home/<USER>/minecraft"));
        assert!(clean.contains("--accessToken [REDACTED]"));
        assert!(clean.contains("Environment Variables: [REDACTED FOR PRIVACY]"));
        assert!(clean.contains("--- NEXT SECTION ---"));
    }
}
