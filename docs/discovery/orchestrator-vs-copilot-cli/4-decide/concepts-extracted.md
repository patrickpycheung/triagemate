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
  **⚠️ D1 is gated by C6, not just D3.** C6 ([[copilot-cli-runtime]]) is a blocker on
  **programmatic** use of the corporate Copilot seat "incl. via a local proxy" — driving
  the proxy from our app IS programmatic use, so the ToS ruling governs the *primary*
  path. Current operating position: proceed at **human-present hackathon scale**; a ruling
  is required before any **unattended** use. If the ruling is "no programmatic use at
  all", D1 falls back to D2 (deterministic) or an authorised enterprise LLM endpoint.
- **D2 — Guaranteed fallback = deterministic engine.** The stage safety net *as launched by
  the runbook* (`run-deterministic.sh`, all connectors mock) — offline, can't fail.
  *(Corrected 2026-07-31, FND-46: "offline, can't fail" is a property of that launcher
  configuration, not of the engine in general — connector mode is independent of engine
  choice, so `triage.connectors.*=real` makes the same engine make real HTTP calls; gateway
  exceptions aren't caught per-tool; and when deterministic is the active engine its
  failures propagate by design, FND-7. Never run D2's demo slot with a real connector.)*
- **D3 — Optional contrast segment = Copilot CLI, no tools.** A short segment handing the
  same incident to Copilot CLI *without* access to our four systems — *only if* the
  network is healthy, and under the **same C6 human-present risk acceptance that already
  covers D1** (D3 does not need a landed ruling that D1 is proceeding without). Never the
  load-bearing path; skippable without hurting the story.

  ⚠️ **Scope caveat (corrected 2026-07-29).** D3 was first written as "run the same incident
  **fully autonomously**". That is **not buildable under the scope already decided**: an
  autonomous run needs MCP servers fronting ServiceNow / Sumo / Confluence / GitLab, which
  is concept **C5** in DDS [[copilot-cli-runtime]] — explicitly deferred off the hackathon
  path — and no MCP servers exist in the repo (only the `E5-mcp-buildout` exploration).
  The same DDS also flagged Copilot CLI's **≥7-tool headless MCP bug** as a reason to avoid
  this path. D3 is therefore re-scoped to a **tool-less contrast**: it needs zero build,
  removes the MCP dependency and the headless bug from the stage, and reinforces D4 (the
  frontier model is identical — the delta on screen is purely the evidence trail).

  To be precise about what the constraint now is: the missing tool layer is what **ruled
  out the original autonomous D3**. The re-scoped, tool-less D3 does not need that layer,
  so its only remaining gates are **network + C6/ToS**.
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
- ✅ **CLOSED 2026-07-30 — demo on `claude-opus-4.6`.** The C1 proxy spike ran on the real
  corp laptop (`copilot-api`, 4/4 pass incl. tool-calling) and the seat exposes 31 models
  including `claude-opus-4.6`, `claude-sonnet-5`, `gpt-5.3-codex`, `gpt-5.4`. So D1's
  "high Copilot-served model" and D3's "same frontier model" both hold as written.
  Evidence: `bin/spike-output.log`; details in [[copilot-cli-runtime]] spike C1.
- **ToS gate (C6 in [[copilot-cli-runtime]]; explored there as E3) governs BOTH D1 and
  D3** — D1 because driving the seat through a proxy is programmatic use, D3 because it
  invokes Copilot CLI directly. It is not a D3-only concern. Use the id **C6** for the
  gate (E3 is the exploration that produced it).
