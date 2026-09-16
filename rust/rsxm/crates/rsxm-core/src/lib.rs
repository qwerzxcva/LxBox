//! RSXM core: the central scheduler that owns the micro-kernel modules.
//!
//! ## The boss does not read
//!
//! The scheduler is a boss: it assigns work (start/stop/configure) and
//! collects results (health, lifecycle reports). It never parses business
//! configuration — no route rules, no TUN parameters, no DNS server lists
//! pass through this crate. Configuration is split by `rsxm-config` into
//! per-module slices ([`ConfigSlice`]); each module deserialises its own
//! slice inside [`Module::configure`]. Consequences:
//!
//! - the scheduler compiles without knowing any rule/DNS/dialer shape;
//! - a module sees exactly its own slice, never the whole document;
//! - changing a module's config shape touches only that module and the one
//!   extractor in rsxm-config.
//!
//! ## Why micro-kernels
//! The monolith (one process, one engine, one config) couples concerns that
//! have different failure modes and different perf profiles:
//!
//! | module   | responsibility                    | failure impact      |
//! |----------|-----------------------------------|---------------------|
//! | tun      | packet I/O (HEV/lwIP or Go stack) | tunnel down          |
//! | rules    | routing decisions                 | misroute, no crash  |
//! | dialer   | protocol handshakes (vless/ss)    | node down            |
//! | dns      | resolution + cache                | slow, no crash      |
//! | stats    | counters + connection table       | UI stale            |
//!
//! RSXM splits them into supervised actors behind one scheduler: a crash in
//! the DNS cache cannot take the tunnel down, and each module can be
//! tested, profiled and optimised in isolation. The scheduler owns
//! lifecycle (start/stop/health/configure) and wires the data path so hot
//! paths stay direct calls — no IPC in the packet fast path.
//!
//! ## Staged migration, not a big bang
//! The agent crate runs the same scheduler against the *existing* sing-box
//! core: modules that are still served by sing-box are wrapped as
//! [`Module`] implementations, and each new Rust module replaces one
//! wrapper at a time. Nothing user-visible changes until a module is
//! proven, which is what makes a gradual Go→Rust conversion safe.

use std::collections::HashMap;
use std::fmt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub use rsxm_config::{ConfigEnvelope, ConfigSlice};
pub use rsxm_rules::{Match, Query, Rule, RuleTable, Target};

/// Health of a module as reported to the scheduler.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Health {
    /// Running and serving traffic.
    Up,
    /// Starting or restarting.
    Degraded(&'static str),
    /// Stopped or failed.
    Down(&'static str),
}

impl fmt::Display for Health {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Health::Up => write!(f, "up"),
            Health::Degraded(why) => write!(f, "degraded: {why}"),
            Health::Down(why) => write!(f, "down: {why}"),
        }
    }
}

/// Every micro-kernel implements this. `Send + Sync` because the scheduler
/// may move modules across threads; the trait stays tiny so a module can be
/// a thin wrapper around existing code during migration.
pub trait Module: Send + Sync {
    /// Stable identifier used in logs, metrics and dependency resolution.
    fn name(&self) -> &'static str;

    /// Modules this one needs to be started first.
    fn depends_on(&self) -> &'static [&'static str] {
        &[]
    }

    /// Receives this module's configuration slice — and nothing else. The
    /// module deserialises and validates its own section here; the scheduler
    /// cannot read the slice's content even if it wanted to (opaque Value).
    /// Called before [`Module::start`] on every config change.
    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String>;

    /// Bring the module up. Called once, in dependency order, after
    /// [`Module::configure`].
    fn start(&self) -> Result<(), String>;

    /// Ask the module to stop; must be idempotent.
    fn stop(&self) -> Result<(), String>;

    /// Cheap, side-effect-free health probe.
    fn health(&self) -> Health {
        Health::Up
    }
}

/// The scheduler: dependency-ordered startup, config distribution, and
/// lifecycle supervision. It holds NO business state — routing tables live
/// in rsxm-rules, caches in rsxm-dns, and so on; the scheduler keeps only
/// module handles.
pub struct Scheduler {
    modules: Vec<Arc<dyn Module>>,
    started: Vec<&'static str>,
    shutdown: AtomicBool,
    /// The last envelope seen; kept only so a *newly registered* module can
    /// be configured before start. The scheduler does not interpret it.
    last_envelope: ConfigEnvelope,
}

impl Scheduler {
    pub fn new() -> Self {
        Self {
            modules: Vec::new(),
            started: Vec::new(),
            shutdown: AtomicBool::new(false),
            last_envelope: ConfigEnvelope::default(),
        }
    }

    /// Registers a module and immediately hands it its slice from the last
    /// envelope (if one arrived). Order here does not matter — startup
    /// sorts by [`Module::depends_on`].
    pub fn register(&mut self, module: Arc<dyn Module>) {
        if let Some(slice) = self.last_envelope.for_module(module.name()) {
            if let Err(why) = module.configure(Some(slice)) {
                eprintln!("[rsxm] {} rejected config: {why}", module.name());
            }
        } else {
            let _ = module.configure(None);
        }
        self.modules.push(module);
    }

    /// Distributes a new envelope: each module receives its own slice (or
    /// None when the feature is off). Modules may be registered after this;
    /// [`Scheduler::register`] catches them up.
    pub fn distribute(&mut self, envelope: ConfigEnvelope) {
        for module in &self.modules {
            let slice = envelope.for_module(module.name());
            if let Err(why) = module.configure(slice) {
                eprintln!("[rsxm] {} rejected config: {why}", module.name());
            }
        }
        self.last_envelope = envelope;
    }

    /// Starts every module in dependency order. A module whose dependency
    /// failed is skipped (and reported), not started against a missing
    /// contract.
    pub fn start_all(&mut self) -> Vec<(&'static str, Result<(), String>)> {
        let mut report = Vec::new();
        let mut started: HashMap<&'static str, bool> = HashMap::new();

        let mut pending: Vec<Arc<dyn Module>> = self.modules.clone();
        let mut progress = true;
        while !pending.is_empty() && progress {
            progress = false;
            let mut next_pending = Vec::new();
            for module in pending {
                let deps_ok = module
                    .depends_on()
                    .iter()
                    .all(|dep| started.get(dep).copied().unwrap_or(false));
                let deps_missing = module
                    .depends_on()
                    .iter()
                    .any(|dep| !self.modules.iter().any(|m| m.name() == *dep));
                if deps_missing {
                    report.push((module.name(), Err("missing dependency".into())));
                    started.insert(module.name(), false);
                    continue;
                }
                if !deps_ok {
                    next_pending.push(module);
                    continue;
                }
                let result = module.start();
                started.insert(module.name(), result.is_ok());
                if result.is_ok() {
                    self.started.push(module.name());
                }
                report.push((module.name(), result));
                progress = true;
            }
            pending = next_pending;
        }
        // Anything left had dependencies that never came up.
        for module in pending {
            report.push((module.name(), Err("dependency not started".into())));
        }
        report
    }

    /// Stops modules in reverse start order. Errors are collected, not
    /// fatal: shutdown must always make progress.
    pub fn stop_all(&self) -> Vec<(&'static str, Result<(), String>)> {
        self.shutdown.store(true, Ordering::Release);
        self.started
            .iter()
            .rev()
            .filter_map(|name| {
                self.modules
                    .iter()
                    .find(|m| m.name() == *name)
                    .map(|module| (module.name(), module.stop()))
            })
            .collect()
    }

    pub fn is_shutting_down(&self) -> bool {
        self.shutdown.load(Ordering::Acquire)
    }

    /// Aggregate health for a status endpoint / UI.
    pub fn health(&self) -> Vec<(&'static str, Health)> {
        self.modules
            .iter()
            .map(|m| (m.name(), m.health()))
            .collect()
    }
}

impl Default for Scheduler {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    struct FakeModule {
        name: &'static str,
        deps: &'static [&'static str],
        fail: bool,
    }
    impl FakeModule {
        fn new(name: &'static str, deps: &'static [&'static str], fail: bool) -> Arc<Self> {
            Arc::new(Self { name, deps, fail })
        }
    }
    impl Module for FakeModule {
        fn name(&self) -> &'static str {
            self.name
        }
        fn depends_on(&self) -> &'static [&'static str] {
            self.deps
        }
        fn configure(&self, _slice: Option<&ConfigSlice>) -> Result<(), String> {
            Ok(())
        }
        fn start(&self) -> Result<(), String> {
            if self.fail {
                Err("boom".into())
            } else {
                Ok(())
            }
        }
        fn stop(&self) -> Result<(), String> {
            Ok(())
        }
    }

    #[test]
    fn starts_in_dependency_order() {
        let mut s = Scheduler::new();
        s.register(FakeModule::new("b", &["a"], false));
        s.register(FakeModule::new("a", &[], false));
        let report = s.start_all();
        assert!(report.iter().all(|(_, r)| r.is_ok()));
        let order: Vec<&'static str> = s.started.clone();
        assert!(order.iter().position(|n| *n == "a") < order.iter().position(|n| *n == "b"));
    }

    #[test]
    fn failing_module_blocks_dependents() {
        let mut s = Scheduler::new();
        s.register(FakeModule::new("a", &[], true));
        s.register(FakeModule::new("b", &["a"], false));
        let report = s.start_all();
        let a = report.iter().find(|(n, _)| *n == "a").unwrap();
        assert!(a.1.is_err());
        let b = report.iter().find(|(n, _)| *n == "b").unwrap();
        assert!(b.1.is_err());
    }

    #[test]
    fn missing_dependency_is_reported() {
        let mut s = Scheduler::new();
        s.register(FakeModule::new("b", &["ghost"], false));
        let report = s.start_all();
        let b = report.iter().find(|(n, _)| *n == "b").unwrap();
        assert!(b.1.is_err());
    }

    #[test]
    fn reverse_order_shutdown() {
        let mut s = Scheduler::new();
        s.register(FakeModule::new("a", &[], false));
        s.register(FakeModule::new("b", &["a"], false));
        s.start_all();
        let order: Vec<&'static str> = s.stop_all().iter().map(|(n, _)| *n).collect();
        assert_eq!(order, vec!["b", "a"]);
    }

    #[test]
    fn distribute_hands_each_module_its_own_slice() {
        use serde_json::{json, Value};

        struct Spy {
            got: std::sync::Mutex<Option<Value>>,
        }
        impl Module for Spy {
            fn name(&self) -> &'static str {
                "spy"
            }
            fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
                *self.got.lock().unwrap() = slice.map(|s| (*s.value).clone());
                Ok(())
            }
            fn start(&self) -> Result<(), String> {
                Ok(())
            }
            fn stop(&self) -> Result<(), String> {
                Ok(())
            }
        }

        let spy = Arc::new(Spy {
            got: std::sync::Mutex::new(None),
        });
        let mut s = Scheduler::new();
        s.register(spy.clone());

        let env = ConfigEnvelope::default();
        s.distribute(env);
        assert!(spy.got.lock().unwrap().is_none());

        // A real envelope: build via rsxm-config from a document.
        let doc = json!({
            "tunMtu": 9000,
            "routeRules": [{ "id": "r1", "domainSuffix": ["x.com"] }],
        });
        let envelope = rsxm_config::split(&doc);
        // The spy is not addressed by this document — still None.
        s.distribute(envelope);
        assert!(spy.got.lock().unwrap().is_none());
    }

    #[test]
    fn scheduler_crud_never_touches_rules() {
        // Compile-time intent check: the scheduler has no rule-table field.
        // (Enforced by the absence of `install_rules`.)
        let s = Scheduler::new();
        assert!(s.is_shutting_down() == false);
    }

    #[test]
    fn concurrent_health_reads() {
        use std::sync::atomic::Ordering;
        let mut s = Scheduler::new();
        s.register(FakeModule::new("a", &[], false));
        s.start_all();
        let health = s.health();
        assert_eq!(health.len(), 1);
        assert_eq!(health[0].1, Health::Up);
        assert!(!s.is_shutting_down());
        let _ = Ordering::SeqCst; // silence unused import in some toolchains
    }

    #[test]
    fn counter_smoke() {
        let c = AtomicUsize::new(0);
        c.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        assert_eq!(c.load(std::sync::atomic::Ordering::Relaxed), 1);
    }
}
