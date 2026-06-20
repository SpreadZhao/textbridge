---
name: android-dev
description: Use in this TextBridge Android project when working on Gradle builds, Android SDK setup, adb devices, APK installation, logs, or app packaging.
---

# TextBridge Android Dev

Use this skill for Android work in the TextBridge repository.

## Environment

- Enter the repository root environment with `nix develop` or `direnv allow`.
- The default shell is the full TextBridge development environment: Python server tools, Fcitx5 addon tools, JDK, Gradle, Android SDK, platform-tools, `android-cli`, and project Android skills.
- The production outputs stay separate from the development shell. NixOS consumers should use the package/module outputs; Android development-only tools are not part of the server or Fcitx5 package closures.
- Android skills are installed by the dev shell from a pinned fixed-output source, not from a flake input. This keeps downstream NixOS `flake.lock` files from inheriting Android skills when they only consume TextBridge products.
- This project has no Gradle wrapper; use the shell's `gradle` package.
- `ANDROID_HOME` and `ANDROID_SDK_ROOT` are selected by the dev shell:
  - Prefer `${XDG_LIB_HOME:-$HOME/Lib}/Android/Sdk` when it contains `platforms/android-37.0` and `build-tools/37.0.0`.
  - Fall back to the Nix-provided Android SDK so `textbridge/android` still builds reproducibly.
  - The shell adds `$ANDROID_HOME/platform-tools`, the required build-tools, and `$ANDROID_HOME/cmdline-tools/latest/bin` to `PATH`.

## Checks

Run `scripts/android-doctor` when Android tooling behaves unexpectedly. It prints the selected SDK path, verifies required SDK components, checks `adb`, lists devices, checks `android --version` when available, and checks Gradle.

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

Entering the dev shell runs `install-android-skills`, which installs project-local Android skill symlinks under `.agents/skills`.

Official Android skills are tracked in `.agents/skills/.android-skills-managed`. Do not edit that manifest by hand unless you are repairing stale symlinks.

## Boundaries

Do not install or update Android SDK packages globally unless the user asks. If the external Android Studio SDK is missing a component, report the exact missing path first; the dev shell can still use the Nix SDK fallback.
