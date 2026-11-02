#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
OUT="$ROOT/build"
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  Linux) HOST_TAG=linux-x86_64 ;;
  *) echo 'Build on macOS or Linux (WSL is supported).' >&2; exit 1 ;;
esac
CLANG="$SDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android28-clang"
for command in java javac jar keytool curl unzip zip; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
for file in "$ANDROID_JAR" "$BT/aapt2" "$BT/aidl" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$CLANG"; do
  [[ -f "$file" ]] || { echo "Missing Android SDK file: $file (see README.md)" >&2; exit 1; }
done
rm -rf "$OUT/classes" "$OUT/dex" "$OUT/generated"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/deps" "$OUT/generated" "$OUT/lib/arm64-v8a" "$OUT/assets"
for module in api provider shared aidl; do
  if [[ ! -s "$OUT/deps/$module.aar" ]]; then
    curl -fsSL --retry 2 "https://repo.maven.apache.org/maven2/dev/rikka/shizuku/$module/13.1.5/$module-13.1.5.aar" -o "$OUT/deps/$module.aar.partial"
    mv "$OUT/deps/$module.aar.partial" "$OUT/deps/$module.aar"
  fi
done
(
  cd "$OUT/deps"
  if command -v sha256sum >/dev/null; then
    sha256sum -c "$ROOT/dependencies.sha256"
  else
    shasum -a 256 -c "$ROOT/dependencies.sha256"
  fi
)
for module in api provider shared aidl; do
  unzip -p "$OUT/deps/$module.aar" classes.jar > "$OUT/deps/$module.jar"
done
"$BT/aidl" -Iapp/src/main/aidl -p"$SDK/platforms/android-36/framework.aidl" -o"$OUT/generated" \
  app/src/main/aidl/br/com/redesurftank/havalcamera/ICameraStream.aidl
javac -source 8 -target 8 -classpath "$ANDROID_JAR:$OUT/deps/*" -d "$OUT/classes" \
  app/src/main/java/br/com/redesurftank/havalcamera/*.java \
  "$OUT/generated/br/com/redesurftank/havalcamera/ICameraStream.java"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$ANDROID_JAR" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar" "$OUT/deps/"*.jar
"$CLANG" -shared -fPIC -O2 -Wall -Wextra -Werror app/src/main/cpp/camera_stream.c -ldl \
  -o "$OUT/lib/arm64-v8a/libcamera_stream.so"
cp LICENSE "$OUT/assets/LICENSE.txt"
cp LICENSES/Shizuku-API.txt "$OUT/assets/Shizuku-API-LICENSE.txt"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" -A "$OUT/assets" \
  --manifest app/src/main/AndroidManifest.xml --min-sdk-version 28 --target-sdk-version 28
zip -q -j "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
(cd "$OUT" && zip -q unsigned.apk lib/arm64-v8a/libcamera_stream.so)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KEYSTORE="${CAMERA_KEYSTORE:-$OUT/debug.keystore}"
KEY_ALIAS="${CAMERA_KEY_ALIAS:-androiddebugkey}"
export CAMERA_STORE_PASS="${CAMERA_STORE_PASS:-android}"
export CAMERA_KEY_PASS="${CAMERA_KEY_PASS:-$CAMERA_STORE_PASS}"
if [[ ! -f "$KEYSTORE" ]]; then
  [[ -z "${CAMERA_KEYSTORE:-}" ]] || { echo "Keystore does not exist: $KEYSTORE" >&2; exit 1; }
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' \
    -storepass:env CAMERA_STORE_PASS -keypass:env CAMERA_KEY_PASS
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:CAMERA_STORE_PASS --key-pass env:CAMERA_KEY_PASS \
  --out "$OUT/haval-camera.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/haval-camera.apk"
"$BT/zipalign" -c 4 "$OUT/haval-camera.apk"
unzip -tq "$OUT/haval-camera.apk"
printf 'APK: %s\n' "$OUT/haval-camera.apk"
