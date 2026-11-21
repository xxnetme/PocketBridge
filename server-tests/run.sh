#!/usr/bin/env bash
# Автономные тесты серверной части PocketBridge (без Android SDK и эмулятора).
# Требуется JDK 17+.
#   Запуск тестов:       ./server-tests/run.sh
#   Запуск демо-сервера: ./server-tests/run.sh --serve <root> <key> [tls] [--read-only <test-root>]
set -euo pipefail
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"
cd "$(dirname "$0")/.."
REPO="$PWD"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
JSON_JAR="$OUT/json.jar"

echo "→ Загружаю org.json"
curl -fsSL -o "$JSON_JAR" https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar

echo "→ Компилирую сервер и заглушки Android API"
{
  find server-tests/stubs -name '*.java'
  echo app/src/main/java/com/local/wififiles/FileServer.java
  echo app/src/main/java/com/local/wififiles/ServerLog.java
  echo app/src/main/java/com/local/wififiles/SelfSignedCertificate.java
  echo app/src/main/java/com/local/wififiles/Thumbnails.java
  echo app/src/main/java/com/local/wififiles/WebDav.java
  echo app/src/main/java/com/local/wififiles/StorageRoot.java
  echo app/src/main/java/com/local/wififiles/ShareInbox.java
  for class in ManagedFiles TrashStore RetainedUploads BatchRenames FolderScans LanAddresses ThumbnailSessions LegacyUploads DavFiles; do echo "app/src/main/java/com/local/wififiles/$class.java"; done
  find server-tests/src -name '*.java'
} > "$OUT/sources.txt"
javac -encoding UTF-8 -d "$OUT" -cp "$JSON_JAR" @"$OUT/sources.txt"

if [[ "${1:-}" == "--serve" ]]; then
  shift
  echo "→ Запускаю демо-сервер"
  java -Dpb.port="${PB_SERVE_PORT:-8765}" -Dpb.files="${PB_FILES_DIR:-$OUT/private}" -Dfile.encoding=UTF-8 -Dpb.assets="$REPO/app/src/main/assets" -cp "$OUT:$JSON_JAR" com.local.wififiles.ServerMain "$@"
else
  echo "→ Запускаю тесты"
  java -Dpb.port="${PB_SERVE_PORT:-8765}" -Dpb.files="${PB_FILES_DIR:-$OUT/private}" -Dfile.encoding=UTF-8 -Dpb.assets="$REPO/app/src/main/assets" -cp "$OUT:$JSON_JAR" com.local.wififiles.Test
fi
