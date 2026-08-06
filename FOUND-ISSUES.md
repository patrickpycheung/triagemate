# Found issues

**Backlog: 0 open.** FND-87 (raw stack trace in the ServiceNow work note) was resolved on
2026-08-06 and moved to the archive. FND-84a/85a/85/86 were all resolved on 2026-08-05 and moved to
[`docs/audit/found-issues-archive.md`](docs/audit/found-issues-archive.md).

All four came out of the `cause-and-resolution-sections` DDS. Two (**FND-84a/85a**) turned
out to be duplicates: `worktree-hack-111` found and **fixed** the same similarity defect
concurrently, from the opposite direction — a live run reporting "Find Similar Incidents
always returns zero hits", against a design exploration reading the query construction.
Neither worktree could see the other; hack-111's merge to develop landed mid-run. The other
two were novel and are fixed: **FND-85** (`fixed:a867de0`, a raw NUL byte that made `grep`
silently skip the largest orchestration file) and **FND-86** (`fixed:14fa031`, FND-67's
self-poisoning still live on the ADK path — carded as **J27**).

**All four share one escape layer: `mock-fidelity`.** The mock supplies a realistic `0.91`,
hand-tuned matching incidents, and writes notes to the log so no journal accumulates — so
every one of these defects is unreachable from `mvn test` and from the demo, and only exists
against a real instance. That is not four independent mistakes; it is one missing verification
layer, and it predicts more of the same shape are still unfound. **Retro input.**

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
