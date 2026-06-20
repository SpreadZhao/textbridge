---
name: android-dev
description: Use in this TextBridge Android project when working on Gradle builds, Android SDK setup, adb devices, APK installation, logs, or app packaging.
---

# TextBridge Android Dev

Use this skill for Android work in the TextBridge repository.

## Environment

- The repository root `nix develop` shell is for TextBridge desktop outputs only: Python server, Fcitx5 addon, adb helper packaging, and the NixOS module.
- The TextBridge production flake intentionally does not provide Android SDK, Gradle, Android skills, or `android-cli`. This avoids leaking Android development-only inputs into downstream NixOS `flake.lock` files that only consume the server/module outputs.
- For Android app work, enter a development environment created from spreadconfig's Android flake template, or use an external Android Studio SDK environment that provides JDK, Gradle, Android SDK, and platform-tools.
- This project has no Gradle wrapper; use the `gradle` command supplied by that Android development environment.
- `ANDROID_HOME` and `ANDROID_SDK_ROOT` must point at an SDK containing the platform/build-tools required by `textbridge/android`.

## Checks

Run `scripts/android-doctor` from the Android development environment when Android tooling behaves unexpectedly. It prints the selected SDK path, verifies required SDK components, checks `adb`, lists devices, checks `android --version` when available, and checks Gradle.

Useful commands:

```bash
scripts/android-doctor
android info
android docs search <keywords>
adb devices
gradle -p textbridge/android testDebugUnitTest
gradle -p textbridge/android assembleDebug
adb install -r textbridge/android/app/build/outputs/apk/debug/app-debug.apk
```

## Skills

Official Android agent skills are development tooling. Keep them in the Android development template/environment, not in the TextBridge production flake.

## Boundaries

Do not install or update Android SDK packages globally unless the user asks. If the active Android development environment is missing a component, report the exact missing path first.
