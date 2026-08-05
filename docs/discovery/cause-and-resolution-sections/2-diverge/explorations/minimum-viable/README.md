# Exploration: Minimum Viable / 80-20

**Bias**: simplest thing that could possibly work on a hackathon timeline.
**Recommendation**: *Cite history, don't guess causes.*

## The MVP: "Prior resolution" — cause and fix quoted from resolved incidents

The app already fetches, for every incident, a list of previously-resolved
incidents carrying `resolutionCode` + `resolutionNotes` (🔬
`DeterministicDiagnosisEngine.java:156`; `MockServiceNowGateway.java:95-108`;
`RealServiceNowGateway.java:130-142`). Nothing in main source reads
`resolutionNotes`. That field *is* the cause-and-fix text — already written by
a human, already in the ticket system, already fetched, already free.

So the MVP does not invent a causal claim. It says: **"incidents matching this
one were resolved like this."** That is a citation of history, not an inference
beyond the evidence — which sidesteps the entire epistemic problem the problem
statement raises. Advisory safety comes for free: quoting what someone else did
last time is not an instruction.

Where it falls down: a genuinely novel incident with no similar ticket. Handled
by degrading, not inventing — both fields go null, and one line lands in
`missingInformation`. 🔬 The engine already has this exact pattern.

## Schema delta

Three flat components on `DiagnosisReport`, no new files, no nesting:

```java
String likelyCause,            // null when unsupported
String likelyResolution,       // null when unsupported
List<String> causeEvidenceRefs // shared: both claims cite the same tickets
```

One shared refs list, not two, because in this approach cause and resolution come
from the *same* source rows. `causeEvidenceRefs` goes into the existing
`danglingRefs` check in `DiagnosisReportValidator` (3 lines) so the J4 rule "no
conclusion floats free" stays mechanically enforced.

Rejected: one nested record (costs a file, a null-check, and hits the exact
LLM-nesting hazard the ADK prompt already warns about at
`AdkDiagnosisEngine.java:170-175`); reusing `recommendedNextAction` (it answers
"what next", works today, and merging two epistemic classes into one string
makes both less trustworthy).

## Build cost

**≈3 hours**, one person: record +3 components and 2 note lines (15m), validator
(10m), deterministic engine (45m), ADK prompt +3 keys and one tool-description
clause (30m — 🔬 `resolutionNotes` already reaches the model, no new tool), one
UI card (20m), 11 constructor call sites and the strict-JSON regression test
(30m), two engine tests (30m).

## What we consciously DON'T build

No root-cause inference engine, no causal-chain reasoning, no confidence score
on the cause itself, no per-claim refs (one shared list), no "apply this fix"
affordance, no new connector, no new ADK tool, no schema for structured remedies
(free text only), and no attempt to produce a cause when no similar incident
exists — silence beats a plausible fabrication, and the FND-8/FND-63 history in
this repo is a list of what happens when we choose otherwise. We also do not fix
the real gateway's weak similarity match (hardcoded `0.5`, single-keyword
`LIKE`); we hedge the wording so it never claims more than that match earns.
