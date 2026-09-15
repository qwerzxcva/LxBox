//go:build linux || android || darwin || ios

package libbox

// AIBox-slim: shell sessions ride the tailscale implementation upstream
// (`protocol/tailscale/tailssh`), which this fork does not ship. The app's
// PlatformInterface never opens one, so the entry points reject instead of
// linking the SSH stack (a meaningful slice of the QUIC/Tailscale graph).

import (
	E "github.com/sagernet/sing/common/exceptions"
)

func OpenNativeShellSession(
	shell, cwd string,
	args, environ StringIterator,
	term string,
	rows, cols, uid, gid int32,
	groups Int32Iterator,
) (ShellSession, error) {
	return nil, E.New("shell sessions are not included in this build")
}

func OpenNativePipeSession(
	shell, cwd string,
	args, environ StringIterator,
	uid, gid int32,
	groups Int32Iterator,
) (ShellSession, error) {
	return nil, E.New("shell sessions are not included in this build")
}
