# PIVOT — Rovo-native → local Spring Boot + Google ADK

**Date**: 2026-07-23
**Trigger**: Requirements changed. The hackathon deliverable is now a **local
Spring Boot application** (run from a laptop for the presentation, **not deployed
anywhere**). Rovo/Forge is **deferred** to a later deployment discussion, and only
returns "on the picture" once the core app works.

This file is the one-screen map of what changed and where the active work lives.

---

## What the tool is (unchanged framing)

> **An AI-assisted TriageMate** that gathers evidence from ServiceNow,
> enterprise knowledge, logs and source repositories to clarify a reported issue,
> identify the likely affected system(s), and recommend the appropriate support
> team and next diagnostic action — posted as an **advisory** ServiceNow work note.

Its job is **not** to auto-reassign, auto-close, or declare a definitive root
cause. It gathers evidence and produces an explainable first-pass diagnosis with
confidence, sources, contradicting evidence, and missing information.

## What changed

| Dimension | Old (suspended) | New (active) |
|---|---|---|
| Host / runtime | Atlassian **Rovo + Forge** (cloud, low-code) | **Spring Boot** app, run **locally** for the demo |
| Agent engine | Rovo LLM (Copilot) | **Google ADK for Java** (`com.google.adk`) |
| Connectors | Forge **Actions** / Rovo skills | **ADK FunctionTools** backed by plain Spring gateway services |
| Language | JavaScript (Forge) | **Java 21 / Spring Boot 3.4** (Maven) |
| Deployment | Forge deploy | **None** — `mvn spring-boot:run` on a laptop |
| Rovo | The whole product | **Deferred** — revisited only at deployment stage |

## The three decisions (resolved in the new DDS)

1. **SDK vs manual loop → use the ADK SDK (ADK-Java).** It supplies the
   tool-calling loop, max-iteration bounds, before/after-tool callbacks (our
   guardrails), sessions and traces (our observability) — all things we'd
   otherwise hand-roll. It runs an **enterprise OpenAI-compatible model** via the
   LangChain4j wrapper, so we stay model-provider-neutral. Spring AI is the
   documented fallback if ADK-Java disappoints.
2. **Skills vs tools vs MCP → tools (plain Java gateways as ADK FunctionTools).**
   No MCP and no Rovo skills for the hackathon. MCP is for cross-platform reuse we
   don't need yet; it can wrap these same gateways later.
3. **Build fresh vs reuse → reuse `~/work/auspost-mcp`.** It is already Java 21 /
   Spring Boot 3.4 / Maven with working **GitLab (gitlab4j)** and **Confluence**
   integrations. Reuse those as gateway implementations; **skip** its MCP
   server/sidecar layer.

Full rationale: `docs/discovery/servicenow-triage-java/4-decide/decision.md`.

## Where the work lives now

- **Active DDS** (Java pivot): `docs/discovery/servicenow-triage-java/`
- **Later DDS** (2026-07-29, all Phase 4 complete — read these for the *current* trigger
  and LLM-backend decisions, which supersede parts of the above):
  - `docs/discovery/servicenow-local-trigger/` → **K1 outbound polling** is the trigger
    (supersedes the manual-only deferral in `servicenow-auto-trigger/`)
  - `docs/discovery/copilot-cli-runtime/` → **E2**: Copilot seat as an OpenAI-compatible
    LLM backend via a local proxy; **C6** ToS gate
  - `docs/discovery/orchestrator-vs-copilot-cli/` → demo shape **D1+D2+D3**
- **Live demo runbook**: `docs/design-java/DEMO-RUNBOOK.md`
- **Open design issues**: `/FOUND-ISSUES.md` (24 open: FND-9…FND-32, from `/doc-test cds`
  2026-07-30 — mostly J-card-vs-code drift; resolved entries in
  `docs/audit/found-issues-archive.md`)
- **Active CDS** (Java design): `docs/design-java/` — concepts **J1–J9** (J9
  contact-suggestion was added post-pivot, after this file's original J1–J8 list)
- **Suspended DDS**: `docs/discovery/servicenow-triage/` (⏸️ banner)
- **Suspended CDS**: `docs/design/` (⏸️ banner)
- **Paused scaffold**: `manifest.yml`, `src/`, `test/` (Forge/Rovo — kept, unused)

## Carried forward from the suspended work

Still valid and reused (not re-derived):
- **Problem statement, constraints, success criteria** (1-elicit).
- **RC3 — Log↔Code reasoning** → J6 (agent matches a log line to its emitting
  source line, cites file:line).
- **RC5 — Work-note postback** → J5 (advisory, **automatic** write — the human-confirm
  gate RC5 originally carried was **removed** on 2026-07-23; trust comes from *what the
  app may do* (post two labelled advisory comments, never touch assignment/state/
  priority), not from a human gate. See J5 "Write (automatic, two comments)" and J8
  "Guardrails").
- **RC6 — Demo safety** (fixtures, fallbacks, seeded bug) → J7.
- **S3′ Sumo fixture + seed-repo** (`docs/design/concepts/log-code-reasoning/`,
  `seed-repo/`) → reused as demo data for J7.

Superseded / dropped:
- **RC2 Rovo-agent**, **forge-actions**, **RC4 chat trigger** — Rovo/Forge-specific,
  paused. Their intent (single investigator, tool allowlist, manual trigger) is
  preserved in J1/J2/J3 in a Spring/ADK form. **Trigger update (2026-07-29)**: the manual
  trigger is now the *fallback* (K3) — **K1 outbound polling** is the current decision.
  See DDS `servicenow-local-trigger`.
