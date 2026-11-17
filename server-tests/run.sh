#!/usr/bin/env bash
# Автономные тесты серверной части PocketBridge (без Android SDK и эмулятора).
# Требуется JDK 17+. Запуск: ./server-tests/run.sh
set -euo pipefail
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"   # имена файлов в UTF-8
cd "$(dirname "$0")/.."
REPO="$PWD"
OUT="$(mktemp -d)"
JSON_JAR="$OUT/json.jar"

echo "→ Загружаю org.json"
curl -fsSL -o "$JSON_JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

echo "→ Компилирую сервер и заглушки Android API"
{
  find server-tests/stubs -name '*.java'
  echo app/src/main/java/com/local/wififiles/FileServer.java
  echo app/src/main/java/com/local/wififiles/SelfSignedCertificate.java
  echo app/src/main/java/com/local/wififiles/Thumbnails.java
  echo app/src/main/java/com/local/wififiles/WebDav.java
  find server-tests/src -name '*.java'
} > "$OUT/sources.txt"
javac -encoding UTF-8 -d "$OUT" -cp "$JSON_JAR" @"$OUT/sources.txt"

echo "→ Запускаю тесты"
java -Dfile.encoding=UTF-8 -Dpb.assets="$REPO/app/src/main/assets" -cp "$OUT:$JSON_JAR" com.local.wififiles.Test
