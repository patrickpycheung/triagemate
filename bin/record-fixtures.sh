#!/usr/bin/env bash
# Capture REAL connector responses for one incident and write them to fixtures/,
# so `./run-deterministic.sh` (mock mode) replays the real estate offline.
#
# Why record rather than hand-write: mock data invented from nothing proves the engine
# works and proves nothing about the estate. A fixture is what the live systems actually
# returned, so the mocked run is the real run minus the network — same fields, same gaps,
# same surprises.
#
#   ./bin/record-fixtures.sh INC0010015
#
# Requires secrets.properties (copy secrets.properties.example) with credentials for
# whichever connectors you want to capture. Connectors you cannot reach can be captured
# separately, on a machine that can — the bundles are per-gateway files, so recording
# GitLab later merges into the same incident directory instead of clobbering it.
#
# To capture ONLY some connectors, pass them explicitly instead of the default "all":
#   ./bin/record-fixtures.sh INC0010015 --triage.connectors.gitlab=real \
#       --triage.connectors.servicenow=mock ...
#
# The run posts NO work notes: writeback is forced off below. Recording is an observation,
# and a capture run should not leave advisory comments on a real ticket every time you
# re-record.
set -euo pipefail
cd "$(dirname "$0")/.."

INCIDENT="${1:-}"
if [ -z "$INCIDENT" ]; then
  echo "usage: ./bin/record-fixtures.sh <INCIDENT-NUMBER> [extra --app.args]" >&2
  exit 1
fi
shift || true

if [ ! -f secrets.properties ]; then
  echo "secrets.properties not found — copy secrets.properties.example and fill it in." >&2
  exit 1
fi

PORT="${RECORD_PORT:-8099}"   # not 80/8080: recording should never collide with a demo already running
FIXTURE_DIR="src/main/resources/fixtures/$INCIDENT"

echo "=== Recording real connector responses for $INCIDENT ==="
echo "    → $FIXTURE_DIR/"
echo ""

# --spring.profiles.active=real makes all four connectors live; any explicit
# --triage.connectors.* passed by the caller overrides it per connector.
mvn -q spring-boot:run -Dspring-boot.run.arguments="\
--spring.profiles.active=real \
--triage.record.enabled=true \
--triage.writeback.enabled=false \
--triage.trigger.poll.enabled=false \
--server.port=$PORT $*" &
APP_PID=$!
# Kill the whole process group: spring-boot:run forks a child JVM, and killing only the
# Maven wrapper leaves that child holding the port.
trap 'kill -- -$APP_PID 2>/dev/null || kill $APP_PID 2>/dev/null || true' EXIT

echo "waiting for the app to come up on :$PORT ..."
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/api/ui-config" 2>/dev/null; then
    break
  fi
  sleep 2
done

echo "running the deterministic diagnosis against the live systems ..."
HTTP=$(curl -sS -o /tmp/record-$INCIDENT.json -w '%{http_code}' -X POST -H 'X-Triage-Local: 1' \
  "http://127.0.0.1:$PORT/api/diagnose/$INCIDENT" || echo 000)

echo ""
if [ "$HTTP" != "200" ]; then
  echo "  diagnose returned HTTP $HTTP — see /tmp/record-$INCIDENT.json and the app log above." >&2
  echo "  Any connector that DID answer before the failure has still been recorded; check below." >&2
fi

echo "=== Recorded files ==="
ls -la "$FIXTURE_DIR" 2>/dev/null || echo "  (nothing written — no connector returned successfully)"
echo ""
echo "Replay them offline with:   ./run-deterministic.sh   → diagnose $INCIDENT"
