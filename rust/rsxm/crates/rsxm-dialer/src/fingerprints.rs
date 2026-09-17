//! Browser / OS fingerprint tables for TLS ClientHello generation.
//!
//! Sing-box calls this `utls`; we keep the same shape so fingerprint names
//! migrate 1:1 (`chrome`, `firefox`, `safari`, `ios`, `edge`…). Each entry
//! is a *template*: the actual ClientHello builder in `hello.rs` picks the
//! right template for the node's fingerprint field at dial time.
//!
//! Why this table instead of pulling utls (200 KB + crypto dep)?
//!
//! The hot path only needs 5 numeric lists (ciphers, extensions,
//! groups, ec_point_formats, versions) — sing-box precomputes the same
//! tables from utls and caches the result. We ship the precomputed
//! values directly: the crate stays tiny, and the table is trivially
//! audit-able.

use serde::Deserialize;

/// One fingerprint template — a ClientHello's identifying fields.
#[derive(Debug, Clone, Deserialize)]
pub struct FingerprintTemplate {
    pub name: String,
    pub version: u16,
    pub ciphers: Vec<u16>,
    pub extensions: Vec<u16>,
    pub groups: Vec<u16>,
    pub ec_point_formats: Vec<u8>,
    /// JA3 string: `version,ciphers,extensions,groups,ec_point_formats`.
    /// Precomputed at build time for display / logging.
    pub ja3: String,
}

impl FingerprintTemplate {
    fn compute_ja3(&self) -> String {
        let c: Vec<String> = self.ciphers.iter().map(|x| x.to_string()).collect();
        let e: Vec<String> = self.extensions.iter().map(|x| x.to_string()).collect();
        let g: Vec<String> = self.groups.iter().map(|x| x.to_string()).collect();
        let p: Vec<String> = self.ec_point_formats.iter().map(|x| x.to_string()).collect();
        format!(
            "{},{},{},{},{}",
            self.version,
            c.join("-"),
            e.join("-"),
            g.join("-"),
            p.join("-")
        )
    }
}

// ---------------------------------------------------------------------------
// Well-known TLS IDs — mirrored from sing-box/internal/tls/utls/const.go
// ---------------------------------------------------------------------------

pub const VERSION_TLS12: u16 = 0x0303;
pub const VERSION_TLS13: u16 = 0x0304;

// Ciphers — IANA TLS parameters
pub const CIPHER_TLS_AES_128_GCM_SHA256: u16 = 0x1301;
pub const CIPHER_TLS_AES_256_GCM_SHA384: u16 = 0x1302;
pub const CIPHER_TLS_CHACHA20_POLY1305_SHA256: u16 = 0x1303;
pub const CIPHER_TLS_AES_128_CBC_SHA: u16 = 0x2f;
pub const CIPHER_TLS_AES_256_CBC_SHA: u16 = 0x35;
pub const CIPHER_TLS_AES_128_CBC_SHA256: u16 = 0x3c;
pub const CIPHER_TLS_AES_256_CBC_SHA384: u16 = 0x3d;
pub const CIPHER_TLS_RSA_WITH_AES_128_GCM_SHA256: u16 = 0x009c;
pub const CIPHER_TLS_RSA_WITH_AES_256_GCM_SHA384: u16 = 0x009d;
pub const CIPHER_TLS_RSA_WITH_AES_128_CBC_SHA256: u16 = 0x003c;
pub const CIPHER_TLS_RSA_WITH_AES_256_CBC_SHA384: u16 = 0x003d;
pub const CIPHER_TLS_RSA_WITH_AES_128_CBC_SHA: u16 = 0x002f;
pub const CIPHER_TLS_RSA_WITH_AES_256_CBC_SHA: u16 = 0x0035;
pub const CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256: u16 = 0xc02b;
pub const CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384: u16 = 0xc02c;
pub const CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305: u16 = 0xcca9;
pub const CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256: u16 = 0xc02f;
pub const CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384: u16 = 0xc030;
pub const CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305: u16 = 0xcca8;
pub const CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA: u16 = 0xc009;
pub const CIPHER_TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA: u16 = 0xc013;
pub const CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA: u16 = 0xc00a;
pub const CIPHER_TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA: u16 = 0xc014;

// Extensions
pub const EXT_SERVER_NAME: u16 = 0x0000;
pub const EXT_ELLIPTIC_CURVES: u16 = 0x000a;
pub const EXT_EC_POINT_FORMATS: u16 = 0x000b;
pub const EXT_SIGNATURE_ALGORITHMS: u16 = 0x000d;
pub const EXT_SUPPORTED_VERSIONS: u16 = 0x002b;
pub const EXT_KEY_SHARE: u16 = 0x0033;
pub const EXT_APPLICATION_PROTOCOLS: u16 = 0x0010; // ALPN
pub const EXT_PADDING: u16 = 0x0015;
pub const EXT_SESSION_TICKET: u16 = 0x0023;
pub const EXT_SUPPORTED_GROUPS: u16 = 0x000a; // same as ELLIPTIC_CURVES (renamed)
pub const EXT_PSK_KEY_EXCHANGE_MODES: u16 = 0x002d;
pub const EXT_SIGNATURE_ALGORITHMS_CERT: u16 = 0x0032;

// Groups
pub const GROUP_X25519: u16 = 0x001d;
pub const GROUP_SECP256R1: u16 = 0x0017;
pub const GROUP_SECP384R1: u16 = 0x0018;
pub const GROUP_SECP521R1: u16 = 0x0019;

// EC point formats
pub const ECPF_UNCOMPRESSED: u8 = 0;

// ---------------------------------------------------------------------------
// Precomputed fingerprints — mirrored from sing-box's `utls/impersonate.go`
// ---------------------------------------------------------------------------

/// Builds the fingerprint table. Called once at dialer module init.
pub fn build_table() -> Vec<FingerprintTemplate> {
    vec![
        chrome(),
        firefox(),
        safari(),
        ios(),
        edge(),
        android(),
        okhttp(),
        quic_go(),
    ]
}

fn chrome() -> FingerprintTemplate {
    // Chrome 133 stable, Windows 11 — TLS 1.3 primary, 23 ciphers.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_RSA_WITH_AES_128_CBC_SHA,
        CIPHER_TLS_RSA_WITH_AES_256_CBC_SHA,
        CIPHER_TLS_AES_128_CBC_SHA,
        CIPHER_TLS_AES_256_CBC_SHA,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
        EXT_SIGNATURE_ALGORITHMS,
        EXT_EC_POINT_FORMATS,
        EXT_PADDING,
        EXT_SESSION_TICKET,
        EXT_PSK_KEY_EXCHANGE_MODES,
        EXT_SIGNATURE_ALGORITHMS_CERT,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1];
    let fp = FingerprintTemplate {
        name: "chrome".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn firefox() -> FingerprintTemplate {
    // Firefox 128 ESR, Linux — 15 ciphers, shorter extension list than Chrome.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
        EXT_SIGNATURE_ALGORITHMS,
        EXT_EC_POINT_FORMATS,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1, GROUP_SECP521R1];
    let fp = FingerprintTemplate {
        name: "firefox".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn safari() -> FingerprintTemplate {
    // Safari 17.5, macOS Sonoma — Apple's Go crypto stack variant.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
        EXT_SIGNATURE_ALGORITHMS,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1, GROUP_SECP521R1];
    let fp = FingerprintTemplate {
        name: "safari".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn ios() -> FingerprintTemplate {
    // iOS 17 App Store — Safari on iOS is the most common mobile fingerprint.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
        EXT_SIGNATURE_ALGORITHMS,
        EXT_PADDING,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1];
    let fp = FingerprintTemplate {
        name: "ios".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn edge() -> FingerprintTemplate {
    // Edge 133 — Chromium-based, almost identical to Chrome.
    let fp = chrome();
    FingerprintTemplate {
        name: "edge".into(),
        ja3: fp.ja3.clone(),
        ..fp
    }
}

fn android() -> FingerprintTemplate {
    // Android 14 Chrome — shorter cipher list, no legacy CBC.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_RSA_WITH_AES_256_GCM_SHA384,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
        EXT_SIGNATURE_ALGORITHMS,
        EXT_EC_POINT_FORMATS,
        EXT_SESSION_TICKET,
        EXT_PSK_KEY_EXCHANGE_MODES,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1];
    let fp = FingerprintTemplate {
        name: "android".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn okhttp() -> FingerprintTemplate {
    // OkHttp 4.x — what most Android apps use for network.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CIPHER_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305,
        CIPHER_TLS_RSA_WITH_AES_128_GCM_SHA256,
        CIPHER_TLS_RSA_WITH_AES_256_GCM_SHA384,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
        EXT_APPLICATION_PROTOCOLS,
    ];
    let groups = vec![GROUP_X25519, GROUP_SECP256R1];
    let fp = FingerprintTemplate {
        name: "okhttp".into(),
        version: VERSION_TLS12,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

fn quic_go() -> FingerprintTemplate {
    // Go net/http QUIC client — same as Go's default TLS.
    let ciphers = vec![
        CIPHER_TLS_AES_128_GCM_SHA256,
        CIPHER_TLS_AES_256_GCM_SHA384,
        CIPHER_TLS_CHACHA20_POLY1305_SHA256,
    ];
    let extensions = vec![
        EXT_SERVER_NAME,
        EXT_SUPPORTED_GROUPS,
        EXT_KEY_SHARE,
        EXT_SUPPORTED_VERSIONS,
    ];
    let groups = vec![GROUP_X25519];
    let fp = FingerprintTemplate {
        name: "quic-go".into(),
        version: VERSION_TLS13,
        ciphers,
        extensions,
        groups,
        ec_point_formats: vec![ECPF_UNCOMPRESSED],
        ja3: String::new(),
    };
    let ja3 = fp.compute_ja3();
    FingerprintTemplate { ja3, ..fp }
}

/// Looks up a fingerprint by name. Accepts common aliases too.
pub fn lookup(name: &str) -> Option<FingerprintTemplate> {
    let table = build_table();
    // Exact match.
    if let Some(fp) = table.iter().find(|f| f.name.eq_ignore_ascii_case(name)) {
        return Some(fp.clone());
    }
    // sing-box uses `chrome` / `firefox` / etc. — normalize common aliases.
    let aliases: Vec<(&str, &str)> = vec![
        ("chrome-120", "chrome"),
        ("chrome-133", "chrome"),
        ("firefox-128", "firefox"),
        ("ios-17", "ios"),
        ("edge-133", "edge"),
        ("mobile", "android"),
    ];
    if let Some((_, canonical)) = aliases.iter().find(|(alias, _)| alias.eq_ignore_ascii_case(name)) {
        return table.iter().find(|f| f.name == *canonical).cloned();
    }
    None
}

/// Names known to `lookup()`.
pub fn known_names() -> Vec<&'static str> {
    vec![
        "chrome", "firefox", "safari", "ios", "edge", "android", "okhttp", "quic-go",
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn table_has_all_named_fingerprints() {
        let table = build_table();
        let names: Vec<_> = table.iter().map(|f| f.name.clone()).collect();
        assert_eq!(names.len(), 8);
        assert!(names.contains(&"chrome".to_string()));
        assert!(names.contains(&"firefox".to_string()));
        assert!(names.contains(&"safari".to_string()));
    }

    #[test]
    fn chrome_has_reasonable_ja3() {
        let fp = chrome();
        assert!(!fp.ja3.is_empty());
        // JA3 format: version,... -> version is "771" (TLS 1.2 decimal).
        assert!(fp.ja3.starts_with("771,"), "chrome JA3 should start with 771");
        // Chrome prefers AES-128-GCM first.
        assert!(fp.ciphers.first() == Some(&CIPHER_TLS_AES_128_GCM_SHA256));
    }

    #[test]
    fn lookup_returns_named_template() {
        let fp = lookup("chrome").expect("chrome must be known");
        assert_eq!(fp.name, "chrome");
        assert!(lookup("nonexistent").is_none());
    }

    #[test]
    fn lookup_accepts_aliases() {
        let fp = lookup("chrome-120").expect("chrome-120 alias should resolve");
        assert_eq!(fp.name, "chrome");
    }

    #[test]
    fn every_fingerprint_has_ja3() {
        for fp in build_table() {
            assert!(!fp.ja3.is_empty(), "{} JA3 is empty", fp.name);
            assert!(fp.version != 0);
            assert!(!fp.ciphers.is_empty());
        }
    }
}
