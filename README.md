# andClaw

andClaw turns an Android phone into an on-device AI gateway host.
It runs OpenClaw inside a `proroot` Ubuntu arm64 environment and provides a Jetpack Compose UI for setup, onboarding, pairing, and runtime control.

Google Play: https://play.google.com/store/apps/details?id=com.coderred.andclaw

## Features

- One-tap setup for rootfs, Node.js, system tools, OpenClaw, and Playwright Chromium
- OpenRouter OAuth onboarding or manual API-key setup
- Gateway lifecycle control from the Android UI
- Provider and model configuration for OpenRouter, OpenAI, Anthropic, Google, and OpenAI Codex mode
- Messaging channel integration for WhatsApp, Telegram, and Discord
- WhatsApp QR pairing flow from inside the app
- Runtime recovery support through foreground service, boot auto-start, app-update restart, and watchdog recovery
- Play Asset Delivery support for large install-time assets

## Requirements

- Android Studio / Gradle environment
- Java 11
- Docker for `scripts/setup-assets.sh`
- arm64 Android device (minimum SDK 26)

## Project Layout

- `app/` - Android app module (Kotlin + Jetpack Compose)
- `app/src/main/java/com/coderred/andclaw/` - app code by feature area (`ui/`, `data/`, `proroot/`, `service/`, `receiver/`, `auth/`)
- `app/src/test/` - JVM unit tests
- `app/src/androidTest/` - instrumentation tests
- `install_time_assets/` - Play Asset Delivery install-time asset pack
- `scripts/setup-assets.sh` - prepares `jniLibs` and bundled runtime assets

## Build

```bash
# 1) Prepare assets (required on first build or when refreshing bundles)
# Run this outside the sandbox because it needs Docker and network access.
./scripts/setup-assets.sh

# 2) Debug APK (compat alias for prod debug flow)
./gradlew assembleDebug

# 3) Recommended production release AAB
./gradlew bundleProdRelease
```

Artifacts:

- Recommended release AAB: `app/build/outputs/bundle/prodRelease/app-prod-release.aab`
- Legacy release path still supported by helper scripts: `app/build/outputs/bundle/release/app-release.aab`

## Install (Debug)

```bash
./gradlew installProdDebug
```

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

## 16KB Page-Size Compatibility

Google Play requires 16KB page-size compatibility for Android 15+ targets. `scripts/setup-assets.sh` verifies bundled native binaries before packaging.

- `scripts/setup-assets.sh` checks `app/src/main/jniLibs/arm64-v8a/*.so` LOAD segment alignments.

## Open-Source Notices

See `THIRD_PARTY_LICENSES.md` for key third-party runtime components and distribution notes.
