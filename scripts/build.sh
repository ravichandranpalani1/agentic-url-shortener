#!/usr/bin/env bash
# Builds every module. common, testlib and orchestrator are zero-dependency
# and compiled directly with javac (no build tool, no network access
# required for those three). service/ is a Spring Boot application and is
# built with Maven, which needs network access to Maven Central the first
# time (to download Spring Boot's dependencies) -- see
# docs/testing-and-limitations.md for why the rest of the system
# deliberately avoids that requirement.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT="$ROOT_DIR/out/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "Compiling common + testlib + orchestrator (plain javac)..."
find common/src/main/java testlib/src/main/java orchestrator/src/main/java \
  -name "*.java" > "$ROOT_DIR/out/main-sources.txt"

javac -d "$OUT" -encoding UTF-8 -Xlint:all @"$ROOT_DIR/out/main-sources.txt"
echo "common + testlib + orchestrator compiled to $OUT"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn (Apache Maven) not found on PATH. Install Maven (e.g. https://maven.apache.org/download.cgi" >&2
  echo "or via your OS package manager) and re-run. See README.md for details." >&2
  exit 1
fi

echo "Building service/ (Spring Boot, via Maven)..."
mvn -q -f service/pom.xml -DskipTests package
echo "service/ built to service/target/urlshortener-service.jar"
