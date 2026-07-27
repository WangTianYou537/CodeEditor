#!/usr/bin/env bash
# Publish the CodeEditor JAR + sources + POM to GitHub Packages (Maven).
#
# Prerequisites:
#   - dist/ artifacts already built (./scripts/build-jar.sh)
#   - GITHUB_TOKEN with `write:packages` (and `read:packages`)
#   - optional: GITHUB_ACTOR (defaults to git/gh user or WangTianYou537)
#
# Usage:
#   GITHUB_TOKEN=ghp_... ./scripts/publish-github-packages.sh
#   VERSION=1.2.3 GITHUB_TOKEN=... ./scripts/publish-github-packages.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GROUP_ID="${GROUP_ID:-cn.wty5}"
ARTIFACT_ID="${ARTIFACT_ID:-codeeditor}"
OWNER="${GITHUB_REPOSITORY_OWNER:-WangTianYou537}"
REPO_NAME="${GITHUB_REPOSITORY##*/}"
REPO_NAME="${REPO_NAME:-CodeEditor}"

die() { echo "error: $*" >&2; exit 1; }

if [[ -z "${VERSION:-}" ]]; then
  if [[ -f "$ROOT/VERSION" ]]; then
    VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
  elif command -v git >/dev/null 2>&1; then
    tag="$(git -C "$ROOT" describe --tags --exact-match HEAD 2>/dev/null || true)"
    VERSION="${tag#v}"
  fi
fi
[[ -n "${VERSION:-}" ]] || die "VERSION not set (export VERSION=x.y.z or tag HEAD)"

TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
[[ -n "$TOKEN" ]] || die "GITHUB_TOKEN (or GH_TOKEN) required for GitHub Packages"

ACTOR="${GITHUB_ACTOR:-}"
if [[ -z "$ACTOR" ]]; then
  if command -v gh >/dev/null 2>&1; then
    ACTOR="$(gh api user --jq .login 2>/dev/null || true)"
  fi
fi
ACTOR="${ACTOR:-$OWNER}"

DIST="$ROOT/dist"
JAR="$DIST/${ARTIFACT_ID}-${VERSION}.jar"
SOURCES="$DIST/${ARTIFACT_ID}-${VERSION}-sources.jar"
POM="$DIST/${ARTIFACT_ID}-${VERSION}.pom"

[[ -f "$JAR" ]]     || die "missing $JAR — run ./scripts/build-jar.sh first"
[[ -f "$SOURCES" ]] || die "missing $SOURCES — run ./scripts/build-jar.sh first"
[[ -f "$POM" ]]     || die "missing $POM — run ./scripts/build-jar.sh first"

# GitHub Packages Maven layout:
#   https://maven.pkg.github.com/OWNER/REPO/group/artifact/version/file
BASE="https://maven.pkg.github.com/${OWNER}/${REPO_NAME}"
GROUP_PATH="${GROUP_ID//.//}"
UPLOAD_DIR="${BASE}/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}"

upload() {
  local file="$1"
  local name
  name="$(basename "$file")"
  echo "  PUT $name"
  # GitHub Packages accepts PUT of the raw artifact; auth is basic with token.
  http_code="$(curl -sS -o /tmp/gh-pkg-out.txt -w '%{http_code}' \
    -X PUT \
    -u "${ACTOR}:${TOKEN}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"$file" \
    "${UPLOAD_DIR}/${name}")"
  if [[ "$http_code" != "200" && "$http_code" != "201" && "$http_code" != "204" ]]; then
    echo "upload failed for $name (HTTP $http_code):" >&2
    cat /tmp/gh-pkg-out.txt >&2 || true
    exit 1
  fi
}

echo "== publish cn.wty5:codeeditor:$VERSION → GitHub Packages =="
echo "   $UPLOAD_DIR"
upload "$POM"
upload "$JAR"
upload "$SOURCES"

echo
echo "OK  published $GROUP_ID:$ARTIFACT_ID:$VERSION"
echo
echo "Consume with:"
echo "  repositories {"
echo "    maven {"
echo "      url = uri(\"https://maven.pkg.github.com/${OWNER}/${REPO_NAME}\")"
echo "      credentials {"
echo "        username = project.findProperty(\"gpr.user\") ?: System.getenv(\"GITHUB_ACTOR\")"
echo "        password = project.findProperty(\"gpr.key\")  ?: System.getenv(\"GITHUB_TOKEN\")"
echo "      }"
echo "    }"
echo "  }"
echo "  implementation(\"$GROUP_ID:$ARTIFACT_ID:$VERSION\")"
