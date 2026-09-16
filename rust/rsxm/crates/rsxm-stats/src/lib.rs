//! RSXM stats micro-kernel — the connection-metering leader.
//!
//! Every other module reports *events* upward; this leader counts the
//! traffic itself. It deliberately owns no clocks and never blocks: the
//! packet/dial path calls lock-free atomic adds, and the slower per-outbound
//! breakdown sits behind a short critical section the hot path avoids by
//! passing the outbound tag straight through.
//!
//! The Conductor never sees per-connection detail — only a throttled
//! [`ReportKind::Stats`] summary the UI can render.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::{Mutex, RwLock};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde::Serialize;

/// Which way the bytes flowed, from the device's point of view.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    /// Device → remote (upload).
    Up,
    /// Remote → device (download).
    Down,
}

/// A point-in-time counters snapshot, JSON-ready for the UI bridge.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
pub struct StatsSnapshot {
    pub connections_total: u64,
    pub connections_active: i64,
    pub connections_rejected: u64,
    pub uplink_bytes: u64,
    pub downlink_bytes: u64,
    /// Bytes per outbound tag: `{"proxy": {"up": n, "down": m}}`.
    pub per_outbound: HashMap<String, OutboundBytes>,
}

/// One outbound's byte counters.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
pub struct OutboundBytes {
    pub up: u64,
    pub down: u64,
}

#[derive(Default)]
struct OutboundMap {
    entries: HashMap<String, OutboundBytes>,
}

/// The stats leader. Cheap to construct; share behind an `Arc`.
pub struct StatsModule {
    connections_total: AtomicU64,
    connections_active: AtomicI64,
    connections_rejected: AtomicU64,
    uplink_bytes: AtomicU64,
    downlink_bytes: AtomicU64,
    per_outbound: Mutex<OutboundMap>,
    /// Throttle wall for the periodic summary report; caller-supplied time.
    last_report_ms: RwLock<i64>,
    min_report_gap_ms: i64,
    reporter: Mutex<Option<ModuleReporter>>,
}

impl StatsModule {
    pub fn new() -> Self {
        Self {
            connections_total: AtomicU64::new(0),
            connections_active: AtomicI64::new(0),
            connections_rejected: AtomicU64::new(0),
            uplink_bytes: AtomicU64::new(0),
            downlink_bytes: AtomicU64::new(0),
            per_outbound: Mutex::new(OutboundMap::default()),
            last_report_ms: RwLock::new(0),
            // One summary per second at most; the bus is for humans/UI.
            min_report_gap_ms: 1_000,
            reporter: Mutex::new(None),
        }
    }

    /// A connection was accepted into the tunnel.
    pub fn connection_opened(&self) {
        self.connections_total.fetch_add(1, Ordering::Relaxed);
        self.connections_active.fetch_add(1, Ordering::Relaxed);
    }

    /// A previously accepted connection ended.
    pub fn connection_closed(&self) {
        // Never go negative even if the native layer replays a close.
        let prev = self.connections_active.load(Ordering::Relaxed);
        if prev > 0 {
            self.connections_active.fetch_sub(1, Ordering::Relaxed);
        }
    }

    /// A connection was refused by policy (reject rule / security guard).
    pub fn connection_rejected(&self) {
        self.connections_rejected.fetch_add(1, Ordering::Relaxed);
    }

    /// Records relayed bytes. The `outbound` breakdown is best-effort:
    /// under mutex poisoning the global counters still advance.
    pub fn record_bytes(&self, direction: Direction, outbound: &str, bytes: u64) {
        if bytes == 0 {
            return;
        }
        match direction {
            Direction::Up => {
                self.uplink_bytes.fetch_add(bytes, Ordering::Relaxed);
            }
            Direction::Down => {
                self.downlink_bytes.fetch_add(bytes, Ordering::Relaxed);
            }
        }
        if let Ok(mut map) = self.per_outbound.lock() {
            let entry = map.entries.entry(outbound.to_string()).or_default();
            match direction {
                Direction::Up => entry.up = entry.up.saturating_add(bytes),
                Direction::Down => entry.down = entry.down.saturating_add(bytes),
            }
        }
    }

    /// A consistent snapshot of every counter.
    pub fn snapshot(&self) -> StatsSnapshot {
        let per_outbound = self
            .per_outbound
            .lock()
            .map(|m| m.entries.clone())
            .unwrap_or_default();
        StatsSnapshot {
            connections_total: self.connections_total.load(Ordering::Relaxed),
            connections_active: self.connections_active.load(Ordering::Relaxed),
            connections_rejected: self.connections_rejected.load(Ordering::Relaxed),
            uplink_bytes: self.uplink_bytes.load(Ordering::Relaxed),
            downlink_bytes: self.downlink_bytes.load(Ordering::Relaxed),
            per_outbound,
        }
    }

    /// Zeroes every counter (new tunnel session / manual UI reset).
    pub fn reset(&self) {
        self.connections_total.store(0, Ordering::Relaxed);
        self.connections_active.store(0, Ordering::Relaxed);
        self.connections_rejected.store(0, Ordering::Relaxed);
        self.uplink_bytes.store(0, Ordering::Relaxed);
        self.downlink_bytes.store(0, Ordering::Relaxed);
        if let Ok(mut map) = self.per_outbound.lock() {
            map.entries.clear();
        }
    }

    /// Emits a Stats summary at most once per throttle window. Returns true
    /// when a report actually went out — the embedding runtime can use it
    /// to pace its UI wakeups (fewer wakeups = less battery).
    pub fn maybe_report(&self, now_ms: i64) -> bool {
        let last = *self
            .last_report_ms
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if now_ms - last < self.min_report_gap_ms {
            return false;
        }
        *self
            .last_report_ms
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = now_ms;
        let snap = self.snapshot();
        let message = format!(
            "active={} total={} rejected={} up={} down={}",
            snap.connections_active,
            snap.connections_total,
            snap.connections_rejected,
            snap.uplink_bytes,
            snap.downlink_bytes
        );
        if let Some(reporter) = self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
        {
            reporter.send(ReportKind::Stats, message);
        }
        true
    }
}

impl Default for StatsModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for StatsModule {
    fn name(&self) -> &'static str {
        "rsxm-stats"
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    /// The meter needs no configuration slice; presence of the module is
    /// enough. A slice, if one ever arrives, is accepted without reading —
    /// the Conductor still must not parse it, and stats owns no policy.
    fn configure(&self, _slice: Option<&ConfigSlice>) -> Result<(), String> {
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
    use rsxm_core::Conductor;
    use std::sync::Arc;

    #[test]
    fn counts_connections_and_bytes() {
        let stats = StatsModule::new();
        stats.connection_opened();
        stats.connection_opened();
        stats.connection_closed();
        stats.connection_rejected();
        stats.record_bytes(Direction::Up, "proxy", 100);
        stats.record_bytes(Direction::Down, "proxy", 400);
        stats.record_bytes(Direction::Up, "direct", 10);

        let snap = stats.snapshot();
        assert_eq!(snap.connections_total, 2);
        assert_eq!(snap.connections_active, 1);
        assert_eq!(snap.connections_rejected, 1);
        assert_eq!(snap.uplink_bytes, 110);
        assert_eq!(snap.downlink_bytes, 400);
        assert_eq!(
            snap.per_outbound.get("proxy").unwrap(),
            &OutboundBytes { up: 100, down: 400 }
        );
    }

    #[test]
    fn close_never_drives_active_negative() {
        let stats = StatsModule::new();
        stats.connection_closed();
        stats.connection_closed();
        assert_eq!(stats.snapshot().connections_active, 0);
    }

    #[test]
    fn reports_are_throttled_and_routed_to_the_bus() {
        let stats = Arc::new(StatsModule::new());
        let mut conductor = Conductor::new();
        conductor.register(stats.clone() as Arc<dyn Module>);
        stats.connection_opened();
        assert!(stats.maybe_report(1_000));
        // Same window: no second report.
        assert!(!stats.maybe_report(1_500));
        assert!(stats.maybe_report(2_001));
        let reports = conductor.drain_reports();
        let stats_reports: Vec<_> = reports
            .iter()
            .filter(|r| r.kind == ReportKind::Stats)
            .collect();
        assert_eq!(stats_reports.len(), 2);
        assert_eq!(stats_reports[0].module, "rsxm-stats");
    }

    #[test]
    fn reset_clears_every_counter() {
        let stats = StatsModule::new();
        stats.connection_opened();
        stats.record_bytes(Direction::Down, "proxy", 999);
        stats.reset();
        assert_eq!(stats.snapshot(), StatsSnapshot::default());
    }
}
