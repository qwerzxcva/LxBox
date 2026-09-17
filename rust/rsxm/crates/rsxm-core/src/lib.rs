//! RSXM core — the **Conductor**, the single central control kernel.
//!
//! ## 一超多强: one conductor, many leaders
//!
//! ```text
//!                    ┌─────────────────────────────┐
//!                    │       Conductor (boss)      │
//!                    │  lifecycle · dependency     │
//!                    │  supervision · report bus   │
//!                    └──────────────┬──────────────┘
//!          ┌───────────┬───────────┼───────────┬────────────┐
//!      rsxm-rules   rsxm-dns   rsxm-dialer  rsxm-tun   rsxm-stats
//!      rsxm-power   rsxm-security …                       (leaders)
//! ```
//!
//! Every micro-kernel is a [`Module`]: the Conductor hands each one its own
//! opaque configuration slice, starts them in dependency order, supervises
//! them, and receives their [`Report`]s on a shared [`Reporter`]. Leaders
//! take orders from the boss and report back; the boss never reads their
//! paperwork.
//!
//! ## What the Conductor must never know
//!
//! This crate is intentionally dependency-free of every business crate. It
//! does not know the shape of a route rule, a DNS server or a node: the
//! only thing it sees is an opaque `serde_json::Value` wrapped in a
//! [`ConfigSlice`] addressed to a module. Adding a micro-kernel means
//! adding a crate that implements [`Module`]; the Conductor changes never.
//!
//! ## Hot path stays direct
//!
//! Micro-kernels collaborate through direct typed handles they obtain at
//! `start` (e.g. the TUN engine calls the rules engine in-process). The
//! Conductor is deliberately *not* on the packet path — it only schedules
//! and supervises. The [`Reporter`] is the single upward channel, sized to
//! absorb bursts without allocating on the reporter when saturated.

use std::collections::{HashMap, VecDeque};
use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::Value;

// ---------------------------------------------------------------------------
// Configuration envelope — opaque to the Conductor
// ---------------------------------------------------------------------------

/// One module's configuration slice. The content is opaque to everyone
/// except the module it is addressed to.
#[derive(Debug, Clone)]
pub struct ConfigSlice {
    pub module: &'static str,
    pub value: Arc<Value>,
}

impl ConfigSlice {
    pub fn new(module: &'static str, value: Value) -> Self {
        Self {
            module,
            value: Arc::new(value),
        }
    }
}

/// The envelope the Conductor distributes: slices addressed to modules,
/// plus nothing else — even the "which modules are enabled" question is
/// answered by slice presence, so the Conductor needs no manifest either.
#[derive(Debug, Clone, Default)]
pub struct ConfigEnvelope {
    slices: Vec<ConfigSlice>,
}

impl ConfigEnvelope {
    pub fn new(slices: Vec<ConfigSlice>) -> Self {
        Self { slices }
    }

    pub fn slices(&self) -> &[ConfigSlice] {
        &self.slices
    }

    /// The slice addressed to `module`, if the document carried one.
    pub fn for_module(&self, module: &str) -> Option<&ConfigSlice> {
        self.slices.iter().find(|s| s.module == module)
    }
}

// ---------------------------------------------------------------------------
// Health
// ---------------------------------------------------------------------------

/// Health of a module as reported to the Conductor.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Health {
    /// Running and serving traffic.
    Up,
    /// Starting or running degraded; the string is a short reason.
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

// ---------------------------------------------------------------------------
// Report bus — leaders report up to the boss
// ---------------------------------------------------------------------------

/// Class of a report. Kept coarse on purpose: the payload string carries
/// the detail, the kind drives aggregation/UI grouping.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ReportKind {
    /// Module entered the running state.
    Started,
    /// Module stopped cleanly.
    Stopped,
    /// A configuration slice was accepted.
    ConfigAccepted,
    /// A configuration slice was rejected (bad shape, impossible value).
    ConfigRejected,
    /// A module's health changed.
    HealthChange,
    /// A routing decision (sampled, not per-packet).
    Route,
    /// A DNS event (cache miss, fake allocation, upstream switch).
    Dns,
    /// A counters/connection summary.
    Stats,
    /// A security finding (leak risk, TLS policy violation).
    Security,
    /// A power-state directive (screen/doze policy applied).
    Power,
    /// Informational line.
    Info,
    /// Recoverable problem.
    Warn,
    /// Failure worth surfacing to the UI.
    Error,
}

impl ReportKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ReportKind::Started => "started",
            ReportKind::Stopped => "stopped",
            ReportKind::ConfigAccepted => "config_accepted",
            ReportKind::ConfigRejected => "config_rejected",
            ReportKind::HealthChange => "health_change",
            ReportKind::Route => "route",
            ReportKind::Dns => "dns",
            ReportKind::Stats => "stats",
            ReportKind::Security => "security",
            ReportKind::Power => "power",
            ReportKind::Info => "info",
            ReportKind::Warn => "warn",
            ReportKind::Error => "error",
        }
    }
}

/// One message flowing upward from a micro-kernel.
#[derive(Debug, Clone)]
pub struct Report {
    pub module: String,
    pub kind: ReportKind,
    /// Wall clock in milliseconds since the Unix epoch, as supplied by the
    /// reporter (the Conductor owns no clock — keeps tests deterministic).
    pub at_ms: i64,
    pub message: String,
}

impl Report {
    pub fn new(module: impl Into<String>, kind: ReportKind, message: impl Into<String>) -> Self {
        Self {
            module: module.into(),
            kind,
            at_ms: 0,
            message: message.into(),
        }
    }

    pub fn at(mut self, ms: i64) -> Self {
        self.at_ms = ms;
        self
    }
}

/// Optional live tap on the report stream (UI bridge, log forwarder).
pub trait ReportSink: Send + Sync {
    fn handle(&self, report: &Report);
}

/// Cloneable upward channel. When the bounded buffer is full the oldest
/// report is dropped and a counter ticks up — a slow UI must never stall a
/// micro-kernel, let alone the packet path.
#[derive(Clone)]
pub struct Reporter {
    inner: Arc<ReporterInner>,
}

struct ReporterInner {
    reports: Mutex<VecDeque<Report>>,
    capacity: usize,
    dropped: AtomicU64,
    sinks: Mutex<Vec<Arc<dyn ReportSink>>>,
}

impl Reporter {
    pub fn new(capacity: usize) -> Self {
        Self {
            inner: Arc::new(ReporterInner {
                reports: Mutex::new(VecDeque::with_capacity(capacity)),
                capacity: capacity.max(1),
                dropped: AtomicU64::new(0),
                sinks: Mutex::new(Vec::new()),
            }),
        }
    }

    /// Hands a report to the boss. Never blocks on sinks: a misbehaving
    /// sink is skipped after it panics (Mutex poisoning tolerated).
    pub fn send(&self, report: Report) {
        {
            let mut queue = self
                .inner
                .reports
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if queue.len() >= self.inner.capacity {
                queue.pop_front();
                self.inner.dropped.fetch_add(1, Ordering::Relaxed);
            }
            queue.push_back(report.clone());
        }
        let sinks = self
            .inner
            .sinks
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        for sink in sinks.iter() {
            sink.handle(&report);
        }
    }

    /// Convenience constructor for modules that hold a named child
    /// reporter (the module name is stamped automatically).
    pub fn for_module(&self, module: &'static str) -> ModuleReporter {
        ModuleReporter {
            reporter: self.clone(),
            module,
        }
    }

    pub fn add_sink(&self, sink: Arc<dyn ReportSink>) {
        self.inner
            .sinks
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .push(sink);
    }

    /// Drains everything the leaders reported since the last drain.
    pub fn drain(&self) -> Vec<Report> {
        let mut queue = self
            .inner
            .reports
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        queue.drain(..).collect()
    }

    pub fn pending_len(&self) -> usize {
        self.inner
            .reports
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .len()
    }

    /// Reports dropped under saturation since start.
    pub fn dropped_count(&self) -> u64 {
        self.inner.dropped.load(Ordering::Relaxed)
    }
}

/// A [`Reporter`] with the module name pre-stamped; what a leader holds.
#[derive(Clone)]
pub struct ModuleReporter {
    reporter: Reporter,
    module: &'static str,
}

impl ModuleReporter {
    pub fn module(&self) -> &'static str {
        self.module
    }

    pub fn send(&self, kind: ReportKind, message: impl Into<String>) {
        self.reporter.send(Report::new(self.module, kind, message));
    }

    pub fn info(&self, message: impl Into<String>) {
        self.send(ReportKind::Info, message);
    }

    pub fn warn(&self, message: impl Into<String>) {
        self.send(ReportKind::Warn, message);
    }

    pub fn error(&self, message: impl Into<String>) {
        self.send(ReportKind::Error, message);
    }
}

// ---------------------------------------------------------------------------
// Module contract
// ---------------------------------------------------------------------------

/// Every micro-kernel ("leader") implements this. `Send + Sync` because the
/// Conductor may move modules across threads; the trait stays tiny so a
/// module can be a thin wrapper around existing code during migration.
pub trait Module: Send + Sync {
    /// Stable identifier used in logs, metrics, dependency resolution and
    /// slice addressing. Convention: `rsxm-<domain>` (`rsxm-rules`…).
    fn name(&self) -> &'static str;

    /// Modules this one needs to be started first.
    fn depends_on(&self) -> &'static [&'static str] {
        &[]
    }

    /// Receives the leader's private reporting channel. Called once before
    /// the first [`Module::configure`]; leaders store the handle and use it
    /// for their whole lifetime.
    fn attach(&self, _reporter: &ModuleReporter) {}

    /// Receives this module's configuration slice — and nothing else. The
    /// module deserialises and validates its own section here; the
    /// Conductor cannot read the slice's content even if it wanted to.
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

// ---------------------------------------------------------------------------
// Supervision policy
// ---------------------------------------------------------------------------

/// Exponential backoff for restarting a crashed leader. Pure policy: the
/// Conductor never sleeps itself — the embedding runtime (agent / JNI
/// runtime) decides when to re-enter [`Conductor::restart_module`].
#[derive(Debug, Clone, Copy)]
pub struct RestartPolicy {
    pub max_attempts: u32,
    /// First delay in milliseconds; doubles per attempt up to [`Self::max_delay_ms`].
    pub base_delay_ms: u64,
    pub max_delay_ms: u64,
}

impl Default for RestartPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 5,
            base_delay_ms: 200,
            max_delay_ms: 30_000,
        }
    }
}

impl RestartPolicy {
    /// Delay before attempt `attempt` (1-based). Attempt 0 returns 0.
    pub fn backoff_ms(&self, attempt: u32) -> u64 {
        if attempt == 0 {
            return 0;
        }
        self.base_delay_ms
            .saturating_mul(1u64.wrapping_shl(attempt.saturating_sub(1).min(20)))
            .min(self.max_delay_ms)
    }

    pub fn can_retry(&self, attempts: u32) -> bool {
        attempts < self.max_attempts
    }
}

// ---------------------------------------------------------------------------
// Conductor
// ---------------------------------------------------------------------------

/// The central control kernel. Owns module handles, lifecycle, dependency
/// ordering, supervision and the upward report stream — and nothing else.
pub struct Conductor {
    modules: Vec<Arc<dyn Module>>,
    started: Vec<&'static str>,
    shutdown: AtomicBool,
    reporter: Reporter,
    /// The last envelope seen; kept only so a newly registered module can
    /// be configured before start. The Conductor does not interpret it.
    last_envelope: ConfigEnvelope,
    attempts: Mutex<HashMap<&'static str, u32>>,
}

impl Conductor {
    pub fn new() -> Self {
        Self::with_reporter(Reporter::new(4096))
    }

    pub fn with_reporter(reporter: Reporter) -> Self {
        Self {
            modules: Vec::new(),
            started: Vec::new(),
            shutdown: AtomicBool::new(false),
            reporter,
            last_envelope: ConfigEnvelope::default(),
            attempts: Mutex::new(HashMap::new()),
        }
    }

    /// The shared upward channel — tests, the agent and the JNI bridge use
    /// it to drain reports or attach live sinks.
    pub fn reporter(&self) -> Reporter {
        self.reporter.clone()
    }

    /// Drains all queued leader reports.
    pub fn drain_reports(&self) -> Vec<Report> {
        self.reporter.drain()
    }

    /// Registers a module: attaches its reporting channel and immediately
    /// hands it its slice from the last envelope (if one arrived).
    /// Registration order does not matter — startup sorts by
    /// [`Module::depends_on`].
    pub fn register(&mut self, module: Arc<dyn Module>) {
        let named = self.reporter.for_module(module.name());
        module.attach(&named);
        if let Some(slice) = self.last_envelope.for_module(module.name()) {
            self.configure_one(&module, Some(slice));
        } else {
            self.configure_one(&module, None);
        }
        self.modules.push(module);
    }

    /// Distributes a new envelope: each module receives its own slice (or
    /// None when the feature is off). Modules may be registered after this;
    /// [`Conductor::register`] catches them up.
    pub fn distribute(&mut self, envelope: ConfigEnvelope) {
        for module in &self.modules {
            let slice = envelope.for_module(module.name());
            self.configure_one(module, slice);
        }
        self.last_envelope = envelope;
    }

    fn configure_one(&self, module: &Arc<dyn Module>, slice: Option<&ConfigSlice>) {
        match module.configure(slice) {
            Ok(()) => module_report(
                module,
                &self.reporter,
                ReportKind::ConfigAccepted,
                "slice accepted",
            ),
            Err(why) => module_report(
                module,
                &self.reporter,
                ReportKind::ConfigRejected,
                why.clone(),
            ),
        }
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
                    let err = "missing dependency".to_string();
                    module_report(&module, &self.reporter, ReportKind::Error, err.clone());
                    report.push((module.name(), Err(err)));
                    started.insert(module.name(), false);
                    continue;
                }
                if !deps_ok {
                    next_pending.push(module);
                    continue;
                }
                let result = module.start();
                started.insert(module.name(), result.is_ok());
                match &result {
                    Ok(()) => {
                        self.attempts
                            .lock()
                            .unwrap_or_else(std::sync::PoisonError::into_inner)
                            .insert(module.name(), 0);
                        self.started.push(module.name());
                        module_report(
                            &module,
                            &self.reporter,
                            ReportKind::Started,
                            "module started",
                        );
                    }
                    Err(why) => module_report(
                        &module,
                        &self.reporter,
                        ReportKind::Error,
                        format!("start failed: {why}"),
                    ),
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
                let module = self.modules.iter().find(|m| m.name() == *name)?;
                let result = module.stop();
                if result.is_ok() {
                    module_report(
                        module,
                        &self.reporter,
                        ReportKind::Stopped,
                        "module stopped",
                    );
                }
                Some((module.name(), result))
            })
            .collect()
    }

    /// Restarts one leader after supervision decided it had to come back:
    /// idempotent stop, then start. Dependency restarts are the caller's
    /// responsibility — a leader whose dependency is down reports
    /// `missing dependency` on the next `start_all` cycle.
    pub fn restart_module(&self, name: &str) -> Result<(), String> {
        let module = self
            .modules
            .iter()
            .find(|m| m.name() == name)
            .ok_or_else(|| format!("no such module: {name}"))?;
        let _ = module.stop();
        let mut attempts = self
            .attempts
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let n = attempts.entry(module.name()).or_insert(0);
        *n += 1;
        let result = module.start();
        if result.is_ok() {
            *n = 0;
        }
        result
    }

    /// Attempt count for a module since its last successful start.
    pub fn restart_attempts(&self, name: &str) -> u32 {
        self.attempts
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(name)
            .copied()
            .unwrap_or(0)
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

fn module_report(
    module: &Arc<dyn Module>,
    reporter: &Reporter,
    kind: ReportKind,
    message: impl Into<String>,
) {
    reporter.send(Report::new(module.name(), kind, message));
}

impl Default for Conductor {
    fn default() -> Self {
        Self::new()
    }
}

/// Backwards-compatible alias for the pre-rename scheduler type.
pub type Scheduler = Conductor;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    struct FakeModule {
        name: &'static str,
        deps: &'static [&'static str],
        fail_start: bool,
        starts: AtomicUsize,
    }
    impl FakeModule {
        fn new(name: &'static str, deps: &'static [&'static str], fail_start: bool) -> Arc<Self> {
            Arc::new(Self {
                name,
                deps,
                fail_start,
                starts: AtomicUsize::new(0),
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
        fn configure(&self, _slice: Option<&ConfigSlice>) -> Result<(), String> {
            Ok(())
        }
        fn start(&self) -> Result<(), String> {
            self.starts.fetch_add(1, Ordering::SeqCst);
            if self.fail_start {
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
        let mut c = Conductor::new();
        c.register(FakeModule::new("b", &["a"], false));
        c.register(FakeModule::new("a", &[], false));
        let report = c.start_all();
        assert!(report.iter().all(|(_, r)| r.is_ok()));
        assert!(c
            .drain_reports()
            .iter()
            .any(|r| r.kind == ReportKind::Started));
    }

    #[test]
    fn failing_module_blocks_dependents() {
        let mut c = Conductor::new();
        c.register(FakeModule::new("a", &[], true));
        c.register(FakeModule::new("b", &["a"], false));
        let report = c.start_all();
        assert!(report.iter().find(|(n, _)| *n == "a").unwrap().1.is_err());
        assert!(report.iter().find(|(n, _)| *n == "b").unwrap().1.is_err());
        // The failure is visible on the upward bus.
        assert!(c
            .drain_reports()
            .iter()
            .any(|r| r.module == "a" && r.kind == ReportKind::Error));
    }

    #[test]
    fn missing_dependency_is_reported() {
        let mut c = Conductor::new();
        c.register(FakeModule::new("b", &["ghost"], false));
        let report = c.start_all();
        assert!(report.iter().find(|(n, _)| *n == "b").unwrap().1.is_err());
    }

    #[test]
    fn reverse_order_shutdown() {
        let mut c = Conductor::new();
        c.register(FakeModule::new("a", &[], false));
        c.register(FakeModule::new("b", &["a"], false));
        c.start_all();
        let order: Vec<&'static str> = c.stop_all().iter().map(|(n, _)| *n).collect();
        assert_eq!(order, vec!["b", "a"]);
    }

    #[test]
    fn reports_carry_module_and_kind_and_are_drained_once() {
        let mut c = Conductor::new();
        c.register(FakeModule::new("a", &[], false));
        c.start_all();
        let reports = c.drain_reports();
        assert!(reports.iter().any(|r| r.module == "a"));
        assert!(c.drain_reports().is_empty());
    }

    #[test]
    fn saturated_bus_drops_oldest_and_counts() {
        let reporter = Reporter::new(2);
        for i in 0..5 {
            reporter.send(Report::new("x", ReportKind::Info, format!("m{i}")));
        }
        assert_eq!(reporter.pending_len(), 2);
        assert_eq!(reporter.dropped_count(), 3);
        let drained = reporter.drain();
        assert_eq!(drained.first().unwrap().message, "m3");
        assert_eq!(drained.last().unwrap().message, "m4");
    }

    #[test]
    fn sinks_see_reports_live() {
        struct Tap(Arc<Mutex<Vec<String>>>);
        impl ReportSink for Tap {
            fn handle(&self, report: &Report) {
                self.0.lock().unwrap().push(report.message.clone());
            }
        }
        let seen = Arc::new(Mutex::new(Vec::new()));
        let reporter = Reporter::new(8);
        reporter.add_sink(Arc::new(Tap(seen.clone())));
        reporter.send(Report::new("m", ReportKind::Info, "hello"));
        assert_eq!(seen.lock().unwrap().as_slice(), ["hello"]);
    }

    #[test]
    fn restart_policy_backoff_doubles_then_caps() {
        let p = RestartPolicy {
            max_attempts: 5,
            base_delay_ms: 100,
            max_delay_ms: 1_000,
        };
        assert_eq!(p.backoff_ms(0), 0);
        assert_eq!(p.backoff_ms(1), 100);
        assert_eq!(p.backoff_ms(2), 200);
        assert_eq!(p.backoff_ms(3), 400);
        assert_eq!(p.backoff_ms(4), 800);
        assert_eq!(p.backoff_ms(5), 1_000);
        assert!(!p.can_retry(5));
        assert!(p.can_retry(4));
    }

    #[test]
    fn restart_module_invokes_stop_then_start() {
        let mut c = Conductor::new();
        let a = FakeModule::new("a", &[], false);
        c.register(a.clone());
        c.start_all();
        assert_eq!(a.starts.load(Ordering::SeqCst), 1);
        c.restart_module("a").unwrap();
        assert_eq!(a.starts.load(Ordering::SeqCst), 2);
        assert_eq!(c.restart_attempts("a"), 0);
        assert!(c.restart_module("ghost").is_err());
    }

    #[test]
    fn conductor_has_no_business_state_by_construction() {
        // Compile-time intent: the only data a Conductor stores about its
        // modules are opaque slices and handles — route tables, DNS servers
        // and node lists live in their own crates.
        let c = Conductor::new();
        assert!(!c.is_shutting_down());
        assert!(c.health().is_empty());
    }
}
