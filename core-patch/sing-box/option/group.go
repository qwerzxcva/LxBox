package option

import "github.com/sagernet/sing/common/json/badoption"

type SelectorOutboundOptions struct {
	GroupCommonOption
	Default                   string `json:"default,omitempty" reference:"outbound"`
	InterruptExistConnections bool   `json:"interrupt_exist_connections,omitempty"`
}

type URLTestOutboundOptions struct {
	GroupCommonOption
	URL                       string                 `json:"url,omitempty"`
	Interval                  badoption.Duration     `json:"interval,omitempty"`
	Tolerance                 uint16                 `json:"tolerance,omitempty"`
	IdleTimeout               badoption.Duration     `json:"idle_timeout,omitempty"`
	InterruptExistConnections bool                   `json:"interrupt_exist_connections,omitempty"`
	Fallback                  URLTestFallbackOptions `json:"fallback,omitempty"`
}

type GroupCommonOption struct {
	Outbounds       []string          `json:"outbounds" reference:"outbound"`
	Providers       []string          `json:"providers" reference:"provider"`
	Exclude         *badoption.Regexp `json:"exclude,omitempty"`
	Include         *badoption.Regexp `json:"include,omitempty"`
	UseAllProviders bool              `json:"use_all_providers,omitempty"`
}

type URLTestFallbackOptions struct {
	Enabled  bool               `json:"enabled,omitempty"`
	MaxDelay badoption.Duration `json:"max_delay,omitempty"`
}

type LoadBalanceOutboundOptions struct {
	GroupCommonOption
	URL         string             `json:"url,omitempty"`
	Interval    badoption.Duration `json:"interval,omitempty"`
	IdleTimeout badoption.Duration `json:"idle_timeout,omitempty"`
	TTL         badoption.Duration `json:"ttl,omitempty"`
	Strategy    string             `json:"strategy,omitempty"`
	// AIBox: hash-dimension switches for consistent-hashing and
	// sticky-sessions. The legacy hash key is the destination only
	// (eTLD+1 domain preferred, else the resolved IP); source-IP and
	// port dimensions are opt-in so defaults match upstream behaviour.
	HashSourceIP      bool               `json:"hash_source_ip,omitempty"`
	HashDestinationIP bool               `json:"hash_destination_ip,omitempty"`
	HashPort          bool               `json:"hash_port,omitempty"`
	HashProtocol      bool               `json:"hash_protocol,omitempty"`
}
