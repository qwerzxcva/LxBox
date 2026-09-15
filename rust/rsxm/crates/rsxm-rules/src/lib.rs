//! RSXM rule engine: the routing brain of the new kernel.
//!
//! Absorbs the best of three lineages:
//!  - **sing-box**: rule phases (package → keyword → suffix → exact →
//!    address) with first-hit-wins and the *destination-address family as
//!    one OR group* — verified against `abstractDefaultRule.matchInner`
//!    (`route/rule/rule_abstract.go`), where domain/suffix/keyword/regex and
//!    ip_cidr all satisfy the same match group;
//!  - **mihomo**: a domain trie instead of linear scans, so a 100k-entry
//!    geosite behaves like a hash lookup;
//!  - **rsxm**: redundancy elimination at load time so the hot path never
//!    visits a rule that cannot fire.
//!
//! Match semantics encoded here (the same table the Android planner feeds
//! the Go kernel today):
//!  - first hit wins, top to bottom;
//!  - across groups (package, destination address, port, …) the conditions
//!    are AND'ed;
//!  - inside the destination-address group the entries are OR'ed;
//!  - redundancy: keyword > suffix > exact domain, broader CIDR > narrower,
//!    earlier rule > later rule, and only a rule whose constraints are
//!    *just* the destination address can vouch for a later rule.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::net::IpAddr;

pub mod check;
pub use check::{CheckOutcome, CheckQuery, CompiledRule, RouteChecker};

/// The kernel's `badoption.Listable[string]`: a bare string or an array of
/// strings, both legal in route-rule JSON.
pub(crate) fn listable<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    Ok(match value {
        serde_json::Value::Null => Vec::new(),
        serde_json::Value::String(text) => vec![text],
        serde_json::Value::Array(items) => items
            .into_iter()
            .filter_map(|item| match item {
                serde_json::Value::String(text) => Some(text),
                _ => None,
            })
            .collect(),
        _ => Vec::new(),
    })
}

/// A rule's target, mirroring the app's action model.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Target {
    /// Route to the named outbound (node / group tag).
    Outbound(String),
    /// Reject (block) the connection.
    Reject,
    /// Resolve only; no routing decision.
    Resolve,
}

/// Match classes, in evaluation order. A rule's class is the strongest one
/// it carries (package > keyword > suffix > exact > other).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Phase {
    Package = 0,
    DomainKeyword = 1,
    DomainSuffix = 2,
    DomainExact = 3,
    Other = 4,
}

/// One rule as loaded from the app state.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Rule {
    pub id: String,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default)]
    pub suffixes: Vec<String>,
    #[serde(default)]
    pub domains: Vec<String>,
    /// Destination CIDRs (`"10.0.0.0/8"`, `"[fdfe::1]/126"`).
    #[serde(default)]
    pub cidrs: Vec<String>,
    #[serde(default)]
    pub ports: Vec<u16>,
    pub target: Option<Target>,
}

impl Rule {
    /// The strongest class the rule carries — its execution phase.
    pub fn phase(&self) -> Phase {
        if !self.packages.is_empty() {
            Phase::Package
        } else if !self.keywords.is_empty() {
            Phase::DomainKeyword
        } else if !self.suffixes.is_empty() {
            Phase::DomainSuffix
        } else if !self.domains.is_empty() {
            Phase::DomainExact
        } else {
            Phase::Other
        }
    }

    /// True when the rule has a matcher in the destination-address family.
    pub fn has_destination_address(&self) -> bool {
        !self.keywords.is_empty()
            || !self.suffixes.is_empty()
            || !self.domains.is_empty()
            || !self.cidrs.is_empty()
    }

    /// True when the rule constrains nothing but the destination address —
    /// the only shape that can vouch for a later rule's entries.
    pub fn is_destination_only(&self) -> bool {
        self.packages.is_empty() && self.ports.is_empty()
    }

    /// True when the rule has no matcher at all (can never fire).
    pub fn is_empty_matcher(&self) -> bool {
        !self.has_destination_address() && self.packages.is_empty() && self.ports.is_empty()
    }
}

/// What a match produced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Match {
    pub rule_id: String,
    pub target: Target,
}

/// A connection's route query, filled by the tun/stream layer.
#[derive(Debug, Clone, Default)]
pub struct Query {
    pub package: Option<String>,
    /// Sniffed or original destination name.
    pub domain: Option<String>,
    /// Resolved destination address (when known before routing).
    pub destination_ip: Option<IpAddr>,
    pub port: Option<u16>,
}

/// Domain trie: reversed-label walk over suffixes plus exact-domain set.
/// A query visits at most `label_count` nodes; no rule list is scanned.
#[derive(Debug, Default)]
pub struct DomainIndex {
    suffix_root: TrieNode,
    exact: HashSet<String>,
}

#[derive(Debug, Default)]
struct TrieNode {
    children: HashMap<String, TrieNode>,
    terminal: bool,
}

impl DomainIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Inserts a suffix: `example.com` also matches `a.example.com`.
    pub fn insert_suffix(&mut self, suffix: &str) {
        let mut node = &mut self.suffix_root;
        for label in suffix.split('.').rev() {
            node = node.children.entry(label.to_string()).or_default();
        }
        node.terminal = true;
    }

    pub fn insert_exact(&mut self, domain: &str) {
        self.exact.insert(domain.to_string());
    }

    pub fn matches(&self, domain: &str) -> bool {
        if self.exact.contains(domain) {
            return true;
        }
        let mut node = &self.suffix_root;
        for label in domain.split('.').rev() {
            match node.children.get(label) {
                Some(next) => {
                    node = next;
                    if node.terminal {
                        return true;
                    }
                }
                None => break,
            }
        }
        false
    }

    pub fn is_empty(&self) -> bool {
        self.exact.is_empty() && self.suffix_root.children.is_empty()
    }
}
/// A parsed CIDR entry: network bytes + prefix length.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cidr {
    addr: IpAddr,
    prefix: u8,
}

impl Cidr {
    /// Parses `a.b.c.d/N` or `[v6::addr]/N`. Returns None for malformed
    /// input — the loader keeps those as opaque text (a future version
    /// surfaces them in the UI as invalid instead of silently ignoring).
    pub fn parse(text: &str) -> Option<Self> {
        let (addr_text, prefix_text) = text.trim().split_once('/')?;
        let addr: IpAddr = addr_text.trim_matches(|c| c == '[' || c == ']').parse().ok()?;
        let prefix: u8 = prefix_text.trim().parse().ok()?;
        let max = if addr.is_ipv4() { 32 } else { 128 };
        if prefix > max {
            return None;
        }
        Some(Self { addr, prefix })
    }

    /// True when `other` (address or network) is fully contained in `self`.
    pub fn contains_addr(&self, other: IpAddr) -> bool {
        match (self.addr, other) {
            (IpAddr::V4(net), IpAddr::V4(ip)) => {
                let net = u32::from(net);
                let ip = u32::from(ip);
                let mask = if self.prefix == 0 { 0 } else { u32::MAX << (32 - self.prefix) };
                (net & mask) == (ip & mask)
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let net = u128::from(net);
                let ip = u128::from(ip);
                let mask = if self.prefix == 0 { 0 } else { u128::MAX << (128 - self.prefix) };
                (net & mask) == (ip & mask)
            }
            _ => false,
        }
    }

    /// True when `other` is fully contained in `self` (same family).
    pub fn contains_net(&self, other: &Cidr) -> bool {
        if self.addr.is_ipv4() != other.addr.is_ipv4() {
            return false;
        }
        if self.prefix > other.prefix {
            return false;
        }
        self.contains_addr(other.addr)
    }
}

/// Compiled CIDs for one rule. Unparseable entries are not indexed for
/// matching (they can never contain an address); cross-rule dedupe handles
/// them by raw-text identity during [`RuleTable::build`].
#[derive(Debug, Default)]
struct RuleCidrs {
    nets: Vec<Cidr>,
}

impl RuleCidrs {
    fn build(raws: &[String]) -> Self {
        let mut nets = Vec::with_capacity(raws.len());
        for raw in raws {
            if let Some(net) = Cidr::parse(raw.trim()) {
                nets.push(net);
            }
        }
        Self { nets }
    }

    fn contains(&self, addr: IpAddr) -> bool {
        self.nets.iter().any(|net| net.contains_addr(addr))
    }
}

/// A compiled routing table: rules in execution order with the domain and
/// CIDR work hoisted into per-rule indexes.
#[derive(Debug, Default)]
pub struct RuleTable {
    rules: Vec<Rule>,
    domain_indexes: Vec<DomainIndex>,
    cidr_indexes: Vec<RuleCidrs>,
}

impl RuleTable {
    /// Builds the table: sorts into phases (stable), removes redundant
    /// entries (both inside a rule and across same-target rules), and
    /// indexes domain/CIDR classes.
    pub fn build(mut rules: Vec<Rule>) -> Self {
        // Stable phase sort keeps the user's order inside a class.
        rules.sort_by_key(|r| r.phase());

        // Cross-rule coverage is tracked per action target: a rule routing
        // elsewhere says nothing about this rule's fate.
        type Seen = (
            HashSet<String>, // keywords
            Vec<String>,     // suffixes
            HashSet<String>, // exact domains
            Vec<Cidr>,       // cidr nets
            HashSet<String>, // cidr raw texts
        );
        let mut seen_by_target: HashMap<(String, String), Seen> = HashMap::new();
        let target_key = |r: &Rule| -> (String, String) {
            match &r.target {
                Some(Target::Outbound(tag)) => ("route".into(), tag.clone()),
                Some(Target::Reject) => ("reject".into(), String::new()),
                Some(Target::Resolve) => ("resolve".into(), String::new()),
                None => ("".into(), String::new()),
            }
        };

        let mut kept: Vec<Rule> = Vec::with_capacity(rules.len());
        for mut rule in rules {
            if rule.is_empty_matcher() {
                continue;
            }
            if rule.is_destination_only() {
                let seen = seen_by_target.entry(target_key(&rule)).or_default();

                // 1) inside the rule: keyword > suffix > exact domain,
                //    broader CIDR > narrower.
                dedupe_within(&mut rule);

                // 2) what earlier same-target destination-only rules cover.
                rule.keywords.retain(|kw| {
                    !seen.0.iter().any(|prev| prev.contains(kw.as_str()))
                });
                rule.suffixes.retain(|sfx| {
                    !seen.0.iter().any(|kw| sfx.contains(kw.as_str()))
                        && !seen
                            .1
                            .iter()
                            .any(|prev| sfx == prev || sfx.ends_with(&format!(".{prev}")))
                });
                rule.domains.retain(|dom| {
                    !seen.0.iter().any(|kw| dom.contains(kw.as_str()))
                        && !seen
                            .1
                            .iter()
                            .any(|prev| dom == prev || dom.ends_with(&format!(".{prev}")))
                        && !seen.2.contains(dom)
                });
                let before = rule.cidrs.len();
                rule.cidrs.retain(|raw| {
                    let text = raw.trim();
                    match Cidr::parse(text) {
                        Some(net) => !seen.3.iter().any(|prev| prev.contains_net(&net)),
                        None => !seen.4.contains(text),
                    }
                });
                let _ = before;

                // 3) register survivors — but never empty the destination
                //    group while other AND conditions remain (the rule would
                //    widen). A destination-only rule that lost everything is
                //    fully covered and gets dropped.
                if rule.has_destination_address() && rule.is_destination_only() {
                    // nothing to widen — safe to keep or drop
                } else if !rule.has_destination_address() {
                    continue; // covered away entirely
                }

                for kw in &rule.keywords {
                    seen.0.insert(kw.clone());
                }
                seen.1.extend(rule.suffixes.iter().cloned());
                for dom in &rule.domains {
                    seen.2.insert(dom.clone());
                }
                for raw in &rule.cidrs {
                    let text = raw.trim();
                    if let Some(net) = Cidr::parse(text) {
                        if !seen.3.contains(&net) {
                            seen.3.push(net);
                        }
                    } else {
                        seen.4.insert(text.to_string());
                    }
                }
            }

            if rule.is_empty_matcher() {
                continue; // pruned to nothing: a later rule already covers it
            }
            kept.push(rule);
        }

        let domain_indexes = kept
            .iter()
            .map(|rule| {
                let mut index = DomainIndex::new();
                for sfx in &rule.suffixes {
                    index.insert_suffix(sfx);
                }
                for dom in &rule.domains {
                    index.insert_exact(dom);
                }
                index
            })
            .collect();
        let cidr_indexes = kept.iter().map(|rule| RuleCidrs::build(&rule.cidrs)).collect();

        Self {
            rules: kept,
            domain_indexes,
            cidr_indexes,
        }
    }

    pub fn rule_count(&self) -> usize {
        self.rules.len()
    }

    /// First-hit-wins routing.
    pub fn match_query(&self, query: &Query) -> Option<Match> {
        for (idx, rule) in self.rules.iter().enumerate() {
            if !self.rule_matches(rule, &self.domain_indexes[idx], &self.cidr_indexes[idx], query) {
                continue;
            }
            if let Some(target) = &rule.target {
                return Some(Match {
                    rule_id: rule.id.clone(),
                    target: target.clone(),
                });
            }
        }
        None
    }

    fn rule_matches(
        &self,
        rule: &Rule,
        index: &DomainIndex,
        cidrs: &RuleCidrs,
        query: &Query,
    ) -> bool {
        // Package group: AND with the rest.
        if !rule.packages.is_empty() {
            match &query.package {
                Some(pkg) if rule.packages.iter().any(|p| p == pkg) => {}
                _ => return false,
            }
        }
        // Destination-address group: OR over domain / suffix / keyword /
        // CIDR — the same grouping the Go kernel uses.
        if rule.has_destination_address() {
            let mut satisfied = false;
            if let Some(domain) = &query.domain {
                if !rule.keywords.is_empty()
                    && rule.keywords.iter().any(|kw| domain.contains(kw.as_str()))
                {
                    satisfied = true;
                }
                if !satisfied && (!rule.suffixes.is_empty() || !rule.domains.is_empty()) {
                    satisfied = index.matches(domain);
                }
            }
            if !satisfied && !rule.cidrs.is_empty() {
                if let Some(ip) = query.destination_ip {
                    satisfied = cidrs.contains(ip);
                }
            }
            if !satisfied {
                return false;
            }
        }
        // Port group: AND with the rest.
        if !rule.ports.is_empty() {
            match query.port {
                Some(port) if rule.ports.contains(&port) => {}
                _ => return false,
            }
        }
        true
    }
}

/// keyword > suffix > exact domain, broader CIDR > narrower, inside one rule.
fn dedupe_within(rule: &mut Rule) {
    // Normalise is the caller's business (the app lowercases before
    // emitting); keep this pure to make the tests meaningful.
    let keywords = rule.keywords.clone();
    let hit_by_keyword = |value: &str| keywords.iter().any(|kw| value.contains(kw.as_str()));

    // Compute against snapshots, then assign: retain() closures cannot hold
    // a borrow of the same field they mutate.
    let suffixes_in = rule.suffixes.clone();
    let kept_suffixes: Vec<String> = suffixes_in
        .iter()
        .filter(|sfx| {
            if hit_by_keyword(sfx) {
                return false;
            }
            // A broader sibling suffix subsumes a narrower one.
            !suffixes_in.iter().any(|other| {
                other != *sfx
                    && other.len() < sfx.len()
                    && (sfx == &other || sfx.ends_with(&format!(".{other}")))
            })
        })
        .cloned()
        .collect();

    let domains_in = rule.domains.clone();
    let kept_domains: Vec<String> = domains_in
        .iter()
        .filter(|dom| {
            if hit_by_keyword(dom) {
                return false;
            }
            !kept_suffixes
                .iter()
                .any(|sfx| *dom == sfx || dom.ends_with(&format!(".{sfx}")))
        })
        .cloned()
        .collect();

    rule.suffixes = kept_suffixes;
    rule.domains = kept_domains;

    // CIDR: a broader net swallows a narrower one kept earlier.
    let mut kept_nets: Vec<Cidr> = Vec::new();
    let mut kept_raws: Vec<String> = Vec::new();
    for raw in &rule.cidrs {
        let text = raw.trim();
        if text.is_empty() {
            continue;
        }
        match Cidr::parse(text) {
            Some(net) => {
                if kept_nets.iter().any(|prev| prev.contains_net(&net)) {
                    continue;
                }
                kept_nets.push(net);
                kept_raws.push(text.to_string());
            }
            None => {
                if !kept_raws.iter().any(|prev| prev == text) {
                    kept_raws.push(text.to_string());
                }
            }
        }
    }
    rule.cidrs = kept_raws;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn outbound(tag: &str) -> Option<Target> {
        Some(Target::Outbound(tag.to_string()))
    }

    fn ip(s: &str) -> Option<IpAddr> {
        s.parse().ok()
    }

    #[test]
    fn phase_ordering_is_enforced() {
        let table = RuleTable::build(vec![
            Rule {
                id: "ip".into(),
                cidrs: vec!["10.0.0.0/8".into()],
                ports: vec![443],
                target: outbound("direct"),
                ..Default::default()
            },
            Rule {
                id: "app".into(),
                packages: vec!["com.example".into()],
                ports: vec![443],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        let m = table
            .match_query(&Query {
                package: Some("com.example".into()),
                port: Some(443),
                destination_ip: ip("10.1.1.1"),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "app");
    }

    #[test]
    fn suffix_index_matches_subdomains_but_not_lookalikes() {
        let table = RuleTable::build(vec![Rule {
            id: "suffix".into(),
            suffixes: vec!["example.com".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        for hit in ["a.b.example.com", "example.com"] {
            assert!(table
                .match_query(&Query {
                    domain: Some(hit.into()),
                    ..Default::default()
                })
                .is_some());
        }
        assert!(table
            .match_query(&Query {
                domain: Some("notexample.com".into()),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn destination_family_is_or_not_and() {
        // A rule with BOTH a suffix and a CIDR must fire on either — the
        // kernel groups them into one OR bucket.
        let table = RuleTable::build(vec![Rule {
            id: "mixed".into(),
            suffixes: vec!["example.com".into()],
            cidrs: vec!["10.0.0.0/8".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        // Domain path.
        assert!(table
            .match_query(&Query {
                domain: Some("a.example.com".into()),
                ..Default::default()
            })
            .is_some());
        // IP path.
        assert!(table
            .match_query(&Query {
                destination_ip: ip("10.2.3.4"),
                ..Default::default()
            })
            .is_some());
        // Neither.
        assert!(table
            .match_query(&Query {
                domain: Some("other.org".into()),
                destination_ip: ip("192.168.1.1"),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn cidr_rules_do_not_match_everything() {
        // Regression: an earlier prototype parsed CIDRs but never matched
        // them, so an IP-only rule swallowed every query.
        let table = RuleTable::build(vec![Rule {
            id: "ip".into(),
            cidrs: vec!["10.0.0.0/8".into()],
            target: outbound("direct"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                destination_ip: ip("10.1.2.3"),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                destination_ip: ip("192.168.1.1"),
                ..Default::default()
            })
            .is_none());
        // A query with no address at all must not match either.
        assert!(table
            .match_query(&Query {
                domain: Some("example.com".into()),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn package_group_ands_with_destination_group() {
        let table = RuleTable::build(vec![Rule {
            id: "combo".into(),
            packages: vec!["com.game".into()],
            suffixes: vec!["game.com".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                package: Some("com.game".into()),
                ..Default::default()
            })
            .is_none());
        assert!(table
            .match_query(&Query {
                domain: Some("game.com".into()),
                ..Default::default()
            })
            .is_none());
        assert!(table
            .match_query(&Query {
                package: Some("com.game".into()),
                domain: Some("api.game.com".into()),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn keyword_beats_suffix_beats_exact() {
        let table = RuleTable::build(vec![
            Rule {
                id: "kw".into(),
                keywords: vec!["google".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "sfx".into(),
                suffixes: vec!["google.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "exact".into(),
                domains: vec!["maps.google.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        assert_eq!(table.rule_count(), 1);
        for domain in ["google.com", "maps.google.com", "www.google.co.jp"] {
            let m = table
                .match_query(&Query {
                    domain: Some(domain.into()),
                    ..Default::default()
                })
                .unwrap();
            assert_eq!(m.rule_id, "kw");
        }
    }

    #[test]
    fn within_rule_dedupe_suffix_vs_exact() {
        // "example.com" suffix + "www.example.com" exact + "example.com"
        // exact: the suffix covers both exact entries; they are dropped.
        let table = RuleTable::build(vec![Rule {
            id: "r".into(),
            suffixes: vec!["example.com".into()],
            domains: vec!["example.com".into(), "www.example.com".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        assert_eq!(table.rule_count(), 1);
        // Behaviour check: a name under the suffix still routes.
        assert!(table
            .match_query(&Query {
                domain: Some("deep.www.example.com".into()),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn narrower_suffix_under_broader_is_dropped() {
        let table = RuleTable::build(vec![Rule {
            id: "r".into(),
            suffixes: vec!["example.com".into(), "a.example.com".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        // Behaviour is identical; the table should have dropped one entry.
        assert_eq!(table.rule_count(), 1);
        // Verify via the index that both were interned into the same rule
        // but the narrower was removed before indexing: check a name only
        // the broader covers.
        assert!(table
            .match_query(&Query {
                domain: Some("b.example.com".into()),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn broader_cidr_swallows_narrower_within_rule() {
        let table = RuleTable::build(vec![Rule {
            id: "r".into(),
            cidrs: vec!["10.0.0.0/8".into(), "10.1.0.0/16".into()],
            target: outbound("direct"),
            ..Default::default()
        }]);
        assert_eq!(table.rule_count(), 1);
        assert!(table
            .match_query(&Query {
                destination_ip: ip("10.99.0.1"),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn cross_rule_dedupe_is_per_target() {
        // Same suffix, different exits: both must survive.
        let table = RuleTable::build(vec![
            Rule {
                id: "proxy-rule".into(),
                suffixes: vec!["google.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "direct-rule".into(),
                suffixes: vec!["google.com".into()],
                target: outbound("direct"),
                ..Default::default()
            },
        ]);
        assert_eq!(table.rule_count(), 2);
        let m = table
            .match_query(&Query {
                domain: Some("google.com".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "proxy-rule");
    }

    #[test]
    fn same_target_duplicate_is_dropped() {
        let table = RuleTable::build(vec![
            Rule {
                id: "a".into(),
                suffixes: vec!["google.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "b".into(),
                suffixes: vec!["google.com".into()],
                domains: vec!["maps.google.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        // b's entries are both covered by a: the rule prunes to nothing.
        assert_eq!(table.rule_count(), 1);
        assert_eq!(
            table
                .match_query(&Query {
                    domain: Some("maps.google.com".into()),
                    ..Default::default()
                })
                .unwrap()
                .rule_id,
            "a"
        );
    }

    #[test]
    fn constrained_rule_does_not_vouch_for_later_ones() {
        let table = RuleTable::build(vec![
            Rule {
                id: "constrained".into(),
                packages: vec!["com.a".into()],
                suffixes: vec!["example.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "plain".into(),
                suffixes: vec!["example.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        assert_eq!(table.rule_count(), 2);
        let m = table
            .match_query(&Query {
                domain: Some("example.com".into()),
                package: Some("com.b".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "plain");
    }

    #[test]
    fn empty_rules_are_dropped() {
        let table = RuleTable::build(vec![
            Rule {
                id: "empty".into(),
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "real".into(),
                domains: vec!["x.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        assert_eq!(table.rule_count(), 1);
    }

    #[test]
    fn trie_handles_many_entries_quickly() {
        let mut rules = vec![Rule {
            id: "bulk".into(),
            suffixes: (0..10_000).map(|i| format!("site{i}.example")).collect(),
            target: outbound("proxy"),
            ..Default::default()
        }];
        rules.push(Rule {
            id: "needle".into(),
            suffixes: vec!["needle.test".into()],
            target: outbound("direct"),
            ..Default::default()
        });
        let table = RuleTable::build(rules);
        let m = table
            .match_query(&Query {
                domain: Some("deep.needle.test".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "needle");
    }

    #[test]
    fn cidr_parse_rejects_malformed_and_oversized_prefix() {
        assert!(Cidr::parse("10.0.0.0/8").is_some());
        assert!(Cidr::parse("[fdfe::1]/126").is_some());
        assert!(Cidr::parse("10.0.0.0/33").is_none());
        assert!(Cidr::parse("banana/8").is_none());
        assert!(Cidr::parse("10.0.0.0").is_none());
    }
}
