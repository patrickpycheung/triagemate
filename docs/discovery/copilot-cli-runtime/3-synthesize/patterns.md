# Synthesis (Phase 3)

## Pattern 1 — Two axes were conflated in the original hypothesis
The request bundled two separable decisions:
1. **Token source**: Copilot subscription vs API key. *(This is the real requirement.)*
2. **Orchestration owner**: Copilot CLI's built-in loop vs our own agent layer.

The operator's premise ("adopt Copilot CLI so we don't build agent logic") ties #2 to #1.
But **we already built & tested the agent logic (L8)**, so the "don't build planning"
benefit is largely already banked. That decouples the axes: we can take the Copilot
**token source** (the true need) *without* surrendering **orchestration**.

## Pattern 2 — E2 dominates E1 against our own success criteria
| Criterion | E1 CLI-orchestrator | E2 Copilot-as-backend |
|-----------|--------------------|------------------------|
| Uses Copilot seat, no API key | ✅ | ✅ |
| App rewrite required | High (MCP servers, lose loop) | **~none** (`base-url` swap) |
| Keeps J8 guardrails + offline demo | ✗ (re-earn, soft) | **✅** |
| Current fragility | 🟠 headless+MCP bugs (≥7 tools) | 🟢 |
| Determinism / repeatable demo | ✗ | ✅ |
| ToS exposure | 🟠 (shared) | 🟠 (shared) |

E1 and E2 carry the **same ToS risk** (E3) but E2 pays far less to get the same benefit.

## Pattern 3 — The one blocker is not technical, it's licensing
E3 (does the **corporate** Copilot agreement allow programmatic / unattended use?) gates
**both** options and can't be settled by code — it's an **IT/legal** answer.
- **Interactive** use → safe reading.
- **Unattended polling** → risk zone → needs explicit ok or a sanctioned machine account.

## Dead ends / de-scoped
- **Build 4 MCP servers now** (E5): only needed for E1; duplicates our gateways under E2. Defer.
- **Assume Copilot CLI headless+MCP "just works"**: current issues (L5) say not yet for ≥7 tools.

## Recommended direction
**E2 (Copilot-as-LLM-backend via LiteLLM/copilot-api), keeping our orchestration**, with
E1 reconsidered only if we deliberately want Copilot to own planning for an *interactive*
experience. Headless remains gated on the E3 ToS answer.

## Traceability note (added 2026-07-30 by `/doc-test dds`, gap G3)

Two loose ends this file had left implicit:

- **E4 (`E4-control-guardrails`) is not cited above.** The synthesis draws on E1/E2/E3/E5
  only. E4's contribution is nonetheless real and *is* carried forward — it is the reason
  the recommended direction keeps **our** orchestration and J8's guardrails rather than
  handing control to Copilot CLI (E1). Recording that here so E4 is not read as an
  abandoned exploration.
- **C3 (rate-limit / quota guard) has no pattern behind it in this file.** It was extracted
  in `4-decide/concepts-extracted.md` from the practical seat-protection concern raised in
  E2/E4, and it lands in the CDS only as an unlabelled parenthetical in
  `docs/design-java/DEMO-RUNBOOK.md` ("built-in usage dashboard + rate-limit guard"). If C3
  is meant to be a tracked concept it needs an explicit home; if it is really just an
  attribute of the chosen proxy (`copilot-api`'s built-in guard), say so and drop the id.
