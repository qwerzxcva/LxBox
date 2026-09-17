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

use serde::{Deserialize, Serialize};
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

mod cidr;
mod domain;
mod table;

pub use cidr::Cidr;
pub use domain::DomainIndex;
pub use table::RuleTable;

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
