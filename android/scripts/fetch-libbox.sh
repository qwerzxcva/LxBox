#!/usr/bin/env bash
# Download the libbox AAR (sing-box-lx core) into app/libs/libbox.aar.
#
# Source: Leadaxe/sing-box-lx GitHub releases — the same core the Flutter
# client pins via app/android/libbox.version. That fork carries the
# client-side extensions this app relies on:
#   * DNS server group  (dns.servers[].type: "group", modes stable/fastest/parallel)
#   * XHTTP transport   (VLESS/VMess/Trojan transport.type: "xhttp")
#   * AmneziaWG 2.0     (wireguard endpoint obfuscation fields)
#   * VLESS encryption  (post-quantum layer)
# The AAR is a local-only artifact (gitignored); CI re-runs the download on
# its own runner.
#
# Version pin: LXCORE_VERSION below. Keep it in sync with the Flutter-side
# pin (app/android/libbox.version) when both clients ship the same core.

set -euo pipefail

LXCORE_VERSION="${LIBBOX_TAG:-v1.14.0-lx.38}"
AAR_NAME="libbox-${LXCORE_VERSION#v}.aar"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
OUT_FILE="$OUT_DIR/libbox.aar"

mkdir -p "$OUT_DIR"

URLS=(
    "https://github.com/Leadaxe/sing-box-lx/releases/download/${LXCORE_VERSION}/${AAR_NAME}"
    "https://gh-proxy.com/https://github.com/Leadaxe/sing-box-lx/releases/download/${LXCORE_VERSION}/${AAR_NAME}"
)

for url in "${URLS[@]}"; do
    echo "→ $url"
    if curl -fSL --retry 3 --retry-all-errors -o "$OUT_FILE" "$url"; then
        # Validate by magic bytes (AAR is a ZIP: PK\x03\x04). Avoids relying
        # on `file` (not installed on minimal containers) or on the
        # Content-Type header (some mirrors return octet-stream for zips).
        SIZE=$(stat -c%s "$OUT_FILE" 2>/dev/null || wc -c <"$OUT_FILE")
        HEAD=$(head -c4 "$OUT_FILE" | od -An -tx1 | tr -d ' \n')
        if [ "$HEAD" = "504b0304" ] && [ "$SIZE" -gt 50000000 ]; then
            echo "OK: $(du -h "$OUT_FILE" | cut -f1) → $OUT_FILE"
            exit 0
        fi
        echo "  download ok but file looks invalid (head=$HEAD size=$SIZE); trying next mirror"
    else
        echo "  failed; trying next mirror"
    fi
done

echo "ERROR: could not download libbox.aar; place it manually at $OUT_FILE"
exit 1