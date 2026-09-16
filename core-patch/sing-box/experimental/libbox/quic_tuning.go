package libbox

import (
	"os"
	"sync"
)

// QUIC compatibility switches for Android.
//
// quic-go reads QUIC_GO_DISABLE_GSO / QUIC_GO_DISABLE_ECN lazily on every
// UDP connection creation (sys_conn_helper_linux.go), so a runtime
// os.Setenv reaches every new QUIC session without restarting the engine.
// Android cannot set process environment variables from outside, which is
// why the app calls these instead.
//
// GSO (generic segmentation offload) is known to break or add latency on
// some SoC/kernel combinations; ECN marking is dropped by some carrier
// NATs. Both default to enabled (quic-go's own default) and only need
// disabling when a device shows QUIC stalls.

var quicEnvMu sync.Mutex

// SetQuicGoGsoDisabled toggles QUIC_GO_DISABLE_GSO at runtime. Pass true to
// disable GSO (the compatibility-safe setting).
func SetQuicGoGsoDisabled(disabled bool) error {
	quicEnvMu.Lock()
	defer quicEnvMu.Unlock()
	return setQuicEnv("QUIC_GO_DISABLE_GSO", disabled)
}

// SetQuicGoEcnDisabled toggles QUIC_GO_DISABLE_ECN at runtime.
func SetQuicGoEcnDisabled(disabled bool) error {
	quicEnvMu.Lock()
	defer quicEnvMu.Unlock()
	return setQuicEnv("QUIC_GO_DISABLE_ECN", disabled)
}

func setQuicEnv(key string, disabled bool) error {
	if disabled {
		return os.Setenv(key, "true")
	}
	return os.Unsetenv(key)
}
