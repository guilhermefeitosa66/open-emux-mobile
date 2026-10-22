# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## This fork: OpenEmux Mobile

This is a fork of RetroArch (public at https://github.com/guilhermefeitosa66/open-emux-mobile,
branch `openemux-mobile`) whose own work lives in `pkg/android/openemux/`: an
Android frontend (Kotlin + Jetpack Compose) on top of the unchanged RetroArch engine.
`pkg/android/openemux/README.md` is the design reference; read it before changing the
launch flow, the pause sheet or the engine hooks.

- The `upstream` remote fetches from libretro; its push URL is disabled on purpose.
  Never re-enable it or push upstream.
- Branch `openemux-mobile`; commits are prefixed `OpenEmux Mobile:` / `OpenEmux mobile:`.
- Commit messages and code comments in English. User-facing UI strings are
  string resources (`res/values*/strings_*.xml`): English is the base, plus de, ja, fr, zh-rCN,
  pt-rBR, es and ta, the desktop's languages. New text goes into every locale.

### Build

Needs Android SDK platform 36, NDK 28 (`~/Android/Sdk/ndk/28.2.13676358` by default,
override with `NDK=`) and JDK 17. From `pkg/android/openemux/`:

```sh
tools/build.sh          # ndk-build the engine (arm64-v8a, x86_64) in ../phoenix-common, then assembleDebug
./gradlew assembleDebug # Kotlin-only change: skip the engine rebuild
```

Any change to C code (the engine hooks) needs the ndk-build step; Gradle only picks up
the prebuilt `libretroarch-activity.so` from `../phoenix-common/libs`. There are no tests
for the Android frontend; it has only been exercised on an x86_64 emulator.

`local.properties` (untracked) holds the ScreenScraper dev credential
(`screenscraper.devid`, `screenscraper.devpassword`), injected as `BuildConfig.SS_*`.

### Architecture

- **Gradle reuses RetroArch's Android Java glue.** `build.gradle.kts` adds
  `../phoenix/src`, `../phoenix-common/src`, `../play-core-stub` and the SAF VFS sources
  as source dirs, so `namespace` must stay `com.retroarch` (those sources import
  `com.retroarch.R` / `BuildConfig`); the `applicationId` is `com.openemux.mobile`.
- **Two processes.** `MainActivity` (library, settings, import) runs in the main
  process; `game/GameActivity` (extends `RetroActivityFuture`, a `NativeActivity`) runs in
  `:game`, because RetroArch exits its process when content closes.
- **Launch = generated config.** `launch/GameLauncher.kt` writes a per-game
  `retroarch.cfg`, shader preset (`global.glslp`), core options and the controller
  overlay (drawn by `launch/SkinRenderer.kt` at the exact screen size), then starts
  `GameActivity` with `OE_*` intent extras (`GameExtras` in `game/RaCommand.kt`).
- **Frontend → engine** goes over RetroArch's network command interface, UDP
  127.0.0.1:55355 (`game/RaCommand.kt`), not JNI. The pause sheet's window must stay
  non-focusable: an unfocused engine stops polling commands.
- **Engine → frontend** is two small C hooks, the only engine changes:
  `android_app_external_menu()` (`frontend/drivers/platform_unix.c`, called from
  `runloop.c` / `retroarch.c`) asks the activity's `onExternalMenuRequested()` before
  opening RetroArch's menu, and `android_input_back_to_menu()`
  (`input/drivers/android_input.c`) turns system BACK into a menu request;
  `android_app_overlay_changed()` tells the activity's `onOverlayChanged()` which skin
  overlay is shown (the NDS screen layouts follow it). The base
  `RetroActivityCommon` returns false, so stock RetroArch behaviour is preserved; keep
  it that way, and keep these hooks under the C89/AGENTS.md rules.
- `data/` holds the library model (`Console`, `Library`, `Importer`), cover matching
  against the No-Intro lists in `assets/covers` (`Covers`) and ScreenScraper label sync
  (`Labels`). Runtime data lives under `Android/data/com.openemux.mobile/files/`.
- **No cores in the APK.** `data/Setup.kt` downloads them (device ABI), `info.zip` and the
  PPSSPP/Dolphin system files from the buildbot on first open; the UI stays on
  `ui/SetupScreen.kt` until it finishes.
