//! RSXM power micro-kernel — the battery-policy leader.
//!
//! The power-saving playbook used by bettbox / FlClash has three cheap
//! levers, all implemented here as *policy*, never as timers the kernel
//! itself owns:
//!
//! 1. **Stretch health checks while the screen is off.** Latency probes
//!    exist to pick a node for the user; nobody watches the screen in a
//!    pocket, so radio wakeups there are pure waste. The interval is
//!    multiplied by [`SCREEN_OFF_MULTIPLIER`] and raised to a
//!    [`SCREEN_OFF_FLOOR_MINUTES`] floor — the same idea bettbox uses to
//!    stop background churn in Doze.
//! 2. **Bounded TCP keepalive.** An idle keepalive shorter than the radio's
//!    own idle timeout keeps the cellular link promoted for nothing; the
//!    leader clamps the configured value and treats `0` as "leave it to the
//!    OS".
//! 3. **Opt-in boot start.** Auto-start is a feature, never a default.
//!
//! The Conductor only learns the outcome via [`ReportKind::Power`] lines;
//! the slice contents never leave this crate.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, RwLock};

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

/// Conservative, quiet defaults: no periodic probes, OS keepalive, no boot
/// auto-start.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
struct PowerPolicy {
    /// Health-check cadence in minutes; `0` disables periodic probes.
    health_check_minutes: u64,
    /// TCP keepalive idle in seconds; `0` = OS default.
    keepalive_idle_seconds: u64,
    boot_auto_start: bool,
}

impl PowerPolicy {
    fn sanitize(mut self) -> Self {
        if self.health_check_minutes > HEALTH_CHECK_MAX_MINUTES {
            self.health_check_minutes = HEALTH_CHECK_MAX_MINUTES;
        }
        if self.keepalive_idle_seconds > KEEPALIVE_MAX_SECONDS {
            self.keepalive_idle_seconds = KEEPALIVE_MAX_SECONDS;
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
}

/// The power leader.
pub struct PowerModule {
    policy: RwLock<PowerPolicy>,
    screen_on: AtomicBool,
    /// Last time a health probe was permitted (caller-supplied clock).
    last_probe_ms: Mutex<Option<i64>>,
    reporter: Mutex<Option<ModuleReporter>>,
}

impl PowerModule {
    pub fn new() -> Self {
        Self {
            policy: RwLock::new(PowerPolicy::default()),
            // Assume the screen is on at boot; the first real state event
            // corrects this immediately.
            screen_on: AtomicBool::new(true),
            last_probe_ms: Mutex::new(None),
            reporter: Mutex::new(None),
        }
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

    /// Feeds a screen-state transition from the Android side
    /// (`ACTION_SCREEN_ON/OFF`). Only real edges emit a report.
    pub fn on_screen_state(&self, on: bool) {
        let previous = self.screen_on.swap(on, Ordering::AcqRel);
        if previous != on {
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
    }

    pub fn screen_is_on(&self) -> bool {
        self.screen_on.load(Ordering::Acquire)
    }

    /// The probe cadence right now, after applying the screen-off stretch.
    /// `None` means periodic health checks are disabled.
    pub fn effective_health_check_ms(&self) -> Option<u64> {
        let policy = self
            .policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let minutes = policy.health_check_minutes;
        if minutes == 0 {
            return None;
        }
        let effective = if self.screen_is_on() {
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
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .keepalive_idle_seconds
    }

    pub fn boot_auto_start(&self) -> bool {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .boot_auto_start
    }

    /// Gate for the health-probe scheduler: returns true (and stamps the
    /// clock) exactly when the effective interval has elapsed. When probes
    /// are disabled it always returns false — the VPN service then skips
    /// its periodic wakeup entirely, the biggest battery win available.
    pub fn should_probe(&self, now_ms: i64) -> bool {
        let Some(interval_ms) = self.effective_health_check_ms() else {
            return false;
        };
        let mut last = self
            .last_probe_ms
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let due = last.is_none_or(|t| now_ms - t >= interval_ms as i64);
        if due {
            *last = Some(now_ms);
        }
        due
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
            *self
                .policy
                .write()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = PowerPolicy::default();
            return Ok(());
        };
        let parsed: PowerSlice =
            serde_json::from_value(slice.value.as_ref().clone()).map_err(|e| e.to_string())?;
        let policy = PowerPolicy {
            health_check_minutes: parsed.healthCheckIntervalMinutes,
            keepalive_idle_seconds: parsed.tcpKeepAliveIdleSeconds,
            boot_auto_start: parsed.bootAutoStart,
        }
        .sanitize();
        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "power policy: probes={}min, keepalive={}s, boot={}",
                policy.health_check_minutes, policy.keepalive_idle_seconds, policy.boot_auto_start
            ),
        );
        *self
            .policy
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = policy;
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
        // the gating decisions the VPN service queries.
        Health::Up
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsxm_core::{Conductor, ConfigEnvelope, ConfigSlice};
    use serde_json::json;
    use std::sync::Arc;

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
                }),
            )))
            .unwrap();
        // 5 min * ... ensure the interval is clamped to the 12h ceiling.
        assert_eq!(module.effective_health_check_ms(), Some(720 * 60_000));
        assert_eq!(module.keepalive_idle_seconds(), 3600);
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
}
