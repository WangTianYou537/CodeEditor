#!/usr/bin/env bash
# Build a debug-signed demo APK that embeds CodeEditorView.
# No Gradle — uses aapt2 + javac + d8 + apksigner from a local SDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$ROOT/android-sdk}"
BT_VER="${BUILD_TOOLS_VERSION:-35.0.1}"
API="${ANDROID_API:-35}"

BT="$SDK/build-tools/$BT_VER"
PLATFORM="$SDK/platforms/android-$API"
ANDROID_JAR="$PLATFORM/android.jar"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

die() { echo "error: $*" >&2; exit 1; }

[[ -x "$AAPT2" ]]     || die "aapt2 not found at $AAPT2 (run scripts to fetch build-tools)"
[[ -f "$ANDROID_JAR" ]] || die "android.jar missing at $ANDROID_JAR"

# Optional: auto-fetch platform jar
if [[ ! -f "$ANDROID_JAR" && -x "$ROOT/scripts/fetch-android-platform.sh" ]]; then
  "$ROOT/scripts/fetch-android-platform.sh" "$API"
fi

WORK="$ROOT/build/demo"
OUT_APK="$ROOT/dist/CodeEditor-demo.apk"
DEMO="$ROOT/demo"
LIB_SRC="$ROOT/src/main/java"
RES_GRAMMARS="$ROOT/src/main/resources/grammars"

rm -rf "$WORK"
mkdir -p "$WORK"/{res-compiled,gen,lib-classes,app-classes,dex,apk-unaligned,apk} \
         "$ROOT/dist"

echo "== 1. compile resources =="
# Compile every res file to .flat
"$AAPT2" compile --dir "$DEMO/res" -o "$WORK/res-compiled/"
# Link into an APK skeleton + R.java
"$AAPT2" link \
  -o "$WORK/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$DEMO/AndroidManifest.xml" \
  --java "$WORK/gen" \
  --auto-add-overlay \
  $(find "$WORK/res-compiled" -name '*.flat' | sort)

echo "== 2. javac library + demo =="
# Library sources (everything under cn.wty5.editor except nothing)
find "$LIB_SRC" -name '*.java' > "$WORK/lib-sources.txt"
# Demo + generated R
find "$DEMO/src" "$WORK/gen" -name '*.java' > "$WORK/app-sources.txt"

javac --release 11 \
  -classpath "$ANDROID_JAR" \
  -d "$WORK/lib-classes" \
  @"$WORK/lib-sources.txt"

# Bundle grammar JSONs into the library classes root so Languages can
# load them from the classpath as /grammars/<name>.json.
mkdir -p "$WORK/lib-classes/grammars"
cp "$RES_GRAMMARS"/*.json "$WORK/lib-classes/grammars/" 2>/dev/null \
  || cp "$ROOT/grammars"/*.json "$WORK/lib-classes/grammars/"

javac --release 11 \
  -classpath "$ANDROID_JAR:$WORK/lib-classes" \
  -d "$WORK/app-classes" \
  @"$WORK/app-sources.txt"

echo "== 3. d8 → classes.dex =="
# Jar library classes + grammar JSON so /grammars/*.json stay on the classpath
# inside the dex zip (Android ClassLoader serves non-class zip entries).
(
  cd "$WORK/lib-classes"
  jar cf "$WORK/lib.jar" .
)
(
  cd "$WORK/app-classes"
  jar cf "$WORK/app.jar" .
)
"$D8" --release --min-api 24 --output "$WORK/dex" \
  --lib "$ANDROID_JAR" \
  "$WORK/lib.jar" "$WORK/app.jar"

echo "== 4. package APK =="
cp "$WORK/base.apk" "$WORK/app-unsigned.apk"
# Add dex into the apk (aapt2-linked apk is a zip)
(
  cd "$WORK/dex"
  # -0 store, -u update, -X no extras
  zip -u -X "$WORK/app-unsigned.apk" classes.dex
  # Multi-dex just in case
  for f in classes*.dex; do
    [[ "$f" == classes.dex ]] && continue
    [[ -f "$f" ]] && zip -u -X "$WORK/app-unsigned.apk" "$f"
  done
)

# Also pack grammar json as assets so Languages filesystem fallback works
# if classpath lookup is stripped — belt and suspenders.
mkdir -p "$WORK/assets/grammars"
cp "$WORK/lib-classes/grammars"/*.json "$WORK/assets/grammars/"
(
  cd "$WORK"
  zip -u -X app-unsigned.apk assets/grammars/*.json
)

echo "== 5. align =="
"$ZIPALIGN" -f -p 4 "$WORK/app-unsigned.apk" "$WORK/app-aligned.apk"

echo "== 6. sign (debug keystore) =="
KS="$WORK/debug.keystore"
if [[ ! -f "$ROOT/demo/debug.keystore" ]]; then
  keytool -genkeypair -v \
    -keystore "$KS" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CodeEditor Demo, OU=Dev, O=wty5, L=Local, ST=NA, C=CN" \
    >/dev/null
  cp "$KS" "$ROOT/demo/debug.keystore"
else
  KS="$ROOT/demo/debug.keystore"
fi

"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT_APK" \
  "$WORK/app-aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_APK" | head -20

echo
echo "OK  $OUT_APK  ($(du -h "$OUT_APK" | awk '{print $1}'))"
echo "Install:  adb install -r $OUT_APK"
