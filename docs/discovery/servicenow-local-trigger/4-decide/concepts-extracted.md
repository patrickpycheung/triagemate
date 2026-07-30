# Concepts extracted (Phase 4 → feeds the Copilot-CLI DDS)

## Decision
**Recommended trigger mechanism: outbound polling (Exploration B), with manual trigger
(D) as the always-on fallback.** Push-via-tunnel (A) is infeasible on the corp laptop;
MID Server (C) is the future sanctioned-production path, not the hackathon path.

## Concepts for design (CDS)

> ✅ **K1–K3 IMPLEMENTED 2026-07-30** → CDS card **`J10-incident-poller`**
> (`docs/design-java/concepts/J10-incident-poller/`). Off by default, offline-verified,
> 10 unit tests. See that card for the built design; the notes below are the original
> extraction, **with two corrections marked** where the DDS wording would have produced a
> broken poller.

- **K1 — Incident poller**: a `@Scheduled` bean that queries ServiceNow for new
  incidents since a cursor and hands each to the existing `DiagnosisOrchestrator`.
  Config: interval, `sysparm_query`, `sysparm_limit`.
  ⚠️ **Correction**: this said query on **updated-since**. That is self-triggering — J5
  posts two work notes per run, each bumping `sys_updated_on` past the cursor, so the next
  tick re-selects the same incident and re-runs the whole diagnosis in a loop. The built
  poller queries **`sys_created_on`** (immutable), which is also what `C-T3: insert-only`
  in [[servicenow-auto-trigger]] always intended. Logged as **FND-1**, now fixed.
- **K2 — Idempotency/cursor**: persist a cursor and mark handled incidents so
  restarts/overlap don't double-triage.
  ⚠️ **Correction**: the cursor is **not** "max `sys_updated_on`" (see K1), and a naive
  "newest handled" high-water mark is *also* wrong — if an early incident fails while a
  later one succeeds, the cursor moves past the failure and loses it permanently. The built
  poller advances only across an **unbroken run of handled incidents**, oldest first, and
  never to wall-clock `now()` (which would drop anything created during batch processing).
  **Still open**: persistence. Cursor + completed-set are in-process only, so a restart
  re-seeds to "now" and skips incidents created while down. Skipping is the safe direction;
  durable state is an open item on the J10 card.
- **K3 — Trigger-mode switch**: implemented as **`triage.trigger.poll.enabled`** (default
  `false`) rather than `triage.trigger=manual|poll` — the manual `POST /api/diagnose/{n}`
  endpoint is always available, so the poller is an additive switch, not a mode that
  disables the other path. Same one-flip intent.
- **K4 — Proxy-aware egress** *(contingency only — not required)*: the 2026-07-29 spike
  reached the instance **directly**, so no proxy is needed. Retained in case egress policy
  changes: document/support JVM proxy settings (`-Dhttps.proxyHost/-Dhttps.proxyPort`).
- **K5 (deferred)** — MID Server productionization path, if/when IT sanctions it.

## Impact on the Copilot-CLI DDS (the reason we ran this first)
Connectivity does NOT cap that DDS at interactive-only: **headless/automated is
achievable via K1 polling.** The remaining *connectivity* question is closed.

What is still open in that DDS is **not** connectivity but: **C6** (licensing/ToS for
programmatic + unattended use — the hard gate for headless), **C1** (does a proxy
authenticate with the corp Copilot seat, and is the proxy binary permitted by endpoint
policy?), and **C2** (end-to-end run through the proxy). C6 alone does not clear headless
— C1/C2 must also pass. See that DDS's spike list.

## GATING SPIKE — ✅ RESOLVED 2026-07-29 (operator-run, on the real corp laptop) 🔬
**Outcome: outbound HTTPS + basic auth to the ServiceNow instance WORKS. No proxy
workaround was needed.** K1 outbound polling is therefore confirmed viable and this
unknown is CLOSED — see `STATUS.md`. The commands below are retained for reproduction
only; do **not** read them as outstanding work.

<details><summary>Original spike (kept for reproduction)</summary>

Confirm the app's one hard dependency: outbound HTTPS to the ServiceNow instance.

```bash
# 1. Direct reachability (expect HTTP 200 or 401 — both prove the host is reachable):
curl -sS -o /dev/null -w "%{http_code}\n" \
  -u "$SNOW_USER:$SNOW_PASSWORD" \
  "https://<instance>.service-now.com/api/now/table/incident?sysparm_limit=1"

# 2. If step 1 hangs/fails, you're likely behind a corporate proxy — retry via it:
curl -sS -o /dev/null -w "%{http_code}\n" \
  --proxy "http://<corp-proxy-host>:<port>" \
  -u "$SNOW_USER:$SNOW_PASSWORD" \
  "https://<instance>.service-now.com/api/now/table/incident?sysparm_limit=1"
```

- **200/401 direct** → K1 polling works with no extra config. ✅
- **Only works via proxy** → set the JVM proxy for the app:
  `-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>` (add `http.nonProxyHosts` as
  needed). K1 still works. ✅
- **Neither works** → the instance is unreachable from the laptop; *no* mode works
  (even manual needs it) — escalate to IT for an egress allowlist to the instance. 🔴

Report the HTTP code back and this unknown closes.

**Actual result (2026-07-29): case 1 — direct 200/401. K1 confirmed, no proxy needed.**

</details>
