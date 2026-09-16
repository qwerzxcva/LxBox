//! rsxm-power: the power micro-kernel.
//!
//! Owns the device power state machine (screen / Doze / charging) and turns
//! it into module-level directives: pause background work, trim caches, or
//! shut down modules whose features are disabled ("run only what is on").
//! The central scheduler forwards these as plain `Module` calls — power
//! never touches other modules' internals or configuration.
//!
//! Policy model:
//!
//! - screen off + no charging for `deep_pause_after_ms` → [`PowerState::DeepPause`]
//! - screen on → [`PowerState::Active`]
//! - charging → active-ish (background work allowed) regardless of screen
//!
//! Feature gating: the app enables/disables features in AppState; the
//! config slice carries `enabledFeatures`. On every reconfigure, power
//! computes which modules should be running and reports the diff to the
//! scheduler through the callback the host installs.

use rsxm_core::{ConfigSlice, Health, Module};
use std::sync::{Arc, Mutex};

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

/// Power policy from the app's config slice.
#[derive(Debug, Clone)]
pub struct PowerPolicy {
    /// Deep-pause grace window after screen-off, in milliseconds.
    pub deep_pause_after_ms: i64,
    /// Features the user enabled; anything not listed may be shut down.
    pub enabled_features: Vec<String>,
}

impl Default for PowerPolicy {
    fn default() -> Self {
        Self {
            deep_pause_after_ms: 5 * 60 * 1000,
            enabled_features: Vec::new(),
        }
    }
}

/// Observed power state + policy, guarded by one mutex (power events are
/// rare; contention is a non-issue).
#[derive(Debug)]
struct Inner {
    state: PowerState,
    screen_off_at: Option<i64>,
    policy: PowerPolicy,
    /// Directives the power kernel has published since start.
    directives_issued: u64,
}

/// The power micro-kernel module.
pub struct PowerModule {
    inner: Mutex<Inner>,
    /// Wall-clock in ms, injectable for tests.
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
}

fn system_clock() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl PowerModule {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                state: PowerState::Active,
                screen_off_at: None,
                policy: PowerPolicy::default(),
                directives_issued: 0,
            }),
            clock: Arc::new(system_clock),
        }
    }

    /// Test constructor with a custom clock.
    pub fn with_clock(clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> Self {
        Self {
            inner: Mutex::new(Inner {
                state: PowerState::Active,
                screen_off_at: None,
                policy: PowerPolicy::default(),
                directives_issued: 0,
            }),
            clock,
        }
    }

    /// Host reports the screen state.
    pub fn record_screen(&self, on: bool, charging: bool) -> Directive {
        let mut inner = self.lock();
        let now = (self.clock)();
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
        let d = self.evaluate_locked(&inner, now);
        inner.directives_issued += 1;
        d
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
        let d = self.evaluate_locked(&inner, now);
        inner.directives_issued += 1;
        d
    }

    /// Modules that should be running given the user's enabled features.
    pub fn desired_modules(&self) -> Vec<&'static str> {
        let inner = self.lock();
        let on = |name: &str| {
            inner
                .policy
                .enabled_features
                .iter()
                .any(|f| f == name)
        };
        // The tunnel (tun + dialer) is the product: always desired while the
        // app runs. Optional features gate their own kernels.
        let mut wanted = vec!["rsxm-tun", "rsxm-dialer", "rsxm-power"];
        if on("dns") {
            wanted.push("rsxm-dns");
        }
        if on("rules") {
            wanted.push("rsxm-rules");
        }
        wanted
    }

    fn evaluate_locked(&self, inner: &Inner, now: i64) -> Directive {
        if inner.state == PowerState::ScreenOff {
            if let Some(off_at) = inner.screen_off_at {
                if now - off_at >= inner.policy.deep_pause_after_ms {
                    return Directive::DeepPause;
                }
            }
        }
        inner.state.directive()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
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

    /// Consumes the power slice: policy numbers + the enabled-feature list.
    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            return Ok(());
        };
        let obj = slice.value.as_object().ok_or("power slice must be object")?;
        let mut inner = self.lock();
        if let Some(ms) = obj.get("deepPauseAfterMs").and_then(|v| v.as_i64()) {
            inner.policy.deep_pause_after_ms = ms;
        }
        if let Some(feats) = obj.get("enabledFeatures").and_then(|v| v.as_array()) {
            inner.policy.enabled_features = feats
                .iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect();
        }
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        Health::Up
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn module(clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> PowerModule {
        PowerModule::with_clock(clock)
    }

    #[test]
    fn screen_off_then_deep_pause_after_window() {
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let now_c = now.clone();
        let m = module(Arc::new(move || *now_c.lock().unwrap()));
        assert_eq!(m.record_screen(false, false), Directive::Throttle);
        *now.lock().unwrap() += 100; // inside grace window
        assert_eq!(m.tick(), Directive::Throttle);
        *now.lock().unwrap() += 6 * 60 * 1000; // past the default 5-minute window
        assert_eq!(m.tick(), Directive::DeepPause);
    }

    #[test]
    fn screen_on_resets_to_active() {
        let mut now = 1000i64;
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let now_c = now.clone();
        let m = module(Arc::new(move || *now_c.lock().unwrap()));
        m.record_screen(false, false);
        *now.lock().unwrap() += 10 * 60 * 1000; // would be deep pause…
        let d = m.record_screen(true, false);
        assert_eq!(d, Directive::Run);
        assert_eq!(m.tick(), Directive::Run);
    }

    #[test]
    fn charging_treats_as_active_even_with_screen_off() {
        let now = Arc::new(std::sync::Mutex::new(1000i64));
        let now_c = now.clone();
        let m = module(Arc::new(move || *now_c.lock().unwrap()));
        assert_eq!(m.record_screen(false, true), Directive::Run);
        *now.lock().unwrap() += 10 * 60 * 1000;
        assert_eq!(m.tick(), Directive::Run);
    }

    #[test]
    fn feature_list_gates_optional_kernels() {
        let m = module(Arc::new(|| 0));
        {
            let mut inner = m.lock();
            inner.policy.enabled_features = vec!["dns".into()];
        }
        let wanted = m.desired_modules();
        assert!(wanted.contains(&"rsxm-dns"));
        assert!(!wanted.contains(&"rsxm-rules"));
        // Tunnel is unconditional.
        assert!(wanted.contains(&"rsxm-tun"));
        assert!(wanted.contains(&"rsxm-dialer"));
    }

    #[test]
    fn configure_reads_slice() {
        use serde_json::json;
        let m = module(Arc::new(|| 0));
        let slice = ConfigSlice {
            module: "rsxm-power",
            value: std::sync::Arc::new(json!({
                "deepPauseAfterMs": 60_000,
                "enabledFeatures": ["dns", "rules"],
            })),
        };
        m.configure(Some(&slice)).unwrap();
        {
            let inner = m.lock();
            assert_eq!(inner.policy.deep_pause_after_ms, 60_000);
            assert_eq!(inner.policy.enabled_features.len(), 2);
        }
        let wanted = m.desired_modules();
        assert!(wanted.contains(&"rsxm-rules"));
    }
}
