# Technical Depth — cause & resolution as a checkable contract

**Recommendation**: two nullable top-level components on `DiagnosisReport`, each carrying a
closed-vocabulary `InferenceBasis` that says *how* the claim was reached. `basis` is what makes
these fields different from every existing one: `evidenceRefs` prove a claim is *traceable*,
`basis` lets the validator check the citation is of the *right kind*. Fabricating a ref is caught
by the existing dangling-ref rule; fabricating a basis is caught by a new basis↔source rule.

**Abstention is legal.** `basis = NONE` (or null) is a valid report state — the codebase already
made this exact ruling for `suggestedAssignment` ("forcing a citation there would push the code
toward inventing one", `DiagnosisReportValidator:100-103`). A required cause forces invention,
which is FND-8, the worst failure class here. The new rules only fire on positive claims, so they
can never turn a passing run into a degrade unless the model asserted something new.

**Mitigation and permanent fix are separate**, inside one record, each with its own refs and
confidence — they have different evidence, different authority, and different reversibility.
Verified payoff: the mock fixture's two similar incidents already carry
`Resolved - Code Fix` and `Resolved - Known Error`, so the demo yields both, each citing its own
`e-sim-*`, with no fixture edit.

**Deterministic engine quotes, never narrates**: a ladder over data already in scope at assemble
time — `similar[0].resolutionNotes` (unused today) → `docs[0].snippet` → `codeHits` → `NONE`.
It asserts only "this prior ticket said X", true by construction. Capped at MEDIUM.

```java
public enum InferenceBasis { PRIOR_RESOLUTION, KNOWN_ERROR_DOC, CODE_PATH, NONE }

/** Why we think it happened. basis=NONE + empty refs is the honest abstention. */
public record LikelyCause(
        String statement,
        InferenceBasis basis,
        Confidence confidence,
        List<String> evidenceRefs
) {}

/** One actionable step. Never an instruction — a lead, attributed to its source. */
public record ResolutionStep(
        String action,
        InferenceBasis basis,
        Confidence confidence,
        List<String> evidenceRefs
) {}

/** Either half may be null; both null means the whole field is null. */
public record LikelyResolution(
        ResolutionStep mitigation,     // reversible, on-call can do it now
        ResolutionStep permanentFix    // code/config change, owning team
) {}

// DiagnosisReport — two components appended after recommendedNextAction:
        String recommendedNextAction,
        LikelyCause likelyCause,             // nullable
        LikelyResolution likelyResolution,   // nullable
        Confidence confidenceOverall,
        boolean advisory
```

**Files that must change** — `model/DiagnosisReport.java`; new `model/LikelyCause.java`,
`LikelyResolution.java`, `ResolutionStep.java`, `InferenceBasis.java`;
`model/DiagnosisReportValidator.java`; `orchestration/DeterministicDiagnosisEngine.java`;
`adk/.../AdkDiagnosisEngine.java` (`instruction()` **and** `stampGeneratedAt`);
`resources/static/index.html` (2 cards **and** the write-back preview at :1235-1238);
`api/DiagnosisControllerPostResponseShapeRegressionTest.java` (strict JSON, +2 keys — deliberate);
`model/DiagnosisReportValidatorTest.java`; `model/DiagnosisReportNoteTest.java`; the 6 other tests
constructing `DiagnosisReport` (arity); new derivation + prompt-vocabulary tests;
`docs/design-java/concepts/J4-diagnosis-report/README.md`.
