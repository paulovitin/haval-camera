#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.0.0"
OUT="$ROOT/build"
APPROVED_CERT_SHA256=5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
command -v unzip >/dev/null || { echo 'Missing command: unzip' >&2; exit 1; }
for name in CAMERA_KEYSTORE CAMERA_KEY_ALIAS CAMERA_STORE_PASS CAMERA_KEY_PASS; do
  [[ -n "${!name:-}" ]] || { echo "Missing release signing variable: $name" >&2; exit 1; }
done
for tool in apksigner zipalign aapt; do
  [[ -x "$BT/$tool" ]] || { echo "Missing Android SDK tool: $BT/$tool (see README.md)" >&2; exit 1; }
done
./gradlew --no-daemon --dependency-verification strict :app:assembleRelease
mkdir -p "$OUT"
cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$OUT/haval-camera.apk"
"$BT/apksigner" verify "$OUT/haval-camera.apk"
SIGNER_SHA256=$("$BT/apksigner" verify --print-certs "$OUT/haval-camera.apk" |
  awk -F': ' '/Signer #[0-9]+ certificate SHA-256 digest:/ { digest=tolower($2); gsub(/:|[[:space:]]/, "", digest); print digest }')
[[ "$SIGNER_SHA256" == "$APPROVED_CERT_SHA256" ]] || {
  echo 'APK signer certificate does not match the approved EcoTrip certificate.' >&2
  exit 1
}
"$BT/zipalign" -c 4 "$OUT/haval-camera.apk"
unzip -tq "$OUT/haval-camera.apk"
bash "$ROOT/scripts/write-update-manifest.sh" "$OUT/haval-camera.apk" "$OUT/update-manifest.json" "$BT"
printf 'APK: %s\n' "$OUT/haval-camera.apk"
