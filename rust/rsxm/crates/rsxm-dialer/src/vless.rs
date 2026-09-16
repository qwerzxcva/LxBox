//! VLESS request frame encoding, byte-verified against
//! `sing-vmess/vless/protocol.go` (WriteRequest):
//! version(0) | uuid(16) | addons_len(1) [addons: protobuf field 1 = flow]
//! command(1) | addr_port | payload...

pub const VLESS_VERSION: u8 = 0;
pub const FLOW_VISION: &str = "xtls-rprx-vision";

pub const COMMAND_TCP: u8 = 1;
pub const COMMAND_UDP: u8 = 2;
pub const COMMAND_MUX: u8 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VlessCommand {
    Tcp,
    Udp,
}

#[derive(Clone, Debug)]
pub enum VlessDestination {
    Domain(String, u16),
    Ipv4([u8; 4], u16),
    Ipv6([u8; 16], u16),
}

/// Address family byte + port-then-address wire order, per
/// `sing-vmess`'s AddressSerializer: M.AddressFamilyByte(0x01, IPv4),
/// (0x03, IPv6), (0x02, Fqdn); PortThenAddress().
impl VlessDestination {
    fn write(&self, out: &mut Vec<u8>) {
        match self {
            VlessDestination::Domain(host, port) => {
                out.extend_from_slice(&port.to_be_bytes());
                out.push(0x02);
                out.push(host.len() as u8);
                out.extend_from_slice(host.as_bytes());
            }
            VlessDestination::Ipv4(addr, port) => {
                out.extend_from_slice(&port.to_be_bytes());
                out.push(0x01);
                out.extend_from_slice(addr);
            }
            VlessDestination::Ipv6(addr, port) => {
                out.extend_from_slice(&port.to_be_bytes());
                out.push(0x03);
                out.extend_from_slice(addr);
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct VlessRequest {
    pub uuid: [u8; 16],
    pub command: VlessCommand,
    pub destination: VlessDestination,
    /// flow (addons protobuf field 1); empty = no addons. Reserved for the
    /// vision layer — the frame encoding already carries it correctly.
    pub flow: String,
}

/// Encodes the request frame with no payload (early-conn style: payload
/// follows in subsequent application-data records).
pub fn encode_request(request: &VlessRequest) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    out.push(VLESS_VERSION);
    out.extend_from_slice(&request.uuid);

    // addons: only the flow field exists (protobuf field 1, wire type 2)
    let mut addons: Vec<u8> = Vec::new();
    if !request.flow.is_empty() {
        addons.push(0x0A); // (1 << 3) | 2
        let flow = request.flow.as_bytes();
        // varint length (flows are short; single byte suffices < 128)
        addons.push(flow.len() as u8);
        addons.extend_from_slice(flow);
    }
    out.push(addons.len() as u8);
    out.extend_from_slice(&addons);

    out.push(match request.command {
        VlessCommand::Tcp => COMMAND_TCP,
        VlessCommand::Udp => COMMAND_UDP,
    });
    request.destination.write(&mut out);
    out
}

/// The magic packetaddr address used for UDP-over-stream mode (unused in
/// stage 4a but defined here to keep the protocol constants together).
pub const PACKETADDR_MAGIC: &str = "packetaddrVersion0";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_frame_matches_go_reference_layout() {
        // sing-vmess WriteRequest with Request{UUID: 16B, Command: TCP,
        // Destination: FQDN "example.com":443, Flow: ""} produces:
        // 00 | uuid(16) | 00 | 01 | 2B 00 0B example.com
        // (version, uuid, addons_len=0, command=1, port=443, family=2,
        //  host_len=11, host)
        let request = VlessRequest {
            uuid: [0xAA; 16],
            command: VlessCommand::Tcp,
            destination: VlessDestination::Domain("example.com".into(), 443),
            flow: String::new(),
        };
        let frame = encode_request(&request);
        let expected: Vec<u8> = {
            let mut e = vec![0x00];
            e.extend_from_slice(&[0xAA; 16]);
            e.push(0x00); // addons len
            e.push(0x01); // tcp
            e.extend_from_slice(&443u16.to_be_bytes());
            e.push(0x02);
            e.push(11);
            e.extend_from_slice(b"example.com");
            e
        };
        assert_eq!(frame, expected);
    }

    #[test]
    fn flow_addons_use_protobuf_field_one() {
        let request = VlessRequest {
            uuid: [0x11; 16],
            command: VlessCommand::Tcp,
            destination: VlessDestination::Ipv4([1, 2, 3, 4], 80),
            flow: "xtls-rprx-vision".into(),
        };
        let frame = encode_request(&request);
        // addons_len = 1(header) + 1(varint len 16) + 16 = 18
        assert_eq!(frame[17], 18);
        assert_eq!(&frame[18..20], &[0x0A, 16]);
        assert_eq!(&frame[20..36], FLOW_VISION.as_bytes());
        // then command tcp(1), port 80, family v4(1), addr
        assert_eq!(frame[36], 0x01);
        assert_eq!(&frame[37..39], &80u16.to_be_bytes());
        assert_eq!(frame[39], 0x01);
        assert_eq!(&frame[40..44], &[1, 2, 3, 4]);
    }

    #[test]
    fn ipv6_family_byte_is_three() {
        let request = VlessRequest {
            uuid: [0; 16],
            command: VlessCommand::Tcp,
            destination: VlessDestination::Ipv6([9u8; 16], 1),
            flow: String::new(),
        };
        let frame = encode_request(&request);
        // version(1) + uuid(16) + addons_len(1) = offset 18 for command;
        // then port(2) at 19..21 and family at 21.
        assert_eq!(frame[18], 0x01);
        assert_eq!(&frame[19..21], &1u16.to_be_bytes());
        assert_eq!(frame[21], 0x03);
        assert_eq!(&frame[22..38], &[9u8; 16]);
    }

    #[test]
    fn decoders_round_trip() {
        // Exactly 43 RawURL chars decode to 32 bytes; shorter/longer fail.
        assert!(crate::base64_rawurl_decode_32("AAAA").is_err());
        let valid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 chars 'A'
        let decoded = crate::base64_rawurl_decode_32(valid).expect("valid");
        assert!(decoded.iter().all(|b| *b == 0));

        let sid = crate::short_id_hex_decode("0123456789abcdef").expect("hex");
        assert_eq!(sid, [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF]);
        let short = crate::short_id_hex_decode("01").expect("hex");
        assert_eq!(short[0], 0x01);

        let uuid = crate::parse_uuid("9f2b4b10-6818-492e-a157-d5131d450c7b").expect("uuid");
        assert_eq!(uuid[0], 0x9F);
        assert_eq!(uuid[15], 0x7B);
    }
}
