#!/usr/bin/env bash
# Download the libbox AAR into app/libs/libbox.aar.
# This is a local-only artifact (gitignored); CI re-runs the download on its
# own runner.

set -euo pipefail

RELEASE_TAG="${LIBBOX_TAG:-v1.15.0-alpha.3-reF1nd}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
OUT_FILE="$OUT_DIR/libbox.aar"

mkdir -p "$OUT_DIR"

URLS=(
    "https://github.com/Asterisk4Magisk/AndroidLibBoxLite/releases/download/${RELEASE_TAG}/libbox.aar"
    "https://gh-proxy.com/https://github.com/Asterisk4Magisk/AndroidLibBoxLite/releases/download/${RELEASE_TAG}/libbox.aar"
)

for url in "${URLS[@]}"; do
    echo "→ $url"
    if curl -fSL --retry 3 --retry-all-errors -o "$OUT_FILE" "$url"; then
        if file --mime-type "$OUT_FILE" 2>/dev/null | grep -q 'application/zip\|application/octet-stream'; then
            echo "OK: $(du -h "$OUT_FILE" | cut -f1) → $OUT_FILE"
            exit 0
        fi
        echo "  download ok but file type unexpected; trying next mirror"
    else
        echo "  failed; trying next mirror"
    fi
done

echo "ERROR: could not download libbox.aar; place it manually at $OUT_FILE"
exit 1