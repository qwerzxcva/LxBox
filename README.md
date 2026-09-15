# AIBox

A native Android proxy client powered by [sing-box](https://github.com/SagerNet/sing-box)
(reF1nd build). Kotlin + Jetpack Compose, vless-family and shadowsocks
protocols, per-app proxy, DNS groups with parallel resolution, fake-IP pool,
and a fully editable rule engine.

## Download

Grab the ARMv8 (arm64-v8a) APK from the
[Releases page](https://github.com/qwerzxcva/LxBox/releases) — ARMv8 is the
only supported ABI.

## Build

Requirements: JDK 17, Android SDK (build-tools 35.0.0, platform android-36),
and the Go/NDK toolchain only if you need to rebuild the core yourself (CI
builds it automatically).

```bash
cd android
./gradlew :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.

### Core (libbox.aar)

The sing-box core is built from
[reF1nd/sing-box @ v1.15.0-alpha.3-reF1nd](https://github.com/reF1nd/sing-box/releases/tag/v1.15.0-alpha.3-reF1nd)
and is **not** vendored — CI compiles it from source on every run
(`Build libbox AAR from reF1nd source` step). Local builds can reuse the same
steps or drop a prebuilt AAR at `app/libs/libbox.aar` (gitignored).

Only four build tags are enabled: `with_quic`, `with_utls`, `with_clash_api`,
`tfogo_checklinkname0`. Everything else — wireguard, tailscale, naive, usbip,
openvpn, openconnect, ebpf, **gvisor** — is compiled out. The TUN uses
sing-tun's own Go stack (the gvisor/mixed/system stacks are deprecated since
1.15 and their runtime was removed with the `with_gvisor` tag, which also
dropped ~26% off the core AAR).

## Project layout

```
android/
  app/src/main/kotlin/com/leadaxe/aibox/
    AIBoxApp.kt           Application: state store, notification channels, VpnRelay
    app/                  AppState model + JSON store + MainActivity + navigation
    engine/singbox/       ConfigCompiler: AppState -> sing-box JSON
    engine/vpn/           BoxEngine, AIVpnService (:vpn process), AIPlatform,
                          BoxController, NetworkMonitor, VpnIpc broadcast bridge
    engine/share/         Share-link parser (vless/ss) + subscription fetcher
    ui/                   Compose theme, localized app shell, five screens
  scripts/fetch-libbox.sh Local helper to fetch/build the core AAR
.github/workflows/
  android.yml             Release APK: core build from source + assembleRelease
```

## Features

* vless (ws / grpc / http / reality / xhttp-family transports) and shadowsocks
* Subscriptions: folders, dedupe, direct/proxy/auto fetch paths, DoH-pinned
  resolution, client impersonation (User-Agent / TLS fingerprint / x-hwid)
* Routing rules: inline matchers + logical AND/OR groups, raw JSON, managed
  rule sets (.srs) with auto-update, drag-free reorder, per-field hints
* DNS: typed servers (udp/tcp/tls/https/quic/h3/local) with per-server exit,
  DNS rules, parallel DNS groups, explicit fallback resolver, fake-IP pool
  (IPv4 + IPv6, whitelist/blacklist filter)
* Outbound groups: manual selector, latency urltest, native fallback mode
* TUN: custom IPv4/IPv6 addresses and system DNS, MTU slider
* English and Simplified Chinese UI
