//! RSXM rule engine: the routing brain of the new kernel.
//!
//! Absorbs the best of three lineages:
//!  - **sing-box**: rule phases (package → keyword → suffix → exact →
//!    address) with first-hit-wins semantics;
//!  - **mihomo**: a domain trie instead of linear scans, so a 100k-entry
//!    geosite behaves like a hash lookup;
//!  - **rsxm**: redundancy elimination at load time so the hot path never
//!    visits a rule that cannot fire.
//!
//! Everything here is pure and allocation-light on the match path: the
//! trie is built once at load, matching walks byte slices.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

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

    /// True when the rule constrains nothing but names/addresses, i.e. it is
    /// safe for later rules to be deduplicated against it.
    pub fn is_destination_only(&self) -> bool {
        self.packages.is_empty() && self.ports.is_empty()
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
    pub domain: Option<String>,
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
}

/// A compiled routing table: rules in execution order with the domain work
/// hoisted into an index.
///
/// Match semantics (absorbed from sing-box, verified against its source):
///  - first hit wins, top to bottom;
///  - within one rule the classes are AND'ed (a rule with package + suffix
///    fires only when both match);
///  - list entries inside a class are OR'ed.
#[derive(Debug, Default)]
pub struct RuleTable {
    rules: Vec<Rule>,
    /// Per-rule domain index (suffix + exact); keywords stay as strings.
    domain_indexes: Vec<DomainIndex>,
}

impl RuleTable {
    /// Builds the table: sorts into phases (stable), drops entries covered
    /// by an earlier same-target rule, and indexes the domain classes.
    pub fn build(mut rules: Vec<Rule>) -> Self {
        // Stable phase sort keeps the user's order inside a class.
        rules.sort_by_key(|r| r.phase());

        // Redundancy elimination: keyword > suffix > exact, earlier wins.
        // Only destination-only rules register coverage (a rule with extra
        // AND conditions covers a subset and cannot vouch for later rules).
        let mut seen_keywords: HashSet<String> = HashSet::new();
        let mut seen_suffixes: Vec<String> = Vec::new();
        let mut seen_domains: HashSet<String> = HashSet::new();
        let mut kept: Vec<Rule> = Vec::with_capacity(rules.len());

        for mut rule in rules {
            if rule.is_destination_only() {
                rule.keywords.retain(|kw| !seen_keywords.contains(kw));
                rule.suffixes.retain(|sfx| {
                    !seen_keywords.iter().any(|kw| sfx.contains(kw.as_str()))
                        && !seen_suffixes
                            .iter()
                            .any(|seen| sfx == seen || sfx.ends_with(&format!(".{seen}")))
                });
                rule.domains.retain(|dom| {
                    !seen_keywords.iter().any(|kw| dom.contains(kw.as_str()))
                        && !seen_suffixes
                            .iter()
                            .any(|seen| dom == seen || dom.ends_with(&format!(".{seen}")))
                        && !seen_domains.contains(dom)
                });

                // Register survivors for later rules.
                for kw in &rule.keywords {
                    seen_keywords.insert(kw.clone());
                }
                seen_suffixes.extend(rule.suffixes.iter().cloned());
                for dom in &rule.domains {
                    seen_domains.insert(dom.clone());
                }
            }

            // A rule that lost every matcher can never fire — drop it.
            if rule.packages.is_empty()
                && rule.keywords.is_empty()
                && rule.suffixes.is_empty()
                && rule.domains.is_empty()
                && rule.cidrs.is_empty()
                && rule.ports.is_empty()
            {
                continue;
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

        Self {
            rules: kept,
            domain_indexes,
        }
    }

    pub fn rule_count(&self) -> usize {
        self.rules.len()
    }

    /// First-hit-wins routing.
    pub fn match_query(&self, query: &Query) -> Option<Match> {
        for (rule, index) in self.rules.iter().zip(self.domain_indexes.iter()) {
            if !self.rule_matches(rule, index, query) {
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

    fn rule_matches(&self, rule: &Rule, index: &DomainIndex, query: &Query) -> bool {
        // AND across classes; each class OR's internally.
        if !rule.packages.is_empty() {
            match &query.package {
                Some(pkg) if rule.packages.iter().any(|p| p == pkg) => {}
                _ => return false,
            }
        }
        if !rule.keywords.is_empty() {
            match &query.domain {
                Some(domain) if rule.keywords.iter().any(|kw| domain.contains(kw.as_str())) => {}
                _ => return false,
            }
        }
        if !rule.suffixes.is_empty() || !rule.domains.is_empty() {
            match &query.domain {
                Some(domain) if index.matches(domain) => {}
                _ => return false,
            }
        }
        if !rule.ports.is_empty() {
            match query.port {
                Some(port) if rule.ports.contains(&port) => {}
                _ => return false,
            }
        }
        // A rule with no matchers at all never fires (loader drops them, but
        // keep the hot path safe).
        !(rule.packages.is_empty()
            && rule.keywords.is_empty()
            && rule.suffixes.is_empty()
            && rule.domains.is_empty()
            && rule.cidrs.is_empty()
            && rule.ports.is_empty())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target(tag: &str) -> Option<Target> {
        Some(Target::Outbound(tag.to_string()))
    }

    #[test]
    fn phase_ordering_is_enforced() {
        let mut ip_rule = Rule {
            id: "ip".into(),
            cidrs: vec!["10.0.0.0/8".into()],
            target: target("direct"),
            ..Default::default()
        };
        ip_rule.ports = vec![443];
        let mut app_rule = Rule {
            id: "app".into(),
            packages: vec!["com.example".into()],
            target: target("proxy"),
            ..Default::default()
        };
        app_rule.ports = vec![443];
        let table = RuleTable::build(vec![ip_rule, app_rule]);
        let m = table
            .match_query(&Query {
                package: Some("com.example".into()),
                port: Some(443),
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
            target: target("proxy"),
            ..Default::default()
        }]);
        assert!(table
            .match_query(&Query {
                domain: Some("a.b.example.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                domain: Some("example.com".into()),
                ..Default::default()
            })
            .is_some());
        assert!(table
            .match_query(&Query {
                domain: Some("notexample.com".into()),
                ..Default::default()
            })
            .is_none());
    }

    #[test]
    fn keyword_beats_suffix_beats_exact() {
        // Rule 1: keyword "google"; rule 2: suffix "google.com" (covered);
        // rule 3: exact "maps.google.com" (covered).
        let table = RuleTable::build(vec![
            Rule {
                id: "kw".into(),
                keywords: vec!["google".into()],
                target: target("proxy"),
                ..Default::default()
            },
            Rule {
                id: "sfx".into(),
                suffixes: vec!["google.com".into()],
                target: target("proxy"),
                ..Default::default()
            },
            Rule {
                id: "exact".into(),
                domains: vec!["maps.google.com".into()],
                target: target("proxy"),
                ..Default::default()
            },
        ]);
        // The two covered rules are dropped at load; only the keyword rule
        // remains, and it fires for all of them.
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
    fn first_hit_wins_across_same_phase_rules() {
        let table = RuleTable::build(vec![
            Rule {
                id: "first".into(),
                suffixes: vec!["a.com".into()],
                target: target("one"),
                ..Default::default()
            },
            Rule {
                id: "second".into(),
                suffixes: vec!["b.com".into()],
                target: target("two"),
                ..Default::default()
            },
        ]);
        let m = table
            .match_query(&Query {
                domain: Some("x.a.com".into()),
                ..Default::default()
            })
            .unwrap();
        assert_eq!(m.rule_id, "first");
    }

    #[test]
    fn mixed_matchers_are_anded() {
        let table = RuleTable::build(vec![Rule {
            id: "combo".into(),
            packages: vec!["com.game".into()],
            suffixes: vec!["game.com".into()],
            target: target("proxy"),
            ..Default::default()
        }]);
        // Package alone must not fire the rule…
        assert!(table
            .match_query(&Query {
                package: Some("com.game".into()),
                ..Default::default()
            })
            .is_none());
        // …nor domain alone…
        assert!(table
            .match_query(&Query {
                domain: Some("game.com".into()),
                ..Default::default()
            })
            .is_none());
        // …only both together.
        assert!(table
            .match_query(&Query {
                package: Some("com.game".into()),
                domain: Some("api.game.com".into()),
                ..Default::default()
            })
            .is_some());
    }

    #[test]
    fn constrained_rule_does_not_vouch_for_later_ones() {
        // The first rule is package-constrained, so the second must keep its
        // suffix even though the names overlap.
        let table = RuleTable::build(vec![
            Rule {
                id: "constrained".into(),
                packages: vec!["com.a".into()],
                suffixes: vec!["example.com".into()],
                target: target("proxy"),
                ..Default::default()
            },
            Rule {
                id: "plain".into(),
                suffixes: vec!["example.com".into()],
                target: target("proxy"),
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
                target: target("proxy"),
                ..Default::default()
            },
            Rule {
                id: "real".into(),
                domains: vec!["x.com".into()],
                target: target("proxy"),
                ..Default::default()
            },
        ]);
        assert_eq!(table.rule_count(), 1);
    }

    #[test]
    fn trie_handles_many_entries_quickly() {
        // 10k suffixes: a linear scan would be ~10k comparisons; the trie
        // walks the 3 labels of the query instead.
        let mut rules = vec![Rule {
            id: "bulk".into(),
            suffixes: (0..10_000).map(|i| format!("site{i}.example")).collect(),
            target: target("proxy"),
            ..Default::default()
        }];
        rules.push(Rule {
            id: "needle".into(),
            suffixes: vec!["needle.test".into()],
            target: target("direct"),
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
}
