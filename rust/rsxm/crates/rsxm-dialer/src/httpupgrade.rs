//! httpupgrade transport (client role): a plain HTTP/1.1 GET with
//! `Upgrade: websocket` semantics but **no** WebSocket framing — after the
//! 101, the connection is a raw bidirectional byte stream (that is the
//! entire point of the httpupgrade profile; CDNs terminate the upgrade and
//! pass bytes through). This is why it is materially simpler than ws.rs:
//! no masks, no opcodes, no fragmentation.

use std::io::{Read, Write};
use std::net::TcpStream;

/// An httpupgrade node descriptor.
#[derive(Clone, Debug)]
pub struct HttpUpgradeTransport {
    pub host: String,
    pub path: String,
    /// Bytes that arrived with the 101 (post-upgrade stream prefix).
    pub pending: Vec<u8>,
}

/// Performs the upgrade handshake. Returns the stream positioned at the
/// raw byte stream (the 101 response is consumed).
pub fn upgrade(
    mut stream: TcpStream,
    host: &str,
    path: &str,
) -> std::io::Result<(TcpStream, Vec<u8>)> {
    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n",
    );
    stream.write_all(req.as_bytes())?;
    stream.flush()?;
    // Read the 101 response headers (terminates at CRLFCRLF).
    let mut buf: Vec<u8> = Vec::with_capacity(1024);
    let mut chunk = [0u8; 512];
    loop {
        let n = stream.read(&mut chunk)?;
        if n == 0 {
            return Err(std::io::Error::other("httpupgrade closed during upgrade"));
        }
        buf.extend_from_slice(&chunk[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
        if buf.len() > 16 * 1024 {
            return Err(std::io::Error::other("httpupgrade header too long"));
        }
    }
    let head_end = buf
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .expect("terminator was found above")
        + 4;
    let head = String::from_utf8_lossy(&buf[..head_end]);
    if !head.starts_with("HTTP/1.1 101") {
        return Err(std::io::Error::other(format!(
            "httpupgrade refused: {}",
            head.lines().next().unwrap_or("")
        )));
    }
    // Bytes past the header belong to the data stream — hand them back.
    let leftover = buf[head_end..].to_vec();
    Ok((stream, leftover))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upgrade_refuses_non_101() {
        // A local server that answers 404 — upgrade must surface the error
        // with the status line.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            s.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
                .unwrap();
        });
        let stream = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        let err = upgrade(stream, "example.com", "/path").unwrap_err();
        assert!(err.to_string().contains("404"), "got: {err}");
        handle.join().unwrap();
    }

    #[test]
    fn upgrade_succeeds_on_101_and_stream_is_raw() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut buf = [0u8; 2048];
            let _ = s.read(&mut buf);
            s.write_all(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n")
                .unwrap();
            // After the upgrade the server writes raw bytes — no framing.
            s.write_all(b"raw-data").unwrap();
        });
        let stream = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        let (mut stream, leftover) = upgrade(stream, "example.com", "/path").unwrap();
        // Timing decides whether the post-upgrade bytes arrive with the
        // header (→ leftover) or after (→ stream). Both are valid; assert
        // on whichever holds the data.
        if leftover.is_empty() {
            let mut buf = [0u8; 8];
            stream.read_exact(&mut buf).unwrap();
            assert_eq!(&buf, b"raw-data");
        } else {
            assert_eq!(leftover, b"raw-data");
        }
    }

    #[test]
    fn upgrade_request_carries_host_and_path() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut buf = vec![0u8; 4096];
            let n = s.read(&mut buf).unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            // Echo the request line back inside the 101 body so the test
            // can assert on it.
            s.write_all(
                format!(
                    "HTTP/1.1 101 Switching Protocols\r\n\r\n{}",
                    req.lines().next().unwrap_or("")
                )
                .as_bytes(),
            )
            .unwrap();
        });
        let stream = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        let (_stream, leftover) = upgrade(stream, "cdn.example.com", "/ws-path").unwrap();
        // The echoed request line came back as post-upgrade data (server
        // sent it immediately after the header, so it lands in leftover).
        assert_eq!(String::from_utf8_lossy(&leftover), "GET /ws-path HTTP/1.1");
    }
}
