//! AIBox Rust core — hot-path helpers shared with the Kotlin app via JNI.
//!
//! Phase 1 moves the connection-snapshot pipeline (serialise + delta
//! fingerprint) here: the VPN process runs it every push interval, so it is
//! the cheapest measurable win to validate the FFI bridge end to end.
//!
//! Phase 2 brings the RSXM route-check engine over the same bridge: the
//! app compiles its rule table (RoutePlanner + ConfigCompiler), sends the
//! compiled JSON here, and gets an explained routing decision back — the
//! micro-kernel's first user-visible capability.

use serde::Serialize;

mod jni;

pub use rsxm_rules::{CheckOutcome, CheckQuery, RouteChecker};

/// One connection in the live view; mirrors BoxEngine.ConnectionInfo.
#[derive(Serialize, Clone, serde::Deserialize)]
pub struct ConnectionInfo {
    pub id: String,
    pub network: String,
    pub inbound: String,
    pub source: String,
    pub destination: String,
    pub domain: String,
    pub protocol: String,
    pub outbound: String,
    #[serde(rename = "outboundType")]
    pub outbound_type: String,
    pub chain: Vec<String>,
    pub rule: String,
    #[serde(rename = "createdAt")]
    pub created_at: i64,
    #[serde(rename = "closedAt")]
    pub closed_at: i64,
    #[serde(rename = "uplinkTotal")]
    pub uplink_total: i64,
    #[serde(rename = "downlinkTotal")]
    pub downlink_total: i64,
    #[serde(rename = "processPath")]
    pub process_path: String,
    #[serde(rename = "userId")]
    pub user_id: i32,
    #[serde(rename = "packageNames")]
    pub package_names: Vec<String>,
}

impl ConnectionInfo {
    /// Stable per-connection fingerprint (same fields the Kotlin side uses
    /// to decide whether a re-broadcast is worth the IPC).
    fn fingerprint(&self) -> u64 {
        fn mix(mut h: u64, v: u64) -> u64 {
            // FNV-1a style fold over the fields that change over a
            // connection's lifetime.
            for b in v.to_le_bytes() {
                h ^= b as u64;
                h = h.wrapping_mul(0x0100_0000_01b3);
            }
            h
        }
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        h = mix(h, self.uplink_total as u64);
        h = mix(h, self.downlink_total as u64);
        h = mix(h, self.closed_at as u64);
        for b in self.id.bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
        h
    }
}

/// Serialise the snapshot to the exact JSON shape VpnIpc expects, and return
/// a whole-snapshot fingerprint (order-independent) the service can compare
/// against the previous push to skip identical broadcasts.
pub fn snapshot_json_and_fingerprint(connections: &[ConnectionInfo]) -> (String, u64) {
    let json = serde_json::to_string(connections).unwrap_or_else(|_| "[]".to_string());
    let mut fp: u64 = 0xcbf2_9ce4_8422_2325;
    for c in connections {
        let cf = c.fingerprint();
        // Order-independent XOR fold.
        fp ^= cf;
    }
    (json, fp)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(id: &str, up: i64) -> ConnectionInfo {
        ConnectionInfo {
            id: id.to_string(),
            network: "tcp".into(),
            inbound: "tun-in".into(),
            source: "172.19.0.1:4444".into(),
            destination: "1.2.3.4:443".into(),
            domain: "example.com".into(),
            protocol: "tls".into(),
            outbound: "proxy".into(),
            outbound_type: "selector".into(),
            chain: vec!["proxy".into(), "node-1".into()],
            rule: "domain_suffix:example.com".into(),
            created_at: 1_000,
            closed_at: 0,
            uplink_total: up,
            downlink_total: 9,
            process_path: String::new(),
            user_id: 10_123,
            package_names: vec!["com.example".into()],
        }
    }

    #[test]
    fn snapshot_json_shape() {
        let (json, _) = snapshot_json_and_fingerprint(&[sample("a", 1)]);
        assert!(json.contains("\"outboundType\":\"selector\""));
        assert!(json.contains("\"packageNames\":[\"com.example\"]"));
    }

    #[test]
    fn fingerprint_ignores_order_but_not_values() {
        let (_, fp1) = snapshot_json_and_fingerprint(&[sample("a", 1), sample("b", 2)]);
        let (_, fp2) = snapshot_json_and_fingerprint(&[sample("b", 2), sample("a", 1)]);
        assert_eq!(fp1, fp2);
        let (_, fp3) = snapshot_json_and_fingerprint(&[sample("a", 5), sample("b", 2)]);
        assert_ne!(fp1, fp3);
    }
}

#[cfg(test)]
mod bench_lite {
    use super::*;
    use std::time::Instant;

    #[test]
    fn thousand_connection_snapshot() {
        let conns: Vec<ConnectionInfo> = (0..1000)
            .map(|i| ConnectionInfo {
                id: format!("conn-{i}"),
                network: "tcp".into(),
                inbound: "tun-in".into(),
                source: format!("172.19.0.1:{}", 40000 + i),
                destination: "93.184.216.34:443".into(),
                domain: format!("host{i}.example.com"),
                protocol: "tls".into(),
                outbound: "proxy".into(),
                outbound_type: "selector".into(),
                chain: vec!["proxy".into(), "node-1".into()],
                rule: "domain_suffix:example.com".into(),
                created_at: 1_000_000,
                closed_at: 0,
                uplink_total: 123_456,
                downlink_total: 9_876_543,
                process_path: String::new(),
                user_id: 10_123,
                package_names: vec!["com.example.app".into()],
            })
            .collect();
        let start = Instant::now();
        let (json, _) = snapshot_json_and_fingerprint(&conns);
        let elapsed = start.elapsed();
        println!("1000-conn snapshot: {elapsed:?}, {} bytes", json.len());
        assert!(elapsed.as_millis() < 100);
    }
}
