# Patterns across the seven explorations

Seven independent reads (six agents + the orchestrator), no communication between them.
Convergence below is therefore genuine agreement, not shared framing.

---

## P1 — HIGH CONFIDENCE (7/7): the system may cite a cause, never assert one

Every exploration arrived at this independently, by different routes:

| Exploration | Route to the same conclusion |
|---|---|
| first-principles | Evidence is *selection-driven* — the Confluence query is built from the ticket's own keywords, so a matching page is the search working, not evidence. Likelihood ratio ≈ 1. |
| prior-art | Only vendors with a **causal substrate** (Dynatrace's Smartscape topology, Traversal's causal ML) say "the root cause". Every text/precedent-based product hedges. TriageMate has no substrate. |
| risk-averse | An asserted cause is what turns FND-67 drift into *confidence laundering* across runs. |
| technical-depth | `evidenceRefs` prove traceability, not support — a citation of the wrong *kind* passes today. |
| minimum-viable | Quoting history sidesteps the epistemic problem entirely; the deterministic engine can only select anyway. |
| user-centric | A historical label survives being wrong; a prescriptive one does not. |
| claude-orchestrator | Attribution converts an instruction into a report, which is the speech act the evidence can actually carry. |

**The design consequence is structural, not stylistic**: there should be **no free-prose
field** in which an assertion could live. Fields hold a *quoted finding* and a *cited
artifact*. Invention has nowhere to go. This also gets the deterministic engine for free —
it can select and template, and that is all the schema permits.

## P2 — HIGH CONFIDENCE (6/7): abstention is the common path, not the edge case

Independently established, and the evidence stacks in one direction:

- 📚 **Real close notes are mostly empty of content.** Prior-art: ServiceNow resolution
  notes in practice are overwhelmingly *"Issue resolved"*, *"Done"*.
- 🔬 **Matching is near-random on live data.** FND-85: `findSimilarIncidents` filters on
  the *first word* of the short description.
- 🔬 **There is no similarity signal at all.** FND-84: `similarity` is hardcoded `0.5`.
- 📚 **The best-performing published agent won by abstaining.** Roy et al. (FSE 2024):
  best precision came from a ReAct agent whose incorrect predictions were **66%
  "Insufficient Information"**; hallucination 6% vs 18% for chain-of-thought.
- 🔬 **The repo has already ruled this way once.** `DiagnosisReportValidator:100-103`
  deliberately exempts `suggestedAssignment` from the citation rule, with the stated
  reason that forcing a citation "would push the code toward inventing one".

So constraint **H7 (abstention must be legal)** is not a safety nicety — it is the
*primary* runtime path against real Australia Post data. A design that treats "no cause
established" as an error state has inverted its own statistics.

**Uncomfortable corollary, stated plainly**: this feature will look excellent in the mock
demo and will abstain much of the time in production. That is the honest outcome, and it
is *still worth building* — but it must not be discovered on stage.

## P3 — HIGH CONFIDENCE (6/7): mitigation and permanent fix are different things

Different reversibility, different evidence requirement, different actor, different
cost-if-wrong. Merged into one "resolution" string, they force the reader to do risk
triage at 3am and invite applying a code-fix line with the restart-line reflex.

🔬 The mock fixture already yields both halves cleanly: `INC0011902` is
`Resolved - Code Fix`, `INC0011455` is `Resolved - Known Error` — each citing its own
`e-sim-*` row, with no fixture edit.

## P4 — HIGH CONFIDENCE (5/7, zero dissent): no percentages

- 📚 ServiceNow's own "Similar Resolved Incidents" is ranked and deliberately shows **no
  percentage**.
- 📚 HAX guideline G2-B: numeric precision must match measured performance. TriageMate has
  measured none.
- 📚 Displayed **high** confidence measurably *decreases* human performance; low-confidence
  displays increase deliberation. High confidence is the dangerous label, not the safe one.
- 🔬 `index.html:1187` records "No percentage, no progress bar" as **direct product
  feedback** — already settled, and a confidence bar would re-litigate it.
- 🔬 FND-84: the one percentage currently rendered from real data is a hardcoded constant.

**The hedge should be a denominator, not a percentage**: *"2 of 2 similar resolved
incidents"* — deterministic-computable, no LLM, and it degrades to *"0 of 3"*, which reads
as an explicit warning rather than a weak endorsement.

⚠️ This casts a shadow on existing code: prior-art notes the `%.0f%%` already rendered on
`candidateSystems` implies a calibration the app has never measured. Out of scope here,
worth a look.

## P5 — HIGH CONFIDENCE (4/7): safety must be structural, not lexical

A deny-list of dangerous verbs loses to paraphrase — *"cycle it"*, *"give it a kick"*,
*"let it re-sync"* all mean restart. The convergent answer is an **allow-list as a closed
Java enum**, so Jackson rejects any unknown constant and mutating verbs are *unreachable
in both engines*, with no `OTHER` escape hatch.

🔬 This is not a new policy — it is codification of one the codebase already follows by
accident: `DeterministicDiagnosisEngine:524-538` currently emits only observation verbs
(Review / Trace / Widen / Reproduce), by authorship habit rather than by rule.

Same thesis as J18, and the same one the guardrail's leg 1 already relies on: **enforce at
the boundary, don't ask the model nicely.**

## P6 — MEDIUM (3/7): plurality is the hedge, and it is also the anti-anchor

"Likely" is skimmed past; a *second bullet* is not. Two hypotheses, each with its
falsifier (*"if this is it, you'd also see X"*), converts an anchor into a test. This
reuses J4's existing never-one-forced-answer rule rather than inventing a mechanism.

Tension with P2: if abstention is the common path, two hypotheses will rarely be
available. Resolution: **≥2 or none** is a rule about *rendering*, not about generation —
when only one is available it still renders, but without the plural framing that would
imply a shortlist exists.

## P7 — the finding nobody was looking for: mock-only verification is the escape layer

FND-84, FND-85 and FND-87 are three unrelated defects that share one property: **the mock
profile structurally cannot reveal any of them.** The mock supplies realistic similarity,
hand-tuned matching incidents, and writes notes to the log so no journal accumulates to
re-poison.

This is a process finding, not a code finding, and it is the highest-value output of the
whole exploration. The escape layer is *"no test exercises the real gateway's data
quality"* — not four independent mistakes. It belongs in a retro, and it predicts that
more defects of this exact shape are still unfound.
