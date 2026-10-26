# OpenEmux Mobile

An Android frontend for the RetroArch engine, with the OpenEmux look:
games as cartridges, a shelf per console, per-console defaults and a pause
sheet of its own. It covers the desktop app's 31 systems (`data/Console.kt`),
each with its own controller skin (`data/Skin.kt`).

This directory lives inside a fork of RetroArch, published at
<https://github.com/guilhermefeitosa66/open-emux-mobile> (branch
`openemux-mobile`); the `upstream` remote can fetch from libretro but its
push URL is disabled on purpose.

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

### The engine hooks (C)

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
3. **Gamepad in the pause sheet.** While the content is paused, gamepad
   keys and sticks go to the activity's `onExternalMenuInput()` first; the
   sheet's window cannot take focus (the engine must keep it), so the
   activity drives the sheet with them. `android_input_to_external_menu()`
   in `input/drivers/android_input.c`, `android_app_external_menu_input()`
   in `frontend/drivers/platform_unix.c`.
4. **Overlay shown.** When the overlay changes (a skin key, or turning the
   phone), the activity's `onOverlayChanged()` gets its name. The NDS skin
   has one overlay per screen layout (`portrait@side`...): the activity sets
   DeSmuME's `desmume_screens_layout` to match through `SET_CORE_OPTION`
   and keeps each orientation's last layout. `android_app_overlay_changed()`
   in `frontend/drivers/platform_unix.c`, called from `CMD_EVENT_OVERLAY_NEXT`
   in `retroarch.c`.
5. **Toggles lit.** An overlay desc can name a frontend state its image
   shows (`overlayN_descM_lit_by = "fast_forward"` or `"turbo"`): the image
   is hidden while that state is off, with or without the overlay LED
   driver (`input/input_overlay_alpha.c`). When either state changes, the
   activity's `onToggleStates()` hears it once, so the pause sheet shows the
   engine's fast forward. `android_app_toggle_states()` in
   `frontend/drivers/platform_unix.c`, called from `input_overlay_states()`
   in `input/input_driver.c` on every input poll, whether or not an overlay
   is shown (a connected gamepad hides it).
6. **D-pad dead zone.** An eight-way area (`dpad_area`, `abxy_area`) takes
   `overlayN_descM_dead_zone`, a radius as a fraction of the area's range
   inside which the touch holds the area but presses no direction. The
   skin's D-pad sets 0.25; the default, 0, is upstream behaviour.
   `task_overlay_desc_populate_eightway_config()` in `tasks/task_overlay.c`,
   `input_overlay_get_eightway_state()` in `input/input_driver.c`.

Upstream behaviour is kept: the base `RetroActivityCommon` returns false
from the first two and ignores the overlay change and the toggles, so the stock RetroArch
app still opens and drives its own menu.

Two config-only keys (not in the menu; their defaults keep upstream
behaviour) are set by the generated `retroarch.cfg`:
`input_turbo_locked = true` makes turbo, once toggled, act as if its bind
were held (the skin's bolt), and `notification_show_autoload_state = false`
mutes the message when Continuar loads the automatic state, while saving and
loading from the action strip still report.

### Frontend → engine

The pause sheet talks to the running engine over RetroArch's network
command interface (`network_cmd_enable`, UDP 127.0.0.1:55355):
`SAVE_STATE_SLOT`, `LOAD_STATE_SLOT`, `FAST_FORWARD`, `SCREENSHOT`,
`SET_SHADER`, `MUTE`, `RESET`, `QUIT`, `GET_STATUS`. No JNI needed, and the
engine polls it on its own thread, paused or not. The sheet's window is
not focusable, because an unfocused engine stops polling.

### Controller skin

`data/Skin.kt` holds each console's skin as the design draws it: a layout
family (D-pad + A/B, + A/B/X/Y + L/R, 6 buttons, analog, two screens), the
console's colors, its face buttons, shoulders and console keys, each bound to
a RetroPad button, an analog stick or a hotkey. `SkinRenderer` draws it at
the screen's exact pixel size (portrait and landscape) and writes a RetroArch
overlay with those touch areas, plus `menu_toggle` and the quick actions
(`save_state`, `load_state`, `turbo_fire_toggle`, `toggle_fast_forward`).
Turbo and fast forward each get a `nul` desc over their button with the
"on" pill as its image and `_lit_by` naming the state, so the engine shows
the pill exactly while the toggle is on (never fast forward's in
RetroAchievements hardcore, where its button is greyed out).
Each overlay also sets where the game is drawn (`overlayN_viewport`, which
RetroArch fits keeping the aspect): on top in portrait, capped like the
design, and centred between the control sides in landscape, with the quick
actions under the picture in both.

The twelve best-known consoles (NES, SNES, N64, GameCube, Game Boy, Game Boy
Color, Game Boy Advance, NDS, Mega Drive, Master System, PlayStation, PSP)
use skins with drawn art instead, in the package format of the iOS
emulators Delta and Manic EMU (`.deltaskin`/`.manicskin`: a zip with
`info.json` and the art). They ship in `assets/skins` and
`launch/ImportedSkinRenderer.kt` turns one into the same kind of overlay:
the phone or tablet art (tablet from 600 dp on the smallest side) scaled to
the screen, the game's screens cut out of it as the viewport, and each item
a touch area, with each console's button names translated to the RetroPad
as `data/Skin.kt` does. A skin in `files/skins/<console>/` takes the
bundled one's place, and one that fails to load falls back to the drawn
skin. The NDS skin draws its two screens one over the other, and DeSmuME is
set to that layout. An item bound to turbo or toggle fast forward gets the
same lit `nul` desc as the drawn strip: the accent pill over the item's
frame, with the skin's own glyph lifted off the art (what differs from the
colour around it) and drawn on it in white, so a user's skin lights up too.
`launch/OverlayWriter.kt` writes the overlays and their images for both
renderers, each stick's movable knob (`<overlay>-knob<N>.png`) and its
widened touch zone included.

The art comes from a design canvas whose Skin component holds the layout
engine and each console's colors; `tools/skins/build.py` renders it with
headless Chrome into the packages (see `tools/skins/README.md`).

### Cartridges, labels and box art

Two different assets, as on the desktop app:

- **Labels** go inside the cartridge frame (the desktop app's art,
  `OpenEmux/.../images/cartridges`; the color variants, a colorize filter in
  the SVG, are baked into PNGs). They come from ScreenScraper's
  `support-texture` media, looked up by ROM hash, one request a second, and
  are kept in `files/labels/<console>/` (a `.none` file marks a game with no
  label). A cartridge without one shows a blank label with the title.
- **Box art** is shown in the covers and list modes, at its own aspect ratio.
  It comes from the `openemux-artwork` mirror, matched by name against the
  No-Intro list shipped in `assets/covers`, and is prefetched into the image
  cache.

Both syncs run in the background with a progress pill on the shelf and the
library (`ui/ArtworkSync.kt`); they pause without a connection or when the
ScreenScraper quota runs out, and resume on their own.

ScreenScraper needs a developer credential: put `screenscraper.devid` and
`screenscraper.devpassword` in `local.properties` (untracked). Without it,
cartridges keep blank labels.

### Shaders

GLSL presets from `libretro/glsl-shaders` (Dot, Sharp Bilinear, zfast CRT,
CRT Geom), applied per game through an auto `global.glslp` next to the
generated config; the pause sheet swaps them live with `SET_SHADER`.

## Building

Needs the Android SDK (platform 36), NDK 28 and JDK 17.

```sh
tools/build.sh              # engine (arm64-v8a, x86_64) + APK
./gradlew testDebugUnitTest --no-daemon   # JVM unit tests (src/test)
```

The APK ships no cores. On first open, `data/Setup.kt` downloads from the
RetroArch buildbot every core `data/Console.kt` names (for the device's ABI),
`info.zip` and the PPSSPP and Dolphin system files, and the app stays on that
screen until it finishes. `tools/bake-frames.py` turns the desktop's cartridge
SVGs into the PNGs in `res/drawable-nodpi`.

Data lives in `Android/data/com.openemux.mobile/files/` (`roms/<console>`,
`states`, `saves`, `system` for BIOS, `screenshots`).

Release builds (`./gradlew assembleRelease`) are signed with the key named by
`openemux.signing` in `local.properties` or `~/.gradle/gradle.properties`: the
path of a properties file kept outside the repository, with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. Without it they fall back to the
debug key, with a warning. An APK signed with another key does not install over
this one: the debug build has to be uninstalled first, and that removes the
data above with it.

## Versioning

The version follows the OpenEmux desktop release it pairs with:
`openemuxVersion` in `gradle.properties` sets `versionName`, and
`versionCode` is `major * 10000 + minor * 100 + patch`.

On start the app asks GitHub for the latest OpenEmux release
(`data/Updates.kt`) and, when it is newer than `versionName`, shows a banner
on the shelf that opens the release page. "Check for updates" in Settings
turns the request off. `-PopenemuxVersion=1.0.0` builds an "old" APK to see it.

## Not yet supported

- Cheats (shown as coming soon).
- Picking cores other than the ones `data/Console.kt` names; the setup
  downloads exactly those.
- Skin editor, or importing a skin from the app; a skin is added by copying it
  into `files/skins/<console>/`.
