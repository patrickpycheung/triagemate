# Found issues

**Backlog: 4 open** — FND-84…FND-87, all found 2026-08-05 during the
`cause-and-resolution-sections` DDS. Three are live defects on the **real-data path that
the mock profile structurally cannot reveal**: a hardcoded `0.5` rendered onto real
tickets as "(50% similar)", similar-incident matching on the description's first word
only, and FND-67's self-poisoning still unfixed on the ADK path (the filter has two call
sites, both deterministic-only). The fourth is a non-UTF-8 byte that makes plain `grep`
silently skip the largest orchestration file, which already caused two wrong conclusions
in one session.

That clustering is itself the signal: **mock-only verification cannot see any of the
first three.** Worth a retro input — the escape layer is "no test exercises the real
gateway's data quality", not four unrelated mistakes.

**Previously resolved.** FND-70…FND-83 were all resolved on 2026-08-05 and moved to
[`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md) with a
**Resolution** and an **Escape** line each. Thirteen came from that day's whole-application
review (8-dimension multi-agent sweep + adversarial verification, cross-checked against
Codex `gpt-5.6-sol` and Gemini); FND-83 was field-reported by sajids4 from a live run. The
28 review findings that needed a *design decision* went to concept cards **J12–J25**, not
here — those cards remain 🔴 designed-not-built and are tracked in
[`docs/design-java/STATUS.md`](docs/design-java/STATUS.md).

**Earlier history — backlog was 0 before that too.** FND-55/56 (deferred pending a design decision) and FND-57/58
(deferred pending the `TriageProperties` refactor) were all fixed 2026-07-31 once
`/found-issues-resolve` re-tested each deferral's premise and found it decidable.
FND-59 (found by code review, not `/doc-test`) fixed the same day. Full detail in
`docs/audit/found-issues-archive.md`.

Queue of findings that need a decision or a fix and are not yet tracked elsewhere.
Resolved entries move to [`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md)
with two added lines: a **Resolution** (`fixed:<sha>` or `promoted:<card>`) and an
**Escape** (which *process layer* should have caught it — the input a future retrospective
clusters on).

Format: `## FND-<n> — <one-line title> · **HIGH|MEDIUM|LOW**`, then **Where** / **What** /
**Why it matters**. Add new entries at the bottom.

## When to log here vs. fix directly

- **Log it** when resolving it means a design decision, a behaviour change, or a data /
  API contract change — anything where picking the fix is itself the hard part.
- **Fix it directly** (no entry) when it is a genuinely small correction with an easy undo
  and no money / legal / data / governance / deploy surface. Don't force ceremony onto
  trivia.

---

## History

**FND-1…FND-8** — `/doc-test dds` (2026-07-29). Two were worth the trip on their own:
the trigger the DDS specified would have re-diagnosed every incident forever (its own
writes bumped the cursor it polled on), and the FND-7 fallback being correct but
*silent* — a run that never called the model looked exactly like success, and cost a
spike cycle before anyone noticed.

**FND-9…FND-32** — `/doc-test cds` (2026-07-30). 40 Phase-2 gaps + 27 raw conflicts
(Codex/Gemini/Claude), deduplicated to 24 entries; 5 more were fixed immediately without
an entry (the J8 allowlist that was documented but not implemented, J4's contact fields,
the silent mini-model default — see `b8b2dd0`). One systemic cause explained most of what
remained: J-cards were written at design time and the implementation moved past them
without the cards following — a process fix, not 24 independent mistakes.

The two most consequential, both real bugs rather than doc drift:

- **FND-14 / FND-31** — a real ServiceNow connector that didn't dedupe identical notes
  (only the mock did), and the manual trigger bypassing the poller's own duplicate-run
  guard — exactly the demo shape (polling on, presenter also clicks). Both fixed via the
  same mechanism: concurrent-call coalescing + idempotent writes at the one place K1 and
  K3 both call through, `DiagnosisOrchestrator`.
- **FND-17 / FND-19 / FND-20** — three guardrail claims that were prose, not code: no J4
  validator existed (a model could return an empty candidate list or a dangling evidence
  ref and the UI would render it), no test existed for the prompt-injection defense
  claim, and the Sumo log-search window/result-count bound was taken from the model with
  no server-side check at all. All three are now enforced and tested.

Full detail on all 32 resolved entries: `docs/audit/found-issues-archive.md`.

---

## FND-84 — every similar-incident line reports a fabricated "% similar" against real data · **HIGH**

**Where**: `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java:158-161`
renders `r.similarity() * 100`; `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:140`
supplies that value as the literal `0.5` for every row.

**What**: the real gateway hardcodes `similarity = 0.5` on every `ResolvedIncident` it
returns — there is no similarity computation in production at all. The deterministic
engine formats it as a percentage into an `Evidence` summary, so against a live instance
**every** similar-incident line reads:

```
INC0011455 (50% similar) resolved by Payments Platform Support: Resolved - Known Error
```

That evidence line is posted to the real ticket in the "Sources consulted" work note.

**Why it matters**: it is a fabricated statistic presented as a computed match score, on
a customer-retained incident record, by a system whose entire pitch is that every claim
is auditable and one click from its evidence. A reader has no way to tell that the number
is a constant — and "50%" reads as a real, if weak, computed match. `MockServiceNowGateway.java:101`
supplies a genuine-looking `0.91`, so the demo path never shows the problem: the defect is
invisible from the stage and only appears against live data. Minimum fix: suppress the
percentage when the value is the sentinel. Real fix: compute a similarity, or stop
claiming one. Found during DDS `cause-and-resolution-sections`.

## FND-85 — `findSimilarIncidents` matches on the first word of the description only · **HIGH**

**Where**: `src/main/java/com/company/triage/gateway/real/RealServiceNowGateway.java:129-142`
and `:314-316`.

**What**: `firstKeyword(s)` returns `s.split("\\s+")[0]` — the first whitespace-delimited
token — and the query is `stateIN6,7^short_descriptionLIKE<that token>`. For the
project's own canonical example, *"User receives HTTP 403 when submitting an order"*, the
production query is `short_descriptionLIKE User`. Incident short descriptions
overwhelmingly begin with "User", "Unable", "Cannot", "Users", "Error", so on a real queue
the filter is close to a no-op. The method's own comment concedes `// Naive keyword match`.

**Why it matters**: "similar past incidents" is load-bearing evidence — it drives the
`e-sim-*` evidence rows and the assignment-group suggestion, and it is the natural source
for any future cause/resolution section. Returning near-arbitrary resolved tickets while
labelling them "similar" (see FND-84) lends borrowed credibility to noise. Compounding:
the mock returns hand-tuned genuinely-similar incidents, so this is another defect the
demo structurally cannot reveal. Found during DDS `cause-and-resolution-sections`.

## FND-86 — a non-UTF-8 byte in `DeterministicDiagnosisEngine.java` makes plain `grep` silently skip the file · **MEDIUM**

**Where**: `src/main/java/com/company/triage/orchestration/DeterministicDiagnosisEngine.java`
(`file` reports `data`, not `Java source`); the byte is in the trace string near `:163`.

**What**: because GNU `grep` classifies the file as binary, a repo-wide
`grep -rn "<symbol>" src/` **omits every match in the largest orchestration file in the
project** and exits 0 with no warning. `grep -a` finds them.

**Why it matters**: this is a silent-wrong-answer hazard for exactly the audit workflows
this repo relies on. It produced two demonstrated wrong conclusions in a single DDS
session: a repo-wide grep for `resolutionCode`/`resolutionNotes` returned only the record
declarations, leading both an exploration agent and the orchestrator to independently
conclude "nothing reads these fields" — when `resolutionCode` is read at `:160`. Any
dead-code sweep, rename, or impact analysis run against this repo is unsound until it is
fixed. Fix is trivial (replace the byte with its ASCII equivalent); the value is in
removing the trap. Found during DDS `cause-and-resolution-sections`.

## FND-87 — FND-67 self-poisoning is only fixed on the deterministic path; the ADK path still feeds on its own notes · **HIGH**

**Where**: `src/main/adk/java/com/company/triage/agent/TriageMateTools.java:76-77`
(`getIncident()` returns the raw `IncidentContext`), vs the only two filter call sites,
`src/main/java/com/company/triage/orchestration/IncidentSignals.java:115` and
`MentionedPeople.java:197` — both deterministic-path helpers.

**What**: FND-67 (documented in `DiagnosisReport.AI_NOTE_PREFIX`'s javadoc) is the bug
where a second diagnosis of the same incident read the first one's work notes as ordinary
human conversation and drifted. The fix was `isAiAuthoredNote`, applied at two
deterministic-path call sites. The ADK path was never covered:

1. `IncidentContext` carries `comments` and `workNotes` (`IncidentContext.java:17-18`).
2. `TriageMateTools.getIncident()` hands that record to the model **unfiltered** — contrast
   `findOwnership` (`:89-95`), which projects to a `Map` and deliberately drops fields.
3. `AdkDiagnosisEngine.instruction()` (`:102`) *directs* the model to read "conversation
   (comments / work notes): the caller's own follow-ups often…".
4. `grep -arn isAiAuthoredNote src/main/` returns no hit anywhere under `src/main/adk/`.

So with `triage.engine=adk` against a real instance, run 2 sees run 1's `[AI Triage · …]`
notes as ticket conversation — the exact condition FND-67 documents, on the path the demo
calls "the real thing working".

**Why it matters**: it is a regression of an already-diagnosed bug, live on the flagship
path, and it is invisible in the mock profile (mock notes go to the log, so no journal
accumulates to re-read). It also compounds badly with any future cause/resolution section:
today the drift is in keywords and contact names, but an *assertive* cause statement would
make run 1's hedged hypothesis into run 2's corroborating "human" evidence and run 3's
stated cause — a circular evidence chain that `evidenceRefs` validation cannot detect,
because every link is a genuine, correctly-cited artifact.

The fix belongs at the tool boundary (filter in `TriageMateTools`, not in the prompt), so
that it holds regardless of what the model chooses to do — the same "enforce at the
boundary, don't ask the model nicely" thesis as J18. Found during DDS
`cause-and-resolution-sections`; verified independently by the orchestrator.
