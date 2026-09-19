//! End-to-end: dial the local vendored sing-box VLESS+REALITY server and
//! fetch a URL through it (verifies TLS+REALITY auth, VLESS frame, relay).
//!
//! Usage: e2e <public_key_b64> <short_id_hex> <uuid> [server] [port] [sni] [url_host] [url_port]

use rsxm_dialer::vless::{VlessCommand, VlessDestination, VlessRequest};
use rsxm_dialer::{dial, VlessRealityTarget};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: e2e <public_key_b64> <short_id_hex> <uuid> [server] [port] [sni] [url_host] [url_port]");
        std::process::exit(2);
    }
    let target = VlessRealityTarget::from_parts(
        args.get(4).cloned().unwrap_or_else(|| "127.0.0.1".into()),
        args.get(5).map(|s| s.parse().unwrap()).unwrap_or(18443),
        args.get(6)
            .cloned()
            .unwrap_or_else(|| "www.cloudflare.com".into()),
        &args[1],
        &args[2],
        &args[3],
    )
    .expect("target");
    let url_host = args
        .get(7)
        .cloned()
        .unwrap_or_else(|| "www.cloudflare.com".into());
    let url_port: u16 = args.get(8).map(|s| s.parse().unwrap()).unwrap_or(80);

    let request = VlessRequest {
        uuid: target.uuid,
        command: VlessCommand::Tcp,
        destination: VlessDestination::Domain(url_host.clone(), url_port),
        flow: String::new(),
    };
    let mut conn = dial(&target, &request, None).expect("dial");
    eprintln!("[e2e] REALITY handshake + VLESS request sent");

    let http = format!("GET / HTTP/1.1\r\nHost: {url_host}\r\nConnection: close\r\n\r\n");
    conn.send(http.as_bytes()).unwrap();

    let mut got = Vec::new();
    let start = std::time::Instant::now();
    while start.elapsed() < std::time::Duration::from_secs(10) {
        let before = got.len();
        if !conn.recv(&mut got) {
            break;
        }
        if got.len() > before {
            continue; // got data; keep reading without delay
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
        if got.windows(5).any(|w| w == b"0\r\n\r\n") {
            break;
        }
    }
    let text = String::from_utf8_lossy(&got);
    println!("{text}");
}
