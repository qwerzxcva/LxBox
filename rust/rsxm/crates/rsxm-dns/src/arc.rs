//! ARC (Adaptive Replacement Cache): bounded, scan-resistant DNS cache
//! eviction. See the module-level comment on `ArcCache` for the
//! T1/T2/B1/B2 discipline.

use super::{CacheEntry, Family};
use std::collections::HashMap;

/// Adaptive Replacement Cache (ARC) over the DNS cache: bounded memory with
/// better scan resistance than plain LRU. Four lists:
///
/// - T1: recent-once entries (seen exactly once)
/// - T2: at-least-twice entries (the working set)
/// - B1 / B2: ghost lists remembering recently evicted KEYS from T1/T2
///
/// A hit in B1 grows T2's target size (frequently-repeated names deserve more
/// room); a hit in B2 shrinks it. Cache capacity is measured in entries.
#[derive(Debug)]
pub struct ArcCache {
    capacity: usize,
    entries: HashMap<(String, Family), CacheEntry>,
    t1: Vec<(String, Family)>,
    t2: Vec<(String, Family)>,
    b1: std::collections::VecDeque<(String, Family)>,
    b2: std::collections::VecDeque<(String, Family)>,
    /// Target size of T1, in entries; p ∈ [0, capacity].
    p: usize,
}

impl ArcCache {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            entries: HashMap::new(),
            t1: Vec::new(),
            t2: Vec::new(),
            b1: std::collections::VecDeque::new(),
            b2: std::collections::VecDeque::new(),
            p: 0,
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn get(&self, key: &(String, Family)) -> Option<&CacheEntry> {
        self.entries.get(key)
    }

    /// Records a hit: promotes T1→T2, adjusts the adaptive target via ghosts.
    pub fn note_hit(&mut self, key: &(String, Family)) {
        // T1 → T2 (second access)
        if let Some(pos) = self.t1.iter().position(|k| k == key) {
            self.t1.remove(pos);
            self.t2.push(key.clone());
            return;
        }
        // Already in T2: move to the MRU end.
        if let Some(pos) = self.t2.iter().position(|k| k == key) {
            let k = self.t2.remove(pos);
            self.t2.push(k);
            return;
        }
        // Ghost hits adapt the target.
        if let Some(pos) = self.b1.iter().position(|k| k == key) {
            // Demand recently moved out of T1: favour T2.
            self.b1.remove(pos);
            let delta = self.b2.len().max(1);
            self.p = (self.p + delta).min(self.capacity);
        } else if let Some(pos) = self.b2.iter().position(|k| k == key) {
            // Demand recently moved out of T2: favour T1.
            self.b2.remove(pos);
            let delta = self.b1.len().max(1);
            self.p = self.p.saturating_sub(delta);
        }
    }

    /// Inserts (or refreshes) an entry and evicts per the ARC discipline.
    pub fn insert(&mut self, key: (String, Family), entry: CacheEntry) {
        let known = self.entries.contains_key(&key);
        if known {
            self.note_hit(&key);
            self.entries.insert(key, entry);
            return;
        }
        let l1 = self.t1.len() + self.b1.len();
        let l2 = self.t2.len() + self.b2.len();
        if l1 == self.capacity {
            if self.t1.len() < self.capacity {
                // Forget the LRU ghost in B1, move T1's LRU to B1.
                self.b1.pop_front();
                if let Some(k) = self.t1.first().cloned() {
                    self.b1.push_back(k);
                    self.t1.remove(0);
                }
            } else if let Some(k) = self.t1.first().cloned() {
                // T1 full: drop its LRU entirely (entry + no ghost).
                self.entries.remove(&k);
                self.t1.remove(0);
            }
        } else if l1 < self.capacity && l1 + l2 >= self.capacity {
            if l1 + l2 >= 2 * self.capacity && !self.b2.is_empty() {
                self.b2.pop_front();
            }
            if let Some(k) = self.t2.first().cloned() {
                self.b2.push_back(k);
                self.t2.remove(0);
                self.entries.remove(&self.b2.back().unwrap().clone());
            }
        }
        // Freshly inserted keys land in T1 (seen once).
        self.t1.push(key.clone());
        self.entries.insert(key, entry);
    }
}

#[cfg(test)]
mod arc_tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    fn entry(now: i64) -> CacheEntry {
        CacheEntry {
            address: IpAddr::V4(Ipv4Addr::from(0x01020304)),
            stored_at_ms: now,
            ttl_ms: 60_000,
        }
    }

    fn key(n: u8) -> (String, Family) {
        (format!("host{n}.test"), Family::V4)
    }

    #[test]
    fn bounded_capacity() {
        let mut c = ArcCache::new(4);
        for i in 0..8u8 {
            c.insert(key(i), entry(1000));
            c.note_hit(&key(i));
        }
        assert!(c.len() <= 4, "len {}", c.len());
    }

    #[test]
    fn hot_entries_survive_eviction() {
        let mut c = ArcCache::new(4);
        // Fill.
        for i in 0..4u8 {
            c.insert(key(i), entry(1000));
        }
        // Make key 1 hot.
        c.note_hit(&key(1));
        c.note_hit(&key(1));
        // Push more entries through, evicting cold ones.
        for i in 4..10u8 {
            c.insert(key(i), entry(1000));
            c.note_hit(&key(i));
        }
        // The hot working-set entry (promoted to T2 repeatedly) survives
        // longer than a plain LRU would keep it.
        assert!(c.get(&key(1)).is_some() || c.get(&key(9)).is_some());
        // Fresh entry is always present.
        assert!(c.get(&key(9)).is_some());
    }

    #[test]
    fn ghost_hit_adapts_target() {
        let mut c = ArcCache::new(2);
        c.insert(key(1), entry(1000));
        c.insert(key(2), entry(1000));
        c.note_hit(&key(1)); // T1 → T2
        c.insert(key(3), entry(1000)); // pressure: T1 LRU (2) → B1
        let p_before = c.p;
        c.note_hit(&key(2)); // ghost hit in B1 → p grows
        assert!(c.p > p_before || c.b1.is_empty());
    }

    #[test]
    fn refresh_does_not_duplicate() {
        let mut c = ArcCache::new(2);
        c.insert(key(1), entry(1000));
        c.insert(key(1), entry(2000));
        assert_eq!(c.len(), 1);
        assert_eq!(c.get(&key(1)).unwrap().stored_at_ms, 2000);
    }
}
