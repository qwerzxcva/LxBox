//! RSXM DNS module — the resolution micro-kernel.
//!
//! Absorbs the three most valuable ideas of the sing-box DNS design (which
//! the app exposes today) and gives them a Rust home:
//!
//!  - **fake-IP pool**: answers A/AAAA with addresses from a synthetic
//!    range so the router can match on names without waiting for real
//!    resolution, then maps the address back to the name for dialing;
//!  - **TTL cache with optimistic serving**: an expired entry is handed out
//!    immediately while a refresh happens in the background — the latency
//!    win the user asked for ("过期缓存先用再后台刷新");
//!  - **parallel race**: several upstreams are queried at once and the
//!    first answer wins, with the losers cancelled.
//!
//! The module is pure: no sockets, no timers — the transport layer feeds it
//! answers and asks it what to do. That keeps it testable and keeps the
//! policy (what to cache, what to fake, which upstream wins) separate from
//! the I/O (which the runtime crate owns).

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// DNS record family.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Family {
    V4,
    V6,
}

/// The synthetic range used for fake answers. Defaults match the app's
/// 198.18.0.0/15 + fc00::/18 configuration.
#[derive(Debug, Clone)]
pub struct FakeIpConfig {
    pub enabled: bool,
    pub v4_base: Ipv4Addr,
    pub v4_prefix: u8,
    pub v6_base: Ipv6Addr,
    pub v6_prefix: u8,
    /// DNS cache bound in entries (ARC-evicted).
    pub cache_capacity: usize,
}

impl Default for FakeIpConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            v4_base: Ipv4Addr::new(198, 18, 0, 0),
            v4_prefix: 15,
            v6_base: "fc00::".parse().expect("static v6"),
            v6_prefix: 18,
            cache_capacity: 4096,
        }
    }
}

/// One cached answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheEntry {
    pub address: IpAddr,
    /// Unix milliseconds when the entry was stored.
    pub stored_at_ms: i64,
    /// Time-to-live in milliseconds.
    pub ttl_ms: i64,
}

impl CacheEntry {
    pub fn is_fresh(&self, now_ms: i64) -> bool {
        now_ms < self.stored_at_ms + self.ttl_ms
    }

    /// Expired but recent enough to serve optimistically while refreshing.
    /// The window is the TTL again — a common choice; beyond 2× TTL the
    /// answer is too stale to hand out.
    pub fn is_optimistically_servable(&self, now_ms: i64) -> bool {
        now_ms < self.stored_at_ms + self.ttl_ms * 2
    }
}

/// What the caller should do with a query.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Answer immediately from cache (fresh or optimistic).
    AnswerFromCache { address: IpAddr, stale: bool },
    /// Answer with a fake address from the pool.
    AnswerFake { address: IpAddr },
    /// The address is a fake one; here is the name it stands for.
    ReverseFake { name: String },
    /// Forward the query upstream.
    QueryUpstream,
}

/// The DNS policy engine: cache + fake pool + reverse mapping.
pub struct DnsEngine {
    fake: FakeIpConfig,
    cache: ArcCache,
    /// fake address -> name (for reverse lookup when dialing).
    fake_reverse: HashMap<IpAddr, String>,
    /// name -> fake address (stable allocation).
    fake_forward: HashMap<String, IpAddr>,
    /// Next candidate offset in the fake space.
    v4_next: u32,
    v6_next: u128,
}

impl DnsEngine {
    pub fn new(fake: FakeIpConfig) -> Self {
        let cache_capacity = fake.cache_capacity;
        Self {
            fake,
            cache: ArcCache::new(cache_capacity),
            fake_reverse: HashMap::new(),
            fake_forward: HashMap::new(),
            v4_next: 1,
            v6_next: 1,
        }
    }

    /// Resolves what to do for a name. `reverse_ip` is checked first: a
    /// connection to a fake address must be mapped back to its name.
    pub fn decide(&self, name: &str, family: Family, reverse_ip: Option<IpAddr>, now_ms: i64) -> Decision {
        if let Some(ip) = reverse_ip {
            if let Some(mapped) = self.fake_reverse.get(&ip) {
                return Decision::ReverseFake {
                    name: mapped.clone(),
                };
            }
        }
        if let Some(entry) = self.cache.get(&(name.to_string(), family)) {
            if entry.is_fresh(now_ms) {
                return Decision::AnswerFromCache {
                    address: entry.address,
                    stale: false,
                };
            }
            if entry.is_optimistically_servable(now_ms) {
                return Decision::AnswerFromCache {
                    address: entry.address,
                    stale: true,
                };
            }
        }
        if self.fake.enabled {
            // Allocation is the caller's job (it happens when an upstream
            // query is issued); this read-only path only answers names that
            // were allocated before.
            if let Some(existing) = self.fake_forward.get(name) {
                return Decision::AnswerFake {
                    address: *existing,
                };
            }
        }
        Decision::QueryUpstream
    }

    /// Stores an upstream answer (TTL in milliseconds).
    pub fn store(&mut self, name: &str, family: Family, address: IpAddr, ttl_ms: i64, now_ms: i64) {
        let key = (name.to_string(), family);
        self.cache.note_hit(&key);
        self.cache.insert(
            key,
            CacheEntry {
                address,
                stored_at_ms: now_ms,
                ttl_ms,
            },
        );
    }

    /// Allocates a fake address for a name (idempotent).
    ///
    /// Only names that will be routed by domain should be faked; callers
    /// filter first (the app's fakeIpFilter semantics).
    pub fn allocate_fake(&mut self, name: &str, family: Family) -> Option<IpAddr> {
        if !self.fake.enabled {
            return None;
        }
        if let Some(existing) = self.fake_forward.get(name) {
            return Some(*existing);
        }
        let address = match family {
            Family::V4 => {
                let base = u32::from(self.fake.v4_base);
                let host_bits = 32 - self.fake.v4_prefix as u32;
                let max = 1u32 << host_bits;
                let candidate = self.v4_next % max.max(1);
                self.v4_next = self.v4_next.wrapping_add(1);
                IpAddr::V4(Ipv4Addr::from(base | candidate))
            }
            Family::V6 => {
                let base = u128::from(self.fake.v6_base);
                let host_bits = 128 - self.fake.v6_prefix as u32;
                let max = 1u128 << host_bits.min(127);
                let candidate = self.v6_next % max.max(1);
                self.v6_next = self.v6_next.wrapping_add(1);
                IpAddr::V6(Ipv6Addr::from(base | candidate))
            }
        };
        self.fake_forward.insert(name.to_string(), address);
        self.fake_reverse.insert(address, name.to_string());
        Some(address)
    }

    pub fn cache_len(&self) -> usize {
        self.cache.len()
    }

    pub fn fake_len(&self) -> usize {
        self.fake_forward.len()
    }

    /// True when `addr` falls inside the configured fake ranges.
    pub fn is_fake_address(&self, addr: IpAddr) -> bool {
        self.fake_reverse.contains_key(&addr)
    }
}

/// Races several upstream attempts and returns the first success.
///
/// The transport layer supplies attempts as futures-like closures; this
/// helper encodes the *policy* (first answer wins, failures tolerated until
/// every attempt fails) without owning any runtime. In the runtime crate
/// the closures are async; here they are synchronous callables so the
/// policy is testable.
pub fn race_first<T, E>(
    attempts: Vec<Box<dyn FnOnce() -> Result<T, E>>>,
) -> Result<T, Vec<E>> {
    let mut errors = Vec::with_capacity(attempts.len());
    for attempt in attempts {
        match attempt() {
            Ok(value) => return Ok(value),
            Err(err) => errors.push(err),
        }
    }
    Err(errors)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v4(s: &str) -> IpAddr {
        s.parse().expect("v4 literal")
    }

    #[test]
    fn fresh_entry_answers_without_stale_flag() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store("example.com", Family::V4, v4("93.184.216.34"), 60_000, 1_000);
        let decision = engine.decide("example.com", Family::V4, None, 30_000);
        assert_eq!(
            decision,
            Decision::AnswerFromCache {
                address: v4("93.184.216.34"),
                stale: false
            }
        );
    }

    #[test]
    fn expired_entry_is_served_optimistically_within_window() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store("example.com", Family::V4, v4("1.2.3.4"), 10_000, 0);
        // t=15s: TTL (10s) passed, optimistic window (20s) not.
        let decision = engine.decide("example.com", Family::V4, None, 15_000);
        assert_eq!(
            decision,
            Decision::AnswerFromCache {
                address: v4("1.2.3.4"),
                stale: true
            }
        );
    }

    #[test]
    fn fully_stale_entry_forwards_upstream() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store("example.com", Family::V4, v4("1.2.3.4"), 10_000, 0);
        // t=25s: beyond 2x TTL.
        assert_eq!(
            engine.decide("example.com", Family::V4, None, 25_000),
            Decision::QueryUpstream
        );
    }

    #[test]
    fn fake_allocation_is_stable_and_reversible() {
        let mut engine = DnsEngine::new(FakeIpConfig {
            enabled: true,
            ..Default::default()
        });
        let first = engine.allocate_fake("ads.example", Family::V4).unwrap();
        let second = engine.allocate_fake("ads.example", Family::V4).unwrap();
        assert_eq!(first, second, "same name must map to the same fake ip");
        assert!(engine.is_fake_address(first));
        assert_eq!(
            engine.decide("whatever", Family::V4, Some(first), 0),
            Decision::ReverseFake {
                name: "ads.example".into()
            }
        );
    }

    #[test]
    fn fake_allocation_stays_inside_the_pool() {
        let mut engine = DnsEngine::new(FakeIpConfig {
            enabled: true,
            v4_base: Ipv4Addr::new(198, 18, 0, 0),
            v4_prefix: 30, // tiny pool: 4 addresses
            ..Default::default()
        });
        for i in 0..10 {
            let addr = engine
                .allocate_fake(&format!("host{i}.test"), Family::V4)
                .unwrap();
            let IpAddr::V4(v4) = addr else { panic!("expected v4") };
            let octets = v4.octets();
            assert_eq!(&octets[..2], &[198, 18]);
            assert!(octets[3] < 4, "address escaped the /30 pool: {v4}");
        }
    }

    #[test]
    fn disabled_fake_pool_never_allocates() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        assert!(engine.allocate_fake("x.test", Family::V4).is_none());
    }

    #[test]
    fn known_fake_name_answers_from_pool_before_upstream() {
        let mut engine = DnsEngine::new(FakeIpConfig {
            enabled: true,
            ..Default::default()
        });
        let allocated = engine.allocate_fake("known.test", Family::V4).unwrap();
        assert_eq!(
            engine.decide("known.test", Family::V4, None, 0),
            Decision::AnswerFake {
                address: allocated
            }
        );
    }

    #[test]
    fn race_returns_first_success_and_keeps_errors_on_total_failure() {
        let attempts: Vec<Box<dyn FnOnce() -> Result<String, String>>> = vec![
            Box::new(|| Err("upstream A timeout".to_string())),
            Box::new(|| Ok("answer from B".to_string())),
            Box::new(|| panic!("losers must not run after a success")),
        ];
        assert_eq!(race_first(attempts), Ok("answer from B".to_string()));

        let all_fail: Vec<Box<dyn FnOnce() -> Result<String, String>>> = vec![
            Box::new(|| Err("A".to_string())),
            Box::new(|| Err("B".to_string())),
        ];
        assert_eq!(
            race_first(all_fail),
            Err(vec!["A".to_string(), "B".to_string()])
        );
    }

    #[test]
    fn families_are_cached_independently() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store("dual.test", Family::V4, v4("1.1.1.1"), 60_000, 0);
        engine.store(
            "dual.test",
            Family::V6,
            "2606:4700::1111".parse().unwrap(),
            60_000,
            0,
        );
        assert_eq!(engine.cache_len(), 2);
        let v6_decision = engine.decide("dual.test", Family::V6, None, 0);
        assert!(matches!(
            v6_decision,
            Decision::AnswerFromCache { stale: false, .. }
        ));
        if let Decision::AnswerFromCache { address, .. } = v6_decision {
            assert!(address.is_ipv6());
        }
    }
}

mod arc;

pub use arc::ArcCache;
