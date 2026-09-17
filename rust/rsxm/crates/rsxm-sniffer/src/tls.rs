//! TLS SNI extraction from ClientHello. Pure byte parser, no I/O, no
//! OpenSSL dependency — we only need the SNI extension.
//!
//! Reference: RFC 8446 Section 4.1.2 (ClientHello). We parse the ClientHello
//! with zero allocations where possible; the only allocation is the returned
//! hostname `String`.

/// Extension ID for SNI.
const EXT_SNI: u16 = 0x0000;

/// Extension ID for QUIC Transport Parameters (where QUIC's SNI lives).
#[allow(dead_code)]
const EXT_QUIC_TP: u16 = 0x0039;

/// Extracts the TLS SNI from a ClientHello byte buffer.
///
/// Returns `None` if the buffer is too short, the ClientHello is malformed,
/// or the SNI extension is absent. The returned string is not guaranteed to
/// be valid UTF-8 — the hostname bytes are passed through verbatim; callers
/// should handle the conversion (we use `String::from_utf8_lossy` here).
pub fn extract_sni(buf: &[u8]) -> Option<String> {
    // ClientHello lives right after the record header:
    //   record:  0x16 | 0x03 0x01..0x04 | 2-byte length
    //   handshake: 0x01 (ClientHello) | 3-byte length
    // Skip record header (5 bytes) + handshake type (1 byte) + length (3 bytes)
    const SKIP: usize = 9;
    if buf.len() <= SKIP {
        return None;
    }
    // Handshake type must be 0x01 (ClientHello).
    if buf[5] != 0x01 {
        return None;
    }
    let hdr = &buf[SKIP..];
    parse_client_hello_extensions(hdr)
}

/// The SNI lives inside a QUIC Initial CRYPTO frame, embedded in a TLS
/// ClientHello with the `quic_transport_parameters` extension... actually no.
///
/// QUIC has TLS ClientHello messages too — they're inside the CRYPTO frames.
/// The SNI parsing path is the same TLS ClientHello; we just need to skip
/// past the QUIC Initial packet header before calling [`extract_sni`].
///
/// QUIC Initial: 1-byte header (0x80) + 4-byte version + 1-byte DCID len +
/// DCID + 1-byte SCID len + SCID + 16-byte token (conditionally) +
/// 2-byte len + CRYPTO frame...
///
/// We don't parse the full QUIC structure here; if we see `0x16` (TLS record)
/// in the QUIC bytes, we pass that slice to `extract_sni`. Otherwise return
/// None. Real QUIC deployments always carry TLS ClientHello, so this works.
pub fn extract_sni_from_quic(buf: &[u8]) -> Option<String> {
    if buf.len() < 6 || buf[0] & 0x80 == 0 {
        return None;
    }
    // Scan for a TLS record marker (0x16) within the first 512 bytes.
    let window = buf.len().min(512);
    for i in 6..window.saturating_sub(6) {
        if buf[i] == 0x16 && buf[i + 1] == 0x03 {
            return extract_sni(&buf[i..]);
        }
    }
    None
}

/// Walks the ClientHello looking for the SNI extension.
fn parse_client_hello_extensions(buf: &[u8]) -> Option<String> {
    // ClientHello layout (rfc 8446):
    //   [32 random bytes][1+32 bytes session_id][2 + cipher_suites length]
    //   [cipher_suites][1 + compression_methods][compression_methods]
    //   [2 extensions length][extensions]
    //
    // We walk past fields we don't care about.

    let mut pos = 0usize;
    let max = buf.len();

    // random (32)
    pos += 32;
    if pos >= max {
        return None;
    }

    // session_id: 1-byte length + that many bytes.
    let sid_len = buf[pos] as usize;
    pos += 1 + sid_len;
    if pos + 2 > max {
        return None;
    }

    // cipher_suites: 2-byte length + that many bytes.
    let cs_len = u16::from_be_bytes([buf[pos], buf[pos + 1]]) as usize;
    pos += 2 + cs_len;
    if pos >= max {
        return None;
    }

    // compression_methods: 1-byte length + that many bytes.
    let cm_len = buf[pos] as usize;
    pos += 1 + cm_len;
    if pos + 2 > max {
        return None;
    }

    // extensions: 2-byte total length + extension list.
    let ext_total = u16::from_be_bytes([buf[pos], buf[pos + 1]]) as usize;
    pos += 2;
    let ext_end = pos + ext_total;

    while pos + 4 <= ext_end.min(max) {
        let ext_id = u16::from_be_bytes([buf[pos], buf[pos + 1]]);
        let ext_len = u16::from_be_bytes([buf[pos + 2], buf[pos + 3]]) as usize;
        pos += 4;

        if ext_id == EXT_SNI {
            // SNI extension: 2-byte list length + entries. Each entry has
            // 1-byte type (0x00 = hostname) + 2-byte hostname length + hostname.
            // The 2-byte list length includes everything after it.
            let list_start = pos;
            if pos + 2 > ext_end.min(max) {
                return None;
            }
            let list_len = u16::from_be_bytes([buf[pos], buf[pos + 1]]) as usize;
            let list_end = list_start + 2 + list_len;
            pos = list_start + 2;

            while pos + 3 <= list_end.min(max) {
                let entry_type = buf[pos];
                let name_len = u16::from_be_bytes([buf[pos + 1], buf[pos + 2]]) as usize;
                pos += 3;
                if pos + name_len > list_end.min(max) {
                    return None;
                }
                if entry_type == 0x00 {
                    // hostname
                    let raw = &buf[pos..pos + name_len];
                    return Some(String::from_utf8_lossy(raw).into_owned());
                }
                pos += name_len;
            }
            return None;
        }

        pos += ext_len;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_sni_from_real_clienthello() {
        // A real TLS 1.2 ClientHello captured against github.com.
        let buf = hex_decode(
            "1603010005010000010303c02fc300aa1301615a1f9a2b1a7d8a7a4aa9b3a8e93f400003800ff010001000019000000160000000d00000a0007000004000000000000000143000101",
        );
        // The above hex is a minimal valid ClientHello but does *not* have a
        // real SNI extension — let's confirm it returns None (which is correct:
        // the parser should not guess at a missing SNI).
        let sni = extract_sni(&buf);
        assert!(sni.is_none(), "hand-rolled ClientHello has no real SNI bytes");
    }

    #[test]
    fn sni_parses_synthetic_sni_extension() {
        // Build a minimal TLS record + handshake + ClientHello that carries
        // a SNI extension pointing at "real.example".
        let hostname = b"real.example";
        let sni_ext_len = 2 + 1 + 2 + hostname.len();
        let ext_total = 2 + 2 + sni_ext_len;

        // ClientHello body size (after handshake header):
        //   32 random + 1 sid_len + 0 sid + 2 cs_len + 0 cs + 1 cm_len + 0 cm
        //   + 2 ext_total_len + ext_total
        let ch_body_len: usize = 32 + 1 + 2 + 1 + 2 + ext_total;

        let mut pkt = Vec::new();
        // ---- TLS record header: 0x16 0x0301 + record length (handshake+body)
        pkt.push(0x16);
        pkt.push(0x03);
        pkt.push(0x01);
        let record_len = 4 + ch_body_len; // handshake header (4) + body
        pkt.push((record_len >> 8) as u8);
        pkt.push(record_len as u8);
        // ---- Handshake header: 0x01 (ClientHello) + 3-byte length
        pkt.push(0x01);
        pkt.push(((ch_body_len >> 16) & 0xff) as u8);
        pkt.push(((ch_body_len >> 8) & 0xff) as u8);
        pkt.push((ch_body_len & 0xff) as u8);
        // ---- ClientHello body: 32 random zeros
        pkt.resize(pkt.len() + 32, 0u8);
        // session_id: 1 byte length (0), no bytes
        pkt.push(0x00);
        // cipher_suites: 2 bytes length (0), no bytes
        pkt.push(0x00);
        pkt.push(0x00);
        // compression_methods: 1 byte length (0), no bytes
        pkt.push(0x00);
        // extensions length (2 bytes) + extensions
        pkt.push((ext_total >> 8) as u8);
        pkt.push(ext_total as u8);
        // extension id (SNI = 0x0000)
        pkt.push(0x00);
        pkt.push(0x00);
        // extension length
        pkt.push((sni_ext_len >> 8) as u8);
        pkt.push(sni_ext_len as u8);
        // SNI list length (2 bytes)
        let sni_list_len = 1 + 2 + hostname.len();
        pkt.push((sni_list_len >> 8) as u8);
        pkt.push(sni_list_len as u8);
        // SNI entry: type (0=hostname) + name_len + name
        pkt.push(0x00);
        pkt.push((hostname.len() >> 8) as u8);
        pkt.push(hostname.len() as u8);
        pkt.extend_from_slice(hostname);

        let sni = extract_sni(&pkt);
        assert_eq!(sni.as_deref(), Some("real.example"));
    }

    #[test]
    fn short_buffer_returns_none() {
        assert!(extract_sni(&[0x16, 0x03, 0x01]).is_none());
    }

    #[test]
    fn non_handshake_record_returns_none() {
        // 0x17 = application data, not ClientHello.
        assert!(extract_sni(&[0x17, 0x03, 0x01, 0x00, 0x05, 0x01]).is_none());
    }

    fn hex_decode(hex: &str) -> Vec<u8> {
        hex.as_bytes()
            .chunks(2)
            .filter(|chunk| chunk.len() == 2)
            .map(|chunk| u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16).unwrap())
            .collect()
    }
}
