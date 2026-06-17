#!/usr/bin/env bash
# Builds the internal QaLens distribution: a self-contained Maven repo of all modules
# (com.qalens:*:VERSION) + SHA-256SUMS, zipped under dist/.
#
#   scripts/release_internal.sh            # build + package
#   scripts/release_internal.sh --verify   # additionally re-verify every checksum
#
# Consumers (settings.gradle.kts):
#   dependencyResolutionManagement {
#     repositories { maven { url = uri("https://YOUR-HOST/qalens-repo") }; google(); mavenCentral() }
#   }
# …or unzip locally and use maven { url = uri("/path/to/qalens-repo") }.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="0.9.0"

# This project needs JDK 17 (Kotlin 2.0.x can't parse newer JDK version strings).
if [[ -z "${JAVA_HOME:-}" || ! "$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1)" =~ \"17\. ]]; then
  CANDIDATE="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -n "$CANDIDATE" ]]; then
    export JAVA_HOME="$CANDIDATE"
    echo "Using JDK 17 at $JAVA_HOME"
  else
    echo "WARNING: JDK 17 not found; continuing with default JAVA_HOME" >&2
  fi
fi

echo "── Building + publishing all modules to build/qalens-repo …"
./gradlew qalensDist

REPO="build/qalens-repo"
[[ -f "$REPO/SHA-256SUMS" ]] || { echo "ERROR: SHA-256SUMS missing"; exit 1; }

if [[ "${1:-}" == "--verify" ]]; then
  echo "── Verifying checksums …"
  (cd "$REPO" && shasum -a 256 -c SHA-256SUMS --quiet) && echo "   all checksums OK"
fi

mkdir -p dist
ZIP="dist/qalens-${VERSION}-repo.zip"
rm -f "$ZIP"
(cd build && zip -rq "../$ZIP" qalens-repo)

echo
echo "── Done"
echo "   repo : $REPO"
echo "   zip  : $ZIP ($(du -h "$ZIP" | cut -f1 | tr -d ' '))"
echo "   sums : $(shasum -a 256 "$ZIP" | cut -d' ' -f1)"
echo
echo "Integrate with: debugImplementation(\"com.qalens:qalens-compose:${VERSION}\")"
echo "                releaseImplementation(\"com.qalens:qalens-noop:${VERSION}\")"
