# G1 — Our custom LangChain/ADK orchestration

**Bias**: Technical-depth. **Verdict**: Sufficient for the task; wins on control.

## What it actually is
Not "just scripted" — the `-Padk` path is a real **`LlmAgent` tool-calling loop** (fetch →
clarify → similar/ownership → knowledge → bounded logs → targeted code → report), plus a
**deterministic** engine that runs the same flow offline with zero network. Bounded by
`BoundsCallback` (max tool calls), advisory-only writes, allowlisted Sumo scopes, full trace.

## Strengths
- **Governed autonomy (M5)**: bounded execution, explicit steps, auditable trace,
  advisory-only guarantee — the architecture the reliability literature says survives.
- **Repeatable / deterministic-capable** → safe on stage; offline fallback can't fail.
- **Provider-flexible**: via the E2 proxy it can drive the SAME high models as Copilot (M3),
  so its *reasoning ceiling* is the model, not the framework (M4).
- Already built + **tested** (5 green).

## Limits
- Our loop is **simpler** than Copilot's tuned autonomous planner: less open-ended
  self-correction / replanning. For a *fixed* triage flow that's fine; for open-ended
  investigation it's less adaptive.
- We maintain the loop, retries, prompt — engineering burden (M4's "operational complexity").
- Less "watch it think for itself" theatre.

## Trust / risk
🔬 (exists, tested) · 🟢. Reasoning quality rides the chosen model (M4).
