# G3 — Which is more suitable FOR THE DEMO (the decisive question)

**Bias**: User-centric + risk-averse. **Verdict**: For a must-work, posts-to-real-tickets
demo, **governed orchestration (G1) is more suitable** — with Copilot autonomy (G2) as an
optional "wow" flourish, not the spine.

## "Better agent" ≠ "better demo"
The web evidence is blunt: *"a polished agent demo is the worst place to evaluate an
agent"* — clean inputs, warm tools, the one happy path. The failure the audience remembers
is a live wrong turn. Two independent principles decide it:

1. **Compounding error (M5)**: 95%-correct steps chained many times → reliability collapses.
   A fully-autonomous run has more uncontrolled steps than our bounded flow.
2. **Match autonomy to failure cost (M5)**: this triage **posts advisory comments to real
   ServiceNow tickets**. High-stakes + imperfect ⇒ *assistant/governed*, not free agent.

## Head-to-head for the demo
| Factor (demo lens) | G1 our orchestration | G2 Copilot CLI autonomous |
|---|---|---|
| Reasoning quality on THIS bounded task | high (same model via proxy, M3) | high (M2) — **~parity, model dominates (M4)** |
| Raw open-ended autonomy | good | **better** |
| Determinism / repeatable on stage | **✅** | ✗ |
| Wow / "watch it think" | moderate | **✅** |
| Failure risk live (network/ToS/≥7-tool bug/off-rails) | **low (offline fallback)** | higher |
| Advisory-only + bounded guarantee | **✅ ours** | ✗ black box |
| Auditable evidence trail (judges love this) | **✅ trace + citations** | partial |

## Reading
The model does most of the reasoning either way (M4), so the **quality gap on our bounded
triage is small** — while the **reliability/control gap is large and favours G1**. The
operator's instinct ("Copilot's orchestration is better") is true for *open-ended
autonomy*, but that's not what wins a live, ticket-writing demo.

## Recommended demo shape
- **Spine = G1** (governed, repeatable, offline-safe) driving a **high model via the E2
  Copilot proxy** → best-of-both: high reasoning + full control + a trace/citations story.
- **Optional 30-sec flourish = G2**: "it can also run fully autonomously in Copilot CLI" —
  only if network/ToS are safe, with the deterministic path as the guaranteed fallback.

## Trust / risk
📚/🔍 · 🟢 recommendation; the flourish is 🟠 (only if conditions allow).
