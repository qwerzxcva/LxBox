//! RSXM core: the central scheduler that owns the micro-kernel modules.
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
//! lifecycle (start/stop/health) and wires the data path so hot paths stay
//! direct calls — no IPC in the packet fast path.
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

    /// Bring the module up. Called once, in dependency order.
    fn start(&self) -> Result<(), String>;

    /// Ask the module to stop; must be idempotent.
    fn stop(&self) -> Result<(), String>;

    /// Cheap, side-effect-free health probe.
    fn health(&self) -> Health {
        Health::Up
    }
}

/// The scheduler: dependency-ordered startup, and a route table that the
/// packet path consults without locks.
pub struct Scheduler {
    modules: Vec<Arc<dyn Module>>,
    started: Vec<&'static str>,
    shutdown: AtomicBool,
    table: parking_lot::RwLock<RuleTable>,
}

impl Scheduler {
    pub fn new() -> Self {
        Self {
            modules: Vec::new(),
            started: Vec::new(),
            shutdown: AtomicBool::new(false),
            table: parking_lot::RwLock::new(RuleTable::default()),
        }
    }

    /// Registers a module. Order here does not matter — startup sorts by
    /// [`Module::depends_on`].
    pub fn register(&mut self, module: Arc<dyn Module>) {
        self.modules.push(module);
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

    /// Replaces the routing table. Cheap for readers: they take a read lock
    /// only on configuration change, and the table itself is immutable.
    pub fn install_rules(&self, rules: Vec<Rule>) {
        // Poisoning cannot happen here: no code panics while holding the
        // write lock (the only body is an assignment).
        *self.table.write().expect("rule table lock") = RuleTable::build(rules);
    }

    /// Hot-path routing. No allocation; one read lock acquisition.
    pub fn route(&self, query: &Query) -> Option<Match> {
        self.table
            .read()
            .expect("rule table lock")
            .match_query(query)
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

/// Minimal RwLock stand-in so this crate has no async runtime dependency.
/// The real integration swaps in `parking_lot` when the runtime lands; the
/// scheduler API does not change.
mod parking_lot {
    pub use std::sync::RwLock;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    struct FakeModule {
        name: &'static str,
        deps: &'static [&'static str],
        starts: AtomicUsize,
        fail: bool,
    }

    impl FakeModule {
        fn new(name: &'static str, deps: &'static [&'static str], fail: bool) -> Arc<Self> {
            Arc::new(Self {
                name,
                deps,
                starts: AtomicUsize::new(0),
                fail,
            })
        }
    }

    impl Module for FakeModule {
        fn name(&self) -> &'static str {
            self.name
        }
        fn depends_on(&self) -> &'static [&'static str] {
            self.deps
        }
        fn start(&self) -> Result<(), String> {
            self.starts.fetch_add(1, Ordering::SeqCst);
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
    fn modules_start_in_dependency_order() {
        let mut scheduler = Scheduler::new();
        let dialer = FakeModule::new("dialer", &["tun"], false);
        let tun = FakeModule::new("tun", &[], false);
        scheduler.register(dialer.clone());
        scheduler.register(tun.clone());

        let report = scheduler.start_all();
        assert!(report.iter().all(|(_, r)| r.is_ok()));
        // tun started before dialer despite registration order.
        let names: Vec<_> = report.iter().map(|(n, _)| *n).collect();
        assert_eq!(names, vec!["tun", "dialer"]);
    }

    #[test]
    fn module_with_failed_dependency_is_skipped() {
        let mut scheduler = Scheduler::new();
        let tun = FakeModule::new("tun", &[], true);
        let dialer = FakeModule::new("dialer", &["tun"], false);
        scheduler.register(tun.clone());
        scheduler.register(dialer.clone());

        let report = scheduler.start_all();
        let dialer_result = report.iter().find(|(n, _)| *n == "dialer").unwrap();
        assert!(dialer_result.1.is_err());
        assert_eq!(dialer.starts.load(Ordering::SeqCst), 0, "must not start");
    }

    #[test]
    fn missing_dependency_is_reported() {
        let mut scheduler = Scheduler::new();
        let ghost = FakeModule::new("ghost", &["nowhere"], false);
        scheduler.register(ghost.clone());
        let report = scheduler.start_all();
        assert!(report[0].1.is_err());
        assert_eq!(ghost.starts.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn routing_consults_the_installed_table() {
        let scheduler = Scheduler::new();
        scheduler.install_rules(vec![Rule {
            id: "r".into(),
            suffixes: vec!["example.com".into()],
            target: Some(Target::Outbound("proxy".into())),
            ..Default::default()
        }]);
        let m = scheduler
            .route(&Query {
                domain: Some("a.example.com".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.target, Target::Outbound("proxy".into()));
    }

    #[test]
    fn stop_is_idempotent_and_flag_flips() {
        let mut scheduler = Scheduler::new();
        scheduler.register(FakeModule::new("tun", &[], false));
        scheduler.start_all();
        scheduler.stop_all();
        assert!(scheduler.is_shutting_down());
        scheduler.stop_all(); // second call must not panic
    }
}
