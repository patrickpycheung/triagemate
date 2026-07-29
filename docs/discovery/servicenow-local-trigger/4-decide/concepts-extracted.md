# Concepts extracted (Phase 4 → feeds the Copilot-CLI DDS)

## Decision
**Recommended trigger mechanism: outbound polling (Exploration B), with manual trigger
(D) as the always-on fallback.** Push-via-tunnel (A) is infeasible on the corp laptop;
MID Server (C) is the future sanctioned-production path, not the hackathon path.

## Concepts for design (CDS)
- **K1 — Incident poller**: a `@Scheduled` bean that queries ServiceNow for new
  incidents since a cursor and hands each to the existing `DiagnosisOrchestrator`.
  Config: interval, `sysparm_query` (state/assignment_group/updated-since), `sysparm_limit`.
- **K2 — Idempotency/cursor**: persist a cursor (max `sys_updated_on`) and mark handled
  incidents (a work-note marker or field) so restarts/overlap don't double-triage.
- **K3 — Trigger-mode switch**: `triage.trigger=manual|poll` (manual = today's
  `POST /api/diagnose/{n}`; poll = K1). Keeps the demo's human-driven path and the
  automated path as one config flip.
- **K4 — Proxy-aware egress**: document/support JVM proxy settings so the corp laptop
  reaches the instance through the corporate proxy.
- **K5 (deferred)** — MID Server productionization path, if/when IT sanctions it.

## Impact on the Copilot-CLI DDS (the reason we ran this first)
Connectivity does NOT cap that DDS at interactive-only: **headless/automated is
achievable via K1 polling.** The remaining headless question there is purely
**Copilot-CLI licensing/ToS for unattended use**, to be decided in that DDS.

## GATING SPIKE — run on the REAL corp laptop (only place it's valid) 🤔→🔬
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
