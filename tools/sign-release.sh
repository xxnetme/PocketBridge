#!/usr/bin/env bash
# Build and stage a production APK/AAB, never substitute a debug APK.
set -euo pipefail
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo 'Usage: tools/sign-release.sh <tag> <new-output-directory> [previous-stable.apk]' >&2
  exit 2
fi
TAG=$1
OUT=$(realpath -m -- "$2")
PREVIOUS=()
if [[ -n "${3:-}" ]]; then PREVIOUS=(--previous "$(realpath -e -- "$3")"); fi
cd "$(dirname "$0")/.."
python3 tools/release.py metadata "$TAG" --check-pin
: "${PB_KEYSTORE_PASSWORD:?Set PB_KEYSTORE_PASSWORD in the protected release environment}"
[[ ! -e "$OUT" ]] || { echo 'Release output directory must not exist' >&2; exit 1; }

umask 077
WORK=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pb-sign.XXXXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
if [[ -n "${PB_KEYSTORE_BASE64:-}" ]]; then
  [[ -z "${PB_KEYSTORE:-}" ]] || { echo 'Set only PB_KEYSTORE or PB_KEYSTORE_BASE64' >&2; exit 1; }
  export PB_KEYSTORE="$WORK/pocketbridge.jks"
  printf '%s' "$PB_KEYSTORE_BASE64" | base64 --decode > "$PB_KEYSTORE"
  unset PB_KEYSTORE_BASE64
fi
: "${PB_KEYSTORE:?Set PB_KEYSTORE or the protected PB_KEYSTORE_BASE64 secret}"
[[ -s "$PB_KEYSTORE" && -r "$PB_KEYSTORE" ]] || { echo 'Release keystore is empty or unreadable' >&2; exit 1; }
export PB_KEYSTORE=$(realpath -e -- "$PB_KEYSTORE")

APK=app/build/outputs/apk/release/app-release.apk
AAB=app/build/outputs/bundle/release/app-release.aab
rm -f -- "$APK" "$AAB"
./gradlew --no-daemon --no-build-cache --no-configuration-cache assembleRelease bundleRelease
unset PB_KEYSTORE PB_KEYSTORE_PASSWORD PB_KEY_ALIAS PB_KEY_PASSWORD
if [[ -z "${PB_BUNDLETOOL_JAR:-}" ]]; then
  export PB_BUNDLETOOL_JAR="$WORK/bundletool-all-1.18.3.jar"
  curl --proto '=https' --proto-redir '=https' --fail --silent --show-error --location \
    -o "$PB_BUNDLETOOL_JAR" https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
fi
python3 tools/release.py stage "$TAG" "$APK" "$AAB" "$OUT" "${PREVIOUS[@]}"
