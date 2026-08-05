# Phase 1 — ELICIT ✅

**Problem**: TriageMate's report says *what* broke and *who* owns it, never *why* or
*how to fix it*. Add two sections: **likely cause**, **likely resolution**.

## Why it isn't a two-field patch

The new sections are a different epistemic class from every existing field. Today's
fields are observations and routings, defensible because J4 mechanically enforces
*every conclusion ties to `evidenceRefs`*. A **cause** is an inference beyond the
evidence (correlation → *because*). A **resolution** is an instruction to act on a
production incident, written into a retained ServiceNow journal by a system whose whole
posture is *advisory — it comments, it never acts*.

The real question is not "can we add two strings" (trivially yes) but **what makes a
cause/fix claim honest enough to post**.

## The finding that shapes everything

`ResolvedIncident.resolutionCode` / `.resolutionNotes` are **already fetched by both the
mock and real ServiceNow gateways** (`close_code`/`close_notes`) and **read by nothing**.
The mock data already contains a cause and a fix shape. So a section built on *how
similar past incidents were actually resolved* is not a guess — it is a citation of
history, needing no new connector and no LLM. 🔬 Spiked.

## Documents

| File | Contents |
|------|----------|
| [problem-statement.md](problem-statement.md) | The gap, why it's non-trivial, success criteria |
| [evidence.md](evidence.md) | Six 🔬 Spiked codebase findings + the open unknowns |
| [constraints.md](constraints.md) | 7 hard (H1–H7), 5 soft (S1–S5), and the non-constraints |

## Success criteria (summary)

Grounded · honestly hedged · advisory-safe · works in both engines · demo-legible · cheap.

## Scope decision (ADM-2, agent-decided)

Both sections live on the **J4 `DiagnosisReport`** and render to **both** the ServiceNow
note and the UI — the plainest reading of the request. J9's UI-only rule exists for
named-individual PII and doesn't transfer. Fallback if explorations show ungrounded fix
advice is the dominant risk: a J9-style UI-only gate or config flag. See `STATUS.md`.

## Most important open constraint

**H7 — abstention must be legal.** If the schema makes "no cause determined" invalid, the
contract itself forces the model to fabricate. Every proposal is tested against this.
