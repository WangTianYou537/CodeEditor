#!/usr/bin/env bash
# Build the CodeEditor library against the Android platform jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/android-sdk/platforms/android-35/android.jar}"
OUT="$ROOT/build/android-classes"
CORE_OUT="$ROOT/build/core-classes"

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "android.jar not found at $ANDROID_JAR" >&2
  echo "Download platform 35, or set ANDROID_JAR." >&2
  exit 1
fi

echo "== core (no Android) =="
rm -rf "$CORE_OUT" && mkdir -p "$CORE_OUT"
find "$ROOT/src/main/java/com/editor" -name "*.java" \
  ! -path "*/view/*" \
  ! -path "*/highlight/Highlighter.java" \
  ! -path "*/complete/CompletionEngine.java" \
  > /tmp/ce-core-src.txt
javac --release 17 -d "$CORE_OUT" @/tmp/ce-core-src.txt
cp -r "$ROOT/grammars" "$CORE_OUT/" 2>/dev/null || true

echo "== tests =="
javac --release 17 -cp "$CORE_OUT" -d "$CORE_OUT" \
  "$ROOT/test/CoreTest.java" "$ROOT/test/GrammarTest.java"
( cd "$ROOT" && java -cp "$CORE_OUT" CoreTest )
( cd "$ROOT" && java -cp "$CORE_OUT" GrammarTest )

echo "== full tree vs android.jar =="
rm -rf "$OUT" && mkdir -p "$OUT"
find "$ROOT/src" -name "*.java" > /tmp/ce-all-src.txt
javac --release 17 -cp "$ANDROID_JAR" -d "$OUT" @/tmp/ce-all-src.txt
mkdir -p "$OUT/grammars" && cp "$ROOT/grammars/"*.json "$OUT/grammars/"

echo "OK — classes in $OUT ($(find "$OUT" -name '*.class' | wc -l) class files)"
