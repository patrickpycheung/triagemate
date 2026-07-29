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
- **D3 — Optional "wow" flourish = Copilot CLI autonomous.** A short segment showing the
  same incident run fully autonomously in Copilot CLI — *only if* network + ToS are safe,
  never the load-bearing path. Skippable without hurting the story.
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
