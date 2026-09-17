//! Shadowsocks 2022 protocol implementation — the most widely adopted
//! encryption layer across the subscription ecosystem today.
//!
//! Unlike legacy SS which only protects payload frames, the 2022 variant
//! also encrypts the per-stream address header, making it invisible to
//! DPI that would otherwise fingerprint the SOCKS-like 0x01/0x03 byte.
//!
//! Wire layout of one TCP chunk, from the sender's point of view:
//!
//! ```text
//! [header_len(2BE) AEAD][ATYP(1) ADDR(var) PORT(2BE)] [payload_len(2BE) AEAD][payload N bytes]
//! ```
//!
//! Each AEAD chunk reuses the session's unique 24-byte nonce, incremented
//! as a 64-bit counter on each message. The 2022-blake3 variants are not
//! yet implemented here — we cover aes-*-gcm, chacha20-ietf-poly1305 and
//! the legacy `none` (kept for parity with sing-box; never recommended).

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes128Gcm, Aes256Gcm};
use chacha20poly1305::ChaCha20Poly1305;
use hmac::{Hmac, Mac};
use sha2::{Sha256};

pub type HmacSha256 = Hmac<Sha256>;

/// Supported encryption methods. The list is a subset of what sing-box
/// exposes — deliberately excluding legacy stream ciphers and blake3
/// variants until the crate pulls in a blake3 dependency.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SsMethod {
    /// Standard AES-128-GCM, zero-IV key schedule via HKDF.
    Aes128Gcm,
    /// Standard AES-256-GCM.
    Aes256Gcm,
    /// ChaCha20-Poly1305 (IETF variant, 24-byte nonce).
    ChaCha20IetfPoly1305,
    /// No encryption. Only useful for debugging; sing-box treats it as an
    /// error in production configs and so does `validate()`.
    None,
}

impl SsMethod {
    /// Parse a sing-box method string.
    pub fn from_str(s: &str) -> Option<Self> {
        Some(match s {
            "aes-128-gcm" => Self::Aes128Gcm,
            "aes-256-gcm" => Self::Aes256Gcm,
            "chacha20-ietf-poly1305" | "xchacha20-ietf-poly1305" => Self::ChaCha20IetfPoly1305,
            "none" => Self::None,
            // Legacy stream ciphers intentionally unsupported.
            _ => return None,
        })
    }

    /// Canonical string label.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Aes128Gcm => "aes-128-gcm",
            Self::Aes256Gcm => "aes-256-gcm",
            Self::ChaCha20IetfPoly1305 => "chacha20-ietf-poly1305",
            Self::None => "none",
        }
    }

    /// Length of the session key the method requires, in bytes.
    pub fn key_len(self) -> usize {
        match self {
            Self::Aes128Gcm => 16,
            Self::Aes256Gcm => 32,
            Self::ChaCha20IetfPoly1305 => 32,
            Self::None => 0,
        }
    }

    /// 24-byte nonce size — the counter field that rolls over per chunk.
    pub const NONCE_LEN: usize = 12;

    /// 16-byte tag appended by all AEAD schemes we support.
    pub const TAG_LEN: usize = 16;
}

/// Fatal problems that make an SS 2022 outbound unusable.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SsConfigError {
    /// `method = none` is rejected by default — it's only useful for tests.
    InsecureMethod,
    /// Password → key derivation produced the wrong length for this method.
    WrongKeyLength { expected: usize, got: usize },
    /// Method string was not recognised.
    UnknownMethod(String),
}

impl std::fmt::Display for SsConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InsecureMethod => f.write_str(
                "`method = none` is not allowed in production — enable allow_none if this is intentional",
            ),
            Self::WrongKeyLength { expected, got } => {
                write!(f, "key length mismatch: method needs {expected}B, got {got}B")
            }
            Self::UnknownMethod(m) => write!(f, "unknown shadowsocks method: {m}"),
        }
    }
}

/// One outbound node's SS2022 credentials.
#[derive(Debug, Clone)]
pub struct Ss2022Config {
    pub server: String,
    pub port: u16,
    pub method: SsMethod,
    /// Raw session key derived from the password. `key_len()` bytes.
    pub key: Vec<u8>,
    /// Optional SIP003 obfs ("obfs-local", "v2ray-plugin").
    pub plugin: Option<String>,
    pub plugin_opts: Option<String>,
    /// Whether this outbound should be allowed when method is None.
    pub allow_none: bool,
}

impl Ss2022Config {
    /// Build an outbound from a sing-box-style password string.
    /// Per spec the session key is `HKDF-SHA256(password, salt="ss-subkey", info=method).
    pub fn new(server: impl Into<String>, port: u16, method: SsMethod, password: &str) -> Result<Self, SsConfigError> {
        if method == SsMethod::None {
            return Err(SsConfigError::InsecureMethod);
        }
        let expected = method.key_len();
        let key = hkdf_derive(password.as_bytes(), b"ss-subkey", method.as_str().as_bytes(), expected);
        if key.len() != expected {
            return Err(SsConfigError::WrongKeyLength { expected, got: key.len() });
        }
        Ok(Self {
            server: server.into(),
            port,
            method,
            key,
            plugin: None,
            plugin_opts: None,
            allow_none: false,
        })
    }

    pub fn validate(&self) -> Result<(), SsConfigError> {
        if self.method == SsMethod::None && !self.allow_none {
            return Err(SsConfigError::InsecureMethod);
        }
        Ok(())
    }
}

/// Derive `out_len` bytes from `ikm` using HKDF-SHA256, matching the
/// Shadowsocks 2022 specification.
pub fn hkdf_derive(ikm: &[u8], salt: &[u8], info: &[u8], out_len: usize) -> Vec<u8> {
    use hkdf::Hkdf;
    let hk = Hkdf::<Sha256>::new(Some(salt), ikm);
    let mut out = vec![0u8; out_len];
    hk.expand(info, &mut out).expect("HKDF expand never fails given a valid length");
    out
}

/// A single direction (client→server OR server→client) of an SS2022 session.
///
/// Shadowsocks encrypts every chunk independently, keyed by the session key
/// and a monotonically-increasing counter nonce. The counter lives in a
/// 24-byte `[0..8]` prefix of the nonce — the rest is zero padding per spec.
pub struct SsSession {
    pub config: Ss2022Config,
    /// 24-byte nonce shared across all chunks. [0..8] is the counter.
    pub nonce: [u8; SsMethod::NONCE_LEN],
}

impl SsSession {
    pub fn new(config: Ss2022Config) -> Self {
        Self { config, nonce: [0u8; SsMethod::NONCE_LEN] }
    }

    /// Increment the 64-bit counter half of the nonce.
    fn bump_nonce(&mut self) {
        let mut c = u64::from_le_bytes(self.nonce[..8].try_into().unwrap());
        c = c.wrapping_add(1);
        self.nonce[..8].copy_from_slice(&c.to_le_bytes());
    }

    fn nonce_bytes(&self) -> &[u8] {
        &self.nonce
    }

    /// Encrypt one chunk (header or payload), prepending the 2-byte big-endian
    /// length that shadowsocks calls `AEAD_LEN`.
    pub fn encrypt_chunk(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        self.bump_nonce();
        let ciphertext = encrypt(&self.config.method, &self.config.key, self.nonce_bytes(), plaintext)?;
        let mut out = Vec::with_capacity(2 + ciphertext.len());
        out.extend_from_slice(&(ciphertext.len() as u16).to_be_bytes());
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    /// Decrypt one chunk given a `[2-byte-len][ciphertext..]` buffer.
    /// Returns (decrypted plaintext, bytes_consumed) so the caller can
    /// advance its read cursor.
    pub fn decrypt_chunk(&mut self, buf: &[u8]) -> Result<(Vec<u8>, usize), String> {
        if buf.len() < 2 {
            return Err("need at least 2 bytes for length prefix".into());
        }
        let ct_len = u16::from_be_bytes(buf[..2].try_into().unwrap()) as usize;
        if buf.len() < 2 + ct_len {
            return Err(format!("incomplete ciphertext: want {ct_len}B, got {}", buf.len() - 2));
        }
        self.bump_nonce();
        let plaintext = decrypt(&self.config.method, &self.config.key, self.nonce_bytes(), &buf[2..2 + ct_len])?;
        Ok((plaintext, 2 + ct_len))
    }
}

/// Encrypt `plaintext` with the given method/key/nonce. 16B tag is appended
/// by the underlying AEAD; callers see `ciphertext_len + TAG_LEN`.
pub fn encrypt(method: &SsMethod, key: &[u8], nonce: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, String> {
    if *method == SsMethod::None {
        return Ok(plaintext.to_vec());
    }
    match method {
        SsMethod::Aes128Gcm => {
            let cipher = Aes128Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
            let ciphertext = cipher
                .encrypt(aes_gcm::Nonce::from_slice(nonce), plaintext)
                .map_err(|e| e.to_string())?;
            Ok(ciphertext)
        }
        SsMethod::Aes256Gcm => {
            let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
            let ciphertext = cipher
                .encrypt(aes_gcm::Nonce::from_slice(nonce), plaintext)
                .map_err(|e| e.to_string())?;
            Ok(ciphertext)
        }
        SsMethod::ChaCha20IetfPoly1305 => {
            let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|e| e.to_string())?;
            let ciphertext = cipher
                .encrypt(chacha20poly1305::Nonce::from_slice(nonce), plaintext)
                .map_err(|e| e.to_string())?;
            Ok(ciphertext)
        }
        SsMethod::None => Ok(plaintext.to_vec()),
    }
}

pub fn decrypt(method: &SsMethod, key: &[u8], nonce: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    if *method == SsMethod::None {
        return Ok(ciphertext.to_vec());
    }
    match method {
        SsMethod::Aes128Gcm => {
            let cipher = Aes128Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
            cipher
                .decrypt(aes_gcm::Nonce::from_slice(nonce), ciphertext)
                .map_err(|e| e.to_string())
        }
        SsMethod::Aes256Gcm => {
            let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
            cipher
                .decrypt(aes_gcm::Nonce::from_slice(nonce), ciphertext)
                .map_err(|e| e.to_string())
        }
        SsMethod::ChaCha20IetfPoly1305 => {
            let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|e| e.to_string())?;
            cipher
                .decrypt(chacha20poly1305::Nonce::from_slice(nonce), ciphertext)
                .map_err(|e| e.to_string())
        }
        SsMethod::None => Ok(ciphertext.to_vec()),
    }
}

// --- HMAC-SHA256 helpers exposed for plugin obfs (obfs-local) -------------

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    use hmac::Mac;
    let mut mac = <HmacSha256 as Mac>::new(key.into());
    mac.update(data);
    mac.finalize().into_bytes().into()
}

// --- SOCKS address encoding used by the header ----------------------------

/// Address types accepted by the SS2022 header (identical to SOCKS5 ATYP).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SocksAddr {
    V4([u8; 4], u16),
    V6([u8; 16], u16),
    Domain(String, u16),
}

impl SocksAddr {
    /// Encode into `[ATYP(1) payload..PORT(2BE)]`.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(24);
        let port: u16;
        match self {
            Self::V4(ip, p) => {
                port = *p;
                out.push(1);
                out.extend_from_slice(ip);
            }
            Self::Domain(d, p) => {
                port = *p;
                out.push(3);
                let b = d.as_bytes();
                out.push(b.len() as u8);
                out.extend_from_slice(b);
            }
            Self::V6(ip, p) => {
                port = *p;
                out.push(4);
                out.extend_from_slice(ip);
            }
        }
        out.extend_from_slice(&port.to_be_bytes());
        out
    }

    /// Parse a SOCKS address from the start of `buf`. Returns the address
    /// and the number of bytes consumed.
    pub fn from_bytes(buf: &[u8]) -> Result<(Self, usize), String> {
        if buf.is_empty() {
            return Err("empty socks address".into());
        }
        match buf[0] {
            1 => {
                if buf.len() < 7 {
                    return Err("short v4 address".into());
                }
                let mut ip = [0u8; 4];
                ip.copy_from_slice(&buf[1..5]);
                let port = u16::from_be_bytes([buf[5], buf[6]]);
                Ok((Self::V4(ip, port), 7))
            }
            3 => {
                if buf.len() < 2 {
                    return Err("short domain address".into());
                }
                let len = buf[1] as usize;
                if buf.len() < 2 + len + 2 {
                    return Err("short domain payload".into());
                }
                let domain = String::from_utf8(buf[2..2 + len].to_vec())
                    .map_err(|e| format!("invalid domain utf-8: {e}"))?;
                let port = u16::from_be_bytes([buf[2 + len], buf[2 + len + 1]]);
                Ok((Self::Domain(domain, port), 2 + len + 2))
            }
            4 => {
                if buf.len() < 19 {
                    return Err("short v6 address".into());
                }
                let mut ip = [0u8; 16];
                ip.copy_from_slice(&buf[1..17]);
                let port = u16::from_be_bytes([buf[17], buf[18]]);
                Ok((Self::V6(ip, port), 19))
            }
            other => Err(format!("unknown ATYP: 0x{other:02x}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn method_parse_roundtrip() {
        for (s, m) in [
            ("aes-128-gcm", SsMethod::Aes128Gcm),
            ("aes-256-gcm", SsMethod::Aes256Gcm),
            ("chacha20-ietf-poly1305", SsMethod::ChaCha20IetfPoly1305),
            ("none", SsMethod::None),
        ] {
            assert_eq!(SsMethod::from_str(s), Some(m));
            assert_eq!(m.as_str(), s);
        }
        assert!(SsMethod::from_str("aes-128-ctr").is_none());
    }

    #[test]
    fn rejects_none_by_default() {
        let err = Ss2022Config::new("127.0.0.1", 1080, SsMethod::None, "pw").unwrap_err();
        assert!(matches!(err, SsConfigError::InsecureMethod));
    }

    #[test]
    fn hkdf_derive_produces_correct_len() {
        let pw = "test-password";
        let key16 = hkdf_derive(pw.as_bytes(), b"ss-subkey", b"aes-128-gcm", 16);
        assert_eq!(key16.len(), 16);
        let key32 = hkdf_derive(pw.as_bytes(), b"ss-subkey", b"aes-256-gcm", 32);
        assert_eq!(key32.len(), 32);
        // Different info strings must yield different keys.
        assert_ne!(key16, key32[..16]);
    }

    #[test]
    fn aes128gcm_roundtrip() {
        let cfg = Ss2022Config::new("127.0.0.1", 443, SsMethod::Aes128Gcm, "hunter2").unwrap();
        let mut s = SsSession::new(cfg.clone());
        let mut dec = SsSession::new(cfg);
        let plain = b"hello shadowsocks 2022, this is a test payload";
        let encrypted = s.encrypt_chunk(plain).unwrap();
        assert!(encrypted.len() >= 2 + plain.len() + SsMethod::TAG_LEN);
        let (decrypted, consumed) = dec.decrypt_chunk(&encrypted).unwrap();
        assert_eq!(consumed, encrypted.len());
        assert_eq!(decrypted, plain);
    }

    #[test]
    fn aes256gcm_roundtrip() {
        let cfg = Ss2022Config::new("127.0.0.1", 443, SsMethod::Aes256Gcm, "hunter2").unwrap();
        let mut s = SsSession::new(cfg.clone());
        let mut dec = SsSession::new(cfg);
        let plain = b"aes256 data packet";
        let encrypted = s.encrypt_chunk(plain).unwrap();
        let (decrypted, _) = dec.decrypt_chunk(&encrypted).unwrap();
        assert_eq!(decrypted, plain);
    }

    #[test]
    fn chacha20_poly1305_roundtrip() {
        let cfg = Ss2022Config::new("example.com", 8388, SsMethod::ChaCha20IetfPoly1305, "super-secret-pw").unwrap();
        let mut s = SsSession::new(cfg.clone());
        let mut dec = SsSession::new(cfg);
        let plain = b"QUIC-over-SS is cool";
        let encrypted = s.encrypt_chunk(plain).unwrap();
        let (decrypted, _) = dec.decrypt_chunk(&encrypted).unwrap();
        assert_eq!(decrypted, plain);
    }

    #[test]
    fn nonce_counter_increments() {
        let cfg = Ss2022Config::new("1.2.3.4", 9, SsMethod::Aes128Gcm, "pw").unwrap();
        let mut s = SsSession::new(cfg.clone());
        let mut dec = SsSession::new(cfg);
        let before = s.nonce;
        s.encrypt_chunk(b"a").unwrap();
        s.encrypt_chunk(b"b").unwrap();
        s.encrypt_chunk(b"c").unwrap();
        let after = s.nonce;
        // First 8 bytes (counter) should have advanced by 3.
        let before_counter = u64::from_le_bytes(before[..8].try_into().unwrap());
        let after_counter = u64::from_le_bytes(after[..8].try_into().unwrap());
        assert_eq!(after_counter.wrapping_sub(before_counter), 3);
    }

    #[test]
    fn socks_addr_roundtrip() {
        let cases = [
            SocksAddr::V4([127, 0, 0, 1], 443),
            SocksAddr::Domain("example.com".into(), 8080),
            SocksAddr::V6([0u8; 16], 443),
        ];
        for addr in cases {
            let bytes = addr.to_bytes();
            let (parsed, consumed) = SocksAddr::from_bytes(&bytes).unwrap();
            assert_eq!(consumed, bytes.len());
            assert_eq!(parsed, addr);
        }
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let cfg = Ss2022Config::new("1.2.3.4", 1, SsMethod::Aes256Gcm, "pw").unwrap();
        let mut s = SsSession::new(cfg.clone());
        let mut dec = SsSession::new(cfg);
        let mut buf = s.encrypt_chunk(b"payload").unwrap();
        // Flip one byte inside the ciphertext (skip the 2-byte length prefix).
        if buf.len() > 3 {
            buf[2] ^= 0xff;
        }
        let err = s.decrypt_chunk(&buf);
        assert!(err.is_err());
    }
}
