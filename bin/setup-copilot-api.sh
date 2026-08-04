#!/usr/bin/env bash
# Set up & start copilot-api (the local OpenAI-compatible proxy for a GitHub
# Copilot seat — E2 in docs/discovery/copilot-cli-runtime) on a corporate
# laptop whose npm is pointed at an internal Nexus registry.
#
# WHY THIS SCRIPT EXISTS
# -----------------------
# On a laptop where `~/.npmrc` sets `registry=` to an internal Nexus repo,
# plain `npx copilot-api@latest` fails two different ways in sequence:
#
#   1. E401 "Unable to authenticate ... Sonatype Nexus Repository Manager"
#      — npm's default registry is the internal Nexus repo, and you have no
#        (or expired) creds for it. copilot-api is a PUBLIC npm package, not
#        something published internally, so the fix is to fetch it from the
#        public registry instead of fixing Nexus auth.
#
#   2. SELF_SIGNED_CERT_IN_CHAIN once you point at the public registry
#      — `~/.npmrc` also pins `cafile` to ONLY the internal Nexus CA cert,
#        which replaces (not adds to) npm's trust store. That CA doesn't
#        include the corporate TLS-interception root used for outbound
#        internet traffic (proxy re-signs certs, e.g. issuer "Forward Trust
#        CA"), so the public registry's cert chain fails validation. Fix:
#        pass the *system* CA bundle explicitly for this one command.
#
# Then, once copilot-api itself is running, it ALSO needs to reach GitHub
# through the corporate proxy — pass `--proxy-env` (a copilot-api flag) so it
# honours `http_proxy`/`https_proxy`. NOTE: `--proxy-env` is a `start`
# subcommand option, it must come AFTER `start`, not before it.
#
# What this script does:
#   1. Verifies the public npm registry is reachable (fails fast with a clear
#      message if it truly isn't — that would need an actual Nexus/network fix).
#   2. Runs `copilot-api auth` (GitHub OAuth device-flow) if no cached token.
#   3. Installs an `npx`-overriding shell function + `copilot-api` alias into
#      ~/.bashrc (idempotent — safe to re-run) so future plain
#      `npx copilot-api@latest` / `copilot-api` invocations just work.
#   4. Starts the proxy on the requested port and waits for /v1/models to
#      answer, OR prints a clear diagnosis if GitHub says the account has no
#      Copilot seat (a licensing issue, not something this script can fix).
#
# Usage:
#   ./bin/setup-copilot-api.sh [port]
#   ./bin/setup-copilot-api.sh --auth-only   # just do the OAuth step
#   ./bin/setup-copilot-api.sh --no-bashrc   # skip editing ~/.bashrc
#
# After this succeeds, run ./bin/e2-proxy-spike.sh to validate the full
# chain (models list, plain completion, tool-calling).

set -uo pipefail

PORT="4000"
AUTH_ONLY=false
SKIP_BASHRC=false
for arg in "$@"; do
  case "$arg" in
    --auth-only) AUTH_ONLY=true ;;
    --no-bashrc) SKIP_BASHRC=true ;;
    ''|*[!0-9]*) : ;; # ignore non-numeric args we don't recognise
    *) PORT="$arg" ;;
  esac
done

NPMJS_REGISTRY="https://registry.npmjs.org/"
SYSTEM_CAFILE="/etc/ssl/certs/ca-certificates.crt"
RUN() { npx --yes --registry="$NPMJS_REGISTRY" --cafile="$SYSTEM_CAFILE" copilot-api@latest "$@"; }

ok()   { echo "  ✅ $*"; }
bad()  { echo "  ❌ $*"; }
info() { echo "  ·  $*"; }

echo "=== copilot-api setup ==="

# --- 0. sanity: is the public registry actually reachable? -------------------
echo "[0/4] checking public npm registry is reachable"
if ! curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$NPMJS_REGISTRY" 2>/dev/null | grep -q '^2\|^3\|^4'; then
  bad "cannot reach $NPMJS_REGISTRY at all — this is a real network/proxy problem,"
  echo "     not just a Nexus-credentials one. Check http_proxy/https_proxy and"
  echo "     firewall rules before continuing."
  exit 1
fi
ok "public npm registry reachable"

if [ ! -f "$SYSTEM_CAFILE" ]; then
  bad "system CA bundle not found at $SYSTEM_CAFILE — adjust SYSTEM_CAFILE in this"
  echo "     script for your distro (e.g. /etc/pki/tls/certs/ca-bundle.crt on RHEL)."
  exit 1
fi

# --- 1. GitHub OAuth device-flow (skips if already logged in) ---------------
echo
echo "[1/4] GitHub auth (device-flow) — will skip if already logged in"
if RUN debug 2>/dev/null | grep -q "Token exists: Yes"; then
  ok "GitHub token already cached (~/.local/share/copilot-api/github_token)"
else
  info "no cached token — starting device-flow login. Follow the printed URL/code."
  RUN auth || { bad "auth failed — see output above"; exit 1; }
fi

if $AUTH_ONLY; then
  echo; ok "auth-only requested — stopping here."; exit 0
fi

# --- 2. wire up the shell so plain npx/copilot-api works from now on ---------
echo
echo "[2/4] wiring ~/.bashrc so 'npx copilot-api@latest' and 'copilot-api' just work"
if $SKIP_BASHRC; then
  info "--no-bashrc passed, skipping"
elif grep -q "npx-override for copilot-api" "$HOME/.bashrc" 2>/dev/null; then
  ok "already present in ~/.bashrc (skipping)"
else
  cat >> "$HOME/.bashrc" << 'BASHRC_EOF'

# npx-override for copilot-api (added by bin/setup-copilot-api.sh):
# plain `npx copilot-api@latest` / `copilot-api` fail with E401 on this
# laptop because npm's default registry is the internal Nexus repo, which
# needs creds copilot-api doesn't have. Route just this package through the
# public npm registry, using the system CA bundle so the corporate
# TLS-interception cert validates. --proxy-env (after any subcommand, e.g.
# `start`) makes copilot-api honour http(s)_proxy for its GitHub calls.
npx() {
  if [ "$1" = "copilot-api@latest" ] || [ "$1" = "copilot-api" ]; then
    shift
    command npx --yes --registry=https://registry.npmjs.org/ \
      --cafile=/etc/ssl/certs/ca-certificates.crt copilot-api@latest "$@" --proxy-env
  else
    command npx "$@"
  fi
}
alias copilot-api='npx copilot-api@latest'
BASHRC_EOF
  ok "added to ~/.bashrc — run 'source ~/.bashrc' (or open a new shell) to pick it up"
fi

# --- 3. start the proxy and wait for it to answer ----------------------------
echo
echo "[3/4] starting copilot-api on port $PORT"
LOG="$(mktemp /tmp/copilot-api-XXXX.log)"
RUN start --port "$PORT" --proxy-env > "$LOG" 2>&1 &
PID=$!
disown

for i in $(seq 1 15); do
  sleep 1
  if curl -sS --max-time 2 "http://localhost:$PORT/v1/models" 2>/dev/null | grep -q '"data"'; then
    echo
    ok "proxy is up and serving models on http://localhost:$PORT/v1"
    echo "     (PID $PID, log at $LOG)"
    echo
    echo "[4/4] next: point secrets.properties at it and run the E2 spike:"
    echo "     triage.integrations.llm.base-url=http://localhost:$PORT/v1"
    echo "     ./bin/e2-proxy-spike.sh"
    exit 0
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    break
  fi
done

echo
bad "proxy did not come up — log follows:"
echo "     -------------------------------------------------------------"
sed 's/^/     /' "$LOG"
echo "     -------------------------------------------------------------"
if grep -q "No access to GitHub Copilot found" "$LOG"; then
  echo
  bad "GitHub says this account has no Copilot seat assigned."
  echo "     This is a LICENSING issue, not an npm/proxy/registry one — no script"
  echo "     can fix it. Ask your GitHub org admin to assign you a Copilot seat,"
  echo "     or re-run './bin/setup-copilot-api.sh --auth-only' logged in as"
  echo "     a different account that has one (delete"
  echo "     ~/.local/share/copilot-api/github_token first to force re-login)."
fi
exit 1

