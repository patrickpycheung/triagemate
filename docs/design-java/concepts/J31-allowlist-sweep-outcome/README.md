# J31 — Allowlist sweep outcome (the sweep must report what actually happened)

**State**: 🟢 **Built** (2026-08-06) — ASO-1…ASO-4 all shipped, with FND-89 (`emitStep` had no
way to say a step failed) fixed as their prerequisite. 7 new tests, 6 of which were confirmed
failing against the pre-change engine. · **Complexity**: Moderate
**Depends on**: J30 (estate binding), J14/FRI-5 (per-call degradation), J6 (GitLab gateway contract)
**Amends**: **J30/GEB-3** only (which specified the failed-vs-empty distinction for a single
attempt, not for a sweep).
**Does not amend J30/GEB-2** — ASO-1 *restores* it. GEB-2's text is correct and unchanged; the
code disagrees with it. Implementing a rule is not amending it.
**Does not amend J14/FRI-5** — ASO-4 satisfies its trace contract rather than changing it.
**Source**: derived while checking cheungp's
[`Patrick_gitlab-update_allowed-projects.md`](../../../Patrick_gitlab-update_allowed-projects.md)
against the tree, 2026-08-06. Patrick's recommendation is **already implemented** (J30/GEB-1);
this card is the defect that implementation exposed.

> **Round 3 correction (2026-08-06).** The first draft of this card claimed *"all four J30
> rules hold as written"* and that this card amended GEB-3 alone. **That was wrong**, and the
> conflict pass caught it: GEB-2 already requires per-project continuation, so the shipped
> `break` violates a converged rule rather than falling into a gap beside it. The draft also
> mis-stated GEB-4 as proposed (it shipped), overstated the reachability of ASO-A, and carried
> a third rule (mode-specific project eligibility) that reaches far outside this card — now
> split out as [J32](../J32-allowlist-eligibility-vs-authorization/README.md). Recorded rather
> than silently rewritten, because the error is the useful part: a card that *asserts* its
> neighbours are unaffected is exactly the claim a conflict round exists to test.

## Essence

`triage.gitlab.allowed-projects` holds two entries by design (J30/GEB-1): the real estate
project, and the offline demo fixture `order-payments/payment-service`, which does not exist
in the real estate. `IncidentSignals.rankAllowlist` **sorts and never filters**, so in real
mode the phantom is a live candidate the engine calls GitLab with.

`DeterministicDiagnosisEngine.java:417-429`:

```java
for (String project : projectsToTry) {
    try { codeHits = gitLab.searchCode(project, errorToken); }
    catch (GatewayUnavailableException e) {
        codeSearchFailed = true;   // J30/GEB-3
        …
        break;                     // ← ends the sweep
    }
    if (!codeHits.isEmpty()) break;
}
```

Two defects, and **neither is a new design question** — both are places where the code
disagrees with rules that are already converged.

### ASO-A — the `break` violates J30/GEB-2

GEB-2 is unambiguous: *"a project that cannot resolve is a **skipped** project, not a failed
run"*, and J30's own verification table requires *"a 404 on the first ranked project → the
sweep continues to the next"*. `GatewayUnavailableException`'s javadoc says the same thing a
third time: *"the engine catches per call site … and continues: the safety net degrades per
call, never per run."*

The code breaks out of the loop. So a phantom entry ranked first stops the real project from
ever being searched.

**Reachability, stated precisely** (the first draft overstated this): `List.sort` is stable
and `application.yml` lists the real project first, so equal or zero token overlap preserves
config order and the real project goes first. The phantom leads **only** when it has *strictly
greater* overlap with the incident's app — e.g. an incident whose app is "Payment Service".
Rarer than the draft implied, and worse when it happens.

### ASO-B — the outcome records the last attempt, not the sweep

`codeSearchFailed` is a boolean set by whichever attempt failed last. When the real project is
searched first and legitimately returns empty, the loop continues, the phantom 404s, and the
run reports **"could not search"** for a search that ran and truthfully found nothing.

GEB-3 asked for exactly this distinction, and shipped it — for **one** attempt. With a
one-entry allowlist the aggregation question could not arise. GEB-1 added the second entry and
made it arise.

**Why GEB-3's own test cannot catch this**: `CodeSearchFailedIsNotNoMatchTest` has three tests
and all three use a *uniform* gateway stub — every project unreachable, or every project empty.
The mixed sweep is unreachable by construction. That is the escape layer, and ASO-3 below is
written to close it.

## Design

> **As built (2026-08-06).** ASO-1 is `continue` in the sweep loop; ASO-2 is
> [`SweepOutcome`](../../../../src/main/java/com/company/triage/orchestration/SweepOutcome.java),
> which owns all five states and the phrasing for each; ASO-3 is
> `SweepingGitLabGateway` (a double whose behaviour varies by project) plus
> `RecordingTraceSink`; ASO-4 is one `emitStep` per attempt, which required FND-89's fix to
> `emitStep` first. The sections below are the design as reasoned, kept for the why.


### ASO-1 — restore GEB-2: a failed project is skipped, the sweep continues

`continue`, not `break`. This is **not a new rule** — it is GEB-2, implemented. J31 owns the
correction; J30 keeps the rule.

### ASO-2 — the sweep reports a structured outcome, not a boolean

A boolean cannot express a sweep. Replace `codeSearchFailed` with the attempt record —
projects attempted, which succeeded, which failed and why — and derive the report line from
it. **Five states, not two**:

| Outcome | The report must say |
|---|---|
| Hits, every attempt succeeded | the citations |
| Hits, but some attempt failed | the citations **and** the failures — the hits are real, and they may not be all of them |
| All projects searched, none had hits | searched, found nothing — **not** a degradation |
| Some searched (empty), some failed | searched *partially*; names what could not be reached |
| No project could be searched | could not search — with the failures |

**Five states, not four** (Round 5 correction). The draft's "any project returned hits → the
citations" silently dropped an earlier failure, and ASO-1 is what makes that reachable: once a
failed project no longer ends the sweep, *failure followed by a hit* becomes an ordinary
outcome. Reporting only the hit would let the reader believe the search was complete when one
project was never reached — a smaller version of the same overclaim ASO-B is about.

The third row is the one the first draft got wrong. It said any successful attempt makes the
sweep "not a degradation" — **false**: if another project failed, nothing is known about that
project, and a report claiming a clean negative would overclaim. Partial is its own state.

**Failures must carry the project.** `GatewayUnavailableException` holds only the system
(`"GitLab is unreachable: …"`), so two failed entries are indistinguishable in
`gatewayFailures` today. The attempt record stores `{project, error}`, or at minimum the
engine prefixes the project when it records the failure. Without this, the "partial" state
cannot name what it could not reach, which is the only thing that makes it actionable.

### ASO-3 — the mixed sweep is a fixture case, not just a rule

Add the mixed-outcome stub GEB-3's tests lack: a gateway that succeeds-empty for one project
and throws for another. This is the J14/FRI-6 idea (a fixture corpus is what stops finding
number six) applied to the sweep, and it is the artefact that keeps ASO-B closed.

### ASO-4 — the trace needs a state for a partly-failed sweep

J14/FRI-5 requires a failed call to resolve its `TraceStep` as **`FAILED`**, never `DONE` —
"using `DONE` would make the trace assert a step succeeded when it did not". A sweep with one
success and one failure fits neither state, and today `emitStep` emits `DONE` regardless,
including for the "COULD NOT SEARCH" line.

**Decision (ADM-2, settled 2026-08-06 from the code, Round 5): one `TraceStep` per attempt.
No new `StepState`.** The two candidates were per-attempt rows, or a single aggregate row with
a new `PARTIAL` state. Three facts decide it:

1. **The spine already models this unit.** `TraceSink.before/after/onError` wrap *a call*, and
   a sweep is N calls. Per-attempt rows are what LT1 already describes; the aggregate line
   ("`gitLab.searchCode(...) → N hit(s)`") is a separate narrative `emitStep`, not a lifecycle
   row, and it stays.
2. **A 7th enum member costs more than it looks.** `StepState` has six members
   (`PENDING, ACTIVE, DONE, FAILED, DENIED, ABANDONED`), and adding one means the enum, the
   renderer's `stepStateToDataState` map, its `console.assert` line, the CSS block, and LT5's
   prose — for a distinction the per-attempt rows already show.
3. **ASO-2 needs per-project attribution anyway.** Per-attempt rows carry it for free; an
   aggregate would need the same information threaded separately, i.e. the same facts in two
   representations.

So a failed attempt resolves `FAILED` (FRI-5's contract, unchanged), a successful one `DONE`,
and "partial" is a property the reader *sees* — some rows failed, some did not — rather than a
state the enum has to name. **This card therefore no longer amends J14/FRI-5's trace
contract**; it satisfies it.

**The aggregate row cannot stay `DONE`.** `emitStep` hardcodes `StepState.DONE` for every
narrative step it writes (`DeterministicDiagnosisEngine.java:752-761` — `sink.after(... DONE
...)`, unconditional). So **today's "COULD NOT SEARCH" line is already emitted as `DONE`**:
the text says the search failed while the state says the step succeeded, which is the exact
thing FRI-5 forbids ("using `DONE` would make the trace assert a step succeeded when it did
not"). That is a live defect independent of this card, and ASO-4 cannot leave it standing while
claiming the trace is honest. `emitStep` takes an explicit state; the sweep's aggregate row is
`FAILED` when no attempt succeeded, `DONE` otherwise, with the per-attempt rows carrying which
was which.

> **Corrected in Round 5, and worth keeping as a caution.** An earlier revision of this section
> claimed LT5's `StepState`→CSS mapping was a member behind the enum and that `ABANDONED` was
> unmapped. **False** — the renderer maps all six (`index.html:1003`, CSS at 489-491, asserted
> at 1017). The claim came from LT5's *prose*, which still says "test all five", rather than
> from the renderer. Only the doc is stale. This is the second time in this card's short life
> that a confident statement about a neighbour came from reading a document instead of the
> code, which is precisely the habit the conflict round exists to catch.

## Relationship to J30 — corrective, not competing

| J30 rule | Shipped? | This card |
|---|---|---|
| GEB-1 — allowlist names the real estate, entries labelled | ✅ | **Cause, not defect.** The second entry is correct and stays |
| GEB-2 — an unresolvable project is skipped, not fatal | ⚠️ **rule converged, code disagrees** | **ASO-1 repairs it** |
| GEB-3 — trace distinguishes "no code matched" from "could not search" | ✅ for one attempt | **ASO-2 extends it** to a sweep |
| GEB-4 — an unresolvable entry is visible before a demo | ✅ **shipped** (`StartupBanner.gitLabAllowlistNote`) — warns when the demo project is the *sole* real-mode entry | Untouched. Deliberately does not fire on the current config, where a real entry is present |

## Verification

| Check | Passes when |
|---|---|
| Failing FIRST entry | remaining projects are still attempted; a later hit is still cited |
| Real search empty + later entry fails | report says *searched partially*, naming the unreachable project — neither a clean negative nor a blanket "could not search" |
| All entries fail | *could not search*, listing each failure with its project |
| All entries searched, none hit | *searched, found nothing* — no degradation claimed |
| Mixed-outcome fixture exists | a stub that succeeds-empty for one project and throws for another (ASO-3) |
| Trace | every attempted project is attributable; no step claims `DONE` for a failure (ASO-4) |
| `mvn test` | green, with a regression test per ASO-1/ASO-2 failing before the change |

## Out of scope

- **The allowlist's contents** — J30/GEB-1 owns them, and they are correct.
- **Mode-specific project eligibility** (should the demo fixture be a real-mode candidate at
  all?) — split to **[J32](../J32-allowlist-eligibility-vs-authorization/README.md)**. It
  changes a list that J6 uses as a *security allowlist* and that also feeds ADK tool
  validation, the ADK instruction text, gateway enforcement and the startup banner. Not this
  card's blast radius.
- **`searchCode`'s error handling** — J14/FRI-5 owns it; this card changes only what the
  *caller* does with the exception.
- **`GitLabGateway.searchCode` staying singular** — it searches one project and must continue
  to (J6). The sweep is the engine's, and stays there.
