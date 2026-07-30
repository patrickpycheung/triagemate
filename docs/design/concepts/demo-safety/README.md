# C6 — Demo Safety (scope, fixtures, fallbacks)

**Level**: 🏘️ Neighborhood · **Complexity**: 🟦 Simple · **Convergence**: 🟢 Converged

## One-liner
Everything that keeps the live demo from failing: what's mocked, what's live, fallbacks,
and the honest caveat.

## Live vs mock
| Source | Demo state | Why |
|--------|-----------|-----|
| ServiceNow read/write | LIVE | The wow: real ticket updated on stage |
| GitLab master | LIVE | Real clickable file:line = credibility |
| Sumo logs | **MOCK fixture** | Flaky/slow/needs failure window → pre-seed |
| Confluence | LIVE (native Rovo) | Free, reliable |

## Seeded scenario (→ spike S3′)
A demo repo with a **known bug** that emits a distinctive log line, plus a matching Sumo
fixture, so C3's correlation lands convincingly and repeatably.

## Demo-prep checklist (must exist before the demo)
- Seeded GitLab project with `payment_service.py` (the S3′ bug) on master.
- Sumo fixture wired into `get-logs` for order `INC-ORD-4471`.
- A real **Confluence page** for the payment service (so the agent's step-4 lookup lands)
  — e.g. a short "Payments runbook / known issues" doc. (C1×C3 demo-prep item, R3.)
- A real ServiceNow ticket `INC0012345` referencing order `INC-ORD-4471`.
- The **two** Forge egress domains (ServiceNow, GitLab) declared + secrets set (S2′) —
  one-time, avoids mid-demo re-consent. (Sumo is mocked → no Sumo egress.)

## Fallbacks
- Bad ticket id → agent asks for a valid id (C4).
- Reasoning low-confidence → degraded "candidates" note, not a fake file:line (C3/R4).
- **Read** action timeout (get-ticket/get-source/get-logs) → cached/canned last-good
  response for the seeded ticket. **NEVER** cache-fallback the **write** (post-worknote):
  a timed-out write may have landed, so a canned retry could double-post (G4). On write
  timeout, surface "unknown outcome" instead.

## Honest caveat (must be said)
Demo autonomy = agent reasoning + actions, NOT auto-fire on ticket creation. Presenter
invokes in chat. Chat-invoked demo, webhook-triggered future.

## Depends on
Cross-cuts all. Owns the fixtures C2/C3 consume.
