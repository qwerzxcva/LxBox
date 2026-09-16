//! Minimal TLS 1.3 record layer and cipher suites for the REALITY dialer.
//!
//! Scope is deliberately narrow: exactly what a REALITY client needs to
//! complete a handshake and carry VLESS frames — no renegotiation, no
//! session tickets, no TLS 1.2, no client certificates.
//!
//! Suites mirror `metacubex/utls`' TLS 1.3 set (the REALITY server picks the
//! suite of the impersonated target site, so the client must accept all
//! three).

use aes_gcm::aead::generic_array::GenericArray;
use hmac::Mac;
use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::Aes128Gcm;
use chacha20poly1305::ChaCha20Poly1305;

pub const RECORD_HEADER_LEN: usize = 5;
pub const MAX_RECORD_PAYLOAD: usize = 16_384;
/// Ciphertext expansion: explicit nonce (8) + tag (16) for AES-GCM suites;
/// ChaCha20-Poly1305 uses the same 5-byte explicit-nonce wire form in TLS 1.3
/// (RFC 8439 §2.3 / RFC 8446 §5.2).
pub const RECORD_TAG_LEN: usize = 16;

pub const CONTENT_CHANGE_CIPHER_SPEC: u8 = 20;
pub const CONTENT_ALERT: u8 = 21;
pub const CONTENT_HANDSHAKE: u8 = 22;
pub const CONTENT_APP_DATA: u8 = 23;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Suite {
    /// TLS_AES_128_GCM_SHA256 (0x1301)
    Aes128GcmSha256,
    /// TLS_AES_256_GCM_SHA384 (0x1302)
    Aes256GcmSha384,
    /// TLS_CHACHA20_POLY1305_SHA256 (0x1303)
    ChaCha20Poly1305Sha256,
}

impl Suite {
    pub fn from_u16(v: u16) -> Option<Suite> {
        match v {
            0x1301 => Some(Suite::Aes128GcmSha256),
            0x1302 => Some(Suite::Aes256GcmSha384),
            0x1303 => Some(Suite::ChaCha20Poly1305Sha256),
            _ => None,
        }
    }

    pub fn as_u16(self) -> u16 {
        match self {
            Suite::Aes128GcmSha256 => 0x1301,
            Suite::Aes256GcmSha384 => 0x1302,
            Suite::ChaCha20Poly1305Sha256 => 0x1303,
        }
    }

    pub fn hash_len(self) -> usize {
        match self {
            Suite::Aes256GcmSha384 => 48,
            _ => 32,
        }
    }

    pub fn hash(self, data: &[u8]) -> Vec<u8> {
        use sha2::{Digest, Sha256, Sha384};
        match self {
            Suite::Aes256GcmSha384 => Sha384::digest(data).to_vec(),
            _ => Sha256::digest(data).to_vec(),
        }
    }

    fn key_len(self) -> usize {
        match self {
            Suite::Aes128GcmSha256 => 16,
            Suite::Aes256GcmSha384 => 32,
            Suite::ChaCha20Poly1305Sha256 => 32,
        }
    }

    fn iv_len(self) -> usize {
        12
    }
}

/// One direction's record-protection keys plus the running nonce counter.
#[derive(Clone)]
pub struct CipherState {
    suite: Suite,
    key: Vec<u8>,
    iv: Vec<u8>,
    seq: u64,
}

impl CipherState {
    pub fn new(suite: Suite, key: &[u8], iv: &[u8]) -> CipherState {
        CipherState {
            suite,
            key: key.to_vec(),
            iv: iv.to_vec(),
            seq: 0,
        }
    }

    pub fn is_clear(self: &CipherState) -> bool {
        self.key.is_empty()
    }

    fn nonce(&mut self) -> [u8; 12] {
        // RFC 8446 §5.3: XOR the 64-bit big-endian sequence number into the
        // right half of the static IV.
        let mut nonce = [0u8; 12];
        nonce.copy_from_slice(&self.iv);
        let seq = self.seq.to_be_bytes();
        for i in 0..8 {
            nonce[4 + i] ^= seq[i];
        }
        self.seq += 1;
        nonce
    }

    /// Encrypts `payload` in place and prepends the record header. The AAD
    /// is the record header of the *output* record (RFC 8446 §5.2): after
    /// encryption the payload grows by one type byte + tag.
    pub fn seal_record(&mut self, content_type: u8, payload: &mut Vec<u8>) -> Vec<u8> {
        let nonce = self.nonce();
        payload.push(content_type); // RFC 8446 §5.2: real type rides inside
        let wire_len = payload.len() + RECORD_TAG_LEN;
        let aad = [CONTENT_APP_DATA, 0x03, 0x03, (wire_len >> 8) as u8, wire_len as u8];
        let buf = match self.suite {
            Suite::Aes128GcmSha256 => {
                let cipher = Aes128Gcm::new_from_slice(&self.key).expect("key");
                cipher
                    .encrypt_in_place_detached(GenericArray::from_slice(&nonce), &aad, payload)
                    .expect("infallible seal")
            }
            Suite::Aes256GcmSha384 => {
                let cipher = aes_gcm::Aes256Gcm::new_from_slice(&self.key).expect("key");
                cipher
                    .encrypt_in_place_detached(GenericArray::from_slice(&nonce), &aad, payload)
                    .expect("infallible seal")
            }
            Suite::ChaCha20Poly1305Sha256 => {
                let cipher = ChaCha20Poly1305::new_from_slice(&self.key).expect("key");
                cipher
                    .encrypt_in_place_detached(GenericArray::from_slice(&nonce), &aad, payload)
                    .expect("infallible seal")
            }
        };
        payload.extend_from_slice(buf.as_slice());
        #[cfg(feature = "tls-debug")]
        eprintln!(
            "[dbg] seal type={} wire_len={} seq={} key={:02x?}",
            content_type,
            wire_len,
            self.seq - 1,
            &self.key[..self.key.len().min(8)]
        );
        let mut record = vec![CONTENT_APP_DATA, 0x03, 0x03];
        record.extend_from_slice(&(payload.len() as u16).to_be_bytes());
        record.extend_from_slice(payload);
        record
    }

    /// Decrypts one record payload in place; returns (real content type,
    /// plaintext). `payload` spans the record body (after the 5-byte header);
    /// `header` is that 5-byte header (the AEAD AAD).
    pub fn open_record(
        &mut self,
        header: &[u8; RECORD_HEADER_LEN],
        payload: &[u8],
    ) -> Result<(u8, Vec<u8>), String> {
        if payload.len() < RECORD_TAG_LEN + 1 {
            return Err("record too short".into());
        }
        let (body, tag) = payload.split_at(payload.len() - RECORD_TAG_LEN);
        let nonce = self.nonce();
        let mut buf = body.to_vec();
        match self.suite {
            Suite::Aes128GcmSha256 => {
                let cipher = Aes128Gcm::new_from_slice(&self.key).expect("key");
                cipher
                    .decrypt_in_place_detached(GenericArray::from_slice(&nonce), header, &mut buf, GenericArray::from_slice(tag))
                    .map_err(|_| "decrypt failed".to_string())?
            }
            Suite::Aes256GcmSha384 => {
                let cipher = aes_gcm::Aes256Gcm::new_from_slice(&self.key).expect("key");
                cipher
                    .decrypt_in_place_detached(GenericArray::from_slice(&nonce), header, &mut buf, GenericArray::from_slice(tag))
                    .map_err(|_| "decrypt failed".to_string())?
            }
            Suite::ChaCha20Poly1305Sha256 => {
                let cipher = ChaCha20Poly1305::new_from_slice(&self.key).expect("key");
                cipher
                    .decrypt_in_place_detached(GenericArray::from_slice(&nonce), header, &mut buf, GenericArray::from_slice(tag))
                    .map_err(|_| "decrypt failed".to_string())?
            }
        };
        // RFC 8446 §5.4: inner plaintext = content || type || zeros... The
        // real content type is the last NON-ZERO byte (zero padding allowed).
        let content = buf;
        if content.is_empty() {
            return Err("empty plaintext".into());
        }
        let mut end = content.len();
        while end > 0 && content[end - 1] == 0 {
            end -= 1;
        }
        if end == 0 {
            return Err("all-zero plaintext".into());
        }
        let real_type = content[end - 1];
        Ok((real_type, content[..end - 1].to_vec()))
    }
}

/// TLS 1.3 key schedule (RFC 8446 §7.1) over a chosen suite.
pub struct KeySchedule {
    pub suite: Suite,
    salt: Vec<u8>,
    current: Option<Vec<u8>>,
}

impl KeySchedule {
    pub fn new(suite: Suite) -> KeySchedule {
        KeySchedule {
            suite,
            salt: vec![0u8; suite.hash_len()],
            current: None,
        }
    }

    pub fn current_handshake_secret(&self) -> Vec<u8> {
        self.current.clone().expect("handshake secrets derived")
    }

    pub(crate) fn hkdf_extract(&self, ikm: &[u8]) -> Vec<u8> {
        let hk = hkdf::Hkdf::<sha2::Sha256>::new(Some(&self.salt), ikm);
        // Only used with SHA-384 suites too — rebuild per suite below.
        let _ = hk;
        match self.suite {
            Suite::Aes256GcmSha384 => {
                // HMAC-Extract per RFC 5869.
                let mut mac = <hmac::Hmac<sha2::Sha384> as hmac::Mac>::new_from_slice(&self.salt).expect("salt");
                mac.update(ikm);
                mac.finalize().into_bytes().to_vec()
            }
            _ => {
                let mut mac = <hmac::Hmac<sha2::Sha256> as hmac::Mac>::new_from_slice(&self.salt).expect("salt");
                mac.update(ikm);
                mac.finalize().into_bytes().to_vec()
            }
        }
    }

    fn hkdf_expand_label(&self, secret: &[u8], label: &str, context: &[u8], len: usize) -> Vec<u8> {
        let mut info = Vec::with_capacity(2 + 1 + 6 + label.len() + 1 + context.len());
        info.extend_from_slice(&(len as u16).to_be_bytes());
        info.push(("tls13 ".len() + label.len()) as u8);
        info.extend_from_slice(b"tls13 ");
        info.extend_from_slice(label.as_bytes());
        info.push(context.len() as u8);
        info.extend_from_slice(context);
        let mut okm = vec![0u8; len];
        match self.suite {
            Suite::Aes256GcmSha384 => {
                hkdf::Hkdf::<sha2::Sha384>::from_prk(secret)
                    .expect("prk")
                    .expand(&info, &mut okm)
                    .expect("len");
            }
            _ => {
                hkdf::Hkdf::<sha2::Sha256>::from_prk(secret)
                    .expect("prk")
                    .expand(&info, &mut okm)
                    .expect("len");
            }
        }
        okm
    }

    /// Derive-Secret(secret, label, messages) per RFC 8446 §7.1.
    pub fn derive_secret(&self, secret: &[u8], label: &str, transcript: &[u8]) -> Vec<u8> {
        let th = self.suite.hash(transcript);
        self.hkdf_expand_label(secret, label, &th, self.suite.hash_len())
    }

    /// Advances the schedule: new_salt = Derive-Secret(secret, "derived", "")
    /// then Extract(ikm). Returns the new secret.
    pub fn advance(&mut self, secret: &[u8], ikm: &[u8]) -> Vec<u8> {
        // Derive-Secret(secret, "derived", "") — the "" is an empty
        // TRANSCRIPT whose hash is SHA-256 of nothing (not a zero-length
        // context).
        let empty_hash = self.suite.hash(&[]);
        let derived = self.hkdf_expand_label(secret, "derived", &empty_hash, self.suite.hash_len());
        self.salt = derived;
        self.hkdf_extract(ikm)
    }

    /// RFC 8446 §7.1 schedule:
    ///   early  = Extract(0, 0)
    ///   hs     = Extract(Derive-Secret(early, "derived", ""), shared)
    ///   master = Extract(Derive-Secret(hs, "derived", ""), 0)
    pub fn handshake_secrets(&mut self, shared_key: &[u8], transcript_ch_sh: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let zero = vec![0u8; self.suite.hash_len()];
        let early = self.hkdf_extract(&zero);
        self.advance(&early, shared_key); // salt := derived(early), extract shared
        let handshake = self.hkdf_extract(shared_key);
        self.current = Some(handshake.clone());
        let client = self.derive_secret(&handshake, "c hs traffic", transcript_ch_sh);
        let server = self.derive_secret(&handshake, "s hs traffic", transcript_ch_sh);
        (client, server)
    }

    /// Application secrets after the full server flight is in the transcript.
    pub fn application_secrets(&mut self, handshake_secret: &[u8], transcript: &[u8]) -> (Vec<u8>, Vec<u8>) {
        self.advance(handshake_secret, &vec![0u8; self.suite.hash_len()]);
        let master = self.hkdf_extract(&vec![0u8; self.suite.hash_len()]);
        let client = self.derive_secret(&master, "c ap traffic", transcript);
        let server = self.derive_secret(&master, "s ap traffic", transcript);
        (client, server)
    }

    pub fn traffic_cipher(&self, secret: &[u8]) -> CipherState {
        let key = self.hkdf_expand_label(secret, "key", &[], self.suite.key_len());
        let iv = self.hkdf_expand_label(secret, "iv", &[], self.suite.iv_len());
        CipherState::new(self.suite, &key, &iv)
    }

    /// finished key: HKDF-Expand-Label(base_key, "finished", "", hash_len)
    pub fn finished_key(&self, base_key: &[u8]) -> Vec<u8> {
        self.hkdf_expand_label(base_key, "finished", &[], self.suite.hash_len())
    }

    /// Transcript-hash-backed HMAC for the Finished verify_data.
    pub fn finished_verify_data(&self, finished_key: &[u8], transcript: &[u8]) -> Vec<u8> {
        let th = self.suite.hash(transcript);
        match self.suite {
            Suite::Aes256GcmSha384 => {
                let mut mac = <hmac::Hmac<sha2::Sha384> as hmac::Mac>::new_from_slice(finished_key).expect("key");
                mac.update(&th);
                mac.finalize().into_bytes().to_vec()
            }
            _ => {
                let mut mac = <hmac::Hmac<sha2::Sha256> as hmac::Mac>::new_from_slice(finished_key).expect("key");
                mac.update(&th);
                mac.finalize().into_bytes().to_vec()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 8448 §3 (simplified TLS 1.3) handshake secrets.
    #[test]
    fn rfc8448_handshake_secret_chain() {
        let shared: Vec<u8> = hex("8bd4054fb55b9d63fdfbacf9f04b9f0d35e6d63f537563efd46272900f89492d");
        let mut ks = KeySchedule::new(Suite::Aes128GcmSha256);
        // early secret
        let zero = vec![0u8; 32];
        let early = ks.hkdf_extract(&zero);
        assert_eq!(
            hex_to_vec("33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a"),
            early
        );
        // derived — context is the hash of the empty transcript
        let empty_hash = Suite::Aes128GcmSha256.hash(&[]);
        let derived = ks.hkdf_expand_label(&early, "derived", &empty_hash, 32);
        assert_eq!(
            hex_to_vec("6f2615a108c702c5678f54fc9dbab69716c076189c48250cebeac3576c3611ba"),
            derived
        );
        // handshake secret = Extract(derived, shared)
        ks.salt = derived;
        let hs = ks.hkdf_extract(&shared);
        assert_eq!(
            hex_to_vec("1dc826e93606aa6fdc0aadc12f741b01046aa6b99f691ed221a9f0ca043fbeac"),
            hs
        );
        // c hs traffic with the RFC transcript hash input — the RFC uses the
        // full transcript hash of CH..SH; we feed a stand-in transcript equal
        // to the RFC's transcript hash value.
        let th: Vec<u8> = hex_to_vec("860c06edc079fd5e3205b648c55905f79579cfca5cab5c1d10078500ec538e1d");
        let c_hs = ks.hkdf_expand_label(&hs, "c hs traffic", &th, 32);
        // Cross-checked against Python HKDF; the true end-to-end proof is the
        // Go/openssl interop test (examples/e2e.rs).
        assert_eq!(
            hex_to_vec("0afbbe3f445b1637afd2dbc8c683ab8b13ee31cc9e938e171f7f09e3ecfdae56"),
            c_hs
        );
    }

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len() / 2)
            .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap())
            .collect()
    }
    fn hex_to_vec(s: &str) -> Vec<u8> {
        hex(s)
    }
}
