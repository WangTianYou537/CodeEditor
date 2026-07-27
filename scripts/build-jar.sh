#!/usr/bin/env bash
# Build the CodeEditor library JAR (+ sources JAR + Maven POM) into dist/.
# No Gradle — javac against android.jar, then jar.
#
# Usage:
#   ./scripts/build-jar.sh                 # version from VERSION / git tag / 0.1.0-SNAPSHOT
#   VERSION=1.2.3 ./scripts/build-jar.sh
#   SKIP_TESTS=1 ./scripts/build-jar.sh
#
# Outputs (gitignored under dist/):
#   dist/codeeditor-<ver>.jar
#   dist/codeeditor-<ver>-sources.jar
#   dist/codeeditor-<ver>.pom
#   dist/maven-repo/   (local Maven layout for file:// installs)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/android-sdk/platforms/android-35/android.jar}"
GROUP_ID="${GROUP_ID:-cn.wty5}"
ARTIFACT_ID="${ARTIFACT_ID:-codeeditor}"
SKIP_TESTS="${SKIP_TESTS:-0}"

die() { echo "error: $*" >&2; exit 1; }

# ---- version resolution ----------------------------------------------------
resolve_version() {
  if [[ -n "${VERSION:-}" ]]; then
    echo "$VERSION"
    return
  fi
  if [[ -f "$ROOT/VERSION" ]]; then
    tr -d '[:space:]' < "$ROOT/VERSION"
    return
  fi
  # Prefer an exact tag on HEAD (v1.2.3 → 1.2.3).
  if command -v git >/dev/null 2>&1 && [[ -d "$ROOT/.git" ]]; then
    local tag
    tag="$(git -C "$ROOT" describe --tags --exact-match HEAD 2>/dev/null || true)"
    if [[ -n "$tag" ]]; then
      echo "${tag#v}"
      return
    fi
  fi
  echo "0.1.0-SNAPSHOT"
}

VERSION="$(resolve_version)"
[[ -n "$VERSION" ]] || die "empty VERSION"

if [[ ! -f "$ANDROID_JAR" ]]; then
  if [[ -x "$ROOT/scripts/fetch-android-platform.sh" ]]; then
    echo "android.jar missing — fetching platform 35 ..."
    "$ROOT/scripts/fetch-android-platform.sh" 35
  else
    die "android.jar not found at $ANDROID_JAR"
  fi
fi
[[ -f "$ANDROID_JAR" ]] || die "android.jar still missing at $ANDROID_JAR"

CLASSES="$ROOT/build/jar-classes"
SRC_STAGING="$ROOT/build/jar-sources"
DIST="$ROOT/dist"
JAR_NAME="${ARTIFACT_ID}-${VERSION}.jar"
SOURCES_NAME="${ARTIFACT_ID}-${VERSION}-sources.jar"
POM_NAME="${ARTIFACT_ID}-${VERSION}.pom"

mkdir -p "$DIST"
rm -rf "$CLASSES" "$SRC_STAGING"
mkdir -p "$CLASSES" "$SRC_STAGING"

echo "== version: $VERSION =="
echo "== groupId: $GROUP_ID  artifactId: $ARTIFACT_ID =="

# ---- optional core tests (no Android) --------------------------------------
if [[ "$SKIP_TESTS" != "1" ]]; then
  echo "== tests (core, plain JDK) =="
  CORE_OUT="$ROOT/build/core-classes"
  rm -rf "$CORE_OUT" && mkdir -p "$CORE_OUT"
  find "$ROOT/src/main/java/cn/wty5/editor" -name "*.java" \
    ! -path "*/view/*" \
    ! -path "*/highlight/Highlighter.java" \
    ! -path "*/complete/CompletionEngine.java" \
    > "$ROOT/build/core-src.txt"
  javac --release 17 -d "$CORE_OUT" @"$ROOT/build/core-src.txt"
  # Grammar JSONs for Languages.ensureBuiltins classpath fallback.
  mkdir -p "$CORE_OUT/grammars"
  cp "$ROOT/grammars/"*.json "$CORE_OUT/grammars/" 2>/dev/null \
    || cp "$ROOT/src/main/resources/grammars/"*.json "$CORE_OUT/grammars/"
  javac --release 17 -cp "$CORE_OUT" -d "$CORE_OUT" \
    "$ROOT/test/CoreTest.java" "$ROOT/test/GrammarTest.java" \
    "$ROOT/test/LspTest.java"
  ( cd "$ROOT" && java -cp "$CORE_OUT" CoreTest )
  ( cd "$ROOT" && java -cp "$CORE_OUT" GrammarTest )
  ( cd "$ROOT" && java -cp "$CORE_OUT" LspTest )
else
  echo "== tests skipped (SKIP_TESTS=1) =="
fi

# ---- compile full library against android.jar ------------------------------
echo "== javac library (release 17) =="
find "$ROOT/src/main/java" -name "*.java" > "$ROOT/build/lib-src.txt"
javac --release 17 -cp "$ANDROID_JAR" -d "$CLASSES" @"$ROOT/build/lib-src.txt"

# Bundle grammar JSONs on the classpath as /grammars/*.json
mkdir -p "$CLASSES/grammars"
if ls "$ROOT/src/main/resources/grammars/"*.json >/dev/null 2>&1; then
  cp "$ROOT/src/main/resources/grammars/"*.json "$CLASSES/grammars/"
else
  cp "$ROOT/grammars/"*.json "$CLASSES/grammars/"
fi

# ---- binary JAR ------------------------------------------------------------
echo "== jar $JAR_NAME =="
(
  cd "$CLASSES"
  jar cf "$DIST/$JAR_NAME" .
)

# ---- sources JAR -----------------------------------------------------------
echo "== sources jar $SOURCES_NAME =="
# Mirror Maven source layout: cn/wty5/editor/... + grammars/
mkdir -p "$SRC_STAGING"
cp -a "$ROOT/src/main/java/." "$SRC_STAGING/"
mkdir -p "$SRC_STAGING/grammars"
cp "$CLASSES/grammars/"*.json "$SRC_STAGING/grammars/"
(
  cd "$SRC_STAGING"
  jar cf "$DIST/$SOURCES_NAME" .
)

# ---- POM -------------------------------------------------------------------
echo "== pom $POM_NAME =="
cat > "$DIST/$POM_NAME" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_ID}</groupId>
  <artifactId>${ARTIFACT_ID}</artifactId>
  <version>${VERSION}</version>
  <packaging>jar</packaging>
  <name>CodeEditor</name>
  <description>High-performance Android code editor widget (piece table, grammar languages, LSP, no EditText).</description>
  <url>https://github.com/WangTianYou537/CodeEditor</url>
  <licenses>
    <license>
      <name>The Apache Software License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>WangTianYou537</id>
      <url>https://github.com/WangTianYou537</url>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:https://github.com/WangTianYou537/CodeEditor.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/WangTianYou537/CodeEditor.git</developerConnection>
    <url>https://github.com/WangTianYou537/CodeEditor</url>
  </scm>
  <!-- No third-party deps. Host app supplies the Android framework. -->
  <dependencies/>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>
  </properties>
</project>
EOF

# ---- local Maven repo layout (file:// install target) ----------------------
REPO_ROOT="$DIST/maven-repo"
GROUP_PATH="${GROUP_ID//.//}"
ARTIFACT_DIR="$REPO_ROOT/$GROUP_PATH/$ARTIFACT_ID/$VERSION"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
cp "$DIST/$JAR_NAME"     "$ARTIFACT_DIR/"
cp "$DIST/$SOURCES_NAME" "$ARTIFACT_DIR/"
cp "$DIST/$POM_NAME"     "$ARTIFACT_DIR/"

# Checksums (sha1 / md5) for local repo consumers that validate them.
if command -v sha1sum >/dev/null 2>&1; then
  for f in "$JAR_NAME" "$SOURCES_NAME" "$POM_NAME"; do
    ( cd "$ARTIFACT_DIR" && sha1sum "$f" | awk '{print $1}' > "${f}.sha1" )
  done
fi
if command -v md5sum >/dev/null 2>&1; then
  for f in "$JAR_NAME" "$SOURCES_NAME" "$POM_NAME"; do
    ( cd "$ARTIFACT_DIR" && md5sum "$f" | awk '{print $1}' > "${f}.md5" )
  done
fi

echo
echo "OK  $DIST/$JAR_NAME  ($(du -h "$DIST/$JAR_NAME" | awk '{print $1}'))"
echo "    $DIST/$SOURCES_NAME"
echo "    $DIST/$POM_NAME"
echo "    local Maven repo: $REPO_ROOT"
echo
echo "Install locally:"
echo "  # Gradle"
echo "  repositories { maven { url uri(\"$REPO_ROOT\") } }"
echo "  implementation \"$GROUP_ID:$ARTIFACT_ID:$VERSION\""
echo
echo "  # or copy the jar"
echo "  implementation files(\"$DIST/$JAR_NAME\")"
