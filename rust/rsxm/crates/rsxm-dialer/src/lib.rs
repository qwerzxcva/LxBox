//! rsxm-dialer: the micro-kernel that dials proxies.
//!
//! Stage 4a carries VLESS over TCP with REALITY (TLS 1.3) transport and no
//! flow (the plain fast path; vision comes later as its own layer). The
//! implementation is from-scratch Rust: a minimal TLS 1.3 client whose
//! ClientHello carries the REALITY authentication, plus the VLESS request
//! frame format verified against `sing-vmess`'s Go sources.

pub mod hello;
pub mod httpupgrade;
pub mod module;
pub mod shadowsocks;
pub mod tls13;
pub mod vless;
pub mod ws;

pub use module::{DialerModule, OutboundInfo};

use std::io::{Read, Write};
use std::net::TcpStream;

pub use hello::RealityAuth;
pub use shadowsocks::{SsSession, SsTarget};
pub use vless::{VlessCommand, VlessDestination, VlessRequest};

#[derive(Debug)]
pub enum DialError {
    Io(std::io::Error),
    Tls(String),
    Reality(String),
    Vless(String),
}

impl std::fmt::Display for DialError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DialError::Io(e) => write!(f, "io: {e}"),
            DialError::Tls(m) => write!(f, "tls: {m}"),
            DialError::Reality(m) => write!(f, "reality: {m}"),
            DialError::Vless(m) => write!(f, "vless: {m}"),
        }
    }
}

impl From<std::io::Error> for DialError {
    fn from(e: std::io::Error) -> Self {
        DialError::Io(e)
    }
}

/// A dialled, ready-to-relay VLESS connection: TLS 1.3 records around the
/// VLESS stream.
pub struct VlessConnection {
    stream: TcpStream,
    tls_read: tls13::CipherState,
    tls_write: tls13::CipherState,
    /// Leftover decrypted bytes from the handshake read.
    pending: Vec<u8>,
    /// WebSocket transport wrapping (optional): when the node is ws-based,
    /// every VLESS record rides inside a masked binary WS frame.
    ws: Option<ws::WsTransport>,
    /// httpupgrade transport (optional): raw byte passthrough after the
    /// 101 — no framing. The struct carries the post-upgrade prefix bytes
    /// that arrived with the 101.
    httpupgrade: Option<httpupgrade::HttpUpgradeTransport>,
}

impl VlessConnection {
    /// True when the connection rides a raw-byte transport (httpupgrade):
    /// no per-record framing beyond TLS itself.
    pub fn is_raw_transport(&self) -> bool {
        self.httpupgrade.is_some()
    }

    /// Sends plaintext (the TLS layer frames and encrypts it).
    pub fn send(&mut self, data: &[u8]) -> std::io::Result<()> {
        // WebSocket transport wraps each chunk in a masked binary frame;
        // the frame itself then rides the TLS record layer as usual.
        let mut wire: Vec<u8> = Vec::with_capacity(data.len() + 14);
        for chunk in data.chunks(tls13::MAX_RECORD_PAYLOAD) {
            let mut framed = Vec::new();
            if self.ws.is_some() {
                ws::encode_frame(chunk, &mut framed);
            } else {
                framed.extend_from_slice(chunk);
            }
            wire.extend_from_slice(&framed);
        }
        for chunk in wire.chunks(tls13::MAX_RECORD_PAYLOAD) {
            let mut piece = chunk.to_vec();
            let record = self
                .tls_write
                .seal_record(tls13::CONTENT_APP_DATA, &mut piece);
            self.stream.write_all(&record)?;
        }
        self.stream.flush()
    }

    /// Reads and decrypts whatever TLS application data is available into
    /// `out`; returns false when the stream is closed or a protocol error
    /// occurred.
    pub fn recv(&mut self, out: &mut Vec<u8>) -> bool {
        let mut buf = [0u8; tls13::MAX_RECORD_PAYLOAD + tls13::RECORD_HEADER_LEN + 64];
        match self.stream.read(&mut buf) {
            Ok(0) => false,
            Ok(n) => {
                let mut data = &buf[..n];
                while data.len() >= tls13::RECORD_HEADER_LEN {
                    let len = u16::from_be_bytes([data[3], data[4]]) as usize;
                    let total = tls13::RECORD_HEADER_LEN + len;
                    if data.len() < total {
                        // Partial record: stash and wait for more reads.
                        self.pending.extend_from_slice(data);
                        break;
                    }
                    let t = data[0];
                    let mut header = [0u8; tls13::RECORD_HEADER_LEN];
                    header.copy_from_slice(&data[..tls13::RECORD_HEADER_LEN]);
                    if t == tls13::CONTENT_APP_DATA {
                        match self
                            .tls_read
                            .open_record(&header, &data[tls13::RECORD_HEADER_LEN..total])
                        {
                            Ok((_rt, plain)) => {
                                if self.ws.is_some() {
                                    // WS transport: unpack frames from the
                                    // decrypted stream (one record may carry
                                    // a partial frame; the pending buffer in
                                    // the caller keeps the rest).
                                    let mut rest = plain.as_slice();
                                    while let Some((consumed, payload)) = ws::decode_frame(rest) {
                                        out.extend_from_slice(&payload);
                                        rest = &rest[consumed..];
                                    }
                                } else {
                                    out.extend_from_slice(&plain);
                                }
                            }
                            Err(_) => return false,
                        }
                    }
                    data = &data[total..];
                }
                true
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => true,
            Err(_) => false,
        }
    }
}

/// Server parameters for a VLESS+REALITY dial.
#[derive(Clone)]
pub struct VlessRealityTarget {
    pub server: String,
    pub server_port: u16,
    pub server_name: String,
    pub public_key: [u8; 32],
    pub short_id: [u8; 8],
    pub uuid: [u8; 16],
}

impl VlessRealityTarget {
    /// Decodes `public_key` (base64 RawURL) and `short_id` (hex) like the
    /// kernel's config parser.
    pub fn from_parts(
        server: String,
        server_port: u16,
        server_name: String,
        public_key_b64: &str,
        short_id_hex: &str,
        uuid_str: &str,
    ) -> Result<VlessRealityTarget, DialError> {
        let public_key = base64_rawurl_decode_32(public_key_b64)
            .map_err(|e| DialError::Reality(format!("public_key: {e}")))?;
        let short_id = short_id_hex_decode(short_id_hex)
            .map_err(|e| DialError::Reality(format!("short_id: {e}")))?;
        let uuid = parse_uuid(uuid_str).ok_or_else(|| DialError::Vless("invalid uuid".into()))?;
        Ok(VlessRealityTarget {
            server,
            server_port,
            server_name,
            public_key,
            short_id,
            uuid,
        })
    }
}

/// Performs the TCP + REALITY(TLS1.3) + VLESS handshake and sends the
/// request header. On success the connection carries the target's stream.
pub fn dial(
    target: &VlessRealityTarget,
    request: &VlessRequest,
    ws_transport: Option<ws::WsTransport>,
    httpupgrade_transport: Option<httpupgrade::HttpUpgradeTransport>,
) -> Result<VlessConnection, DialError> {
    let mut stream = TcpStream::connect((target.server.as_str(), target.server_port))?;
    stream.set_nodelay(true).ok();

    // WebSocket transport (optional): the HTTP upgrade precedes any TLS —
    // the CDNs that front ws nodes expect the upgrade handshake first.
    if let Some(ref transport) = ws_transport {
        stream = ws::upgrade(stream, &transport.host, &transport.path).map_err(DialError::Io)?;
    }
    // httpupgrade transport (optional): same shape as ws but raw bytes
    // after the 101.
    let httpupgrade_transport = match httpupgrade_transport {
        Some(mut t) => {
            let (upgraded, leftover) =
                httpupgrade::upgrade(stream, &t.host, &t.path).map_err(DialError::Io)?;
            stream = upgraded;
            t.pending = leftover;
            Some(t)
        }
        None => None,
    };

    // ---- ClientHello with REALITY auth ----
    let suites = [
        tls13::Suite::Aes128GcmSha256,
        tls13::Suite::Aes256GcmSha384,
        tls13::Suite::ChaCha20Poly1305Sha256,
    ];
    let auth = hello::RealityAuth {
        public_key: target.public_key,
        short_id: target.short_id,
    };
    let ch = hello::build_client_hello(&target.server_name, &suites, &auth);
    let mut ch_record = vec![tls13::CONTENT_HANDSHAKE, 0x03, 0x01];
    ch_record.extend_from_slice(&(ch.raw.len() as u16).to_be_bytes());
    ch_record.extend_from_slice(&ch.raw);
    stream.write_all(&ch_record)?;
    stream.flush()?;

    // ---- transcript: CH ----
    let mut transcript = Vec::new();
    transcript.extend_from_slice(&ch.raw);

    // ---- ServerHello (cleartext) ----
    let (sh_body, sh_full) = read_handshake_message_clear(&mut stream)?;
    let parsed = parse_server_hello(&sh_body)?;
    let suite = parsed.suite;
    transcript.extend_from_slice(&sh_full);

    // ---- key schedule: shared -> handshake traffic secrets ----
    let clamped = curve25519_dalek::scalar::clamp_integer(ch.ecdhe_secret);
    let server_point = curve25519_dalek::MontgomeryPoint(parsed.server_share);
    let shared = server_point.mul_clamped(clamped).to_bytes();

    let mut ks = tls13::KeySchedule::new(suite);
    let ch_sh_transcript = transcript.clone();
    let (c_hs_secret, s_hs_secret) = ks.handshake_secrets(&shared, &ch_sh_transcript);
    let mut tls_read = ks.traffic_cipher(&s_hs_secret);
    // Client handshake traffic keys: the server flight is only read, but the
    // client Finished must be sealed with the HANDSHAKE write key (the key
    // change to application secrets happens only after it, per RFC 8446).
    let mut hs_write = ks.traffic_cipher(&c_hs_secret);
    #[cfg(feature = "tls-debug")]
    {
        eprintln!("[dbg] suite={suite:?} shared={:02x?}", &shared[..8]);
        eprintln!("[dbg] s_hs={:02x?}", &s_hs_secret[..8]);
        eprintln!("[dbg] transcript_len={}", transcript.len());
    }

    // ---- encrypted flight: optional CCS shim, then EE/Cert/CV/Finished ----
    let mut record = read_record(&mut stream)?;
    if record.0[0] == tls13::CONTENT_CHANGE_CIPHER_SPEC {
        record = read_record(&mut stream)?;
    }

    #[cfg(feature = "tls-debug")]
    eprintln!(
        "[dbg] ch_raw_len={} sh_full_len={} first record type={} len={}",
        ch.raw.len(),
        sh_full.len(),
        record.0[0],
        record.1.len()
    );
    let mut got_ee = false;
    let mut got_cert = false;
    let mut got_cv = false;
    let mut got_finished = false;
    let mut reality_verified = false;

    loop {
        if record.0[0] != tls13::CONTENT_APP_DATA {
            return Err(DialError::Tls(format!(
                "unexpected record type {}",
                record.0[0]
            )));
        }
        let (real_type, plaintext) = tls_read.open_record(&record.0, &record.1).map_err(|e| {
            #[cfg(feature = "tls-debug")]
            eprintln!("[dbg] open failed at seq: {e}");
            DialError::Tls(e)
        })?;
        if real_type != tls13::CONTENT_HANDSHAKE {
            return Err(DialError::Tls(format!(
                "expected handshake, got {real_type}"
            )));
        }
        let mut cursor = plaintext.as_slice();
        while !cursor.is_empty() {
            if cursor.len() < 4 {
                return Err(DialError::Tls("short handshake header".into()));
            }
            let msg_type = cursor[0];
            let len = u32::from_be_bytes([0, cursor[1], cursor[2], cursor[3]]) as usize;
            if cursor.len() < 4 + len {
                return Err(DialError::Tls("truncated handshake message".into()));
            }
            let body = &cursor[4..4 + len];
            match msg_type {
                0x08 => {
                    got_ee = true;
                    transcript.extend_from_slice(&cursor[..4 + len]);
                }
                0x0B => {
                    got_cert = true;
                    // REALITY verify happens on the transcript BEFORE the
                    // Certificate message is added (the HMAC covers the
                    // ephemeral key material, not the TLS transcript) —
                    // order here only matters for the transcript itself.
                    let leaf = extract_leaf_certificate(body)
                        .ok_or_else(|| DialError::Reality("no leaf certificate".into()))?;
                    #[cfg(feature = "tls-debug")]
                    eprintln!(
                        "[dbg] leaf len={} head={:?}",
                        leaf.len(),
                        &leaf[..leaf.len().min(8)]
                    );
                    if !hello::reality_verify_certificate(&leaf, &ch.auth_key) {
                        #[cfg(feature = "tls-debug")]
                        {
                            eprintln!("[dbg] auth_key={:02x?}", ch.auth_key);
                            let sig = hello::debug_sig(&leaf);
                            let pk = hello::debug_pub(&leaf);
                            eprintln!("[dbg] leaf_sig={:02x?}", &sig[..sig.len().min(16)]);
                            eprintln!("[dbg] leaf_pub={:02x?}", pk);
                            hello::debug_walk(&leaf);
                        }
                        return Err(DialError::Reality("certificate HMAC mismatch".into()));
                    }
                    reality_verified = true;
                    transcript.extend_from_slice(&cursor[..4 + len]);
                }
                0x0F => {
                    got_cv = true;
                    transcript.extend_from_slice(&cursor[..4 + len]);
                }
                0x14 => {
                    got_finished = true;
                    let fk = ks.finished_key(&s_hs_secret);
                    let expect = ks.finished_verify_data(&fk, &transcript);
                    if expect.as_slice() != body {
                        return Err(DialError::Tls("server finished mismatch".into()));
                    }
                    transcript.extend_from_slice(&cursor[..4 + len]);
                }
                other => {
                    return Err(DialError::Tls(format!("unexpected handshake msg {other}")));
                }
            }
            cursor = &cursor[4 + len..];
        }
        if got_finished {
            break;
        }
        record = read_record(&mut stream)?;
    }
    if !(got_ee && got_cert && got_cv && got_finished) {
        return Err(DialError::Tls("incomplete server flight".into()));
    }
    if !reality_verified {
        return Err(DialError::Reality("server did not prove REALITY".into()));
    }

    // ---- client Finished (encrypted with the HANDSHAKE write key) ----
    let fk = ks.finished_key(&c_hs_secret);
    let verify = ks.finished_verify_data(&fk, &transcript);
    let mut finished_msg = vec![0x14];
    push_u24(&mut finished_msg, verify.len());
    finished_msg.extend_from_slice(&verify);
    let out = hs_write.seal_record(tls13::CONTENT_HANDSHAKE, &mut finished_msg);
    stream.write_all(&out)?;
    stream.flush()?;

    // ---- key change: application traffic keys (after client Finished) ----
    let hs_secret = ks.current_handshake_secret();
    let (c_app, s_app) = ks.application_secrets(&hs_secret, &transcript);
    let mut tls_write = ks.traffic_cipher(&c_app);
    let tls_read = ks.traffic_cipher(&s_app);

    // ---- VLESS request frame (first application data) ----
    let frame = vless::encode_request(request);
    let record = tls_write.seal_record(tls13::CONTENT_APP_DATA, &mut frame.clone());
    stream.write_all(&record)?;
    stream.flush()?;

    Ok(VlessConnection {
        stream,
        tls_read,
        tls_write,
        pending: Vec::new(),
        ws: ws_transport,
        httpupgrade: httpupgrade_transport,
    })
}

/// Concatenates the ClientHello and ServerHello transcript fragments.
#[allow(dead_code)]
fn transcript_ch_sh(ch_raw: &[u8], sh_raw: &[u8]) -> Vec<u8> {
    let mut t = Vec::with_capacity(ch_raw.len() + sh_raw.len());
    t.extend_from_slice(ch_raw);
    t.extend_from_slice(sh_raw);
    t
}

fn read_record(stream: &mut TcpStream) -> Result<([u8; 5], Vec<u8>), DialError> {
    let mut header = [0u8; 5];
    stream.read_exact(&mut header)?;
    let len = u16::from_be_bytes([header[3], header[4]]) as usize;
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body)?;
    Ok((header, body))
}

fn read_handshake_message_clear(stream: &mut TcpStream) -> Result<(Vec<u8>, Vec<u8>), DialError> {
    let mut buf = Vec::new();
    // May need several records for one handshake message.
    // Read first 4 bytes through record framing: simplest correct path is
    // record-wise accumulation.
    let (first_header, body) = read_record(stream)?;
    if first_header[0] != tls13::CONTENT_HANDSHAKE {
        return Err(DialError::Tls(format!(
            "expected handshake record, got {}",
            first_header[0]
        )));
    }
    buf.extend_from_slice(&body);
    if buf.len() < 4 {
        return Err(DialError::Tls("short handshake".into()));
    }
    let need = u32::from_be_bytes([0, buf[1], buf[2], buf[3]]) as usize + 4;
    while buf.len() < need {
        let (header, body) = read_record(stream)?;
        if header[0] != tls13::CONTENT_HANDSHAKE {
            return Err(DialError::Tls("interleaved record in handshake".into()));
        }
        buf.extend_from_slice(&body);
    }
    Ok((buf[4..need].to_vec(), buf[..need].to_vec()))
}

struct ParsedServerHello {
    suite: tls13::Suite,
    server_share: [u8; 32],
}

fn parse_server_hello(body: &[u8]) -> Result<ParsedServerHello, DialError> {
    // ServerHello body: version(2) random(32) session_len(1) session(...)
    // cipher_suite(2) compression(1) extensions_len(2) extensions...
    if body.len() < 39 {
        return Err(DialError::Tls("server hello too short".into()));
    }
    let mut pos = 2 + 32;
    let session_len = body[pos] as usize;
    pos += 1 + session_len;
    if body.len() < pos + 3 {
        return Err(DialError::Tls("server hello truncated at cipher".into()));
    }
    let suite = tls13::Suite::from_u16(u16::from_be_bytes([body[pos], body[pos + 1]]))
        .ok_or_else(|| DialError::Tls("unsupported suite".into()))?;
    pos += 2 + 1; // suite + compression null
    if body.len() < pos + 2 {
        return Err(DialError::Tls("server hello truncated at exts".into()));
    }
    let ext_len = u16::from_be_bytes([body[pos], body[pos + 1]]) as usize;
    pos += 2;
    let exts_end = (pos + ext_len).min(body.len());
    let mut server_share = None;
    let mut p = pos;
    while p + 4 <= exts_end {
        let typ = u16::from_be_bytes([body[p], body[p + 1]]);
        let len = u16::from_be_bytes([body[p + 2], body[p + 3]]) as usize;
        let start = p + 4;
        if start + len > exts_end {
            break;
        }
        if typ == hello::EXT_KEY_SHARE {
            let d = &body[start..start + len];
            if d.len() >= 36 {
                let group = u16::from_be_bytes([d[0], d[1]]);
                let klen = u16::from_be_bytes([d[2], d[3]]) as usize;
                if group == hello::GROUP_X25519 && klen == 32 && d.len() >= 4 + 32 {
                    let mut share = [0u8; 32];
                    share.copy_from_slice(&d[4..36]);
                    server_share = Some(share);
                }
            }
        }
        p = start + len;
    }
    let server_share = server_share.ok_or_else(|| DialError::Tls("no x25519 key share".into()))?;
    Ok(ParsedServerHello {
        suite,
        server_share,
    })
}

/// Certificate message body: cert_request_context_len(1) ctx
/// certificates_len(3) { len(3) der }...
fn extract_leaf_certificate(body: &[u8]) -> Option<Vec<u8>> {
    if body.is_empty() {
        return None;
    }
    let ctx_len = body[0] as usize;
    let mut pos = 1 + ctx_len;
    if body.len() < pos + 3 {
        return None;
    }
    let _total = u32::from_be_bytes([0, body[pos], body[pos + 1], body[pos + 2]]) as usize;
    pos += 3;
    if body.len() < pos + 3 {
        return None;
    }
    let der_len = u32::from_be_bytes([0, body[pos], body[pos + 1], body[pos + 2]]) as usize;
    pos += 3;
    if body.len() < pos + der_len {
        return None;
    }
    Some(body[pos..pos + der_len].to_vec())
}

fn push_u24(v: &mut Vec<u8>, x: usize) {
    v.extend_from_slice(&(x as u32).to_be_bytes()[1..]);
}

/// base64 RawURL (no padding) decode of exactly 32 bytes.
pub fn base64_rawurl_decode_32(s: &str) -> Result<[u8; 32], String> {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = [0u8; 32];
    let mut acc: u32 = 0;
    let mut bits = 0;
    let mut n = 0;
    for c in s.bytes() {
        let v = ALPHABET
            .iter()
            .position(|a| *a == c)
            .ok_or_else(|| format!("bad base64 char {c}"))? as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            if n >= 32 {
                return Err("too long".into());
            }
            out[n] = (acc >> bits) as u8;
            n += 1;
        }
    }
    if n != 32 {
        return Err(format!("expected 32 bytes, got {n}"));
    }
    Ok(out)
}

/// Hex decode into 8 bytes (pads implicitly with zeros when shorter).
pub fn short_id_hex_decode(s: &str) -> Result<[u8; 8], String> {
    let mut out = [0u8; 8];
    let s = s.trim();
    if !s.len().is_multiple_of(2) || s.len() > 16 {
        return Err("short_id must be 0-8 bytes of hex".into());
    }
    let bytes: Vec<u8> = (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .map_err(|e| e.to_string())?;
    out[..bytes.len()].copy_from_slice(&bytes);
    Ok(out)
}

/// Standard UUID string parse (hyphenated or bare hex).
pub fn parse_uuid(s: &str) -> Option<[u8; 16]> {
    let hex: String = s.chars().filter(|c| *c != '-').collect();
    if hex.len() != 32 {
        return None;
    }
    let mut out = [0u8; 16];
    for (i, b) in out.iter_mut().enumerate() {
        *b = u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}
