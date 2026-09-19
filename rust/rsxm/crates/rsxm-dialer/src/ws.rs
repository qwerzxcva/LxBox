//! WebSocket transport wrapper (RFC 6455, client role) for the VLESS
//! dialer: after the TCP connect and the HTTP upgrade, every VLESS record
//! rides inside a masked binary WS frame.

use std::io::{Read, Write};
use std::net::TcpStream;

/// Marks a connection as WebSocket-transported.
#[derive(Clone, Debug)]
pub struct WsTransport {
    /// The upgrade path (kept for ping/reconnect handling).
    pub path: String,
    /// The Host header used in the upgrade.
    pub host: String,
}

/// Upgrades the plain TCP stream to a WebSocket connection. Returns the
/// stream with the 101 response consumed; subsequent reads are WS frames.
pub fn upgrade(mut stream: TcpStream, host: &str, path: &str) -> std::io::Result<TcpStream> {
    let key = ws_key();
    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n",
    );
    stream.write_all(req.as_bytes())?;
    stream.flush()?;
    // Read the 101 response headers.
    let mut buf = [0u8; 4096];
    let mut total = 0;
    loop {
        let n = stream.read(&mut buf[total..])?;
        if n == 0 {
            return Err(std::io::Error::other("ws upgrade closed"));
        }
        total += n;
        if total >= 4 && buf[..total].windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
        if total == buf.len() {
            return Err(std::io::Error::other("ws upgrade header too long"));
        }
    }
    let head = String::from_utf8_lossy(&buf[..total]);
    if !head.starts_with("HTTP/1.1 101") {
        return Err(std::io::Error::other(format!("ws upgrade refused: {head}")));
    }
    Ok(stream)
}

/// Wraps a payload as a masked binary WS frame.
pub fn encode_frame(payload: &[u8], out: &mut Vec<u8>) {
    out.clear();
    out.push(0x82); // FIN | binary
    let len = payload.len();
    let mask: [u8; 4] = rand_mask();
    if len < 126 {
        out.push(0x80 | (len as u8));
    } else if len < 65536 {
        out.push(0x80 | 126);
        out.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        out.push(0x80 | 127);
        out.extend_from_slice(&(len as u64).to_be_bytes());
    }
    out.extend_from_slice(&mask);
    for (i, byte) in payload.iter().enumerate() {
        out.push(byte ^ mask[i % 4]);
    }
}

/// Extracts the payload from one complete WS frame (server frames are
/// unmasked per RFC; masking server frames is a protocol violation we
/// tolerate). Returns None for control frames (ping/pong/close handled by
/// the caller) and on malformed input.
pub fn decode_frame(data: &[u8]) -> Option<(usize, Vec<u8>)> {
    if data.len() < 2 {
        return None;
    }
    let opcode = data[0] & 0x0f;
    if opcode != 0x02 && opcode != 0x01 && opcode != 0x09 {
        // binary / text / ping only; pong and close are dropped here
        return None;
    }
    let masked = data[1] & 0x80 != 0;
    let mut len = (data[1] & 0x7f) as usize;
    let mut off = 2;
    if len == 126 {
        if data.len() < 4 {
            return None;
        }
        len = u16::from_be_bytes([data[2], data[3]]) as usize;
        off = 4;
    } else if len == 127 {
        if data.len() < 10 {
            return None;
        }
        len = u64::from_be_bytes([
            data[2], data[3], data[4], data[5], data[6], data[7], data[8], data[9],
        ]) as usize;
        off = 10;
    }
    let mask: Option<[u8; 4]> = if masked {
        if data.len() < off + 4 {
            return None;
        }
        let m = [data[off], data[off + 1], data[off + 2], data[off + 3]];
        off += 4;
        Some(m)
    } else {
        None
    };
    if data.len() < off + len {
        return None;
    }
    let payload = &data[off..off + len];
    let out = match mask {
        Some(m) => payload
            .iter()
            .enumerate()
            .map(|(i, b)| b ^ m[i % 4])
            .collect(),
        None => payload.to_vec(),
    };
    Some((off + len, out))
}

fn ws_key() -> String {
    let mut key = [0u8; 16];
    fill(&mut key);
    b64(&key)
}

fn rand_mask() -> [u8; 4] {
    let mut mask = [0u8; 4];
    fill(&mut mask);
    mask
}

fn fill(buf: &mut [u8]) {
    use std::io::Read;
    let _ = std::fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(buf));
}

fn b64(data: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        if chunk.len() > 1 {
            out.push(TABLE[((n >> 6) & 63) as usize] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[(n & 63) as usize] as char);
        } else {
            out.push('=');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_round_trip_binary() {
        let payload = b"vless request bytes";
        let mut frame = Vec::new();
        encode_frame(payload, &mut frame);
        assert_eq!(frame[0], 0x82);
        let (consumed, decoded) = decode_frame(&frame).unwrap();
        assert_eq!(decoded, payload);
        assert_eq!(consumed, frame.len());
    }

    #[test]
    fn frame_encodes_extended_length() {
        let payload = vec![7u8; 70000];
        let mut frame = Vec::new();
        encode_frame(&payload, &mut frame);
        assert_eq!(frame[1] & 0x7f, 127);
        let (consumed, decoded) = decode_frame(&frame).unwrap();
        assert_eq!(decoded.len(), 70000);
        assert_eq!(consumed, frame.len());
    }

    #[test]
    fn close_and_pong_are_dropped_by_decoder() {
        // close frame (opcode 8), pong (10) — decoder returns None so the
        // caller can act on them.
        let close = [0x88, 0x02, 0x03, 0xe8];
        assert!(decode_frame(&close).is_none());
        let pong = [0x8a, 0x00];
        assert!(decode_frame(&pong).is_none());
    }

    #[test]
    fn masked_server_frame_is_unmasked() {
        // Some servers incorrectly mask replies; tolerate and unmask.
        let mask = [1u8, 2, 3, 4];
        let mut frame = vec![0x82, 0x85];
        frame.extend_from_slice(&mask);
        frame.push(b'h' ^ mask[0]);
        frame.push(b'e' ^ mask[1]);
        frame.push(b'l' ^ mask[2]);
        frame.push(b'l' ^ mask[3]);
        frame.push(b'o' ^ mask[0]);
        let (_, decoded) = decode_frame(&frame).unwrap();
        assert_eq!(decoded, b"hello");
    }
}
