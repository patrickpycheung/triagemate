#!/usr/bin/env bash
# Start TriageMate in ADK / LIVE AGENT mode (D1) — our LlmAgent loop on
# whatever OpenAI-compatible model secrets.properties points at (E2: normally
# a Copilot seat behind a local proxy).
#
# Prerequisites (see docs/design-java/DEMO-RUNBOOK.md):
#   1. A proxy is up and validated:  ./bin/e2-proxy-spike.sh
#      (rehearse offline first with ./bin/fake-openai-proxy.py)
#   2. secrets.properties has triage.integrations.llm.{base-url,api-key,model}
#      set, with `model` matching an id the proxy actually serves.
#
# This is the -Padk Maven profile — it compiles src/main/adk (J2, the agent
# loop) which the default build skips. Also sets triage.engine=adk, since the
# ADK code being on the classpath doesn't switch the app to use it.
#
# Runs on port 8080 by default — same as run-deterministic.sh, so run only one
# at a time unless you override --port to compare them side by side.
#
# Usage: ./run-adk.sh [-- extra mvn args]
set -euo pipefail
cd "$(dirname "$0")"

command -v mvn >/dev/null 2>&1 || {
  echo "mvn not found. Install a JDK + Maven, e.g.:" >&2
  echo "  sudo apt install openjdk-21-jdk maven" >&2
  exit 1
}

if [ ! -f secrets.properties ]; then
  echo "secrets.properties not found — copy secrets.properties.example and fill in" >&2
  echo "triage.integrations.llm.{base-url,api-key,model}. Continuing anyway (the" >&2
  echo "app will fail fast on first LLM call if it's genuinely missing)." >&2
fi

echo "=== TriageMate — ADK live agent engine (D1) ==="
echo "    http://localhost:8080"
echo "    Run ./scripts/e2-proxy-spike.sh first if you haven't validated the proxy."
exec mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk "$@"
