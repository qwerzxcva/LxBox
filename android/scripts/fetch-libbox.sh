#!/usr/bin/env bash
# Build the libbox AAR (arm64-v8a) from reF1nd/sing-box source into
# app/libs/libbox.aar.
#
# The core is NOT vendored and NOT downloaded: it is compiled from
# reF1nd/sing-box @ v1.15.0-alpha.3-reF1nd — the same source pin CI uses.
# There is no prebuilt release artifact to fetch, so this script needs a
# Linux host with:
#   * Go >= 1.25        (on the PATH)
#   * gomobile + gobind (sagernet fork v0.1.13; `go install` below)
#   * Android NDK       (ANDROID_NDK_HOME or ANDROID_HOME/ndk/<version>)
#
# Why arm64 only: ARMv8 is the sole shipped target; a single-ABI AAR is
# ~21 MB vs 83 MB for all four ABIs.
#
# Go 1.25 note: two files linkname into runtime internals that newer Go
# toolchains no longer export (pidfd_android.go, oomprofile). They are
# stubbed out in a scratch clone before binding; badlinkname must NOT be
# enabled.

set -euo pipefail

REF_TAG="v1.15.0-alpha.3-reF1nd"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
OUT_FILE="$OUT_DIR/libbox.aar"
SRC_DIR="${LIBBOX_SRC:-/tmp/refsrc}"

if ! command -v go >/dev/null; then
    echo "ERROR: go not on PATH (need >= 1.25)"; exit 1
fi
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ] && [ -n "${ANDROID_HOME:-}" ]; then
    NDK="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "ERROR: Android NDK not found (set ANDROID_NDK_HOME)"; exit 1
fi
export ANDROID_NDK_HOME="$NDK"

if [ ! -d "$SRC_DIR" ]; then
    echo "→ clone reF1nd/sing-box @ $REF_TAG"
    git clone --depth 1 -b "$REF_TAG" https://github.com/reF1nd/sing-box.git "$SRC_DIR"
fi
cd "$SRC_DIR"
# shallow clone already carries the tag; a plain 'git tag' would 128-exit
git tag -f "$REF_TAG" 2>/dev/null || true

echo "→ stub Go-1.25-incompatible linkname files"
rm -f experimental/libbox/pidfd_android.go
printf '%s\n' 'package libbox' '' '// Stub: the original file linknamed os.checkPidfdOnce, which newer' '// Go toolchains no longer export. pidfd is not required here.' > experimental/libbox/pidfd_android.go
rm -rf experimental/libbox/internal/oomprofile
mkdir -p experimental/libbox/internal/oomprofile
printf '%s\n' '//go:build darwin || linux || windows' '' 'package oomprofile' '' 'import "errors"' '' 'func WriteFile(_ string, _ string) error {' '  return errors.New("oom profiling disabled in this build")' '}' > experimental/libbox/internal/oomprofile/oomprofile.go

echo "→ install sagernet gomobile v0.1.13"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13

echo "→ gomobile bind (arm64-v8a only, SDK 24)"
"$(go env GOPATH)/bin/gomobile" bind \
    -o "$OUT_FILE" \
    -target=android/arm64 \
    -androidapi 24 \
    -javapkg=io.nekohasekai \
    -libname=box \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=$REF_TAG -s -w" \
    -tags "with_quic,with_utls,with_clash_api,tfogo_checklinkname0" \
    ./experimental/libbox

echo "OK: $(du -h "$OUT_FILE" | cut -f1) → $OUT_FILE"
