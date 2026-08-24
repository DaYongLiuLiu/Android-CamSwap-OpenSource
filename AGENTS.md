# AGENTS Guide

## Purpose
- This repository is a single Android application module, `:app`, that also ships an Xposed/LSPosed module.
- The codebase mixes Kotlin, Java, Jetpack Compose, XML resources, Gradle Groovy, JNI, and native C++ hooks.
- Prefer practical, low-churn edits that match the file you are touching instead of broad cleanups.
- Treat host-app code and injected hook code as one system: changes in one half often affect the other.

## Rule file findings
- Project documentation is centralized in `docs/` (`docs/plans/`, `docs/issues/`, `docs/logs/`, `docs/README.md`).
- There is no `.cursorrules` or `.github/copilot-instructions.md` file in this checkout.
- The repository-specific guidance for agents lives in this file.

## Repository snapshot
- Gradle root name is `VCAM`, but the Android package is `io.github.zensu357.camswap`.
- The only Gradle module is `:app`.
- Host UI entry point: `app/src/main/java/io/github/zensu357/camswap/MainActivity.kt`.
- Xposed entry declaration: `app/src/main/assets/xposed_init`.
- Xposed entry class: `io.github.zensu357.camswap.HookMain`.
- Provider authority: `io.github.zensu357.camswap.provider`.
- Config and media are centered around `DCIM/Camera1/`, with config file `cs_config.json`.
- Documentation hub: `docs/README.md`.

## Architecture notes
- `HookMain` is the injected runtime entry point and shared state holder for camera, audio, renderer, and config coordination.
- `Camera1Handler` and `Camera2Handler` install camera hooks; `Camera2SessionHook` manages Camera2 session redirection.
- `MediaPlayerManager`, `GLVideoRenderer`, and `SurfaceRelay` own playback and rendering lifecycles for fake preview output.
- `ScreenColorDetector` provides dynamic ambient screen color flash detection and GL injection for liveness defense.
- `MicrophoneHandler` handles Java-side audio interception; `NativeAudioHook` bridges to JNI for OpenSL ES and AAudio interception.
- `ConfigManager` is the configuration core (ContentProvider first, file fallback); `ConfigWatcher` keeps runtime state fresh.
- `VideoProvider` and `ConfigReceiver` support cross-process config and media delivery.
- `CameraServerBridge`, `cs_camserver` (native so), and `cs-injector` (root binary) provide the Scheme 2 bottom-layer CameraServer injection pipeline.
- `ResidualCleaner` provides risk residual and leftover path scanning and cleanup.
- Host UI is Compose-based and lives under `app/src/main/java/io/github/zensu357/camswap/ui/`.

## Toolchain and versions
- Gradle wrapper: 8.11.1.
- Android Gradle Plugin: 8.9.0.
- Kotlin Gradle plugin: 2.1.0 (with Compose Compiler plugin).
- `compileSdk` and `targetSdk`: 36.
- `minSdk`: 26.
- NDK: 25.1.8937393.
- Native build uses CMake 3.22.1.
- Native code is compiled as C++17 with `-fno-exceptions -fno-rtti`.

## Build commands
- Recommended JDK: **JDK 21** (`C:\Program Files\Java\jdk-21`). Set `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"` if default JDK differs.
- On Windows use `gradlew.bat`; on Unix-like shells use `./gradlew`.
- Build debug APK: `gradlew.bat assembleDebug`.
- Build only the app debug variant: `gradlew.bat :app:assembleDebug`.
- Build release APK: `gradlew.bat assembleRelease`.
- Run full clean + build: `gradlew.bat clean assembleDebug`.
- Run JVM unit tests: `gradlew.bat :app:testDebugUnitTest`.
- Run one JVM test class: `gradlew.bat :app:testDebugUnitTest --tests "io.github.zensu357.camswap.ConfigManagerTest"`.
- APK outputs land under `app/build/outputs/apk/`.

## Test reality check
- JVM tests exist under `app/src/test/java/io/github/zensu357/camswap/` and cover `ConfigManagerTest` and `VideoProviderTest`.
- Instrumentation tests under `app/src/androidTest/` are currently stale.

## Important gotchas
- JDK requirement: Use **JDK 21**. Newer preview JDKs (e.g. JDK 26) may fail on TLS handshake or Gradle daemon compatibility.
- The native build depends on the Dobby submodule at `app/src/main/cpp/third_party/Dobby`. If missing, run `git submodule update --init --recursive`.
- Native CMake targets include `camswap-native-hook` (OpenSL/AAudio), `cs_camserver` (CameraServer hook), and `cs-injector` (root binary).
- In Android Bionic NDK, shared memory mapping must use standard POSIX `open()` and `mmap()`, not `shm_open()`.
- `MainActivity.isModuleActive()` intentionally returns `false`; Xposed hooks it to return `true` at runtime for self-checks. Do not hardcode `true`.
- Runtime logs use `LogUtil` and standard UTF-8 Chinese tags (`【CS】...`). Keep existing logging style.
- The module uses external storage (`DCIM/Camera1/`) and legacy fallbacks by design.

## Editing guidance
- Read the surrounding file first and follow the local style.
- Keep changes tight and targeted; avoid unnecessary renames or package moves.
- Preserve package names, config keys, intent actions, provider authorities, and Xposed hook signatures.
- Indentation: 4 spaces for Kotlin, Java, Groovy, XML, C++.
- Fail safely in hooks: prefer logging and graceful fallback over crashing the target application.

## Native C++ guidance
- Native sources live under `app/src/main/cpp/`.
- Keep C++ code compatible with C++17 and `-fno-exceptions -fno-rtti`.
- Link against `dobby`, `log`, `dl`, `android`, and `OpenSLES` where needed.
- Do not edit vendored Dobby submodule sources unless explicitly required.

## Validation expectations
- For any code changes, verify with `gradlew.bat :app:assembleDebug` and `gradlew.bat :app:testDebugUnitTest`.
