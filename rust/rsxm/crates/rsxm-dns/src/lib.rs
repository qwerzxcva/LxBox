//! RSXM DNS module — the resolution micro-kernel.
//!
//! Absorbs the strongest ideas of the sing-box DNS design (karing/lxbox
//! style configs) and gives them a Rust home:
//!
//!  - **DNS rules pick the upstream**: domain/qtype/package-scoped route
//!    rules decide *which* server answers before any packet leaves, exactly
//!    like sing-box's `dns.rules` (`route` / `reject` actions);
//!  - **fake-IP pool**: answers A/AAAA with synthetic addresses so the
//!    router matches on names without waiting for real resolution, then
//!    maps the address back to the name for dialing;
//!  - **TTL cache with optimistic serving**: an expired entry is handed out
//!    immediately while a refresh happens in the background ("过期缓存先用
//!    再后台刷新"); bounded ARC (Adaptive Replacement Cache) so a busy phone
//!    can never grow the map without limit, and a burst of one-off cold
//!    names cannot flush the hot working set;
//!  - **negative caching**: short-lived NXDOMAIN entries kill the
//!    app-retry storms that re-ask for a name known not to exist;
//!  - **hosts**: static name→address overrides answered locally;
//!  - **parallel race**: several upstreams queried at once, first answer
//!    wins, losers cancelled.
//!
//! The module is pure: no sockets, no timers — the transport layer feeds it
//! answers and asks it what to do. Policy (what to cache, what to fake,
//! which upstream wins) stays separated from I/O (owned by the runtime).

pub mod arc;
pub mod module;

use arc::ArcCache;
use regex::RegexBuilder;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub use module::DnsModule;

/// DNS record family.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Family {
    V4,
    V6,
}

/// The synthetic range used for fake answers. Defaults match the app's
/// 198.18.0.0/15 + fc00::/18 configuration.
/// DNS cache eviction policy. ARC is the default (scan-resistant);
/// LRU is the classic least-recently-used discipline.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CacheAlgorithm {
    #[default]
    Arc,
    Lru,
}

pub struct FakeIpConfig {
    pub enabled: bool,
    pub v4_base: Ipv4Addr,
    pub v4_prefix: u8,
    pub v6_base: Ipv6Addr,
    pub v6_prefix: u8,
    /// DNS cache bound in entries (ARC/LRU-evicted).
    pub cache_capacity: usize,
    /// Eviction algorithm.
    pub cache_algorithm: CacheAlgorithm,
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
            cache_algorithm: CacheAlgorithm::Arc,
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

type CacheKey = (String, Family);

/// What the caller should do with a query.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// Answer immediately from cache (fresh or optimistic).
    AnswerFromCache { address: IpAddr, stale: bool },
    /// Answer with a fake address from the pool.
    AnswerFake { address: IpAddr },
    /// Answer from a local hosts override.
    AnswerHosts { address: IpAddr },
    /// A cached negative (NXDOMAIN); do not re-ask yet.
    NegativeCached,
    /// The address is a fake one; here is the name it stands for.
    ReverseFake { name: String },
    /// A DNS rule rejected the query (ad/tracker blocking at DNS layer).
    Reject,
    /// Forward the query to the server a DNS rule selected.
    ForwardToServer { server: String },
    /// Forward the query upstream (default selection).
    QueryUpstream,
}

/// What a matching DNS rule orders.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum DnsAction {
    Route(String),
    /// A rule without an explicit action fails closed: reject. Privacy
    /// beats silent forwarding.
    #[default]
    Reject,
}

/// One sing-box-style DNS rule: first match wins.
#[derive(Debug, Clone, Default)]
pub struct DnsRouteRule {
    pub domains: Vec<String>,
    pub suffixes: Vec<String>,
    pub keywords: Vec<String>,
    pub regexes: Vec<String>,
    /// Query type names (`"A"`, `"AAAA"`, `"HTTPS"`…); empty = any.
    pub query_types: Vec<String>,
    /// Caller packages; empty = any.
    pub packages: Vec<String>,
    pub action: DnsAction,
}

impl DnsRouteRule {
    fn matches(&self, name: &str, qtype: Option<&str>, package: Option<&str>) -> bool {
        let name_hit = self.domains.iter().any(|d| d == name)
            || self
                .suffixes
                .iter()
                .any(|s| name == s || name.ends_with(&format!(".{s}")))
            || self.keywords.iter().any(|k| name.contains(k.as_str()))
            || self.regexes.iter().any(|p| {
                RegexBuilder::new(p)
                    .case_insensitive(true)
                    .build()
                    .map(|re| re.is_match(name))
                    .unwrap_or(false)
            });
        if !name_hit {
            return false;
        }
        if !self.query_types.is_empty()
            && !qtype
                .map(|q| self.query_types.iter().any(|t| t.eq_ignore_ascii_case(q)))
                .unwrap_or(false)
        {
            return false;
        }
        if !self.packages.is_empty()
            && !package
                .map(|p| self.packages.iter().any(|x| x == p))
                .unwrap_or(false)
        {
            return false;
        }
        true
    }
}

/// The ordered DNS rule set the engine consults before the fake pool.
#[derive(Debug, Clone, Default)]
pub struct DnsRouter {
    rules: Vec<DnsRouteRule>,
}

impl DnsRouter {
    pub fn new(rules: Vec<DnsRouteRule>) -> Self {
        Self { rules }
    }

    pub fn len(&self) -> usize {
        self.rules.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// First-hit-wins upstream selection, sing-box semantics.
    pub fn select(
        &self,
        name: &str,
        qtype: Option<&str>,
        package: Option<&str>,
    ) -> Option<&DnsAction> {
        self.rules
            .iter()
            .find(|r| r.matches(name, qtype, package))
            .map(|r| &r.action)
    }
}

/// The DNS policy engine: router + cache + fake pool + hosts + reverse map.
pub struct DnsEngine {
    fake: FakeIpConfig,
    router: DnsRouter,
    hosts: Vec<(String, IpAddr)>,
    /// Bounded ARC cache (scan-resistant; a cold-name burst can't flush the
    /// hot working set the way a plain LRU allows).
    cache: ArcCache,
    /// name/family → expiry of a cached NXDOMAIN.
    negative: HashMap<CacheKey, i64>,
    negative_ttl_ms: i64,
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
        let capacity = fake.cache_capacity;
        let algorithm = fake.cache_algorithm;
        Self::with_policy(fake, DnsRouter::default(), Vec::new(), capacity, algorithm)
    }

    pub fn with_policy(
        fake: FakeIpConfig,
        router: DnsRouter,
        hosts: Vec<(String, IpAddr)>,
        cache_capacity: usize,
        algorithm: CacheAlgorithm,
    ) -> Self {
        Self {
            fake,
            router,
            hosts: hosts
                .into_iter()
                .map(|(name, ip)| (name.to_ascii_lowercase(), ip))
                .collect(),
            cache: match algorithm {
                CacheAlgorithm::Arc => ArcCache::new(cache_capacity.clamp(16, 1_048_576)),
                CacheAlgorithm::Lru => ArcCache::new_lru(cache_capacity.clamp(16, 1_048_576)),
            },
            negative: HashMap::new(),
            negative_ttl_ms: 30_000,
            fake_reverse: HashMap::new(),
            fake_forward: HashMap::new(),
            v4_next: 1,
            v6_next: 1,
        }
    }

    /// Resolves what to do for a name. `reverse_ip` is checked first: a
    /// connection to a fake address must be mapped back to its name.
    pub fn decide(
        &mut self,
        name: &str,
        family: Family,
        reverse_ip: Option<IpAddr>,
        now_ms: i64,
    ) -> Decision {
        self.decide_routed(name, family, reverse_ip, now_ms, None, None)
    }

    /// Full decision path including DNS rules.
    ///
    /// `qtype` is the DNS question type (`"A"`/`"AAAA"`) and `package` the
    /// calling app, when the caller knows them; both are optional.
    pub fn decide_routed(
        &mut self,
        name: &str,
        family: Family,
        reverse_ip: Option<IpAddr>,
        now_ms: i64,
        qtype: Option<&str>,
        package: Option<&str>,
    ) -> Decision {
        if let Some(ip) = reverse_ip {
            if let Some(mapped) = self.fake_reverse.get(&ip) {
                return Decision::ReverseFake {
                    name: mapped.clone(),
                };
            }
        }

        let lname = name.to_ascii_lowercase();

        // Hosts overrides beat everything except a fake reverse.
        if let Some((_, addr)) = self
            .hosts
            .iter()
            .find(|(h, _)| h == &lname)
            .filter(|(_, addr)| family_matches(family, *addr))
        {
            return Decision::AnswerHosts { address: *addr };
        }

        let key = (lname.clone(), family);

        // Negative cache: still valid → refuse; expired → forget it.
        if let Some(expiry) = self.negative.get(&key) {
            if now_ms < *expiry {
                return Decision::NegativeCached;
            }
        }

        // Probe the cache inside a closure so the immutable borrow ends
        // before `touch` takes a mutable one.
        let cached = self.cache.get(&key).and_then(|entry| {
            if entry.is_fresh(now_ms) {
                Some((entry.address, false))
            } else if entry.is_optimistically_servable(now_ms) {
                Some((entry.address, true))
            } else {
                None
            }
        });
        if let Some((address, stale)) = cached {
            self.cache.note_hit(&key);
            return Decision::AnswerFromCache { address, stale };
        }

        // Miss: still feed the ARC ghost feedback. A re-request for a name
        // evicted in the last round shifts the T1/T2 target before the
        // answer is refetched and re-stored, so repeated demand wins the
        // next eviction round (ARC's scan-resistance feedback loop).
        self.cache.note_hit(&key);

        // DNS rules choose the upstream (or reject) before the fake pool.
        if let Some(action) = self.router.select(&lname, qtype, package) {
            return match action {
                DnsAction::Reject => Decision::Reject,
                DnsAction::Route(server) => Decision::ForwardToServer {
                    server: server.clone(),
                },
            };
        }

        if self.fake.enabled {
            // Allocation is the caller's job (it happens when an upstream
            // query is issued); this path only answers names allocated
            // before.
            if let Some(existing) = self.fake_forward.get(&lname) {
                return Decision::AnswerFake { address: *existing };
            }
        }
        Decision::QueryUpstream
    }

    /// Bounds the negative map (ArcCache evicts the positive cache itself).
    fn trim_negative(&mut self) {
        let neg_cap = (self.cache.capacity() / 8).max(64);
        if self.negative.len() > neg_cap {
            // Negatives are tiny and self-expiring; drop arbitrary overflow
            // rather than paying for LRU bookkeeping on NXDOMAIN entries.
            let extras = self.negative.len() - neg_cap;
            let to_remove: Vec<CacheKey> = self.negative.keys().take(extras).cloned().collect();
            for key in to_remove {
                self.negative.remove(&key);
            }
        }
    }

    /// Stores an upstream answer (TTL in milliseconds) and clears any
    /// negative entry for the name. ARC eviction happens inside `insert`.
    pub fn store(&mut self, name: &str, family: Family, address: IpAddr, ttl_ms: i64, now_ms: i64) {
        let key = (name.to_ascii_lowercase(), family);
        self.cache.insert(
            key.clone(),
            CacheEntry {
                address,
                stored_at_ms: now_ms,
                ttl_ms,
            },
        );
        self.negative.remove(&key);
    }

    /// Records a negative answer (NXDOMAIN) for the name.
    pub fn store_negative(&mut self, name: &str, family: Family, now_ms: i64) {
        let key = (name.to_ascii_lowercase(), family);
        self.negative.insert(key, now_ms + self.negative_ttl_ms);
        self.trim_negative();
    }

    /// Allocates a fake address for a name (idempotent).
    ///
    /// Only names that will be routed by domain should be faked; callers
    /// filter first (the app's fakeIpFilter semantics).
    pub fn allocate_fake(&mut self, name: &str, family: Family) -> Option<IpAddr> {
        if !self.fake.enabled {
            return None;
        }
        let lname = name.to_ascii_lowercase();
        if let Some(existing) = self.fake_forward.get(&lname) {
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
        self.fake_forward.insert(lname.clone(), address);
        self.fake_reverse.insert(address, lname);
        Some(address)
    }

    pub fn cache_len(&self) -> usize {
        self.cache.len()
    }

    pub fn fake_len(&self) -> usize {
        self.fake_forward.len()
    }

    pub fn router_len(&self) -> usize {
        self.router.len()
    }

    /// True when `addr` falls inside the configured fake ranges.
    pub fn is_fake_address(&self, addr: IpAddr) -> bool {
        self.fake_reverse.contains_key(&addr)
    }
}

fn family_matches(family: Family, addr: IpAddr) -> bool {
    matches!(
        (family, addr),
        (Family::V4, IpAddr::V4(_)) | (Family::V6, IpAddr::V6(_))
    )
}

/// Races several upstream attempts and returns the first success.
///
/// The transport layer supplies attempts as futures-like closures; this
/// helper encodes the *policy* (first answer wins, failures tolerated until
/// every attempt fails) without owning any runtime. In the runtime crate
/// the closures are async; here they are synchronous callables so the
/// policy is testable.
pub fn race_first<T, E>(attempts: Vec<Box<dyn FnOnce() -> Result<T, E>>>) -> Result<T, Vec<E>> {
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

    fn router(rules: Vec<DnsRouteRule>) -> DnsRouter {
        DnsRouter::new(rules)
    }

    #[test]
    fn fresh_entry_answers_without_stale_flag() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store(
            "example.com",
            Family::V4,
            v4("93.184.216.34"),
            60_000,
            1_000,
        );
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
            v4_prefix: 30,
            ..Default::default()
        });
        for i in 0..10 {
            let addr = engine
                .allocate_fake(&format!("host{i}.test"), Family::V4)
                .unwrap();
            let IpAddr::V4(v4) = addr else {
                panic!("expected v4")
            };
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
            Decision::AnswerFake { address: allocated }
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

    #[test]
    fn dns_rules_select_upstream_or_reject_first_hit() {
        let r = router(vec![
            DnsRouteRule {
                suffixes: vec!["cn".into()],
                action: DnsAction::Route("local".into()),
                ..Default::default()
            },
            DnsRouteRule {
                domains: vec!["ads.tracker".into()],
                action: DnsAction::Reject,
                ..Default::default()
            },
            DnsRouteRule {
                suffixes: vec!["example.com".into()],
                query_types: vec!["A".into()],
                action: DnsAction::Route("remote".into()),
                ..Default::default()
            },
        ]);
        assert!(matches!(
            r.select("www.cn", None, None),
            Some(DnsAction::Route(s)) if s == "local"
        ));
        assert_eq!(
            r.select("ads.tracker", None, None),
            Some(&DnsAction::Reject)
        );
        // qtype gate: AAAA does not match the A-only rule.
        assert_eq!(r.select("api.example.com", Some("AAAA"), None), None);
        assert!(matches!(
            r.select("api.example.com", Some("A"), None),
            Some(DnsAction::Route(s)) if s == "remote"
        ));
    }

    #[test]
    fn engine_forwards_to_rule_selected_server() {
        let engine = DnsEngine::with_policy(
            FakeIpConfig::default(),
            router(vec![DnsRouteRule {
                suffixes: vec!["lan".into()],
                action: DnsAction::Route("local-dns".into()),
                ..Default::default()
            }]),
            Vec::new(),
            64,
            CacheAlgorithm::Arc,
        );
        // decide_routed needs &mut self only for LRU touch; engines with an
        // empty cache never mutate here, but keep the signature honest.
        let mut engine = engine;
        assert_eq!(
            engine.decide_routed("host.lan", Family::V4, None, 0, Some("A"), None),
            Decision::ForwardToServer {
                server: "local-dns".into()
            }
        );
    }

    #[test]
    fn hosts_override_answers_locally_per_family() {
        let mut engine = DnsEngine::with_policy(
            FakeIpConfig::default(),
            DnsRouter::default(),
            vec![("router.home".into(), v4("192.168.1.1"))],
            64,
            CacheAlgorithm::Arc,
        );
        assert_eq!(
            engine.decide("router.home", Family::V4, None, 0),
            Decision::AnswerHosts {
                address: v4("192.168.1.1")
            }
        );
        // No AAAA record in hosts → upstream, not a fake v4 answer.
        assert_eq!(
            engine.decide("router.home", Family::V6, None, 0),
            Decision::QueryUpstream
        );
    }

    #[test]
    fn negative_cache_short_circuits_then_expires() {
        let mut engine = DnsEngine::new(FakeIpConfig::default());
        engine.store_negative("gone.test", Family::V4, 1_000);
        assert_eq!(
            engine.decide("gone.test", Family::V4, None, 10_000),
            Decision::NegativeCached
        );
        // After 30s default negative TTL the query escapes upstream.
        assert_eq!(
            engine.decide("gone.test", Family::V4, None, 31_001),
            Decision::QueryUpstream
        );
    }

    #[test]
    fn cache_respects_arc_capacity() {
        // The policy clamps capacity to a 16-entry floor; exercise the ARC
        // discipline right at that floor.
        let mut engine = DnsEngine::with_policy(
            FakeIpConfig::default(),
            DnsRouter::default(),
            Vec::new(),
            16,
            CacheAlgorithm::Arc,
        );
        for i in 0..16 {
            engine.store(
                &format!("h{i}.test"),
                Family::V4,
                v4(&format!("1.0.0.{i}")),
                60_000,
                0,
            );
        }
        // Re-touch h0: it promotes T1 → T2 (the working set). With the ARC
        // target p=0, the first capacity event reclassifies T2's lone entry
        // as a B2 *ghost* rather than dropping it outright, keeping the
        // cache bounded at 16.
        assert!(matches!(
            engine.decide("h0.test", Family::V4, None, 0),
            Decision::AnswerFromCache { stale: false, .. }
        ));
        engine.store("overflow.test", Family::V4, v4("2.0.0.1"), 60_000, 0);
        assert_eq!(engine.cache_len(), 16);
        // h0 now needs a refetch; decide() feeds the B2 ghost hit, adapting
        // the ARC target.
        assert_eq!(
            engine.decide("h0.test", Family::V4, None, 0),
            Decision::QueryUpstream
        );
        // Re-store the re-requested hot name; a cold one-timer (h1) leaves
        // instead — repeated demand is protected on this round.
        engine.store("h0.test", Family::V4, v4("1.0.0.0"), 60_000, 0);
        assert_eq!(engine.cache_len(), 16);
        assert!(matches!(
            engine.decide("h0.test", Family::V4, None, 0),
            Decision::AnswerFromCache { stale: false, .. }
        ));
        assert_eq!(
            engine.decide("h1.test", Family::V4, None, 0),
            Decision::QueryUpstream
        );
    }
}
