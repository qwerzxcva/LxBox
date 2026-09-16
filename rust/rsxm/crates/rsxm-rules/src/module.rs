//! The `rsxm-rules` micro-kernel: a [`Module`] wrapper around the compiled
//! [`RuleTable`].
//!
//! The Conductor hands this leader only its own slice (`{"enabled",
//! "rules"}`); everything else in AppState is invisible here. The compiled
//! table is shared behind an `Arc` so the TUN/dialer hot path can take a
//! direct typed handle without the Conductor ever sitting on it.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde::Deserialize;

use crate::{Rule, RuleTable};

#[derive(Debug, Deserialize)]
struct RulesSlice {
    #[serde(default = "default_true")]
    enabled: bool,
    #[serde(default)]
    rules: Vec<Rule>,
}

fn default_true() -> bool {
    true
}

/// The routing leader.
pub struct RulesModule {
    table: RwLock<Option<Arc<RuleTable>>>,
    reporter: Mutex<Option<ModuleReporter>>,
    configured_rules: AtomicUsize,
}

impl RulesModule {
    pub fn new() -> Self {
        Self {
            table: RwLock::new(None),
            reporter: Mutex::new(None),
            configured_rules: AtomicUsize::new(0),
        }
    }

    /// The current compiled table. `None` until a slice arrives. The Arc is
    /// the hot-path handle: clone it, match against it, drop it — a config
    /// reload swaps a new table in without blocking readers.
    pub fn table(&self) -> Option<Arc<RuleTable>> {
        self.table.read().expect("rules table poisoned").clone()
    }

    /// Number of rules accepted into the last compiled table.
    pub fn rule_count(&self) -> usize {
        self.configured_rules.load(Ordering::Relaxed)
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
}

impl Default for RulesModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for RulesModule {
    fn name(&self) -> &'static str {
        "rsxm-rules"
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            *self.table.write().expect("rules table poisoned") = None;
            self.configured_rules.store(0, Ordering::Relaxed);
            return Ok(());
        };
        let parsed: RulesSlice =
            serde_json::from_value((*slice.value).clone()).map_err(|e| e.to_string())?;
        if !parsed.enabled {
            *self.table.write().expect("rules table poisoned") = None;
            self.configured_rules.store(0, Ordering::Relaxed);
            self.report(ReportKind::ConfigAccepted, "routing disabled");
            return Ok(());
        }
        let incoming = parsed.rules.len();
        let table = Arc::new(RuleTable::build(parsed.rules));
        self.configured_rules
            .store(table.rule_count(), Ordering::Relaxed);
        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "{} rules compiled ({} pruned)",
                table.rule_count(),
                incoming.saturating_sub(table.rule_count())
            ),
        );
        *self.table.write().expect("rules table poisoned") = Some(table);
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        if self.table.read().expect("rules table poisoned").is_some() {
            Health::Up
        } else {
            // Routing without a table means "no native decision"; not fatal
            // while the engine falls back, but the boss should see it.
            Health::Degraded("no rules slice")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsxm_core::{Conductor, ConfigEnvelope};
    use serde_json::json;

    #[test]
    fn configures_from_its_slice_and_exposes_table() {
        let module = Arc::new(RulesModule::new());
        let mut conductor = Conductor::new();
        conductor.register(module.clone());
        conductor.distribute(ConfigEnvelope::new(vec![ConfigSlice::new(
            "rsxm-rules",
            json!({
                "enabled": true,
                "rules": [
                    { "id": "r1", "suffixes": ["example.com"], "target": { "Outbound": "proxy" } }
                ]
            }),
        )]));
        let table = module.table().expect("table compiled");
        assert_eq!(table.rule_count(), 1);
        assert_eq!(module.health(), Health::Up);

        // The Conductor itself only sees opaque JSON; the leader reads it.
        let reports = conductor.drain_reports();
        assert!(reports
            .iter()
            .any(|r| r.module == "rsxm-rules" && r.kind == ReportKind::ConfigAccepted));
    }

    #[test]
    fn disabled_slice_clears_table() {
        let module = RulesModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-rules",
                json!({ "enabled": false, "rules": [] }),
            )))
            .unwrap();
        assert!(module.table().is_none());
        assert_eq!(module.health(), Health::Degraded("no rules slice"));
    }

    #[test]
    fn no_slice_keeps_module_startable_without_rules() {
        let module = RulesModule::new();
        module.configure(None).unwrap();
        assert!(module.start().is_ok());
        assert!(module.table().is_none());
    }
}
