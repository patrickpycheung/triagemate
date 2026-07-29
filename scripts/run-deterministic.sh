#!/usr/bin/env bash
# Start TriageMate in DETERMINISTIC mode (D2) — offline, no LLM, cannot fail.
#
# This is the default engine and the stage safety net: it does log<->code
# correlation without a model, so it works with no network and no Copilot seat.
# Runs on port 8080 by default; the ADK build (run-adk.sh) also defaults to
# 8080, so run only one at a time unless you override --port to compare them
# side by side (see docs/design-java/DEMO-RUNBOOK.md, "The fallback flip").
#
# Usage: ./scripts/run-deterministic.sh [-- extra mvn args]
set -euo pipefail
cd "$(dirname "$0")/.."

command -v mvn >/dev/null 2>&1 || {
  echo "mvn not found. Install a JDK + Maven, e.g.:" >&2
  echo "  sudo apt install openjdk-21-jdk maven" >&2
  exit 1
}

echo "=== TriageMate — deterministic engine (D2), no LLM, offline ==="
echo "    http://localhost:8080"
exec mvn spring-boot:run "$@"
