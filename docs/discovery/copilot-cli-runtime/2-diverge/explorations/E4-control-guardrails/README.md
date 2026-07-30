# E4 — Control, determinism & guardrails

**Bias**: Operations / risk-averse. **Verdict**: Decisive tie-breaker → favors E2.

## What TriageMate must keep (non-negotiables)
- **Advisory-only** (never reassign/close/re-prioritise) — J8.
- **Bounded** tool use (max tool calls, allowlisted Sumo scopes) — J8.
- **Auditable trace** ("it really consulted the sources") — J7/J8.
- **Offline deterministic demo** that needs no network/LLM — the hackathon safety net.

## How each option preserves them
| Guardrail | E1 (Copilot CLI orchestrator) | E2 (Copilot as LLM backend) |
|-----------|------------------------------|------------------------------|
| Advisory-only | Prompt + MCP-side write-guards only (soft) | **Kept** — our code owns the writes |
| Bounded tool calls | Copilot controls the loop (hard to cap) | **Kept** — `BoundsCallback` (J8) |
| Deterministic trace | Copilot's plan is opaque | **Kept** — our trace list |
| Offline demo | Separate code path anyway | **Kept** — deterministic engine |
| Model/repeatability | Copilot chooses | **We choose** via proxy model |

## Reading
E1 asks us to **re-earn** guardrails we already have, inside a black-box loop we don't
control. E2 keeps them for free because the orchestration stays ours and only the
**token source** changes. For an advisory tool posting to real tickets, control is worth
more than "Copilot does the planning."

## Nuance
The operator's "we don't need to build the agent logic" is true in general — but here
**it's already built and tested (L8)**. So E1's headline advantage is largely spent,
while its cost (lost control + current MCP fragility) is live.

## Trust / risk
🔍 Inferred from the codebase + L5/L8 · 🟢.
