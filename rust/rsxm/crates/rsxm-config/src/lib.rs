//! rsxm-config: the ONLY place that understands the whole AppState JSON.
//!
//! The central kernel (rsxm-core) is a boss, not a reader: it never parses
//! route rules, TUN parameters or DNS server lists. This crate splits the
//! full JSON document into per-module slices — each micro-kernel receives
//! exactly its own section and deserialises it itself, so no kernel (and no
//! the scheduler) ever loads the whole document.
//!
//! Slice keys are module names. Adding a micro-kernel means adding one
//! `extract_*` here and nothing anywhere else.

use serde_json::{json, Map, Value};

/// One module's configuration slice. The content is opaque to everyone
/// except the module it is addressed to.
#[derive(Debug, Clone)]
pub struct ConfigSlice {
    pub module: &'static str,
    pub value: std::sync::Arc<Value>,
}

/// The envelope the scheduler hands around: slices addressed to modules,
/// plus a small manifest the scheduler is allowed to look at (which modules
/// are enabled — that is scheduling data, not business data).
#[derive(Debug, Clone, Default)]
pub struct ConfigEnvelope {
    slices: Vec<ConfigSlice>,
}

impl ConfigEnvelope {
    pub fn slices(&self) -> &[ConfigSlice] {
        &self.slices
    }

    /// The slice addressed to `module`, if the document carried one.
    pub fn for_module(&self, module: &str) -> Option<&ConfigSlice> {
        self.slices.iter().find(|s| s.module == module)
    }

    fn push(&mut self, module: &'static str, value: Value) {
        self.slices.push(ConfigSlice {
            module,
            value: std::sync::Arc::new(value),
        });
    }
}

/// Splits a full AppState JSON document into module slices.
///
/// Unknown top-level keys are dropped on purpose: the scheduler never sees
/// them, and a micro-kernel that needs a new section gets its own extractor
/// here. Feature gates follow the Android AppState fields verbatim: a
/// module whose feature is off receives an `{"enabled": false}` slice (or
/// none at all, for modules that are optional) so it can decide to stay
/// down — the scheduler only runs what is enabled.
pub fn split(root: &Value) -> ConfigEnvelope {
    let mut env = ConfigEnvelope::default();
    let obj = match root.as_object() {
        Some(o) => o,
        None => return env,
    };

    // ---- rsxm-rules: routing table -------------------------------------
    // Enabled whenever there is at least one rule; the slice carries only
    // rule-shaped entries (inline fields, json bodies, preset ids).
    let route_rules = obj.get("routeRules").cloned().unwrap_or(Value::Null);
    let rule_slice = build_rules_slice(&route_rules);
    if let Some(slice) = rule_slice {
        env.push("rsxm-rules", slice);
    }

    // ---- rsxm-dns: servers, rules, fakeip -------------------------------
    let dns_enabled = obj
        .get("enableFakeIp")
        .and_then(Value::as_bool)
        .unwrap_or(false)
        || obj
            .get("dnsServers")
            .and_then(Value::as_array)
            .map(|a| !a.is_empty())
            .unwrap_or(false);
    if dns_enabled {
        let mut dns = Map::new();
        dns.insert("enabled".into(), Value::Bool(true));
        for key in [
            "dnsServers",
            "dnsRules",
            "enableFakeIp",
            "fakeIpFilter",
            "fakeIpFilterExclude",
            "fakeIpInet4Range",
            "fakeIpInet6Range",
            "fakeIpScope",
            "dnsStrategy",
            "dnsIndependentCache",
            "dnsCacheCapacity",
        ] {
            if let Some(v) = obj.get(key) {
                dns.insert(key.into(), v.clone());
            }
        }
        env.push("rsxm-dns", Value::Object(dns));
    }

    // ---- rsxm-tun: TUN parameters ----------------------------------------
    let mut tun = Map::new();
    for key in [
        "enableIpv6",
        "tunMtu",
        "tunInet4Address",
        "tunInet6Address",
        "tunDnsAddresses",
        "tunStackRemoved", // historical no-op, kept for slice stability
    ] {
        if let Some(v) = obj.get(key) {
            tun.insert(key.into(), v.clone());
        }
    }
    if !tun.is_empty() {
        env.push("rsxm-tun", Value::Object(tun));
    }

    // ---- rsxm-dialer: outbounds and their protocols ----------------------
    let outbounds = obj.get("outbounds").cloned().unwrap_or(Value::Null);
    let groups = obj.get("outboundGroups").cloned().unwrap_or(Value::Null);
    if has_items(&outbounds) || has_items(&groups) {
        let mut dialer = Map::new();
        dialer.insert("outbounds".into(), outbounds);
        dialer.insert("groups".into(), groups);
        if let Some(v) = obj.get("selectedOutbound") {
            dialer.insert("selected".into(), v.clone());
        }
        if let Some(v) = obj.get("muxProtocol") {
            dialer.insert("muxProtocol".into(), v.clone());
        }
        env.push("rsxm-dialer", Value::Object(dialer));
    }

    // ---- rsxm-power: power/battery policy --------------------------------
    let mut power = Map::new();
    for key in [
        "powerReportEnabled",
        "statusIntervalSeconds",
        "autoPauseEnabled",
    ] {
        if let Some(v) = obj.get(key) {
            power.insert(key.into(), v.clone());
        }
    }
    if !power.is_empty() {
        env.push("rsxm-power", Value::Object(power));
    }

    env
}

fn has_items(v: &Value) -> bool {
    v.as_array().map(|a| !a.is_empty()).unwrap_or(false)
}

/// Android rule field name → rules-engine field name. The translation
/// belongs here so the rules micro-kernel never sees AppState casing.
const RULE_FIELD_MAP: &[(&str, &str)] = &[
    ("domain", "domains"),
    ("domainSuffix", "suffixes"),
    ("domainKeyword", "keywords"),
    ("packageName", "packages"),
    ("ipCidr", "cidrs"),
    ("port", "ports"),
];

/// Builds the rsxm-rules slice: only rules that are enabled, in user order,
/// with Android field names translated to the rules-engine's names.
fn build_rules_slice(route_rules: &Value) -> Option<Value> {
    let rules = route_rules.as_array()?;
    let enabled: Vec<Value> = rules
        .iter()
        .filter(|r| r.get("enabled").and_then(Value::as_bool).unwrap_or(true))
        .filter_map(|r| {
            let obj = r.as_object()?;
            let mut mapped = Map::new();
            if let Some(id) = obj.get("id") {
                mapped.insert("id".into(), id.clone());
            }
            let target = obj
                .get("outbound")
                .or_else(|| obj.get("target"))
                .and_then(Value::as_str);
            match target {
                Some("reject") | Some("block") => {
                    mapped.insert("target".into(), json!("Reject"));
                }
                Some("resolve") => {
                    mapped.insert("target".into(), json!("Resolve"));
                }
                Some(tag) => {
                    mapped.insert("target".into(), json!({ "Outbound": tag }));
                }
                None => {}
            }
            for (from, to) in RULE_FIELD_MAP {
                if let Some(v) = obj.get(*from) {
                    mapped.insert((*to).into(), v.clone());
                }
            }
            Some(Value::Object(mapped))
        })
        .collect();
    if enabled.is_empty() {
        return None;
    }
    let mut obj = Map::new();
    obj.insert("enabled".into(), Value::Bool(true));
    obj.insert("rules".into(), Value::Array(enabled));
    Some(Value::Object(obj))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn scheduler_never_sees_business_data() {
        // The envelope carries opaque slices; nothing here is readable by the
        // scheduler without going through Arc<Value> — by design.
        let doc = json!({
            "routeRules": [{ "id": "r1", "enabled": true, "domainSuffix": ["example.com"] }],
            "dnsServers": [{ "id": "s1", "tag": "dns-remote", "type": "https" }],
            "tunMtu": 9000,
            "someUnknownField": { "nested": [1, 2, 3] },
        });
        let env = split(&doc);
        let names: Vec<&'static str> = env.slices().iter().map(|s| s.module).collect();
        assert_eq!(names, vec!["rsxm-rules", "rsxm-dns", "rsxm-tun"]);
        // Unknown field not copied anywhere.
        for slice in env.slices() {
            let text = slice.value.to_string();
            assert!(!text.contains("someUnknownField"), "{text}");
            assert!(!text.contains("nested"), "{text}");
        }
    }

    #[test]
    fn disabled_rules_are_not_shipped() {
        let doc = json!({
            "routeRules": [
                { "id": "r1", "enabled": false, "domainSuffix": ["a.com"] },
                { "id": "r2", "enabled": true, "outbound": "proxy", "domainSuffix": ["b.com"] },
            ],
        });
        let env = split(&doc);
        let slice = env.for_module("rsxm-rules").expect("rules slice");
        let rules = slice.value.get("rules").and_then(Value::as_array).unwrap();
        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0].get("id").and_then(Value::as_str), Some("r2"));
        // Android casing translated: domainSuffix → suffixes
        assert!(rules[0].get("suffixes").is_some());
        assert!(rules[0].get("domainSuffix").is_none());
    }

    #[test]
    fn module_slice_is_isolated() {
        // A module reading its own slice cannot see another module's data —
        // the slice value simply does not contain it.
        let doc = json!({
            "dnsServers": [{ "id": "s1" }],
            "routeRules": [{ "id": "r1", "domainSuffix": ["x.com"] }],
        });
        let env = split(&doc);
        let dns = env.for_module("rsxm-dns").expect("dns");
        assert!(dns.value.get("routeRules").is_none());
        let rules = env.for_module("rsxm-rules").expect("rules");
        assert!(rules.value.get("dnsServers").is_none());
    }

    #[test]
    fn feature_off_means_no_slice() {
        let doc = json!({ "routeRules": [], "dnsServers": [] });
        let env = split(&doc);
        assert!(env.for_module("rsxm-rules").is_none());
        assert!(env.for_module("rsxm-dns").is_none());
    }

    #[test]
    fn non_object_document_yields_empty_envelope() {
        let env = split(&json!([1, 2, 3]));
        assert!(env.slices().is_empty());
    }
}
