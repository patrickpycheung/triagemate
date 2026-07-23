# Phase 4 — Decide (Java pivot DDS)

## Outcome
Selected approach: **Spring Boot orchestrator + ADK-Java (GA 1.7.0) bounded agent +
plain-Java tool gateways**, live enterprise OpenAI-compatible endpoint, advisory
work-note output. Five forks D1–D5 resolved (`decision.md`); spike JS-1 moved D1 to
🟢 Low Risk.

## Evaluation vs success criteria (Phase 1)
| Criterion | Met by |
|---|---|
| Clearer structured summary than raw ticket | J2 understand-step + J4 contract |
| Correct app in top-3 | J5 similar-incidents + CMDB, J6 Confluence |
| Correct team in top-3 | J5 ownership + resolution groups |
| Cites useful evidence | J4 evidence[] + J6 log↔code citation |
| Sensible next action + missing info | J4 contract fields |
| Advisory, no state mutation | J5 confirmed work note, J8 guardrails |

## Files
- `decision.md` — D1–D5 resolved + spike + operator inputs.
- `concepts-extracted.md` — J1–J8 seeds → CDS (`docs/design-java/`).

## Handoff status
Concepts J1–J8 are stable and already drafted in `docs/design-java/`. Remaining
verification is impl-time **JS-1b** (live round-trip) + **JS-2** (connectivity),
both auto-run on build day 1. → awaiting Phase 4 operator concept-confirmation.
