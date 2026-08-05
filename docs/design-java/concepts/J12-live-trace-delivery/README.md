# J12 — Live Trace Delivery (convergent, identity-keyed)

**State**: 🟢 Built (LTD-1/LTD-2, 2026-08-05) — the `since` cursor is replaced by convergent
full re-read + client upsert on `(attempt, callId)`, so a row now resolves IN PLACE during
the run. Verified three ways (unit test, live run, node harness on the real upsert logic).
**LTD-3/LTD-4 (seq ownership under parallel ADK dispatch) are NOT done** — convergent
delivery makes the swallowed-row race unreachable through the transport, but the
collector-side ordering question stands · **Complexity**: Moderate · **Priority**: HIGH
**Depends on**: J7 (UI), J8 (guardrail edges), J11 (LT1 spine, LT4 transport, LT5 states)
**Amends**: J11 (LT4's `since` cursor binding; LT1's `seq` ownership; the LT4 caption's timing clause)
**Source**: application review 2026-08-05 (multi-agent + Codex + Gemini), 5 confirmed findings

## Essence

**What the client shows after any poll must equal what the buffer holds — not what the
buffer *appended* since last time.** J11 built the trace as a set of *mutable rows keyed by
`callId`* and then shipped a transport that can only deliver *new positions*. Those two
models are incompatible, and the gap is not an edge case: on the live ADK path it swallows
every single `ACTIVE → DONE` resolution. J12 replaces the positional cursor with a
convergent, identity-keyed delivery: full re-read, upsert by `(attempt, callId)`, and a
`seq` that is assigned where insertion actually happens.

## Why this is a concept, not five bug fixes

The five findings are one broken seam seen from five angles, and fixing them separately
produces contradictory code:

- Patching the server to re-deliver mutated rows (a change-version cursor) while the client
  still appends blindly gives you **duplicate rows** instead of stale ones — strictly worse
  on stage.
- Patching only the client (upsert by `callId`) fixes resolution but leaves the swallowed-row
  race (LTD-3) intact, because that race is about *ordering inside the buffer*, not delivery.
- Fixing the `seq` race by pinning ADK to sequential dispatch (LTD-4) *appears* to make LTD-3
  moot — but the collector must stay concurrency-safe regardless, because J11/LT1 invariant 3
  and the un-killable FND-15 orphan thread both guarantee a second writer that ADK's config
  has no say over.
- And the "ADK may run several calls from one `Event` in parallel" comment
  ([`AdkDiagnosisEngine.java:258`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L258))
  is the *justification* cited for the `callId` correlation key in J11/LT4 — if that comment
  is false, someone will eventually "simplify" the key back to a counter and re-open every
  one of these.

So: **one decision about what a row's identity is, and who owns its order.** Everything else
follows.

## Evidence — what the review found

| # | What | Where | Severity | Failure |
|---|---|---|---|---|
| F1 | The `since` cursor indexes a *flattened position*; a `before`/`after` pair occupies **one** position, so a resolution never creates a new index and is never re-delivered. The client advances `since += newCount` and only ever appends. | [`RunStepsController.java:94`](../../../../src/main/java/com/company/triage/api/RunStepsController.java#L94), [`index.html:877-884`](../../../../src/main/resources/static/index.html#L877) | **HIGH** | Demo D1: steps land **8.0 s ± 2.6** apart (J11's own LT4 measurement) against a 750 ms poll, so essentially every row is captured `ACTIVE` and stays glow-pulsing for the rest of the 37–93 s run. J11's essence — "resolves in place to its result — then the next row begins" — never happens live. |
| F2 | The same mechanism means an already-delivered row can never receive its **`ABANDONED` re-tag** on an FND-7 mid-run degrade — the re-tag mutates rows in place at consumed positions. | [`TraceCollector.java:126-137`](../../../../src/main/java/com/company/triage/orchestration/trace/TraceCollector.java#L126) | MEDIUM | The poll loop's own comment ([`index.html:822-831`](../../../../src/main/resources/static/index.html#L822)) promises the struck-through treatment "arrives through this same poll loop". Only the synthetic `FALLBACK_STARTED` row is a new position, so the abandoned agent rows keep pulsing `ACTIVE` *beside* the fallback boundary — exactly the engine-boundary blur LT1's `engine` field exists to prevent. |
| F3 | `seq` is taken by `stepSeq.getAndIncrement()` **before** the row is recorded ([`:338`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L338), [`:524`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L524)), while `steps()` sorts each segment by `seq` ([`TraceCollector.java:180`](../../../../src/main/java/com/company/triage/orchestration/trace/TraceCollector.java#L180)). A late-recorded lower-`seq` row therefore **inserts** rather than appends. | [`AdkDiagnosisEngine.java:338`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L338) | LOW | Two concurrent `before` edges: A takes seq 5, is preempted, B takes seq 6 and records; a poll delivers B at position *p*; A's record then shifts B to *p+1*. Next poll re-sends B (**duplicate row** — `appendTraceRows` has no dedupe) and A is **never** delivered (position ≤ `since`). Microsecond window; silent and unexplainable on stage. |
| F4 | The endpoint's javadoc rests the whole `since` contract on positions being "stable and strictly growing… only a newly-seen `callId` ever extends the list". F3 shows a newly-seen `callId` can *insert*. | [`RunStepsController.java:38`](../../../../src/main/java/com/company/triage/api/RunStepsController.java#L38) | LOW | The premise the transport is documented on is false, so the next person reasoning from it reasons wrongly. |
| F5 | Internally inconsistent concurrency hedging. `activeCalls` / `activeModelCalls` / `deniedCallIds` are `ConcurrentHashMap`s justified by "ADK may run several calls from one `Event` in parallel", while the *same* callbacks mutate a plain `ArrayList trace` and a `long[] lastNs`, and `TriageMateTools.CURRENT_INCIDENT` is a **`ThreadLocal`** that assumes caller-thread dispatch. Both cannot be right. | [`AdkDiagnosisEngine.java:258`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L258), [`TriageMateTools.java:51`](../../../../src/main/adk/java/com/company/triage/agent/TriageMateTools.java#L51) | LOW *(verifier corrected down from MEDIUM)* | **Verifier's corrected claim**, from decompiled `google-adk-1.7.0`: `RunConfig.builder()` defaults `toolExecutionMode=NONE` and non-streaming, and only `PARALLEL_SUBSCRIBE` — *not* plain `PARALLEL` — resolves a `Schedulers.io()`/executor. With the config built at [`:682`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L682) everything is sequential on one thread today, so **the line-258 comment is false for this configuration** and the hedging is dead weight. Not a reachable defect — a latent hazard: flipping that one builder switch would make `CURRENT_INCIDENT.get()` return `null` inside every tool body (FND-33's incident pinning gone) with nothing flagging it. |

Two claims the review made that **did not survive my own read**, and are excluded:

- "100 % of rows stay `ACTIVE`" — the verifier already narrowed this and it is right to:
  `DENIED` rows arrive terminal from the `before` edge, the `FALLBACK_STARTED` boundary is
  written `DONE`, and any step shorter than one poll gap arrives resolved. The dominant case,
  not the only case.
- The suggested "set `toolExecutionMode` to `SEQUENTIAL`" assumes an enum constant nobody in
  this review named. The decompile only established `NONE` (default), `PARALLEL`,
  `PARALLEL_SUBSCRIBE`. LTD-4 therefore specifies a `javap` check before writing the line.

## Design

### LTD-1 — The client re-reads the whole buffer every tick

**Rule**: the LT4 poller sends `since=-1` on **every** poll and treats each response as the
complete current state of the run, not as a delta.

**Mechanism**: delete the `since += newCount` bookkeeping at
[`index.html:884`](../../../../src/main/resources/static/index.html#L884). The server-side
`since` parameter, its default, and `RunStepsControllerTest`'s cursor cases stay exactly as
they are — this is a *client* binding change, so the endpoint contract and its five existing
cursor tests are untouched.

**Why this and not a server-side change-version cursor.** Both work. The discriminator is
cost against this app's actual shape: the buffer is capped at ~20 small rows (J11's LT4
bound), so a full re-read at 750 ms is a few kilobytes — and "inherently correct — re-read
the buffer" was **literally the argument J11 used to choose polling over SSE** (LT4's
transport table). A monotonic change-version stamped by `TraceCollector` on every `record()`
is the more scalable answer, and is the right one if this ever streams something large; here
it adds a field, a comparison, and a new class of off-by-one to the one component that must
not have one, to save bandwidth nobody is paying for.

**Rejected**: keeping the cursor and having the server re-send rows whose state changed. That
needs the same per-row version stamp *plus* a client that can still tell an update from an
append — i.e. LTD-2 anyway, at strictly higher cost.

### LTD-2 — A row's identity is `(attempt, callId)`, and delivery is an upsert

**Rule**: `appendTraceRows` becomes `upsertTraceRows`. For each step in a response: if a DOM
row with that `(attempt, callId)` exists, update it **in place**; otherwise append.

**Mechanism**: `traceRowHtml`
([`index.html:584`](../../../../src/main/resources/static/index.html#L584)) gains
`data-attempt` and `data-call-id` on the row root — the transport already carries both on
every `TraceStep`, they are simply dropped at render. The in-place update writes exactly
three things:

1. `data-state` on the row root (LT5's CSS keys off it — `PENDING→queued, ACTIVE→active,
   DONE→done, FAILED→fail, DENIED→warn, ABANDONED→abandoned`, already implemented in
   `stepStateToDataState`);
2. `.trace-label` text, via the existing `unresolved ? label : result` rule at
   [`index.html:687-690`](../../../../src/main/resources/static/index.html#L687);
3. `.trace-meta` text from `stepMetaText`.

⚠️ **Update the attribute and the text nodes — do not re-write the row's `innerHTML`.**
LT5's motion lives on the 32 px badge (`box-shadow` glow) and a sweeping rail. Replacing the
badge element restarts its CSS animation, so a wholesale re-render would make every unresolved
row visibly re-flash on every poll — a 750 ms strobe. The whole point of `data-state` being an
*attribute* flip is that the resolve is a transition, not a repaint.

`shell.countEl` keeps deriving from `rowsEl.children.length`, which now counts distinct rows
rather than delivered rows — which is also the F3 over-count, fixed for free.

**Why `(attempt, callId)` and not `callId` alone**: segments restart `seq` at 0 per attempt
and a degraded run legitimately holds two segments; `callId` is only unique *within* an
attempt (the deterministic path mints `det-<seq>`, so attempt 0 and attempt 1 of the same run
would collide on `det-0`).

### LTD-3 — `seq` is assigned by the collector at first insert, not by the engine at emit

**Rule**: `TraceCollector.record()` owns `seq`. On the first insert of a `(attempt, callId)`
it assigns the next value for that segment, under the existing `abandonLock`; a **replacement**
row for a `callId` already present **inherits the stored row's `seq`**. Insertion order and
`seq` order then match by construction, and `steps()`'s sort becomes a formality rather than a
load-bearing assumption.

**Mechanism**: extend `AttemptSink.stamp()`
([`TraceCollector.java:194`](../../../../src/main/java/com/company/triage/orchestration/trace/TraceCollector.java#L194)),
which **already** overrides the engine-supplied `attempt` for exactly this reason: *"engines
have no notion of retry state; only the orchestrator does."* The same argument extends
verbatim to display order — engines have no notion of the interleaving of concurrent
callbacks; only the collector, which serialises them, does. `AdkDiagnosisEngine`'s `stepSeq`
stays for its `ActiveCall` bookkeeping and log lines; its value simply stops being
authoritative.

**Rejected**: taking `abandonLock` around `getAndIncrement()` + `sink.before()` in the engine
(the finding's suggestion). It works, but it exports the collector's lock into ADK callback
code, has to be repeated at all four emit sites
([`:323`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L323),
[`:338`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L338),
[`:474`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L474),
[`:524`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L524)),
and leaves the deterministic engine's `det-<seq>` on a different rule. One owner is cheaper
than four disciplined callers.

⚠️ This changes `TraceStep.seq`'s documented meaning
([`TraceStep.java:14-17`](../../../../src/main/java/com/company/triage/orchestration/trace/TraceStep.java#L14))
from "index within this engine call's segment" to "insertion index assigned by the collector".
The javadoc and any `TraceCollectorTest` assertion that constructs steps with hand-picked
`seq` values must move with it — that is the real cost of this sub-decision and it is small.

### LTD-4 — Single-threaded ADK dispatch is a declared invariant, not an assumption

**Rule**: the run configuration explicitly pins sequential tool dispatch, and the two things
that depend on it are named at the pin site.

**Mechanism**: at
[`AdkDiagnosisEngine.java:682`](../../../../src/main/adk/java/com/company/triage/agent/AdkDiagnosisEngine.java#L682),
set the tool-execution mode explicitly instead of inheriting the default, with a comment
naming its dependents: **`TriageMateTools.CURRENT_INCIDENT`** (a `ThreadLocal` — FND-33's
incident pinning, which returns `null` off the caller thread) and the unsynchronised
`List<String> trace` / `long[] lastNs` closed over by all six callbacks. Correct the
line-258 comment to say what is actually true: *the `callId` key is required because a
`before` and its `after` must correlate across a bounded loop, and because ADK's config
permits parallel dispatch even though this run forbids it* — the key stays, its stated
reason stops being false.

🔬 **One-look check before writing the line**: `javap` the `RunConfig.ToolExecutionMode` enum
in the bundled `google-adk-1.7.0.jar` and pin the constant that actually denotes sequential
dispatch. The decompile behind F5 established `NONE` (the current default), `PARALLEL`, and
`PARALLEL_SUBSCRIBE`; it did **not** establish a `SEQUENTIAL` constant, and this card will not
assert one. If `NONE` is the sequential mode, pin `NONE` explicitly — an explicit default is
still a declaration.

**Keep the `ConcurrentHashMap`s.** They are not dead weight even under a pinned sequential
mode: J11/LT1 invariant 3 requires the sink to be thread-safe regardless, and the FND-15
timed-out primary thread **is not killed** — it keeps writing while the fallback runs. The
inconsistency F5 identified is real, but it resolves by *correcting the comment*, not by
removing the defence.

**Rejected — making the run robust to parallel dispatch** (the finding's option (b)):
replacing the `ThreadLocal` with a per-run tools instance or an `InvocationContext`-scoped
binding is a redesign of `TriageMateTools` and of FND-33's guarantee, to support a mode no
code path in this repo sets, for a demo that runs once. Pinning the invariant costs one line
and one test; supporting the mode costs a refactor of the security-relevant incident pin.

### LTD-5 — The live caption describes when rows *arrive*, not only when they *return*

**Rule**: `LT4_FRAME_TEXT` must state both edges of a row's life, because after LTD-1/LTD-2
the UI finally has both.

Today it reads *"each step appears the moment that call returns"*
([`index.html:781-782`](../../../../src/main/resources/static/index.html#L781)). That is
false about the shipped mechanism in the opposite direction from everything else on this card:
rows appear at the `before` edge, i.e. the moment the call **starts**. Replace the timing
clause with one that matches: *each step appears when it starts and resolves in place when it
finishes*. The three load-bearing live words — *live*, *nothing here is paced or replayed*,
*real* — stay verbatim; only the clause that describes this transport changes.

This is inside J12 rather than J23 because it is a statement **about this delivery
mechanism**; J23 owns the settle-render's tense and the provenance chips.

### LTD-6 — The settle render must become a no-op, and that is testable

**Rule**: after this card, `renderLt4Final` is still the authoritative render (J11's
additive-transport correction requires the POST's `data.engine` and `data.writebackPosted` to
win), but it must no longer *change what is on screen*. Its own comment today
([`index.html:899-907`](../../../../src/main/resources/static/index.html#L899)) describes it
as healing staleness "the poll loop could not see" — that framing is retired; the poll loop
now sees everything.

**Mechanism**: keep the one-shot rebuild (it is cheap and it is the additive-transport
guarantee), and prove convergence where it is actually testable — at the data layer, server
side: for a completed run, the flattened rows of a `since=-1` response must equal
`TraceCollector.steps()` element-for-element, which is precisely the list `DiagnosisResult`
carries. If those two agree, the rebuild cannot alter the DOM.

## Verification

- `RunStepsControllerTest` — new: **`aFullRereadOfACompletedRunEqualsTheCollectorSteps()`**,
  flattening `buildResponse(collector, -1).attempts()` and asserting element equality with
  `collector.steps()` (LTD-6). Existing cursor tests
  (`sinceMinusOneReturnsEverythingFromTheStart`, `aSecondPollWithAHigherSinceReturnsOnlyNewSteps`,
  `aDegradedRunProducesTwoAttemptsWithAttempt0AbandonedAndAttempt1Live`, …) must stay green
  **unchanged** — LTD-1 touches the client, not the endpoint. Any of them going red means the
  change leaked into the server.
- `TraceCollectorTest` — new: **`aReplacementInheritsTheStoredRowsSeq()`** and
  **`seqFollowsInsertionOrderWhenWritersInterleave()`** (LTD-3). The second drives two threads
  through `record()` with deliberately inverted engine-supplied `seq` values and asserts
  `steps()` order matches record order. Existing assertions that construct steps with
  hand-picked `seq` need auditing against LTD-3's new meaning.
- `TraceCollectorTest` — extend the degrade cases: after `abandonAndStartFallback`, a
  `since=-1` response must carry the attempt-0 rows **as `ABANDONED`** (F2), which no current
  test asserts *through the endpoint*.
- **Under `-Padk`**: `AdkRunConfigTest` (new) — assert the `RunConfig` the engine builds
  carries the pinned sequential tool-execution mode (LTD-4). This is the test that fails when
  someone "improves" the run by flipping the switch, which is the entire point.
- **Under `-Padk`**: the existing model-edge safety tests
  (`AdkBeforeModelCallbackSafetyTest`) must stay green — LTD-3 changes who assigns `seq`, and
  must not touch the `Optional.empty()` guarantee on the `before` edges.
- Client-side (LTD-1/LTD-2/LTD-5) has no test harness in this project. Verify by the
  `e2e/` deterministic-degrade path plus one live ADK rehearsal, watching for the two
  observable signatures: **no row remains `active` after its successor appears**, and **the
  final settle render produces no visible change**.
- Baseline to hold: **152 default / 201 `-Padk`**, both green. LTD-3's javadoc/meaning change
  is the only place existing assertions are expected to move.

## Out of scope

- **Registry lifecycle** — TTL, cap, eviction, and the `runId` aliasing that leaves a
  coalesced poller-owned run watching an empty buffer: **J16-run-trace-registry-lifecycle**.
  J12 assumes it is handed a live `TraceCollector`; who registers it and for how long is J16's.
- **Poller/`done` completion semantics** — when `markDone()` fires and what a client should
  conclude from `done:true`: **J17-poller-completion-semantics**.
- **The rest of the live UI's honesty surface** — the settle render keeping a present-tense
  "Live run" caption after the run has ended, missing LT7 provenance chips during the live
  window, and the engine chip claiming "deterministic · offline" for a degraded run that used
  the network: **J23-live-ui-honesty**. J12 changes exactly one caption clause, the one that
  describes this transport.
- **Guardrail enforcement completeness** on the `before` edges (`DENIED` rows arrive terminal
  and are unaffected by anything here): **J18-guardrail-enforcement-completeness**.
