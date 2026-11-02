#!/usr/bin/env bash
# Run only on the authorized build host, after signing. No manual version/hash inputs.
set -euo pipefail
[[ $# -eq 3 ]] || { echo 'usage: write-update-manifest.sh APK OUTPUT_JSON BUILD_TOOLS_DIR' >&2; exit 64; }
UPDATE_APK=$1
UPDATE_JSON=$2
UPDATE_BT=$3
[[ -f "$UPDATE_APK" && $(basename "$UPDATE_JSON") == update-manifest.json ]] || exit 65
rm -f "$UPDATE_JSON"
UPDATE_TMP=$(mktemp -d)
trap 'rm -rf "$UPDATE_TMP"' EXIT
"$UPDATE_BT/apksigner" verify --print-certs "$UPDATE_APK" > "$UPDATE_TMP/certs"
UPDATE_CERT=$(awk -F': ' '/Signer #[0-9]+ certificate SHA-256 digest:/ { digest=tolower($2); gsub(/:|[[:space:]]/, "", digest); print digest }' "$UPDATE_TMP/certs")
[[ "$UPDATE_CERT" == 5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0 ]] || {
  echo 'Refusing update manifest: APK signer is not approved.' >&2; exit 1;
}
"$UPDATE_BT/zipalign" -c 4 "$UPDATE_APK"
unzip -tq "$UPDATE_APK"
"$UPDATE_BT/aapt" dump badging "$UPDATE_APK" > "$UPDATE_TMP/badging"
python3 - "$UPDATE_APK" "$UPDATE_JSON" "$UPDATE_TMP/badging" <<'PY'
import hashlib, json, os, re, sys, tempfile
apk, output, badging = sys.argv[1:]
with open(badging, encoding='utf-8') as source:
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", source.read(), re.M)
if not match or match[1] != 'br.com.redesurftank.havalcamera':
    raise SystemExit('Refusing update manifest: wrong or missing package identity')
code, name, filename = int(match[2]), match[3], os.path.basename(apk)
size = os.path.getsize(apk)
if not (0 < code <= 2147483647 and 0 < size <= 1073741824 and name.strip() and len(name) <= 128
        and not any(ord(c) < 32 or ord(c) == 127 for c in name)
        and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk', filename)):
    raise SystemExit('Refusing update manifest: invalid APK metadata')
digest = hashlib.sha256()
with open(apk, 'rb') as source:
    for block in iter(lambda: source.read(32768), b''):
        digest.update(block)
manifest = dict(schemaVersion=1, versionCode=code, versionName=name, apk=filename, size=size, sha256=digest.hexdigest())
with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=os.path.dirname(os.path.abspath(output)), delete=False) as target:
    temporary = target.name
    json.dump(manifest, target, ensure_ascii=True, indent=2)
    target.write('\n')
try:
    os.replace(temporary, output)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
print('Update manifest: ' + output)
PY
