//! RSXM power micro-kernel — the battery-policy leader.
//!
//! Merges the strongest ideas from the two independent implementations:
//!
//! * **bettbox / FlClash battery playbook** (policy, never self-owned
//!   timers):
//!   1. stretch latency health checks while the screen is off (radio
//!      wakeups in a pocket are pure waste);
//!   2. clamp TCP keepalive so a short idle value cannot keep the cellular
//!      link promoted for nothing;
//!   3. boot auto-start is opt-in, never a default.
//! * **state machine → directives** (the "boss/leaders" contract):
//!   Active / ScreenOff / DeepPause / Charging map to Run / Throttle /
//!   DeepPause directives the Conductor forwards to the other leaders, and
//!   `desired_modules` lets the user run only the features switched on.
//!
//! The Conductor only learns outcomes via [`ReportKind::Power`] lines; the
//! slice contents never leave this crate.

use std::sync::{Arc, Mutex};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde::Deserialize;

/// Health-check interval is multiplied by this while the screen is off.
const SCREEN_OFF_MULTIPLIER: u64 = 6;
/// …but never below this, so a 1-minute setting does not become 6 minutes
/// of still-frequent background radio wakeups.
const SCREEN_OFF_FLOOR_MINUTES: u64 = 30;
/// Upper clamp for TCP keepalive idle (seconds); larger values are almost
/// certainly misconfigured.
const KEEPALIVE_MAX_SECONDS: u64 = 3600;
/// Sanity ceiling for a configured health-check interval (12 h).
const HEALTH_CHECK_MAX_MINUTES: u64 = 720;
/// Default deep-pause grace window after screen-off (5 minutes).
const DEFAULT_DEEP_PAUSE_AFTER_MS: i64 = 5 * 60 * 1000;
/// Shortest sane grace window (15 s).
const DEEP_PAUSE_MIN_MS: i64 = 15 * 1000;

/// Device power state as observed by the host (Android forwards
/// screen/battery broadcasts into here).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PowerState {
    /// Screen on, user present.
    Active,
    /// Screen off, still within the grace window.
    ScreenOff,
    /// Screen off past the grace window: deep pause permitted.
    DeepPause,
    /// Charging: treat as active even with the screen off.
    Charging,
}

/// What the host should do to other modules right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Directive {
    /// Normal operation.
    Run,
    /// Throttle: no probes, no rule-set refresh, status interval widened.
    Throttle,
    /// Deep pause: caches may trim, timers stop, only the tunnel lives.
    DeepPause,
}

impl PowerState {
    pub fn directive(self) -> Directive {
        match self {
            PowerState::Active | PowerState::Charging => Directive::Run,
            PowerState::ScreenOff => Directive::Throttle,
            PowerState::DeepPause => Directive::DeepPause,
        }
    }
}

/// Conservative, quiet defaults: no periodic probes, OS keepalive, no boot
/// auto-start.
#[derive(Debug, Clone, PartialEq, Eq)]
struct PowerPolicy {
    /// Health-check cadence in minutes; `0` disables periodic probes.
    health_check_minutes: u64,
    /// TCP keepalive idle in seconds; `0` = OS default.
    keepalive_idle_seconds: u64,
    boot_auto_start: bool,
    /// Grace window before screen-off becomes deep pause.
    deep_pause_after_ms: i64,
    /// Features the user enabled; unlisted optional leaders may be stopped.
    enabled_features: Vec<String>,
}

impl Default for PowerPolicy {
    fn default() -> Self {
        Self {
            health_check_minutes: 0,
            keepalive_idle_seconds: 0,
            boot_auto_start: false,
            deep_pause_after_ms: DEFAULT_DEEP_PAUSE_AFTER_MS,
            enabled_features: Vec::new(),
        }
    }
}

impl PowerPolicy {
    fn sanitize(mut self) -> Self {
        if self.health_check_minutes > HEALTH_CHECK_MAX_MINUTES {
            self.health_check_minutes = HEALTH_CHECK_MAX_MINUTES;
        }
        if self.keepalive_idle_seconds > KEEPALIVE_MAX_SECONDS {
            self.keepalive_idle_seconds = KEEPALIVE_MAX_SECONDS;
        }
        if self.deep_pause_after_ms < DEEP_PAUSE_MIN_MS {
            self.deep_pause_after_ms = DEFAULT_DEEP_PAUSE_AFTER_MS;
        }
        self
    }
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
#[allow(non_snake_case)]
struct PowerSlice {
    #[serde(default)]
    healthCheckIntervalMinutes: u64,
    #[serde(default)]
    tcpKeepAliveIdleSeconds: u64,
    #[serde(default)]
    bootAutoStart: bool,
    #[serde(default)]
    deepPauseAfterMs: Option<i64>,
    #[serde(default)]
    enabledFeatures: Vec<String>,
}

#[derive(Debug)]
struct Inner {
    state: PowerState,
    screen_on: bool,
    charging: bool,
    screen_off_at: Option<i64>,
    policy: PowerPolicy,
    /// Last time a health probe was permitted.
    last_probe_ms: Option<i64>,
    /// Directives the power kernel has published since start.
    directives_issued: u64,
}

/// The power leader.
pub struct PowerModule {
    inner: Mutex<Inner>,
    /// Wall-clock in ms, injectable for tests.
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    reporter: Mutex<Option<ModuleReporter>>,
}

fn system_clock() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl PowerModule {
    pub fn new() -> Self {
        Self::with_clock(Arc::new(system_clock))
    }

    /// Test constructor with a custom clock.
    pub fn with_clock(clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> Self {
        Self {
            inner: Mutex::new(Inner {
                // Assume the screen is on at boot; the first real state
                // event corrects this immediately.
                state: PowerState::Active,
                screen_on: true,
                charging: false,
                screen_off_at: None,
                policy: PowerPolicy::default(),
                last_probe_ms: None,
                directives_issued: 0,
            }),
            clock,
            reporter: Mutex::new(None),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn report(&self, kind: ReportKind, message: impl Into<String>) {
        if let Some(reporter) = self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
        {
            reporter.send(kind, message);
        }
    }

    /// Host reports screen/charging state and receives the directive the
    /// other leaders must follow now.
    pub fn record_screen(&self, on: bool, charging: bool) -> Directive {
        let mut inner = self.lock();
        let now = (self.clock)();
        let screen_edge = inner.screen_on != on;
        inner.screen_on = on;
        inner.charging = charging;
        if charging {
            inner.state = PowerState::Charging;
            inner.screen_off_at = None;
        } else if on {
            inner.state = PowerState::Active;
            inner.screen_off_at = None;
        } else {
            inner.screen_off_at.get_or_insert(now);
            inner.state = PowerState::ScreenOff;
        }
        let directive = self.evaluate_locked(&inner, now);
        if directive != inner.state.directive() {
            inner.state = state_for(directive, inner.charging);
        }
        inner.directives_issued = inner.directives_issued.saturating_add(1);
        drop(inner);
        if screen_edge {
            let interval = self
                .effective_health_check_ms()
                .map(|ms| format!("{ms}ms"))
                .unwrap_or_else(|| "off".into());
            self.report(
                ReportKind::Power,
                format!(
                    "screen {} -> health checks {}",
                    if on { "on" } else { "off" },
                    interval
                ),
            );
        }
        directive
    }

    /// Feeds a screen-state transition from the Android side
    /// (`ACTION_SCREEN_ON/OFF`); charging state is left unchanged.
    pub fn on_screen_state(&self, on: bool) {
        let charging = self.lock().charging;
        self.record_screen(on, charging);
    }

    /// Re-evaluates without a new event (called by the host's timer).
    pub fn tick(&self) -> Directive {
        let mut inner = self.lock();
        let now = (self.clock)();
        if let Some(off_at) = inner.screen_off_at {
            if inner.state != PowerState::Charging
                && now - off_at >= inner.policy.deep_pause_after_ms
            {
                inner.state = PowerState::DeepPause;
            }
        }
        let d = inner.state.directive();
        inner.directives_issued = inner.directives_issued.saturating_add(1);
        d
    }

    pub fn current_state(&self) -> PowerState {
        self.lock().state
    }

    pub fn current_directive(&self) -> Directive {
        self.current_state().directive()
    }

    /// Modules that should be running given the user's enabled features.
    pub fn desired_modules(&self) -> Vec<&'static str> {
        let inner = self.lock();
        let on = |name: &str| inner.policy.enabled_features.iter().any(|f| f == name);
        // The tunnel (tun + dialer) and the power leader itself are the
        // product: always desired while the app runs. Optional features
        // gate their own kernels ("run only what is on").
        let mut wanted = vec!["rsxm-tun", "rsxm-dialer", "rsxm-power"];
        if on("dns") {
            wanted.push("rsxm-dns");
        }
        if on("rules") {
            wanted.push("rsxm-rules");
        }
        if on("stats") {
            wanted.push("rsxm-stats");
        }
        if on("security") {
            wanted.push("rsxm-security");
        }
        wanted
    }

    /// Directives published since start (observability/testing).
    pub fn directives_issued(&self) -> u64 {
        self.lock().directives_issued
    }

    fn evaluate_locked(&self, inner: &Inner, now: i64) -> Directive {
        if inner.state == PowerState::ScreenOff {
            if let Some(off_at) = inner.screen_off_at {
                if now - off_at >= inner.policy.deep_pause_after_ms {
                    return Directive::DeepPause;
                }
            }
            return Directive::Throttle;
        }
        inner.state.directive()
    }

    pub fn screen_is_on(&self) -> bool {
        self.lock().screen_on
    }

    /// The probe cadence right now, after applying the screen-off stretch.
    /// Charging counts as active; `None` means periodic probes are off.
    pub fn effective_health_check_ms(&self) -> Option<u64> {
        let inner = self.lock();
        let minutes = inner.policy.health_check_minutes;
        if minutes == 0 {
            return None;
        }
        let active = matches!(inner.state, PowerState::Active | PowerState::Charging);
        let effective = if active {
            minutes
        } else {
            minutes
                .saturating_mul(SCREEN_OFF_MULTIPLIER)
                .max(SCREEN_OFF_FLOOR_MINUTES)
        };
        Some(effective.saturating_mul(60_000))
    }

    /// The sanitised TCP keepalive idle (0 = OS default).
    pub fn keepalive_idle_seconds(&self) -> u64 {
        self.lock().policy.keepalive_idle_seconds
    }

    pub fn boot_auto_start(&self) -> bool {
        self.lock().policy.boot_auto_start
    }

    /// Gate for the health-probe scheduler: returns true (and stamps the
    /// clock) exactly when the effective interval has elapsed. When probes
    /// are disabled it always returns false — the VPN service then skips
    /// its periodic wakeup entirely, the biggest battery win available.
    pub fn should_probe(&self, now_ms: i64) -> bool {
        let Some(interval_ms) = self.effective_health_check_ms() else {
            return false;
        };
        let mut inner = self.lock();
        let due = inner
            .last_probe_ms
            .is_none_or(|t| now_ms - t >= interval_ms as i64);
        if due {
            inner.last_probe_ms = Some(now_ms);
        }
        due
    }
}

fn state_for(d: Directive, charging: bool) -> PowerState {
    match d {
        Directive::Run => {
            if charging {
                PowerState::Charging
            } else {
                PowerState::Active
            }
        }
        Directive::Throttle => PowerState::ScreenOff,
        Directive::DeepPause => PowerState::DeepPause,
    }
}

impl Default for PowerModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for PowerModule {
    fn name(&self) -> &'static str {
        "rsxm-power"
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            // No slice is not an error: quiet defaults apply.
            self.lock().policy = PowerPolicy::default();
            return Ok(());
        };
        let parsed: PowerSlice =
            serde_json::from_value(slice.value.as_ref().clone()).map_err(|e| e.to_string())?;
        let mut policy = PowerPolicy {
            health_check_minutes: parsed.healthCheckIntervalMinutes,
            keepalive_idle_seconds: parsed.tcpKeepAliveIdleSeconds,
            boot_auto_start: parsed.bootAutoStart,
            deep_pause_after_ms: parsed
                .deepPauseAfterMs
                .unwrap_or(DEFAULT_DEEP_PAUSE_AFTER_MS),
            enabled_features: parsed.enabledFeatures,
        };
        policy = policy.sanitize();
        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "power policy: probes={}min, keepalive={}s, boot={}, deep-pause={}ms, features={}",
                policy.health_check_minutes,
                policy.keepalive_idle_seconds,
                policy.boot_auto_start,
                policy.deep_pause_after_ms,
                policy.enabled_features.join(",")
            ),
        );
        self.lock().policy = policy;
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        // The meterless policy leader is always "up"; its real output is
        // the directives and gating decisions the host queries.
        Health::Up
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsxm_core::{Conductor, ConfigEnvelope};
    use serde_json::json;

    fn clock(now: Arc<std::sync::Mutex<i64>>) -> Arc<dyn Fn() -> i64 + Send + Sync> {
        Arc::new(move || *now.lock().unwrap())
    }

    #[test]
    fn no_slice_means_quiet_defaults() {
        let module = PowerModule::new();
        module.configure(None).unwrap();
        assert_eq!(module.effective_health_check_ms(), None);
        assert!(!module.should_probe(0));
        assert_eq!(module.keepalive_idle_seconds(), 0);
        assert!(!module.boot_auto_start());
        assert_eq!(module.health(), Health::Up);
    }

    #[test]
    fn screen_off_stretches_interval_to_floor() {
        let module = PowerModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-power",
                json!({ "healthCheckIntervalMinutes": 5 }),
            )))
            .unwrap();
        assert_eq!(module.effective_health_check_ms(), Some(5 * 60_000));
        module.on_screen_state(false);
        // 5*6 = 30 minutes — exactly the floor.
        assert_eq!(module.effective_health_check_ms(), Some(30 * 60_000));
        module.on_screen_state(true);
        assert_eq!(module.effective_health_check_ms(), Some(5 * 60_000));
    }

    #[test]
    fn screen_off_never_drops_below_floor() {
        let module = PowerModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-power",
                json!({ "healthCheckIntervalMinutes": 1 }),
            )))
            .unwrap();
        module.on_screen_state(false);
        assert_eq!(module.effective_health_check_ms(), Some(30 * 60_000));
    }

    #[test]
    fn probe_gate_respects_interval_and_disabled_state() {
        let module = PowerModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-power",
                json!({ "healthCheckIntervalMinutes": 1 }),
            )))
            .unwrap();
        assert!(module.should_probe(0), "first probe is always due");
        assert!(!module.should_probe(30_000), "30s < 1min");
        assert!(module.should_probe(60_000));

        // Screen off stretches the cadence to the 30-minute floor.
        module.on_screen_state(false);
        assert!(!module.should_probe(61_000));
        assert!(module.should_probe(60_000 + 30 * 60_000));
    }

    #[test]
    fn out_of_range_values_are_clamped() {
        let module = PowerModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-power",
                json!({
                    "healthCheckIntervalMinutes": 100_000,
                    "tcpKeepAliveIdleSeconds": 999_999,
                    "deepPauseAfterMs": 1
                }),
            )))
            .unwrap();
        assert_eq!(module.effective_health_check_ms(), Some(720 * 60_000));
        assert_eq!(module.keepalive_idle_seconds(), 3600);
        // Absurd grace window resets to the 5-minute default.
        module.record_screen(false, false);
        assert_eq!(module.tick(), Directive::Throttle);
    }

    #[test]
    fn screen_edges_report_once_and_register_through_conductor() {
        let module = Arc::new(PowerModule::new());
        let mut conductor = Conductor::new();
        conductor.register(module.clone() as Arc<dyn Module>);
        conductor.distribute(ConfigEnvelope::new(vec![ConfigSlice::new(
            "rsxm-power",
            json!({ "healthCheckIntervalMinutes": 10 }),
        )]));
        module.on_screen_state(false);
        module.on_screen_state(false); // duplicate edge: nothing
        module.on_screen_state(true);
        let power_reports: Vec<_> = conductor
            .drain_reports()
            .into_iter()
            .filter(|r| r.kind == ReportKind::Power)
            .collect();
        assert_eq!(power_reports.len(), 2, "only real screen edges report");
        assert!(power_reports[0].message.contains("screen off"));
        assert!(power_reports[1].message.contains("screen on"));
    }

    #[test]
    fn screen_off_then_deep_pause_after_window() {
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let m = PowerModule::with_clock(clock(now.clone()));
        assert_eq!(m.record_screen(false, false), Directive::Throttle);
        *now.lock().unwrap() += 100; // inside grace window
        assert_eq!(m.tick(), Directive::Throttle);
        *now.lock().unwrap() += 6 * 60 * 1000; // past default 5-minute window
        assert_eq!(m.tick(), Directive::DeepPause);
    }

    #[test]
    fn screen_on_resets_to_active() {
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let m = PowerModule::with_clock(clock(now.clone()));
        m.record_screen(false, false);
        *now.lock().unwrap() += 10 * 60 * 1000; // would be deep pause…
        let d = m.record_screen(true, false);
        assert_eq!(d, Directive::Run);
        assert_eq!(m.tick(), Directive::Run);
    }

    #[test]
    fn charging_treats_as_active_even_with_screen_off() {
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let m = PowerModule::with_clock(clock(now.clone()));
        assert_eq!(m.record_screen(false, true), Directive::Run);
        *now.lock().unwrap() += 10 * 60 * 1000;
        assert_eq!(m.tick(), Directive::Run);
        assert_eq!(m.current_state(), PowerState::Charging);
    }

    #[test]
    fn feature_list_gates_optional_kernels() {
        let m = PowerModule::new();
        m.configure(Some(&ConfigSlice::new(
            "rsxm-power",
            json!({ "enabledFeatures": ["dns"] }),
        )))
        .unwrap();
        let wanted = m.desired_modules();
        assert!(wanted.contains(&"rsxm-dns"));
        assert!(!wanted.contains(&"rsxm-rules"));
        assert!(!wanted.contains(&"rsxm-stats"));
        // Tunnel is unconditional.
        assert!(wanted.contains(&"rsxm-tun"));
        assert!(wanted.contains(&"rsxm-dialer"));
    }

    #[test]
    fn configure_reads_full_slice() {
        let m = PowerModule::new();
        m.configure(Some(&ConfigSlice::new(
            "rsxm-power",
            json!({
                "healthCheckIntervalMinutes": 3,
                "deepPauseAfterMs": 60_000,
                "enabledFeatures": ["dns", "rules", "stats", "security"],
            }),
        )))
        .unwrap();
        let wanted = m.desired_modules();
        for name in ["rsxm-dns", "rsxm-rules", "rsxm-stats", "rsxm-security"] {
            assert!(wanted.contains(&name), "missing {name}");
        }
        assert_eq!(m.effective_health_check_ms(), Some(3 * 60_000));
    }
}
