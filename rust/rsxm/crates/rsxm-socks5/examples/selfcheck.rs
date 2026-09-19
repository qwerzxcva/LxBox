//! Exercises both SOCKS5 paths without the test harness (proot kills the
//! harness threads; a plain binary is reliable here).

use rsxm_socks5::{DirectDialer, SocksServer};
use std::io::{Read, Write};
use std::sync::Arc;

fn main() {
    // Path 1: refused command.
    let server = SocksServer::start(0, Arc::new(DirectDialer)).unwrap();
    let mut c = std::net::TcpStream::connect(("127.0.0.1", server.port())).unwrap();
    c.write_all(&[0x05, 0x01, 0x00]).unwrap();
    let mut r = [0u8; 2];
    c.read_exact(&mut r).unwrap();
    assert_eq!(&r, &[0x05, 0x00]);
    c.write_all(&[0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
        .unwrap();
    let mut ack = [0u8; 10];
    c.read_exact(&mut ack).unwrap();
    assert_eq!(ack[1], 0x07, "BIND must be refused with 0x07");
    println!("path1 refused-command OK");
    println!("shutting path1...");
    let mut s = server;
    s.shutdown();

    // Path 2: CONNECT round-trip through an echo server.
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
    let mut c = std::net::TcpStream::connect(("127.0.0.1", server.port())).unwrap();
    c.write_all(&[0x05, 0x01, 0x00]).unwrap();
    let mut r = [0u8; 2];
    c.read_exact(&mut r).unwrap();
    let mut req = vec![0x05u8, 0x01, 0x00, 0x03, 9];
    req.extend_from_slice(b"127.0.0.1");
    req.extend_from_slice(&echo_port.to_be_bytes());
    c.write_all(&req).unwrap();
    let mut ack = [0u8; 10];
    c.read_exact(&mut ack).unwrap();
    assert_eq!(ack[1], 0x00, "CONNECT must succeed");
    c.write_all(b"ping").unwrap();
    let mut buf = [0u8; 4];
    c.read_exact(&mut buf).unwrap();
    assert_eq!(&buf, b"ping");
    println!("path2 connect-round-trip OK");
    echo_handle.join().unwrap();
    let mut s = server;
    s.shutdown();
    println!("selfcheck complete");
}
