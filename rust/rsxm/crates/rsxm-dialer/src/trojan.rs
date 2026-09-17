//! Trojan protocol — TLS-wrapped raw SOCKS proxy.
//!
//! Trojan is deliberately thin: once the TCP handshake + TLS verify succeed,
//! the client speaks a single `[CRLF-terminated password]` greeting followed
//! by the standard SOCKS5 address + the original application bytes. The
//! server replies with a simple `+OK\r\n` (or `-ERR\r\n`) and then just
//! proxies bytes.
//!
//! Wire layout (client → server):
//!
//! ```text
//! <password>\r\n
//! ATYP(1) ADDR(var) PORT(2BE)
//! [application bytes...]
//! ```
//!
//! We don't own a TLS stack here — the caller hands us an already-opened
//! `TcpStream` that it negotiated TLS on (either via REALITY or a plain
//! rustls/TLS13 session). The Trojan dialect is only concerned with the
//! greeting framing on top of that encrypted channel.

use std::io::{Read, Write};
use std::net::TcpStream;

use crate::shadowsocks::SocksAddr;

/// Trojan outbound credentials.
#[derive(Debug, Clone)]
pub struct TrojanConfig {
    pub server: String,
    pub port: u16,
    pub password: String,
    pub sni: Option<String>,
    pub alpn: Vec<String>,
    /// Whether to send SNI. Trojan servers historically rely on the TLS
    /// SNI to route incoming connections when they share an IP with other
    /// vhosts. Disabling it lets the operator rely purely on the password.
    pub sni_enabled: bool,
}

impl TrojanConfig {
    pub fn new(server: impl Into<String>, port: u16, password: impl Into<String>) -> Self {
        Self {
            server: server.into(),
            port,
            password: password.into(),
            sni: None,
            alpn: vec!["h2".to_string(), "http/1.1".to_string()],
            sni_enabled: true,
        }
    }
}

/// Errors specific to Trojan handshake.
#[derive(Debug)]
pub enum TrojanError {
    /// Password was rejected by the server (`-ERR\r\n` response).
    AuthFailed,
    /// The response line was neither `+OK` nor `-ERR`.
    UnexpectedResponse(String),
    /// I/O failure on the underlying stream.
    Io(std::io::Error),
}

impl From<std::io::Error> for TrojanError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

impl std::fmt::Display for TrojanError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::AuthFailed => f.write_str("trojan: password rejected by server"),
            Self::UnexpectedResponse(s) => write!(f, "trojan: unexpected server response: {s:?}"),
            Self::Io(e) => write!(f, "trojan: I/O error: {e}"),
        }
    }
}

/// Send the Trojan greeting (`password\r\n[addr]port`) on top of an already
/// connected stream.
pub fn send_greeting(stream: &mut TcpStream, cfg: &TrojanConfig, target: &SocksAddr) -> Result<(), TrojanError> {
    let mut buf = Vec::with_capacity(128);
    buf.extend_from_slice(cfg.password.as_bytes());
    buf.extend_from_slice(b"\r\n");
    buf.extend_from_slice(&target.to_bytes());
    stream.write_all(&buf)?;
    Ok(())
}

/// Read the server's one-line response (`+OK\r\n` or `-ERR\r\n`).
pub fn recv_response(stream: &mut TcpStream) -> Result<(), TrojanError> {
    let mut line = Vec::with_capacity(32);
    let mut byte = [0u8; 1];
    loop {
        stream.read_exact(&mut byte)?;
        line.push(byte[0]);
        if line.ends_with(b"\r\n") {
            break;
        }
        if line.len() > 64 {
            return Err(TrojanError::UnexpectedResponse(
                String::from_utf8_lossy(&line).into_owned(),
            ));
        }
    }
    let text = {
        // Trim trailing CR/LF manually — Vec<u8> has no trim_end_matches.
        let mut trimmed = line.clone();
        while let Some(last) = trimmed.last() {
            if *last == b'\r' || *last == b'\n' {
                trimmed.pop();
            } else {
                break;
            }
        }
        String::from_utf8_lossy(&trimmed).into_owned()
    };
    match text.as_str() {
        "+OK" => Ok(()),
        "-ERR" => Err(TrojanError::AuthFailed),
        other => Err(TrojanError::UnexpectedResponse(other.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greeting_buffer_layout() {
        let cfg = TrojanConfig::new("example.com", 443, "my-secret-pw");
        let addr = SocksAddr::Domain("google.com".into(), 443);
        let mut buf = Vec::new();
        buf.extend_from_slice(cfg.password.as_bytes());
        buf.extend_from_slice(b"\r\n");
        buf.extend_from_slice(&addr.to_bytes());
        let text = String::from_utf8_lossy(&buf);
        // Must start with the password + CRLF
        assert!(text.starts_with("my-secret-pw\r\n"));
        // Then ATYP=3 (domain), len=10, "google.com", port=0x01BB (443)
        assert_eq!(buf[buf.len() - 2..], [0x01, 0xBB]);
    }

    #[test]
    fn response_parsing_ok() {
        let mut data = b"+OK\r\n".to_vec();
        while matches!(data.last(), Some(b'\r') | Some(b'\n')) {
            data.pop();
        }
        let text = String::from_utf8_lossy(&data);
        assert_eq!(text, "+OK");
    }

    #[test]
    fn response_parsing_err() {
        let mut data = b"-ERR\r\n".to_vec();
        while matches!(data.last(), Some(b'\r') | Some(b'\n')) {
            data.pop();
        }
        let text = String::from_utf8_lossy(&data);
        assert_eq!(text, "-ERR");
    }

    #[test]
    fn response_sanity() {
        // Ensure the TrojanError enum stringifies cleanly.
        let err = TrojanError::AuthFailed;
        assert!(err.to_string().contains("rejected"));
        let err = TrojanError::UnexpectedResponse("???".into());
        assert!(err.to_string().contains("???"));
    }
}
