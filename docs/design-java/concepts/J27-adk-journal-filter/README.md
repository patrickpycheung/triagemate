# J27 — ADK journal filter (FND-67 closure on the agent path)

**State**: 🟢 Built · **Complexity**: Simple · **Depends on**: J2, J4, J5

> **ID note**: J26 is already in use in code for the similar-incident ranking work
> (`SimilarIncidentRanker`, `SymptomTokens`, `triage.servicenow.*` properties) landed from
> `worktree-hack-111`, though it has no card directory yet. This card takes J27 to avoid
> the collision.

## Essence

FND-67 — the app reading its **own** advisory notes back as if they were human ticket
conversation — was fixed on the deterministic path only. The ADK agent path was never
covered, so with `triage.engine=adk` the bug was still live.

`isAiAuthoredNote` had exactly two call sites, both deterministic-path helpers
(`IncidentSignals`, `MentionedPeople`). Meanwhile:

1. `IncidentContext` carries `comments` and `workNotes`.
2. `TriageMateTools.getIncident()` returned that record to the model **unfiltered** —
   unlike `findOwnership`, which deliberately projects to a `Map` and drops fields.
3. `AdkDiagnosisEngine`'s instruction *directs* the model to read "the ticket conversation
   (comments / work notes)".

So run 2 of a diagnosis saw run 1's `[AI Triage · …]` notes as ordinary conversation — on
the path the demo calls "the real thing working".

## Rule

**The agent never sees this app's own notes.** Filtering happens at the **tool boundary**
(`TriageMateTools`), not in the prompt, so it holds regardless of what the model chooses to
do — the same "enforce at the boundary, don't ask the model nicely" thesis as J8's
capability bound and J18.

`IncidentContext.withoutAiAuthoredNotes()` returns a copy with both journals filtered
through `DiagnosisReport.isAiAuthoredNote`. The tool layer calls it; the gateway is
unchanged, so the deterministic path keeps its existing behaviour and the raw context stays
available to anything that legitimately needs it.

## Why it matters more than the drift it currently causes

Today the observable damage is keyword and contact-name drift — the "AI Triage" suggested
as a person to talk to that FND-67 documents.

The consequence upgrades sharply if a cause/resolution section is ever added
(`docs/discovery/cause-and-resolution-sections/`): an assertive cause statement read back
as human evidence turns drift into **confidence laundering** — run 1's hedged hypothesis
becomes run 2's corroborating "human" evidence becomes run 3's stated cause. That chain is
undetectable by `evidenceRefs` validation, because every link is a genuine, correctly-cited
artifact. Closing this is therefore a prerequisite for that work, not merely adjacent to it.

## Verification

`AdkJournalFilterTest` — fails-before / passes-after:

- an `IncidentContext` carrying a prior `[AI Triage · First-pass diagnosis …]` work note
  reaches the tool layer with that note **removed**, and the human entries **intact**;
- the filter matches the prefix anywhere in the entry, not at position 0, because
  `RealServiceNowGateway` prefixes each journal line with its author
  (`"sys_created_by: <text>"`);
- `null` journals stay null-safe.

## Escape

`mock-fidelity` — the mock profile writes notes to the log rather than to a journal, so no
AI-authored entries ever accumulate to be re-read. The bug is unreachable in `mvn test` and
in the demo, and only exists against a real instance. Same escape layer as FND-84/85/86.
