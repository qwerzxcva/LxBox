//! RSXM security micro-kernel — the privacy/leak-guard leader.
//!
//! Borrows the fail-closed posture of xray-lineage clients such as mikuRay:
//!
//!  - **FLAG_SECURE gate.** When `blockScreenshots` is on the Android layer
//!    applies `FLAG_SECURE` to every engine surface so the task switcher and
//!    screen recordings cannot show node lists or traffic. The kernel only
//!    *publishes the verdict*; the activity reads it at attach time.
//!  - **Encrypted-DNS enforcement.** Plaintext DNS (`udp`/`tcp`/`dhcp`)
//!    leaks every queried name to the local carrier or any on-path
//!    observer. Such upstreams are rejected unless the user explicitly
//!    allows insecure DNS. Local virtual servers (`hosts`, `fakeip`) are
//!    not network transports and always pass.
//!  - **TLS fragmentation posture.** Fragmentation is a reachability tool,
//!    not a privacy feature; enabling it is surfaced as a security report
//!    so the UI can explain the trade-off.
//!
//! Everything here is policy: the Conductor distributes the slice, this
//! leader answers guard questions, and the transport layer obeys.

use std::sync::{Mutex, RwLock};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter, ReportKind};
use serde::Deserialize;

/// sing-box DNS upstream classes, grouped by what they leak.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsTransport {
    /// Plain DNS over UDP/53 — names in cleartext.
    Udp,
    /// Plain DNS over TCP/53 — names in cleartext.
    Tcp,
    /// DHCP-discovered resolver — controlled by the local network.
    Dhcp,
    /// DNS-over-TLS (`tls`).
    Tls,
    /// DNS-over-HTTPS (`https`).
    Https,
    /// DNS-over-QUIC (`quic`, `h3`).
    Quic,
    /// Local hosts table — virtual, never touches the wire.
    Hosts,
    /// Fake-IP allocator — virtual, never touches the wire.
    FakeIp,
}

impl DnsTransport {
    /// Parses the `type` field the app/sing-box config uses. Unknown names
    /// are treated conservatively as [`DnsTransport::Udp`] (cleartext): a
    /// future encrypted transport must be allow-listed, never the reverse.
    pub fn from_type_name(name: &str) -> Self {
        match name.to_ascii_lowercase().as_str() {
            "udp" => Self::Udp,
            "tcp" => Self::Tcp,
            "dhcp" => Self::Dhcp,
            "tls" | "dot" => Self::Tls,
            "https" | "doh" => Self::Https,
            "quic" | "doq" | "h3" | "http3" => Self::Quic,
            "hosts" | "host" => Self::Hosts,
            "fakeip" | "fake-ip" | "fake_ip" => Self::FakeIp,
            // A URL-shaped descriptor ("https://...") is encrypted; a bare
            // unknown token fails closed.
            other if other.starts_with("https://") || other.starts_with("h3://") => Self::Https,
            other if other.starts_with("tls://") => Self::Tls,
            other if other.starts_with("quic://") => Self::Quic,
            _ => Self::Udp,
        }
    }

    /// True when queries on this transport are encrypted end-to-end or the
    /// transport is a local virtual one.
    pub fn is_private(&self) -> bool {
        matches!(
            self,
            Self::Tls | Self::Https | Self::Quic | Self::Hosts | Self::FakeIp
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SecurityPolicy {
    block_screenshots: bool,
    allow_insecure_dns: bool,
    tls_fragment_mode: String,
}

impl Default for SecurityPolicy {
    fn default() -> Self {
        // Privacy-safe defaults: no special window flags, but cleartext DNS
        // stays forbidden unless the user opts in.
        Self {
            block_screenshots: false,
            allow_insecure_dns: false,
            tls_fragment_mode: "none".to_string(),
        }
    }
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
#[allow(non_snake_case)]
struct SecuritySlice {
    #[serde(default)]
    blockScreenshots: bool,
    #[serde(default)]
    allowInsecureDns: bool,
    #[serde(default)]
    tlsFragmentMode: String,
}

/// The security leader.
pub struct SecurityModule {
    policy: RwLock<SecurityPolicy>,
    configured: RwLock<bool>,
    reporter: Mutex<Option<ModuleReporter>>,
}

impl SecurityModule {
    pub fn new() -> Self {
        Self {
            policy: RwLock::new(SecurityPolicy::default()),
            configured: RwLock::new(false),
            reporter: Mutex::new(None),
        }
    }

    fn report(&self, kind: ReportKind, message: impl Into<String>) {
        if let Some(reporter) = self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
        {
            reporter.send(kind, message);
        }
    }

    /// Whether the Android UI must attach FLAG_SECURE to its windows.
    pub fn should_block_screenshots(&self) -> bool {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .block_screenshots
    }

    pub fn tls_fragment_mode(&self) -> String {
        self.policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .tls_fragment_mode
            .clone()
    }

    /// The fail-closed DNS guard. `server_type` is the upstream's `type`
    /// field (or URL scheme) straight from the DNS slice.
    pub fn guard_dns_upstream(&self, server_type: &str) -> Result<(), String> {
        let transport = DnsTransport::from_type_name(server_type);
        if transport.is_private() {
            return Ok(());
        }
        let allowed = self
            .policy
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .allow_insecure_dns;
        if allowed {
            self.report(
                ReportKind::Security,
                format!("insecure DNS '{server_type}' permitted by explicit opt-in"),
            );
            Ok(())
        } else {
            let why = format!(
                "cleartext DNS '{server_type}' blocked (enable allowInsecureDns to override)"
            );
            self.report(ReportKind::Security, why.clone());
            Err(why)
        }
    }
}

impl Default for SecurityModule {
    fn default() -> Self {
        Self::new()
    }
}

impl Module for SecurityModule {
    fn name(&self) -> &'static str {
        "rsxm-security"
    }

    fn attach(&self, reporter: &ModuleReporter) {
        *self
            .reporter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let Some(slice) = slice else {
            *self
                .policy
                .write()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = SecurityPolicy::default();
            *self
                .configured
                .write()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = false;
            return Ok(());
        };
        let parsed: SecuritySlice =
            serde_json::from_value(slice.value.as_ref().clone()).map_err(|e| e.to_string())?;
        let mode = if parsed.tlsFragmentMode.trim().is_empty() {
            "none".to_string()
        } else {
            parsed.tlsFragmentMode.trim().to_ascii_lowercase()
        };
        let policy = SecurityPolicy {
            block_screenshots: parsed.blockScreenshots,
            allow_insecure_dns: parsed.allowInsecureDns,
            tls_fragment_mode: mode.clone(),
        };
        self.report(
            ReportKind::ConfigAccepted,
            format!(
                "security posture: flag_secure={}, encrypted_dns_required={}, tls_fragment={}",
                policy.block_screenshots, !policy.allow_insecure_dns, policy.tls_fragment_mode
            ),
        );
        if policy.block_screenshots {
            self.report(
                ReportKind::Security,
                "FLAG_SECURE enforced on engine surfaces",
            );
        }
        if mode != "none" {
            self.report(
                ReportKind::Warn,
                format!("TLS fragmentation enabled ({mode}): reachability over privacy-grade TLS"),
            );
        }
        *self
            .policy
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = policy;
        *self
            .configured
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        Health::Up
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsxm_core::{Conductor, ConfigEnvelope, ConfigSlice, ReportKind};
    use serde_json::json;
    use std::sync::Arc;

    #[test]
    fn encrypted_and_virtual_transports_pass() {
        let module = SecurityModule::new();
        module.configure(None).unwrap();
        for name in [
            "tls",
            "https",
            "h3",
            "quic",
            "hosts",
            "fakeip",
            "https://1.1.1.1/dns-query",
        ] {
            assert!(module.guard_dns_upstream(name).is_ok(), "{name}");
        }
    }

    #[test]
    fn cleartext_dns_is_blocked_by_default() {
        let module = SecurityModule::new();
        module.configure(None).unwrap();
        for name in ["udp", "tcp", "dhcp", "mystery-transport"] {
            let err = module.guard_dns_upstream(name);
            assert!(err.is_err(), "{name} must be blocked");
            assert!(err.unwrap_err().contains("blocked"));
        }
    }

    #[test]
    fn explicit_opt_in_permits_cleartext_and_reports() {
        let module = Arc::new(SecurityModule::new());
        let mut conductor = Conductor::new();
        conductor.register(module.clone() as Arc<dyn Module>);
        conductor.distribute(ConfigEnvelope::new(vec![ConfigSlice::new(
            "rsxm-security",
            json!({ "allowInsecureDns": true }),
        )]));
        assert!(module.guard_dns_upstream("udp").is_ok());
        assert!(conductor
            .drain_reports()
            .iter()
            .any(|r| { r.kind == ReportKind::Security && r.message.contains("explicit opt-in") }));
    }

    #[test]
    fn flag_secure_verdict_follows_slice() {
        let module = SecurityModule::new();
        assert!(!module.should_block_screenshots());
        module
            .configure(Some(&ConfigSlice::new(
                "rsxm-security",
                json!({ "blockScreenshots": true, "tlsFragmentMode": "TLSHello" }),
            )))
            .unwrap();
        assert!(module.should_block_screenshots());
        assert_eq!(module.tls_fragment_mode(), "tlshello");
    }

    #[test]
    fn unknown_transport_fails_closed_not_open() {
        let module = SecurityModule::new();
        module.configure(None).unwrap();
        assert!(module.guard_dns_upstream("icmp-dns-v99").is_err());
    }
}
