//! TLS record framing and handshake-message parsing for the dialer.

use super::{DialError, hello, tls13};
use std::io::Read;
use std::net::TcpStream;

pub(crate) fn read_record(stream: &mut TcpStream) -> Result<([u8; 5], Vec<u8>), DialError> {
    let mut header = [0u8; 5];
    stream.read_exact(&mut header)?;
    let len = u16::from_be_bytes([header[3], header[4]]) as usize;
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body)?;
    Ok((header, body))
}

pub(crate) fn read_handshake_message_clear(stream: &mut TcpStream) -> Result<(Vec<u8>, Vec<u8>), DialError> {
    let mut buf = Vec::new();
    // May need several records for one handshake message.
    // Read first 4 bytes through record framing: simplest correct path is
    // record-wise accumulation.
    let (first_header, body) = read_record(stream)?;
    if first_header[0] != tls13::CONTENT_HANDSHAKE {
        return Err(DialError::Tls(format!("expected handshake record, got {}", first_header[0])));
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

pub(crate) struct ParsedServerHello {
    pub(crate) suite: tls13::Suite,
    pub(crate) server_share: [u8; 32],
}

pub(crate) fn parse_server_hello(body: &[u8]) -> Result<ParsedServerHello, DialError> {
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
pub(crate) fn extract_leaf_certificate(body: &[u8]) -> Option<Vec<u8>> {
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

pub(crate) fn push_u24(v: &mut Vec<u8>, x: usize) {
    v.extend_from_slice(&(x as u32).to_be_bytes()[1..]);
}
