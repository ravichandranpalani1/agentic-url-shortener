#!/usr/bin/env bash
# Runs the test suite for one or more modules and writes a single combined
# JSON summary ({"total":N,"passed":N,"failed":N,"durationMs":N,"tests":[]})
# to the path given via --json-out=<path> -- this is the exact contract
# TestingAgent (orchestrator) depends on, regardless of which underlying
# test runner actually produced the numbers.
#
# orchestrator (+ common) are zero-dependency and run via the hand-rolled
# javac + com.schwab.testlib.TestRunner, exactly as before. service is a
# Spring Boot / Maven module: its tests run via `mvn test` (JUnit 5 +
# Surefire), and this script parses Surefire's XML reports into the same
# summary shape so TestingAgent needs no changes at all.
#
# Usage: scripts/test.sh [service|orchestrator|all] [--json-out=path]
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p "$ROOT_DIR/out"

TARGET="${1:-all}"
JSON_OUT_ARG="${2:-}"
JSON_OUT_PATH="${JSON_OUT_ARG#--json-out=}"

TOTAL=0
PASSED=0
FAILED=0
START_MS=$(date +%s%3N)

run_javac_tests() {
  if [[ "$TARGET" != "orchestrator" && "$TARGET" != "all" ]]; then
    return 0
  fi

  OUT="$ROOT_DIR/out/classes"
  mkdir -p "$OUT"
  echo "Compiling common + testlib + orchestrator (plain javac)..."
  find common/src/main/java testlib/src/main/java orchestrator/src/main/java -name "*.java" \
    > "$ROOT_DIR/out/main-sources.txt"
  javac -d "$OUT" -encoding UTF-8 @"$ROOT_DIR/out/main-sources.txt"

  TEST_OUT="$ROOT_DIR/out/test-classes"
  rm -rf "$TEST_OUT"
  mkdir -p "$TEST_OUT"
  SOURCES_FILE="$ROOT_DIR/out/test-sources.txt"
  find orchestrator/src/test/java -name "*.java" > "$SOURCES_FILE"
  find common/src/test/java -name "*.java" >> "$SOURCES_FILE" 2>/dev/null || true

  echo "Compiling orchestrator + common test sources..."
  javac -d "$TEST_OUT" -cp "$OUT" -encoding UTF-8 @"$SOURCES_FILE"

  CLASS_NAMES=$(cd "$TEST_OUT" && find . -name "*.java" -prune -o -name "*Test.class" -print \
    | sed 's|^\./||; s|\.class$||; s|/|.|g')

  local sub_json="$ROOT_DIR/out/test-summary-orchestrator.json"
  echo "Running tests: orchestrator (+ common)"
  java -cp "$TEST_OUT:$OUT" com.schwab.testlib.TestRunner --json-out="$sub_json" $CLASS_NAMES || true

  local t p f
  t=$(grep -o '"total":[0-9]*' "$sub_json" | head -1 | grep -o '[0-9]*')
  p=$(grep -o '"passed":[0-9]*' "$sub_json" | head -1 | grep -o '[0-9]*')
  f=$(grep -o '"failed":[0-9]*' "$sub_json" | head -1 | grep -o '[0-9]*')
  TOTAL=$((TOTAL + t))
  PASSED=$((PASSED + p))
  FAILED=$((FAILED + f))
}

run_maven_tests() {
  if [[ "$TARGET" != "service" && "$TARGET" != "all" ]]; then
    return 0
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: mvn (Apache Maven) not found on PATH. Install Maven and re-run -- see README.md." >&2
    exit 1
  fi

  echo "Running tests: service (Spring Boot, via Maven / JUnit 5 / Surefire)..."
  rm -rf service/target/surefire-reports
  mvn -q -f service/pom.xml test || true # non-zero on test failure; we parse the reports below instead of trusting the exit code

  local t=0 f=0 report rt rf re
  shopt -s nullglob
  for report in service/target/surefire-reports/TEST-*.xml; do
    rt=$(grep -o 'tests="[0-9]*"' "$report" | head -1 | grep -o '[0-9]*')
    rf=$(grep -o 'failures="[0-9]*"' "$report" | head -1 | grep -o '[0-9]*')
    re=$(grep -o 'errors="[0-9]*"' "$report" | head -1 | grep -o '[0-9]*')
    t=$((t + rt))
    f=$((f + rf + re))
  done
  shopt -u nullglob

  if [[ "$t" -eq 0 ]]; then
    echo "ERROR: no Surefire reports found under service/target/surefire-reports/ -- the Maven build likely" >&2
    echo "failed before any test ran. Re-run 'mvn -f service/pom.xml test' directly to see the real error." >&2
    exit 1
  fi

  TOTAL=$((TOTAL + t))
  PASSED=$((PASSED + t - f))
  FAILED=$((FAILED + f))
}

run_javac_tests
run_maven_tests

END_MS=$(date +%s%3N)
DURATION=$((END_MS - START_MS))

echo
echo "TESTS: total=$TOTAL passed=$PASSED failed=$FAILED durationMs=$DURATION"

if [[ -n "$JSON_OUT_PATH" ]]; then
  mkdir -p "$(dirname "$JSON_OUT_PATH")"
  printf '{"total":%d,"passed":%d,"failed":%d,"durationMs":%d,"tests":[]}' \
    "$TOTAL" "$PASSED" "$FAILED" "$DURATION" > "$JSON_OUT_PATH"
fi

if [[ "$FAILED" -gt 0 ]]; then
  exit 1
fi
