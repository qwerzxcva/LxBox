//! The compiled routing table: phase ordering, redundancy elimination,
//! candidate gathering through the global structures, and the
//! first-hit-wins query path (including logical AND/OR/invert rules).

use super::cidr::{Cidr, RuleCidrs};
use super::domain::{DomainIndex, GlobalNode};
use super::{Match, Query, Rule, Target};
use regex::RegexBuilder;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::net::IpAddr;

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
