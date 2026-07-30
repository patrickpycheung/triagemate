# Spike LT1-SPI — is the `DiagnosisEngine` 2-arg migration actually feasible?

**Status**: ✅ **DONE — 🔬 Spiked**, 2026-07-31. Result: **feasible, mechanical, and both
suites stay green.** Source changes were **reverted** afterwards (see "Disposition").

## Question

LT1 proposes changing the `DiagnosisEngine` SPI so `diagnose(String, TraceSink)` is the
*implemented* (abstract) method and `diagnose(String)` becomes a `default`. That direction is
deliberate — engines implement the 2-arg form, so **no engine can silently drop step
emission**. But `DiagnosisEngine` is a genuine SAM, so every 1-arg lambda breaks.

Two things needed proving before LT1 could be called converged:
1. Does the migration actually compile and keep 34/34 + 50/50 green?
2. **How big is the blast radius, really?** The DDS asserted "16 lambdas — 14 in
   `DiagnosisOrchestratorTest`, 2 in `PromptInjectionGuardrailTest`", derived by **grep**.

## Method

Created throwaway `orchestration/trace/{Platform,StepState,TraceStep,TraceSink}.java`, flipped
the interface, migrated both engines (sink **ignored** — feasibility only, not the real
emission), then let `javac` enumerate every break. Ran `mvn clean test` and
`mvn -Padk clean test`.

## Result — the grep-derived claim was WRONG

| | Claimed (grep) | **Actual (compiler)** |
|---|---|---|
| Sites | 16 | **18** |
| Files | 2 | **3** |
| `DiagnosisOrchestratorTest` | 14 | 14 ✅ |
| `PromptInjectionGuardrailTest` | 2 | 2 ✅ |
| **`IncidentPollerTest`** | **0 — missed entirely** | **2** |

**Why grep missed it.** The pattern searched for was effectively `= incident ->`.
`IncidentPollerTest`'s two lambdas are neither assigned to a variable nor named `incident` —
they are positional arguments in a test-subclass constructor, named `i`:

```java
CountingOrchestrator(ServiceNowGateway snow, DiagnosisResult.Engine engine) {
    super(i -> new DiagnosisResult(null, new ArrayList<>(), engine),   // primary
          i -> new DiagnosisResult(null, new ArrayList<>(), engine),   // FND-7 fallback
          snow, false, 5000);
```

A **paired primary + fallback pair**, which is precisely the FND-7 degrade path LT1's
"one sink per engine call" invariant is about — so the file grep missed is the one most
relevant to the design. Two lessons, both generalisable:
- **Enumerate breakage with the compiler, not a regex.** A SAM change is a *type-level* event;
  no textual pattern reliably finds every lambda that conforms to the interface.
- A first `mvn test-compile` **without `clean` reported 0 errors** (stale classes) and looked
  like a pass. Always `clean` when measuring an SPI change.

## Verified facts (trust: 🔬 Spiked)

1. ✅ The migration **compiles and passes**: `mvn test` **34/34**, `mvn -Padk test` **50/50**.
2. ✅ Blast radius is **18 sites / 3 files**, purely mechanical
   (`incident ->` → `(incident, sink) ->`); **zero assertion changes**, zero production-logic
   changes. The cost is real but small, and buys un-droppable step emission.
3. ✅ `src/main/adk/` can import `orchestration.trace` (direction already established).
4. ✅ Making the 2-arg form abstract does force both engines to implement it — the compiler
   rejected them until migrated, which is exactly the safety property LT1 wants.

## Disposition — reverted, deliberately

All source edits were reverted. The spike stubbed the sink (engines accepted and ignored it),
so keeping it would have left **dead API surface with no emitter** — worse than nothing, and
it would pre-commit an SPI while LT4's transport fork is still open. The *knowledge* is the
deliverable. Real LT1 implementation follows the repo's docs → regression test → code order
once J11 converges.

## Consequences for the design

- LT1's blast-radius note in J11 and in the DDS `concepts-extracted.md` **must be corrected
  from 16/2-files to 18/3-files**.
- `IncidentPollerTest`'s paired primary/fallback lambdas are a ready-made fixture for testing
  the "**one sink per engine call, discard the primary's steps on degrade**" invariant —
  it already constructs an orchestrator with two distinct engines.
