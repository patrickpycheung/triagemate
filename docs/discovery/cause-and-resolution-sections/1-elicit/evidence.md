# Evidence — what the codebase already gives us

All claims below verified by reading/grepping the repo on 2026-08-05.

## 🔬 Spiked: a grounded resolution source already exists and is discarded

`ResolvedIncident` (`src/main/java/com/company/triage/model/ResolvedIncident.java`)
declares:

```java
String resolutionCode,
String resolutionNotes,
```

**Both gateways populate them:**

- Mock — `MockServiceNowGateway.java:97-105`:
  ```java
  new ResolvedIncident("INC0011902",
          "Checkout 500 error — payment reconcile mismatch on discounted orders",
          "Payments Platform Support", "Resolved - Code Fix",
          "Discount was applied after tax in the gateway; reconcile check failed. ...")
  ```
- Real — `RealServiceNowGateway.java:135-140` selects
  `number,short_description,assignment_group,close_code,close_notes` and maps
  `close_code` → `resolutionCode`, `close_notes` → `resolutionNotes`.

**`resolutionNotes` is read by nothing. `resolutionCode` IS read** —
`DeterministicDiagnosisEngine.java:160`.

> ⚠️ **Corrected 2026-08-05.** This section originally claimed *both* fields were unread,
> "verified" by a repo-wide `grep`. That grep was wrong: `DeterministicDiagnosisEngine.java`
> contains a non-UTF-8 byte, so GNU `grep` classifies it as binary and **silently skips the
> whole file, exiting 0**. `grep -a` shows the truth. Filed as **FND-86** — it produced two
> independent wrong conclusions (this one and an exploration agent's) in a single session.
> **Use `grep -a` when auditing this repo.**

```
$ grep -arn "resolutionCode\|resolutionNotes" src/main/ --include=*.java
.../orchestration/DeterministicDiagnosisEngine.java:160:  r.number(), r.similarity() * 100, r.resolutionGroup(), r.resolutionCode()),
.../model/ResolvedIncident.java:11:        String resolutionCode,
.../model/ResolvedIncident.java:12:        String resolutionNotes,
```

So the accurate picture is **narrower but still favourable**: the deterministic engine
already calls `findSimilarIncidents` (`:156`) and already builds `e-sim-<number>` evidence
rows from each result, using `resolutionGroup` and `resolutionCode`. It discards only
`resolutionNotes` — the one field carrying the actual causal narrative. The MVP is
therefore an *insert into an existing pipeline step*, not a new one.

That same line is also the site of **FND-84**: it formats `similarity * 100` as a
percentage, and the real gateway hardcodes `similarity = 0.5`, so live runs post
"(50% similar)" onto real tickets for every past incident.

**Why this dominates the design**: the mock resolution note already contains both a
cause (*"Discount was applied after tax in the gateway; reconcile check failed"*) and a
fix shape (*"Workaround then code fix"*). A cause/resolution section sourced from how
**similar past incidents were actually resolved** is not a guess — it is a *citation of
organisational history*, and it needs no new connector, no new API call, and no LLM.

## 🔬 Spiked: the contract mechanically enforces grounding

`DiagnosisReportValidator` rejects reports where a conclusion cites an `Evidence.id`
that was never listed, and rejects duplicate evidence ids because an `evidenceRef`
naming a duplicated id "cannot identify which item it cites". J4's stated rule is
*every conclusion ties to `evidenceRefs`*.

**Implication**: any new cause/resolution field must decide whether it participates in
this regime. If it does, it inherits the integrity guarantee. If it doesn't, it becomes
the one un-grounded field in an otherwise-grounded contract — a visible inconsistency.

## 🔬 Spiked: the app has already been burned by its own assertive output

`DiagnosisReport.AI_NOTE_PREFIX` javadoc documents **FND-67**: once the real gateway
began reading comments/work notes back (FND-61), a second diagnosis of the same
incident consumed *its own prior notes* as ordinary ticket conversation and drifted —
"AI Triage" was suggested as a person to talk to, extracted from the prefix itself.

**Implication**: cause/resolution text is far more assertive than any existing field.
The self-poisoning surface grows with this feature. The `isAiAuthoredNote` filter must
be re-verified against the new sections, not assumed sufficient.

## 🔬 Spiked: the note already gives directive advice

`toDiagnosisNote()` already emits `"Recommended next check: "` — an instruction to a
human on a production incident. Adding cause/resolution is therefore a **difference of
degree, not of kind**, which weakens (but does not eliminate) the "this crosses a new
line" objection.

## 🔬 Spiked: the deterministic engine is the binding constraint

Two engines must produce these sections:
- `DeterministicDiagnosisEngine` (752 lines) — no LLM. It can *select and template*,
  never invent prose. It is the guaranteed-safe demo path
  (README: *"nothing can fail"*).
- `AdkDiagnosisEngine` (814 lines) — live LLM via the Copilot proxy.

Any design that only works with an LLM fails the deterministic path, and the
deterministic path is the one the demo leans on when the network is hostile.

## 🔬 Spiked: response shape is deliberately guarded

`DiagnosisControllerPostResponseShapeRegressionTest` exists, so the API response shape
is a protected contract. Schema changes are expected to break it deliberately, not
accidentally.

## ❓ Unknown — to be resolved by exploration

- How accurate is LLM-proposed root cause in published incident-management research?
- What wording do comparable products use for a hedged machine-generated fix?
- Does `PromptInjectionGuardrail` cover a "suggested remediation" field, which is a far
  more attractive injection target than "candidate system"?
