# C3 — Log↔Code Reasoning — Mechanics

Critical concept: the demo payoff and the top risk (hallucinated file:line). These are
the implementation mechanics that make LLM reasoning trustworthy without a deterministic
engine.

## The correlation, step by step
1. From the log window, pick the **ERROR** line that best explains the failure (skip
   INFO/WARN noise). In the fixture that's `PAYMENT_RECONCILE_MISMATCH ...`.
2. Extract the **literal, non-variable tokens** of that line — the log's format-string
   skeleton: `PAYMENT_RECONCILE_MISMATCH order= expected= charged=`.
3. Find the source `log(...)` statement whose format string contains those literal
   tokens. Unique tokens (an ALL_CAPS event name) collapse this to one line.
4. Report `file:line` — cite the line the literal **tokens** are on. A log call may span
   several lines (`logger.error(` on one line, the format string on the next); cite the
   format-string line (e.g. seed `payment_service.py:44`, not the `logger.error(` at 43),
   because that's where grep/self-check land.
5. Quote both the log line and the source line, then explain *why* the code produced it
   (read the surrounding function).

**Caveat (Codex):** matching the log statement identifies the **emitter / failure site**,
not a proven defect. The root cause is a *hypothesis* the surrounding code supports — the
note says "hypothesis," never states proof.

## Anti-hallucination mechanics (the trust layer)
The prompt enforces three rules; together they make a fabricated answer nearly impossible
to state with high confidence:

| Rule | Effect |
|------|--------|
| **Quote-both** — must quote the exact log line AND the exact source line verbatim | A hallucinated file:line can't produce a real quotable source line → forces grounding |
| **Self-check** — re-read the cited source line and confirm its format string literally matches the log tokens | Catches near-miss / wrong-line matches before posting |
| **Degrade-not-invent** — if no real emitting line is found, output ranked candidates + "could not confirm", confidence=low | Turns the failure mode from "confident lie" into "honest maybe" (R4) |

## Dependency: source must contain the emitting file (R3 conflict fix)
Quote-both only works if `getSource` (C2) actually returns the file that emits the log.
Resolved by pinning `getSource` for the seeded project to a fixed file set that always
includes `payment_service.py`. Without that guarantee C3 would falsely degrade. See C2
design.md + `.agent.work/cds/conflict-detection.md`.

## Why no Drain3/AST engine (prototype)
- Unique event tokens make LLM matching reliable *for the seeded scenario* — a template
  engine would be belt-and-suspenders the demo doesn't need.
- Documented as the optional precision tool for the real system (a `match-template`
  action the agent could call) — not built now.
- Trade-off accepted: on a repo with **non-unique** log strings, LLM-only matching could
  mis-rank. Mitigated for the demo by the seeded unique token (S3′); flagged for the real
  system.

## Degraded-mode note shape (R4)
The exact degraded note format is **owned by C5** (`work-note-postback/design.md`) so the
two concepts can't drift — see its "Degraded" variant. Illustrative candidates for the
seed scenario: `payment_service.py:44` (closest format-string match) and `order_api.py`
(surfaced the ValueError). Confidence low → recommend a human confirm before acting.

## Convergence
Grounding rules are concrete and testable against the S3′ fixture. → 🟢 Converged.
