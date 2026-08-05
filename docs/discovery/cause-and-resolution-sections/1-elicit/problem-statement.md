# Problem Statement

## The gap

TriageMate turns a blank ticket into a head start — but the head start stops short of
the two questions an engineer actually opens a ticket asking:

1. **Why did this happen?**
2. **What do I do about it?**

Today's `DiagnosisReport` (J4) answers neither. It is deliberately **what/who**-shaped:

| Field | Renders as | Question answered |
|-------|-----------|-------------------|
| `reportedSymptom` | "What appears to have happened" | what |
| `candidateSystems` | "Likely involved systems" | where |
| `suggestedAssignment` | "Suggested assignment group" | who |
| `recommendedNextAction` | "Recommended next check" | what next (one step) |
| `missingInformation` | "Still missing" | what's unknown |

`recommendedNextAction` is the closest thing to "how", but it is a *single verification
step* ("Confirm the user has the ORDER_SUBMITTER entitlement"), not a remedy. Nothing in
the contract expresses a **causal hypothesis** or a **fix**.

## Requested change

Add two sections:

1. **Likely cause** — why the system believes this happened.
2. **Likely resolution** — how it can likely be fixed.

## Why this is not a trivial field addition

The two new sections are a **different epistemic class** from every existing field:

- Existing fields are *observations and routings* — "this evidence points at Order
  Portal". They are defensible because J4's rule is *every conclusion ties to
  `evidenceRefs`*, enforced mechanically by `DiagnosisReportValidator`.
- A **cause** is an inference *beyond* the evidence. Evidence shows correlation
  (error code + a recent commit + a runbook); cause asserts *because*.
- A **resolution** is an *instruction to act* on a production incident, written into a
  ServiceNow journal that is retained and auditable.

The app's entire posture is **advisory — it comments, it never acts**. A "how to fix"
section is the closest the product has ever come to that line. The design question is
not "can we add two strings" (we can, trivially) but **what makes a cause/fix claim
honest enough to post**.

## Success criteria

A proposal succeeds if:

1. **Grounded** — cause and fix trace to gathered evidence via `evidenceRefs`, holding
   the J4 rule that no conclusion floats free.
2. **Honestly hedged** — expresses uncertainty; never states a guess as fact. Degrades
   to "insufficient evidence" rather than inventing.
3. **Advisory-safe** — cannot be mistaken for an authorised instruction; survives the
   worst case where the fix is wrong and an engineer follows it.
4. **Works in both engines** — deterministic (no LLM) and ADK produce it. The
   deterministic engine is the demo's guaranteed-safe path and cannot invent prose.
5. **Demo-legible** — visibly answers why + how on stage in one glance.
6. **Cheap** — hackathon rigor; no new connectors, no new external dependency.
