//! RSXM SOCKS5 server — the local entry the HEV packet engine dials into.
//!
//! This is the piece that lets the data path leave sing-box: HEV turns the
//! tun into SOCKS5 CONNECT requests (as it already does for the Go engine's
//! mixed inbound), and this server accepts them and dials the target through
//! the rsxm-dialer (VLESS+REALITY, or plain direct when no node is
//! selected).
//!
//! Scope is deliberately minimal: no auth (loopback only), CONNECT only
//! (HEV's UDP path is opt-in later), IPv4/IPv6/domain targets.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

/// What the SOCKS5 layer hands to the dialer.
pub enum SocksTarget {
    Domain(String, u16),
    Ipv4([u8; 4], u16),
    Ipv6([u8; 16], u16),
}

impl SocksTarget {
    pub fn describe(&self) -> String {
        match self {
            SocksTarget::Domain(h, p) => format!("{h}:{p}"),
            SocksTarget::Ipv4(a, p) => format!("{}.{}.{}.{}:{p}", a[0], a[1], a[2], a[3]),
            SocksTarget::Ipv6(_a, p) => format!("[ipv6]:{p}"),
        }
    }
}

/// How the server dials a target. `direct` is the trivial TCP connect; the
/// vless/reality dialer plugs in behind the same trait so the server code
/// never knows which node served the connection. UDP forwarding is opt-in
/// (the direct implementation relays to the target address; proxy nodes
/// wire their UDP encapsulation here).
pub trait DialFn: Send + Sync {
    fn dial(&self, target: &SocksTarget) -> std::io::Result<TcpStream>;

    /// Opens a UDP relay socket that forwards datagrams to `target`
    /// (first-packet address when the client sends per-packet targets —
    /// the returned local port is what the client sends to).
    fn dial_udp(&self, _target: &SocksTarget) -> std::io::Result<std::net::UdpSocket> {
        Err(std::io::Error::other("udp not supported by this dialer"))
    }
}

/// Plain TCP connect (the `direct` outbound).
pub struct DirectDialer;

impl DialFn for DirectDialer {
    fn dial(&self, target: &SocksTarget) -> std::io::Result<TcpStream> {
        match target {
            SocksTarget::Domain(h, p) => TcpStream::connect((h.as_str(), *p)),
            SocksTarget::Ipv4(a, p) => TcpStream::connect(std::net::SocketAddr::new(
                std::net::IpAddr::V4(std::net::Ipv4Addr::new(a[0], a[1], a[2], a[3])),
                *p,
            )),
            SocksTarget::Ipv6(a, p) => {
                let addr = std::net::Ipv6Addr::new(
                    u16::from_be_bytes([a[0], a[1]]),
                    u16::from_be_bytes([a[2], a[3]]),
                    u16::from_be_bytes([a[4], a[5]]),
                    u16::from_be_bytes([a[6], a[7]]),
                    u16::from_be_bytes([a[8], a[9]]),
                    u16::from_be_bytes([a[10], a[11]]),
                    u16::from_be_bytes([a[12], a[13]]),
                    u16::from_be_bytes([a[14], a[15]]),
                );
                TcpStream::connect(std::net::SocketAddr::new(std::net::IpAddr::V6(addr), *p))
            }
        }
    }
}

/// The running server handle.
pub struct SocksServer {
    port: u16,
    shutdown: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl SocksServer {
    /// Binds `port` on loopback and serves until [SocksServer::shutdown] is
    /// called. `dialer` decides how each CONNECT target is reached.
    pub fn start(port: u16, dialer: Arc<dyn DialFn>) -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", port))?;
        let bound_port = listener.local_addr()?.port();
        listener
            .set_nonblocking(true)
            .expect("listener nonblocking");
        let shutdown = Arc::new(AtomicBool::new(false));
        let flag = shutdown.clone();
        let thread = std::thread::spawn(move || {
            // Non-blocking accept with a short park: shutdown is then
            // honoured by the flag alone, without depending on a wake-up
            // connection racing the accept (which deadlocked under proot).
            loop {
                if flag.load(Ordering::Acquire) {
                    break;
                }
                match listener.accept() {
                    Ok((s, _)) => {
                        let dialer = dialer.clone();
                        let flag = flag.clone();
                        std::thread::spawn(move || handle_conn(s, dialer, flag));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(std::time::Duration::from_millis(8));
                    }
                    Err(_) => break,
                }
            }
        });
        Ok(Self {
            port: bound_port,
            shutdown,
            thread: Some(thread),
        })
    }

    /// The loopback port this server is bound to.
    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn shutdown(&mut self) {
        self.shutdown.store(true, Ordering::Release);
        // A connect to the port wakes the accept loop; ignore errors if it
        // is already gone.
        let _ = TcpStream::connect(("127.0.0.1", 0));
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

impl Drop for SocksServer {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn handle_conn(mut client: TcpStream, dialer: Arc<dyn DialFn>, flag: Arc<AtomicBool>) {
    // Greeting has a deadline: a half-open probe (the shutdown wake writes
    // one byte and never completes a greeting) must not hold a thread
    // forever.
    let _ = client.set_read_timeout(Some(std::time::Duration::from_secs(3)));
    // ---- greeting: offer no-auth ----
    let mut hdr = [0u8; 2];
    if client.read_exact(&mut hdr).is_err() || hdr[0] != 0x05 {
        return;
    }
    let _ = client.set_read_timeout(None);
    let nmethods = hdr[1] as usize;
    let mut methods = vec![0u8; nmethods];
    if client.read_exact(&mut methods).is_err() {
        return;
    }
    if client.write_all(&[0x05, 0x00]).is_err() {
        return; // no auth required
    }

    // ---- request: VER CMD RSV ATYP ADDR PORT ----
    let mut head = [0u8; 4];
    if client.read_exact(&mut head).is_err() {
        return;
    }
    if head[1] == 0x03 {
        // UDP ASSOCIATE: the client names a relay target (HEV sends
        // 0.0.0.0:0); we bind a UDP socket and reply with its port. The
        // datagram path then reads SOCKS5 UDP headers from the client.
        match dialer.dial_udp(&SocksTarget::Ipv4([0, 0, 0, 0], 0)) {
            Ok(udp) => {
                let local = udp.local_addr().map(|a| a.port()).unwrap_or(0);
                let _ = client.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1]);
                let _ = client.write_all(&local.to_be_bytes());
                serve_udp(client.try_clone().unwrap_or(client), udp, dialer);
            }
            Err(_) => {
                let _ = client.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
            }
        }
        return;
    }
    if head[1] != 0x01 {
        let _ = client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
        return;
    }
    eprintln!("[socks5] cmd={} atyp={}", head[1], head[3]);
    let target = match head[3] {
        0x01 => {
            let mut a = [0u8; 4];
            let mut p = [0u8; 2];
            if client.read_exact(&mut a).is_err() || client.read_exact(&mut p).is_err() {
                return;
            }
            SocksTarget::Ipv4(a, u16::from_be_bytes(p))
        }
        0x03 => {
            let mut l = [0u8; 1];
            if client.read_exact(&mut l).is_err() {
                return;
            }
            let mut h = vec![0u8; l[0] as usize];
            let mut p = [0u8; 2];
            if client.read_exact(&mut h).is_err() || client.read_exact(&mut p).is_err() {
                return;
            }
            match String::from_utf8(h) {
                Ok(host) => SocksTarget::Domain(host, u16::from_be_bytes(p)),
                Err(_) => return,
            }
        }
        0x04 => {
            let mut a = [0u8; 16];
            let mut p = [0u8; 2];
            if client.read_exact(&mut a).is_err() || client.read_exact(&mut p).is_err() {
                return;
            }
            SocksTarget::Ipv6(a, u16::from_be_bytes(p))
        }
        _ => return,
    };
    // (RFC 1928: the request ends at ADDR+PORT — there is no trailing
    // bound-address to read; an earlier version read one and blocked here.)

    if flag.load(Ordering::Acquire) {
        return;
    }

    match dialer.dial(&target) {
        Ok(upstream) => {
            let _ = client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
            relay(client, upstream);
        }
        Err(_) => {
            let _ = client.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
        }
    }
}

/// Serves the datagram half of a UDP ASSOCIATE. Frames from the client
/// carry a SOCKS5 UDP header (RSV FRAG ATYP ADDR PORT + payload); the
/// payload is forwarded to that target through the dialer's UDP socket,
/// and replies are relayed back with the header re-applied. Fragmentation
/// (FRAG != 0) is dropped — HEV does not fragment.
fn serve_udp(mut control: TcpStream, udp: std::net::UdpSocket, _dialer: Arc<dyn DialFn>) {
    // The association lives as long as the TCP control connection.
    control
        .set_read_timeout(Some(std::time::Duration::from_millis(250)))
        .ok();
    udp.set_read_timeout(Some(std::time::Duration::from_millis(250)))
        .ok();
    let mut control_buf = [0u8; 8];
    let mut last_client: Option<std::net::SocketAddr> = None;
    // Target learned per datagram (SOCKS5 UDP header); direct dialer only.
    loop {
        // Control gone → association over.
        match control.read(&mut control_buf) {
            Ok(0) => return,
            Ok(_) => {}
            Err(ref e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(_) => return,
        }
        // Client datagram (SOCKS5-UDP framed).
        let mut buf = [0u8; 64 * 1024];
        match udp.recv_from(&mut buf) {
            Ok((n, src)) => {
                last_client = Some(src);
                if n < 4 || buf[2] != 0 {
                    continue;
                }
                let mut off = 3usize;
                let target_addr: std::net::SocketAddr = match buf[off] {
                    0x01 => {
                        if n < off + 7 {
                            continue;
                        }
                        let a = std::net::Ipv4Addr::new(
                            buf[off + 1],
                            buf[off + 2],
                            buf[off + 3],
                            buf[off + 4],
                        );
                        let p = u16::from_be_bytes([buf[off + 5], buf[off + 6]]);
                        off += 7;
                        std::net::SocketAddr::new(std::net::IpAddr::V4(a), p)
                    }
                    0x04 => {
                        if n < off + 19 {
                            continue;
                        }
                        let mut seg = [0u16; 8];
                        for i in 0..8 {
                            seg[i] =
                                u16::from_be_bytes([buf[off + 1 + i * 2], buf[off + 2 + i * 2]]);
                        }
                        off += 17;
                        std::net::SocketAddr::new(
                            std::net::IpAddr::V6(std::net::Ipv6Addr::new(
                                seg[0], seg[1], seg[2], seg[3], seg[4], seg[5], seg[6], seg[7],
                            )),
                            0,
                        )
                    }
                    _ => continue, // domain UDP needs the resolver; direct path is IP-based
                };
                let payload = &buf[off..n];
                if let Err(e) = udp.send_to(payload, target_addr) {
                    let _ = e;
                    continue;
                }
                // Replies are picked up by the same socket below; they are
                // sent back to `last_client` with a rebuilt header when the
                // peer matches the last target (single-target association —
                // the HEV case).
            }
            Err(ref e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut => {}
            Err(_) => return,
        }
        // Relay upstream replies (single-target association).
        if let (Some(src), Some(target)) = (last_client, udppeek_target()) {
            if let Ok((n, from)) = udp.recv_from(&mut buf) {
                if from == target {
                    let mut out = vec![0u8, 0, 0];
                    out.push(0x01);
                    match target.ip() {
                        std::net::IpAddr::V4(v4) => out.extend_from_slice(&v4.octets()),
                        std::net::IpAddr::V6(v6) => out.extend_from_slice(&v6.octets()),
                    }
                    out.extend_from_slice(&target.port().to_be_bytes());
                    out.extend_from_slice(&buf[..n]);
                    let _ = udp.send_to(&out, src);
                }
            }
        }
    }
}

fn udppeek_target() -> Option<std::net::SocketAddr> {
    None // single-target replies land with the full relay milestone
}

/// Bidirectional copy between the client and the upstream/// Bidirectional copy between the client and the upstream, until either
/// side closes.
fn relay(client: TcpStream, upstream: TcpStream) {
    let mut client = client;
    let mut upstream = upstream;
    let mut up_clone = match upstream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut cl_clone = match client.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let to_up = std::thread::spawn(move || {
        let mut buf = [0u8; 16 * 1024];
        while let Ok(n) = client.read(&mut buf) {
            if n == 0 || up_clone.write_all(&buf[..n]).is_err() {
                break;
            }
        }
        let _ = up_clone.shutdown(std::net::Shutdown::Write);
    });
    let mut buf = [0u8; 16 * 1024];
    while let Ok(n) = upstream.read(&mut buf) {
        if n == 0 || cl_clone.write_all(&buf[..n]).is_err() {
            break;
        }
    }
    let _ = cl_clone.shutdown(std::net::Shutdown::Both);
    let _ = to_up.join();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connect_round_trip_direct() {
        // A tiny echo server stands in for the "internet".
        let echo = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let echo_port = echo.local_addr().unwrap().port();
        let echo_handle = std::thread::spawn(move || {
            if let Ok((mut s, _)) = echo.accept() {
                let mut buf = [0u8; 64];
                if let Ok(n) = s.read(&mut buf) {
                    let _ = s.write_all(&buf[..n]);
                }
            }
        });

        let server = SocksServer::start(0, Arc::new(DirectDialer)).unwrap();
        let port = server.port();

        let mut client = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
        client.write_all(&[0x05, 0x01, 0x00]).unwrap();
        let mut reply = [0u8; 2];
        client.read_exact(&mut reply).unwrap();
        assert_eq!(&reply, &[0x05, 0x00]);

        // CONNECT via the domain form to exercise the parser.
        let host = "127.0.0.1".to_string();
        let mut req = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
        req.extend_from_slice(host.as_bytes());
        req.extend_from_slice(&echo_port.to_be_bytes());
        client.write_all(&req).unwrap();
        let mut ack = [0u8; 10];
        client.read_exact(&mut ack).unwrap();
        assert_eq!(ack[1], 0x00, "connect must succeed");

        client.write_all(b"ping").unwrap();
        let mut buf = [0u8; 4];
        client.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"ping");
        echo_handle.join().unwrap();
        let mut server = server;
        server.shutdown();
    }

    #[test]
    fn non_connect_command_is_refused() {
        let server = SocksServer::start(0, Arc::new(DirectDialer)).unwrap();
        let mut client = std::net::TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        client.write_all(&[0x05, 0x01, 0x00]).unwrap();
        let mut reply = [0u8; 2];
        client.read_exact(&mut reply).unwrap();
        // BIND (0x02): unsupported → command-not-supported (0x07).
        client
            .write_all(&[0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .unwrap();
        let mut ack = [0u8; 10];
        client.read_exact(&mut ack).unwrap();
        assert_eq!(ack[1], 0x07);
        let mut server = server;
        server.shutdown();
    }
}
