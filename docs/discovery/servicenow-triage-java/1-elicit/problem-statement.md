# Problem Statement — Incident Triage Copilot (Java POC)

> Reuses the original DDS 1-elicit
> (`docs/discovery/servicenow-triage/1-elicit/`). This file records only what the
> pivot **changes or sharpens**.

## The problem (unchanged)

When an incident is reported in ServiceNow, large amounts of time are lost to
**(1)** merely identifying what the issue actually is (poor descriptions; teams
unfamiliar with the systems they nominally support; teams not even knowing they
own the affected app) and **(2)** the ticket **bouncing between teams** before it
lands with the right owner.

## What we're building (sharpened)

A **local Spring Boot application** that, given an incident number, runs a
**bounded agentic investigation** and posts an **advisory** first-pass diagnosis
as a ServiceNow work note. The diagnosis clarifies the symptom, lists candidate
affected systems (ranked, with confidence), suggests the likely owning team, cites
evidence, and states what information is missing and what to check next.

It is a **copilot, not an autopilot**: no auto-reassign, no auto-close, no priority
change, no remediation. Every conclusion carries confidence + sources +
contradicting evidence + missing info.

## What the pivot changes

- **Runtime**: local Spring Boot (laptop), **not** Rovo/Forge, **not** deployed.
  Purpose is a **proof-of-concept demo** for the presentation.
- **Engine**: Google **ADK for Java** inside the Spring Boot process.
- **Connectors**: plain Java gateway services exposed to the agent as **tools**.
- **Rovo**: out of scope for the build; revisited only when we later discuss
  deployment (e.g. Rovo Agent Connector / A2A as a future UX front-end).

## Constraints (pivot-specific, additive to the original)

- **C-J1** Java 21 / Spring Boot 3.4 / Maven (team's stack; matches `auspost-mcp`).
- **C-J2** Runs locally with **mockable** connectors — the demo must work even if a
  real SaaS integration is not approved in time (mock ⇄ real behind one interface).
- **C-J3** Model access is an **enterprise OpenAI-compatible endpoint** (assume; the
  engine must be provider-neutral). No hard dependency on Gemini/Vertex.
- **C-J4** Treat all fetched content (ticket text, logs, wiki, code) as **untrusted
  input**; the agent may not exceed an allowlist of tools/scopes/limits.
- **C-J5** Scope to **one demonstration application** with a small ground-truth
  dataset (3–10 historical incidents, 1–2 wiki pages, 1 repo, a few logs).

## Success criteria (demo-measurable, from the analysis)

Judge by outcomes, not root-cause bingo:
- Produces a **clearer** structured summary than the raw ticket.
- Names the **correct application in its top-3** candidates.
- Names the **correct support team in its top-3**.
- Cites **useful evidence** (a past incident / KB / log line / source line).
- Suggests a **sensible next diagnostic action** and the **missing info**.
- Posts a clearly-labelled **advisory** work note without mutating ticket state.
