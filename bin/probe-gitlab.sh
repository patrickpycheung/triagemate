#!/usr/bin/env bash
# Probe the real GitLab API for the calls TriageMate makes, and write a shareable report.
#
# Why this exists: GitLab is the one connector unreachable from off the corp network (the
# perimeter 403s identically with and without a token), so questions about what it actually
# returns cannot be answered from a dev machine. This runs the exact requests
# RealGitLabGateway issues, prints the answers, and writes them to a file you can commit and
# push so they can be read by someone who cannot make the call themselves.
#
#   ./bin/probe-gitlab.sh                       # default project + the 3 INC0010015 files
#   ./bin/probe-gitlab.sh <project> [file ...]  # any allowlisted project / paths
#
# Reads triage.integrations.gitlab.* from secrets.properties. Read-only: every request is a
# GET, nothing is written to GitLab.
set -euo pipefail
cd "$(dirname "$0")/.."

SECRETS="secrets.properties"
[ -f "$SECRETS" ] || { echo "secrets.properties not found — copy secrets.properties.example." >&2; exit 1; }

prop() { grep -E "^$1[[:space:]]*=" "$SECRETS" | head -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'; }
BASE=$(prop 'triage\.integrations\.gitlab\.base-url')
TOKEN=$(prop 'triage\.integrations\.gitlab\.token')
[ -n "$BASE" ] && [ -n "$TOKEN" ] || { echo "gitlab base-url/token missing from $SECRETS" >&2; exit 1; }

PROJECT="${1:-enterprise/parcel-systems/applications/delivery-hazards}"
shift || true
if [ "$#" -gt 0 ]; then
  FILES=("$@")
else
  # The three files the INC0010015 code search implicated, which all returned 0 committers.
  FILES=(
    "src/main/java/au/com/auspost/hazards/service/AddressService.java"
    "src/main/java/au/com/auspost/hazards/service/RoundService.java"
    "src/main/java/au/com/auspost/hazards/web/controller/rest/SnapSendSolveRestControllerV1.java"
  )
fi

# GitLab wants the project path URL-encoded exactly ONCE (group%2Fname). Encoding it twice
# resolves to a project that does not exist and 404s — the same bug J22 fixed in the gateway,
# so this script must not reintroduce it in its own URLs.
ENC_PROJECT=$(printf '%s' "$PROJECT" | sed 's|/|%2F|g')
API="$BASE/api/v4/projects/$ENC_PROJECT"
OUT="gitlab-probe-$(date +%Y%m%d-%H%M%S).txt"

# Never let the token reach the report or the terminal: it is passed via a header file that
# curl reads, so it stays out of the process list too.
HDR=$(mktemp); trap 'rm -f "$HDR"' EXIT
printf 'PRIVATE-TOKEN: %s\n' "$TOKEN" > "$HDR"
get() { curl -sS -o "$2" -w '%{http_code}' -H @"$HDR" "$1" 2>/dev/null || echo 000; }

{
  echo "=== GitLab probe ==="
  echo "when:    $(date -Is)"
  echo "host:    $BASE"
  echo "project: $PROJECT"
  echo ""

  echo "--- [1] reachability + project resolves ---"
  BODY=$(mktemp)
  CODE=$(get "$API" "$BODY")
  echo "GET /projects/<id> → HTTP $CODE"
  if [ "$CODE" = "200" ]; then
    python3 -c "
import json,sys
d=json.load(open('$BODY'))
print('  path:', d.get('path_with_namespace'))
print('  default_branch:', d.get('default_branch'))
print('  last_activity_at:', d.get('last_activity_at'))" 2>/dev/null || echo "  (unparseable body)"
  else
    echo "  STOP: the project did not resolve. 401/403 = token; 404 = wrong path or no access."
    head -c 300 "$BODY"; echo ""
  fi
  echo ""

  echo "--- [2] the release boundary (newest tag) ---"
  # This is what the gateway uses as the 'since' cutoff. No tags → no cutoff at all.
  TAGS=$(mktemp)
  CODE=$(get "$API/repository/tags?per_page=1" "$TAGS")
  echo "GET /repository/tags?per_page=1 → HTTP $CODE"
  SINCE=""
  if [ "$CODE" = "200" ]; then
    # One line of "<name> <committed_date>", empty when the repo has no tags.
    TAGLINE=$(python3 -c "
import json
d=json.load(open('$TAGS'))
if d:
    print(d[0].get('name',''), d[0].get('commit',{}).get('committed_date',''))" 2>/dev/null || true)
    if [ -n "$TAGLINE" ]; then
      echo "  newest tag: $TAGLINE"
      SINCE=$(printf '%s' "$TAGLINE" | awk '{print $2}')
    else
      echo "  NO TAGS — the gateway skips the since-filter entirely for this repo."
    fi
  fi
  echo ""

  echo "--- [3] per-file commit history ---"
  echo "For each file: (a) commits since the tag — what the gateway asked FIRST;"
  echo "               (b) commits with no time bound — what the new fallback asks."
  echo ""
  for FILE in "${FILES[@]}"; do
    echo "  FILE: $FILE"
    ENC_FILE=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$FILE")

    if [ -n "$SINCE" ]; then
      B=$(mktemp)
      CODE=$(get "$API/repository/commits?path=$ENC_FILE&per_page=20&since=$SINCE" "$B")
      N=$(python3 -c "import json;print(len(json.load(open('$B'))))" 2>/dev/null || echo '?')
      echo "    (a) since $SINCE → HTTP $CODE, $N commit(s)"
    else
      echo "    (a) skipped — no tag to bound by"
    fi

    B2=$(mktemp)
    CODE=$(get "$API/repository/commits?path=$ENC_FILE&per_page=5" "$B2")
    N2=$(python3 -c "import json;print(len(json.load(open('$B2'))))" 2>/dev/null || echo '?')
    echo "    (b) unbounded    → HTTP $CODE, $N2 commit(s)"
    if [ "$CODE" = "200" ] && [ "$N2" != "0" ] && [ "$N2" != "?" ]; then
      python3 -c "
import json
for c in json.load(open('$B2')):
    print('        ', c.get('committed_date'), '|', c.get('author_name'), '|', c.get('author_email'))"
    elif [ "$CODE" = "200" ]; then
      echo "         (no history for this path — check it matches the repo exactly, incl. case)"
    else
      head -c 200 "$B2"; echo ""
    fi
    echo ""
  done

  echo "--- verdict guide ---"
  echo "  (a)=0 and (b)>0            → working as designed; the fallback now uses (b)."
  echo "  no tags and (b)>0          → BUG: unbounded query should already have returned these."
  echo "  (b)=0 for every file       → path/ref mismatch: the commits API is not seeing these paths."
  echo "  any HTTP 401/403           → token scope (needs read_api / read_repository)."
} 2>&1 | tee "$OUT"

echo ""
echo "Report written to: $OUT"
echo "Share it with:  git add -f $OUT && git commit -m 'gitlab probe results' && git push"
echo "(No token appears in the report — check before sharing anyway.)"
