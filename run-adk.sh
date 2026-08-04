#!/usr/bin/env bash
# Start TriageMate in ADK / LIVE AGENT mode (D1) — our LlmAgent loop on
# whatever OpenAI-compatible model secrets.properties points at (E2: normally
# a Copilot seat behind a local proxy).
#
# Prerequisites (see docs/design-java/DEMO-RUNBOOK.md):
#   1. A Copilot proxy (E2) reachable at $PROXY_BASE — this script starts one
#      itself (`copilot-api start --port 4000 --proxy-env`) if nothing is
#      already listening. First run needs the GitHub OAuth device-flow
#      (interactive — see ./bin/setup-copilot-api.sh on a corp laptop with a
#      Nexus-pinned npm); once authed, the token is cached and later runs
#      just reuse it. Validate the full chain any time with
#      ./bin/e2-proxy-spike.sh (rehearse offline first with
#      ./bin/fake-openai-proxy.py).
#   2. secrets.properties has triage.integrations.llm.{base-url,api-key,model}
#      set, with `model` matching an id the proxy actually serves.
#
# C6 (docs/discovery/copilot-cli-runtime): this is human-present demo use —
# you ran this script — not the headless/unattended use C6 currently blocks.
#
# This is the -Padk Maven profile — it compiles src/main/adk (J2, the agent
# loop) which the default build skips. Also sets triage.engine=adk, since the
# ADK code being on the classpath doesn't switch the app to use it.
#
# Runs on port 80 by default, falling back to 8080 when 80 needs elevation or is
# taken — same as run-deterministic.sh, so run only one at a time unless you pass
# --server.port=N to compare them side by side.
#
# Usage: ./run-adk.sh [extra --app.args=...]   (forwarded to the app, not to Maven)
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

PROXY_PORT="4000"
PROXY_BASE="http://localhost:${PROXY_PORT}/v1"
PUBLIC_NPM_REGISTRY="https://registry.npmjs.org/"
SYSTEM_CAFILE="/etc/ssl/certs/ca-certificates.crt"

STARTED_PROXY=false
if curl -fsS --max-time 2 "$PROXY_BASE/models" >/dev/null 2>&1; then
  echo "Copilot proxy already up at $PROXY_BASE"
elif command -v copilot-api >/dev/null 2>&1; then
  echo "Starting Copilot proxy: copilot-api start --port $PROXY_PORT --proxy-env"
  nohup copilot-api start --port "$PROXY_PORT" --proxy-env \
    > /tmp/copilot-api.log 2>&1 &
  PROXY_PID=$!
  echo "  proxy pid $PROXY_PID, log: /tmp/copilot-api.log"
  STARTED_PROXY=true
elif command -v npx >/dev/null 2>&1; then
  # Plain `npx copilot-api@latest` fails on a corp laptop whose npm points at
  # an internal Nexus registry (E401, then a cert-chain error even once
  # pointed at the public registry) — see bin/setup-copilot-api.sh for the
  # full story. That script fixes this by installing an npx()/copilot-api
  # override into ~/.bashrc, but ~/.bashrc is only sourced by interactive
  # shells — a script invoked as `./run-adk.sh` never sources it, so that
  # override is NOT active here even on a laptop where it's set up and the
  # manual command works. Route explicitly through the public registry +
  # system CA bundle ourselves, matching that script's RUN() helper, so the
  # proxy starts the same way whether this is run by hand or by the script.
  NPX_ARGS=(--yes --registry="$PUBLIC_NPM_REGISTRY")
  if [ -f "$SYSTEM_CAFILE" ]; then
    NPX_ARGS+=(--cafile="$SYSTEM_CAFILE")
  fi
  echo "Starting Copilot proxy: npx ${NPX_ARGS[*]} copilot-api@latest start --port $PROXY_PORT --proxy-env"
  nohup npx "${NPX_ARGS[@]}" copilot-api@latest start --port "$PROXY_PORT" --proxy-env \
    > /tmp/copilot-api.log 2>&1 &
  PROXY_PID=$!
  echo "  proxy pid $PROXY_PID, log: /tmp/copilot-api.log"
  STARTED_PROXY=true
else
  echo "copilot-api not found and no npx available — install Node.js, or start" >&2
  echo "the proxy yourself (see ./bin/setup-copilot-api.sh). Continuing anyway." >&2
fi

if $STARTED_PROXY; then
  for _ in $(seq 1 30); do
    curl -fsS --max-time 2 "$PROXY_BASE/models" >/dev/null 2>&1 && break
    sleep 1
  done
  if ! curl -fsS --max-time 2 "$PROXY_BASE/models" >/dev/null 2>&1; then
    echo "Proxy did not come up within 30s — check /tmp/copilot-api.log" >&2
    echo "(first run needs an interactive GitHub OAuth device-flow login;" >&2
    echo "on a corp-laptop/Nexus-pinned npm see ./bin/setup-copilot-api.sh)" >&2
  fi
fi

# Extra arguments are forwarded to the APPLICATION, appended to the engine flag
# this script already sets. Lets you do e.g.
#   ./run-adk.sh --server.port=8081
# See docs/design-java/CUSTOM-DOMAIN.md.

# Can we actually listen on this port? Checked before launching so a failure is a
# one-line note here rather than a Spring stack trace 20 seconds in.
port_is_bindable() {
  python3 - "$1" <<'PY' 2>/dev/null
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", int(sys.argv[1])))
except OSError:
    sys.exit(1)
finally:
    s.close()
PY
}

# The spring-boot plugin takes application arguments SPACE-separated in one -D
# property. Comma looks plausible (Maven splits comma for List<String> params
# elsewhere) but does NOT work here: the plugin passes the whole comma string
# through as a single argument, so `--triage.engine=adk,--server.port=80` reached
# Spring as engine="adk,--server.port=80" and failed enum binding at startup.
# Verified empirically against this plugin version.
join_args() { echo "$*"; }

# --- port selection ----------------------------------------------------------
# Same rule as run-deterministic.sh — see the long note there for why the script
# defaults to 80 while application.yml stays on 8080.
DEFAULT_PORT="80"
PORT=""
for arg in "$@"; do
  case "$arg" in --server.port=*) PORT="${arg#--server.port=}" ;; esac
done

APP_ARGS="--triage.engine=adk"
if [ "$#" -gt 0 ]; then
  APP_ARGS="$APP_ARGS $(join_args "$@")"
fi
if [ -z "$PORT" ]; then
  PORT="$DEFAULT_PORT"
  if ! port_is_bindable "$PORT"; then
    echo "" >&2
    echo "  Port 80 is not bindable here, so falling back to 8080." >&2
    echo "  (Windows binds 80 without elevation; macOS/Linux reserve ports below 1024.)" >&2
    echo "" >&2
    echo "  To get port 80 on Linux, run the one-time setup (persists across reboots):" >&2
    echo "      sudo ./bin/setup-custom-domain.sh" >&2
    echo "  It lowers the unprivileged-port floor so ./run-*.sh binds 80 as your normal" >&2
    echo "  user. Preferred over 'sudo ./run-*.sh', which would leave root-owned files" >&2
    echo "  in target/ and ~/.m2 and break later non-root builds." >&2
    echo "" >&2
    PORT="8080"
  fi
  APP_ARGS="$APP_ARGS --server.port=$PORT"
fi

echo "=== TriageMate — ADK live agent engine (D1) ==="
if [ "$PORT" = "80" ]; then echo "    http://localhost"; else echo "    http://localhost:$PORT"; fi
echo "    Run ./bin/e2-proxy-spike.sh any time to validate the full proxy chain."
exec mvn -Padk spring-boot:run -Dspring-boot.run.arguments="$APP_ARGS"
