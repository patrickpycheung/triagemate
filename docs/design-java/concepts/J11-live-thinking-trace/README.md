# J11 — Live Thinking Trace (animated per-step agent trace)

**State**: 🟢 Built (STREAM-001–006, 2026-08-02 — verification sweep TASK-017 confirmed
`mvn test` 140/140 and `mvn -Padk test` 183/183 green, the additive-only transport
guarantee holds with no `X-Triage-Run-Id` header, and the deterministic replay path
renders correctly) · **Complexity**: Complex ·
`TraceStep`/`TraceSink`/`StepCatalog` and the LT1–LT7 design below are all implemented in
`src/`. J1–J11 are now all 🟢 Built.
**Depends on**: J1, J2, J4, J7, J8 · **Amends**: J1 (response shape), J7 (UI), J8 (observability)
**Source**: DDS `docs/discovery/live-thinking-trace-ui/` (Phase 4, concepts LT1–LT7)

**Amended by** (reciprocal — added 2026-08-06): [J12](../J12-live-trace-delivery/README.md) (LTD: the `since` cursor replaced by convergent re-read), [J16](../J16-run-trace-registry-lifecycle/README.md) (RTR: LT4 rules 4 and 5, and the buffer bound), [J23](../J23-live-ui-honesty/README.md) (LUH: the honesty contract made state-owned).
*This line exists because 33 `Amends` references in this workspace are one-directional: a card
records what it amends, the amended card never learns of it. That is how LT4 rule 4 stayed
stated-as-live here while J16 retired it and J17/J21 went on citing it.*

## Essence
Show the copilot's *process*, not a spinner. A per-step trace where each row carries the
**logo of the platform it touched**, animates while that step is in progress, and
**resolves in place** to its result — then the next row begins. The step log accumulates
(it is never collapsed away) because the evidence trail *is* the demo's differentiator (D4).

This is J8's "flight recorder" made legible per step, rendered through J7's UI.

## Why this is a data-model concept, not an animation concept

Two measurements from the DDS killed the intuitive "stream it over SSE and animate" design
as a *foundation*:

- **F-2 — the deterministic engine finishes in 2–19 ms** (measured). Nothing can stream,
  poll, or paint inside that window. And it is **D2, the demo's guaranteed fallback** — so
  an ADK-only design leaves the safety-net path blank exactly when the presenter is
  recovering from a failure.
- **F-3 — `connectors=real` makes the *same* engine genuinely slow.** "Instant" is a
  **config** property, not an engine property, so no *fixed* reveal pace is correct.

What survives both: **capture real per-step durations as data, then render honestly.**

## The honesty contract (load-bearing — read before changing any of this)

FND-8, FND-16 and FND-25 were all the same defect: **the UI asserting something that did not
happen**. Each was fixed identically — *add a real field, read the real field*. J11 must obey
that idiom rather than re-open it. Concretely:

- ✅ Claim **"the order shown is the order it happened"**, sourced per run from `seq`.
- ⛔ Do **NOT** claim a canonical flow shared by both engines. The ADK engine is a flat
  `LlmAgent` where the **model** chooses tool order (J2/FND-13) and ADK may execute several
  calls from one `Event` in parallel. (An earlier draft of the DDS asserted "both engines run
  the same ordered flow", reading `DeterministicDiagnosisEngine`'s javadoc — a statement of
  *its own* intent — as a contract ADK honours. It is not.)
- The **reveal cadence** is the only display property, and the frame must say so.
- Never parse trace strings to recover structure — **FND-16 was exactly that bug, in exactly
  this UI file**, and its resolution ("string-matching a trace line is not a contract") is
  quoted in `DiagnosisResult`'s javadoc today.

## Design

### LT1 — `TraceStep` + `TraceSink` (the spine; everything else renders it)

New package `com.company.triage.orchestration.trace` in `src/main/java/` (so `src/main/adk/`
may import it; the reverse stays forbidden).

```java
record TraceStep(int seq, int attempt, String callId,          // callId: correlation key
                 Platform platform, String tool, String label, String result,
                 StepState state, long startedAtEpochMs, Long durationMs,
                 DiagnosisResult.Engine engine) {}
enum Platform  { SERVICENOW, CONFLUENCE, SUMO, GITLAB, TRIAGEMATE }
enum StepState { PENDING, ACTIVE, DONE, FAILED, DENIED }
```

**`callId` and `attempt` were added in Round 3** — doc-test found the Round-2 shape could not
actually support its own transport (see the three fixes below). `callId` is
`ToolContext.functionCallId()` on the ADK path and a synthetic `det-<seq>` on the
deterministic path; `attempt` is 0 for the primary engine call and 1 for an FND-7 fallback.
Naming: the enum is **`StepState`**, not `State` — `State` collides confusingly and the LT1
spike already created `StepState`.

**A step is a mutable row, not an append.** `before` emits the row `ACTIVE`; `after`/`onError`
**replace the row with the same `callId`**, they do not append a second one. Without a
correlation key the UI could not know which `ACTIVE` row a later `DONE` resolves — the
Round-2 shape had this hole.

- `label` (in-progress verb) and `result` are **separate fields** so the resolve animation
  has both at once.
- `durationMs` is what makes a paced reveal honest: a 2 ms step can *say* 2 ms.
- `engine` gives **attempt identity** — without it a live view can show ADK steps beside a
  deterministic final report with no boundary (the FND-16 problem again).
- `DiagnosisResult` gains a 5th component `List<TraceStep> steps`; **`List<String> trace`
  stays byte-identical**, not re-derived (11 formats carry arg detail; ~10 test assertions
  match their prefixes).

**Emission**: `DiagnosisEngine` gains `diagnose(String, TraceSink)` as the *implemented*
method; `diagnose(String)` becomes a `default` passing `TraceSink.NOOP`. Engines implement
the 2-arg form so none can silently drop steps.

⚠️ **This is a source-breaking SPI change** — `DiagnosisEngine` is a genuine SAM, so every
1-arg lambda breaks: **18 sites across 3 files** (14 `DiagnosisOrchestratorTest`,
2 `IncidentPollerTest`, 2 `PromptInjectionGuardrailTest`) must become
`(incident, sink) -> …`. 🔬 **Spiked and proven** — compiles clean, the whole suite still
passes (34/34 + 50/50 *at spike time*, 2026-07-31; the suite has since grown to 43/43 + 59/59
via unrelated `found-issues-resolve` fixes — this spike's own edits stayed at zero assertion
changes regardless of the ambient count), zero assertion changes from the spike itself.
Accepted deliberately: 18 mechanical edits buys un-droppable step emission. *(The DDS claimed
"16 across 2 files" from a grep; the compiler found a third file. See
`verification-lt1-spi/findings.md`.)*

**Three invariants that are easy to get wrong:**
1. **One sink per engine call — and on degrade the primary's segment is FROZEN, not deleted.**
   ⚠️ **Round-3 correction (3 independent reviews).** Round 2 said "discard the primary's
   steps exactly as its trace is discarded". **That is unachievable once LT4 streams live**:
   by the time the fallback starts, the ADK steps are already in the buffer *and already
   painted on screen*, so a server-side discard would require the client to un-draw rows —
   and silently deleting them would itself breach the honesty contract. Worse, the timed-out
   virtual thread is **not killed** (FND-15 cancellation is best-effort), so the orphan keeps
   writing.
   **Resolution — segment per attempt.** Key the buffer `runId#attempt`. On degrade: freeze
   attempt 0, mark it **ABANDONED** and keep its rows visible but visually struck through;
   append a `FALLBACK_STARTED` boundary; open attempt 1 with a fresh sink and `seq` restarting
   at 0 *within that segment*. Late writes from the orphaned attempt-0 thread land in a dead
   segment and are ignored — which turns the un-killable orphan from a corruption risk into a
   no-op. This is *more* honest than discarding: the audience sees the agent tried, failed,
   and fell back, which is exactly the D2 story the runbook tells.
2. **All three `DiagnosisResult` reconstruction sites must carry `steps` forward** — the
   orchestrator rebuilds the record three times (for `engine`, then `writebackPosted`); a
   back-compat constructor at any of them silently drops the steps.
3. **`TraceSink` must be thread-safe** — on the ADK path it is invoked from ADK/RxJava
   callback threads, *not* the virtual thread the orchestrator submits to.

### LT2 — `StepCatalog` (platform + in-progress label)

The in-progress verbs ("Searching Confluence for a runbook…") **exist nowhere today** — only
result strings do. One catalog keyed by *both* ADK snake_case tool names (`search_confluence`)
and deterministic dotted keys (`confluence.search`) → `{platform, label}`. Non-platform steps
(`understand:`, `contacts:`, `report assembled:`) map to the `TRIAGEMATE` pseudo-platform.

⚠️ The intended "build-failing test that `ALLOWED_TOOLS ⊆ catalog`" **cannot fire as
described**: `ALLOWED_TOOLS` is `private static final` in `src/main/adk/`, which compiles
only under `-Padk`, so bare `mvn test` would never run the check.

⛔ **But do NOT fix it by moving the tool-name set into `StepCatalog`** (Round-2 said to;
Round-3 review rejected it). That would make an **observability/UI component the source of
truth for which tools are permitted** — inverting J8's security ownership and violating
J11's own "compose, don't conflate" rule. A catalog entry must never be able to *grant*
permission.
**Resolution**: put the canonical permitted-tool set in a **neutral security-owned registry**
in `src/main/java/` (J8's layer, e.g. `guardrails/ToolRegistry`), have *both*
`AdkDiagnosisEngine.ALLOWED_TOOLS` and `StepCatalog` reference it, and assert
`registry ⊆ catalog` in a `src/test/` test. The catalog must *cover* the permitted set; it
must not *define* it.

**Model-think rows bypass the catalog entirely (Round 6).** LT4's model edges produce rows
with **no tool at all**, so there is no key to look up: they carry `tool = null`,
`platform = TRIAGEMATE`, and the fixed label `Thinking…`. Worth stating explicitly because
the catalog is otherwise keyed by tool name and a `null` key would otherwise look like a bug.

⚠️ **`TRIAGEMATE` stops being an edge case on the ADK path.** It was introduced for the two
or three `understand:` / `contacts:` steps. With model edges the measured 3-call run is
**3 platform rows and 4 model rows — 57 % `TRIAGEMATE`**. Two consequences:
- **It needs a visual treatment, and LT6 does not give it one** (LT6 ships four *vendor*
  marks; the pseudo-platform has no badge).
- **Decision (Round 6): render model-think rows as visually subordinate connective rows** —
  no badge, dimmer, slightly indented — not as peer rows with equal weight. Rationale is the
  honesty contract, not aesthetics: badging them as peers would make the trace read as seven
  equal steps when **only three touched a real system**, overstating the evidence trail the
  step log exists to prove. Subordinate rows say "the agent thought here" without implying a
  source was consulted. *Watch item: if the connective rows read as clutter on a projector,
  collapse consecutive ones rather than re-badging them.*

⚠️ **The catalog cannot label a hallucinated tool** — and that is the main `DENIED` case.
`BoundsCallback` denies names *outside* the allowlist, which are by construction absent from
the catalog. Specify a fallback: unknown tool → `Platform.TRIAGEMATE`, label
`"Blocked an unrecognised tool call"`, and carry `BoundsCallback.denialReason()` into
`result` so allowlist-rejection and budget-exhaustion stay distinguishable (today they are,
in the text trace; the new UI must not lose that).

### LT3 — Replay renderer (v1; works on **both** engines)

Reveal completed steps at a perceptible cadence (~250–400 ms floor — Nielsen's ~100 ms
threshold means a 19 ms flash reads as a *glitch*; Buell & Norton's "labor illusion" finds
instant answers to hard-looking work *reduce* trust), each row showing its **real** duration
as text in the meta line. No per-row progress/duration bar (tried in an earlier version,
removed per direct product feedback 2026-08-03): step count isn't always known ahead of a
run, so a bar reads as a progress indicator it can't honestly be — the text duration already
carries the same information without that implication.

Frame wording must carry four load-bearing words — *replaying*, *completed*, *nothing is
running now*, *real*:

> `↺ Replaying a completed run — 8 steps in 19 ms total. Nothing is running now; steps are
> revealed at 0.4 s each so they're readable. The order and the timings below are real.`

**Pre-render only the unconditional step prefix, and only when `engine != ADK`** — the flat
`LlmAgent` has no knowable fixed prefix, and `gitlab.searchCode` is conditional even on the
deterministic path, so pre-rendering it would assert work that may never happen.

**Ruled out**: unlabelled synthetic dwell + spinners + present tense (that is FND-8 in a
costume); fabricated reasoning copy; ETA bars; `Step N of 8` on the bounded ADK loop.

**The pacing floor is a REPLAY concern only (clarified Round 5).** An earlier note said the
LT4 measurement "settled the reveal cadence — reveal each step as it lands", which read as a
blanket rule and contradicted the ~250–400 ms floor above. Both are right, for different
paths, and the distinction is the point:

| | real step duration | cadence |
|---|---|---|
| **Replay** (LT3; deterministic, 2–19 ms) | far below perception | **pace it** — `max(realDuration, ~0.4 s)` |
| **Live** (LT4; ADK, measured 8.0 s ± 2.6) | far above perception | **no pacing at all** — render on arrival |

So the open "fixed floor vs proportional-with-floor" fork resolves as
**proportional-with-floor, and only in replay**: reveal step N after
`max(realDuration, floor)`. That single rule covers both engines *and* the awkward middle
case neither branch named — a **deterministic run against real connectors** (F-3), whose
steps are genuinely slow, where a fixed 0.4 s floor would understate real work and a pure
proportional rule would flash the fast steps. Live streaming never paces because it cannot:
the step arrives when it arrives, and the 8 s gap *is* the cadence.

### LT4 — Live streaming (**mandatory**, ADK path)

Not optional polish, and no longer a judgement call — **measured 2026-08-01**
(`verification-lt4-latency/`): a 3-tool-call run takes **37 s**, an 8-call run ~77 s. `DEMO-RUNBOOK.md`
**locks D1 as the primary path**, so replay-only leaves the screen blank for that entire
window (bounded by the 120 s `timeout-ms` — FND-69, itself corrected by the same spike).

Wire **all six** verified ADK 1.7.0 edges — three tool, three model.

**Tool edges**: `beforeToolCallbackSync` → `ACTIVE`, `afterToolCallbackSync(…, Object result)`
→ `DONE`, `onToolErrorCallbackSync` → `FAILED` (`onToolError` is required; `after` is **not**
a `finally` hook). Correlate with **`ToolContext.functionCallId()`**, not a counter — ADK may
run several calls from one `Event` in parallel.

**Model edges — added Round 5, and they are the majority of the timeline.**
⚠️ The latency spike made a hole in this very section countable: **~75 % of the run is the
model thinking, and the three tool edges emit nothing during any of it** (measured: ~28 s of
LLM time vs ~10 s of tool execution, per run). Two dead windows — between tools
(`after(N)`→`before(N+1)`), and, worst, **after the last tool while the model composes the
final JSON: 13.1 s ± 1.3, consistently the single longest gap in every run.** Left unwired,
the live view resolves every step to `DONE` and then **freezes for 13 s at the exact moment
the audience is waiting for the answer** — the blank screen LT4 exists to prevent, surviving
inside LT4's own design. Invisible from the code; only 4 LLM calls against 3 tool callbacks
in the proxy log made it countable.

`beforeModelCallbackSync` → open a `TRIAGEMATE` row `ACTIVE`; `afterModelCallbackSync` →
resolve `DONE` with its real duration; `onModelErrorCallbackSync` → `FAILED` (which is also
how a proxy/model failure becomes *visible* rather than the run just stopping). Correlate on
**`CallbackContext.eventId()`** — the model-side analogue of `functionCallId()`.
🔬 All three verified present by `javap` against the 1.7.0 jar
(`verification-lt4-model-edges/findings.md`).

⛔ **`beforeModelCallback` has the same short-circuit hazard as `beforeTool`, and worse
consequences.** Returning a non-empty `Optional<LlmResponse>` **replaces the model's
response** — an observer that returned anything but `Optional.empty()` would make the UI show
words the model never produced, a direct FND-8 breach with no `DENIED` row to betray it.
**A trace observer on these edges MUST return `Optional.empty()` unconditionally.**

**Label the row retrospectively, not predictively.** "Composing the diagnosis" vs "deciding
what to check next" is only knowable *after* the fact (did a tool call follow?). So: label
every model window **`Thinking…`** while `ACTIVE`, and on resolve set `result` to what it
actually produced — *"chose search_confluence"* / *"produced the report"*. Truthful at every
instant without predicting the future — the same discipline the honesty contract imposes
everywhere else on this card. Free side-effect: an FND-42 repair retry is a model call with
no tool call, so it too becomes visible instead of silent.

⛔ **The `before` edge must keep returning a non-empty `Optional` to deny.** That is how
`BoundsCallback` enforces J8's allowlist + budget. A trace observer that forced it to return
empty would **silently disable the guardrail**. Compose, don't conflate: keep the denial
untouched and emit `DENIED` from that same decision (which also settles when `DENIED` is set
— on the `before` edge, since a denial short-circuits execution).

**Transport — ✅ DECIDED Round 2 (ADM-2): poll a per-`runId` buffer. Not SSE.**

Both were viable; the discriminating criterion turned out to be **what this app is for**.
The operator confirmed (2026-07-31, re: logos) that this is an **internal throwaway demo** —
which dissolves SSE's main advantage, since "better long-term end state" has no long term
here. What remains is stage risk, and there polling wins decisively:

| | Poll a `runId` buffer ✅ | SSE + `Last-Event-ID` |
|---|---|---|
| Failure modes on stage | client retries next tick | 3 new ones (below) |
| Coalescing / reload / mid-run joiner | inherently correct — re-read the buffer | needs replay protocol |
| Endpoints | 1 | 1–2 + emitter lifecycle |
| Cost of its weakness | ~1 s latency, irrelevant against a 10–60 s run | a dead stream mid-demo |

SSE's three extra failure modes are all live on the demo path: **`spring.mvc.async.request-timeout`
has no Boot default → embedded Tomcat's 30 s, which would tear the stream down mid-run against
a 120 s engine deadline** — and the LT4 measurement turns that from a hazard into a
certainty: **every** real run measured (33–40 s, and ~77 s projected for the full 8-tool
flow) exceeds Tomcat's 30 s, so an unconfigured SSE stream would be severed mid-run on the
demo path, every time; `SseEmitter.send()` is not thread-safe under concurrent emission
(and ADK callbacks arrive on RxJava threads); and emitting after `future.cancel(true)` on an
FND-15 timeout throws. Each is fixable, none is free, and a hackathon demo should not be
paying for a durability property it will never use.

~~**Watch item (reopens this):** if the poll interval visibly lags the agent…~~ ✅ **CLOSED by
the LT4 measurement (Round 5).** Real steps land **8.0 s ± 2.6** apart, so a 1 s poll adds at
most ~12 % to a step's time-to-appear and cannot produce the "bursts" this watch item feared
— that failure mode needs steps faster than the poll, which is the *deterministic* path, and
that path replays from a completed run rather than polling. Polling is not a compromise here;
it is comfortably the right tool. The 500 ms fallback stays available but is not expected.

Binding on the chosen option:
#### The `runId` protocol (added Round 3 — **3 independent reviews found it missing**)

Round 2 decided "poll a per-`runId` buffer" without ever defining what a `runId` *is*. It
appeared in four lines of this card and **nowhere in the repo** — no type, no generator, no
endpoint, and critically **no channel by which a client learns it before the run ends**. The
only channel today is the POST, which returns at the *end*. The whole live path dead-ended.

**Resolution — the client mints the id.** The one option needing no extra "start" round-trip
and no change to the POST response:

1. Client generates a `runId` (UUID) **before** calling, sending it as an optional
   `X-Triage-Run-Id` header on `POST /api/diagnose/{incidentNumber}`.
2. Client immediately polls `GET /api/runs/{runId}/steps?since={seq}` (~500 ms), returning
   `{attempts: [{attempt, state, steps: [...]}], done: bool}`.
3. The POST still returns its usual `200 DiagnosisResult` at the end, unchanged. The client
   stops on `done` or when the POST resolves — whichever is first.
4. ~~**No header ⇒ no buffer.** Absent the header the orchestrator uses `TraceSink.NOOP` and
   allocates nothing — which is exactly what **K1 must do** (see the bound below).~~
   **RETIRED 2026-08-06 by [J16](../J16-run-trace-registry-lifecycle/README.md)/RTR-1**
   (`4ae6955`). `DiagnosisOrchestrator.run(...)` now mints a server-side `runId`
   (`"srv-" + UUID`) when the caller sent none and **always** registers a collector — so a
   K1-owned run does get a buffer.

   *Why the retirement was safe*: this rule's stated premise was memory growth ("K1 runs
   unattended, indefinitely, with nobody to poll it"). The **Bound the buffer** paragraph
   directly below removed that premise — the map is capped at ~20 with a TTL swept on every
   `register`, and a K1 tick registering *is* a `register` call, so each unattended tick
   sweeps the map it grows. The bound survives; only this rule's argument for itself did not.
   J16/RTR-2 additionally deleted `currentRunIdByIncident`, because with every run registered
   the orchestrator's own `inFlight` map is the single authority on what is live.

   > **The header stays `required = false`.** [J21](../J21-network-exposure-posture/README.md)
   > builds a CORS argument on that optionality, and it is unaffected: RTR-1 mints an id when
   > the header is absent rather than demanding one. J21 deliberately declined to weld its
   > trust boundary to `X-Triage-Run-Id`, warning that "the day someone makes tracing optional
   > again … the drive-by path silently re-opens". This *was* that day, and the separation held.
5. **FND-31 coalescing**: caller B blocks in `awaitExisting` and never runs an engine, so it
   owns no segment. Alias B's `runId` to the canonical run's buffer so it watches the same
   live steps instead of a permanently empty one.

**Bound the buffer** (J10 parity — that card holds itself to `completed-cap: 500`; this one
had no cap at all): cap retained runs (~20) with a TTL (~5 min), evicting oldest-first.
Otherwise K1 running unattended, with no client draining it, grows the map forever.

**Two corrections, binding regardless of transport:**

- **Streaming must be purely ADDITIVE.** Keep `POST /api/diagnose/{incidentNumber}` → `200
  DiagnosisResult`; `index.html` reads `data.engine` (FND-16) and `data.writebackPosted`
  (FND-25) from it. End every stream with the complete result (or a typed terminal payload
  carrying those fields).
- **Key by `runId`, not incident.** Sequential runs of one incident are deliberately
  separate — `DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced`
  proves it — so an incident-keyed buffer leaks events across runs. Map incident → *current*
  `runId` only for the FND-31 concurrent-coalescing case.
- ~~**`spring.mvc.async.request-timeout`**~~ — **withdrawn Round 3.** That property governs
  Spring MVC *async dispatch*, which ordinary polling does not use; it was a leftover from
  the rejected SSE branch. It becomes binding again only if SSE is ever reinstated.

### LT5 — Visual vocabulary (prototyped, render-verified)

`../../discovery/live-thinking-trace-ui/2-diverge/explorations/C-visual-design/sketch.html`
is a working artifact verified in headless Chromium. CSS-only, driven by a `data-state` flip:
motion lives on the **32 px badge** (box-shadow glow — area change survives projector
contrast loss, unlike a spinner) and a 2 px sweeping rail; **the text never moves**.
`prefers-reduced-motion` swaps all animation for a static ring, so state is never
motion-only. Reuses J7's existing `.ev` left-rail idiom.

⚠️ **`State` tokens must be mapped, not serialized** — the CSS keys off
`queued/active/done/warn/fail`, which do not match the enum. Map
`PENDING→queued, ACTIVE→active, DONE→done, FAILED→fail, DENIED→warn` and test all five.

### LT6 — Vendor logos ✅ **DONE** (operator ruling 2026-07-30/31)

All four real marks live in `src/main/resources/static/logos/`, normalised to a single
`<path fill="currentColor">` so a brand colour can tint them; uniform 32 px
square badges; attribution footer present. GitLab + Confluence from simple-icons;
**ServiceNow + Sumo Logic from vectorlogo.zone**, because simple-icons has no ServiceNow
entry at all and ships Sumo Logic only as a wordmark that renders as a smudge at badge size.
⚠️ `sumologic.svg` keeps a non-zero viewBox origin (`22.84 23.58 64 64`) — "tidying" it to
`0 0 64 64` crops the mark.

⚠️ **`--sn/--cf/--sl/--gl` do not exist yet in the shipped UI** (Round-3 correction — an
earlier draft, and `logos/README.md`, both claimed the logos are tinted by "J7's" vars).
`static/index.html` defines only `--bg --card --ink --muted --acc --ok --warn`; the four
brand vars live solely in the DDS prototype `sketch.html`. **J7 must gain them** as part of
landing LT5 — until then the marks would render in the inherited text colour.

### LT7 — Provenance chips (separable)

Putting `4 ms` beside `servicenow.getIncident(INC0010005)` makes an *existing* ambiguity
acute: that call hit a fixture and nothing on screen says so.

⛔ **Two chips, never one.** A single `connectors: mock (no network)` label would be
untruthful twice over — connector selection is **per-connector and mixable** (FND-10), and
"no network" is **false on the ADK path** even with all connectors mock (the Copilot proxy).
Render a connector chip (`servicenow=real, others=fixtures`) *and* an engine/backend chip
(`deterministic · offline` vs `ADK · via Copilot proxy`). Scope the no-network claim to
*connector* network.

**Response-contract note (FND-72, recorded 2026-08-05).** The connector chip is fed by a
**6th `DiagnosisResult` component**, `Map<String,String> connectors`
(`DiagnosisResult.java:48-54`, TASK-016) — additive and wire-compatible, like `steps`, but it
shipped without either this section or J1's contract section recording it. Both now do.
The value is normalised to lower case at the provider (FND-74): the bean wiring matches
`real` case-insensitively, so an un-normalised `Real` built the real gateway while this chip
rendered `fixtures` — the honesty contract inverted at exactly the moment it matters most.

## Verification
- 🔬 **Spike LT1-SPI ✅ DONE** (`verification-lt1-spi/findings.md`) — the 2-arg SPI migration
  compiled and kept the full suite green at spike time (2026-07-31: 34/34 + 50/50; current
  suite size is 43/43 + 59/59 per `STATUS.md`, grown since via unrelated
  `found-issues-resolve` fixes — cite `STATUS.md` for the live count, not this line); blast
  radius measured by the compiler at **18 sites / 3 files**, purely mechanical, zero
  assertion changes. Source reverted (the spike stubbed the sink, so keeping it would have
  left dead API surface).
  Two method lessons recorded: enumerate a SAM change's breakage **with the compiler, not a
  regex** (grep missed a whole file), and always `clean` — a warm `test-compile` reported
  0 errors from stale classes.
- 🔬 **ADK callback edges ✅ DONE** (DDS `verification-adk-callbacks.md`, three independent
  confirmations incl. two `javap` runs): `before`/`after`/`onToolError` all exist;
  `AfterToolCallbackSync` carries the real result; `ToolContext.functionCallId()` is the
  correlation key.
- 🔬 **LT5 visual vocabulary ✅ rendered** — `sketch.html` verified in headless Chromium
  (states progress, disclosure toggles work); LT6's four real logos render legibly.
- 🔬 **LT4 real ADK per-step latency ✅ DONE** (2026-08-01, `verification-lt4-latency/`):
  first end-to-end agentic run against a real Copilot-served model (real ServiceNow +
  Confluence). **3 runs, all successful and byte-identical: 3 tool calls → 37s mean;
  8.0s ± 2.6s per tool call; 13.1s for the final report.** ⇒ `N × 8.0 + 13.1`, so 8 calls
  ≈ 77s and a full 10-call run ≈ 93s ± 8. **This settles
  LT4: a three-call run already leaves the screen blank for 37 seconds, with a 13s stretch of
  nothing — live streaming is required, not a nice-to-have.** It also settles the reveal
  cadence below (real gaps dwarf any artificial floor) and exposed FND-69 (the old 90s timeout
  was shorter than a run its own tool budget permits).
- 🔬 **LT4 model-callback edges ✅ DONE** (2026-08-01, Round 5, `verification-lt4-model-edges/`):
  `javap` against the 1.7.0 jar confirms `beforeModelCallbackSync` /
  `afterModelCallbackSync` / `onModelErrorCallbackSync` all exist, mirroring the tool edges,
  with `CallbackContext.eventId()` as the correlation key. Triggered by the latency spike
  exposing that **~75% of the run is model-think time with zero events** under the
  three-tool-edge design. LT4 is now six edges. Same ⛔ short-circuit hazard as `beforeTool`,
  with worse consequences — see LT4.
- ⏳ Pending: LT2 registry/catalog subset test, LT3 replay-frame wording review,
  projector legibility. *(Transport is DECIDED — polling; the
  `spring.mvc.async.request-timeout` item was withdrawn as SSE-only.)*

## Open / risks
- ~~**Real ADK per-step latency**~~ ✅ **MEASURED 2026-08-01, 3 runs** — 8.0s ± 2.6s per tool
  call, 13.1s for the final report, 37s for a 3-call run. LT4 confirmed necessary;
  `timeout-ms` corrected 90s → 120s (FND-69). `verification-lt4-latency/findings.md`.
- ~~**Projector legibility**~~ ✅ **CLOSED 2026-08-02 — premise changed, operator call.**
  There is no physical projector: presentation output is a big screen driven directly off the
  corporate laptop, not a projected image. That removes the two effects the contrast-maths
  caveat was built on (photon loss, ambient-light wash) — a direct digital/HDMI feed doesn't
  degrade the low-alpha glow-pulse ring the way projection would, so the desk-measured
  contrast ratios are a much closer proxy for what the audience actually sees. Operator
  reviewed the desk analysis (`verification-lt5-projector/findings.md`) and closed the risk
  without a live run: the solid-accent signals (border/shimmer/spinner, 4.05–13.58:1) already
  carry the in-progress read regardless, so the outcome doesn't change whether the glow reads
  or not. `projector-test.html` stays in the repo — still the right 2-minute check on a real
  projector, if a venue ever changes.
  ⚠️ Kept from the desk analysis: `sketch.html` hardcodes the pulse as `rgba(90,169,255,…)` —
  the midnight blue — instead of `var(--acc)`. **Do not port that rule into J7 as-is.**
- ~~**Reveal cadence**: fixed floor vs proportional-with-floor~~ ✅ **RESOLVED Round 5** —
  **proportional-with-floor, replay only**: reveal step N after `max(realDuration, ~0.4 s)`;
  live never paces (the ~8 s gap *is* the cadence). One rule covering both engines and the
  slow-deterministic F-3 middle case. See LT3. *(An intermediate note said "reveal as it
  lands" globally, which contradicted LT3's floor — corrected here.)*
