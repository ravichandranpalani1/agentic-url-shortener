#!/usr/bin/env bash
# Runs one orchestrator scenario end-to-end against the real codebase.
# Usage: scripts/run-orchestrator.sh <greenfield|brownfield|ambiguous> [extra orchestrator args...]
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SCENARIO="${1:?Usage: scripts/run-orchestrator.sh <greenfield|brownfield|ambiguous> [--auto] [--approvals=path]}"
shift || true

"$ROOT_DIR/scripts/build.sh" > /dev/null

java -cp "$ROOT_DIR/out/classes" com.schwab.orchestrator.cli.Main "$SCENARIO" --repo-root="$ROOT_DIR" "$@"
