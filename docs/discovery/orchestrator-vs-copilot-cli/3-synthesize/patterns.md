# Synthesis (Phase 3)

## The instinct is half-right — and the half that's right doesn't decide the demo
- **True**: Copilot CLI's autonomous loop is a more capable *open-ended* agent than a
  hand-rolled LangChain/ADK loop (G2, M2). If the task were "investigate anything," Copilot wins.
- **But**: (a) our triage is a **bounded, well-defined flow**, so (b) **the model does the
  reasoning, not the framework** (M4) — and **both can use the same high model** (Opus 4.6 /
  GPT-5.3) via the E2 proxy (M3). So the *quality* gap on THIS task is small.

## What actually separates them for a demo is reliability, not IQ
- **Compounding error** + **match-autonomy-to-failure-cost** (M5) both point the same way:
  a live demo that **posts to real tickets** should run on **governed autonomy**, not a
  free-wheeling agent. The dependable-looking demo that fails on stage is the classic trap.
- Judges reward **"it worked + here's the evidence trail"** more than "it improvised."
  Our trace + log↔code citations + advisory-only guardrails are a stronger story than
  opaque autonomy.

## Convergence with the prior DDS
This reinforces the E2 decision: keep our orchestration, feed it a **high Copilot-served
model**. We get near-parity reasoning **and** control/repeatability — we don't have to
choose between "good reasoning" and "safe demo."

## Where Copilot CLI still earns a place
As an **optional wow flourish** ("it can also run fully autonomously") and as the
**interactive** power-user mode later (E1) — never as the load-bearing demo path.

## Net
For the demo: **G1 spine on a high model, G2 as optional garnish.** Quality gap: small
and model-driven. Reliability/control gap: large and in our favour.
