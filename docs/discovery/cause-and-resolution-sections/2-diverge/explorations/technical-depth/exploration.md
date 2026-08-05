# Technical Depth exploration — cause & resolution as a checkable contract

Trust markers: 🔬 verified in this repo's code · 🔍 inferred from code but not executed ·
🤔 judgment · ❓ open.

---

## 1. What actually makes this hard

The problem statement's framing is correct and worth restating in contract terms. Every existing
J4 field is an *observation* or a *routing*: "this evidence points at Order Portal". The rule
`every conclusion ties to evidenceRefs` is sufficient for those, because the conclusion is a
selection from things we saw. A **cause** is an inference past the evidence — evidence shows
co-occurrence, a cause asserts *because*. A **resolution** is worse: it is an act, proposed in a
customer-visible, permanently retained journal.

So `evidenceRefs` alone is necessary but not sufficient. Attaching `["e-log"]` to "the discount
was applied after tax" makes the claim *traceable*; it does not make it *supported*. J13 already
named this exact pathology for candidate systems — a citation that does not support the claim it
is attached to is "worse than no citation because it looks rigorous" 🔬
(`DeterministicDiagnosisEngine.java:401-406`).

The schema must therefore carry something `evidenceRefs` cannot: **what kind of inferential move
was made**. That is the whole design.

---

## 2. Schema

```java
public enum InferenceBasis {
    /** A previously resolved incident with this symptom was closed with this explanation. */
    PRIOR_RESOLUTION,
    /** A runbook / known-error page describes this symptom and its cause. */
    KNOWN_ERROR_DOC,
    /** A log error token was tied to the source line that emits it. */
    CODE_PATH,
    /** Nothing gathered supports a claim. The honest abstention. */
    NONE
}

public record LikelyCause(
        String statement,
        InferenceBasis basis,
        Confidence confidence,
        List<String> evidenceRefs
) {}

public record ResolutionStep(
        String action,
        InferenceBasis basis,
        Confidence confidence,
        List<String> evidenceRefs
) {}

public record LikelyResolution(
        ResolutionStep mitigation,
        ResolutionStep permanentFix
) {}
```

Appended to `DiagnosisReport` after `recommendedNextAction`, both nullable. Jackson emits record
keys in canonical-constructor order 🔍, so appending appends keys and the strict-JSON diff stays
readable.

### Fields rejected, and why

- **`rationale` (free text, separate from `statement`).** Rejected. `basis` + `evidenceRefs` *is*
  the rationale, in machine-checkable form. A second prose field is a second thing the
  deterministic engine must template and a place the model can smuggle an unhedged assertion past
  a hedged `statement`.
- **`isSpeculative` / `hedged` boolean.** Rejected — duplicates `basis == NONE` and
  `confidence == LOW`. A boolean that restates an enum value earns nothing and creates a
  disagreement state.
- **`generatedBy` / engine tag.** Rejected — `DiagnosisResult.engine` already carries it 🔬.
- **`risk` on `ResolutionStep`.** Tempting (a workaround has blast radius). Rejected for this
  phase: neither engine has any input from which to derive risk, so it would be a field only a
  model could fill, i.e. a pure invention surface. ❓ Revisit if a change-management connector
  ever lands.
- **`double` confidence instead of the `Confidence` band.** Rejected. `CandidateSystem` uses a
  double because it is *ranked* against siblings; cause and resolution are not ranked, and FND-66
  🔬 was caused precisely by the schema describing "confidence" two different ways. Adding a third
  numeric confidence to the prompt re-opens that defect.

### Why cause is not a ranked list

J4's doctrine is "ranked shortlists, never one forced answer", and mirroring it for causes looks
principled. Three reasons not to:

1. **The deterministic engine cannot produce a real differential.** It has one derivation per
   basis, and the prior-resolution and runbook derivations usually restate each other. A list
   would manufacture the *appearance* of competing hypotheses the engine never weighed. That is
   the FND-63 failure shape 🔬 — narrating an analysis that did not happen.
2. **`contradictingEvidence` already owns "another explanation is possible"** 🔬 and is rendered
   as its own card. A second-ranked cause competes with it; two places to say one thing drift.
3. **Ranked *systems* read as "shortlist, go look". Ranked *causes* read as confusion** 🤔 — and
   the honest hedge we actually want is `basis` + `confidence` + abstention, not a fake ranking.

Migration cost if we are wrong: `LikelyCause` → `List<LikelyCause>` is a breaking JSON change.
Accepted at hackathon rigor, and cheap because both engines construct the value in one place.

### Mitigation vs permanent fix — the argument

**Separate.** Four reasons, the first three verified:

1. **They are separately attested in the data we already read.** `ResolvedIncident` carries
   `resolutionCode` *and* `resolutionNotes` 🔬, and the demo fixture's two similar incidents are
   `"Resolved - Code Fix"` and `"Resolved - Known Error"` respectively, with the second's notes
   literally reading *"Workaround then code fix"* 🔬
   (`MockServiceNowGateway.java:96-107`). The real gateway maps ServiceNow `close_code` /
   `close_notes` onto the same two components 🔬 (`RealServiceNowGateway.java:137-141`). A single
   field forces the engine to drop one or to blend two claims into one sentence — the blend being
   exactly the fabrication vector.
2. **Different citations.** The demo's mitigation comes from `INC0011455` and the fix from
   `INC0011902`. Sharing one `evidenceRefs` list would degrade the citation to "these refs
   support one or both of these claims" — ECI-2's failure mode, restated.
3. **Different authority and reversibility.** A workaround is something the on-call engineer can
   do in five minutes and undo; a permanent fix needs a code change, a review and a release, by a
   different person on a different timeline. Advisory-safety (success criterion 3) wants the
   disclaimer attached to the *actionable* one, not blanketed over both.
4. **Availability differs in practice** 🤔 — "runbook workaround exists, no known fix" and "prior
   code fix exists, no safe workaround" are both common, and both are representable only if the
   halves are independent.

**But inside one record**, not as two top-level components: it keeps the top-level delta at
exactly +2 (smaller strict-JSON diff, smaller prompt), and keeps the two adjacent so no renderer
can show a fix without its mitigation context.

---

## 3. Validator rules

Extend `DiagnosisReportValidator.validate`. Existing structure is reused, not replaced: the new
owners are appended to the existing `danglingRefs(...)` entry list, so **a fabricated
`evidenceRef` on a cause is caught by the rule that already exists**, with the same
report-everything semantics and an owner label (`"likelyCause"`,
`"likelyResolution.mitigation"`).

| Rule | Statement |
|---|---|
| **CR-0** | Cause and both resolution steps join the existing dangling-ref check. |
| **CR-1** | `basis != NONE` ⇒ `evidenceRefs` non-empty. (ECI-2 applied to the new fields.) |
| **CR-2** | `basis == NONE` ⇒ `evidenceRefs` empty. An abstention that cites evidence is a claim wearing an abstention label — the worst of both, and a real model behaviour (hedge the enum, assert the prose). |
| **CR-3** | `statement` / `action` must be non-**blank** when the object exists. Blank, not null: FND-67 🔬 shipped an empty-named candidate because the live ticket's `cmdb_ci` was `""`, not null. |
| **CR-4** | `likelyResolution != null` ⇒ at least one half non-null. No empty containers. |
| **CR-5** | `permanentFix.basis != CODE_PATH`. A code-search hit *locates*; it does not prescribe what to change. Allowed as a cause basis, rejected as a fix basis. |
| **CR-6** | **basis ↔ evidence-source agreement.** `PRIOR_RESOLUTION` requires ≥1 cited Evidence whose id starts `e-sim-`; `KNOWN_ERROR_DOC` requires a cited Evidence with `source == "confluence"`; `CODE_PATH` requires `source == "gitlab"`. |

CR-6 is the headline. `Evidence.source` is already a stable closed vocabulary in this codebase —
`servicenow-incident, servicenow-cmdb, confluence, sumo, gitlab`, documented on the record itself
🔬. So CR-6 is a `switch` over four enum values against a string already in hand, and it turns
`basis` from decoration into a cross-reference. It catches the precise fabrication the brief asks
about: a model claiming `PRIOR_RESOLUTION` while citing only `e-log`.

Two independent fabrication vectors, two independent rules: invent a **ref** → CR-0; invent a
**basis** → CR-6.

### What the validator cannot check — stated plainly

That the `statement` actually paraphrases the cited source. `basis` narrows the space and CR-6
proves support *of the claimed kind* was cited, but nothing here proves the sentence is a faithful
reading of `close_notes`. On the deterministic path that gap is closed structurally (§4, the
engine quotes). On the ADK path it is not closed at all, and no cheap rule closes it. ❓ The only
honest mitigations are the rendering disclaimer (§6) and the fact that every claim is one click
from the source it claims to rest on.

### Abstention must be legal — the critical call

If `likelyCause` were required, we would have forced the model to invent. Three pieces of
in-repo evidence say do not:

1. `uncitedCandidates`'s javadoc 🔬 (`DiagnosisReportValidator.java:100-103`): the citation rule
   was **deliberately not extended** to `suggestedAssignment` because the honest
   "Unassigned — no ownership or similar-incident signal" fallback has only the ticket to cite,
   "and forcing a citation there would push the code toward inventing one". The project has
   already made this exact ruling once, in code, for this exact reason.
2. FND-63 🔬: hardcoded narrative prose was "an outright fabrication for any other [incident] —
   the FND-8 failure class (narrating something that did not happen), which is the worst one in
   this project."
3. J13 🔬: "every new hard rule is a new way to degrade to the fallback mid-demo". A required
   cause makes every thin incident a validation failure; on the deterministic path there is no
   fallback to fall back to (`DiagnosisOrchestrator.java:331` 🔬) so it surfaces as a 500.

**Ruling: both fields nullable; `basis = NONE` is the canonical, positively-asserted abstention;
the validator treats null and `NONE` identically.** Null is the tolerant state (model omission,
old JSON, repair turns); `NONE` is what both engines actually emit, so the UI always has a card
that says "not enough evidence to state a cause" rather than a section that silently vanishes —
a missing section is invisible to a reader who does not know it should be there 🤔.

A useful consequence: **every new rule fires only on a positive claim.** A model that omits both
fields produces a report that validates exactly as today. The new rules cannot convert a
previously-passing run into a degrade unless the model asserted something it could not support.

---

## 4. Deterministic engine — it selects and quotes, it never narrates

All inputs are already in scope at the assemble step (`similar`, `docs`, `codeHits`, `errorToken`,
`gathered`) 🔬. Notably `ResolvedIncident.resolutionNotes` **is currently used nowhere in
`src/main`** 🔬 — the richest signal in the report's inputs is being discarded today.

Ladder, first match wins:

1. `similar` non-empty and top entry's `resolutionNotes` non-blank → `PRIOR_RESOLUTION`, citing
   `e-sim-<number>` (an id the engine already mints 🔬 at `:158`).
2. `docs` non-empty → `KNOWN_ERROR_DOC`, citing `e-kb-<id>` 🔬 (`:187`).
3. `codeHits` non-empty and `errorToken != null` → `CODE_PATH`, citing `e-code-1` + `e-log`
   (cause only; CR-5 forbids it as a fix basis).
4. otherwise `NONE`, empty refs, and a `missingInformation` line.

Cause template, basis 1 — **quotation with attribution**:

```java
"A previously resolved incident with the same symptom (%s, %.0f%% similar) was closed as \"%s\": %s"
    .formatted(top.number(), top.similarity() * 100, top.resolutionCode(),
               firstSentence(top.resolutionNotes()))
```

The engine asserts only *"this prior ticket said X"*, which is true by construction. Honest
hedging becomes a **structural** property rather than a rhetorical one — which is the only kind of
hedging a template engine can guarantee.

Resolution split, from the same source: `resolutionCode` is the discriminator already read 🔬 —
`"Resolved - Code Fix"` → `permanentFix`; `"Resolved - Known Error"` / `"Resolved - Workaround"` →
`mitigation`; a runbook `snippet` → `mitigation` (a runbook prescribes an operational step, not a
code change).

On the demo incident this yields, with no fixture edit 🔬:

- cause ← `INC0011902` (0.91) "…Discount was applied after tax in the gateway…", `PRIOR_RESOLUTION`, refs `[e-sim-INC0011902]`
- permanentFix ← `INC0011902` (`Resolved - Code Fix`), refs `[e-sim-INC0011902]`
- mitigation ← `INC0011455` (`Resolved - Known Error`), refs `[e-sim-INC0011455]`

Confidence: `similarity >= 0.85 → MEDIUM`, else `LOW`; **never HIGH** — this engine has no
mechanism that could justify HIGH for a causal claim, and the cap belongs in code, mirroring the
existing 0.86/0.70/0.45 ladder discipline 🔬.

Two robustness notes, both the FND-67 class:

- `RealServiceNowGateway` hardcodes `similarity = 0.5` 🔬 (`:140`), so against real ServiceNow
  every prior-resolution cause lands LOW and never crosses 0.85. That is *accidentally* correct —
  a naive `short_descriptionLIKE` keyword match deserves LOW — but the code should say so
  deliberately rather than depend on a constant that may change.
- `close_notes` is routinely empty on real tickets 🔍. Blank-check and **fall through to the next
  basis**, never emit a blank `statement` (CR-3 would throw, and on the deterministic path a throw
  is a 500).

---

## 5. ADK path

Prompt delta, two parts. Schema block gains:

```
"likelyCause": {"statement","basis":"PRIOR_RESOLUTION|KNOWN_ERROR_DOC|CODE_PATH|NONE",
                "confidence":"LOW|MEDIUM|HIGH","evidenceRefs":[]},
"likelyResolution": {"mitigation":{ …same shape… }, "permanentFix":{ …same shape… }}
```

Rules paragraph — the load-bearing sentences:

- *You are not required to state a cause.* If nothing you read explains **why**, set
  `basis: "NONE"`, `evidenceRefs: []`, and record it under `missingInformation`. An honest "not
  enough evidence" is a correct answer; a plausible guess is a wrong one.
- `basis` must match what you actually cite: `PRIOR_RESOLUTION` needs a cited `e-sim-*` item,
  `KNOWN_ERROR_DOC` a cited Confluence item, `CODE_PATH` a cited GitLab item.
- `mitigation` is something the on-call engineer can do now and undo; `permanentFix` is a change
  to code or config the owning team must make. Never put a code change in `mitigation`.

The second bullet is FND-60's principle applied 🔬 — never enforce a value the model was not
told about. The model is shown the rule CR-6 will judge it by.

**J19 fidelity.** These are static literals, not config-derived, so they do not fall under J19's
"never a second literal" rule for *bounds*. They do fall under its guarding-test pattern: add a
test asserting the prompt contains **every** `InferenceBasis` constant (iterate
`InferenceBasis.values()`, not a hardcoded sample), so adding an enum member without updating the
prompt fails 🔬 — the invariant, not one instance of it.

**When the model fabricates.** `validate()` runs at `AdkDiagnosisEngine.java:440` 🔬, *after* the
agent loop. A violation throws `DiagnosisReportInvalidException` out of `diagnose()`, and the
orchestrator degrades to deterministic (FND-7). J13/ECI-6 has already ruled that a validation
failure should instead feed **one FND-42 repair turn**, and records that wiring as *not yet done*
🔬. Adding these rules raises ECI-6's value, so it is a co-requisite, not a nice-to-have. The safe
interim is the nullability property from §3: a model that stays silent never trips the new rules.

Second required ADK edit, easy to miss: `stampGeneratedAt` 🔬 (`:688-696`) rebuilds the record
**positionally**. The two new components must be passed through or the model's cause and
resolution are silently dropped. The arity change makes this a compile error rather than a silent
data loss — which is the argument for taking the arity break rather than avoiding it.

---

## 6. Backward compatibility — what breaks, verified

1. **`DiagnosisControllerPostResponseShapeRegressionTest` fails.** It asserts
   `content().json(EXPECTED_JSON, true)` 🔬 — strict, "no extra/missing fields" per its own
   comment at `:122`. The default Spring mapper serializes nulls (proved by `"errorCode": null`
   at `:73` 🔬; there is no `spring.jackson` config in `application.yml` 🔬), so the response gains
   `"likelyCause": null, "likelyResolution": null`. **This is the guard working as designed** —
   update `EXPECTED_JSON` with two keys, do not weaken the matcher.
2. **11 positional `new DiagnosisReport(...)` sites** 🔬 — 2 in `src/main`
   (`DeterministicDiagnosisEngine`, `AdkDiagnosisEngine`) and 9 in `src/test`. All become compile
   errors. Mechanical, and each test file has a single helper builder.
3. **A compatibility overload was considered and rejected.** Jackson binds records via the
   canonical constructor 🔍; an extra constructor risks ambiguity and would need explicit
   annotation. Worse, it would let nine test call sites keep silently constructing null-cause
   reports — the opposite of what we want. And item 1 fails regardless, so the "no breakage"
   benefit is illusory.
4. **ADK deserialization is safe.** Its mapper sets `FAIL_ON_UNKNOWN_PROPERTIES = false` 🔬
   (`:74`); omitted fields become null, extra fields are ignored.
5. **No stored JSON fixtures** — `find src -name "*.json"` returns nothing 🔬. The only golden
   JSON is the inline `EXPECTED_JSON`.
6. **`DiagnosisReportNoteTest` keeps passing.** `notesStillCarryEvidenceAndAssignment` uses
   `.contains(...)` 🔬, so additive note text is fine.
7. ⚠️ **FND-2 edge.** `suggestedContactsNeverAppearInServiceNowNotes` checks only names drawn from
   `r.suggestedContacts()` 🔬. A person named inside a quoted `close_notes` or runbook `snippet`
   would slip past it. For ServiceNow-sourced text this is not a new disclosure (the name is
   already in that ticket system); for a Confluence snippet it could be 🤔. Worth a sentence in
   J4's rules; not worth a scrubber this phase.

---

## 7. Rendering

**`toDiagnosisNote()`** — insert cause after "Likely involved systems" (why follows where) and the
resolution steps after the assignment, before "Recommended next check":

```
Likely cause (medium confidence, from a previously resolved incident): …
Possible mitigation (low confidence): …
Possible permanent fix (medium confidence): …
```

`basis == NONE`/null renders one line — *"Likely cause: not enough evidence to state one."* —
rather than vanishing.

The trailing disclaimer must grow one clause. Today it reads "No reassignment, closure, or
priority change has been made — the assigned engineer decides" 🔬, which disclaims **our** actions
and says nothing about *this fix might be wrong*. Add: *"The cause and resolution above are
inferred from the cited sources and have not been verified against this incident — treat them as
leads, not instructions."* That single sentence, in the one artifact that reaches ServiceNow, is
the mechanism for success criterion 3.

**UI (`index.html`)** — two cards after "Candidate systems", each showing statement, a confidence
pill (reusing `confidencePillHtml`), a plain-English rendering of `basis`, and its cited evidence
ids.

**The easily-missed second UI edit**: lines `1235-1238` 🔬 are the *write-back preview* card — the
UI's rendering of what was posted to ServiceNow, hand-mirrored from `toDiagnosisNote()` rather
than derived from it. If only the note changes, the UI asserts on screen a note body that differs
from the one actually posted. That is a J23-class honesty defect (a UI state claiming something
that did not happen) and it is invisible to every existing test. ❓ The durable fix is to derive
that preview from the note text instead of mirroring it; out of scope here, but the mirroring
must at least be updated in the same commit.
