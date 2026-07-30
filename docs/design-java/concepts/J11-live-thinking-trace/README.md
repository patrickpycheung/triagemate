# J11 — Live Thinking Trace (animated per-step agent trace)

**State**: 🔴 Exploring (CDS Round 1, 2026-07-31) · **Complexity**: Complex ·
**Depends on**: J1, J2, J4, J7, J8 · **Amends**: J7 (UI), J8 (observability)
**Source**: DDS `docs/discovery/live-thinking-trace-ui/` (Phase 4, concepts LT1–LT7)

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
record TraceStep(int seq, Platform platform, String tool, String label, String result,
                 State state, long startedAtEpochMs, Long durationMs,
                 DiagnosisResult.Engine engine) {}
enum Platform { SERVICENOW, CONFLUENCE, SUMO, GITLAB, TRIAGEMATE }
enum State    { PENDING, ACTIVE, DONE, FAILED, DENIED }
```

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
`(incident, sink) -> …`. 🔬 **Spiked and proven** — compiles clean, **34/34 and 50/50 still
pass**, zero assertion changes. Accepted deliberately: 18 mechanical edits buys un-droppable
step emission. *(The DDS claimed "16 across 2 files" from a grep; the compiler found a third
file. See `verification-lt1-spi/findings.md`.)*

**Three invariants that are easy to get wrong:**
1. **One sink per engine call, never one per run.** The orchestrator **discards the primary
   engine's whole result and trace** on an FND-7 degrade. A run-scoped sink would accumulate
   the *abandoned* ADK steps plus the deterministic run with `seq` restarting at 0 — and a
   timed-out virtual thread is **not killed** (FND-15 cancellation is best-effort), so the
   orphan can keep emitting *after* the fallback starts. Discard the primary's steps exactly
   as its trace is discarded; prepend one synthetic `FALLBACK_STARTED` boundary step.
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
only under `-Padk`. Move the canonical tool-name set into `StepCatalog` in `src/main/java/`,
have `AdkDiagnosisEngine` reference it, and assert the subset in a `src/test/` test so bare
`mvn test` enforces it.

### LT3 — Replay renderer (v1; works on **both** engines)

Reveal completed steps at a perceptible cadence (~250–400 ms floor — Nielsen's ~100 ms
threshold means a 19 ms flash reads as a *glitch*; Buell & Norton's "labor illusion" finds
instant answers to hard-looking work *reduce* trust), each row showing its **real** duration,
with log-scale duration bars so 2 ms and 40 s share one visual language.

Frame wording must carry four load-bearing words — *replaying*, *completed*, *nothing is
running now*, *real*:

> `↺ Replaying a completed run — 8 steps in 19 ms total. Nothing is running now; steps are
> revealed at 0.4 s each so they're readable. The order and the timings below are real.`

**Pre-render only the unconditional step prefix, and only when `engine != ADK`** — the flat
`LlmAgent` has no knowable fixed prefix, and `gitlab.searchCode` is conditional even on the
deterministic path, so pre-rendering it would assert work that may never happen.

**Ruled out**: unlabelled synthetic dwell + spinners + present tense (that is FND-8 in a
costume); fabricated reasoning copy; ETA bars; `Step N of 8` on the bounded ADK loop.

### LT4 — Live streaming (**mandatory**, ADK path)

Not optional polish: `DEMO-RUNBOOK.md` **locks D1 as the primary path**, so replay-only would
leave the screen blank for the whole live model run (up to the 90 s `timeout-ms`).

Wire **all three** verified ADK 1.7.0 edges — `beforeToolCallbackSync` → `ACTIVE`,
`afterToolCallbackSync(…, Object result)` → `DONE`, `onToolErrorCallbackSync` → `FAILED`
(`onToolError` is required; `after` is **not** a `finally` hook). Correlate edges with
**`ToolContext.functionCallId()`**, not a counter — ADK may run several calls from one
`Event` in parallel.

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
a 90 s engine deadline**; `SseEmitter.send()` is not thread-safe under concurrent emission
(and ADK callbacks arrive on RxJava threads); and emitting after `future.cancel(true)` on an
FND-15 timeout throws. Each is fixable, none is free, and a hackathon demo should not be
paying for a durability property it will never use.

**Watch item (reopens this):** if the poll interval visibly lags the agent — steps appearing
in bursts rather than as they happen — drop the interval to ~500 ms before reconsidering SSE.

Binding on the chosen option:
- **Streaming must be purely ADDITIVE.** Keep `POST /api/diagnose/{incidentNumber}` → `200
  DiagnosisResult`; `index.html` reads `data.engine` (FND-16) and `data.writebackPosted`
  (FND-25) from it. End every stream with the complete result (or a typed terminal payload
  carrying those fields).
- **Key by `runId`, not incident.** Sequential runs of one incident are deliberately
  separate — `DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced`
  proves it — so an incident-keyed buffer leaks events across runs. Map incident → *current*
  `runId` only for the FND-31 concurrent-coalescing case.
- **`spring.mvc.async.request-timeout` has no Spring Boot default** → falls to embedded
  Tomcat's **30 s**, silently conflicting with the 90 s engine deadline. Set it explicitly.

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
`<path fill="currentColor">` so J7's `--sn/--cf/--sl/--gl` vars tint them; uniform 32 px
square badges; attribution footer present. GitLab + Confluence from simple-icons;
**ServiceNow + Sumo Logic from vectorlogo.zone**, because simple-icons has no ServiceNow
entry at all and ships Sumo Logic only as a wordmark that renders as a smudge at badge size.
⚠️ `sumologic.svg` keeps a non-zero viewBox origin (`22.84 23.58 64 64`) — "tidying" it to
`0 0 64 64` crops the mark.

### LT7 — Provenance chips (separable)

Putting `4 ms` beside `servicenow.getIncident(INC0012345)` makes an *existing* ambiguity
acute: that call hit a fixture and nothing on screen says so.

⛔ **Two chips, never one.** A single `connectors: mock (no network)` label would be
untruthful twice over — connector selection is **per-connector and mixable** (FND-10), and
"no network" is **false on the ADK path** even with all connectors mock (the Copilot proxy).
Render a connector chip (`servicenow=real, others=fixtures`) *and* an engine/backend chip
(`deterministic · offline` vs `ADK · via Copilot proxy`). Scope the no-network claim to
*connector* network.

## Verification
- 🔬 **Spike LT1-SPI ✅ DONE** (`verification-lt1-spi/findings.md`) — the 2-arg SPI migration
  compiles and keeps **34/34** + **50/50** green; blast radius measured by the compiler at
  **18 sites / 3 files**, purely mechanical, zero assertion changes. Source reverted (the
  spike stubbed the sink, so keeping it would have left dead API surface).
  Two method lessons recorded: enumerate a SAM change's breakage **with the compiler, not a
  regex** (grep missed a whole file), and always `clean` — a warm `test-compile` reported
  0 errors from stale classes.
- 🔬 **ADK callback edges ✅ DONE** (DDS `verification-adk-callbacks.md`, three independent
  confirmations incl. two `javap` runs): `before`/`after`/`onToolError` all exist;
  `AfterToolCallbackSync` carries the real result; `ToolContext.functionCallId()` is the
  correlation key.
- 🔬 **LT5 visual vocabulary ✅ rendered** — `sketch.html` verified in headless Chromium
  (states progress, disclosure toggles work); LT6's four real logos render legibly.
- ⏳ Pending: LT2 catalog subset test, LT3 replay-frame wording review, LT4 transport choice
  + its `spring.mvc.async.request-timeout` fix, real ADK latency, projector legibility.

## Open / risks
- **Transport fork (LT4)**: polling vs SSE — ADM-2, decide in Round 2/3.
- **Real ADK per-step latency** — unmeasured (`timeout-ms: 90000` is self-documented as a
  guess). Needs the corp laptop + proxy; decides how much LT4 actually matters.
- **Projector legibility** of glow-pulse vs spinner — reasoned, not measured. One dry run.
- **Reveal cadence**: fixed floor vs proportional-with-floor. Cheap to tune live.
