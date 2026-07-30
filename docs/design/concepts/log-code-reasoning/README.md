# C3 — Log ↔ Code Reasoning (the wow)

**Level**: 🛣️ Highway · **Complexity**: 🟥 Critical · **Convergence**: 🟢 Converged

## One-liner
The Rovo agent's LLM matches a runtime log line from Sumo to the `log(...)` statement
that emits it in the fetched GitLab source, citing **file:line** + the execution path.

## Why Critical
This is the demo's payoff AND the biggest risk: an LLM can **hallucinate** a plausible
file:line that doesn't exist. Trust hinges on the claim being verifiable.

## Approach (AI-first)
- No deterministic engine (Drain3/AST) in the prototype — the LLM reasons directly over
  (log window + source). Deterministic matcher = documented optional tool, not built.
- **Grounding rule** (anti-hallucination): the agent MUST quote the *exact* matched log
  line verbatim AND the source line it maps to. If it can't find a real match, it says
  so (→ R4 degraded behavior) rather than inventing one.

## Carried DDS items folded here
- **R3** (failure-window derivation): ticket time ≠ failure time. Prototype: the mock
  Sumo fixture is pre-scoped to the failure window, so timing is fixed. Note the real
  system would derive the window from ticket + first-error heuristics.
- **R4** (degraded-note behavior): when the agent can't confidently localize, it posts a
  "candidates + what I checked" note, not a false file:line.

## Success criterion (from DDS)
Agent names the responsible **file:line**, quotes the exact log line, and the file:line
is real in the seeded repo.

## Open questions
- How to make grounding enforceable in a prompt (self-check step? re-read the cited
  line?) — design in mechanics/.
- Confidence phrasing: how the agent signals high vs low confidence in the note.

## Depends on
C2 output (source + logs). Feeds C1 → C5. Verified by **spike S3′**.
