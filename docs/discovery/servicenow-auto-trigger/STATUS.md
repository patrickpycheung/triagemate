# STATUS — DDS: Automatic trigger on ServiceNow incident creation

**Current Phase**: ✅ Lean DDS complete → **auto-trigger DEFERRED for the demo**
(operator decision 2026-07-24: cloud dev instance can't reach the corp-network laptop;
no public tunnel / MID Server). Demo uses the **manual trigger**; instead we shipped
**real ServiceNow comment write-back** so the demo shows a real ticket updating.
Auto-trigger design + production path retained for future revival.
**Started**: 2026-07-23
**Relationship**: Fleshes out the "if we have time" upgrade noted in J1 and the original
DDS **RC4 — Trigger** ("chat-invoked demo now; autonomous on-ticket trigger = future").
Our endpoint already supports it with **zero code change**: `POST /api/diagnose/{number}`.
**Rigor**: Hackathon/RAPID; grounded in current ServiceNow docs (see decision.md sources).

## The question
When a new ServiceNow incident is created **matching specific criteria** (category /
priority / assignment group …), automatically call the copilot so it posts its two
advisory comments — no human clicking "Diagnose".

## Outputs
- `1-elicit/README.md` — problem + constraints (esp. reachability of a local app).
- `2-diverge/README.md` — the two independent choices: **trigger mechanism** ×
  **reachability**.
- `4-decide/decision.md` — recommendation (demo vs prod), spike **JS-3**, open questions.

## Bottom line
Two clean native trigger paths (**Flow Designer** recommended, **Business Rule +
RESTMessageV2** fallback). The real constraint isn't the trigger — it's **network
reachability** of our locally-run app from ServiceNow's cloud. Demo: a public tunnel.
Production: a **MID Server**.
