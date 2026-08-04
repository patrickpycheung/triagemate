#!/usr/bin/env bash
# Start TriageMate in DETERMINISTIC mode (D2) — offline, no LLM, cannot fail.
#
# This is the default engine and the stage safety net: it does log<->code
# correlation without a model, so it works with no network and no Copilot seat.
# Runs on port 80 by default so the demo URL has no ":8080" tail, falling back to
# 8080 (with a printed note) when 80 needs elevation or is already taken.
# run-adk.sh does the same, so run only one at a time unless you pass
# --server.port=N (see docs/design-java/DEMO-RUNBOOK.md, "The fallback flip").
#
# Usage: ./run-deterministic.sh [-- extra mvn args]
set -euo pipefail
cd "$(dirname "$0")"

command -v mvn >/dev/null 2>&1 || {
  echo "mvn not found. Install a JDK + Maven, e.g.:" >&2
  echo "  sudo apt install openjdk-21-jdk maven" >&2
  exit 1
}

# Extra arguments are forwarded to the APPLICATION (not to Maven). Lets you do e.g.
#   ./run-deterministic.sh --server.port=8081
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
# Default to 80 so the demo URL has no ":8080" tail — the point is for it to read
# like a deployed service. application.yml deliberately still says 8080: that is
# what plain `mvn spring-boot:run` and the e2e suite use, and port 80 is
# privileged on macOS/Linux, so defaulting the APP to 80 would make the test
# suite and everyday `mvn` runs need sudo. The scripts are the demo path; the
# framework default stays unprivileged.
#
# If 80 can't be bound (no privilege, or something else already has it) we fall
# back to 8080 and SAY SO, rather than dying with a stack trace or — worse —
# silently serving somewhere the printed URL doesn't point.
#
# An explicit --server.port=N always wins and is never second-guessed.
DEFAULT_PORT="80"
PORT=""
for arg in "$@"; do
  case "$arg" in --server.port=*) PORT="${arg#--server.port=}" ;; esac
done

if [ -z "$PORT" ]; then
  PORT="$DEFAULT_PORT"
  if ! port_is_bindable "$PORT"; then
    echo "" >&2
    echo "  Port 80 is not bindable here, so falling back to 8080." >&2
    echo "  (Windows binds 80 without elevation; macOS/Linux reserve ports below 1024.)" >&2
    echo "" >&2
    echo "  To actually get port 80 on Linux, lift the reservation once per boot:" >&2
    echo "      sudo sysctl net.ipv4.ip_unprivileged_port_start=80" >&2
    echo "  then re-run this script normally. Preferred over 'sudo ./run-*.sh', which" >&2
    echo "  would leave root-owned files in target/ and ~/.m2 and break later builds." >&2
    echo "" >&2
    PORT="8080"
  fi
  APP_ARGS="$(join_args "$@" "--server.port=$PORT")"
else
  APP_ARGS="$(join_args "$@")"
fi

echo "=== TriageMate — deterministic engine (D2), no LLM, offline ==="
if [ "$PORT" = "80" ]; then echo "    http://localhost"; else echo "    http://localhost:$PORT"; fi
exec mvn spring-boot:run -Dspring-boot.run.arguments="$APP_ARGS"
