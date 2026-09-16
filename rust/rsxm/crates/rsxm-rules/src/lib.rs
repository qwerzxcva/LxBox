//! RSXM rule engine: the routing brain of the new kernel.
//!
//! Absorbs the best of three lineages:
//!  - **sing-box**: rule phases (package → keyword → suffix → exact →
//!    address) with first-hit-wins and the *destination-address family as
//!    one OR group* — verified against `abstractDefaultRule.matchInner`
//!    (`route/rule/rule_abstract.go`), where domain/suffix/keyword/regex and
//!    ip_cidr all satisfy the same match group; RE2 semantics for regexes;
//!  - **mihomo**: a global domain trie instead of linear rule scans, so a
//!    100k-entry geosite behaves like a label walk; candidate rules are
//!    gathered in O(labels) and only those candidates are AND-checked;
//!  - **rsxm**: redundancy elimination at load time so the hot path never
//!    visits a rule that cannot fire, plus logical (AND/OR, invertible,
//!    nestable) rules and the sing-box extended matchers
//!    (network/protocol/ssid/source CIDR).
//!
//! Match semantics encoded here (the same table the Android planner feeds
//! the Go kernel today):
//!  - first hit wins, top to bottom;
//!  - across groups (package, destination address, port, network, …) the
//!    conditions are AND'ed;
//!  - inside the destination-address group the entries are OR'ed;
//!  - redundancy: keyword > suffix > exact domain, broader CIDR > narrower,
//!    earlier rule > later rule, and only a rule whose constraints are
//!    *just* the destination address can vouch for a later rule.

pub mod module;

use regex::RegexBuilder;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::net::IpAddr;

pub use module::RulesModule;

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

/// One rule as loaded from the app state. Logical rules carry `branches`;
/// every matcher group is optional and AND'ed with the others.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Rule {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default)]
    pub suffixes: Vec<String>,
    #[serde(default)]
    pub domains: Vec<String>,
    /// RE2-style patterns (anchored by the writer when exactness matters).
    #[serde(default)]
    pub regexes: Vec<String>,
    /// Destination CIDRs (`"10.0.0.0/8"`, `"[fdfe::1]/126"`).
    #[serde(default)]
    pub cidrs: Vec<String>,
    /// Source CIDRs — the sing-box `source_ip_cidr` matcher.
    #[serde(default)]
    pub source_cidrs: Vec<String>,
    #[serde(default)]
    pub ports: Vec<u16>,
    /// L4 protocols: `tcp` / `udp`.
    #[serde(default)]
    pub networks: Vec<String>,
    /// Sniffed L7 protocols: `http` / `tls` / `dns` / `quic` …
    #[serde(default)]
    pub protocols: Vec<String>,
    /// Wi-Fi SSIDs the rule applies to.
    #[serde(default)]
    pub ssids: Vec<String>,
    /// Negates the whole rule (sing-box `invert`).
    #[serde(default)]
    pub invert: bool,
    // ---- logical rules ---------------------------------------------------
    /// Combine mode for `branches`: `"and"` (default) or `"or"`.
    #[serde(default)]
    pub mode: String,
    #[serde(default)]
    pub branches: Vec<Rule>,
    #[serde(default)]
    pub target: Option<Target>,
}

impl Rule {
    pub fn is_logical(&self) -> bool {
        !self.branches.is_empty()
    }

    /// The strongest class the rule carries — its execution phase.
    pub fn phase(&self) -> Phase {
        if self.is_logical() {
            // A logical rule preempts at the strongest phase its branches
            // reach (package-scoped logical rules must run first).
            self.branches
                .iter()
                .map(Rule::phase)
                .min()
                .unwrap_or(Phase::Other)
        } else if !self.packages.is_empty() {
            Phase::Package
        } else if !self.keywords.is_empty() || !self.regexes.is_empty() {
            // Regex patterns are author-anchored precision matchers like
            // keywords: they must preempt broad suffix/exact rules (a
            // `^ads\d+\.example\.com$` reject beats a `example.com` proxy).
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
        if self.is_logical() {
            return self.branches.iter().any(Rule::has_destination_address);
        }
        !self.keywords.is_empty()
            || !self.suffixes.is_empty()
            || !self.domains.is_empty()
            || !self.regexes.is_empty()
            || !self.cidrs.is_empty()
    }

    /// True when the rule constrains nothing but the destination address —
    /// the only shape that can vouch for a later rule's entries. Logical
    /// rules and anything carrying an AND-side constraint are excluded.
    pub fn is_destination_only(&self) -> bool {
        if self.is_logical() {
            return false;
        }
        self.packages.is_empty()
            && self.ports.is_empty()
            && self.networks.is_empty()
            && self.protocols.is_empty()
            && self.ssids.is_empty()
            && self.source_cidrs.is_empty()
    }

    /// True when the rule has no matcher at all (can never fire).
    pub fn is_empty_matcher(&self) -> bool {
        if self.is_logical() {
            return self.branches.iter().all(Rule::is_empty_matcher);
        }
        !self.has_destination_address()
            && self.packages.is_empty()
            && self.ports.is_empty()
            && self.networks.is_empty()
            && self.protocols.is_empty()
            && self.ssids.is_empty()
            && self.source_cidrs.is_empty()
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
    /// Source address of the connection (source_ip_cidr matcher).
    pub source_ip: Option<IpAddr>,
    pub port: Option<u16>,
    /// `"tcp"` / `"udp"`.
    pub network: Option<String>,
    /// Sniffed L7 protocol.
    pub protocol: Option<String>,
    /// Current Wi-Fi SSID.
    pub ssid: Option<String>,
}

/// Domain trie: reversed-label walk over suffixes plus exact-domain set.
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
        let addr: IpAddr = addr_text
            .trim_matches(|c| c == '[' || c == ']')
            .parse()
            .ok()?;
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
                let mask = if self.prefix == 0 {
                    0
                } else {
                    u32::MAX << (32 - self.prefix)
                };
                (net & mask) == (ip & mask)
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let net = u128::from(net);
                let ip = u128::from(ip);
                let mask = if self.prefix == 0 {
                    0
                } else {
                    u128::MAX << (128 - self.prefix)
                };
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

/// Compiled CIDRs for one rule. Unparseable entries are not indexed for
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

/// Node of the *global* trie: every terminal carries the indices of rules
/// whose suffix/exact ends there. One shared trie replaces per-rule tries,
/// so candidate gathering is one label walk for the whole table.
#[derive(Debug, Default)]
struct GlobalNode {
    children: HashMap<String, GlobalNode>,
    terminals: Vec<usize>,
}

impl GlobalNode {
    fn insert_suffix(&mut self, suffix: &str, rule_idx: usize) {
        let mut node = self;
        for label in suffix.split('.').rev() {
            node = node.children.entry(label.to_string()).or_default();
        }
        if !node.terminals.contains(&rule_idx) {
            node.terminals.push(rule_idx);
        }
    }

    fn insert_exact(&mut self, domain: &str, rule_idx: usize) {
        let mut node = self;
        for label in domain.split('.').rev() {
            node = node.children.entry(label.to_string()).or_default();
        }
        if !node.terminals.contains(&rule_idx) {
            node.terminals.push(rule_idx);
        }
    }

    /// All rule indices whose suffix/exact matches `domain`, in one walk.
    fn collect(&self, domain: &str, out: &mut BTreeSet<usize>) {
        let mut node = self;
        for label in domain.split('.').rev() {
            match node.children.get(label) {
                Some(next) => {
                    node = next;
                    out.extend(node.terminals.iter().copied());
                }
                None => break,
            }
        }
    }
}

/// A compiled routing table.
///
/// Rules are in execution order (phase-sorted, redundancy-pruned). The hot
/// path gathers candidate indices from global structures — a shared domain
/// trie, a keyword inverted map and the CIDR-bearing rule list — then AND-
/// checks only the candidates plus the always-live rules (logical rules and
/// rules with no destination-address group, e.g. port- or SSID-only).
#[derive(Debug, Default)]
pub struct RuleTable {
    rules: Vec<Rule>,
    domain_indexes: Vec<DomainIndex>,
    cidr_indexes: Vec<RuleCidrs>,
    source_cidr_indexes: Vec<RuleCidrs>,
    regex_indexes: Vec<Vec<regex::Regex>>,
    // ---- global candidate structures ----
    global_domains: GlobalNode,
    keyword_index: HashMap<String, Vec<usize>>,
    cidr_rules: Vec<usize>,
    /// Simple rules carrying regexes cannot be gathered from a trie: a
    /// pattern has no labels. Every domain query evaluates these rules'
    /// compiled patterns, so they are indexed as one short list rather
    /// than scanning the whole table.
    regex_rules: Vec<usize>,
    always_rules: Vec<usize>,
}

impl RuleTable {
    /// Builds the table: sorts into phases (stable), removes redundant
    /// entries (both inside a rule and across same-target rules), compiles
    /// regexes and indexes the global domain/CIDR structures.
    pub fn build(mut rules: Vec<Rule>) -> Self {
        // Stable phase sort keeps the user's order inside a class.
        rules.sort_by_key(Rule::phase);

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
                rule.keywords
                    .retain(|kw| !seen.0.iter().any(|prev| prev.contains(kw.as_str())));
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
                rule.cidrs.retain(|raw| {
                    let text = raw.trim();
                    match Cidr::parse(text) {
                        Some(net) => !seen.3.iter().any(|prev| prev.contains_net(&net)),
                        None => !seen.4.contains(text),
                    }
                });

                if !rule.has_destination_address() {
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

        // Per-rule validation structures.
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
        let cidr_indexes: Vec<RuleCidrs> =
            kept.iter().map(|r| RuleCidrs::build(&r.cidrs)).collect();
        let source_cidr_indexes: Vec<RuleCidrs> = kept
            .iter()
            .map(|r| RuleCidrs::build(&r.source_cidrs))
            .collect();
        let regex_indexes: Vec<Vec<regex::Regex>> = kept
            .iter()
            .map(|r| {
                r.regexes
                    .iter()
                    .filter_map(|p| RegexBuilder::new(p).case_insensitive(true).build().ok())
                    .collect()
            })
            .collect();

        // Global candidate structures.
        let mut global_domains = GlobalNode::default();
        let mut keyword_index: HashMap<String, Vec<usize>> = HashMap::new();
        let mut cidr_rules = Vec::new();
        let mut regex_rules = Vec::new();
        let mut always_rules = Vec::new();
        for (idx, rule) in kept.iter().enumerate() {
            for sfx in &rule.suffixes {
                global_domains.insert_suffix(sfx, idx);
            }
            for dom in &rule.domains {
                global_domains.insert_exact(dom, idx);
            }
            for kw in &rule.keywords {
                keyword_index.entry(kw.clone()).or_default().push(idx);
            }
            if !cidr_indexes[idx].nets.is_empty() {
                cidr_rules.push(idx);
            }
            if !rule.is_logical() && !regex_indexes[idx].is_empty() {
                regex_rules.push(idx);
            }
            // Logical rules, and simple rules with no destination group,
            // must be evaluated for every query (their constraints live in
            // the AND groups).
            if rule.is_logical() || !rule.has_destination_address() {
                always_rules.push(idx);
            }
        }

        Self {
            rules: kept,
            domain_indexes,
            cidr_indexes,
            source_cidr_indexes,
            regex_indexes,
            global_domains,
            keyword_index,
            cidr_rules,
            regex_rules,
            always_rules,
        }
    }

    pub fn rule_count(&self) -> usize {
        self.rules.len()
    }

    /// First-hit-wins routing, driven by global candidate gathering.
    pub fn match_query(&self, query: &Query) -> Option<Match> {
        let mut candidates: BTreeSet<usize> = self.always_rules.iter().copied().collect();

        if let Some(domain) = &query.domain {
            // One shared trie walk for every suffix/exact matcher.
            self.global_domains.collect(domain, &mut candidates);
            // Keywords are substring matchers: check each distinct keyword
            // once and map it to all rules carrying it.
            for (keyword, indices) in &self.keyword_index {
                if domain.contains(keyword.as_str()) {
                    candidates.extend(indices.iter().copied());
                }
            }
            // Regexes have no indexable key: test every regex-bearing rule's
            // compiled pattern and stage only the hits for full AND checks.
            for &idx in &self.regex_rules {
                if self.regex_indexes[idx].iter().any(|re| re.is_match(domain)) {
                    candidates.insert(idx);
                }
            }
        }
        if let Some(ip) = query.destination_ip {
            for idx in &self.cidr_rules {
                if self.cidr_indexes[*idx].contains(ip) {
                    candidates.insert(*idx);
                }
            }
        }

        // BTreeSet yields ascending execution order; first valid hit wins.
        for idx in candidates {
            let rule = &self.rules[idx];
            if self.rule_matches(idx, rule, query) {
                if let Some(target) = &rule.target {
                    return Some(Match {
                        rule_id: rule.id.clone(),
                        target: target.clone(),
                    });
                }
            }
        }
        None
    }

    fn rule_matches(&self, idx: usize, rule: &Rule, query: &Query) -> bool {
        let result = if rule.is_logical() {
            self.logical_matches(idx, rule, query)
        } else {
            self.simple_matches(idx, rule, query)
        };
        if rule.invert {
            !result
        } else {
            result
        }
    }

    fn logical_matches(&self, idx: usize, rule: &Rule, query: &Query) -> bool {
        let branches = rule
            .branches
            .iter()
            // Branches carry no independent index slot: evaluate with
            // on-the-fly structures built once per branch (logical rules
            // are few; their entries are small).
            .map(|branch| evaluate_standalone(branch, query))
            .collect::<Vec<_>>();
        let combined = if rule.mode.eq_ignore_ascii_case("or") {
            branches.iter().any(|b| *b)
        } else {
            branches.iter().all(|b| *b)
        };
        // A logical rule may also carry outer AND-side constraints.
        combined && self.and_groups_match(idx, rule, query)
    }

    fn simple_matches(&self, idx: usize, rule: &Rule, query: &Query) -> bool {
        // Destination-address group: OR over domain / suffix / keyword /
        // regex / CIDR — the same grouping the Go kernel uses.
        if rule.has_destination_address() {
            let mut satisfied = false;
            if let Some(domain) = &query.domain {
                if !rule.keywords.is_empty()
                    && rule.keywords.iter().any(|kw| domain.contains(kw.as_str()))
                {
                    satisfied = true;
                }
                if !satisfied
                    && (!rule.suffixes.is_empty() || !rule.domains.is_empty())
                    && self.domain_indexes[idx].matches(domain)
                {
                    satisfied = true;
                }
                if !satisfied && self.regex_indexes[idx].iter().any(|re| re.is_match(domain)) {
                    satisfied = true;
                }
            }
            if !satisfied && !self.cidr_indexes[idx].nets.is_empty() {
                if let Some(ip) = query.destination_ip {
                    satisfied = self.cidr_indexes[idx].contains(ip);
                }
            }
            if !satisfied {
                return false;
            }
        }
        self.and_groups_match(idx, rule, query)
    }

    /// The AND-side groups shared by simple and logical rules: package,
    /// port, network, protocol, SSID, source CIDR.
    fn and_groups_match(&self, idx: usize, rule: &Rule, query: &Query) -> bool {
        if !rule.packages.is_empty() {
            match &query.package {
                Some(pkg) if rule.packages.iter().any(|p| p == pkg) => {}
                _ => return false,
            }
        }
        if !rule.ports.is_empty() {
            match query.port {
                Some(port) if rule.ports.contains(&port) => {}
                _ => return false,
            }
        }
        if !rule.networks.is_empty() {
            match &query.network {
                Some(v) if rule.networks.iter().any(|n| n.eq_ignore_ascii_case(v)) => {}
                _ => return false,
            }
        }
        if !rule.protocols.is_empty() {
            match &query.protocol {
                Some(v) if rule.protocols.iter().any(|p| p.eq_ignore_ascii_case(v)) => {}
                _ => return false,
            }
        }
        if !rule.ssids.is_empty() {
            match &query.ssid {
                Some(v) if rule.ssids.iter().any(|s| s == v) => {}
                _ => return false,
            }
        }
        if !self.source_cidr_indexes[idx].nets.is_empty() {
            match query.source_ip {
                Some(ip) if self.source_cidr_indexes[idx].contains(ip) => {}
                _ => return false,
            }
        }
        true
    }
}

fn match_source_cidrs(raws: &[String], source_ip: Option<IpAddr>) -> bool {
    match source_ip {
        Some(ip) => raws
            .iter()
            .filter_map(|raw| Cidr::parse(raw.trim()))
            .any(|net| net.contains_addr(ip)),
        None => false,
    }
}

/// Evaluates a logical branch as a self-contained rule against the query.
/// Used for nested branches that are not individually indexed; regexes and
/// CIDRs inside branches are parsed per evaluation (logical trees stay
/// small by construction).
fn evaluate_standalone(rule: &Rule, query: &Query) -> bool {
    let mut result = if rule.is_logical() {
        let branches: Vec<bool> = rule
            .branches
            .iter()
            .map(|b| evaluate_standalone(b, query))
            .collect();
        if rule.mode.eq_ignore_ascii_case("or") {
            branches.iter().any(|b| *b)
        } else {
            branches.iter().all(|b| *b)
        }
    } else {
        let mut dest = !rule.has_destination_address();
        if !dest {
            if let Some(domain) = &query.domain {
                dest |= rule.keywords.iter().any(|kw| domain.contains(kw.as_str()));
                dest |= rule
                    .suffixes
                    .iter()
                    .any(|sfx| domain == sfx || domain.ends_with(&format!(".{sfx}")));
                dest |= rule.domains.iter().any(|d| d == domain);
                dest |= rule.regexes.iter().any(|p| {
                    RegexBuilder::new(p)
                        .case_insensitive(true)
                        .build()
                        .map(|re| re.is_match(domain))
                        .unwrap_or(false)
                });
            }
            if !dest {
                if let Some(ip) = query.destination_ip {
                    dest |= rule
                        .cidrs
                        .iter()
                        .filter_map(|raw| Cidr::parse(raw.trim()))
                        .any(|net| net.contains_addr(ip));
                }
            }
        }
        dest
    };
    if result {
        // AND-side constraints on the branch itself.
        if let Some(pkg) = &query.package {
            if !rule.packages.is_empty() && !rule.packages.iter().any(|p| p == pkg) {
                result = false;
            }
        } else if !rule.packages.is_empty() {
            result = false;
        }
        if result {
            match query.port {
                Some(port) if rule.ports.is_empty() || rule.ports.contains(&port) => {}
                None if rule.ports.is_empty() => {}
                _ => result = false,
            }
        }
        if result && !rule.source_cidrs.is_empty() {
            result = match_source_cidrs(&rule.source_cidrs, query.source_ip);
        }
    }
    if rule.invert {
        !result
    } else {
        result
    }
}

/// keyword > suffix > exact domain, broader CIDR > narrower, inside one rule.
fn dedupe_within(rule: &mut Rule) {
    let keywords = rule.keywords.clone();
    let hit_by_keyword = |value: &str| keywords.iter().any(|kw| value.contains(kw.as_str()));

    let suffixes_in = rule.suffixes.clone();
    let kept_suffixes: Vec<String> = suffixes_in
        .iter()
        .filter(|sfx| {
            if hit_by_keyword(sfx) {
                return false;
            }
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
        let table = RuleTable::build(vec![Rule {
            id: "mixed".into(),
            suffixes: vec!["example.com".into()],
            cidrs: vec!["10.0.0.0/8".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                domain: Some("a.example.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                destination_ip: ip("10.2.3.4"),
                ..Default::default()
            })
            .is_some());
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
        let table = RuleTable::build(vec![Rule {
            id: "r".into(),
            suffixes: vec!["example.com".into()],
            domains: vec!["example.com".into(), "www.example.com".into()],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        assert_eq!(table.rule_count(), 1);
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
        assert_eq!(table.rule_count(), 1);
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

    #[test]
    fn regex_matchers_fire_with_re2_semantics() {
        let table = RuleTable::build(vec![
            Rule {
                id: "digits".into(),
                regexes: vec![r"^ads\d+\.example\.com$".into()],
                target: Some(Target::Reject),
                ..Default::default()
            },
            Rule {
                id: "fallback".into(),
                suffixes: vec!["example.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
        ]);
        // Real backreference-free patterns the old hand-rolled checker
        // could not express.
        let m = table
            .match_query(&Query {
                domain: Some("ads42.example.com".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.target, Target::Reject);
        assert!(table
            .match_query(&Query {
                domain: Some("www.example.com".into()),
                ..Default::default()
            })
            .is_some());
        // Case-insensitive like sing-box's domain_regex.
        assert!(table
            .match_query(&Query {
                domain: Some("ADS9.example.com".into()),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn network_and_protocol_groups_are_anded() {
        let table = RuleTable::build(vec![Rule {
            id: "udp-dns".into(),
            networks: vec!["udp".into()],
            protocols: vec!["dns".into()],
            target: outbound("reject"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                network: Some("udp".into()),
                protocol: Some("dns".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                network: Some("tcp".into()),
                protocol: Some("dns".into()),
                ..Default::default()
            })
            .is_none());
        assert!(table
            .match_query(&Query {
                network: Some("udp".into()),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn source_cidr_rules_require_source_address() {
        let table = RuleTable::build(vec![Rule {
            id: "lan-source".into(),
            source_cidrs: vec!["192.168.0.0/16".into()],
            target: outbound("direct"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                source_ip: ip("192.168.1.5"),
                destination_ip: ip("1.1.1.1"),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                source_ip: ip("10.0.0.2"),
                ..Default::default()
            })
            .is_none());
        assert!(table.match_query(&Query::default()).is_none());
    }

    #[test]
    fn logical_or_and_and_with_invert() {
        // OR logical: either suffix matches → proxy.
        let table = RuleTable::build(vec![Rule {
            id: "or-rule".into(),
            mode: "or".into(),
            branches: vec![
                Rule {
                    suffixes: vec!["a.com".into()],
                    ..Default::default()
                },
                Rule {
                    suffixes: vec!["b.com".into()],
                    ..Default::default()
                },
            ],
            target: outbound("proxy"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                domain: Some("x.a.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                domain: Some("x.b.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                domain: Some("x.c.com".into()),
                ..Default::default()
            })
            .is_none());

        // Invert flips the whole logical result.
        let inverted = RuleTable::build(vec![Rule {
            id: "not-a".into(),
            mode: "or".into(),
            invert: true,
            branches: vec![Rule {
                suffixes: vec!["a.com".into()],
                ..Default::default()
            }],
            target: outbound("direct"),
            ..Default::default()
        }]);
        assert!(inverted
            .match_query(&Query {
                domain: Some("b.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(inverted
            .match_query(&Query {
                domain: Some("a.com".into()),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn first_hit_ordering_survives_candidate_gathering() {
        // Two suffix rules both candidate for the query; the earlier rule
        // (post phase sort, user order preserved) must win even though the
        // global trie returns both.
        let table = RuleTable::build(vec![
            Rule {
                id: "first".into(),
                suffixes: vec!["example.com".into()],
                target: outbound("proxy"),
                ..Default::default()
            },
            Rule {
                id: "second".into(),
                suffixes: vec!["api.example.com".into()],
                target: outbound("direct"),
                ..Default::default()
            },
        ]);
        let m = table
            .match_query(&Query {
                domain: Some("api.example.com".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "first");
    }
}
