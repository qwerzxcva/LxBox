//go:build with_quic

package include

// AIBox-slim QUIC surface: the app speaks vless (whose QUIC transport is
// transport/v2rayquic) and resolves over DoQ / DoH3. Everything else the
// upstream `with_quic` tag drags in — hysteria, hysteria2 (including the
// realm service), TUIC, the naive HTTP/3 listener — was removed with the
// protocol diet; those registrations are gone rather than stubbed, so the
// types are not even linked.

import (
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/quic"
	_ "github.com/sagernet/sing-box/transport/v2rayquic"
)

func registerQUICInbounds(_ *inbound.Registry) {}

func registerQUICOutbounds(_ *outbound.Registry) {}

func registerQUICTransports(registry *dns.TransportRegistry) {
	quic.RegisterTransport(registry)
	quic.RegisterHTTP3Transport(registry)
}

