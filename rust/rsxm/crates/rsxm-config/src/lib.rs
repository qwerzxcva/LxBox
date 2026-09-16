//! rsxm-config: the ONLY place that understands the whole AppState JSON.
//!
//! The central kernel (the **Conductor**, in `rsxm-core`) is a boss, not a
//! reader: it never parses route rules, TUN parameters or DNS server lists.
//! This crate is the boss's clerk: it splits the full JSON document into
//! per-module slices — each micro-kernel receives exactly its own section
//! and deserialises it itself, so no leader ever loads the whole document.
//!
//! Slice keys are module names. Adding a micro-kernel means adding one
//! extraction branch here and nothing anywhere else.

use rsxm_core::{ConfigEnvelope, ConfigSlice};
use serde_json::{json, Map, Value};

/// Splits a full AppState JSON document into module slices.
///
/// Unknown top-level keys are dropped on purpose: the Conductor never sees
/// them, and a micro-kernel that needs a new section gets its own extractor
/// here. A module whose feature is off simply receives no slice (its
/// `configure(None)` decides whether to stay down or run on safe defaults).
pub fn split(root: &Value) -> ConfigEnvelope {
    let mut slices: Vec<ConfigSlice> = Vec::new();
    let obj = match root.as_object() {
        Some(o) => o,
        None => return ConfigEnvelope::new(slices),
    };

    // ---- rsxm-rules: routing table -------------------------------------
    if let Some(slice) = build_rules_slice(obj.get("routeRules").unwrap_or(&Value::Null)) {
        slices.push(ConfigSlice::new("rsxm-rules", slice));
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
            .unwrap_or(false)
        || obj
            .get("dnsRules")
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
            "fakeIpBypass",
            "fakeIpFilterExclude",
            "fakeIpInet4Range",
            "fakeIpInet6Range",
            "fakeIpScope",
            "fakeIpTtl",
            "fakeIpBlockHttps",
            "dnsStrategy",
            "dnsIndependentCache",
            "dnsCacheCapacity",
            "dnsClientSubnet",
        ] {
            if let Some(v) = obj.get(key) {
                dns.insert(key.into(), v.clone());
            }
        }
        slices.push(ConfigSlice::new("rsxm-dns", Value::Object(dns)));
    }

    // ---- rsxm-tun: TUN parameters ---------------------------------------
    let mut tun = Map::new();
    for key in [
        "enableIpv6",
        "tunMtu",
        "tunInet4Address",
        "tunInet6Address",
        "tunDnsAddresses",
        "hevTunMode",
    ] {
        if let Some(v) = obj.get(key) {
            tun.insert(key.into(), v.clone());
        }
    }
    if !tun.is_empty() {
        slices.push(ConfigSlice::new("rsxm-tun", Value::Object(tun)));
    }

    // ---- rsxm-dialer: outbounds and their protocols ---------------------
    let outbounds = obj.get("outbounds").cloned().unwrap_or(Value::Null);
    let groups = obj.get("outboundGroups").cloned().unwrap_or(Value::Null);
    if has_items(&outbounds) || has_items(&groups) {
        let mut dialer = Map::new();
        dialer.insert("outbounds".into(), outbounds);
        dialer.insert("groups".into(), groups);
        for key in ["selectedOutbound", "muxProtocol", "udpOverTcp"] {
            if let Some(v) = obj.get(key) {
                dialer.insert(key.into(), v.clone());
            }
        }
        slices.push(ConfigSlice::new("rsxm-dialer", Value::Object(dialer)));
    }

    // ---- rsxm-power: battery policy -------------------------------------
    let mut power = Map::new();
    for key in [
        "healthCheckIntervalMinutes",
        "tcpKeepAliveIdleSeconds",
        "bootAutoStart",
    ] {
        if let Some(v) = obj.get(key) {
            power.insert(key.into(), v.clone());
        }
    }
    if !power.is_empty() {
        slices.push(ConfigSlice::new("rsxm-power", Value::Object(power)));
    }

    // ---- rsxm-security: privacy posture ---------------------------------
    let mut security = Map::new();
    for key in [
        "blockScreenshots",
        "allowInsecureDns",
        "tlsFragmentMode",
        "tlsFragmentFallbackDelay",
    ] {
        if let Some(v) = obj.get(key) {
            security.insert(key.into(), v.clone());
        }
    }
    if !security.is_empty() {
        slices.push(ConfigSlice::new("rsxm-security", Value::Object(security)));
    }

    ConfigEnvelope::new(slices)
}

fn has_items(v: &Value) -> bool {
    v.as_array().map(|a| !a.is_empty()).unwrap_or(false)
}

/// Android rule field name → native rules-engine field name. The
/// translation belongs here so the rules micro-kernel never sees AppState
/// casing.
const RULE_FIELD_MAP: &[(&str, &str)] = &[
    ("domain", "domains"),
    ("domainSuffix", "suffixes"),
    ("domainKeyword", "keywords"),
    ("domainRegex", "regexes"),
    ("packageName", "packages"),
    ("ipCidr", "cidrs"),
    ("sourceIpCidr", "source_cidrs"),
    ("network", "networks"),
    ("protocol", "protocols"),
    ("wifiSsid", "ssids"),
];

/// Builds the rsxm-rules slice: enabled rules only, in user order, with
/// Android field names translated. Logical rules are translated
/// recursively; the leader decides how to evaluate the tree.
fn build_rules_slice(route_rules: &Value) -> Option<Value> {
    let rules = route_rules.as_array()?;
    let enabled: Vec<Value> = rules
        .iter()
        .filter(|r| r.get("enabled").and_then(Value::as_bool).unwrap_or(true))
        .filter_map(translate_rule)
        .collect();
    if enabled.is_empty() {
        return None;
    }
    let mut obj = Map::new();
    obj.insert("enabled".into(), Value::Bool(true));
    obj.insert("rules".into(), Value::Array(enabled));
    Some(Value::Object(obj))
}

fn translate_rule(r: &Value) -> Option<Value> {
    let obj = r.as_object()?;
    let is_logical = obj
        .get("type")
        .and_then(Value::as_str)
        .is_some_and(|t| t == "logical");

    if is_logical {
        let mut mapped = Map::new();
        if let Some(id) = obj.get("id") {
            mapped.insert("id".into(), id.clone());
        }
        let mode = obj
            .get("logicalMode")
            .and_then(Value::as_str)
            .unwrap_or("and");
        mapped.insert("mode".into(), json!(mode));
        if obj.get("invert").and_then(Value::as_bool).unwrap_or(false) {
            mapped.insert("invert".into(), Value::Bool(true));
        }
        if let Some(target) = target_value(obj) {
            mapped.insert("target".into(), target);
        }
        let branches: Vec<Value> = obj
            .get("rules")
            .and_then(Value::as_array)
            .map(|rs| rs.iter().filter_map(translate_rule).collect())
            .unwrap_or_default();
        mapped.insert("branches".into(), Value::Array(branches));
        return Some(Value::Object(mapped));
    }

    let mut mapped = Map::new();
    if let Some(id) = obj.get("id") {
        mapped.insert("id".into(), id.clone());
    }
    if let Some(target) = target_value(obj) {
        mapped.insert("target".into(), target);
    }
    if obj.get("invert").and_then(Value::as_bool).unwrap_or(false) {
        mapped.insert("invert".into(), Value::Bool(true));
    }
    for (from, to) in RULE_FIELD_MAP {
        if let Some(v) = obj.get(*from) {
            mapped.insert((*to).into(), v.clone());
        }
    }
    // AppState ports are strings and may carry ranges; the native table
    // accepts bare port numbers only — ranges live in the compiled path.
    if let Some(ports) = obj.get("port").and_then(Value::as_array) {
        let numeric: Vec<Value> = ports
            .iter()
            .filter_map(|p| p.as_str().and_then(|s| s.parse::<u16>().ok()))
            .map(Value::from)
            .collect();
        if !numeric.is_empty() {
            mapped.insert("ports".into(), Value::Array(numeric));
        }
    }
    Some(Value::Object(mapped))
}

fn target_value(obj: &Map<String, Value>) -> Option<Value> {
    match obj
        .get("outbound")
        .or_else(|| obj.get("target"))
        .and_then(Value::as_str)
    {
        Some("reject") | Some("block") => Some(json!("Reject")),
        Some("resolve") => Some(json!("Resolve")),
        Some(tag) => Some(json!({ "Outbound": tag })),
        None => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn conductor_never_sees_business_data() {
        // The envelope carries opaque slices; nothing here is readable by
        // the Conductor except the module address on each slice.
        let doc = json!({
            "routeRules": [{ "id": "r1", "enabled": true, "domainSuffix": ["example.com"] }],
            "dnsServers": [{ "id": "s1", "tag": "dns-remote", "type": "https" }],
            "tunMtu": 9000,
            "someUnknownField": { "nested": [1, 2, 3] },
        });
        let env = split(&doc);
        let names: Vec<&'static str> = env.slices().iter().map(|s| s.module).collect();
        assert_eq!(names, vec!["rsxm-rules", "rsxm-dns", "rsxm-tun"]);
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
        assert!(rules[0].get("suffixes").is_some());
        assert!(rules[0].get("domainSuffix").is_none());
    }

    #[test]
    fn extended_matchers_are_translated() {
        let doc = json!({
            "routeRules": [{
                "id": "r1",
                "outbound": "proxy",
                "domainRegex": ["^ads\\."],
                "network": ["tcp"],
                "protocol": ["dns"],
                "wifiSsid": ["Home"],
                "sourceIpCidr": ["10.0.0.0/8"],
                "port": ["443", "1000-2000"],
            }],
        });
        let env = split(&doc);
        let rules = env
            .for_module("rsxm-rules")
            .unwrap()
            .value
            .get("rules")
            .and_then(Value::as_array)
            .unwrap();
        let r = &rules[0];
        assert!(r.get("regexes").is_some());
        assert!(r.get("networks").is_some());
        assert!(r.get("protocols").is_some());
        assert!(r.get("ssids").is_some());
        assert!(r.get("source_cidrs").is_some());
        assert_eq!(r.get("ports").unwrap()[0], json!(443));
        assert!(r.get("port").is_none());
    }

    #[test]
    fn logical_rules_are_translated_recursively() {
        let doc = json!({
            "routeRules": [{
                "id": "l1",
                "type": "logical",
                "logicalMode": "or",
                "invert": true,
                "outbound": "proxy",
                "rules": [
                    { "id": "a", "domainSuffix": ["a.com"] },
                    { "id": "b", "domainKeyword": ["b"] },
                ],
            }],
        });
        let env = split(&doc);
        let rules = env
            .for_module("rsxm-rules")
            .unwrap()
            .value
            .get("rules")
            .and_then(Value::as_array)
            .unwrap();
        let l = &rules[0];
        assert_eq!(l.get("mode").unwrap(), "or");
        assert_eq!(l.get("invert").unwrap(), true);
        assert_eq!(l.get("target").unwrap()["Outbound"], "proxy");
        assert_eq!(l.get("branches").unwrap().as_array().unwrap().len(), 2);
    }

    #[test]
    fn dns_rules_alone_enable_dns_slice() {
        let doc = json!({
            "dnsRules": [{ "id": "d1", "server": "remote", "domainSuffix": ["x.com"] }],
        });
        let env = split(&doc);
        let dns = env.for_module("rsxm-dns").expect("dns slice");
        assert_eq!(dns.value.get("enabled").unwrap(), true);
        assert!(dns.value.get("dnsRules").is_some());
    }

    #[test]
    fn module_slice_is_isolated() {
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
    fn power_and_security_slices_use_real_appstate_keys() {
        let doc = json!({
            "healthCheckIntervalMinutes": 30,
            "tcpKeepAliveIdleSeconds": 120,
            "blockScreenshots": true,
        });
        let env = split(&doc);
        let power = env.for_module("rsxm-power").expect("power");
        assert_eq!(power.value.get("healthCheckIntervalMinutes").unwrap(), 30);
        let security = env.for_module("rsxm-security").expect("security");
        assert_eq!(security.value.get("blockScreenshots").unwrap(), true);
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
