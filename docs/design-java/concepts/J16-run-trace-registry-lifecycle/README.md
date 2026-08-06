# J16 — Run-Trace Registry Lifecycle (who owns a live buffer, and for how long)

**State**: 🟢 Built — RTR-1, RTR-2, RTR-4, RTR-5 complete; RTR-3 server-side complete, its
client-side twin **deferred** (see the note in §RTR-3) ·
**Complexity**: Moderate · **Priority**: MEDIUM ·
**Depends on**: J1 (orchestrator), J10 (poller), J11 (LT4 `runId` protocol) ·
**Amends**: J11 (LT4 `runId` protocol rules 4 and 5, and the "Bound the buffer" paragraph) ·
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 3 confirmed
findings + 1 unverified tail item

## Essence

A live trace buffer belongs to the **run that is currently in flight**, and it stays alive
for at least as long as that run is permitted to take. Today it belongs to *whoever last
sent a header for that incident number*, and it dies on a fixed 5-minute timer that no
constant in the codebase ties to the orchestrator's own wall-clock bound. Those two
mismatches are why the J11 promise "a coalesced caller watches the same live steps" is
unfulfillable in exactly the mixed-trigger shape FND-31 was built for.

## Why this is a concept, not a bug fix

The three confirmed findings all reduce to one sentence: **`alias()` resolves against
registration history instead of against the live run.** `InMemoryRunTraceRegistry` keeps
`currentRunIdByIncident` (`InMemoryRunTraceRegistry.java:73`), written only by `register()`
(line 90) and removed only by TTL/cap eviction (lines 128, 135) — never on run completion.
So the map answers "which runId most recently registered for this incident", which is *not*
the question `alias()` asks.

Fixed piecemeal, each finding pulls in a different direction:

- Patch #1 alone ("register K1 runs too") makes `currentRunIdByIncident` *more* populated
  and therefore makes the stale-binding case #2 **more** likely, not less — a finished K1 run
  now leaves a canonical entry behind for the next click to bind to.
- Patch #2 alone ("clear the incident index on `markDone`") leaves the K1 case #1 exactly as
  broken, because a K1 run never populated the index in the first place.
- Patch #3 alone (bump the TTL) hides the eviction coupling instead of removing it.

Designed together they collapse: once the orchestrator publishes the in-flight run's `runId`
alongside its `CompletableFuture`, the incident index has no remaining caller and can be
**deleted**, which retires both the stale-binding class and the K1-has-nothing-to-alias-to
class at once. That deletion is the concept; the three fixes are its consequences.

## Evidence — what the review found

| # | What | Where | Severity | Failure |
|---|---|---|---|---|
| 1 | A K1 (poller)-owned canonical run never registers a buffer, so a coalescing presenter click has nothing to alias to | [`DiagnosisOrchestrator.java:190`](../../../../src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java#L190), [`IncidentPoller.java:192`](../../../../src/main/java/com/company/triage/orchestration/IncidentPoller.java#L192) | MEDIUM | Poller picks up INC0010005 two seconds before the presenter clicks. The click correctly coalesces (FND-31), `alias()` hits `canonicalRunId == null` and returns (`InMemoryRunTraceRegistry.java:106-107`). The UI polls, gets 404 five times (`LT4_NOT_FOUND_GRACE_ATTEMPTS = 5` × 750 ms ≈ 3.75 s), stops, and shows **zero rows under the caption `▶ Live run — each step appears the moment that call returns`** for the whole 37–77 s ADK run. |
| 2 | `alias()` can bind a fresh waiter to a **finished** previous run's collector | [`InMemoryRunTraceRegistry.java:105-113`](../../../../src/main/java/com/company/triage/orchestration/trace/InMemoryRunTraceRegistry.java#L105) | MEDIUM | Manual run R1 at T0 registers and completes (`markDone()`). At T+90 s the poller starts its own run of the same incident. The presenter clicks again with fresh R2 → coalesces → `alias(R2)` resolves to **R1's finished collector**. The first poll returns R1's old steps *with `done:true`*, the client renders them under the live caption and stops (`index.html` `startLt4Poll`) — a previous run's trace presented as the live one. This is the FND-8 honesty class, corrected only minutes later when the POST settles. |
| 3 | Millisecond register race between two browser callers | [`DiagnosisOrchestrator.java:181`](../../../../src/main/java/com/company/triage/orchestration/DiagnosisOrchestrator.java#L181) | MEDIUM (same finding) | B's `inFlight.putIfAbsent` can observe A's future before A's `runOnce` reaches `register()` (line 237), so B's `alias()` no-ops for the same reason as #1. Narrow window, identical symptom. |
| 4 | *(unverified — tail)* Registry TTL is a hardcoded 5 min with no link to `timeout-ms` | [`InMemoryRunTraceRegistry.java:54`](../../../../src/main/java/com/company/triage/orchestration/trace/InMemoryRunTraceRegistry.java#L54), [`application.yml:119`](../../../../src/main/resources/application.yml#L119) | LOW | A run that emits no steps can legitimately stay silent for a full `timeout-ms`. If TTL ever falls below that window, `lookup()`'s own sweep (line 154) evicts the buffer it is about to read, the poll 404s with `everSawData=true`, and the UI treats it as the documented terminal signal and goes dark **while the run is still in flight**. |

**Reachability, stated honestly** (the verifier's narrowing, kept because it changes how much
this is worth): `triage.trigger.poll.enabled` is `false` in the shipped
`application.yml:75`, so findings #1 and #2 need the operator to enable K1 polling. That is
the documented FND-14/FND-31 mixed-trigger shape and the unattended/real-connector mode —
in scope, but not the out-of-box click-only demo. Finding #3 needs no config at all.

**Correction to finding #4's arithmetic — do not repeat it.** The finding computes a
worst-case silent window of `2 × timeout-ms + writeback ≈ 290 s` against the 300 s TTL and
concludes the margin is 10 seconds. It is not. `TraceCollector.abandonAndStartFallback`
stamps the synthetic `FALLBACK_STARTED` boundary row with `System.currentTimeMillis()`
(`TraceCollector.java:140-142`), and `lastWriteEpochMs` takes the max over all steps
(`InMemoryRunTraceRegistry.java:139-147`) — so the degrade **refreshes** the last-write
clock. The real worst-case gap since last write is *one* timeout plus the writeback tail
(2 × `addWorkNote`, bounded by `connect-timeout: 5s` + `read-timeout: 20s` each,
`application.yml:24-25`) ≈ **170 s against a 300 s TTL — a ~130 s margin, and eviction
mid-run needs `timeout-ms ≳ 250 s`, not the finding's ~137 s.** The *structural* point
survives intact and is what this card acts on: the two constants live in different files
with no cross-reference, and `timeout-ms` has already been retuned once (90 s → 120 s,
FND-69). The deliverable is the link, not the number.

## Design

### RTR-1 — Every run has a `runId` and a registered buffer, regardless of trigger

**The rule.** `DiagnosisOrchestrator.run(incident, runId)` mints a server-side `runId` when
the caller supplied none, and always calls `runTraceRegistry.register(...)`. LT4 rule 4
("no header ⇒ no buffer") is **retired**.

**Mechanism.** In `run(...)`, before `inFlight.putIfAbsent`:
`String effectiveRunId = hasText(runId) ? runId : "srv-" + UUID.randomUUID();`
then `runTraceRegistry.register(effectiveRunId, incidentNumber)` and pass the resulting
collector down into `runOnce`. The client-minted id stays authoritative when present, so the
`X-Triage-Run-Id` contract and every existing `DiagnosisControllerRunIdTest` assertion are
untouched.

**What it rejects, and why.** Rule 4's stated premise is memory growth — J11: *"K1 runs
unattended, indefinitely, with nobody to poll it"*, echoed in
`RunTraceRegistry.java:15-17` and `InMemoryRunTraceRegistry.java:37-42`. **TASK-010 removed
that premise**: the map is capped at `MAX_RETAINED_RUNS = 20` with a TTL swept on every
`register`, and a K1 run registering *is* a `register` call, so each unattended tick sweeps
the map it grows. The bound survives; only the class javadoc's *argument* for it
(«K1 never calls either method») becomes false and must be rewritten in the same change —
otherwise the class documents a premise its code no longer has.

The rejected alternative is the finding's own fallback: *document in J11 that K1-initiated
runs have no live feed*. Rejected because it concedes the live trace — J11's stated
differentiator — in precisely the most impressive demo shape (poller finds the incident,
presenter clicks, one coalesced diagnosis, one write-back). Paying ~20 bounded map entries
to keep that is not a close call.

### RTR-2 — Alias binds runId → runId via the in-flight map; `currentRunIdByIncident` is deleted

**The rule.** A waiter is aliased to **the `runId` of the run it is actually waiting on**,
which the orchestrator knows exactly. The registry stops guessing from incident history.

**Mechanism.**
1. `inFlight` becomes `ConcurrentHashMap<String, InFlightRun>` where
   `record InFlightRun(CompletableFuture<DiagnosisResult> future, String runId) {}`.
2. Ordering inside `run(...)` is **register → publish → putIfAbsent**: register the
   collector under `effectiveRunId` first, *then* `putIfAbsent(incident, new
   InFlightRun(mine, effectiveRunId))`. Any caller that can see the future is therefore
   guaranteed the canonical collector already exists — which is what closes evidence row #3
   (the race is not narrowed, it is eliminated by ordering).
3. The coalesce branch calls a new `RunTraceRegistry.aliasTo(waiterRunId, canonicalRunId)`
   using `existing.runId()`. No incident number is involved.
4. `alias(String runId, String incidentNumber)` and the `currentRunIdByIncident` map are
   **removed** — with (3) in place they have no caller.

A caller that loses `putIfAbsent` has already registered a collector it will never write to.
That is harmless: `aliasTo` overwrites its own entry with the canonical collector
(`collectors.put(runId, …)` already replaces), and a lost entry would age out on TTL anyway.

**What it rejects, and why.** The alternative was to keep the incident index and clear it on
completion (the registry can already see `TraceCollector`'s `done` flag). Rejected as the
*primary* mechanism because it treats a symptom: the index would still be a second,
independently-maintained answer to "which run is live", and every future trigger added to
the app (a webhook, a retry, a re-run button) is another chance for the two answers to
diverge. `inFlight` is already the single authority on what is running — the orchestrator's
FND-31 coalescing depends on it being exactly that. Delete the duplicate.

### RTR-3 — A terminal buffer is never aliasable (defence in depth)

**The rule.** `aliasTo` refuses to alias to a collector whose `done` flag is set, and
returns `false`.

Under RTR-2 a `done` canonical run cannot be in `inFlight`, so this branch should be
unreachable — it is a guard against a future caller reintroducing the class, not a fix for a
live path. When it fires, the waiter simply has no live buffer: its poll 404s through the
grace budget and `renderLt4Final` fills the card from the POST's `data.steps`, which is the
honest outcome (there was nothing live to show).

**Client-side twin.** If a run's **first** poll response arrives already `done:true` while
the POST has not resolved, `startLt4Poll` renders it under the settled/replay caption rather
than `LT4_FRAME_TEXT`. Five lines of JS, and it is the same idiom J11's honesty contract
imposes everywhere else: never let the UI assert "live" over data that is not.

> **⏸ DEFERRED — the client-side twin is NOT built.** The server half of RTR-3 shipped
> (`aliasTo` refuses a `done` collector and returns `false`, pinned by
> `InMemoryRunTraceRegistryTest#aliasToARunThatIsAlreadyDoneIsRefused`). The five lines of
> JS in `startLt4Poll` were **not** written, because `src/main/resources/static/index.html`
> was owned by a concurrent worktree with uncommitted changes at implementation time and
> editing it would have destroyed that work. This is a scheduling deferral, not a design
> change: the twin is still wanted exactly as specified above.
>
> **Consequence while deferred**: the server guarantees a waiter is never *bound* to a
> terminal buffer, so the failure this closes cannot arise from aliasing. The uncovered
> residue is the narrower case where a run's own first poll response arrives already
> `done:true` — the UI still captions that as live for one frame. Harmless today (the POST
> resolves immediately after), and the reason this half was safe to split.
>
> **To finish**: apply the `startLt4Poll` change described in this section once `index.html`
> is free, and delete this note.

### RTR-4 — TTL derives from the run's own wall-clock bound

**The rule.** `TTL = max(5 min, 2 × triage.orchestrator.timeout-ms + 60 s)`, computed from
injected `TriageProperties` rather than a hardcoded `Duration.ofMinutes(5)`.

At today's `timeout-ms: 120000` this evaluates to 5 min — **the shipped behaviour does not
change**, which is the point: the change is a link, not a retune. The `2 ×` covers the
ADK-primary-then-deterministic-fallback shape even though RTR-4's own analysis above shows
the boundary row refreshes the clock between them; the extra headroom is free and the
factor is the one an implementer would reach for anyway.

**What it rejects, and why.** The tail item's alternative — *bump `lastWrite` on every
`lookup()`* — is rejected. It re-defines TTL as "time since last client *interest*" rather
than "time since last write", which lets a browser tab left open on a finished run pin its
entry indefinitely; combined with the oldest-first cap eviction, that pinned entry would
push out a *real* live run's buffer. A registry bound the client can influence is worse than
one that is 130 s too generous.

### RTR-5 — The mixed-trigger shape is a tested shape, not a documented hope

Today's tests cover browser-owner + browser-waiter (`DiagnosisOrchestratorRunIdTest`,
`CoalescedRunSharesLiveTraceBufferTest`) and the no-op branch itself
(`InMemoryRunTraceRegistryTest#aliasWithNoCanonicalRegisteredIsANoOp` pins the *current*
behaviour and will need re-pointing). Nothing covers **headerless owner + runId-bearing
waiter** — the exact shape FND-31 exists for. That gap is why three review passes found this
and no test did.

## Verification

- **`CoalescedRunSharesLiveTraceBufferTest`** (extend): new case
  `waiterOnAHeaderlessOwnerReadsTheOwnersLiveSegment` — caller A runs with **no** runId
  (the K1 shape), caller B coalesces with a fresh runId, and `registry.peek(bRunId)` returns
  the *same* `TraceCollector` instance A is writing into, asserted **mid-run** (the existing
  test's latch idiom) so object identity cannot be trivially true via a shared result.
- **`DiagnosisOrchestratorRunIdTest`** (extend): `headerlessRunNeverTouchesTheRegistry` and
  `twoArgOverloadWithNullRunIdBehavesIdenticallyToHeaderlessCall` **invert** under RTR-1 —
  rewrite them to assert a server-minted runId *is* registered, and add
  `serverMintedRunIdIsStableForTheLifetimeOfTheRun`. Both are behaviour changes to pinned
  tests; the rewrite is the deliverable, not collateral.
  *Shipped as* `headerlessRunRegistersAServerMintedRunId`,
  `twoArgOverloadWithNullRunIdAlsoMintsAServerRunId`,
  `blankRunIdIsTreatedAsAbsentAndServerMinted`,
  `serverMintedRunIdIsStableForTheLifetimeOfTheRun`. A third test inverted for the same
  reason and was **not** anticipated here: `coalescedCallerWithNoRunIdNeverCallsAlias` →
  `coalescedCallerWithNoRunIdIsAliasedUnderItsServerMintedRunId` (under RTR-1 there is no
  caller without a runId). `coalescedCallerRunIdIsAliasedToTheCanonicalIncident` was renamed
  to `...ToTheCanonicalRunId` and now asserts the runId → runId binding of RTR-2.
- **`InMemoryRunTraceRegistryTest`** (rework): `aliasWithNoCanonicalRegisteredIsANoOp`,
  `aliasPointsTheCoalescedRunIdAtTheCanonicalIncidentsCollector` and
  `aliasSweepsBeforeResolvingSoAStaleCanonicalRunIsANoOp` all key off the deleted
  incident index — re-express them against `aliasTo(runId, runId)`. Add
  `aliasToARunThatIsAlreadyDoneIsRefused` (RTR-3).
  *Shipped as* `aliasToPointsTheCoalescedRunIdAtTheCanonicalRunsCollector`,
  `aliasToAnUnregisteredCanonicalRunIdIsARefusedNoOp` (re-pointed, not deleted — it now also
  pins the `false` return that lets the orchestrator distinguish a bound waiter from an
  unbound one), `aliasToSweepsBeforeResolvingSoAStaleCanonicalRunIsARefusedNoOp`, and
  `aliasToARunThatIsAlreadyDoneIsRefused`.
- **New `RunTraceRegistryTtlContractTest`**: asserts `TTL >= 2 × props.orchestrator().timeoutMs()
  + 60_000` for the **shipped** `application.yml` value, so a future `timeout-ms` raise fails
  a test instead of silently arming a mid-run eviction. This is the whole point of RTR-4.
  *Shipped as specified*, reading the committed YAML directly (multi-document, so the base
  value is resolved across profile blocks) and asserting **through the Spring-wired
  constructor** rather than the static helper, so rewiring the bean to stop reading
  `TriageProperties` also fails. `atTodaysShippedTimeoutTheDerivedTtlIsUnchangedFromTheConstantItReplaced`
  is the companion that pins RTR-4 as a *link, not a retune*.
- **`IncidentPollerTest`**: assert a poller-triggered run leaves exactly one registry entry
  and that N ticks never exceed `MAX_RETAINED_RUNS` — the bound RTR-1 now leans on directly.
  **Deviation, deliberate**: this landed as a new `UnattendedRunBufferBoundTest` instead.
  `IncidentPollerTest`'s orchestrator is a `CountingOrchestrator` stub that *overrides*
  `run(...)` and therefore never reaches a registry at all — asserting entry counts there
  would have asserted nothing. The new test drives the same single-argument `run(String)`
  overload K1 calls, against a real orchestrator and a real registry.
- No test here requires `-Padk`: every case is engine-agnostic and runs under bare
  `mvn -B test`. The `-Padk` suite must stay green regardless (baseline **152 default / 201
  adk**, both green) since `DiagnosisOrchestrator`'s SPI is shared.

## Out of scope

- **How steps get from the server to the browser** (poll shape, `since` indexing, position
  stability, 404 grace) → **J12-live-trace-delivery**. This card only decides *which buffer*
  a `runId` resolves to and *how long* it lives.
- **Whether the UI's live caption is honest about what it is showing** in the general case →
  **J23-live-ui-honesty**. RTR-3's client-side twin is the narrow slice this card owns.
- **Poller completion/idempotency semantics** (completed-set, cursor advance, in-flight
  bookkeeping) → **J17-poller-completion-semantics**.
- **Discovering a K1 run's `runId` without having clicked** (e.g. `GET
  /api/runs?incident=…`, so an unattended run can be watched from a cold browser). RTR-1
  makes it *possible* for the first time, but nothing here requires it; it belongs with
  J12's delivery surface if it is ever wanted.
- **Retuning `timeout-ms` itself** — J1 still owns that open item. RTR-4 deliberately keeps
  today's effective TTL unchanged.
