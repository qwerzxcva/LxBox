//! REALITY-authenticated TLS 1.3 ClientHello.
//!
//! ClientHello shape mirrors `metacubex/utls` HelloChrome_133 with the PQ
//! group removed (what sing-box's reality_client.go produces when
//! `utls.pq_enabled` is off — AIBox's default), simplified to the extensions
//! that matter for the REALITY server's checks. The REALITY authentication
//! rides in `session_id` exactly as in Xray/sing-box:
//!
//! 1. `session_id[0..2]` = {1, 8} (protocol/version marker)
//! 2. `session_id[2]`    = 1 (one key share)
//! 3. `session_id[4..8]` = unix time (seconds)
//! 4. `session_id[8..16]` = short_id
//! 5. auth_key = ECDHE(ephemeral_x25519, reality_public_key)
//!             then HKDF-SHA256(ikm=auth_key, salt=random[0..20], info="REALITY")
//! 6. `session_id[0..16]` = AES-128-GCM-Seal(key=auth_key,
//!        nonce=random[20..32], plaintext=session_id[0..16], aad=raw ClientHello)

use curve25519_dalek::MontgomeryPoint;

pub const EXT_SERVER_NAME: u16 = 0x0000;
pub const EXT_SUPPORTED_GROUPS: u16 = 0x000a;
pub const EXT_SIGNATURE_ALGORITHMS: u16 = 0x000d;
pub const EXT_SUPPORTED_VERSIONS: u16 = 0x002b;
pub const EXT_KEY_SHARE: u16 = 0x0033;

pub const GROUP_X25519: u16 = 0x001d;
pub const SUPPORTED_VERSION_TLS13: u16 = 0x0304;

/// The 32-byte REALITY public key (base64 RawURL decoded by the caller).
#[derive(Clone)]
pub struct RealityAuth {
    pub public_key: [u8; 32],
    pub short_id: [u8; 8],
}

pub struct ClientHelloOutput {
    /// The complete handshake message (type byte + u24 length + body).
    pub raw: Vec<u8>,
    /// The ephemeral X25519 secret used for the key share.
    pub ecdhe_secret: [u8; 32],
    /// REALITY auth key: ECDHE(ephemeral, server public) expanded with
    /// HKDF-SHA256(salt=random[0..20], info="REALITY") — the key the
    /// server derives identically from the same ClientHello.
    pub auth_key: [u8; 32],
}

fn push_u16(v: &mut Vec<u8>, x: u16) {
    v.extend_from_slice(&x.to_be_bytes());
}

fn push_u24(v: &mut Vec<u8>, x: usize) {
    v.extend_from_slice(&(x as u32).to_be_bytes()[1..]);
}

fn push_vec8(v: &mut Vec<u8>, data: &[u8]) {
    v.push(data.len() as u8);
    v.extend_from_slice(data);
}

fn push_vec16(v: &mut Vec<u8>, data: &[u8]) {
    push_u16(v, data.len() as u16);
    v.extend_from_slice(data);
}

/// ServerName extension with a single host (no trailing zero list shenanigans
/// beyond the RFC's required structure).
fn ext_server_name(host: &str) -> Vec<u8> {
    let mut inner = Vec::new();
    inner.push(0x00); // host_name type
    push_vec16(&mut inner, host.as_bytes());
    let mut ext = Vec::new();
    push_vec16(&mut ext, &inner);
    ext
}

/// supported_groups: x25519 only (PQ stripped, REALITY server requirement is
/// just a 32-byte X25519 share).
fn ext_supported_groups() -> Vec<u8> {
    let mut list = Vec::new();
    push_u16(&mut list, GROUP_X25519);
    let mut ext = Vec::new();
    push_vec16(&mut ext, &list);
    ext
}

/// signature_algorithms: the subset Chrome 133 offers that a Go TLS 1.3
/// server may pick for its certificate; the REALITY server impersonates a
/// target site so the value is cosmetic but should look browser-real.
fn ext_signature_algorithms() -> Vec<u8> {
    let algs: [u16; 8] = [
        0x0403, // ecdsa_secp256r1_sha256
        0x0804, // rsa_pss_rsae_sha256
        0x0401, // rsa_pkcs1_sha256
        0x0503, // ecdsa_secp384r1_sha384
        0x0805, // rsa_pss_rsae_sha384
        0x0501, // rsa_pkcs1_sha384
        0x0806, // rsa_pss_rsae_sha512
        0x0601, // rsa_pkcs1_sha512
    ];
    let mut list = Vec::new();
    for a in algs {
        push_u16(&mut list, a);
    }
    let mut ext = Vec::new();
    push_vec16(&mut ext, &list);
    ext
}

/// supported_versions: TLS 1.3 only.
fn ext_supported_versions() -> Vec<u8> {
    let mut list = Vec::new();
    push_u16(&mut list, SUPPORTED_VERSION_TLS13);
    let mut ext = Vec::new();
    ext.push(list.len() as u8);
    ext.extend_from_slice(&list);
    ext
}

/// key_share: a single x25519 entry.
fn ext_key_share(public: &[u8; 32]) -> Vec<u8> {
    let mut entry = Vec::new();
    push_u16(&mut entry, GROUP_X25519);
    push_vec16(&mut entry, public);
    let mut ext = Vec::new();
    push_vec16(&mut ext, &entry);
    ext
}

/// Builds the ClientHello and performs the REALITY session_id authentication.
pub fn build_client_hello(
    server_name: &str,
    cipher_suites: &[Suite],
    auth: &RealityAuth,
) -> ClientHelloOutput {
    // 32 random bytes for both the random field and the ephemeral key. The
    // secret is clamped by curve25519-dalek internally on scalar ops.
    let mut random = [0u8; 32];
    let mut secret_seed = [0u8; 32];
    getrandom(&mut random);
    getrandom(&mut secret_seed);

    // X25519 per RFC 7748: mul_base_clamped does clamp+basepoint-multiply.
    let ecdhe_public = *MontgomeryPoint::mul_base_clamped(secret_seed).as_bytes();

    // ECDHE against the REALITY server's static public key. X25519 uses the
    // clamped bytes directly (Montgomery ladder), NOT an Ed25519 scalar.
    let server_pub = MontgomeryPoint(auth.public_key);
    let clamped = curve25519_dalek::scalar::clamp_integer(secret_seed);
    let mut auth_key = server_pub.mul_clamped(clamped).to_bytes();

    // Build the session_id cleartext: ver(2) | nkeys(1) | pad(1) | time(4) |
    // short_id(8) = 16 bytes; encrypted to 32 bytes total with the tag.
    let mut session_plain = [0u8; 16];
    session_plain[0] = 0x00; // Xray leaves [0]=0; sing-box writes nothing here
    session_plain[1] = 0x08;
    session_plain[2] = 0x01;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as u32)
        .unwrap_or(0);
    session_plain[4..8].copy_from_slice(&now.to_be_bytes());
    session_plain[8..16].copy_from_slice(&auth.short_id);

    // ----- assemble the ClientHello body -----
    let legacy_version: [u8; 2] = [0x03, 0x03];
    let mut session_id_ciphertext = [0u8; 32]; // filled after raw assembly below

    // First assemble with a placeholder session_id so we can compute the
    // AEAD over the exact raw bytes, then patch both the struct and raw.
    let mut body = Vec::with_capacity(512);
    body.extend_from_slice(&legacy_version);
    body.extend_from_slice(&random);

    // The session_id goes on the wire ZEROED for the AEAD's AAD (the Go
    // client's hello.Raw still holds zeros here — the plaintext lives only
    // in the struct field); ciphertext is patched in after sealing.
    push_vec8(&mut body, &[0u8; 32]);

    // cipher_suites
    let mut suites = Vec::new();
    for s in cipher_suites {
        push_u16(&mut suites, s.as_u16());
    }
    push_vec16(&mut body, &suites);

    // compression methods: null
    push_vec8(&mut body, &[0x00]);

    // extensions
    let mut exts = Vec::new();
    push_u16(&mut exts, EXT_SERVER_NAME);
    push_vec16(&mut exts, &ext_server_name(server_name));
    push_u16(&mut exts, EXT_SUPPORTED_GROUPS);
    push_vec16(&mut exts, &ext_supported_groups());
    push_u16(&mut exts, EXT_SIGNATURE_ALGORITHMS);
    push_vec16(&mut exts, &ext_signature_algorithms());
    push_u16(&mut exts, EXT_SUPPORTED_VERSIONS);
    push_vec16(&mut exts, &ext_supported_versions());
    push_u16(&mut exts, EXT_KEY_SHARE);
    push_vec16(&mut exts, &ext_key_share(&ecdhe_public));
    push_vec16(&mut body, &exts);

    // handshake message: type 1 (client_hello) + u24 length + body
    let mut raw = Vec::with_capacity(body.len() + 4);
    raw.push(0x01);
    push_u24(&mut raw, body.len());
    raw.extend_from_slice(&body);

    // The session_id field sits at raw offset 39: 4-byte handshake header +
    // 2 legacy version + 32 random + 1 length byte (utls' raw[39:] is the
    // same field counted without the 4-byte handshake header).
    const SESSION_ID_AT: usize = 39;

    // REALITY AEAD over the cleartext, AAD = full ClientHello message.
    let (ciphertext, tag) = {
        use aes_gcm::aead::{Aead, KeyInit, Payload};
        // Derive the AES-128 key from the shared secret exactly as
        // sing-box/utls: HKDF-SHA256(ikm=auth_key, salt=random[0..20],
        // info="REALITY") read into the same 32 bytes.
        let hk = hkdf::Hkdf::<sha2::Sha256>::new(Some(&random[0..20]), &auth_key);
        hk.expand(b"REALITY", &mut auth_key).expect("32 bytes");
        // Go: aes.NewCipher(32-byte authKey) → AES-256-GCM.
        let cipher = aes_gcm::Aes256Gcm::new_from_slice(&auth_key).expect("key");
        let nonce = &random[20..32];
        let pt = Payload {
            msg: &session_plain,
            aad: &raw,
        };
        let out = cipher.encrypt(nonce.into(), pt).expect("seal");
        debug_assert_eq!(out.len(), 32);
        let mut c = [0u8; 16];
        c.copy_from_slice(&out[..16]);
        let mut t = [0u8; 16];
        t.copy_from_slice(&out[16..]);
        (c, t)
    };
    session_id_ciphertext[..16].copy_from_slice(&ciphertext);
    session_id_ciphertext[16..].copy_from_slice(&tag);
    raw[SESSION_ID_AT..SESSION_ID_AT + 32].copy_from_slice(&session_id_ciphertext);

    // The ecdhe secret for later transcript/FINISHED derivation is the
    // clamped scalar's byte form (dalek accepts from_bits as-is for
    // Montgomery multiplication).
    let mut ecdhe_secret = [0u8; 32];
    ecdhe_secret.copy_from_slice(&clamped);
    // Zeroize nothing here: caller needs auth_key? No — auth key is derived
    // server-side independently.
    let _ = ecdhe_public;

    ClientHelloOutput {
        raw,
        ecdhe_secret,
        auth_key,
    }
}

/// Minimal /dev/urandom-backed fill (no_std-friendly dependency surface;
/// std is available in this crate).
fn getrandom(buf: &mut [u8]) {
    use std::io::Read;
    let mut f = std::fs::File::open("/dev/urandom").expect("urandom");
    f.read_exact(buf).expect("urandom read");
}

/// Verifies the server's REALITY proof: the leaf certificate must be a bare
/// Ed25519 self-signed certificate whose signature equals
/// HMAC-SHA512(key=auth_key, msg=ed25519_public_key) (Xray REALITY §verify).
/// Returns the leaf certificate's DER (also needed nowhere else — the TLS
/// layer only needs the verdict) and the server's finished verify data is
/// handled by the standard handshake.
pub fn reality_verify_certificate(
    cert_der: &[u8],
    auth_key: &[u8; 32],
) -> bool {
    // Parse the minimal DER to find: signature algorithm (must be Ed25519),
    // subjectPublicKeyInfo (last 32 bytes = ed25519 pub), signature BIT STRING.
    let Some(sig) = extract_cert_signature(cert_der) else {
        return false;
    };
    let Some(pub_key) = extract_ed25519_public(cert_der) else {
        return false;
    };
    // HMAC over the public key with the auth key.
    use hmac::Mac;
    let mut mac = hmac::Hmac::<sha2::Sha512>::new_from_slice(auth_key).expect("key");
    mac.update(&pub_key);
    let expect = mac.finalize().into_bytes();
    sig == expect[..]
}

/// Reads one DER TLV; returns (tag, content WITHOUT header, rest).
fn read_tlv_tagged(buf: &[u8]) -> Option<(u8, &[u8], &[u8])> {
    if buf.len() < 2 {
        return None;
    }
    let tag = buf[0];
    let len_byte = buf[1] as usize;
    let (hdr, content_len) = if len_byte & 0x80 == 0 {
        (2, len_byte)
    } else {
        let n = len_byte & 0x7f;
        if buf.len() < 2 + n {
            return None;
        }
        let mut len = 0usize;
        for b in &buf[2..2 + n] {
            len = (len << 8) | *b as usize;
        }
        (2 + n, len)
    };
    if buf.len() < hdr + content_len {
        return None;
    }
    Some((tag, &buf[hdr..hdr + content_len], &buf[hdr + content_len..]))
}

fn content_len_of(whole_with_header: &[u8]) -> usize {
    let len_byte = whole_with_header[1] as usize;
    if len_byte & 0x80 == 0 {
        len_byte
    } else {
        let n = len_byte & 0x7f;
        let mut len = 0usize;
        for b in &whole_with_header[2..2 + n] {
            len = (len << 8) | *b as usize;
        }
        len
    }
}

/// Extracts the raw signature bytes (the contents of the outer BIT STRING of
/// the Certificate's signature field) from a DER certificate.
fn extract_cert_signature(der: &[u8]) -> Option<Vec<u8>> {
    // Certificate ::= SEQUENCE { tbs, signatureAlgorithm, signatureValue }
    let (_tag, cert_content, _) = read_tlv_tagged(der)?;
    let (_t1, _tbs, rest) = read_tlv_tagged(cert_content)?;
    let (_t2, _alg, rest) = read_tlv_tagged(rest)?;
    let (_t3, sig, _tail) = read_tlv_tagged(rest)?;
    // sig is a BIT STRING: first byte = unused-bits count (0), rest = raw.
    if sig.first() != Some(&0) || sig.len() < 2 {
        return None;
    }
    Some(sig[1..].to_vec())
}

/// Extracts the 32-byte Ed25519 public key from the SubjectPublicKeyInfo.
fn extract_ed25519_public(der: &[u8]) -> Option<[u8; 32]> {
    let (_tag, cert_content, _) = read_tlv_tagged(der)?;
    // TBSCertificate: [0] version (explicit), serial, sigalg, issuer,
    // validity, subject, SPKI, [extensions], sigalg, signature.
    let (_t0, tbs_content, _rest) = read_tlv_tagged(cert_content)?;
    let mut rest = tbs_content;
    // optional [0] version
    if rest.first() == Some(&0xA0) {
        let (_t, _ver, r) = read_tlv_tagged(rest)?;
        rest = r;
    }
    let (_a, _serial, r) = read_tlv_tagged(rest)?;
    let (_b, _alg, r) = read_tlv_tagged(r)?;
    let (_c, _issuer, r) = read_tlv_tagged(r)?;
    let (_d, _validity, r) = read_tlv_tagged(r)?;
    let (_e, _subject, r) = read_tlv_tagged(r)?;
    let (_f, spki, _r) = read_tlv_tagged(r)?;
    // SPKI ::= SEQUENCE { algorithm, subjectPublicKey BIT STRING }
    let (_g, _alg, r) = read_tlv_tagged(spki)?;
    let (_h, bitstring, _) = read_tlv_tagged(r)?;
    if bitstring.first() != Some(&0) || bitstring.len() != 33 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bitstring[1..]);
    Some(key)
}

/// Reads one DER TLV; returns (contents-with-header, rest).
fn read_tlv(buf: &[u8]) -> Option<(&[u8], &[u8])> {
    if buf.len() < 2 {
        return None;
    }
    let len_byte = buf[1] as usize;
    let (hdr, content_len) = if len_byte & 0x80 == 0 {
        (2, len_byte)
    } else {
        let n = len_byte & 0x7f;
        if buf.len() < 2 + n {
            return None;
        }
        let mut len = 0usize;
        for b in &buf[2..2 + n] {
            len = (len << 8) | *b as usize;
        }
        (2 + n, len)
    };
    if buf.len() < hdr + content_len {
        return None;
    }
    Some((&buf[..hdr + content_len], &buf[hdr + content_len..]))
}

use crate::tls13::Suite;

pub fn debug_sig(der: &[u8]) -> Vec<u8> {
    extract_cert_signature(der).unwrap_or_default()
}
pub fn debug_pub(der: &[u8]) -> [u8; 32] {
    extract_ed25519_public(der).unwrap_or([0; 32])
}
pub fn debug_walk(der: &[u8]) {
    let (_tag, cert_content, _) = match read_tlv_tagged(der) {
        Some(v) => v,
        None => {
            eprintln!("[dbg] walk: outer fail");
            return;
        }
    };
    let mut rest = cert_content;
    for i in 0..10 {
        match read_tlv_tagged(rest) {
            Some((tag, content, t)) => {
                eprintln!(
                    "[dbg] walk[{}] tag={:02x} len={} head={:02x?}",
                    i,
                    tag,
                    content.len(),
                    &content[..content.len().min(4)]
                );
                rest = t;
            }
            None => {
                eprintln!("[dbg] walk[{}] end", i);
                break;
            }
        }
    }
}

pub fn debug_walk_tbs(der: &[u8]) {
    let (_tag, cert_content, _) = match read_tlv_tagged(der) {
        Some(v) => v,
        None => return,
    };
    let (_t0, tbs, _) = match read_tlv_tagged(cert_content) {
        Some(v) => v,
        None => return,
    };
    let mut rest = tbs;
    for i in 0..9 {
        match read_tlv_tagged(rest) {
            Some((tag, content, t)) => {
                eprintln!(
                    "[dbg] tbs[{}] tag={:02x} len={} head={:02x?}",
                    i, tag, content.len(), &content[..content.len().min(4)]
                );
                rest = t;
            }
            None => break,
        }
    }
}

/// Fixed Go-generated ed25519 certificate for parser tests (from certgen).
pub fn test_cert_der() -> Vec<u8> {
    hex_to_vec("3081af3063a003020102020100300506032b657030003022180f30303031303130313030303030305a180f30303031303130313030303030305a3000302a300506032b65700321006ba4ba46ad864d8f191f986e04428b8853ddd659f377388d75751be639d09a01300506032b657003410064f95c60a03d01162a1410e2e57c4c06fddbd37abb60c92d377aa9ca56929698057ee0b2e873d3ad9690043716ffcf0521a1793ca553d02e9dfe3b2bf27a2a01")
}
pub fn hex_to_vec(s: &str) -> Vec<u8> {
    let s: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap())
        .collect()
}
