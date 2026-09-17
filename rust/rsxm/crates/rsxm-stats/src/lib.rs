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

/// A per-connection record — what the UI renders as the live connections panel.
///
/// Design goal: cheap on the hot path (opening / closing a conn is a single
/// Mutex acquire), bounded by a hard cap, and JSON-serializable out of the box.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ConnRecord {
    /// Stable counter-allocated id (monotonic within a StatsModule).
    pub id: u64,
    /// Human-useful endpoint the connection targets (host:port), if known.
    pub target_host: String,
    /// Outbound tag that carried this connection (`"proxy"`, `"direct"`…).
    pub outbound: String,
    /// Bytes uploaded so far on this connection.
    pub up: u64,
    /// Bytes downloaded so far on this connection.
    pub down: u64,
    /// ms epoch when the connection was opened. 0 = unknown.
    pub open_ms: i64,
    /// ms epoch when the connection closed. 0 = still active.
    pub close_ms: i64,
    /// ms epoch of the last time we saw bytes move on this connection.
    pub last_active_ms: i64,
    /// Whether this connection is still open.
    pub active: bool,
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
    /// Currently open connections, capped.
    pub active_connections: Vec<ConnRecord>,
    /// Most recently closed connections (tail of the closed ring), capped.
    pub recent_closed: Vec<ConnRecord>,
}

/// One outbound's byte counters.
#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
pub struct OutboundBytes {
    pub up: u64,
    pub down: u64,
}

/// How many connection records to keep. Beyond this, old closed records
/// get dropped; active ones stay until they close.
const CONN_CAP: usize = 256;

#[derive(Default, Clone)]
struct OutboundMap {
    entries: HashMap<String, OutboundBytes>,
}

#[derive(Default, Clone)]
struct ConnStore {
    next_id: u64,
    /// id → ConnRecord (active + recently closed).
    records: HashMap<u64, ConnRecord>,
    /// Ring of recently closed ids, newest at the tail.
    closed_ring: Vec<u64>,
}

/// The stats leader. Cheap to construct; share behind an `Arc`.
pub struct StatsModule {
    connections_total: AtomicU64,
    connections_active: AtomicI64,
    connections_rejected: AtomicU64,
    uplink_bytes: AtomicU64,
    downlink_bytes: AtomicU64,
    per_outbound: Mutex<OutboundMap>,
    conns: Mutex<ConnStore>,
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
            conns: Mutex::new(ConnStore {
                next_id: 1,
                records: HashMap::new(),
                closed_ring: Vec::new(),
            }),
            last_report_ms: RwLock::new(0),
            // One summary per second at most; the bus is for humans/UI.
            min_report_gap_ms: 1_000,
            reporter: Mutex::new(None),
        }
    }

    /// Open a new connection. Returns the connection id the caller must
    /// carry through `record_conn_bytes` / `close_connection`. The `target`
    /// and `outbound` are stored for UI rendering.
    pub fn open_connection(
        &self,
        target_host: impl Into<String>,
        outbound: impl Into<String>,
        open_ms: i64,
    ) -> u64 {
        self.connections_total.fetch_add(1, Ordering::Relaxed);
        self.connections_active.fetch_add(1, Ordering::Relaxed);
        let mut store = self.conns.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let id = store.next_id;
        store.next_id = store.next_id.saturating_add(1);
        store.records.insert(
            id,
            ConnRecord {
                id,
                target_host: target_host.into(),
                outbound: outbound.into(),
                up: 0,
                down: 0,
                open_ms,
                close_ms: 0,
                last_active_ms: open_ms,
                active: true,
            },
        );
        store.try_shrink();
        id
    }

    /// A connection was accepted into the tunnel (legacy path without per-conn id).
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

    /// Close a specific connection by id (per-conn path).
    pub fn close_connection(&self, id: u64, close_ms: i64) {
        let prev = self.connections_active.load(Ordering::Relaxed);
        if prev > 0 {
            self.connections_active.fetch_sub(1, Ordering::Relaxed);
        }
        let mut store = self.conns.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(mut rec) = store.records.remove(&id) {
            rec.active = false;
            rec.close_ms = close_ms;
            store.closed_ring.push(id);
            // Keep the ring bounded — clone the length first to avoid borrow.
            let ring_len = store.closed_ring.len();
            if ring_len > CONN_CAP {
                let drain_n = ring_len - CONN_CAP;
                store.closed_ring.drain(..drain_n);
            }
            store.records.insert(id, rec);
            store.try_shrink();
        }
    }

    /// A connection was refused by policy (reject rule / security guard).
    pub fn connection_rejected(&self) {
        self.connections_rejected.fetch_add(1, Ordering::Relaxed);
    }

    /// Record relayed bytes, associated with a specific connection id.
    pub fn record_conn_bytes(&self, id: u64, direction: Direction, bytes: u64, now_ms: i64) {
        if bytes == 0 {
            return;
        }
        match direction {
            Direction::Up => self.uplink_bytes.fetch_add(bytes, Ordering::Relaxed),
            Direction::Down => self.downlink_bytes.fetch_add(bytes, Ordering::Relaxed),
        };
        let mut store = self.conns.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(rec) = store.records.get_mut(&id) {
            match direction {
                Direction::Up => rec.up = rec.up.saturating_add(bytes),
                Direction::Down => rec.down = rec.down.saturating_add(bytes),
            }
            rec.last_active_ms = now_ms;
            // Per-outbound break-down — the outbound tag lives on the record.
            if let Ok(mut map) = self.per_outbound.lock() {
                let entry = map
                    .entries
                    .entry(rec.outbound.clone())
                    .or_default();
                match direction {
                    Direction::Up => entry.up = entry.up.saturating_add(bytes),
                    Direction::Down => entry.down = entry.down.saturating_add(bytes),
                }
            }
        }
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

    /// A consistent snapshot of every counter + per-connection detail.
    pub fn snapshot(&self) -> StatsSnapshot {
        let per_outbound = self
            .per_outbound
            .lock()
            .map(|m| m.entries.clone())
            .unwrap_or_default();
        let store = self.conns.lock().map(|s| s.clone()).unwrap_or_default();
        let active_connections: Vec<ConnRecord> = store
            .records
            .values()
            .filter(|r| r.active)
            .cloned()
            .collect();
        let recent_closed: Vec<ConnRecord> = store
            .closed_ring
            .iter()
            .filter_map(|id| store.records.get(id).cloned())
            .collect();
        StatsSnapshot {
            connections_total: self.connections_total.load(Ordering::Relaxed),
            connections_active: self.connections_active.load(Ordering::Relaxed),
            connections_rejected: self.connections_rejected.load(Ordering::Relaxed),
            uplink_bytes: self.uplink_bytes.load(Ordering::Relaxed),
            downlink_bytes: self.downlink_bytes.load(Ordering::Relaxed),
            per_outbound,
            active_connections,
            recent_closed,
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
        if let Ok(mut store) = self.conns.lock() {
            store.records.clear();
            store.closed_ring.clear();
            store.next_id = 1;
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
        let n_active = snap.active_connections.len();
        let message = format!(
            "active={} total={} rejected={} up={} down={} open_conns={}",
            snap.connections_active,
            snap.connections_total,
            snap.connections_rejected,
            snap.uplink_bytes,
            snap.downlink_bytes,
            n_active,
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

    fn supports_hot_configure(&self) -> bool {
        // Stats keeps no OS resources — reload is a no-op configure.
        true
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

impl ConnStore {
    /// Drop the oldest closed records when we overflow. Active records
    /// always stay — the cap only applies to the closed tail.
    fn try_shrink(&mut self) {
        if self.records.len() <= CONN_CAP {
            return;
        }
        let overflow = self.records.len() - CONN_CAP;
        // Delete closed records first (those at the front of closed_ring).
        for &id in self.closed_ring.iter().take(overflow) {
            self.records.remove(&id);
        }
        self.closed_ring.retain(|id| self.records.contains_key(id));
        // If still overflow (more active than cap — unusual), drop the
        // oldest active too. In practice CONN_CAP = 256 is plenty.
        while self.records.len() > CONN_CAP {
            // Drop an arbitrary active record — oldest by open_ms would be
            // nicer but we don't maintain that index.
            let victim = *self.records.keys().next().expect("non-empty");
            self.records.remove(&victim);
        }
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
    fn per_connection_path_tracks_individual() {
        let stats = StatsModule::new();
        let a = stats.open_connection("google.com:443", "proxy", 1000);
        let b = stats.open_connection("github.com:443", "direct", 1100);
        stats.record_conn_bytes(a, Direction::Up, 200, 1200);
        stats.record_conn_bytes(a, Direction::Down, 800, 1210);
        stats.record_conn_bytes(b, Direction::Down, 50, 1300);

        let snap = stats.snapshot();
        assert_eq!(snap.connections_active, 2);
        assert_eq!(snap.active_connections.len(), 2);
        let ga = snap.active_connections.iter().find(|r| r.id == a).unwrap();
        assert_eq!(ga.up, 200);
        assert_eq!(ga.down, 800);
        assert!(ga.active);
        let gb = snap.active_connections.iter().find(|r| r.id == b).unwrap();
        assert_eq!(gb.outbound, "direct");

        stats.close_connection(a, 1500);
        let snap2 = stats.snapshot();
        assert_eq!(snap2.connections_active, 1);
        assert_eq!(snap2.active_connections.len(), 1);
        assert_eq!(snap2.recent_closed.len(), 1);
        let closed_a = snap2.recent_closed.first().unwrap();
        assert!(!closed_a.active);
        assert_eq!(closed_a.close_ms, 1500);
        assert_eq!(closed_a.target_host, "google.com:443");
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
        let _ = stats.open_connection("x:443", "proxy", 0);
        stats.reset();
        assert_eq!(stats.snapshot(), StatsSnapshot::default());
    }

    #[test]
    fn connection_store_caps_at_conn_cap() {
        let stats = StatsModule::new();
        let ids: Vec<u64> = (0..CONN_CAP + 10)
            .map(|i| stats.open_connection(format!("h{i}:443"), "proxy", i as i64))
            .collect();
        // Close half — they move to the closed ring but stay in records.
        for &id in ids.iter().take(CONN_CAP / 2) {
            stats.close_connection(id, 10_000);
        }
        let snap = stats.snapshot();
        // The store must have been pruned down to CONN_CAP entries (active + closed combined).
        assert!(
            snap.active_connections.len() + snap.recent_closed.len() <= CONN_CAP,
            "total records must be bounded by CONN_CAP"
        );
    }
}
