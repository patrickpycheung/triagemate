# Concepts extracted → CDS handoff

**From**: DDS `cause-and-resolution-sections`, 2026-08-05
**Into**: `docs/design-java/` as new concept cards
**Rigor**: Hackathon/RAPID

> ## ⚠️ Updated 2026-08-05, post-merge — both prerequisites are now DONE
>
> This document originally proposed **J26** (the feature) and **J27** (fix similar-incident
> relevance, blocking), plus FND-87 as a hard prerequisite. All three numbers moved, and
> both prerequisites have since been resolved:
>
> | Originally | Now |
> |---|---|
> | J26 — the feature | **J28** — `J26` was already claimed in code by the peer ranking work |
> | J27 — fix similar-incident relevance (*blocking*) | ✅ **Already shipped** as develop's **J26** (`SimilarIncidentRanker`, `SymptomTokens`, config-driven `resolved-states`). Filed here as FND-84/85, archived as duplicates of hack-111's FND-84. |
> | FND-87 — ADK self-poisoning (*prerequisite*) | ✅ **Fixed** — carded as **J27**, `fixed:14fa031` |
>
> `worktree-hack-111` found and fixed the similarity defect concurrently and independently,
> from a live run ("Find Similar Incidents always returns zero hits") while this DDS reached
> it by reading the query construction. **The path to J28 is therefore clear** — the
> blocking work it identified is done, by someone else, before the card was written.
>
> One consequence for the honest-expectation section at the foot of this document: the
> pessimism there was partly premised on first-word matching and a fabricated similarity.
> Both are gone. Real `close_notes` sparsity (📚 prior-art) still stands, so abstention
> remains common — but less so than estimated, and open-question Q1 is now the only thing
> gating a real number.

---

## Recommendation in one line

Build it as **precedent citation, not causal assertion**. The prerequisite — a
similar-incident signal worth citing — is now in place.

---

## J28 — Precedent-grounded cause & resolution

**State**: 🔵 Proposed · **Complexity**: Moderate · **Depends on**: J4, J5, J7, **J26**, **J27**

> **Evidence-basis prerequisites** (`/doc-test dds`, 2026-08-05 — Codex flagged all three
> HIGH, Claude corroborated the J13 one). Each `InferenceBasis` rests on a concept that is
> **🔴 designed, not built**, so enabling it early would produce faithfully-quoted nonsense:
>
> | Basis | Rests on | Risk if enabled now |
> |---|---|---|
> | `PRIOR_RESOLUTION` | J26 ✅ built | — safe |
> | `KNOWN_ERROR_DOC` | **J25** 🔴 | Confluence search currently returns unrelated pages; quote fidelity would prove only that an irrelevant page was quoted accurately |
> | `CODE_PATH` | **J13** 🔴 | code-evidence ids collide and citations can name the wrong system |
>
> J26's ranker also weights `cmdb_ci` at 0.3, and **J24** 🔴 shows reference fields currently
> parse to `""` — so ~30% of the ranking input is dead until J24 lands.
>
> **Ruling**: ship J28 restricted to `PRIOR_RESOLUTION` only. The other two bases stay in the
> enum (the schema is right) but are not emitted until their concept is built. This is a
> config/emit restriction, not a schema change, so nothing is thrown away.

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
        List<String> citedArtifacts,  // ["INC0011902","INC0011455"] — what the reader can open
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
        String citedArtifact,         // singular ON PURPOSE — one step quotes one incident's
                                      // fix. Only LikelyCause aggregates ("2 of 2"), which is
                                      // why that one is a list. Do not "fix" this to match.
        List<String> evidenceRefs
) {}

enum InferenceBasis { PRIOR_RESOLUTION, KNOWN_ERROR_DOC, CODE_PATH }
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
- **CR-7 — MEDIUM ceiling.** `LikelyCause` carries **no confidence field of its own** — the
  denominator is the entire uncertainty signal (rule 3). CR-7 therefore constrains the
  *report-level* `confidenceOverall`: a report whose only new signal is a `LikelyCause` may
  not raise `confidenceOverall` above `MEDIUM`. Analogical transfer is never HIGH.
  *(`/doc-test dds`: Codex caught that the original wording capped a field that did not
  exist, which would have left the rule silently modifying the report's global confidence.)*
- **CR-8 — quote fidelity**: `quotedFinding` must be a substring of a cited artifact's
  text, so invention is mechanically detectable rather than merely discouraged.

### Files that change

`DiagnosisReport.java` (+3 records, +2 enums, `toDiagnosisNote()`),
`DiagnosisReportValidator.java` (+3 rules), `DeterministicDiagnosisEngine.java` (insert at
the existing `findSimilarIncidents` step — locate it by symbol, not by line; J26's ranker
rework moved this region), `AdkDiagnosisEngine.java` (prompt delta
+ `stampGeneratedAt` arity), `TriageMateTools.java`, **`index.html` (twice — the report
render AND the hand-mirrored note preview at `:1235-1238`)**,
`DiagnosisControllerPostResponseShapeRegressionTest` (will fail strictly — that is the
guard working), 11 positional `new DiagnosisReport(...)` call sites.

### Est. cost

~3h for the flat MVP; ~6–8h for the full shape above. Both well inside hackathon scope.

---

## ~~Similar-incident relevance & honesty~~ ✅ SHIPPED as develop's J26

**Superseded — no card needed.** This was proposed here as a blocking prerequisite, on the
reasoning that J28 is a *rendering of* `findSimilarIncidents`, and that signal was broken
against real data in two ways (first-word `LIKE` matching; a hardcoded `0.5` rendered as
"(50% similar)" onto real tickets). Shipping the feature on top would have produced the
worst available outcome: **correct-looking attribution pointing at an unrelated ticket**,
converting attribution — J28's primary safety mechanism — into borrowed credibility for
noise.

`worktree-hack-111` fixed exactly this, concurrently and independently, reaching it from a
live run rather than from a design read. Landed on develop as **J26**: retrieve wide on the
keys that carry signal (`cmdb_ci` + distinctive symptom terms), then rank locally via
`SimilarIncidentRanker` (text Jaccard 0.6 + CI 0.3 + category 0.1), with `resolved-states`,
`similarity-floor` and `max-similar` as `triage.servicenow.*` properties rather than
literals.

Verified post-merge: the hardcoded `0.5` is gone and `SimilarIncidentRanker.rank()` computes
a real score. **This prerequisite is satisfied.**

---

## ~~Hard prerequisite (bug, not concept)~~ ✅ FIXED as J27

**FND-86 (filed here as FND-87) — ADK path still self-poisons.** `isAiAuthoredNote` had two
call sites, both deterministic-only; `TriageMateTools.getIncident()` handed raw
`comments`/`workNotes` to the model while the instruction directs it to read them.

The reason this blocked J28 specifically: today it drifts keywords and contact names, but
with a cause section it becomes **confidence laundering** — run 1's hedged hypothesis
becomes run 2's corroborating "human" evidence becomes run 3's stated cause, a circular
chain `evidenceRefs` validation cannot detect because every link is a genuine, correctly
cited artifact.

Fixed at the tool boundary (not in the prompt, so it holds however the model behaves) and
carded as **J27** — `docs/design-java/concepts/J27-adk-journal-filter/`, `fixed:14fa031`,
regression `TriageMateToolsJournalFilterTest` + `IncidentContextAiNoteFilterTest`, both
profiles green at 266 tests. **This prerequisite is satisfied.**

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
