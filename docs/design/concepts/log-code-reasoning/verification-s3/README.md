# Spike S3′ — Seeded correlation scenario (RESULT)

**Question**: Can we seed a bug that emits a distinctive log line + a matching Sumo
fixture so the agent's log↔code correlation lands convincingly and repeatably?

**Result**: ✅ **YES — fixture built & executed.** Trust: 🔬 Spiked (code runs; numbers
verified by running `python3 seed-repo/payment_service.py`).

## The scenario (answer key — what the agent should produce)
- **Distinctive log line** (from `sumo-fixture.json`, and emitted verbatim when the seed
  runs):
  `PAYMENT_RECONCILE_MISMATCH order=INC-ORD-4471 expected=11.50 charged=11.25`
- **Emitting statement**: `seed-repo/payment_service.py:43–44` — the
  `logger.error("PAYMENT_RECONCILE_MISMATCH ...")` call inside `reconcile()`. The
  distinctive token literal is on **line 44** (the format string); the enclosing call
  begins on line 43. (The agent should cite the format-string line 44, where the tokens
  live — see the multi-line note in C3 mechanics.)
- **Root cause**: order-of-operations divergence between `compute_expected_total()`
  (`payment_service.py:13`, discount **before** tax → expected=11.50) and
  `apply_discount()` (`payment_service.py:27`, applied to the tax-**inclusive** amount by
  `MockGateway.charge()` → charged=11.25). The 0.25 delta trips the reconcile guard.
- **Confidence**: high — `PAYMENT_RECONCILE_MISMATCH` is unique and greps to exactly one
  source line.

## Numbers check (executed — `python3 payment_service.py`)
subtotal=10.00, tax_rate=0.25, discount_pct=0.10
- expected (compute_expected_total, discount pre-tax): 10 − (10×0.10=1.00) + (10×0.25=2.50) = **11.50**
- charged (MockGateway → apply_discount on tax-inclusive 12.50): 12.50 × (1−0.10) = **11.25**
- delta 0.25 > 0.01 → mismatch logged as `expected=11.50 charged=11.25`. ✔ (matches fixture)

The `__main__` block executes this path, so the fixture line is exactly what the code
produces — no hand-arithmetic to get wrong.

## Files in the seed repo
- `payment_service.py` — the bug + the emitting `logger.error` + `MockGateway` (executes `apply_discount`).
- `order_api.py` — the caller; emits the two `order_api` lines that appear in the Sumo fixture (used by C3's degraded-mode candidate example).

## Why this validates C3
- **Groundable**: the exact log line exists verbatim in the fixture AND the exact source
  line exists in the repo → the agent can quote both (anti-hallucination rule enforceable).
- **Unique token**: `PAYMENT_RECONCILE_MISMATCH` appears once in the repo → correlation is
  unambiguous even though reasoning is LLM-driven.
- **Explains, not just locates**: the two divergent functions give the LLM a real
  root-cause narrative. NB (Codex): matching the log identifies the *failure site /
  emitter*; the root cause is a hypothesis the two functions support, not a proof — the
  note says "hypothesis" and C3 carries this caveat.

## Residual (real-system only, out of prototype scope)
- R3 failure-window derivation is faked by pre-scoping the fixture window; a real system
  must derive it from ticket time + first-error heuristics.
- Fixed seed file set (can't triage an arbitrary project) — documented prototype scope
  (C2 grounding guarantee).
