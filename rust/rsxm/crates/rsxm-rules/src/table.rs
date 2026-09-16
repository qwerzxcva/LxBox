//! The compiled routing table: phase ordering, redundancy elimination
//! and the first-hit-wins query path.

use super::cidr::{Cidr, RuleCidrs};
use super::domain::DomainIndex;
use super::{Match, Query, Rule, Target};
use std::collections::{HashMap, HashSet};

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
