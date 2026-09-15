//go:build linux

package libbox

// AIBox-slim: the bridge service (L3 forwarding between interfaces) is not
// part of this fork's surface — the app never constructs one. Rejecting
// here instead of linking protocol/bridge keeps a meaningful slice of the
// Linux-only routing machinery out of the Android binary. Android builds
// satisfy the `linux` tag, so this file — not the stub — is what the
// Android artifact compiles.

import E "github.com/sagernet/sing/common/exceptions"

func NewBridgeService(options *BridgeOptions) (BridgeSession, error) {
	return nil, E.New("bridge service is not included in this build")
}
