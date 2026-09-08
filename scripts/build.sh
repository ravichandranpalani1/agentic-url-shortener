#!/usr/bin/env bash
# Compiles every module (common, testlib, service, orchestrator) with plain javac.
# No build tool, no network access, no third-party dependencies required.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT="$ROOT_DIR/out/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "Compiling main sources..."
find common/src/main/java testlib/src/main/java service/src/main/java orchestrator/src/main/java \
  -name "*.java" > "$ROOT_DIR/out/main-sources.txt"

javac -d "$OUT" -encoding UTF-8 -Xlint:all @"$ROOT_DIR/out/main-sources.txt"

echo "Main sources compiled to $OUT"
