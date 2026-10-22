# OpenEmux Mobile (POC)

An Android frontend for the RetroArch engine, with the OpenEmux look:
games as cartridges, a shelf per console, per-console defaults and a pause
sheet of its own. This POC covers **Game Boy Advance** and **Super Nintendo**.

This directory lives inside a local fork of RetroArch. It is not published
anywhere; the `upstream` remote can fetch from libretro but its push URL is
disabled on purpose.

## Why not reskin RetroArch's own UI

On Android, everything RetroArch shows is drawn in C by its menu drivers
(Ozone, XMB, MaterialUI) inside a `NativeActivity`; the Java side is only
glue (permissions, vibration, keyboard). Matching the design there would
mean writing a new menu driver in C. So the split is:

| Layer | What | Where |
|---|---|---|
| Library, settings, import | Kotlin + Jetpack Compose | `src/main/java/com/openemux/mobile/ui`, `data` |
| Launch | Writes a per-game `retroarch.cfg`, shader preset, core options and the controller skin, then starts the engine | `launch/GameLauncher.kt`, `launch/SkinRenderer.kt` |
| Engine | RetroArch's `NativeActivity`, unchanged except for two hooks | `game/GameActivity.kt` (extends `RetroActivityFuture`) |
| Pause sheet | Compose, in a dialog over the engine | `game/PauseSheet.kt` |

The engine runs in its own process (`:game`): RetroArch exits the process
when the content closes, and the library must survive that.

### The two engine hooks (C)

1. **Menu → frontend.** When RetroArch would open its menu during play
   (the skin's menu button, a gamepad's menu combo), it calls
   `onExternalMenuRequested()` on the activity first. If that returns true,
   the content is paused and the RetroArch menu stays closed.
   `android_app_external_menu()` in `frontend/drivers/platform_unix.c`,
   called from `runloop.c` and `retroarch.c`.
2. **System BACK.** The navigation bar's BACK (device -1) asks for the menu
   during play instead of being registered as a gamepad, which used to
   hide the touch skin. `android_input_back_to_menu()` in
   `input/drivers/android_input.c`.

Upstream behaviour is kept: the base `RetroActivityCommon` returns false,
so the stock RetroArch app still opens its own menu.

### Frontend → engine

The pause sheet talks to the running engine over RetroArch's network
command interface (`network_cmd_enable`, UDP 127.0.0.1:55355):
`SAVE_STATE_SLOT`, `LOAD_STATE_SLOT`, `FAST_FORWARD`, `SCREENSHOT`,
`SET_SHADER`, `MUTE`, `RESET`, `QUIT`, `GET_STATUS`. No JNI needed, and the
engine polls it on its own thread, paused or not. The sheet's window is
not focusable, because an unfocused engine stops polling.

### Controller skin

`SkinRenderer` draws the design's controller at the screen's exact pixel
size (portrait and landscape) and writes a RetroArch overlay with the touch
areas bound to RetroPad buttons and hotkeys (`menu_toggle`,
`toggle_fast_forward`, `save_state`). The cartridge being played is drawn
into the portrait skin, sitting in a slot under the picture. The picture is
pinned to the top with `video_viewport_bias_portrait_y = 0`.

### Cartridges

The frames are the desktop app's art (`OpenEmux/.../images/cartridges`);
the color variants, a colorize filter in the SVG, are baked into PNGs. The
cover is box art from the `openemux-artwork` mirror, matched by name
against the No-Intro list shipped in `assets/covers`.

### Shaders

GLSL presets from `libretro/glsl-shaders` (Dot, Sharp Bilinear, zfast CRT,
CRT Geom), applied per game through an auto `global.glslp` next to the
generated config; the pause sheet swaps them live with `SET_SHADER`.

## Building

Needs the Android SDK (platform 36), NDK 28 and JDK 17.

```sh
tools/build.sh              # engine (arm64-v8a, x86_64) + APK
```

`tools/fetch-cores.sh` downloads mGBA, gpSP, Snes9x and bsnes from the
RetroArch buildbot into `corelibs/` (not committed).

Data lives in `Android/data/com.openemux.mobile/files/` (`roms/<console>`,
`states`, `saves`, `system` for BIOS, `screenshots`).

## Not in this POC

- Cheats (shown as "Em breve").
- Downloading other cores; the four shipped are the choice.
- Skin editor or alternative skins; one skin per console.
- Real-device testing: everything was exercised on an x86_64 emulator;
  the arm64 build is produced but was not run on a phone.
