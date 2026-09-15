#!/bin/sh
# Build libhev_tun.so (aibox JNI wrapper + hev-socks5-tunnel static lib)
# for arm64-v8a. Run from the repo root on a machine with the NDK; the
# checked-in jniLibs copy is what CI/Gradle consume — rebuild and commit
# only when updating the vendored hev sources under
# android/app/src/main/cpp/hev/.
set -eu

NDK="${ANDROID_NDK_HOME:-$HOME/.aicode/android-sdk/ndk/android-ndk-r29-aarch64/toolchains/llvm/prebuilt/linux-aarch64}"
CC="$NDK/bin/aarch64-linux-android24-clang"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HEV="$ROOT/android/app/src/main/cpp/hev"

echo "==> build third-party static libs"
for lib in hev-task-system yaml lwip; do
    (cd "$HEV/third-part/$lib" && CROSS_PREFIX="$NDK/bin/aarch64-linux-android24-" make -j4 >/dev/null)
done

echo "==> build libhev-socks5-tunnel.a"
(cd "$HEV" && CC="$CC" AR="$NDK/bin/aarch64-linux-android24-ar" \
    make static >/dev/null)

echo "==> link libhev_tun.so"
mkdir -p "$ROOT/android/app/src/main/jniLibs/arm64-v8a"
$CC -shared -O2 -fPIC -DANDROID -DENABLE_LIBRARY \
    -I"$HEV/src" -I"$HEV/src/misc" -I"$HEV/src/core/include" \
    -I"$HEV/third-part/yaml/include" \
    -I"$HEV/third-part/lwip/src/include" -I"$HEV/third-part/lwip/src/ports/include" \
    -I"$HEV/third-part/hev-task-system/include" \
    "$HEV/aibox-jni.c" \
    -L"$HEV/bin" -lhev-socks5-tunnel \
    -o "$ROOT/android/app/src/main/jniLibs/arm64-v8a/libhev_tun.so"

ls -l "$ROOT/android/app/src/main/jniLibs/arm64-v8a/libhev_tun.so"
