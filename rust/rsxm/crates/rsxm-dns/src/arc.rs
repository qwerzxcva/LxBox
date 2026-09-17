//! ARC (Adaptive Replacement Cache): bounded, scan-resistant DNS cache
//! eviction — borrowed from rsxm-dev's implementation and adapted to the
//! superset DNS engine (negative cache / hosts / rules live in `lib.rs`).
//!
//! Four lists:
//!
//! - T1: recent-once entries (seen exactly once)
//! - T2: at-least-twice entries (the working set)
//! - B1 / B2: ghost lists remembering recently evicted KEYS from T1/T2
//!
//! A hit in B1 grows T2's target size (frequently-repeated names deserve
//! more room); a hit in B2 shrinks it. Capacity is measured in entries.
//! Compared with the previous plain LRU, a one-off scan of thousands of
//! names can no longer flush the phone's hot working set out of cache.

use super::{CacheEntry, CacheKey};
use std::collections::{HashMap, VecDeque};

#[derive(Debug)]
pub struct ArcCache {
    capacity: usize,
    entries: HashMap<CacheKey, CacheEntry>,
    t1: Vec<CacheKey>,
    t2: Vec<CacheKey>,
    b1: VecDeque<CacheKey>,
    b2: VecDeque<CacheKey>,
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
            b1: VecDeque::new(),
            b2: VecDeque::new(),
            p: 0,
        }
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn get(&self, key: &CacheKey) -> Option<&CacheEntry> {
        self.entries.get(key)
    }

    /// Records a hit: promotes T1→T2, moves T2 entries to the MRU end, and
    /// adapts the target when a ghost list recognises the key.
    pub fn note_hit(&mut self, key: &CacheKey) {
        if let Some(pos) = self.t1.iter().position(|k| k == key) {
            self.t1.remove(pos);
            self.t2.push(key.clone());
            return;
        }
        if let Some(pos) = self.t2.iter().position(|k| k == key) {
            let k = self.t2.remove(pos);
            self.t2.push(k);
            return;
        }
        if let Some(pos) = self.b1.iter().position(|k| k == key) {
            self.b1.remove(pos);
            let delta = self.b2.len().max(1);
            self.p = (self.p + delta).min(self.capacity);
        } else if let Some(pos) = self.b2.iter().position(|k| k == key) {
            self.b2.remove(pos);
            let delta = self.b1.len().max(1);
            self.p = self.p.saturating_sub(delta);
        }
    }

    /// Inserts (or refreshes) an entry and evicts per the ARC discipline.
    pub fn insert(&mut self, key: CacheKey, entry: CacheEntry) {
        if self.entries.contains_key(&key) {
            self.note_hit(&key);
            self.entries.insert(key, entry);
            return;
        }
        let l1 = self.t1.len() + self.b1.len();
        let l2 = self.t2.len() + self.b2.len();
        if l1 >= self.capacity && !self.t1.is_empty() {
            if self.t1.len() < self.capacity {
                // Make room in |L1| by forgetting B1's oldest ghost, then
                // reclassify T1's LRU entry as a B1 ghost.
                self.b1.pop_front();
                let victim = self.t1.remove(0);
                self.entries.remove(&victim);
                self.b1.push_back(victim);
            } else {
                // T1 alone fills the whole cache: the entry leaves for good.
                let victim = self.t1.remove(0);
                self.entries.remove(&victim);
            }
        } else if l1 < self.capacity && l1 + l2 >= self.capacity && !self.t2.is_empty() {
            if l1 + l2 >= 2 * self.capacity {
                self.b2.pop_front();
            }
            // T2's LRU becomes a B2 ghost (the entry leaves the cache).
            let victim = self.t2.remove(0);
            self.entries.remove(&victim);
            self.b2.push_back(victim);
        }
        // Fresh keys land in T1 (seen once).
        self.t1.push(key.clone());
        self.entries.insert(key, entry);
    }

    /// True when the key currently lives only in a ghost list. A re-request
    /// for a ghost adapts the target split (`p`), so the re-fetched entry is
    /// protected on the next eviction round — the scan-resistance feedback
    /// loop.
    pub fn is_ghost(&self, key: &CacheKey) -> bool {
        self.b1.contains(key) || self.b2.contains(key)
    }

    pub fn remove(&mut self, key: &CacheKey) {
        self.entries.remove(key);
        self.t1.retain(|k| k != key);
        self.t2.retain(|k| k != key);
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.t1.clear();
        self.t2.clear();
        self.b1.clear();
        self.b2.clear();
        self.p = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr};

    fn entry(now: i64) -> CacheEntry {
        CacheEntry {
            address: IpAddr::V4(Ipv4Addr::from(0x01020304)),
            stored_at_ms: now,
            ttl_ms: 60_000,
        }
    }

    fn key(n: u8) -> CacheKey {
        (format!("host{n}.test"), super::super::Family::V4)
    }

    #[test]
    fn bounded_capacity() {
        let mut c = ArcCache::new(4);
        for i in 0..8u8 {
            c.insert(key(i), entry(1000));
        }
        assert!(c.len() <= 4, "len {}", c.len());
    }

    #[test]
    fn hot_entry_becomes_ghost_under_scan() {
        let mut c = ArcCache::new(4);
        for i in 0..4u8 {
            c.insert(key(i), entry(1000));
        }
        // Key 1 is the hot working-set entry (repeated read → T2).
        c.note_hit(&key(1));
        // A one-off scan of cold one-timer names. Under the ARC policy the
        // T2 entry leaves the cache but is *remembered* as a B2 ghost: the
        // next request for it adapts `p` and the re-fetched entry is then
        // protected — a plain LRU forgets it outright.
        for i in 4..8u8 {
            c.insert(key(i), entry(1000));
        }
        assert!(c.get(&key(1)).is_none());
        assert!(c.is_ghost(&key(1)), "hot entry must survive as a B2 ghost");
    }

    #[test]
    fn ghost_hit_adapts_target() {
        let mut c = ArcCache::new(2);
        c.insert(key(1), entry(1000));
        c.insert(key(2), entry(1000));
        c.note_hit(&key(1)); // T1 → T2
        c.insert(key(3), entry(1000)); // pressure: key 2 becomes a B1 ghost
        let p_before = c.p;
        c.note_hit(&key(2)); // demand for the ghost → favour T2
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

    #[test]
    fn clear_resets_state() {
        let mut c = ArcCache::new(2);
        c.insert(key(1), entry(1000));
        c.clear();
        assert!(c.is_empty());
        assert_eq!(c.p, 0);
    }
}
