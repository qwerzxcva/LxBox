# LxBox Android

Native Android client built on [sing-box-lx](https://github.com/Leadaxe/sing-box-lx).
This directory holds a fresh, from-scratch Kotlin + Jetpack Compose rewrite that
sits next to the existing Flutter project (`app/`).

## Build prerequisites

- JDK 17 (`apt-get install -y openjdk-17-jdk`)
- Android SDK with `build-tools/35.0.0` and `platforms/android-36`
- A copy of `libbox.aar` placed under `app/libs/libbox.aar`. The AAR is **not**
  vendored — CI downloads it from upstream
  [Asterisk4Magisk/AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite/releases);
  local developers either download a release manually or run the helper:
  ```bash
  bash scripts/fetch-libbox.sh
  ```
  (the script writes to `app/libs/libbox.aar`, which is gitignored).
- Gradle 8.x. If you don't have it globally, use the wrapper script that the
  project checks in via `gradle/wrapper/` (run `gradle wrapper` once to generate
  it locally, then `./gradlew assembleDebug`).

## Build

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
gradle :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### arm64 dev machines

AGP downloads an x86_64-only `aapt2` from Maven; on arm64 hosts (Raspberry Pi,
Asahi Linux, ARM Chromebook, native-arm64 WSL, Ampere servers) this fails with
`error=2, No such file or directory`. Point AGP at the arm64 binary that ships
with the Android SDK build-tools via your user-level Gradle properties:

```bash
mkdir -p ~/.gradle
printf "\nandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2\n" \
    >> ~/.gradle/gradle.properties
```

CI runners on x86_64 do not need this and leave the property unset.

## Project layout

- `app/src/main/kotlin/com/leadaxe/lxbox/`
  - `LxBoxApp.kt` — `Application` subclass, owns `AppStateStore` and notification channels
  - `app/` — `AppState` JSON model + persistent store + `MainActivity`
  - `engine/singbox/` — `ConfigCompiler` (turns `AppState` into sing-box JSON config)
  - `engine/vpn/` — `BoxEngine`, `LxPlatform`, `LxVpnService`, `BoxController`, `BoxState`
  - `engine/share/` — share-link parser (vless/vmess/trojan/ss/hy2/tuic + JSON outbounds) and subscription fetcher
  - `ui/` — Compose theme + bottom bar + five screens (Home / Subscriptions / Routes / DNS / Settings)
- `app/src/main/AndroidManifest.xml` — declares `MainActivity` and the `LxVpnService` foreground service