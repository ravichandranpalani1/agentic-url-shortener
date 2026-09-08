#!/usr/bin/env bash
# Compiles and runs the hand-rolled test suite for one or both modules.
# Usage: scripts/test.sh [service|orchestrator|all] [--json-out=path]
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TARGET="${1:-all}"
JSON_OUT_ARG="${2:-}"

"$ROOT_DIR/scripts/build.sh"

OUT="$ROOT_DIR/out/classes"
TEST_OUT="$ROOT_DIR/out/test-classes"
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"

SOURCES_FILE="$ROOT_DIR/out/test-sources.txt"
> "$SOURCES_FILE"
if [[ "$TARGET" == "service" || "$TARGET" == "all" ]]; then
  find service/src/test/java -name "*.java" >> "$SOURCES_FILE"
fi
if [[ "$TARGET" == "orchestrator" || "$TARGET" == "all" ]]; then
  find orchestrator/src/test/java -name "*.java" >> "$SOURCES_FILE"
fi
find common/src/test/java -name "*.java" >> "$SOURCES_FILE" 2>/dev/null || true

if [[ ! -s "$SOURCES_FILE" ]]; then
  echo "No test sources found for target '$TARGET'."
  exit 2
fi

echo "Compiling test sources..."
javac -d "$TEST_OUT" -cp "$OUT" -encoding UTF-8 @"$SOURCES_FILE"

# Discover fully-qualified test class names from the compiled .class files.
CLASS_NAMES=$(cd "$TEST_OUT" && find . -name "*.java" -prune -o -name "*Test.class" -print \
  | sed 's|^\./||; s|\.class$||; s|/|.|g')

echo "Running tests: $TARGET"
java -cp "$TEST_OUT:$OUT" com.schwab.testlib.TestRunner $JSON_OUT_ARG $CLASS_NAMES
