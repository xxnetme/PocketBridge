#!/usr/bin/env bash
# Produce two QA-only release APKs with one disposable signing key, with no owner credentials.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo 'Usage: tools/build-upgrade-pair.sh <previous-version-ref> <new-output-directory>' >&2
  exit 2
fi
REF=$1
OUT=$(realpath -m -- "$2")
cd "$(dirname "$0")/.."
BASE=$(git rev-parse --verify --end-of-options "$REF^{commit}")
[[ "$BASE" != "$(git rev-parse HEAD)" ]] && git merge-base --is-ancestor "$BASE" HEAD || {
  echo 'Choose an older release commit in this branch history' >&2
  exit 1
}
[[ ! -e "$OUT" ]] || { echo 'QA output directory must not exist' >&2; exit 1; }
umask 077
WORK=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pb-upgrade.XXXXXXXX")
trap 'rm -rf -- "$WORK"' EXIT
mkdir "$WORK/previous"
git archive "$BASE" | tar -x -C "$WORK/previous"
# Isolate both builds from the working tree's normal signing configuration and outputs.
mkdir "$WORK/candidate"
git archive HEAD | tar -x -C "$WORK/candidate"
export PB_KEYSTORE="$WORK/qa.p12" PB_KEY_ALIAS=pocketbridge
PB_KEYSTORE_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
export PB_KEYSTORE_PASSWORD
unset PB_KEY_PASSWORD PB_KEYSTORE_BASE64 PB_SIGNER_SHA256
keytool -genkeypair -keyalg RSA -keysize 2048 -validity 7 -storetype PKCS12 \
  -alias "$PB_KEY_ALIAS" -dname 'CN=PocketBridge upgrade QA' \
  -keystore "$PB_KEYSTORE" -storepass:env PB_KEYSTORE_PASSWORD -noprompt
for build in previous candidate; do
  (cd "$WORK/$build"; ./gradlew --no-daemon assembleRelease)
done
previous="$WORK/previous/app/build/outputs/apk/release/app-release.apk"
candidate="$WORK/candidate/app/build/outputs/apk/release/app-release.apk"
python3 tools/release.py compare "$previous" "$candidate"
mkdir -p "$OUT"
cp "$previous" "$OUT/previous.apk"
cp "$candidate" "$OUT/candidate.apk"
printf '\nQA-only APK pair staged at %s; never publish these APKs.\n' "$OUT"
