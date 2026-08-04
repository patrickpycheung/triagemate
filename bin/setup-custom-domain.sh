#!/usr/bin/env bash
# Point a friendly hostname at this machine, so the demo reads
# http://triagemate.auspost.local instead of http://localhost:8080.
#
# WHAT THIS ACTUALLY DOES: adds one line to your hosts file mapping the name to
# 127.0.0.1. Nothing is published, no DNS is registered, no traffic leaves the
# machine — the name resolves on this laptop only. It is cosmetic-but-convincing.
# Say that plainly if anyone asks; it is not a deployment.
#
# Works on Windows (Git Bash / MSYS, run as Administrator), macOS and Linux (sudo).
# Idempotent: safe to run repeatedly. Undo with --remove.
#
# Usage:
#   ./bin/setup-custom-domain.sh                 # add triagemate.auspost.local
#   ./bin/setup-custom-domain.sh myname.local    # add a different name
#   ./bin/setup-custom-domain.sh --remove        # remove whatever this script added
#   ./bin/setup-custom-domain.sh --check         # report status, change nothing
#
# Full background, port-80 options and no-admin fallbacks:
#   docs/design-java/CUSTOM-DOMAIN.md
set -uo pipefail

DEFAULT_HOST="triagemate.auspost.local"
MARKER="# added by TriageMate setup-custom-domain.sh"

ACTION="add"
HOSTNAME_ARG=""
for arg in "$@"; do
  case "$arg" in
    --remove) ACTION="remove" ;;
    --check)  ACTION="check" ;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    -*) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
    *)  HOSTNAME_ARG="$arg" ;;
  esac
done
HOST="${HOSTNAME_ARG:-$DEFAULT_HOST}"

# --- locate the hosts file ---------------------------------------------------
# TRIAGEMATE_HOSTS_FILE points this at a scratch file instead of the real one. It
# exists so the add/remove/idempotency logic can be tested without touching a
# machine's actual hosts file — this script is meant to be handed to other people
# and run elevated, so shipping it untested was not an option.
if [ -n "${TRIAGEMATE_HOSTS_FILE:-}" ]; then
  HOSTS="$TRIAGEMATE_HOSTS_FILE"
  PLATFORM="test"
# On Windows, $SYSTEMROOT is set even under Git Bash; translate it to a POSIX path.
elif [ -n "${SYSTEMROOT:-}" ] && [ -d "${SYSTEMROOT}" ]; then
  HOSTS="$(printf '%s' "$SYSTEMROOT" | sed 's|\\|/|g; s|^\([A-Za-z]\):|/\l\1|')/System32/drivers/etc/hosts"
  PLATFORM="windows"
elif [ -f /etc/hosts ]; then
  HOSTS="/etc/hosts"
  PLATFORM="unix"
else
  echo "ERROR: can't find a hosts file on this system." >&2
  exit 1
fi

if [ ! -f "$HOSTS" ]; then
  echo "ERROR: hosts file not found at: $HOSTS" >&2
  exit 1
fi

echo "hosts file: $HOSTS"
echo "hostname:   $HOST"
echo

already_present() { grep -qiE "^[^#]*[[:space:]]$(printf '%s' "$HOST" | sed 's/\./\\./g')([[:space:]]|$)" "$HOSTS"; }

# --- check -------------------------------------------------------------------
if [ "$ACTION" = "check" ]; then
  if already_present; then
    echo "PRESENT — $HOST is mapped in the hosts file:"
    grep -iE "^[^#]*[[:space:]]$(printf '%s' "$HOST" | sed 's/\./\\./g')([[:space:]]|$)" "$HOSTS" | sed 's/^/    /'
  else
    echo "ABSENT — $HOST is not mapped. Run without --check to add it."
  fi
  echo
  echo "Resolution test:"
  if getent hosts "$HOST" >/dev/null 2>&1 || ping -c1 -W1 "$HOST" >/dev/null 2>&1 \
     || ping -n 1 -w 1000 "$HOST" >/dev/null 2>&1; then
    echo "    resolves OK"
  else
    echo "    does NOT resolve"
  fi
  exit 0
fi

# --- writability check, with a platform-specific fix ------------------------
# Checked BEFORE attempting the edit: on Windows, a non-elevated write to hosts
# fails in ways that are easy to misread as success, so we refuse up front rather
# than half-doing it.
if [ ! -w "$HOSTS" ]; then
  echo "ERROR: no write permission on the hosts file." >&2
  echo >&2
  if [ "$PLATFORM" = "windows" ]; then
    echo "  Close this terminal, then re-open Git Bash with" >&2
    echo "  RIGHT-CLICK -> 'Run as administrator', and run this script again." >&2
  else
    echo "  Re-run with sudo:  sudo $0 $*" >&2
  fi
  exit 1
fi

# --- remove ------------------------------------------------------------------
if [ "$ACTION" = "remove" ]; then
  if ! already_present; then
    echo "Nothing to do — $HOST is not in the hosts file."
    exit 0
  fi
  HOST_RE="$(printf '%s' "$HOST" | sed 's/\./\\./g')"
  # Remove ONLY lines this script added — they carry $MARKER *and* the hostname.
  # A hand-added or IT-managed entry for the same name is deliberately left alone:
  # silently deleting a line we did not create, in a file this central on a managed
  # laptop, is not ours to do. Report it instead so the user isn't left wondering
  # why the name still resolves after a "successful" removal.
  if grep -qE "^[^#]*[[:space:]]${HOST_RE}([[:space:]]|$).*${MARKER}" "$HOSTS"; then
    BACKUP="${HOSTS}.triagemate.bak"
    cp "$HOSTS" "$BACKUP" && echo "backup: $BACKUP"
    TMP="$(mktemp)"
    grep -vE "^[^#]*[[:space:]]${HOST_RE}([[:space:]]|$).*${MARKER}" "$HOSTS" > "$TMP" || true
    cat "$TMP" > "$HOSTS" && rm -f "$TMP"
    echo "Removed $HOST (the entry this script added)."
  fi
  if already_present; then
    echo
    echo "NOTE: $HOST is still mapped by an entry this script did not add:"
    grep -iE "^[^#]*[[:space:]]${HOST_RE}([[:space:]]|$)" "$HOSTS" | sed 's/^/    /'
    echo "Left untouched — remove it by hand if you want the name gone."
  fi
  exit 0
fi

# --- add ---------------------------------------------------------------------
if already_present; then
  echo "Already mapped — nothing to do:"
  grep -iE "^[^#]*[[:space:]]$(printf '%s' "$HOST" | sed 's/\./\\./g')([[:space:]]|$)" "$HOSTS" | sed 's/^/    /'
else
  BACKUP="${HOSTS}.triagemate.bak"
  cp "$HOSTS" "$BACKUP" && echo "backup: $BACKUP"
  # Leading newline guards against a hosts file with no trailing newline, which
  # would otherwise splice our entry onto the end of the last existing line.
  printf '\n127.0.0.1\t%s\t%s\n' "$HOST" "$MARKER" >> "$HOSTS"
  echo "Added: 127.0.0.1  $HOST"
fi

# --- verify ------------------------------------------------------------------
echo
echo "Verifying..."
if getent hosts "$HOST" >/dev/null 2>&1 || ping -c1 -W1 "$HOST" >/dev/null 2>&1 \
   || ping -n 1 -w 1000 "$HOST" >/dev/null 2>&1; then
  echo "  $HOST resolves."
else
  echo "  WARNING: $HOST still does not resolve." >&2
  echo "  Try flushing the DNS cache:" >&2
  if [ "$PLATFORM" = "windows" ]; then
    echo "    ipconfig /flushdns" >&2
  else
    echo "    sudo dscacheutil -flushcache   (macOS)" >&2
    echo "    sudo systemd-resolve --flush-caches   (Linux)" >&2
  fi
fi

cat <<EOF

Done. Now start the app and open the name:

  ./run-deterministic.sh --server.port=80      # then http://$HOST
  ./run-deterministic.sh                       # then http://$HOST:8080

Port 80 needs elevation on macOS/Linux (use sudo, or just keep :8080 — the
hostname is doing most of the work visually either way). If port 80 is already
taken by IIS/Docker/another server, stay on 8080.

Undo:  $0 --remove
More:  docs/design-java/CUSTOM-DOMAIN.md
EOF
