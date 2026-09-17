//! The `rsxm-dialer` micro-kernel: outbound inventory + dial policy.
//!
//! The slice this leader owns is the outbounds section (nodes and groups).
//! It validates the inventory at configure time, exposes the currently
//! selected outbound for the packet path, and reports reachability of the
//! dial plan — it never sees routes or DNS content.

use std::collections::HashSet;
use std::sync::{Mutex, RwLock};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde_json::Value;

/// Protocol types that need a real server endpoint to be dialable.
const NETWORK_OUTBOUNDS: &[&str] = &[
    "vless",
    "vmess",
    "trojan",
    "shadowsocks",
    "ss",
    "ss2022",
    "tuic",
    "hysteria",
    "hysteria2",
    "wireguard",
    "http",
    "socks",
];

/// One outbound node in the compiled inventory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboundInfo {
    pub id: String,
    pub kind: String,
    pub server: Option<String>,
    pub port: Option<u16>,
}

#[derive(Debug, Default)]
struct DialerPolicy {
    outbounds: Vec<OutboundInfo>,
    /// IDs carried by groups (selectors/url-tests) — the selection target
    /// may legitimately be a group instead of a node.
    groups: Vec<String>,
    selected: Option<String>,
    mux: String,
    udp_over_tcp: bool,
}

impl DialerPolicy {
    fn knows_target(&self, target: &str) -> bool {
        self.outbounds.iter().any(|o| o.id == target) || self.groups.iter().any(|g| g == target)
    }
}

/// The dialer leader.
pub struct DialerModule {
    policy: RwLock<DialerPolicy>,
    reporter: Mutex<Option<ModuleReporter>>,
}

impl DialerModule {
    pub fn new() -> Self {
        Self {
            policy: RwLock::new(DialerPolicy::default()),
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

    /// Number of parsed node outbounds.
    pub fn outbound_count(&self) -> usize {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .outbounds
            .len()
    }

    /// The selected outbound/group tag, when one is configured and known.
    pub fn selected_outbound(&self) -> Option<String> {
        let policy = self
            .policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let tag = policy.selected.as_ref()?;
        policy.knows_target(tag).then(|| tag.clone())
    }

    /// Configured multiplexing protocol (`"none"` when off).
    pub fn mux_protocol(&self) -> String {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .mux
            .clone()
    }

    /// Whether UDP-over-TCP fallback is enabled.
    pub fn udp_over_tcp(&self) -> bool {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .udp_over_tcp
    }
}

impl Default for DialerModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for DialerModule {
    fn name(&self) -> &'static str {
        "rsxm-dialer"
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            *self
                .policy
                .write()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = DialerPolicy::default();
            return Ok(());
        };
        let value: &Value = &slice.value;

        let mut outbounds = Vec::new();
        let mut malformed = 0usize;
        let mut duplicate_ids: HashSet<String> = HashSet::new();
        if let Some(items) = value.get("outbounds").and_then(Value::as_array) {
            for item in items {
                if item
                    .get("enabled")
                    .and_then(Value::as_bool)
                    .is_some_and(|v| !v)
                {
                    continue;
                }
                let Some(id) = item.get("id").and_then(Value::as_str) else {
                    malformed += 1;
                    continue;
                };
                if !duplicate_ids.insert(id.to_string()) {
                    // Duplicate node ids make selection ambiguous.
                    self.report(
                        ReportKind::Warn,
                        format!("duplicate outbound id skipped: {id}"),
                    );
                    continue;
                }
                let kind = item
                    .get("type")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown")
                    .to_ascii_lowercase();
                let server = item
                    .get("server")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .map(str::to_string);
                let port = item
                    .get("serverPort")
                    .or_else(|| item.get("port"))
                    .and_then(Value::as_u64)
                    .and_then(|p| u16::try_from(p).ok());
                if NETWORK_OUTBOUNDS.contains(&kind.as_str())
                    && (server.is_none() || port.is_none())
                {
                    self.report(
                        ReportKind::Warn,
                        format!("outbound '{id}' ({kind}) missing server/port"),
                    );
                }
                outbounds.push(OutboundInfo {
                    id: id.to_string(),
                    kind,
                    server,
                    port,
                });
            }
        }

        let mut groups = Vec::new();
        if let Some(items) = value.get("groups").and_then(Value::as_array) {
            for item in items {
                let tag = item
                    .get("name")
                    .or_else(|| item.get("tag"))
                    .or_else(|| item.get("id"))
                    .and_then(Value::as_str);
                if let Some(tag) = tag {
                    groups.push(tag.to_string());
                }
            }
        }

        let selected = value
            .get("selectedOutbound")
            .and_then(Value::as_str)
            .map(str::to_string);
        let mux = value
            .get("muxProtocol")
            .and_then(Value::as_str)
            .unwrap_or("none")
            .to_string();
        let udp_over_tcp = value
            .get("udpOverTcp")
            .and_then(Value::as_bool)
            .unwrap_or(false);

        if let Some(tag) = &selected {
            let known = outbounds.iter().any(|o| &o.id == tag) || groups.iter().any(|g| g == tag);
            if !known {
                self.report(
                    ReportKind::Warn,
                    format!("selected outbound '{tag}' is not in the inventory"),
                );
            }
        }
        if malformed > 0 {
            self.report(
                ReportKind::Warn,
                format!("{malformed} malformed outbound entries skipped"),
            );
        }

        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "dial plan: {} nodes, {} groups, selected={}, mux={}",
                outbounds.len(),
                groups.len(),
                selected.as_deref().unwrap_or("<none>"),
                mux
            ),
        );

        *self
            .policy
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = DialerPolicy {
            outbounds,
            groups,
            selected,
            mux,
            udp_over_tcp,
        };
        Ok(())
    }

    fn supports_hot_configure(&self) -> bool {
        // DialerPolicy swap is behind a RwLock — no OS resources to teardown.
        true
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        if self.outbound_count() == 0 {
            // No nodes is fine during bring-up, but nothing can carry
            // traffic until a slice with outbounds arrives.
            Health::Degraded("no outbounds configured")
        } else {
            Health::Up
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsxm_core::{Conductor, ConfigEnvelope, ReportKind};
    use serde_json::json;
    use std::sync::Arc;

    #[test]
    fn parses_inventory_and_selection() {
        let module = Arc::new(DialerModule::new());
        let mut conductor = Conductor::new();
        conductor.register(module.clone() as Arc<dyn Module>);
        conductor.distribute(ConfigEnvelope::new(vec![ConfigSlice::new(
            "rsxm-dialer",
            json!({
                "outbounds": [
                    { "id": "n1", "type": "vless", "server": "a.example", "serverPort": 443 },
                    { "id": "n2", "type": "trojan", "server": "b.example", "serverPort": 443 },
                    { "id": "off", "enabled": false, "type": "vless" },
                    { "type": "vless" }
                ],
                "groups": [{ "name": "auto", "type": "urltest" }],
                "selectedOutbound": "auto"
            }),
        )]));
        assert_eq!(module.outbound_count(), 2);
        assert_eq!(module.selected_outbound().as_deref(), Some("auto"));
        assert!(conductor
            .drain_reports()
            .iter()
            .any(|r| r.kind == ReportKind::ConfigAccepted));
    }

    #[test]
    fn missing_selection_target_warns_and_resolves_none() {
        let module = DialerModule::new();
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-dialer",
                json!({
                    "outbounds": [{ "id": "n1", "type": "vless", "server": "x", "serverPort": 443 }],
                    "selectedOutbound": "ghost"
                }),
            )))
            .unwrap();
        assert_eq!(module.selected_outbound(), None);
    }

    #[test]
    fn empty_inventory_is_degraded() {
        let module = DialerModule::new();
        module.configure(None).unwrap();
        assert_eq!(module.health(), Health::Degraded("no outbounds configured"));
    }
}
