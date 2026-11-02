#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.0.0"
OUT="$ROOT/build"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
command -v unzip >/dev/null || { echo 'Missing command: unzip' >&2; exit 1; }
for tool in apksigner zipalign; do
  [[ -x "$BT/$tool" ]] || { echo "Missing Android SDK tool: $BT/$tool (see README.md)" >&2; exit 1; }
done
./gradlew --no-daemon --dependency-verification strict :app:assembleRelease
mkdir -p "$OUT"
cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$OUT/haval-camera.apk"
"$BT/apksigner" verify "$OUT/haval-camera.apk"
"$BT/zipalign" -c 4 "$OUT/haval-camera.apk"
unzip -tq "$OUT/haval-camera.apk"
printf 'APK: %s\n' "$OUT/haval-camera.apk"
