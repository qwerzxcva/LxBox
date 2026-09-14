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