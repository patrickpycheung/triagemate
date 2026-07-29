# Concepts extracted (Phase 4)

## Verdict
- **Quality gap (open-ended autonomy)**: Copilot CLI > our loop — the operator's instinct
  is directionally correct, *but* it's the wrong axis for this task.
- **On THIS bounded triage**: near-parity, because the **model dominates** (M4) and both
  can run a **high Copilot-served model** (Opus 4.6 / GPT-5.3) via the E2 proxy.
- **For the demo**: **our governed orchestration (G1) is MORE SUITABLE** — reliability,
  repeatability, control, offline safety net, and an auditable evidence trail beat opaque
  autonomy for a live, ticket-writing demo (M5).

## Recommended demo strategy (concepts for build)
- **D1 — Primary demo path = our orchestration on a high model.** Run `-Padk` `LlmAgent`
  pointed at the E2 Copilot proxy with `--model` set to a high tier. Best-of-both:
  high-quality reasoning + full control + trace/citations.
- **D2 — Guaranteed fallback = deterministic engine.** Offline, can't fail; the stage safety net.
- **D3 — Optional contrast segment = Copilot CLI, no tools.** A short segment handing the
  same incident to Copilot CLI *without* access to our four systems — *only if* network +
  ToS are safe, never the load-bearing path. Skippable without hurting the story.

  ⚠️ **Scope caveat (corrected 2026-07-29).** D3 was first written as "run the same incident
  **fully autonomously**". That is **not buildable under the scope already decided**: an
  autonomous run needs MCP servers fronting ServiceNow / Sumo / Confluence / GitLab, which
  is concept **C5** in DDS [[copilot-cli-runtime]] — explicitly deferred off the hackathon
  path — and no MCP servers exist in the repo (only the `E5-mcp-buildout` exploration).
  The same DDS also flagged Copilot CLI's **≥7-tool headless MCP bug** as a reason to avoid
  this path. D3 is therefore re-scoped to a **tool-less contrast**: it needs zero build,
  removes the MCP dependency and the headless bug from the stage, and reinforces D4 (the
  frontier model is identical — the delta on screen is purely the evidence trail).
  The binding constraint on D3 is the **missing tool layer**, not just network/ToS.
- **D4 — Lead the narrative with the evidence trail** (sources consulted, log↔code
  citation, advisory-only) — the differentiator judges can verify, and the thing autonomy
  can't guarantee.

## What NOT to do
- Don't make **Copilot CLI autonomous the primary live path** — non-determinism + the
  ≥7-tool headless bug + ToS/network exposure make it a stage-failure risk (M6).
- Don't over-invest building a fancier LangGraph-style orchestrator to "beat" Copilot —
  the reasoning ceiling is the model, not the loop (M4). Effort better spent on the
  evidence-trail story and demo reliability.

## Open items
- Pick the exact high model to demo once the E2 proxy is up on the corp laptop (spike
  from the [[triagemate-copilot-backend-decision]] DDS).
- ToS gate (E3) still governs whether the D3 flourish is allowed live.
