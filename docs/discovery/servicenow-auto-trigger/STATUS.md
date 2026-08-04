# STATUS — DDS: Automatic trigger on ServiceNow incident creation

> ## ⏸️ SUPERSEDED 2026-07-29 by DDS [[servicenow-local-trigger]]
> This DDS deferred auto-trigger because ServiceNow (cloud) cannot reach the corp-network
> laptop — correct, **for inbound push**. `servicenow-local-trigger` inverted the
> direction: the app **polls** ServiceNow over the same outbound HTTPS channel it already
> uses (K1), which the operator verified works on the real corp laptop. **Automated
> triggering is therefore back in scope**, and K1 — not the manual trigger — is the
> current decision.
>
> Two statements below are superseded and must not be actioned:
> - "Demo uses the **manual trigger**" → manual is now the K3 *fallback*, not the plan.
> - The retained "**Flow Designer + public tunnel** (`ngrok http 8080`)" recommendation
>   (`4-decide/decision.md`, the "Production / PDI path" section) → this contradicts
>   that file's opening decision paragraph ("Cloudflare is
>   internal so public tunnels are out") and is ruled out on the corp laptop entirely.
>   Kept only as the *production/PDI* path for a future non-corp environment. The same
>   claim appears again in the "Bottom line" near the end of THIS file — also superseded.
>
> Flow Designer mechanics and the `POST /api/diagnose/{number}` contract remain valid.
> **The C-T\* constraints remain valid — and C-T3 ("insert-only") was vindicated.** The
> superseding K1 design initially specified an `sys_updated_on > cursor` poll, which J5's
> own work-note writes would have re-triggered in a loop. ✅ **Resolved 2026-07-30**: the
> built poller queries **`sys_created_on`** (immutable), which is exactly what C-T3
> intended. See CDS `J10-incident-poller` and FND-1 in
> [`docs/audit/found-issues-archive.md`](../../audit/found-issues-archive.md).

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
reachability** of our locally-run app from ServiceNow's cloud. ~~Demo: a public tunnel.~~
Production: a **MID Server**.

> ⏸️ **Superseded**: "Demo: a public tunnel" is ruled out (see the banner at the top) —
> the demo path is **K1 outbound polling** per [[servicenow-local-trigger]]. The MID
> Server remains a valid *production* option (K5 there).
