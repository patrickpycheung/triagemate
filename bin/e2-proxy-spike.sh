#!/usr/bin/env bash
# E2 proxy spike — run this ON THE CORPORATE LAPTOP (the one with the Copilot seat).
#
# Validates, in order, the four things the live demo (D1) depends on:
#   1. a local OpenAI-compatible proxy comes up on the Copilot seat
#   2. it lists models, and the id in secrets.properties is one of them
#   3. /v1/chat/completions answers a plain prompt
#   4. it answers a TOOL-CALLING request  <-- the one most likely to fail
#
# Step 4 is the real spike. TriageMate's ADK loop only works if the proxy passes
# `tools` through to the model and returns `tool_calls` back. Some Copilot proxies
# accept the field and silently drop it — you get prose where you need a tool call,
# and the agent loop degrades to a single-shot answer with no evidence trail.
#
# Usage:  ./bin/e2-proxy-spike.sh [base_url] [model]
# Default: http://localhost:4000/v1, model read from secrets.properties.

set -uo pipefail

BASE="${1:-http://localhost:4000/v1}"
SECRETS="$(dirname "$0")/../secrets.properties"
PASS=0; FAIL=0

ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
info() { echo "  ·  $*"; }

model_from_secrets() {
  [ -f "$SECRETS" ] || return 0
  grep -E '^triage\.integrations\.llm\.model=' "$SECRETS" | tail -1 | cut -d= -f2-
}
MODEL="${2:-$(model_from_secrets)}"

echo "=== E2 proxy spike ==="
echo "base-url: $BASE"
echo "model:    ${MODEL:-(unset)}"
echo

# --- 1 + 2: models -----------------------------------------------------------
echo "[1/4] GET /models"
MODELS_JSON="$(curl -sS --max-time 15 "$BASE/models" 2>&1)"
if [ -z "$MODELS_JSON" ] || ! echo "$MODELS_JSON" | grep -q '"data"'; then
  bad "no usable /models response — is the proxy running?"
  echo "     raw: ${MODELS_JSON:0:300}"
  echo
  echo "     start one:  npx copilot-api@latest        # OAuth device-flow, port 4000"
  echo "             or: litellm --config config.yaml  # github_copilot provider"
  exit 1
fi
IDS="$(echo "$MODELS_JSON" | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | cut -d'"' -f4)"
ok "proxy answered; $(echo "$IDS" | wc -l) model(s) exposed"
echo "$IDS" | sed 's/^/       /'

echo
echo "[2/4] configured model is served"
if [ -z "${MODEL:-}" ]; then
  bad "no model configured — set triage.integrations.llm.model in secrets.properties"
elif echo "$IDS" | grep -qx "$MODEL"; then
  ok "'$MODEL' is in the served list"
else
  bad "'$MODEL' is NOT served — pick one of the ids above and update secrets.properties"
fi

# --- 3: plain completion -----------------------------------------------------
echo
echo "[3/4] POST /chat/completions (plain)"
CHAT="$(curl -sS --max-time 60 "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer dummy' \
  -d "{\"model\":\"$MODEL\",\"temperature\":0,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly: PROXY-OK\"}]}" 2>&1)"
if echo "$CHAT" | grep -q '"content"'; then
  ok "completion returned"
  info "$(echo "$CHAT" | grep -oE '"content"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | cut -c1-160)"
else
  bad "no completion — check auth / rate limit"
  echo "     raw: ${CHAT:0:300}"
fi

# --- 4: tool calling (the actual spike) --------------------------------------
echo
echo "[4/4] POST /chat/completions (TOOL CALLING — the one that matters)"
TOOLS='[{"type":"function","function":{"name":"get_incident","description":"Fetch a ServiceNow incident by number","parameters":{"type":"object","properties":{"number":{"type":"string","description":"e.g. INC0012345"}},"required":["number"]}}}]'
TOOLCALL="$(curl -sS --max-time 60 "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer dummy' \
  -d "{\"model\":\"$MODEL\",\"temperature\":0,\"tools\":$TOOLS,\"messages\":[{\"role\":\"user\",\"content\":\"Look up incident INC0012345. Use the tool.\"}]}" 2>&1)"
if echo "$TOOLCALL" | grep -q '"tool_calls"'; then
  ok "proxy returned tool_calls — the ADK agent loop (D1) will work"
elif echo "$TOOLCALL" | grep -q '"content"'; then
  bad "answered with PROSE, not tool_calls — proxy is dropping the 'tools' field"
  echo "     => D1's agent loop will NOT make real tool calls through this proxy."
  echo "     => Try the other proxy (copilot-api <-> LiteLLM), or a different model id."
  echo "     => If neither works: demo D2 (deterministic) as primary; it needs no LLM."
else
  bad "tool-calling request errored"
  echo "     raw: ${TOOLCALL:0:300}"
fi

echo
echo "=== $PASS passed, $FAIL failed ==="
[ "$FAIL" -eq 0 ] && echo "E2 validated — set base-url/model in secrets.properties and run: ./run-adk.sh" \
                  || echo "E2 NOT validated — see failures above. D2 remains the guaranteed path."
exit $((FAIL > 0))
