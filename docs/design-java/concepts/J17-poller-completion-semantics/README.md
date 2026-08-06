# J17 — Poller Completion Semantics (diagnosed ≠ delivered ≠ nobody-else-did-it)

**State**: 🟡 **Mostly built** (2026-08-06) — PCS-1 (conjunctive completion), PCS-2 (bounded redelivery queue, no engine re-run), PCS-3 (per-call delivery outcome) and PCS-5 (loud unattended failure) landed. **Remaining: PCS-4** (cross-trigger completion — needs the orchestrator to record what it diagnosed so the poller can consult it) · **Complexity**: Moderate · **Priority**: MEDIUM ·
**Depends on**: J1, J5, J10 · **Amends**: J10 (what "handled" means; the "never diagnose
the same incident twice" property), J1 (writeback failure disclosure) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 2 confirmed
findings + 1 unverified tail item

## Essence

K1 currently calls an incident **completed** on one fact: `orchestrator.run()` returned
without throwing. That is the weakest of the three facts that actually matter. A run can
return normally having **delivered nothing** (both advisory comments lost to a transient
ServiceNow failure), and an incident can be **already diagnosed by the other trigger** and
still look brand-new to the poller. This card defines completion as a conjunction —
*diagnosed* **and** *delivered* **and** *not already done by anyone else* — and gives each
conjunct a mechanism that costs no extra LLM run.

## Why this is a concept, not a bug fix

The three findings look independent and are not. They all reduce to the poller reading a
single boolean (`run() didn't throw`) as if it answered three different questions, and the
obvious piecemeal fixes actively fight each other:

- Fixing the writeback loss by "leave it out of the completed set so the next tick retries
  it" ([the finding's own first suggestion](#evidence--what-the-review-found)) re-runs a
  full ADK diagnosis to fix a failed HTTP PATCH — which is exactly the behaviour **FND-36
  was created to remove** (`DiagnosisOrchestrator.java:241-250` says so verbatim: "for K1
  that meant a writeback-only failure looked identical to a diagnosis failure and re-ran
  the whole (expensive, LLM-backed) diagnosis on a later tick instead of just retrying the
  write"). Fixing finding 1 that way re-opens FND-36.
- And it re-opens it *into* finding 2's failure mode: an ADK re-run produces
  non-identical note text, so `RealServiceNowGateway`'s exact-match dedupe
  (`RealServiceNowGateway.java:171-194`) does not suppress it, and the ticket collects a
  second pair of comments.
- Meanwhile the retry that *is* safe — re-posting the **byte-identical** note text — is
  only safe because of that same exact-match dedupe, and only *provably* safe if the trace
  and the retry agree on **which** of the two calls actually failed. That is finding 3.

So the three fixes share one substrate: the poller must know, per incident, *what was
produced*, *what was delivered*, and *who else already did it*. Decide them together or
the first fix breaks the second.

## Evidence — what the review found

| # | What | Where | Severity | Failure scenario |
|---|---|---|---|---|
| 1 | K1 marks an incident completed even when writeback failed — the two advisory comments are permanently lost after one attempt | [`IncidentPoller.java:192-202`](../../../../src/main/java/com/company/triage/orchestration/IncidentPoller.java#L192-L202) (`markCompleted` at :201) | MEDIUM | Poll enabled overnight (the K1 use case). INC0012345 is created; diagnosis succeeds; ServiceNow returns a transient 503 on the first `addWorkNote`. `runOnce()` catches it and returns `writebackPosted=false`; the poller logs "diagnosed via ADK", marks completed, and the FND-41 cursor advances past it. K1 has no UI and no buffer, so the two comments were the run's **only** durable output — gone, with one WARN line as the record. |
| 2 | Poller re-diagnoses an incident the manual trigger already completed — FND-31's original scenario survives in sequential form | [`IncidentPoller.java:184`](../../../../src/main/java/com/company/triage/orchestration/IncidentPoller.java#L184) (`completed.contains(number)`) | MEDIUM | Poll enabled with `interval-ms` raised past a run's duration. Incident created at T; presenter clicks at T+2s; the ADK run finishes at T+45s posting two comments. The tick at T+120s selects the incident (created after the cursor, absent from the *poller's* completed set), runs a second full ADK diagnosis, and posts two more comments whose wording does not match — four advisory notes on a real ticket plus doubled unattended LLM spend. |
| 3 | Partial-writeback trace disclosure "at most one of the two comments may have posted" is wrong in two sub-cases — **unverified** (LOW tail item; one-look check before acting) | [`DiagnosisOrchestrator.java:263-265`](../../../../src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java#L263-L265) | LOW | Real ServiceNow, slow but functioning: the second PATCH lands server-side at 21s, the client read timeout fires at 20s. The ticket shows **both** comments; the trace asserts at most one. And even in the ordinary case the trace is self-contradictory — it contains a definite "posted 'Sources consulted' comment" line two lines above a hedge saying at most one *may have* posted. |

**What the verifier sharpened.** On finding 1 (HIGH confidence): `IncidentPoller` contains
**zero** references to `writebackPosted` — confirmed by grep — while `markCompleted` is
called unconditionally on normal return; `triage.writeback.enabled` defaults to `true`
(`application.yml:88`), so this is the default unattended path, and `IncidentPollerTest`
has no writeback-failure case at all. This is an unreported residual of FND-36, not a
tracked item (`/FOUND-ISSUES.md` is empty).

On finding 2 (HIGH confidence): `DiagnosisOrchestrator`'s FND-31 fix is **concurrency-only
by design** — `DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced`
pins sequential non-coalescing as *intended* behaviour, and the class javadoc
(`DiagnosisOrchestrator.java:64-68`) calls the poller's bookkeeping "complementary, not
redundant". But that bookkeeping is **poller-scoped**: nothing anywhere covers
manual-completes-then-poller-discovers. The verifier also narrowed the blast radius
honestly — poll is off by default, and the four-comment outcome needs poll-enabled
real-connector mode *plus* an interval exceeding run duration (at the default 30s tick a
~45s ADK run usually still overlaps and coalesces). At the default interval the bite is a
wasted duplicate run on the fast/degrade path, where identical deterministic text *is*
deduped. MEDIUM, at the low end.

## Design

### PCS-1 — Completion is a conjunction, and the poller stores which conjunct failed

`handled` (the flag that lets the FND-41 cursor advance) stops meaning "`run()` returned"
and starts meaning **diagnosed and delivered**:

```
handled = ran-without-throwing
          AND (result.writebackPosted() OR writeback disabled)
```

`writeback disabled` counts as delivered because with `triage.writeback.enabled=false`
there was never anything to deliver — the run's output is the HTTP response, and the
disabled case already announces itself in the trace (`DiagnosisOrchestrator.java:268`).

**Rejected: freezing the cursor on an undelivered incident.** That is the natural reading
of J10's "the first unhandled incident freezes the cursor", and it is wrong here. A
permanent write failure — a `write-field` the instance rejects, a revoked write ACL —
would stall *every subsequent incident* behind one ticket forever, converting a
delivery bug into a total triage outage. Diagnosis and delivery get **separate** state:
the cursor tracks diagnosis (unchanged), and delivery gets its own retry queue (PCS-2).

### PCS-2 — Undelivered runs go to a bounded delivery queue, not back through the engine

On `writebackPosted=false`, the poller retains the run's two note bodies (`toSourcesNote()`
/ `toDiagnosisNote()` — plain strings, already computed) in a bounded FIFO
`pendingDelivery` map keyed by the normalized incident number, and drains it at the **top
of each tick**, before the new-incident query. Each drain attempt calls `addWorkNote`
only — no engine, no LLM, no ADK.

- Re-posting is idempotent by construction: the text is **byte-identical** to what was
  attempted, so both gateways' exact-match dedupe suppresses anything that actually landed
  (`RealServiceNowGateway.java:185-194`, `MockServiceNowGateway.java:122-129`) — including
  the finding-3 lost-response case where the PATCH succeeded server-side.
- Bound: the same `triage.trigger.poll.completed-cap` (default 500) that already bounds
  the completed set. **Rejected: a second knob.** Two properties bounding the same
  in-process "how much do we remember about recent runs" budget is exactly the config
  drift FND-57 consolidated away.
- Attempts are bounded too: after `N` failed drains (**pick: 3**, i.e. up to ~90s of
  retries at the default interval) the entry is dropped with an `ERROR` naming the
  incident and both note bodies' first line, so the run is at least recoverable from the
  log. Retrying forever turns one broken ticket into a permanent per-tick error loop.
- **Rejected: retrying inside `runOnce()`.** A synchronous in-run retry would sit in front
  of K3's HTTP response and in front of the poller's single scheduler thread, and a 503
  that lasts 30s would blow the FND-15 wall-clock budget's sibling assumption that
  writeback is fast. The scheduler tick is already the right retry clock; K1 is the
  trigger with no human to notice, and it is the one that gets the queue.

### PCS-3 — Delivery outcome is tracked per-call, not as one boolean

`runOnce()` records **which** of the two `addWorkNote` calls threw, and that fact drives
three consumers instead of one:

| Case | `writebackPosted` | Trace disclosure | Delivery-queue payload |
|---|---|---|---|
| first call threw | `false` | "neither advisory comment was confirmed posted" | both notes |
| second call threw | `false` | "'Sources consulted' posted; 'First-pass diagnosis' unconfirmed — the write may still have landed and the response been lost" | both notes (dedupe suppresses the one that landed) |
| both succeeded | `true` | existing two "posted" lines | — |

This replaces the fixed string at `DiagnosisOrchestrator.java:263-265`, which claims "at
most one of the two advisory comments may have posted" in every case — false when the
second PATCH landed and the response was lost, and self-contradictory against the definite
"posted 'Sources consulted' comment" line the same trace already carries two lines up.
`writebackPosted` stays `false` for *unconfirmed*: for a project whose FND-8/FND-25/FND-36
lineage is entirely about disclosed status matching ground truth, "we do not know" must
never render as "posted".

**Finding 3 is unverified** (LOW tail item). Before implementing, re-read
`DiagnosisOrchestrator.java:251-269` and confirm the catch block is still call-agnostic —
it is a one-look check.

### PCS-4 — Cross-trigger completion: the orchestrator records, the poller consults

The orchestrator is already "the ONE place their calls meet" (its own javadoc,
`DiagnosisOrchestrator.java:55-68`). It gains a bounded, insertion-ordered
`recentlyCompleted` set — every incident number that reached the end of `runOnce()`
successfully, **whatever trigger asked for it** — and exposes a read-only
`wasRecentlyCompleted(String)`. `IncidentPoller` consults it in the same position as its
own `completed.contains(number)` check (`IncidentPoller.java:184`), treating a hit exactly
as it treats its own: skip, and count as handled so the cursor advances over it.

**This is advisory to the poller only. The orchestrator must not refuse a run because of
it.** A presenter clicking Diagnose a second time on stage must get a real second run —
`DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced` pins that as
intended behaviour and this card does not amend it. The asymmetry is the point: a human
deliberately re-triggering is a decision; a scheduler rediscovering a ticket is an
accident.

**Rejected: querying ServiceNow for an existing AI work note before each run.** It is the
durable option (survives restart, sees runs by a *previous* process) and J10's Open/risks
already sketches it. But it costs one journal query per candidate per tick, it only works
under `connectors.servicenow=real`, and it needs a stable marker in the note text that
nothing currently guarantees. **Decision: build the in-process set now, note the query as
the durable upgrade** — it belongs with J10's still-undecided "persist the cursor +
completed set" item, and shipping it piecemeal would create the second half of a durable
state design without the first.

Normalization is already correct at both call sites (`IncidentPoller.java:181` and
`DiagnosisOrchestrator.java:179`, both `trim().toUpperCase()` per FND-50) so the new set is
keyed identically to the coalescing map with no new normalization site.

### PCS-5 — Unattended delivery failure is loud

K1 has no UI. Today the only trace of a lost writeback is one `WARN` from
`DiagnosisOrchestrator.java:261`. Under this card the poller logs, per incident, at
`WARN` on queueing ("diagnosed but not delivered — queued for redelivery, attempt 1/3")
and at `ERROR` on give-up, in the same register as the existing FND-8 degradation WARN
(`IncidentPoller.java:196-197`). No new channel: J8's observability contract is the log
on the unattended path, and this card does not introduce a competing one.

## Verification

All of these run under the default profile (no ADK needed — the engines are stubs in these
tests), so the 152/201 baseline moves on the default count only.

- `IncidentPollerTest#writebackFailureDoesNotCountAsCompleted` — orchestrator stub returns
  `writebackPosted=false`; assert the incident is **not** in the completed set and the
  incident is queued for redelivery. Direct cover for finding 1, which today has no test.
- `IncidentPollerTest#queuedDeliveryIsRetriedWithoutRerunningTheEngine` — next tick posts
  the two notes and increments **no** engine-call counter. This is the assertion that keeps
  FND-36 closed while fixing finding 1.
- `IncidentPollerTest#redeliveryGivesUpAfterThreeAttemptsAndLogsError` — bounded retry
  (PCS-2), asserted via a capturing log appender as the existing FND-45 WARN tests already
  do (`#warnsWhenPollingWithAdkEngineAndNoAck`).
- `IncidentPollerTest#writebackDisabledStillCountsAsCompleted` — the PCS-1 conjunct that
  must *not* regress: `enabled=false` runs still advance the cursor.
- `IncidentPollerTest#incidentAlreadyDiagnosedByTheManualTriggerIsNotRediagnosed` — run
  the orchestrator once directly (standing in for K3), then poll; assert exactly **one**
  engine call and two work notes total. This is finding 2 stated as an executable claim,
  in J10's "two correctness properties" style.
- `DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced` — **must stay
  green unchanged**. It is the guard that PCS-4's record did not quietly become a refusal.
- `DiagnosisOrchestratorTest#partialWritebackDisclosesWhichCommentIsUnconfirmed` — extends
  the existing `#partialWritebackFailureIsDisclosedNotLost` (which asserts only the generic
  "writeback failed partway through" substring): assert the second-call-failed trace names
  the first comment as posted and the second as unconfirmed, and that a first-call-failed
  run says neither was confirmed. Covers finding 3 once verified.
- Idempotency of redelivery leans on `RealServiceNowGatewayTest#skipsWhenIdenticalNoteAlreadyExists`
  and `#anExactPrefixIsNotTreatedAsADuplicate`, which already exist and already pass — no
  new test needed there, but the dependency should be stated in the redelivery code's
  comment so a future change to the dedupe rule surfaces this consumer.

## Out of scope

- **Durable cursor / completed-set across restarts** — J10's Open/risks item, still
  undecided; PCS-4 deliberately stops at in-process state and names the ServiceNow-query
  option as its successor.
- **Live-trace buffers, aliasing, and what a coalesced client watches** — J16
  (run-trace-registry-lifecycle) and J12 (live-trace-delivery). K1 sends no `runId` and
  registers no buffer by design (LT4 rule 4); this card does not change that.
- **Real-connector HTTP contract coverage in general** — J22 (real-gateway-contract-tests).
- **Which incidents qualify for triage at all** (`C-T4` narrow criteria) — J10's open item,
  a cost/noise question, not a completion-semantics one.
- **The FND-43 same-second batch-limit collision** — accepted and closed in J10; PCS-1's
  conjunction does not change it either way.
