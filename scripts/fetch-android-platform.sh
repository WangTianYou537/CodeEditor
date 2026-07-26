#!/usr/bin/env bash
# Fetch Android platform 35 (android.jar only) for compiling the view layer.
# The full platform zip is NOT committed — run this once on a machine that
# needs to compile against the real framework.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="${1:-35}"
OUT_DIR="$ROOT/android-sdk/platforms/android-${API}"
JAR="$OUT_DIR/android.jar"

if [[ -f "$JAR" ]]; then
  echo "already present: $JAR"
  exit 0
fi

# Known platform zip names on dl.google.com. Override with PLATFORM_ZIP_URL.
case "$API" in
  35) ZIP_NAME="platform-35_r01.zip" ;;
  34) ZIP_NAME="platform-34-ext7_r03.zip" ;;
  *)  echo "unknown API $API — set PLATFORM_ZIP_URL" >&2; exit 1 ;;
esac
URL="${PLATFORM_ZIP_URL:-https://dl.google.com/android/repository/${ZIP_NAME}}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "downloading $URL ..."
curl -fL --retry 3 -o "$TMP/platform.zip" "$URL"
echo "extracting android.jar ..."
unzip -q -d "$TMP/extracted" "$TMP/platform.zip"

# Zip layout is android-<N>/android.jar (sometimes nested one extra level).
FOUND="$(find "$TMP/extracted" -name android.jar -type f | head -1)"
if [[ -z "$FOUND" ]]; then
  echo "android.jar not found inside $ZIP_NAME" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
cp "$FOUND" "$JAR"
# Optional: keep package.xml if present (sdkmanager metadata).
PKG="$(find "$TMP/extracted" -name package.xml -type f | head -1 || true)"
if [[ -n "$PKG" ]]; then
  cp "$PKG" "$OUT_DIR/package.xml"
fi

echo "installed $JAR ($(du -h "$JAR" | awk '{print $1}'))"
echo "compile with: ANDROID_JAR=$JAR ./build.sh"
