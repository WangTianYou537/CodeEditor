#!/usr/bin/env bash
# Build a Maven Central Portal deployment bundle (signed) and optionally upload it.
#
# Prerequisites (one-time, on your machine / as GitHub secrets):
#   1. Account at https://central.sonatype.com
#   2. Verified namespace for the groupId (cn.wty5 requires domain/DNS proof of
#      wty5.cn — or switch GROUP_ID to io.github.WangTianYou537 after claiming
#      that GitHub namespace on the Portal).
#   3. User token from https://central.sonatype.com/account → Generate User Token
#      → export as CENTRAL_USERNAME + CENTRAL_PASSWORD
#   4. GPG key for signing:
#        gpg --full-generate-key     # RSA 4096, never expires (or long expiry)
#        gpg --list-secret-keys --keyid-format LONG
#        gpg --export-secret-keys -a KEYID > secring.asc
#      CI needs: GPG_PRIVATE_KEY (ascii armored), GPG_PASSPHRASE, GPG_KEY_ID
#
# Usage:
#   # Build signed bundle only (no upload):
#   ./scripts/publish-maven-central.sh
#
#   # Build + upload as AUTOMATIC publish:
#   CENTRAL_USERNAME=... CENTRAL_PASSWORD=... \
#     ./scripts/publish-maven-central.sh --upload
#
#   # Dry-run status poll after upload:
#   ./scripts/publish-maven-central.sh --upload --wait
#
# Env:
#   VERSION, GROUP_ID, ARTIFACT_ID          — same as build-jar.sh
#   GPG_KEY_ID / GPG_PASSPHRASE            — signing
#   GPG_PRIVATE_KEY                        — optional; imported if set (CI)
#   CENTRAL_USERNAME / CENTRAL_PASSWORD    — Portal user token pair
#   CENTRAL_PUBLISHING_TYPE                — AUTOMATIC (default) | USER_MANAGED
#   SKIP_BUILD=1                           — reuse existing dist/ artifacts
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GROUP_ID="${GROUP_ID:-cn.wty5}"
ARTIFACT_ID="${ARTIFACT_ID:-codeeditor}"
PUBLISHING_TYPE="${CENTRAL_PUBLISHING_TYPE:-AUTOMATIC}"
DO_UPLOAD=0
DO_WAIT=0
SKIP_BUILD="${SKIP_BUILD:-0}"

die() { echo "error: $*" >&2; exit 1; }

for arg in "$@"; do
  case "$arg" in
    --upload) DO_UPLOAD=1 ;;
    --wait)   DO_WAIT=1 ;;
    --help|-h)
      sed -n '2,40p' "$0"
      exit 0
      ;;
    *) die "unknown arg: $arg" ;;
  esac
done

if [[ -z "${VERSION:-}" ]]; then
  if [[ -f "$ROOT/VERSION" ]]; then
    VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
  elif command -v git >/dev/null 2>&1; then
    tag="$(git -C "$ROOT" describe --tags --exact-match HEAD 2>/dev/null || true)"
    VERSION="${tag#v}"
  fi
fi
[[ -n "${VERSION:-}" ]] || die "VERSION not set"

DIST="$ROOT/dist"
JAR="$DIST/${ARTIFACT_ID}-${VERSION}.jar"
SOURCES="$DIST/${ARTIFACT_ID}-${VERSION}-sources.jar"
JAVADOC="$DIST/${ARTIFACT_ID}-${VERSION}-javadoc.jar"
POM="$DIST/${ARTIFACT_ID}-${VERSION}.pom"

# ---- build artifacts if needed ---------------------------------------------
if [[ "$SKIP_BUILD" != "1" ]]; then
  echo "== build artifacts =="
  VERSION="$VERSION" GROUP_ID="$GROUP_ID" ARTIFACT_ID="$ARTIFACT_ID" \
    "$ROOT/scripts/build-jar.sh"
fi

[[ -f "$JAR" ]]     || die "missing $JAR"
[[ -f "$SOURCES" ]] || die "missing $SOURCES"
[[ -f "$POM" ]]     || die "missing $POM"
[[ -f "$JAVADOC" ]] || die "missing $JAVADOC (rebuild with javadoc enabled)"

# ---- optional GPG private key import (CI) ----------------------------------
if [[ -n "${GPG_PRIVATE_KEY:-}" ]]; then
  echo "== import GPG private key =="
  KEYFILE="$(mktemp)"
  # Support both literal armor in the env var and base64-encoded armor.
  if [[ "$GPG_PRIVATE_KEY" == -----BEGIN* ]]; then
    printf '%s\n' "$GPG_PRIVATE_KEY" > "$KEYFILE"
  else
    printf '%s' "$GPG_PRIVATE_KEY" | base64 -d > "$KEYFILE" 2>/dev/null \
      || printf '%s\n' "$GPG_PRIVATE_KEY" > "$KEYFILE"
  fi
  gpg --batch --import "$KEYFILE"
  rm -f "$KEYFILE"
fi

# Resolve key id if not provided.
if [[ -z "${GPG_KEY_ID:-}" ]]; then
  GPG_KEY_ID="$(gpg --list-secret-keys --with-colons 2>/dev/null \
    | awk -F: '/^sec:/ {print $5; exit}')"
fi
[[ -n "${GPG_KEY_ID:-}" ]] || die "no GPG secret key — set GPG_KEY_ID / GPG_PRIVATE_KEY"

sign_file() {
  local f="$1"
  rm -f "${f}.asc"
  if [[ -n "${GPG_PASSPHRASE:-}" ]]; then
    gpg --batch --yes --pinentry-mode loopback \
      --passphrase "$GPG_PASSPHRASE" \
      --local-user "$GPG_KEY_ID" \
      --detach-sign --armor "$f"
  else
    gpg --batch --yes \
      --local-user "$GPG_KEY_ID" \
      --detach-sign --armor "$f"
  fi
  [[ -f "${f}.asc" ]] || die "failed to sign $f"
}

checksum_file() {
  local f="$1"
  # Maven Central expects the bare hex digest as file content.
  ( cd "$(dirname "$f")" && md5sum "$(basename "$f")" | awk '{print $1}' > "$(basename "$f").md5" )
  ( cd "$(dirname "$f")" && sha1sum "$(basename "$f")" | awk '{print $1}' > "$(basename "$f").sha1" )
}

# ---- stage Maven layout + sign ---------------------------------------------
STAGE="$ROOT/build/central-bundle"
GROUP_PATH="${GROUP_ID//.//}"
ART_DIR="$STAGE/$GROUP_PATH/$ARTIFACT_ID/$VERSION"
rm -rf "$STAGE"
mkdir -p "$ART_DIR"

echo "== stage + sign under $ART_DIR =="
for src in "$JAR" "$SOURCES" "$JAVADOC" "$POM"; do
  base="$(basename "$src")"
  cp "$src" "$ART_DIR/$base"
  sign_file "$ART_DIR/$base"
  checksum_file "$ART_DIR/$base"
  # Also checksum the .asc (not required by all validators, but harmless).
done

# List what will go in the bundle.
echo "== bundle contents =="
find "$ART_DIR" -type f | sort | sed "s|^$STAGE/||"

BUNDLE="$DIST/${ARTIFACT_ID}-${VERSION}-central-bundle.zip"
rm -f "$BUNDLE"
(
  cd "$STAGE"
  # zip paths must be group/artifact/version/... (no leading ./)
  zip -r -q "$BUNDLE" .
)
echo "OK  $BUNDLE  ($(du -h "$BUNDLE" | awk '{print $1}'))"

if [[ "$DO_UPLOAD" != "1" ]]; then
  echo
  echo "Bundle ready (not uploaded). To publish:"
  echo "  CENTRAL_USERNAME=... CENTRAL_PASSWORD=... $0 --upload"
  echo "  # or upload $BUNDLE manually at https://central.sonatype.com"
  exit 0
fi

# ---- upload to Central Publisher API ---------------------------------------
[[ -n "${CENTRAL_USERNAME:-}" ]] || die "CENTRAL_USERNAME required for --upload"
[[ -n "${CENTRAL_PASSWORD:-}" ]] || die "CENTRAL_PASSWORD required for --upload"

# Portal expects Authorization: Bearer base64(username:password)
AUTH_B64="$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 -w0 2>/dev/null \
  || printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64)"

UPLOAD_URL="https://central.sonatype.com/api/v1/publisher/upload"
UPLOAD_URL+="?name=${ARTIFACT_ID}-${VERSION}&publishingType=${PUBLISHING_TYPE}"

echo "== upload to Maven Central Portal ($PUBLISHING_TYPE) =="
HTTP_BODY="$(mktemp)"
HTTP_CODE="$(curl -sS -o "$HTTP_BODY" -w '%{http_code}' \
  -X POST \
  -H "Authorization: Bearer ${AUTH_B64}" \
  -F "bundle=@${BUNDLE};type=application/octet-stream" \
  "$UPLOAD_URL")"

if [[ "$HTTP_CODE" != "201" && "$HTTP_CODE" != "200" ]]; then
  echo "upload failed (HTTP $HTTP_CODE):" >&2
  cat "$HTTP_BODY" >&2 || true
  rm -f "$HTTP_BODY"
  exit 1
fi

DEPLOYMENT_ID="$(tr -d '[:space:]' < "$HTTP_BODY")"
rm -f "$HTTP_BODY"
echo "deploymentId: $DEPLOYMENT_ID"
echo "$DEPLOYMENT_ID" > "$DIST/${ARTIFACT_ID}-${VERSION}-deployment-id.txt"

if [[ "$DO_WAIT" == "1" ]]; then
  echo "== poll deployment status =="
  for i in $(seq 1 60); do
    STATUS_JSON="$(curl -sS -X POST \
      -H "Authorization: Bearer ${AUTH_B64}" \
      "https://central.sonatype.com/api/v1/publisher/status?id=${DEPLOYMENT_ID}")"
    STATE="$(printf '%s' "$STATUS_JSON" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("deploymentState",""))' 2>/dev/null || true)"
    echo "  [$i] $STATE"
    case "$STATE" in
      PUBLISHED)
        echo "OK  published to Maven Central"
        echo "$STATUS_JSON" | python3 -m json.tool 2>/dev/null || echo "$STATUS_JSON"
        exit 0
        ;;
      FAILED)
        echo "FAILED:" >&2
        echo "$STATUS_JSON" | python3 -m json.tool 2>/dev/null || echo "$STATUS_JSON" >&2
        exit 1
        ;;
      VALIDATED)
        if [[ "$PUBLISHING_TYPE" == "USER_MANAGED" ]]; then
          echo "Validated. Publish manually:"
          echo "  curl -X POST -H \"Authorization: Bearer \$AUTH\" \\"
          echo "    https://central.sonatype.com/api/v1/publisher/deployment/${DEPLOYMENT_ID}"
          exit 0
        fi
        ;;
    esac
    sleep 10
  done
  echo "timed out waiting for deployment $DEPLOYMENT_ID" >&2
  exit 1
fi

echo
echo "Upload accepted. Check status at https://central.sonatype.com"
echo "  or: curl -X POST -H \"Authorization: Bearer \$AUTH\" \\"
echo "        \"https://central.sonatype.com/api/v1/publisher/status?id=${DEPLOYMENT_ID}\""
