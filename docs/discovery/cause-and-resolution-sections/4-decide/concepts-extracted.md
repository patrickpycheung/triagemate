# Concepts extracted → CDS handoff

**From**: DDS `cause-and-resolution-sections`, 2026-08-05
**Into**: `docs/design-java/` as new concept cards
**Rigor**: Hackathon/RAPID

---

## Recommendation in one line

Build it as **precedent citation, not causal assertion** — and fix the similar-incident
relevance defects first, because the whole feature is a rendering of that one signal.

---

## J26 — Precedent-grounded cause & resolution

**State**: 🔵 Proposed · **Complexity**: Moderate · **Depends on**: J4, J5, J7, **J27**

### Essence

Two new components on `DiagnosisReport` that **quote** what happened before rather than
**assert** what is happening now. The system has no causal substrate (no topology graph,
no deploy-correlation ML), so it is structurally in the hedged family of tools; the one
genuinely causal artifact available to it is a human's closed verdict on a past incident.

### Schema

```java
// appended to DiagnosisReport
LikelyCause likelyCause,              // nullable — null IS the abstention
LikelyResolution likelyResolution     // nullable

public record LikelyCause(
        String quotedFinding,         // verbatim from the cited artifact, never paraphrased
        String citedArtifact,         // "INC0011902" — what the reader can open
        InferenceBasis basis,         // how we got here
        List<String> evidenceRefs,
        int supportingCount,          // the denominator: "2 of 2"
        int consideredCount
) {}

public record LikelyResolution(
        ResolutionStep mitigation,    // nullable — restore service now
        ResolutionStep permanentFix   // nullable — stop it recurring
) {}

public record ResolutionStep(
        ResolutionVerb verb,          // CLOSED enum — the safety boundary
        String quotedFinding,
        String citedArtifact,
        List<String> evidenceRefs
) {}

enum InferenceBasis { PRIOR_RESOLUTION, KNOWN_ERROR_DOC, CODE_PATH, NONE }
enum ResolutionVerb { CHECK, COMPARE, REPRODUCE_NON_PROD, CONTACT,
                      CONSULT_RUNBOOK, GATHER }   // no OTHER, no escape hatch
```

### The four rules that make it honest

1. **Quote, never paraphrase.** Paraphrasing launders someone else's guess into
   TriageMate's assertion. Verbatim + attribution keeps epistemic ownership where it
   belongs and gives the reader a ticket they can open.
2. **Abstention is legal and expected.** `null` is the canonical "not established".
   Validator rules fire only on *positive* claims, so they can never turn a passing run
   into a mid-demo degrade. Precedent: `DiagnosisReportValidator:100-103` already exempts
   `suggestedAssignment` for exactly this reason.
3. **No percentages.** The hedge is the denominator — *"2 of 2 similar resolved
   incidents"*, degrading to *"0 of 3"*, which reads as a warning rather than a weak
   endorsement. Confidence capped at MEDIUM by validator rule; analogical transfer is
   never HIGH.
4. **Remediation verbs are a closed enum.** A deny-list loses to paraphrase ("cycle it",
   "give it a kick"). An allow-list makes mutating verbs *unreachable in both engines* —
   Jackson rejects unknown constants. `ENABLE_DEBUG_LOGGING` is deliberately excluded: it
   fills disks and changes behaviour under load, a state mutation dressed as observation.

### New validator rules

- **CR-6 — basis↔source agreement.** `basis` must match the `Evidence.source` of its
  refs (`PRIOR_RESOLUTION` → `servicenow-incident`, etc.). `Evidence.source` is already a
  closed vocabulary, so this is a 4-way switch. This is the rule that catches the failure
  `evidenceRefs` cannot: a citation of the *wrong kind*. Existing `danglingRefs` covers
  fabricated refs; the new owners just join it.
- **CR-7 — MEDIUM ceiling** on any cause-derived confidence.
- **CR-8 — quote fidelity**: `quotedFinding` must be a substring of a cited artifact's
  text, so invention is mechanically detectable rather than merely discouraged.

### Files that change

`DiagnosisReport.java` (+3 records, +2 enums, `toDiagnosisNote()`),
`DiagnosisReportValidator.java` (+3 rules), `DeterministicDiagnosisEngine.java` (insert at
the existing `findSimilarIncidents` step ~`:156`), `AdkDiagnosisEngine.java` (prompt delta
+ `stampGeneratedAt` arity), `TriageMateTools.java`, **`index.html` (twice — the report
render AND the hand-mirrored note preview at `:1235-1238`)**,
`DiagnosisControllerPostResponseShapeRegressionTest` (will fail strictly — that is the
guard working), 11 positional `new DiagnosisReport(...)` call sites.

### Est. cost

~3h for the flat MVP; ~6–8h for the full shape above. Both well inside hackathon scope.

---

## J27 — Similar-incident relevance & honesty **(prerequisite)**

**State**: 🔵 Proposed · **Complexity**: Simple · **Blocks**: J26

J26 is a rendering of `findSimilarIncidents`. That signal is currently broken against real
data in two ways, both filed:

- **FND-85** — matching is `short_descriptionLIKE <first word of description>`. For the
  project's own example the live query is `LIKE User`.
- **FND-84** — `similarity` is hardcoded `0.5` and rendered as *"(50% similar)"* onto real
  tickets today.

Shipping J26 on top of this produces the worst available outcome: **correct-looking
attribution pointing at an unrelated ticket**, which converts attribution — J26's primary
safety mechanism — into borrowed credibility for noise.

Minimum: multi-token matching with stopword removal; compute a real similarity or render
none. Not optional, and independently worth doing.

---

## Hard prerequisite (bug, not concept)

**FND-87 — ADK path still self-poisons.** `isAiAuthoredNote` has two call sites, both
deterministic-only; `TriageMateTools.getIncident()` hands raw `comments`/`workNotes` to
the model, and the instruction directs it to read them. Today this drifts keywords and
contact names. With J26 it becomes **confidence laundering**: run 1's hedged hypothesis
becomes run 2's corroborating "human" evidence becomes run 3's stated cause — a circular
chain that `evidenceRefs` validation cannot detect, because every link is a genuine,
correctly-cited artifact. Fix at the tool boundary, not in the prompt.

---

## Demo change (cheap, high value)

One extra mock incident with **no** matching resolved tickets, so the abstention path can
be shown. `MockServiceNowGateway.getIncident` currently throws for any number but
`INC0010005` (FND-54). Since abstention is the *common* production path (see below),
demoing only the strong case misrepresents the feature.

---

## The finding to carry into the pitch, not hide from it

📚 Ahmed et al. (ICSE 2023, Microsoft — 44,340 incidents, graded by the 25 incident owners
who actually fixed them): LLM root-cause **correctness 2.40–2.88 / 5**, while
**readability scored 3.5–4.6**.

> It reads far better than it is right.

That single asymmetry is the justification for every constraint above, and it is also the
strongest thing in the pitch. A tool-less frontier model will happily produce a fluent,
confident, wrong cause. The differentiator is not more detail — it is:

> *"Only one of these told you how many times that was right before, and showed you the
> two tickets."*

📚 Corroborating: Roy et al. (FSE 2024) got the best precision from an agent whose
incorrect predictions were **66% 'Insufficient Information'** — it won by abstaining
(hallucination 6% vs 18% for chain-of-thought).

---

## Honest expectation setting

Combining P2's evidence — real close notes are mostly *"Issue resolved"* / *"Done"*
(📚 prior-art), matching is first-word (🔬 FND-85), similarity is fake (🔬 FND-84) — the
expected behaviour is:

**Excellent in the mock demo. Frequently abstaining in production.**

That is the correct and honest outcome for a system with no causal substrate, and it is
still worth building. But it should be known before the demo, not discovered during it,
and J27 is what moves the needle on it.

Three ❓ Unknowns need the corp laptop to close and would sharpen this estimate —
`verification-similar-incidents/open-questions.md` (are real `close_notes` populated? what
`close_code` values exist? how generic are first tokens?). None block CDS; all three
affect only how often the feature speaks.
