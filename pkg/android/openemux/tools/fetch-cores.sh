#!/bin/sh
# Downloads the libretro cores this build ships (GBA and SNES) from the
# RetroArch buildbot, for both ABIs, into corelibs/<abi>/lib<core>.so.
set -eu
cd "$(dirname "$0")/.."
for abi in arm64-v8a x86_64; do
  mkdir -p "corelibs/$abi"
  for core in mgba gpsp snes9x bsnes; do
    tmp=$(mktemp -d)
    curl -sfL -o "$tmp/core.zip" \
      "https://buildbot.libretro.com/nightly/android/latest/$abi/${core}_libretro_android.so.zip"
    unzip -oq "$tmp/core.zip" -d "$tmp"
    mv "$tmp/${core}_libretro_android.so" "corelibs/$abi/lib${core}_libretro_android.so"
    rm -rf "$tmp"
    echo "$abi/$core"
  done
done
