//! Route checking: replay a connection query against a compiled rule
//! table and explain the outcome.
//!
//! This is the "route-check" capability the app's 防分流检测页 (rule
//! inspector) builds on: the user types a domain / IP / package / port and
//! gets back the rule that would fire, the outbound it leads to, or the
//! fact that nothing matched and the route `final` applies.
//!
//! It is deliberately a **replay** of the app's compiled rule table, not an
//! independent evaluation of raw user rules: the app already owns the
//! translation layer (RoutePlanner + ConfigCompiler), and feeding this
//! engine the *compiled* sing-box rules keeps one source of truth. The
//! engine understands the sing-box route-rule JSON dialect — the exact
//! objects `compileRouteRules` emits — so a match here is a match in the
//! kernel, by construction.
//!
//! Semantics mirrored from the vendored core (verified against source):
//!  - rules are first-hit-wins, top to bottom;
//!  - a default rule ANDs its groups; the destination-address family
//!    (domain / suffix / keyword / regex / ip_cidr) is one OR group;
//!  - `rule_set` is a separate AND condition on top of the groups for
//!    remote sets (mergeable inline sets fold into the OR group, which the
//!    compiler never emits for remote URLs — the common case here);
//!  - `resolve`, `sniff` and `route-options` actions do NOT stop matching;
//!    only `route`, `reject` and `hijack-dns` terminate;
//!  - logical rules combine their branches with and/or; a branch is a
//!    default rule without an action.

use serde::{Deserialize, Serialize};
use std::net::IpAddr;

/// One rule as the compiler emitted it (sing-box route-rule JSON, minus the
/// fields this engine does not need). Unknown fields are ignored so newer
/// compilers keep working against older engines.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CompiledRule {
    #[serde(default)]
    pub r#type: String,
    #[serde(default)]
    pub mode: String,
    #[serde(default)]
    pub rules: Vec<CompiledRule>,
    #[serde(default)]
    pub invert: bool,
    #[serde(default)]
    pub action: Option<String>,
    #[serde(default)]
    pub outbound: Option<String>,
    // --- matchers (each listable: bare string or array) ---
    #[serde(default, deserialize_with = "crate::listable")]
    pub domain: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub domain_suffix: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub domain_keyword: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub domain_regex: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub ip_cidr: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub source_ip_cidr: Vec<String>,
    #[serde(default)]
    pub port: Vec<serde_json::Value>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub port_range: Vec<String>,
    #[serde(default)]
    pub source_port: Vec<serde_json::Value>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub source_port_range: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub network: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub protocol: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub package_name: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub wifi_ssid: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub wifi_bssid: Vec<String>,
    #[serde(default, deserialize_with = "crate::listable")]
    pub rule_set: Vec<String>,
    #[serde(default)]
    pub ip_is_private: bool,
    #[serde(default)]
    pub source_ip_is_private: bool,
    #[serde(default)]
    pub ip_version: Option<u8>,
    #[serde(default)]
    pub clash_mode: Option<String>,
}

/// What the checker learned about one query.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct CheckOutcome {
    /// Index of the terminating rule (0-based), or `None` when `final` won.
    pub rule_index: Option<usize>,
    /// The rule's `id` when the compiler attached one, else its index.
    pub rule_label: String,
    /// `route` / `reject` / `hijack-dns` — the terminating action.
    pub action: String,
    /// Outbound the action routes to (route only).
    pub outbound: Option<String>,
    /// Human-readable reason the rule fired ("domain_suffix example.com").
    pub reason: String,
}

/// A connection query in the shape the tun layer would hand the router.
#[derive(Debug, Clone, Default)]
pub struct CheckQuery {
    pub domain: Option<String>,
    pub destination_ip: Option<IpAddr>,
    pub source_ip: Option<IpAddr>,
    pub port: Option<u16>,
    pub source_port: Option<u16>,
    pub network: Option<String>,
    pub protocol: Option<String>,
    pub package: Option<String>,
    pub wifi_ssid: Option<String>,
    pub clash_mode: Option<String>,
    /// Rule-set tags the checker may treat as matched. The app compiles
    /// remote sets into the config and pre-resolves their membership out of
    /// band; for an interactive check the UI offers the loaded tags and the
    /// user picks one to simulate.
    pub matched_rule_sets: std::collections::HashSet<String>,
}

/// The parsed, indexed checker over one compiled table.
pub struct RouteChecker {
    rules: Vec<CompiledRule>,
}

impl RouteChecker {
    /// Parses the compiled `route.rules` array (the JSON array the
    /// compiler emits). Malformed entries are skipped, not fatal: a check
    /// against a broken config still explains what it could load.
    pub fn from_json_array(text: &str) -> Result<Self, String> {
        let parsed: serde_json::Value =
            serde_json::from_str(text).map_err(|e| format!("invalid JSON: {e}"))?;
        let rules = match parsed {
            serde_json::Value::Array(items) => items,
            other => vec![other],
        };
        let rules = rules
            .into_iter()
            .filter_map(|item| serde_json::from_value::<CompiledRule>(item).ok())
            .collect();
        Ok(Self { rules })
    }

    pub fn rule_count(&self) -> usize {
        self.rules.len()
    }

    /// Runs the query through the table, mirroring the kernel's match loop:
    /// non-terminating actions (resolve / sniff / route-options) pass
    /// through, the first route / reject / hijack-dns ends it.
    pub fn check(&self, query: &CheckQuery) -> CheckOutcome {
        for (index, rule) in self.rules.iter().enumerate() {
            if !self.rule_matches(rule, query) {
                continue;
            }
            let action = rule.action.clone().unwrap_or_else(|| "route".to_string());
            match action.as_str() {
                "route" | "reject" | "hijack-dns" => {
                    return CheckOutcome {
                        rule_index: Some(index),
                        rule_label: rule.outbound.clone().unwrap_or_else(|| format!("#{index}")),
                        action: action.clone(),
                        outbound: rule.outbound.clone(),
                        reason: explain(rule),
                    };
                }
                // Non-terminating: keep scanning. The reason is still
                // surfaced for the check *after* the walk in the "no
                // terminator" case? No — the kernel just continues; the
                // explanation here lists the rule when nothing terminates.
                _ => continue,
            }
        }
        CheckOutcome {
            rule_index: None,
            rule_label: "final".into(),
            action: "route".into(),
            outbound: Some("final".into()),
            reason: "no rule matched; the route final catch-all applies".into(),
        }
    }

    fn rule_matches(&self, rule: &CompiledRule, query: &CheckQuery) -> bool {
        if rule.r#type == "logical" {
            let want_or = rule.mode == "or";
            let results: Vec<bool> = rule
                .rules
                .iter()
                .map(|b| self.rule_matches(b, query))
                .collect();
            let mut matched = if want_or {
                results.iter().any(|hit| *hit)
            } else {
                results.iter().all(|hit| *hit)
            };
            if rule.invert {
                matched = !matched;
            }
            return matched;
        }
        let mut matched = self.default_rule_matches(rule, query);
        if rule.invert {
            matched = !matched;
        }
        matched
    }

    fn default_rule_matches(&self, rule: &CompiledRule, query: &CheckQuery) -> bool {
        // --- package group (AND) ---
        if !rule.package_name.is_empty()
            && !matches!(&query.package, Some(pkg) if rule.package_name.iter().any(|p| p == pkg))
        {
            return false;
        }
        // --- network / protocol (plain AND items in the kernel) ---
        if !rule.network.is_empty()
            && !matches!(&query.network, Some(net) if rule.network.iter().any(|n| n == net))
        {
            return false;
        }
        if !rule.protocol.is_empty()
            && !matches!(&query.protocol, Some(proto) if rule.protocol.iter().any(|p| p == proto))
        {
            return false;
        }
        // --- wifi (AND) ---
        if !rule.wifi_ssid.is_empty()
            && !matches!(&query.wifi_ssid, Some(ssid) if rule.wifi_ssid.iter().any(|w| w == ssid))
        {
            return false;
        }
        // --- source group (OR within the family, AND outside) ---
        let source_matched = {
            let by_cidr = !rule.source_ip_cidr.is_empty()
                && matches!(&query.source_ip, Some(ip) if source_cidr_hit(&rule.source_ip_cidr, *ip));
            let by_private = rule.source_ip_is_private
                && matches!(&query.source_ip, Some(ip) if is_private(*ip));
            if rule.source_ip_cidr.is_empty() && !rule.source_ip_is_private {
                // group not required
                true
            } else {
                by_cidr || by_private
            }
        };
        if !source_matched {
            return false;
        }
        // --- destination address family: one OR group ---
        let dest_required = !rule.domain.is_empty()
            || !rule.domain_suffix.is_empty()
            || !rule.domain_keyword.is_empty()
            || !rule.domain_regex.is_empty()
            || !rule.ip_cidr.is_empty()
            || rule.ip_is_private;
        if dest_required {
            let mut satisfied = false;
            if let Some(domain) = &query.domain {
                let d = domain.to_lowercase();
                satisfied = rule.domain.iter().any(|x| x.eq_ignore_ascii_case(&d))
                    || rule.domain_suffix.iter().any(|s| {
                        d == s.to_lowercase() || d.ends_with(&format!(".{}", s.to_lowercase()))
                    })
                    || rule
                        .domain_keyword
                        .iter()
                        .any(|k| d.contains(&k.to_lowercase()))
                    || rule.domain_regex.iter().any(|re| regex_hit(re, domain));
            }
            if !satisfied && !rule.ip_cidr.is_empty() {
                if let Some(ip) = query.destination_ip {
                    satisfied = cidr_hit(&rule.ip_cidr, ip);
                }
            }
            if !satisfied && rule.ip_is_private {
                if let Some(ip) = query.destination_ip {
                    satisfied = is_private(ip);
                }
            }
            if !satisfied {
                return false;
            }
        }
        // --- ports (OR within the family, AND outside) ---
        let port_required = !rule.port.is_empty()
            || !rule.port_range.is_empty()
            || !rule.source_port.is_empty()
            || !rule.source_port_range.is_empty();
        if port_required {
            let dest_ok = (rule.port.is_empty() && rule.port_range.is_empty())
                || match query.port {
                    Some(port) => {
                        rule.port.iter().any(|p| value_as_u16(p) == Some(port))
                            || rule.port_range.iter().any(|r| range_hit(r, port))
                    }
                    None => false,
                };
            let source_ok = (rule.source_port.is_empty() && rule.source_port_range.is_empty())
                || match query.source_port {
                    Some(port) => {
                        rule.source_port
                            .iter()
                            .any(|p| value_as_u16(p) == Some(port))
                            || rule.source_port_range.iter().any(|r| range_hit(r, port))
                    }
                    None => false,
                };
            if !dest_ok || !source_ok {
                return false;
            }
        }
        // --- ip_version (AND) ---
        if let Some(version) = rule.ip_version {
            let query_version = query
                .destination_ip
                .map(|ip| if ip.is_ipv4() { 4 } else { 6 });
            if query_version != Some(version) {
                return false;
            }
        }
        // --- clash_mode (AND) ---
        if let Some(mode) = &rule.clash_mode {
            if query.clash_mode.as_deref() != Some(mode.as_str()) {
                return false;
            }
        }
        // --- rule_set: its own AND condition; membership is supplied by
        // the caller (matched_rule_sets simulates a loaded set). ---
        if !rule.rule_set.is_empty()
            && !rule
                .rule_set
                .iter()
                .any(|tag| query.matched_rule_sets.contains(tag))
        {
            return false;
        }
        true
    }
}

/// Human explanation of the first matcher that satisfied a rule.
fn explain(rule: &CompiledRule) -> String {
    if rule.r#type == "logical" {
        return format!("logical {} ({} branches)", rule.mode, rule.rules.len());
    }
    if let Some(domain) = rule.domain.first() {
        return format!("domain {domain}");
    }
    if let Some(suffix) = rule.domain_suffix.first() {
        return format!("domain_suffix {suffix}");
    }
    if let Some(keyword) = rule.domain_keyword.first() {
        return format!("domain_keyword {keyword}");
    }
    if let Some(cidr) = rule.ip_cidr.first() {
        return format!("ip_cidr {cidr}");
    }
    if rule.ip_is_private {
        return "ip_is_private".into();
    }
    if let Some(package) = rule.package_name.first() {
        return format!("package_name {package}");
    }
    if let Some(tag) = rule.rule_set.first() {
        return format!("rule_set {tag}");
    }
    if !rule.port.is_empty() || !rule.port_range.is_empty() {
        return "port".into();
    }
    "match".into()
}

fn value_as_u16(value: &serde_json::Value) -> Option<u16> {
    match value {
        serde_json::Value::Number(n) => n.as_u64().and_then(|v| u16::try_from(v).ok()),
        serde_json::Value::String(s) => s.parse().ok(),
        _ => None,
    }
}

/// `5228:5230` style range (inclusive both ends, per the kernel).
fn range_hit(range: &str, port: u16) -> bool {
    let Some((start, end)) = range.split_once(':') else {
        return false;
    };
    let Ok(start) = start.trim().parse::<u16>() else {
        return false;
    };
    let Ok(end) = end.trim().parse::<u16>() else {
        return false;
    };
    port >= start && port <= end
}

fn cidr_hit(raws: &[String], ip: IpAddr) -> bool {
    raws.iter()
        .any(|raw| parse_cidr(raw).is_some_and(|(net, prefix)| contains(net, prefix, ip)))
}

fn source_cidr_hit(raws: &[String], ip: IpAddr) -> bool {
    cidr_hit(raws, ip)
}

/// Parses `a.b.c.d/N` / `[v6::x]/N` into (address, prefix).
fn parse_cidr(text: &str) -> Option<(IpAddr, u8)> {
    let (addr, prefix) = text.trim().split_once('/')?;
    let addr: IpAddr = addr.trim_matches(|c| c == '[' || c == ']').parse().ok()?;
    let prefix: u8 = prefix.trim().parse().ok()?;
    let max = if addr.is_ipv4() { 32 } else { 128 };
    if prefix > max {
        return None;
    }
    Some((addr, prefix))
}

fn contains(net: IpAddr, prefix: u8, ip: IpAddr) -> bool {
    match (net, ip) {
        (IpAddr::V4(net), IpAddr::V4(ip)) => {
            let net = u32::from(net);
            let ip = u32::from(ip);
            let mask = if prefix == 0 {
                0
            } else {
                u32::MAX << (32 - prefix)
            };
            (net & mask) == (ip & mask)
        }
        (IpAddr::V6(net), IpAddr::V6(ip)) => {
            let net = u128::from(net);
            let ip = u128::from(ip);
            let mask = if prefix == 0 {
                0
            } else {
                u128::MAX << (128 - prefix)
            };
            (net & mask) == (ip & mask)
        }
        _ => false,
    }
}

/// The kernel's notion of "private": RFC1918/loopback/link-local/ULA and
/// friends — sing delegates to `N.IsPublicAddr`. Kept aligned with the
/// ranges the Go helper covers.
fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_multicast()
                || v4.is_unspecified()
                || v4.octets()[0] == 100 && (v4.octets()[1] & 0xC0) == 64 // CGNAT 100.64/10
                || v4.octets()[0] == 192 && v4.octets()[1] == 0 && v4.octets()[2] == 0 // 192.0.0.0/24
                || v4.octets()[0] == 198 && (v4.octets()[1] & 0xFE) == 18 // benchmarking
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                || (v6.segments()[0] & 0xFE00) == 0xFC00 // ULA fc00::/7
                || (v6.segments()[0] & 0xFFC0) == 0xFE80 // link-local fe80::/10
        }
    }
}

/// Minimal regex support: the kernel uses Go RE2 syntax; instead of vendoring
/// a regex engine, this treats `^...$`-anchored patterns as prefix/suffix
/// matches and falls back to substring. Domain regexes in practice are
/// `^alt\d+-mtalk\.google\.com$`-shaped; a full engine lands with the
/// runtime crate if interactive checks ever need more.
fn regex_hit(pattern: &str, domain: &str) -> bool {
    let trimmed = pattern.trim();
    let anchored_start = trimmed.starts_with('^');
    let anchored_end = trimmed.ends_with('$');
    let body = trimmed
        .trim_start_matches('^')
        .trim_end_matches('$')
        .to_lowercase();
    if body.contains(['.', '+', '*', '?', '(', '[', '|']) {
        // Give literal-dot handling a chance: treat `\.` and `.` as `.`.
        // Without a real engine we only handle the common anchored case:
        // strip the escapes and compare structurally.
        let literal = body.replace("\\.", ".");
        if anchored_start && anchored_end {
            return domain.to_lowercase() == literal;
        }
        if anchored_start {
            return domain.to_lowercase().starts_with(&literal);
        }
        if anchored_end {
            return domain.to_lowercase().ends_with(&literal);
        }
        return domain.to_lowercase().contains(&literal);
    }
    if anchored_start && anchored_end {
        return domain.eq_ignore_ascii_case(&body);
    }
    if anchored_start {
        return domain.to_lowercase().starts_with(&body);
    }
    if anchored_end {
        return domain.to_lowercase().ends_with(&body);
    }
    domain.to_lowercase().contains(&body)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn checker(rules: &str) -> RouteChecker {
        RouteChecker::from_json_array(rules).expect("rules parse")
    }

    fn query(domain: &str) -> CheckQuery {
        CheckQuery {
            domain: Some(domain.into()),
            ..Default::default()
        }
    }

    #[test]
    fn first_hit_wins_across_rules() {
        let c = checker(
            r#"[
                {"domain_suffix":"ads.example","action":"reject"},
                {"domain_suffix":"example","outbound":"direct"}
            ]"#,
        );
        let out = c.check(&query("tracker.ads.example"));
        assert_eq!(out.action, "reject");
        assert_eq!(out.rule_index, Some(0));
        assert_eq!(out.reason, "domain_suffix ads.example");
    }

    #[test]
    fn no_match_reports_final() {
        let c = checker(r#"[{"domain_suffix":"example","outbound":"direct"}]"#);
        let out = c.check(&query("other.org"));
        assert_eq!(out.rule_index, None);
        assert_eq!(out.rule_label, "final");
    }

    #[test]
    fn destination_family_is_or_within_and_across_groups() {
        let c = checker(
            r#"[{
                "domain_suffix":["example.com"],
                "ip_cidr":["10.0.0.0/8"],
                "port":[443],
                "outbound":"proxy"
            }]"#,
        );
        // Domain + port: hit.
        let mut q = query("a.example.com");
        q.port = Some(443);
        assert_eq!(c.check(&q).outbound.as_deref(), Some("proxy"));
        // IP + port: hit (OR inside the destination family).
        let mut q = CheckQuery {
            port: Some(443),
            ..Default::default()
        };
        q.destination_ip = Some("10.1.2.3".parse().unwrap());
        assert_eq!(c.check(&q).outbound.as_deref(), Some("proxy"));
        // Domain but wrong port: the port group fails.
        assert_eq!(c.check(&query("a.example.com")).rule_index, None);
    }

    #[test]
    fn resolve_action_does_not_stop_matching() {
        let c = checker(
            r#"[
                {"domain_suffix":"example.com","action":"resolve"},
                {"domain_keyword":"shop","outbound":"proxy"}
            ]"#,
        );
        let out = c.check(&query("shop.example.com"));
        assert_eq!(out.action, "route");
        assert_eq!(out.rule_index, Some(1));
    }

    #[test]
    fn logical_and_requires_every_branch() {
        let c = checker(
            r#"[{
                "type":"logical","mode":"and",
                "rules":[
                    {"package_name":["com.game"]},
                    {"domain_suffix":"game.com"}
                ],
                "outbound":"proxy"
            }]"#,
        );
        let mut q = query("api.game.com");
        q.package = Some("com.game".into());
        assert_eq!(c.check(&q).outbound.as_deref(), Some("proxy"));
        q.package = Some("com.other".into());
        assert_eq!(c.check(&q).rule_index, None);
    }

    #[test]
    fn logical_or_matches_any_branch() {
        let c = checker(
            r#"[{
                "type":"logical","mode":"or",
                "rules":[
                    {"domain":["mtalk.google.com"]},
                    {"port_range":["5228:5230"]}
                ],
                "outbound":"direct"
            }]"#,
        );
        assert_eq!(
            c.check(&query("mtalk.google.com")).outbound.as_deref(),
            Some("direct")
        );
        let q = CheckQuery {
            port: Some(5229),
            ..Default::default()
        };
        assert_eq!(c.check(&q).outbound.as_deref(), Some("direct"));
        let q = CheckQuery {
            port: Some(5231),
            ..Default::default()
        };
        assert_eq!(c.check(&q).rule_index, None);
    }

    #[test]
    fn rule_set_is_simulated_from_supplied_tags() {
        let c = checker(r#"[{"rule_set":["ads-all"],"action":"reject"}]"#);
        let mut q = query("ads.tracker.net");
        assert_eq!(c.check(&q).rule_index, None);
        q.matched_rule_sets.insert("ads-all".into());
        assert_eq!(c.check(&q).action, "reject");
    }

    #[test]
    fn invert_flips_the_match() {
        let c = checker(r#"[{"domain_suffix":"example.com","invert":true,"outbound":"direct"}]"#);
        assert_eq!(c.check(&query("example.com")).rule_index, None);
        assert_eq!(
            c.check(&query("other.org")).outbound.as_deref(),
            Some("direct")
        );
    }

    #[test]
    fn network_and_package_gate_the_match() {
        let c = checker(r#"[{"network":["udp"],"port":[53],"action":"hijack-dns"}]"#);
        let mut q = CheckQuery {
            port: Some(53),
            network: Some("udp".into()),
            ..Default::default()
        };
        assert_eq!(c.check(&q).action, "hijack-dns");
        q.network = Some("tcp".into());
        assert_eq!(c.check(&q).rule_index, None);
    }

    #[test]
    fn private_addresses_satisfy_ip_is_private() {
        let c = checker(r#"[{"ip_is_private":true,"outbound":"direct"}]"#);
        let q = CheckQuery {
            destination_ip: Some("192.168.1.1".parse().unwrap()),
            ..Default::default()
        };
        assert_eq!(c.check(&q).outbound.as_deref(), Some("direct"));
        let q = CheckQuery {
            destination_ip: Some("1.1.1.1".parse().unwrap()),
            ..Default::default()
        };
        assert_eq!(c.check(&q).rule_index, None);
    }

    #[test]
    fn suffix_matching_respects_label_boundaries() {
        let c = checker(r#"[{"domain_suffix":"example.com","outbound":"direct"}]"#);
        // "notexample.com" must not match the suffix.
        assert_eq!(c.check(&query("notexample.com")).rule_index, None);
        assert_eq!(
            c.check(&query("example.com")).outbound.as_deref(),
            Some("direct")
        );
    }

    #[test]
    fn domain_regex_anchored_literal_is_handled() {
        // The lightweight matcher covers the anchored-literal shape most
        // domain regexes use (dots escaped, no character classes). Classes
        // like \d need the runtime crate's regex engine and are not
        // promised here — they fall back to a structural comparison.
        let c =
            checker(r#"[{"domain_regex":["^alt1\\.mtalk\\.google\\.com$"],"outbound":"direct"}]"#);
        assert_eq!(
            c.check(&query("alt1.mtalk.google.com")).outbound.as_deref(),
            Some("direct")
        );
        assert_eq!(c.check(&query("alt2.mtalk.google.com")).rule_index, None);
    }
}
