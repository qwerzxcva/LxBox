use std::net::IpAddr;

/// A parsed CIDR entry: network bytes + prefix length.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cidr {
    addr: IpAddr,
    prefix: u8,
}

impl Cidr {
    /// Parses `a.b.c.d/N` or `[v6::addr]/N`. Returns None for malformed
    /// input — the loader keeps those as opaque text (a future version
    /// surfaces them in the UI as invalid instead of silently ignoring).
    pub fn parse(text: &str) -> Option<Self> {
        let (addr_text, prefix_text) = text.trim().split_once('/')?;
        let addr: IpAddr = addr_text.trim_matches(|c| c == '[' || c == ']').parse().ok()?;
        let prefix: u8 = prefix_text.trim().parse().ok()?;
        let max = if addr.is_ipv4() { 32 } else { 128 };
        if prefix > max {
            return None;
        }
        Some(Self { addr, prefix })
    }

    /// True when `other` (address or network) is fully contained in `self`.
    pub fn contains_addr(&self, other: IpAddr) -> bool {
        match (self.addr, other) {
            (IpAddr::V4(net), IpAddr::V4(ip)) => {
                let net = u32::from(net);
                let ip = u32::from(ip);
                let mask = if self.prefix == 0 { 0 } else { u32::MAX << (32 - self.prefix) };
                (net & mask) == (ip & mask)
            }
            (IpAddr::V6(net), IpAddr::V6(ip)) => {
                let net = u128::from(net);
                let ip = u128::from(ip);
                let mask = if self.prefix == 0 { 0 } else { u128::MAX << (128 - self.prefix) };
                (net & mask) == (ip & mask)
            }
            _ => false,
        }
    }

    /// True when `other` is fully contained in `self` (same family).
    pub fn contains_net(&self, other: &Cidr) -> bool {
        if self.addr.is_ipv4() != other.addr.is_ipv4() {
            return false;
        }
        if self.prefix > other.prefix {
            return false;
        }
        self.contains_addr(other.addr)
    }
}

/// Compiled CIDs for one rule. Unparseable entries are not indexed for
/// matching (they can never contain an address); cross-rule dedupe handles
/// them by raw-text identity during [`RuleTable::build`].
#[derive(Debug, Default)]
pub(crate) struct RuleCidrs {
    nets: Vec<Cidr>,
}

impl RuleCidrs {
    pub(crate) fn build(raws: &[String]) -> Self {
        let mut nets = Vec::with_capacity(raws.len());
        for raw in raws {
            if let Some(net) = Cidr::parse(raw.trim()) {
                nets.push(net);
            }
        }
        Self { nets }
    }

    pub(crate) fn contains(&self, addr: IpAddr) -> bool {
        self.nets.iter().any(|net| net.contains_addr(addr))
    }
}
