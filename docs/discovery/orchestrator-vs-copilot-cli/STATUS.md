# STATUS — DDS: Our orchestration vs Copilot CLI autonomous (for the demo)

**Phase**: 4 COMPLETE — operator confirmed **Option 1 (D1+D2+D3)** (2026-07-29).
**Rigor**: RAPID (Claude + WebSearch).

> ⚠️ **`D1`–`D4` here are DEMO PATHS** (FND-6) — D1 our orchestration on a high model,
> D2 the deterministic offline fallback, D3 the no-tools Copilot CLI contrast, D4 the
> evidence trail. **This is the namespace `DEMO-RUNBOOK.md`, the J-cards and the
> found-issues archive use.** DDS [[servicenow-triage-java]] has an unrelated `D1`–`D5`
> meaning its own decision forks (agent engine, connector shape, …). Check the source
> workspace before resolving a `Dn`.

## DECISION (operator-confirmed)
Demo runs on **our orchestration driving a high Copilot-served model** (D1), with the
**deterministic engine as the guaranteed offline fallback** (D2), and an **optional 30s
no-tools Copilot CLI contrast** (D3) only if network + ToS allow. Runbook:
`docs/design-java/DEMO-RUNBOOK.md`.


## Verdict (short)
- **Open-ended autonomy quality**: Copilot CLI (Opus 4.6 / GPT-5.3) **>** our hand-rolled
  loop — operator's instinct is directionally right.
- **On THIS bounded triage**: **near-parity** — the **model dominates** the framework, and
  both can run the **same high Copilot model** via the E2 proxy.
- **For the demo**: **our governed orchestration is MORE SUITABLE** — reliability,
  repeatability, control, offline fallback, and an auditable evidence trail beat opaque
  autonomy for a live, ticket-writing demo. "Better agent" ≠ "better demo."

## Recommended demo shape
- **D1** primary = our `LlmAgent` loop on a **high model via the E2 Copilot proxy**.
- **D2** guaranteed fallback = deterministic offline engine.
- **D3** optional contrast = Copilot CLI with **no tools** (only if network/ToS safe).
  Re-scoped 2026-07-29 from "fully-autonomous" — autonomy needs the C5 MCP layer that
  `copilot-cli-runtime` deferred; see the scope caveat in `4-decide/concepts-extracted.md`.
- **D4** lead with the evidence trail (sources, log↔code citation, advisory-only).

## Files
- `1-elicit/problem-and-constraints.md`
- `2-diverge/explorations/` — G1 ours · G2 Copilot autonomous · G3 demo-suitability (decisive)
- `3-synthesize/patterns.md` · `4-decide/concepts-extracted.md`

## Coherence
Reinforces the prior [[triagemate-copilot-backend-decision]] (E2): keep our orchestration,
feed it a high Copilot-served model → high reasoning **and** a safe, controllable demo.
