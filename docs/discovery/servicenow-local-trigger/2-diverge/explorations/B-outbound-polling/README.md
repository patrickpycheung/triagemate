# Exploration B — Outbound polling (the app pulls) ⭐ WINNER

**Bias**: Constraint-driven / minimum-viable. **Verdict**: ✅ Promising — recommended.

## Idea
Invert the direction. Instead of ServiceNow pushing to the laptop, the **local app
polls ServiceNow's REST Table API** for new/updated incidents on a timer and triages
each new one automatically:

```
every N seconds:
  GET /api/now/table/incident?sysparm_query=<new since last cursor>&sysparm_fields=...
  for each new incident: orchestrator.run(number)   # existing J1 flow
  advance the cursor (max sys_updated_on / a "triaged" flag / assignment_group filter)
```

## Why it fits the corp laptop perfectly
- **Outbound-only HTTPS** to the ServiceNow instance — the *exact same channel*
  `RealServiceNowGateway` already uses to read tickets and PATCH work notes. If the app
  can function at all, polling works.
- **No inbound, no tunnel, no public URL, no new software** → sidesteps C3/C4/C5/C7 entirely.
- Honors corporate proxy automatically (JVM `https.proxyHost/Port` or `HTTPS_PROXY`).
- Runs **headless / no human** → preserves the "automated" option for the Copilot DDS.

## Trade-offs
- **Latency** = poll interval (e.g. 15–60 s), not instant. Fine for triage.
- **API volume**: one lightweight query per interval; use a narrow `sysparm_query`
  (state=new, assignment_group, updated-since cursor) + `sysparm_limit`. Idempotency:
  mark handled incidents (a field/work-note marker) so restarts don't double-triage.
- Not "event-driven" architecturally — but functionally equivalent for the goal.

## Trust / risk
🔍 Inferred (strong) · 🟢 Low–🟡 Medium. The only unproven bit is **outbound
reachability to the instance from the real laptop** (C-verify) — same dependency the
whole app already has. Cursor/idempotency is standard engineering.

## Build size
Small: one `@Scheduled` poller bean + a `findNewIncidents(cursor)` gateway method +
a persisted cursor. Reuses the entire existing orchestrator/engine unchanged.
