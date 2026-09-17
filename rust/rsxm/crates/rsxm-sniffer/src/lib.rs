//! RSXM protocol sniffer — the inbound-discovery micro-kernel.
//!
//! ## What it does
//!
//! Merges the sing-box `sniff` (engine/sniff) inbound discovery logic and
//! v2ray's `InboundHandler` self-identification. Given the first few bytes
//! of a connection, the sniffer answers two questions:
//!
//! 1. **What protocol is this?** `tls` / `http` / `quic` / `socks5` /
//!    `shadowsocks` / `trojan` / `vless` / `unknown`.
//! 2. **What hostname is the client talking to?** The TLS SNI or HTTP
//!    `Host` header, so the tun module can do route matching on names
//!    even when the user never configured a sniffer.
//!
//! ## Why it lives in its own micro-kernel
//!
//! Protocol discovery runs on the hot path — every new TCP flow pays for
//! it. Keeping it separate means:
//!  - The Conductor schedules it once at boot (dependency: rsxm-core
//!    only, no business deps), never rebuilds it per-config-change;
//!  - The tun module (or a threaded Conductor runner) calls `sniff()`
//!    directly — the hot path is one function call, no channel, no I/O;
//!  - A future `rsxm-sniffer-v2` with QUIC v1 support drops in as a new
//!    crate, Conductor never learns the difference.
//!
//! ## Byte budget
//!
//! Everything below fits in the first 2048 bytes of the connection.
//! (SING-BOX uses 8 KiB; we trim to 2 KiB because Android apps compress
//! their initial reads aggressively and we want to parse without a
//! second syscall.)

use std::sync::{Arc, Mutex};

use rsxm_core::{ConfigSlice, Health, Module, ModuleReporter};
use serde::Deserialize;

pub mod tls;

// ---------------------------------------------------------------------------
// Public API — what the tun / dialer modules call
// ---------------------------------------------------------------------------

/// Protocol the sniffer identified.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocol {
    /// TLS 1.2 or 1.3 ClientHello; `hostname` carries the SNI extension.
    Tls,
    /// HTTP/1.x request; `hostname` comes from the `Host:` header.
    Http,
    /// QUIC Initial / Handshake datagram; `hostname` comes from the TLS SNI
    /// inside the Initial CRYPTO frame.
    Quic,
    /// SOCKS5 handshake. Has no hostname on its own; the target comes in
    /// the next request byte, which we don't read here.
    Socks5,
    /// Shadowsocks AEAD / ChaCha20-IETF header (salt length varies).
    Shadowsocks,
    /// Trojan: begins with `GET / HTTP/1.1` style plaintext followed by
    /// a CRLF-terminated header block.
    Trojan,
    /// VLESS: 0x00 + 16-byte UUID + 0x00 + 1-byte version + command +
    /// address-port payload.
    Vless,
    /// Nothing matched — the caller should either proxy blindly or fall
    /// back to its default protocol handler.
    Unknown,
}

impl Protocol {
    pub fn as_str(self) -> &'static str {
        match self {
            Protocol::Tls => "tls",
            Protocol::Http => "http",
            Protocol::Quic => "quic",
            Protocol::Socks5 => "socks5",
            Protocol::Shadowsocks => "shadowsocks",
            Protocol::Trojan => "trojan",
            Protocol::Vless => "vless",
            Protocol::Unknown => "unknown",
        }
    }
}

impl Default for Protocol {
    fn default() -> Self {
        Protocol::Unknown
    }
}

/// What one `sniff()` call returns.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SniffResult {
    pub protocol: Protocol,
    /// Hostname / SNI extracted, if any. Always lowercase.
    pub hostname: Option<String>,
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub struct SnifferConfig {
    /// Bytes to read before giving up — defaults to 2048.
    #[serde(default = "default_byte_limit")]
    pub byte_limit: usize,
    /// Whether to extract the hostname from TLS/HTTP. Disable on power save.
    #[serde(default = "default_true")]
    pub extract_hostname: bool,
}

fn default_byte_limit() -> usize {
    2048
}

fn default_true() -> bool {
    true
}

// ---------------------------------------------------------------------------
// Sniffer — the stateless engine used by the hot path
// ---------------------------------------------------------------------------

/// A stateless sniffer. Create once, call `sniff()` per connection.
/// No `Arc` needed on the hot path — the sniffer is a thin const wrapper.
pub struct Sniffer {
    limit: usize,
    extract_hostname: bool,
}

impl Sniffer {
    pub fn new(limit: usize, extract_hostname: bool) -> Self {
        Self {
            limit: limit.clamp(64, 8192),
            extract_hostname,
        }
    }

    pub fn from_config(config: &SnifferConfig) -> Self {
        Self::new(config.byte_limit, config.extract_hostname)
    }

    /// Sniffs the first `self.limit` bytes of `buf`. Returns what it found.
    pub fn sniff(&self, buf: &[u8]) -> SniffResult {
        let slice = &buf[..buf.len().min(self.limit)];

        // Order matters:
        //  - TLS first (v2ray/ sing-box both lead with it);
        //  - QUIC header is distinct from TCP-based protocols;
        //  - HTTP before SOCKS5 because both start with printable ASCII;
        //  - VLESS has a fixed-format 18-byte header before any of these;
        //  - Shadowsocks AEAD starts with a random-looking salt.

        if let Some(result) = self.try_tls(slice) {
            return result;
        }
        if let Some(result) = self.try_quic(slice) {
            return result;
        }
        if let Some(result) = self.try_http(slice) {
            return result;
        }
        if let Some(result) = self.try_vless(slice) {
            return result;
        }
        if self.try_trojan(slice) {
            return SniffResult {
                protocol: Protocol::Trojan,
                hostname: None,
            };
        }
        if self.try_socks5(slice) {
            return SniffResult {
                protocol: Protocol::Socks5,
                hostname: None,
            };
        }
        if self.try_shadowsocks(slice) {
            return SniffResult {
                protocol: Protocol::Shadowsocks,
                hostname: None,
            };
        }
        SniffResult {
            protocol: Protocol::Unknown,
            hostname: None,
        }
    }

    // --- per-protocol detectors ----------------------------------------

    fn try_tls(&self, buf: &[u8]) -> Option<SniffResult> {
        // TLS record: 0x16 (handshake) + 0x03 0x01..0x04 + 2-byte length.
        if buf.len() < 6 || buf[0] != 0x16 || buf[1] != 0x03 {
            return None;
        }
        let version = buf[2];
        if !(1..=4).contains(&version) {
            return None;
        }
        let hostname = if self.extract_hostname {
            tls::extract_sni(buf).map(|s| s.to_ascii_lowercase())
        } else {
            None
        };
        Some(SniffResult {
            protocol: Protocol::Tls,
            hostname,
        })
    }

    fn try_quic(&self, buf: &[u8]) -> Option<SniffResult> {
        // QUIC v1: first byte has the Long Header bit (0x80) and the
        // 2-bit version family. QUIC v1 == version 0x00000001.
        if buf.len() < 6 || buf[0] & 0x80 == 0 {
            return None;
        }
        let version = u32::from_be_bytes([buf[1], buf[2], buf[3], buf[4]]);
        let is_quic_version = version == 1 || (version & 0xf000_0000) == 0x0000_0000;
        if !is_quic_version {
            return None;
        }
        let hostname = if self.extract_hostname {
            tls::extract_sni_from_quic(buf).map(|s| s.to_ascii_lowercase())
        } else {
            None
        };
        Some(SniffResult {
            protocol: Protocol::Quic,
            hostname,
        })
    }

    fn try_http(&self, buf: &[u8]) -> Option<SniffResult> {
        if buf.len() < 5 {
            return None;
        }
        let verb = &buf[..4.min(buf.len())];
        let looks_get = verb.starts_with(b"GET ");
        let looks_post = verb.starts_with(b"POST");
        let looks_put = verb.starts_with(b"PUT ");
        let looks_head = verb.starts_with(b"HEAD");
        let looks_connect = verb.starts_with(b"CONN");
        if !(looks_get || looks_post || looks_put || looks_head || looks_connect) {
            return None;
        }
        let hostname = if self.extract_hostname {
            extract_http_host(buf)
        } else {
            None
        };
        if hostname.is_none() {
            return None;
        }
        Some(SniffResult {
            protocol: Protocol::Http,
            hostname,
        })
    }

    fn try_vless(&self, buf: &[u8]) -> Option<SniffResult> {
        // VLESS header: 0x00 + 16-byte UUID + 0x00 + version + command.
        if buf.len() < 19 {
            return None;
        }
        if buf[0] != 0x00 {
            return None;
        }
        if buf[17] != 0x00 {
            return None;
        }
        Some(SniffResult {
            protocol: Protocol::Vless,
            hostname: None,
        })
    }

    fn try_trojan(&self, buf: &[u8]) -> bool {
        if buf.len() < 5 || !buf.starts_with(b"GET /") {
            return false;
        }
        let window = &buf[..buf.len().min(self.limit)];
        // Trojan: starts with GET / but has no Host header, no TLS, no VLESS,
        // and carries a Trojan marker header.
        !window.windows(6).any(|w| w.eq_ignore_ascii_case(b"host:"))
            || window.windows(7).any(|w| w.eq_ignore_ascii_case(b"trojan-"))
    }

    fn try_socks5(&self, buf: &[u8]) -> bool {
        buf.len() >= 2 && buf[0] == 0x05
    }

    fn try_shadowsocks(&self, buf: &[u8]) -> bool {
        if buf.len() < 32 {
            return false;
        }
        // High-entropy check: printable ASCII bytes ratio should be low
        // (random salt vs structured protocol headers).
        let printable = buf
            .iter()
            .take(32)
            .copied()
            .filter(|b| (0x21..=0x7e).contains(b))
            .count();
        printable < 16
    }
}

// ---------------------------------------------------------------------------
// HTTP Host header extraction
// ---------------------------------------------------------------------------

fn find_http_body(buf: &[u8]) -> usize {
    let mut i = 0;
    while i + 4 <= buf.len() {
        if &buf[i..i + 4] == b"\r\n\r\n" {
            return i + 4;
        }
        i += 1;
    }
    buf.len()
}

fn extract_http_host(buf: &[u8]) -> Option<String> {
    let body_start = find_http_body(buf);
    let headers = &buf[..body_start];

    let mut i = 0;
    while i < headers.len() {
        let line_end = headers[i..]
            .windows(2)
            .position(|w| w == b"\r\n")
            .map(|p| i + p)
            .unwrap_or(headers.len());
        let line = &headers[i..line_end];
        if line.len() > 5 && line[..5].eq_ignore_ascii_case(b"host:") {
            let value = line[5..]
                .iter()
                .copied()
                .skip_while(|b| *b == b' ')
                .collect::<Vec<_>>();
            let trimmed = value
                .into_iter()
                .take_while(|b| *b != b'\r' && *b != b' ')
                .collect::<Vec<_>>();
            return String::from_utf8(trimmed).ok();
        }
        i = line_end + 2;
    }
    None
}

// ---------------------------------------------------------------------------
// Module integration — plug into the Conductor
// ---------------------------------------------------------------------------

struct Inner {
    sniffer: Sniffer,
    reporter: Option<ModuleReporter>,
}

/// The sniffer module. Config is held in a `Mutex<Inner>` because
/// [`Module::configure`] takes `&self` — exactly the pattern rsxm-power
/// uses too.
pub struct SnifferModule {
    inner: Mutex<Inner>,
}

impl SnifferModule {
    pub fn new() -> Self {
        Self::with_config(SnifferConfig::default())
    }

    pub fn with_config(config: SnifferConfig) -> Self {
        Self {
            inner: Mutex::new(Inner {
                sniffer: Sniffer::from_config(&config),
                reporter: None,
            }),
        }
    }

    /// Returns a hot-path sniffer handle. Pure call — no channel, no I/O.
    pub fn sniffer(&self) -> Arc<Sniffer> {
        // Clone out of the Mutex guard; Sniffer is small and Send+Sync.
        let guard = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        Arc::new(guard.sniffer.clone())
    }
}

impl Default for SnifferModule {
    fn default() -> Self {
        Self::new()
    }
}

// Sniffer is intentionally Clone so we can wrap it in Arc cheaply.
impl Clone for Sniffer {
    fn clone(&self) -> Self {
        Self {
            limit: self.limit,
            extract_hostname: self.extract_hostname,
        }
    }
}

impl Module for SnifferModule {
    fn name(&self) -> &'static str {
        "rsxm-sniffer"
    }

    fn depends_on(&self) -> &'static [&'static str] {
        &[]
    }

    fn attach(&self, reporter: &ModuleReporter) {
        let mut inner = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        inner.reporter = Some(reporter.clone());
    }

    fn configure(&self, slice: Option<&ConfigSlice>) -> Result<(), String> {
        let mut inner = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(slice) = slice {
            let cfg: SnifferConfig = serde_json::from_value((*slice.value).clone())
                .map_err(|e| format!("invalid sniffer config: {e}"))?;
            inner.sniffer = Sniffer::from_config(&cfg);
        }
        if let Some(r) = &inner.reporter {
            r.info("sniffer configured");
        }
        Ok(())
    }

    fn start(&self) -> Result<(), String> {
        if let Some(r) = &self.inner.lock().unwrap().reporter {
            r.info("sniffer ready");
        }
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        Ok(())
    }

    fn health(&self) -> Health {
        Health::Up
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn sniffer() -> Sniffer {
        Sniffer::new(2048, true)
    }

    #[test]
    fn identifies_tls_clienthello() {
        // Minimal TLS 1.2 ClientHello — header says handshake record.
        let buf = hex_decode(
            "1603010005010000010303c02fc300aa1301615a1f9a2b1a7d8a7a4aa9b3a8e93f400003800ff010001000019000000160000000d00000a0007000004000000000000000143000101",
        );
        let result = sniffer().sniff(&buf);
        assert_eq!(result.protocol, Protocol::Tls);
    }

    #[test]
    fn identifies_http_request_with_host() {
        let buf = b"GET /index.html HTTP/1.1\r\nHost: example.com\r\nUser-Agent: curl\r\n\r\n";
        let result = sniffer().sniff(buf);
        assert_eq!(result.protocol, Protocol::Http);
        assert_eq!(result.hostname.as_deref(), Some("example.com"));
    }

    #[test]
    fn identifies_http_post_request() {
        let buf = b"POST /api/v1/login HTTP/1.1\r\nHost: api.example.com\r\nContent-Length: 0\r\n\r\n";
        let result = sniffer().sniff(buf);
        assert_eq!(result.protocol, Protocol::Http);
        assert_eq!(result.hostname.as_deref(), Some("api.example.com"));
    }

    #[test]
    fn identifies_socks5() {
        let buf = [0x05, 0x01, 0x00];
        let result = sniffer().sniff(&buf);
        assert_eq!(result.protocol, Protocol::Socks5);
    }

    #[test]
    fn identifies_vless_header() {
        let mut buf = vec![0x00u8]; // version
        buf.extend_from_slice(&[0u8; 16]); // fake UUID
        buf.push(0x00); // addons length
        buf.push(0x00); // version
        let result = sniffer().sniff(&buf);
        assert_eq!(result.protocol, Protocol::Vless);
    }

    #[test]
    fn returns_unknown_for_empty_buffer() {
        let result = sniffer().sniff(&[]);
        assert_eq!(result.protocol, Protocol::Unknown);
        assert!(result.hostname.is_none());
    }

    #[test]
    fn identifies_quic_long_header() {
        // QUIC v1 Initial: long header (0x80) + version 1.
        let buf = [0x80u8, 0x00, 0x00, 0x00, 0x01, 0x04, 0x00, 0x00];
        let result = sniffer().sniff(&buf);
        assert_eq!(result.protocol, Protocol::Quic);
    }

    #[test]
    fn configure_updates_runtime_config() {
        let m = SnifferModule::with_config(SnifferConfig {
            byte_limit: 4096,
            extract_hostname: false,
        });
        assert!(m.configure(None).is_ok());
        // A slice overrides.
        let slice = ConfigSlice::new(
            "rsxm-sniffer",
            serde_json::json!({"byte_limit": 1024, "extract_hostname": true}),
        );
        assert!(m.configure(Some(&slice)).is_ok());
        assert!(m.start().is_ok());
    }

    fn hex_decode(hex: &str) -> Vec<u8> {
        hex.as_bytes()
            .chunks(2)
            .filter(|chunk| chunk.len() == 2)
            .map(|chunk| u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16).unwrap())
            .collect()
    }
}
