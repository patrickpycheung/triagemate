# Phase 4 — Decision

## Operator decision (2026-07-24): DEFERRED for the demo
Given the environment — a ServiceNow **dev instance** we control (settings/automation
editable), a **corporate-network laptop** running the app, **Cloudflare is internal** so
public tunnels are out, and no MID Server on hand — auto-trigger from the cloud dev
instance to the laptop isn't reachable in time. **Decision: skip auto-trigger for the
demo; use the manual trigger** (type the incident number). Auto-trigger is presented as
clear future work (this doc + the production path below).

**In its place, we added real value the demo *can* show:** the app now writes its two
advisory comments to a **real dev ServiceNow ticket** (`triage.connectors.servicenow=real`
/ `snow-live` profile), so the presenter triggers manually and then shows the real ticket
updating live. See `app/README.md` → "Live demo: write the comments to a REAL ServiceNow
ticket."

## Recommendation (retained for when auto-trigger is revived)

**Demo path** — **Flow Designer (A1) + a public tunnel (B1)**:
1. Run the app locally; expose it with `ngrok http 8080` → a public HTTPS URL.
2. In a ServiceNow **PDI** (Personal Developer Instance), build a Flow:
   - Trigger: **Record Created** on **Incident**, condition e.g.
     `category = Software AND priority <= 2 AND assignment_group = Service Desk`.
   - Action: **Send HTTP Request** (REST) → `POST https://<tunnel>/api/diagnose/${record.number}`,
     header `X-Triage-Secret: <shared secret>`.
   - (No IntegrationHub? Use **Business Rule + RESTMessageV2** via an event/Script Action
     instead — same trigger condition, async so it doesn't block creation.)
3. Create a qualifying incident → the two advisory comments appear automatically.

**Production path** — **Flow Designer (A1) + MID Server (B2)**: identical Flow, but the
REST step routes through an on-prem MID Server to reach the internal app; no tunnel, no
inbound firewall holes.

## Why
- Flow Designer is ServiceNow's recommended path: visual trigger condition, native auth /
  retry / execution logs, maintainable by non-developers.
- The trigger is the easy part; **reachability is the real work** — a tunnel for the demo,
  a MID Server for real internal deployment.
- **Zero app change**: the trigger calls our existing `POST /api/diagnose/{number}`.

## Guardrails (carry into the build)
- **Insert-only** trigger → our comment writes never re-fire it (C-T3).
- **Async / non-blocking** so incident creation isn't delayed (C-T2).
- **Narrow criteria** (specific category/priority/group) so it doesn't fire on every
  ticket — cost + noise control (C-T4).
- **Shared-secret header** on the endpoint; validate it in a small Spring filter before
  running (the one small app addition worth making for real use).
- **Idempotency**: the app already skips an identical AI comment (J5), so a duplicate
  trigger is harmless.

## Spike JS-3 (prove it end-to-end)
On a ServiceNow PDI: app up + `ngrok`; a Flow (or Business Rule) on incident-create with
a test condition → `POST /api/diagnose/${number}`; create a matching incident; confirm
the two comments land within seconds. Timebox ~half a day.

## Open questions (need operator/environment facts before JS-3)
1. **IntegrationHub licensed?** → yes: Flow REST step. No: Business Rule + RESTMessageV2
   (or the scripted Flow Action fallback).
2. **Reachability for the demo** — is a public **tunnel** (ngrok/Cloudflare) acceptable,
   or must we use a **MID Server**?
3. **Which instance** — a ServiceNow **PDI** we control (recommended), or a corporate
   instance where admin/trigger approval is required?
4. **Exact criteria** — which category / priority / assignment group defines a
   "qualifying" incident for the demo?

## Sources
- [Outbound REST from ServiceNow — Business Rule vs Flow Designer](https://www.servicenow.com/community/) (SN Community / practitioner guides)
- [Flow Designer REST step & IntegrationHub spokes (scripted fallback if unlicensed)](https://www.servicenow.com/community/)
- [Async outbound REST via Business Rule + event/Script Action](https://www.servicenow.com/community/)
- [MID Server: architecture & when you need one](https://www.nowspectrum.com/blog/mid-server-guide)
- [Routing RESTMessageV2 through a MID Server (ECC Target / ECC queue)](https://www.servicenow.com/community/)
