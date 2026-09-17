//! Domain index: a reversed-label trie shared by suffix and exact matchers.
//! `GlobalNode` is the whole-table trie the compiled `RuleTable` consults
//! to gather candidate rule indices in one walk.

use std::collections::{BTreeSet, HashMap, HashSet};

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

/// Node of the *global* trie: every terminal carries the indices of rules
/// whose suffix/exact ends there. One shared trie replaces per-rule tries,
/// so candidate gathering is one label walk for the whole table.
#[derive(Debug, Default)]
pub(crate) struct GlobalNode {
    children: HashMap<String, GlobalNode>,
    terminals: Vec<usize>,
}

impl GlobalNode {
    pub(crate) fn insert_suffix(&mut self, suffix: &str, rule_idx: usize) {
        let mut node = self;
        for label in suffix.split('.').rev() {
            node = node.children.entry(label.to_string()).or_default();
        }
        if !node.terminals.contains(&rule_idx) {
            node.terminals.push(rule_idx);
        }
    }

    pub(crate) fn insert_exact(&mut self, domain: &str, rule_idx: usize) {
        let mut node = self;
        for label in domain.split('.').rev() {
            node = node.children.entry(label.to_string()).or_default();
        }
        if !node.terminals.contains(&rule_idx) {
            node.terminals.push(rule_idx);
        }
    }

    /// All rule indices whose suffix/exact matches `domain`, in one walk.
    pub(crate) fn collect(&self, domain: &str, out: &mut BTreeSet<usize>) {
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
