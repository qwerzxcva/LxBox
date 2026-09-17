//! The `rsxm-dns` micro-kernel: a [`Module`] wrapper around the pure
//! [`DnsEngine`].
//!
//! The slice this leader receives is the DNS section only: servers, rules,
//! fake-IP ranges, cache capacity. It translates AppState-shaped JSON into
//! the engine's policy types itself — the Conductor never opens this box.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::{Mutex, RwLock};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde_json::Value;

use crate::{DnsAction, DnsEngine, DnsRouteRule, DnsRouter, FakeIpConfig};

/// The DNS leader.
pub struct DnsModule {
    engine: RwLock<Option<DnsEngine>>,
    reporter: Mutex<Option<ModuleReporter>>,
}

impl DnsModule {
    pub fn new() -> Self {
        Self {
            engine: RwLock::new(None),
            reporter: Mutex::new(None),
        }
    }

    /// Runs a read-only decision against the configured engine. The
    /// engine takes a short write lock only for LRU bookkeeping; the policy
    /// itself is allocation-free on the hot path.
    pub fn with_engine<R>(&self, f: impl FnOnce(&mut DnsEngine) -> R) -> Option<R> {
        self.engine
            .write()
            .expect("dns engine poisoned")
            .as_mut()
            .map(f)
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

impl Default for DnsModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for DnsModule {
    fn name(&self) -> &'static str {
        "rsxm-dns"
    }

    fn depends_on(&self) -> &'static [&'static str] {
        // Resolver selection can be steered by routing policy later; the
        // dependency is informational until dial-time resolution lands.
        &["rsxm-rules"]
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            *self.engine.write().expect("dns engine poisoned") = None;
            return Ok(());
        };
        let value: &Value = &slice.value;
        let enabled = value
            .get("enabled")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        if !enabled {
            *self.engine.write().expect("dns engine poisoned") = None;
            return Ok(());
        }

        let fake_enabled = value
            .get("enableFakeIp")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let v4 = parse_v4_range(
            value
                .get("fakeIpInet4Range")
                .and_then(Value::as_str)
                .unwrap_or("198.18.0.0/15"),
        );
        let v6 = parse_v6_range(
            value
                .get("fakeIpInet6Range")
                .and_then(Value::as_str)
                .unwrap_or("fc00::/18"),
        );
        let capacity = value
            .get("dnsCacheCapacity")
            .and_then(Value::as_u64)
            .unwrap_or(4096) as usize;

        let rules = value
            .get("dnsRules")
            .and_then(Value::as_array)
            .map(|a| parse_dns_rules(a.as_slice()))
            .unwrap_or_default();
        let hosts = value
            .get("dnsServers")
            .and_then(Value::as_array)
            .map(|a| parse_hosts(a.as_slice()))
            .unwrap_or_default();

        let host_count = hosts.len();
        let engine = DnsEngine::with_policy(
            FakeIpConfig {
                enabled: fake_enabled,
                v4_base: v4.0,
                v4_prefix: v4.1,
                v6_base: v6.0,
                v6_prefix: v6.1,
            },
            DnsRouter::new(rules.0),
            hosts,
            capacity,
        );
        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "dns policy ready: {} rules, {} hosts, fakeip={}, cap={}",
                rules.1, host_count, fake_enabled, capacity
            ),
        );
        if rules.2 > 0 {
            self.report(
                ReportKind::Warn,
                format!("{} malformed dns rules skipped", rules.2),
            );
        }
        *self.engine.write().expect("dns engine poisoned") = Some(engine);
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        if self.engine.read().expect("dns engine poisoned").is_some() {
            Health::Up
        } else {
            Health::Degraded("no dns slice")
        }
    }
}

fn split_prefix(text: &str) -> Option<(&str, u8)> {
    let (addr, prefix) = text.split_once('/')?;
    Some((addr.trim(), prefix.trim().parse::<u8>().ok()?))
}

fn parse_v4_range(text: &str) -> (Ipv4Addr, u8) {
    if let Some((addr, prefix)) = split_prefix(text) {
        if let (Ok(addr), true) = (addr.parse::<Ipv4Addr>(), (0..=32).contains(&prefix)) {
            return (addr, prefix);
        }
    }
    (Ipv4Addr::new(198, 18, 0, 0), 15)
}

fn parse_v6_range(text: &str) -> (Ipv6Addr, u8) {
    if let Some((addr, prefix)) = split_prefix(text) {
        if let (Ok(addr), true) = (addr.parse::<Ipv6Addr>(), (0..=128).contains(&prefix)) {
            return (addr, prefix);
        }
    }
    ("fc00::".parse().expect("static v6"), 18)
}

/// Parses AppState-shaped dnsRules into engine rules.
///
/// Returns (rules, accepted, malformed). Disabled rules and rules with no
/// action are skipped (the app already gates them, but the leader trusts
/// only its own reading of its slice).
fn parse_dns_rules(items: &[Value]) -> (Vec<DnsRouteRule>, usize, usize) {
    let mut rules = Vec::new();
    let mut malformed = 0usize;
    for item in items {
        if item
            .get("enabled")
            .and_then(Value::as_bool)
            .is_some_and(|v| !v)
        {
            continue;
        }
        let action = match item
            .get("action")
            .and_then(Value::as_str)
            .unwrap_or("route")
        {
            "reject" => DnsAction::Reject,
            _ => match item.get("server").and_then(Value::as_str) {
                Some(tag) if !tag.is_empty() => DnsAction::Route(tag.to_string()),
                // route-options/route without a target cannot steer: skip.
                _ => {
                    malformed += 1;
                    continue;
                }
            },
        };
        rules.push(DnsRouteRule {
            domains: string_list(item, "domain"),
            suffixes: string_list(item, "domainSuffix"),
            keywords: string_list(item, "domainKeyword"),
            regexes: string_list(item, "domainRegex"),
            query_types: string_list(item, "queryType"),
            packages: string_list(item, "packageName"),
            action,
        });
    }
    let accepted = rules.len();
    (rules, accepted, malformed)
}

fn string_list(item: &Value, key: &str) -> Vec<String> {
    match item.get(key) {
        Some(Value::String(s)) => vec![s.clone()],
        Some(Value::Array(a)) => a
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_string)
            .collect(),
        _ => Vec::new(),
    }
}

/// Extracts hosts entries from `type = "hosts"` DNS servers: `"domain=ip"`.
fn parse_hosts(servers: &[Value]) -> Vec<(String, IpAddr)> {
    let mut hosts = Vec::new();
    for server in servers {
        if server
            .get("enabled")
            .and_then(Value::as_bool)
            .is_some_and(|v| !v)
        {
            continue;
        }
        let is_hosts = server
            .get("type")
            .and_then(Value::as_str)
            .is_some_and(|t| t.eq_ignore_ascii_case("hosts"));
        if !is_hosts {
            continue;
        }
        if let Some(entries) = server.get("hostsEntries").and_then(Value::as_array) {
            for entry in entries.iter().filter_map(Value::as_str) {
                let Some((name, ip)) = entry.split_once('=') else {
                    continue;
                };
                if let Ok(ip) = ip.trim().parse::<IpAddr>() {
                    hosts.push((name.trim().to_string(), ip));
                }
            }
        }
    }
    hosts
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    use crate::{Decision, Family};
    use rsxm_core::{Conductor, ConfigEnvelope};
    use serde_json::json;

    #[test]
    fn builds_engine_from_appstate_slice() {
        let module = Arc::new(DnsModule::new());
        let mut conductor = Conductor::new();
        conductor.register(module.clone());
        conductor.distribute(ConfigEnvelope::new(vec![ConfigSlice::new(
            "rsxm-dns",
            json!({
                "enabled": true,
                "enableFakeIp": false,
                "dnsCacheCapacity": 128,
                "dnsRules": [
                    { "id": "r1", "enabled": true, "action": "route",
                      "server": "local", "domainSuffix": ["home.lan"] },
                    { "id": "r2", "enabled": true, "action": "reject",
                      "domain": ["ads.tracker"] },
                    { "id": "off", "enabled": false, "server": "x",
                      "domain": ["nope.x"] }
                ],
                "dnsServers": [
                    { "id": "h", "enabled": true, "type": "hosts",
                      "hostsEntries": ["router.home.lan=192.168.1.1"] }
                ]
            }),
        )]));

        assert_eq!(module.health(), Health::Up);
        module
            .with_engine(|engine| {
                let decision =
                    engine.decide_routed("api.home.lan", Family::V4, None, 0, Some("A"), None);
                assert_eq!(
                    decision,
                    Decision::ForwardToServer {
                        server: "local".into()
                    }
                );
                assert_eq!(
                    engine.decide("ads.tracker", Family::V4, None, 0),
                    Decision::Reject
                );
                assert_eq!(
                    engine.decide("router.home.lan", Family::V4, None, 0),
                    Decision::AnswerHosts {
                        address: "192.168.1.1".parse().unwrap()
                    }
                );
            })
            .expect("engine configured");
        assert!(conductor
            .drain_reports()
            .iter()
            .any(|r| r.kind == ReportKind::ConfigAccepted));
    }

    #[test]
    fn no_slice_leaves_engine_absent() {
        let module = DnsModule::new();
        module.configure(None).unwrap();
        assert!(module.with_engine(|_| ()).is_none());
        assert_eq!(module.health(), Health::Degraded("no dns slice"));
    }
}
