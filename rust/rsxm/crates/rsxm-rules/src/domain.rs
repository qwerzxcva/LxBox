use std::collections::{HashMap, HashSet};

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
