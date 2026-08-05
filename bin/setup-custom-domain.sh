#!/usr/bin/env bash
# Point a friendly hostname at this machine, so the demo reads
# http://triagemate.auspost.com.au instead of http://localhost:8080.
#
# WHAT THIS ACTUALLY DOES: adds one line to your hosts file mapping the name to
# 127.0.0.1. Nothing is published, no DNS is registered, no traffic leaves the
# machine — the name resolves on this laptop only. It is cosmetic-but-convincing.
# Say that plainly if anyone asks; it is not a deployment.
#
# Works on Windows (Git Bash / MSYS, run as Administrator), macOS and Linux (sudo).
# Idempotent: safe to run repeatedly. Undo with --remove.
#
# RENAMING the hostname: change DEFAULT_HOST below (or pass the new name as an
# argument) and re-run. Any name THIS SCRIPT previously added is retired in the same
# pass, so you end up with one mapping, not two — the old name stops resolving instead
# of quietly continuing to work. Entries you or your IT team added by hand are never
# touched; if the old name still resolves after a re-run, that is why, and the --check
# output will show it.
#
# The default was triagemate.auspost.local until 2026-08-05. NOTE that the current
# default sits under a REAL public domain (auspost.com.au): the hosts entry shadows
# whatever public DNS says for that exact name, and a corporate proxy/PAC file may route
# *.auspost.com.au to the proxy rather than honouring hosts at all — if the browser
# can't reach it on the demo laptop, that is the first thing to check (curl works,
# browser doesn't ⇒ proxy). A made-up TLD had neither problem.
#
# Usage:
#   ./bin/setup-custom-domain.sh                 # add triagemate.auspost.com.au
#   ./bin/setup-custom-domain.sh myname.local    # add a different name
#   ./bin/setup-custom-domain.sh --remove        # remove whatever this script added
#   ./bin/setup-custom-domain.sh --check         # report status, change nothing
#
# Full background, port-80 options and no-admin fallbacks:
#   docs/design-java/CUSTOM-DOMAIN.md
set -uo pipefail

DEFAULT_HOST="triagemate.auspost.com.au"
MARKER="# added by TriageMate setup-custom-domain.sh"

# Linux reserves ports below 1024 for root, so an unprivileged `./run-*.sh` cannot
# bind 80 and falls back to 8080 — which defeats the whole point of the nice
# hostname. Lowering ip_unprivileged_port_start to 80 fixes that for every user on
# the machine, permanently, with no root needed at RUN time.
#
# Chosen over the alternatives deliberately:
#   sudo ./run-*.sh      leaves root-owned files in target/ and ~/.m2, breaking
#                        later non-root builds — a genuinely annoying thing to debug
#   setcap on java       breaks on every JDK update, and grants the capability to
#                        every Java process on the box, not just this app
#   iptables REDIRECT    more moving parts, and invisible when someone later wonders
#                        why :80 behaves oddly
# This is one file, greppable, and reversible with --remove.
# TRIAGEMATE_SYSCTL_FILE overrides the target for testing, same reason as
# TRIAGEMATE_HOSTS_FILE above — this writes to /etc and must not ship untested.
SYSCTL_FILE="${TRIAGEMATE_SYSCTL_FILE:-/etc/sysctl.d/99-triagemate-unprivileged-port.conf}"
SYSCTL_KEY="net.ipv4.ip_unprivileged_port_start"

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

# --- unprivileged port 80 (Linux only) ---------------------------------------
# On macOS there is no equivalent knob (and no ip_unprivileged_port_start), so these
# are no-ops there; on Windows port 80 is already bindable unprivileged.
port80_supported() {
  [ "$PLATFORM" = "test" ] && return 0    # exercised via TRIAGEMATE_SYSCTL_FILE
  [ "$PLATFORM" = "unix" ] && [ -d /etc/sysctl.d ] \
    && [ -e "/proc/sys/net/ipv4/ip_unprivileged_port_start" ]
}

port80_current() { cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2>/dev/null || echo "?"; }

port80_enabled() { [ "$(port80_current)" -le 80 ] 2>/dev/null; }

port80_enable() {
  port80_supported || return 0
  if port80_enabled && [ -f "$SYSCTL_FILE" ]; then
    echo "Unprivileged port 80: already enabled."
    return 0
  fi
  printf '# Lets TriageMate (and anything else) bind port 80 without root.\n# Added by bin/setup-custom-domain.sh — remove with --remove.\n%s=80\n' \
    "$SYSCTL_KEY" > "$SYSCTL_FILE"
  # Apply now as well as persisting, so this shell session benefits immediately
  # rather than only after the next reboot.
  sysctl -q -w "$SYSCTL_KEY=80" 2>/dev/null
  if port80_enabled; then
    echo "Unprivileged port 80: enabled (now, and persisted in $SYSCTL_FILE)."
  else
    echo "WARNING: wrote $SYSCTL_FILE but $SYSCTL_KEY is still $(port80_current)." >&2
    echo "         ./run-*.sh will keep falling back to 8080." >&2
  fi
}

port80_disable() {
  port80_supported || return 0
  [ -f "$SYSCTL_FILE" ] || { echo "Unprivileged port 80: nothing to undo."; return 0; }
  rm -f "$SYSCTL_FILE"
  # Back to the kernel default. Not simply "whatever it was before" — we only ever
  # set it from a default system, and guessing a prior custom value would be worse
  # than restoring the documented default.
  sysctl -q -w "$SYSCTL_KEY=1024" 2>/dev/null
  echo "Unprivileged port 80: disabled (removed $SYSCTL_FILE, reset to 1024)."
}

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
  if port80_supported; then
    echo
    echo "Unprivileged port 80:"
    if port80_enabled; then
      echo "    ENABLED ($SYSCTL_KEY = $(port80_current)) — ./run-*.sh can bind 80"
    else
      echo "    disabled ($SYSCTL_KEY = $(port80_current)) — ./run-*.sh will use 8080"
    fi
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
  port80_disable
  if already_present; then
    echo
    echo "NOTE: $HOST is still mapped by an entry this script did not add:"
    grep -iE "^[^#]*[[:space:]]${HOST_RE}([[:space:]]|$)" "$HOSTS" | sed 's/^/    /'
    echo "Left untouched — remove it by hand if you want the name gone."
  fi
  exit 0
fi

# --- add ---------------------------------------------------------------------
# Retire any name this script previously added that is NOT the one we're adding now.
# Without this, renaming the host (triagemate.auspost.local → .com.au, 2026-08-05) and
# re-running leaves the OLD name mapped as well: it still resolves, the app still answers
# on it, and nothing says which one is current — so a stale bookmark or a colleague's
# muscle memory keeps working and hides the rename until it matters.
#
# Only lines carrying $MARKER are touched, for the same reason --remove is that careful:
# a hand-added or IT-managed entry is not ours to delete from a file this central.
retire_previous_names() {
  HOST_RE="$(printf '%s' "$HOST" | sed 's/\./\\./g')"
  # Marker-bearing lines that do NOT map the current hostname.
  STALE="$(grep -E "^[^#]*${MARKER}" "$HOSTS" 2>/dev/null \
           | grep -vE "[[:space:]]${HOST_RE}([[:space:]]|$)" || true)"
  [ -n "$STALE" ] || return 0

  BACKUP="${HOSTS}.triagemate.bak"
  cp "$HOSTS" "$BACKUP" && echo "backup: $BACKUP"
  echo "Retiring name(s) this script added for an older hostname:"
  printf '%s\n' "$STALE" | sed 's/^/    /'

  TMP="$(mktemp)"
  # Drop every marker line, then put back the one for the CURRENT host (if any), so an
  # idempotent re-run is unaffected and only genuinely stale names are dropped.
  grep -vE "^[^#]*${MARKER}" "$HOSTS" > "$TMP" || true
  grep -E "^[^#]*${MARKER}" "$HOSTS" | grep -E "[[:space:]]${HOST_RE}([[:space:]]|$)" >> "$TMP" || true
  cat "$TMP" > "$HOSTS" && rm -f "$TMP"
}
retire_previous_names

if already_present; then
  echo "Already mapped — nothing to do:"
  grep -iE "^[^#]*[[:space:]]$(printf '%s' "$HOST" | sed 's/\./\\./g')([[:space:]]|$)" "$HOSTS" | sed 's/^/    /'
else
  BACKUP="${HOSTS}.triagemate.bak"
  # Only back up if retire_previous_names hasn't already done so this run — otherwise the
  # second copy would overwrite the pre-change backup with the half-changed file.
  [ -n "${STALE:-}" ] || { cp "$HOSTS" "$BACKUP" && echo "backup: $BACKUP"; }
  # Leading newline guards against a hosts file with no trailing newline, which
  # would otherwise splice our entry onto the end of the last existing line.
  printf '\n127.0.0.1\t%s\t%s\n' "$HOST" "$MARKER" >> "$HOSTS"
  echo "Added: 127.0.0.1  $HOST"
fi

echo
port80_enable

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

  ./run-deterministic.sh        # serves on port 80 → http://$HOST

On Linux this script also lowered the unprivileged-port floor, so ./run-*.sh binds
port 80 as your normal user — no sudo needed to RUN, only to set up (just now).
That persists across reboots. If port 80 is already taken by another server, pass
--server.port=N instead.

Undo:  $0 --remove
More:  docs/design-java/CUSTOM-DOMAIN.md
EOF
