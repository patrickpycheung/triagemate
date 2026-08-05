# Exploration — FIRST PRINCIPLES

**Bias**: ignore prior art; reason from what a cause claim *is*.

## Core thesis

**TriageMate cannot assert a cause. It can cite one.**

Every signal the engine gathers is correlational, and most of it is *selection-driven*:
`confluence.search(query=symptomKeywords)` returning a page that mentions the symptom is
the search working, not evidence (🔬 verified — `DeterministicDiagnosisEngine` builds the
Confluence query from the ticket's own keywords). Causation is counterfactual; nothing here
is counterfactual.

One evidence class is categorically different. `ResolvedIncident.resolutionCode` +
`.resolutionNotes` are **a human's closed causal verdict** — someone diagnosed it, fixed it,
watched it stop, and wrote down why. That is testimony, transferred by analogy. So the
system's assertion is the *analogy* ("this looks like that"), which is exactly the claim its
evidence can carry. The causal content is **quoted, never asserted**.

## Top 3 findings

1. **🔬 The grounded source is already in hand and unused.** `resolutionNotes` is fetched by
   the mock and the real gateway (`close_notes`, `RealServiceNowGateway:135-140`) and read by
   **nothing** in main source. Cause/fix needs no new connector — criterion 6 is free.

2. **🔍 Confidence on a cause is confidence in the *transfer*, not the mechanism.**
   `similarity=0.91` is a text score, not P(same cause). Rendering it beside a cause claim
   manufactures precision no evidence supports. Use the coarse `Confidence` band, and make
   **MEDIUM the hard ceiling** — nothing this system observes warrants HIGH on a cause.

3. **🔍 Mitigation and fix must be two sections, not one.** They differ on reversibility,
   evidence requirement, actor, and time horizon. Merged, they force the reader to do the
   risk triage at 3am — and invite executing a code-fix line as if it were a restart line.
   Criterion 3 (survives a wrong fix being followed) disqualifies the merge.

## Recommended shape

- **Ranked hypotheses, not hedged prose.** Plurality *is* the hedge — a reader skims past
  "likely", not past a second bullet. Empty list = "not established", the natural default.
- **No free-prose field.** The cause statement is a verbatim quotation plus a template frame.
  The deterministic engine has nowhere to fabricate into (criterion 4 for free), which is the
  structural cure for the FND-63/FND-8 hardcoded-narrative pathology.
- **Warrant is declared, not implied.** A runbook hit may license *containment* only, never a
  cause — otherwise every incident gets one and the section becomes noise.
- **Cause is two-place.** `subjectSystem` is nullable and advisory (not validator-enforced):
  gets cause-of-*what* into the schema without forcing invention today.
- **Reported tense for cause/fix; imperative only for reversible containment.**

## Proposed schema

```java
/** How a hypothesis earned the right to be stated. Warrant, not score. */
public enum CauseWarrant { PRECEDENT, CHANGE, SCOPE }

public record CauseHypothesis(
        CauseWarrant warrant,
        String subjectSystem,       // nullable, advisory — cause-of-WHAT
        String quotedFinding,       // VERBATIM from the artifact; never engine-authored
        String citedArtifact,       // "INC0011902" | "payment_service.java:88"
        Confidence confidence,      // MEDIUM ceiling, validator-enforced
        List<String> evidenceRefs   // non-empty, validator-enforced
) {}

public record ContainmentStep(
        String action,              // imperative; reversible only
        boolean reversible,         // validator rejects false — a stated proof obligation
        String citedArtifact,
        List<String> evidenceRefs
) {}

public record DurableFix(
        String quotedFix,           // VERBATIM resolutionNotes / commit subject
        String citedArtifact,
        String routeTo,             // owning group — the fix is THEIR call, not the reader's
        Confidence confidence,
        List<String> evidenceRefs
) {}

// on DiagnosisReport, after suggestedAssignment:
List<CauseHypothesis> likelyCauses,
List<ContainmentStep> containment,
List<DurableFix>      durableFixes,
```

Full reasoning: [exploration.md](exploration.md)
