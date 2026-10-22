#!/bin/sh
# Full build: the patched RetroArch engine for both ABIs, then the APK.
#   NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build.sh
set -eu
cd "$(dirname "$0")/.."
NDK=${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}
for abi in arm64-v8a x86_64; do
  (cd ../phoenix-common && "$NDK/ndk-build" APP_ABI=$abi -j"$(nproc)")
done
./gradlew assembleDebug
echo "APK: $(ls build/outputs/apk/debug/*.apk)"
