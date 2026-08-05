# First-principles exploration — cause & resolution sections

Trust levels: 🔬 Spiked (verified in code) · 📚 Documented · 🔍 Inferred · 🤔 Assumed · ❓ Unknown

---

## 1. What is a "cause" claim, formally?

A causal claim `C → E` asserts a counterfactual: had `C` not obtained, `E` would not have
obtained. That is strictly stronger than any statement about co-occurrence.

Inventory what TriageMate actually gathers (🔬 all four verified in
`DeterministicDiagnosisEngine`):

| Signal | What it is | Relation to causation |
|---|---|---|
| Confluence page mentions the symptom | co-occurrence in **text** | none |
| ERROR line in the same `_sourceCategory` in the last 24h | co-occurrence in **time** | weak temporal |
| Error token appears in a source file | co-occurrence in **token space** | mechanism-plausibility |
| Resolved incident with a similar `short_description` | similarity in **text space** | analogy |

None is counterfactual. Worse — and this is the sharpest epistemic point — most are
**selection-driven**. The Confluence query is composed from the ticket's own keywords
(🔬 `confluenceQuery` is built from `IncidentSignals`, and the trace line renders as
`confluence.search(query="...") → N page(s)`). A returned page that mentions the symptom is
**the search working**, not evidence about the world. Its likelihood ratio is ≈ 1. Confirmatory
retrieval is not confirmation.

The same applies, more weakly, to the GitLab hit: the engine searches for `errorToken`
(🔬 `gitLab.searchCode(project, errorToken)`), so finding the token in the file that emits it is
near-certain and carries little discriminating information *about which change broke it*.

**Conclusion**: TriageMate has no warrant to assert a cause. Anything shaped like "X caused Y"
is a category error wearing a confidence band.

## 2. The one thing that changes the picture

There is exactly one evidence class in the system that is not correlational at all:

🔬 `ResolvedIncident.resolutionCode` + `.resolutionNotes` — fetched as ServiceNow's
`close_code` / `close_notes` by the real gateway (`RealServiceNowGateway:135-140`) and
populated in the mock. Verified by grep: **read by nothing in `src/main/java`.**

These fields are a **human's closed causal verdict**. A person diagnosed a similar incident,
applied a fix, observed it stop recurring, and wrote down why. They did the counterfactual work.
That is not correlational evidence about *this* incident — it is **testimony**, and TriageMate's
job is to transfer it by analogy.

This reframes the whole feature. The honest claim is never:

> The cause is a discount applied after tax.

It is:

> When this shape of symptom was seen before (INC0011902), a human found the cause to be
> a discount applied after tax, and fixed the order of operations in `payment_service`.

The causal content is **quoted**. The system's own assertion is the *analogy* — "this resembles
that" — which is precisely the kind of similarity claim its evidence is made of. 🔍

**TriageMate is not a diagnostician. It is a witness-locator.** The cause section should be a
citation, not a conclusion. Every design decision below follows from this.

### 2a. The confidence trap

🔍 The confidence attached to a cause section is **not** confidence in the mechanism. It is
confidence in the **transfer** — that this incident is relevantly like the precedent. Conflating
the two is the core dishonesty risk of the whole feature.

`similarity = 0.91` (🔬 real gateway hardcodes `0.5`; the mock carries `0.91`/`0.78`) is a
text-similarity score. It is *not* P(same cause). Rendering "91%" next to a causal statement
invites exactly that reading, and readers under time pressure will take it.

**Rule**: never surface the raw similarity number beside a cause claim. Use the coarse
`Confidence` band (🔬 already exists, documented as "for human-facing conclusions"), and make
**MEDIUM the hard ceiling** for any cause hypothesis. Analogical transfer is never HIGH. Making
the ceiling a validator rule rather than a hope is the difference between a design and a wish.

## 3. Is "likely cause" one thing?

Three distinguishable things, with different availability:

1. **Mechanism / proximate cause** — "discount applied after tax, reconcile check fails".
   Requires reading code *and understanding it*. Available only as quoted precedent.
2. **Trigger** — what changed. 🔍 This is the only category where the engine holds
   genuinely causally-flavoured evidence: a commit since the last release touching the file that
   emits the observed error token. Temporal precedence + mechanism-plausibility is
   Bradford-Hill-lite. Still not causation — but not the vacuous confirmatory kind either.
3. **Contributing condition / scope** — "only discounted orders", "only prod". Derivable from
   the ticket itself. Cheapest signal, and the one the engine is *strongest* at.

🤔 Which does an on-call engineer want at triage time? Not (1). At 2am on a P1 they want (3)
then (2): *who is affected, and what changed*. Mechanism is what goes in the next morning's RCA;
it does not shorten the outage. The naive design would lead with mechanism — that ordering is
backwards for the actual reader.

**Decision (ADM-1, inline)**: do not create three sections. Create one ranked list whose
entries **declare their kind** via a `CauseWarrant` enum. The kind is what carries the
epistemic warrant, so it must be in the data; but three sections is over-engineering at
hackathon rigor and would hurt criterion 5 (demo-legible in one glance).

### The warrant ladder

| Warrant | Evidence in hand | Licenses | Band |
|---|---|---|---|
| `PRECEDENT` — a human's closed verdict | `e-sim-*` + `resolutionCode`/`Notes` | cause **and** fix | MEDIUM |
| `CHANGE` — commit since release touching the emitting file | `e-code-*` + `e-log` | cause | MEDIUM if log↔code tie, else LOW |
| `SCOPE` — condition shared by affected cases | `e-incident` | contributing condition | LOW |
| *(procedure)* — a runbook step | `e-kb-*` | **containment only — never a cause** | LOW |

🔬 Evidence-id prefixes verified: `e-incident`, `e-log`, `e-cmdb`, `e-kb-*`, `e-sim-*`,
`e-code-*` are all constructed in `DeterministicDiagnosisEngine`, so the warrant rules are
enforceable by cheap prefix checks in the validator.

The last row is the important one. If a Confluence hit may license a cause, then — because the
search is confirmatory — *every* incident gets a cause, and the section degrades into noise with
a confidence band on it. The asymmetry (a runbook may license an *action* but not a *claim*)
falls straight out of §1: a runbook step's warrant is that a human wrote it down as a procedure,
and it is safe-if-wrong.

### Corroboration

🔬 Both mock precedents (INC0011902 @ 0.91, INC0011455 @ 0.78) cite substantively the same
cause. Two independent human verdicts agreeing is genuinely stronger than one. The codebase
already has this exact intuition for Contacts (🔬 `sourceCount`, FND-64: corroboration across
distinct sources ranks higher). Reuse it: merge agreeing precedents into **one** hypothesis
citing both refs. No new scoring machinery — the extra `evidenceRefs` entry is the signal.

## 4. Resolution: one section or two?

**Two. This is the position I would defend hardest after §2.**

Mitigation and fix differ on every axis that matters:

| | Containment | Durable fix |
|---|---|---|
| Reversibility | reversible by definition | not reversible |
| Evidence needed | *plausible and safe-if-wrong* | *correct* |
| Cost if wrong | ~5 wasted minutes | a second incident on top of the first |
| Actor | the on-call, alone | the owning team, with review |
| Horizon | minutes | days |

Merging them into one "likely resolution" blob **forces the reader to perform the risk
classification the system should have performed** — at precisely the moment they are least able
to. And it produces the worst concrete safety failure: an engineer scanning one list and
executing the code-fix line with the same reflex they applied to the restart line.

Success criterion 3 requires surviving the case where the fix is wrong and an engineer follows
it. The merged design fails it. The split design survives it:

- **Wrong containment**: reversible by construction (reversibility is the admission ticket to
  the field), so the cost is bounded at minutes.
- **Wrong durable fix**: framed in reported past tense and addressed to the owning team — an
  engineer cannot "follow" it without going through that team, which *is* the review step that
  catches it.

🔍 **Grammatical mood does real safety work here.** Indicative-past ("was fixed by…") is safe;
imperative ("apply the fix to…") is not. 🔬 The existing `recommendedNextAction` is imperative
("Review %s:%d, which emits '%s'") and gets away with it because reviewing a file is harmless.
Fix advice must not inherit that mood by proximity.

Note this yields **three** action-flavoured fields, each mapping to a distinct reader-moment,
with no overlap: *investigate* (`recommendedNextAction`, exists), *contain* (new), *fix* (new).

## 5. Designing the degradation so "unknown" is the default

The failure mode to design against: **a section that exists must say something**. Empty
sections read as bugs to implementers, so implementers fill them. Every hardcoded-narrative
incident already in this codebase is this exact pathology — 🔬 FND-63 (`function` was the
literal "Order submission (checkout)"; the narrative fields were hardcoded prose about checkout
reconciliation, "an outright fabrication for any other incident") and FND-8 (narrating an
investigation step that did not occur).

Discipline will not fix this. **Construction** will. Three mechanisms:

1. **No free-prose field in the cause record.** The statement is not `String statement`
   authored by the engine — it is `quotedFinding`, a verbatim (truncated) quotation of
   `resolutionNotes`, plus a template frame supplied by the renderer. With fields named
   `citedArtifact` / `quotedFinding` / `evidenceRefs`, **there is nowhere for invention to
   live**. The deterministic engine physically cannot fabricate, which satisfies criterion 4
   for free, and the ADK engine is pushed toward extraction rather than generation.

2. **Empty list is the type-level default**, and renders explicitly:

   > *Likely cause*: not established. No resolved incident, recent change, or scope condition
   > ties to this symptom. (See "Still missing".)

   🔍 Word choice matters: "not established", not "unknown". "Unknown" reads as *we didn't
   look*; "not established" reads as *we looked and it isn't there*. This is genuine, actionable,
   negative information — it tells the engineer not to spend twenty minutes hunting for a
   precedent that does not exist. Present it as an outcome, not a gap.

3. **Cross-link the abstention to `missingInformation`.** The engine already computes exactly
   why it came up short (🔬 the `missing` list: no ERROR lines in 24h, no runbook matched,
   environment unset, app was inferred, logs not searched). Zero new work; the abstention
   explains itself.

## 6. Should cause be tied to a candidateSystem?

Yes in structure — cause is a **two-place relation**. A cause claim floating free of a subject
is not truth-apt: it cannot be wrong, so it cannot be right. There is a concrete safety
consequence too: 🔍 a report naming three candidate systems and one unlocated cause invites the
reader to attach the cause to the top-ranked candidate, which may not be where the precedent's
cause lived.

But full referential integrity (validator requires `subjectSystem` ∈ `candidateSystems[].name`)
has a real cost, and an honest counterargument: `resolutionNotes` is free text and will not
reliably name a system in a form matching a CMDB CI name (🔬 the mock's notes name
`payment_service`, a *repo/file* token, while candidates are prettified logger names). Enforcing
the tie would force either a fabricated mapping or a silently dropped hypothesis — both worse
than a null.

**Decision (ADM-2, agent-decided)**: carry `subjectSystem` as **nullable and advisory**,
populated only when the engine can tie it honestly (the precedent's `resolutionGroup` matches a
candidate's owner, or the code hit's project matches a candidate). The validator does **not**
require a match. Render it when present ("in Payment Service"), omit when absent.

*Rationale*: gets the two-place structure into the schema now so it can be tightened later,
without forcing invention today. *Expectancy I am watching*: if `subjectSystem` is null on most
real runs, the tie was never real, and the field should be **dropped** rather than propped up
with heuristics.

## 7. What this costs

- No new connector, no new external dependency (criterion 6) — the source data is already
  fetched and discarded.
- Three new records + three fields on `DiagnosisReport`, plus rendering in `toDiagnosisNote()`.
- Four new validator rules, all cheap: non-empty `evidenceRefs` (mirrors 🔬 the existing
  `uncitedCandidates` rule verbatim), MEDIUM ceiling on cause confidence, `reversible == true`
  on containment, and no-cause-from-`e-kb-*`.
- 🔬 `DiagnosisReportValidator.validate(report)` is already called by the deterministic engine,
  so the rules are enforced on both paths with no new wiring.

## 8. Open questions

- ❓ Does real ServiceNow reliably populate `close_notes`, or is it blank on most closed
  tickets? If mostly blank, `PRECEDENT` collapses and `CHANGE` becomes the load-bearing warrant.
  Worth one live query before committing.
- ❓ The real gateway hardcodes `similarity = 0.5` (🔬 `RealServiceNowGateway:140`), so there is
  no real similarity signal in production today. This does not break the design (the band is
  coarse and capped at MEDIUM regardless), but "how similar is similar enough to transfer?" has
  no answer from data yet.
- 🤔 Whether the ADK engine can be held to quotation-only for `quotedFinding` without a
  post-hoc substring check against the source artifact. A substring assertion in the validator
  would close it cheaply and is probably worth doing.
