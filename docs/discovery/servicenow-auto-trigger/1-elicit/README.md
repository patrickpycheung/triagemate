# Phase 1 — Elicit

## Problem
Today the copilot is triggered manually (UI button / `POST /api/diagnose/{n}`). We want
it to fire **automatically the moment a qualifying incident is created** in ServiceNow,
so the two advisory comments are already on the ticket before a human opens it.

## What "qualifying" means (criteria)
A condition on the new incident, e.g.:
- `category = Software` (or a specific subcategory),
- `priority <= 2` (P1/P2 only), and/or
- `assignment_group = Service Desk` (untriaged), and/or
- a specific business service / CI.

The trigger must be **narrow** — we do not want it firing on every ticket (cost, noise).

## Constraints
- **C-T1 Reachability (the crux).** The app runs **locally / internally** (not deployed).
  ServiceNow runs in the **cloud** and cannot reach `localhost` or an internal host by
  default. Something must bridge cloud → our app.
- **C-T2 Non-blocking.** The trigger must not delay or block incident creation for the
  end user — the outbound call must be asynchronous / fire-and-forget.
- **C-T3 No re-trigger loops.** The copilot writes work-note comments; the trigger must
  fire on **create only** (insert), not on our own comment updates.
- **C-T4 Least privilege + auth.** The call into our app should carry a shared secret;
  the trigger should be scoped to the allowlisted criteria only.
- **C-T5 Zero app change.** Our endpoint (`POST /api/diagnose/{incidentNumber}`) already
  is the integration point — the trigger just needs to call it with the number.
- **C-T6 Admin access.** Building the trigger needs ServiceNow admin rights on an
  instance we control (a Personal Developer Instance / PDI is ideal for the demo).

## Success criteria (demo)
Create a qualifying incident in a ServiceNow instance → within seconds, the app runs and
the **two advisory comments appear on that ticket**, with no human interaction.
