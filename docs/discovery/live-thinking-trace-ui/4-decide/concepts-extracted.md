# Phase 4 — Decide: concepts extracted

## Decision (recommended; awaiting operator lock)

**Build the structured-step spine + a replay renderer (v1) now; add live streaming as an
ADK-only upgrade (v2) only if the demo runs D1 as primary. Default to house glyphs, not
real vendor logos.**

Applying the ADM method to the decision as a whole:

- **Neutral framing**: an additive UI feature plus a supporting data model in code we own.
- **Reversibility framing**: highly reversible — new package, one new record component, a
  new renderer; `List<String> trace` stays **byte-identical** so nothing existing breaks.
- **Charter framing**: does not touch mission, budget, or authority. Two sub-questions do
  touch value-laden ground — the honesty principle (resolved *by design*, see below) and
  third-party trademark use (a real legal surface → carved out as LT6).

Majority: **ADM-2** for the technical shape — several viable options *and* a real
discriminating test existed (the 2–19 ms measurement and the `javap` check both changed the
answer). Decided on evidence. The trademark sub-question is carved out rather than
self-approved, and a safe default that needs no ruling is shipped in the meantime.

**Why this shape and not the obvious one**: the intuitive build is "stream the agent's steps
over SSE and animate them." Two measurements killed that as a *foundation* — the default
engine finishes in 2–19 ms (nothing can stream in time), and `connectors=real` makes the
same engine slow (so no fixed pace is correct). What survives both is: **capture real
per-step durations as data, then render honestly.**

**The honesty principle is satisfied structurally, not by restraint.** Every pixel is
backed by a real field: real durations, real results, real platform attribution, and a real
per-run step order recorded in `seq`. The reveal cadence is the only display property, and
the frame says so explicitly.

> ⚠️ **Premise corrected (doc-test 2026-07-30, found independently twice).** An earlier
> version justified this with *"both engines run the same ordered flow"*. **That is false
> for the ADK path.** The built engine is a single **flat `LlmAgent`** whose *model* chooses
> tool order (J2/FND-13), and ADK may execute several calls from one `Event` **in
> parallel** — a fact recorded elsewhere in this very document. I had read the deterministic
> engine's javadoc (which describes running "the SAME ordered steps the ADK agent runs") as
> a guarantee about ADK's runtime behaviour; it is that engine's statement of its own
> intent, not a contract ADK honours.
>
> The honesty argument does **not** collapse — it needs a weaker, true claim. Assert only
> *"the order shown is the order it happened"*, sourced per-run from `seq`. Do **not** claim
> a canonical flow shared across engines. Consequence for LT3: a pre-rendered step prefix is
> legitimate **only when `engine != ADK`**, since only the deterministic script has a
> knowable fixed prefix.

---

## Concepts for CDS

Suggested home: **one new concept card `J11 — Live thinking trace`** owning LT1–LT3+LT5, with
cross-reference amendments to J7 (UI), J8 (observability), J1/J2 (emission points). LT6/LT7
are separable.

### LT1 — `TraceStep` model + `TraceSink` emission ⭐ (the spine — everything depends on it)

New package `com.company.triage.orchestration.trace` (in `src/main/java/`, so
`src/main/adk/` can import it — that direction is already established and the reverse stays
forbidden).

```java
record TraceStep(int seq, Platform platform, String tool, String label, String result,
                 State state, long startedAtEpochMs, Long durationMs,
                 DiagnosisResult.Engine engine) {}   // engine: attempt identity, see below
enum Platform { SERVICENOW, CONFLUENCE, SUMO, GITLAB, TRIAGEMATE }
enum State    { PENDING, ACTIVE, DONE, FAILED, DENIED }
```

⚠️ **State tokens must be mapped, not serialized directly** (doc-test 2026-07-30): LT5's
verified CSS keys off `data-state` values `queued` / `active` / `done` / `warn` / `fail`,
which do **not** match this enum (`PENDING`, `FAILED`, `DENIED`). Naming the attribute from
`State.name()` would silently select no style. Specify the mapping explicitly —
`PENDING→queued`, `ACTIVE→active`, `DONE→done`, `FAILED→fail`, `DENIED→warn` — and cover all
five in a test, or rename one side to match the other.

- `label` (in-progress verb) and `result` are **separate fields** so the resolve animation
  has both simultaneously.
- `durationMs` is the field that makes a paced reveal honest (P-2/P-3): a 2 ms step can
  *say* 2 ms.
- `DiagnosisResult` gains a 5th component `List<TraceStep> steps` + a third back-compat
  constructor. **`List<String> trace` is preserved byte-identical, not re-derived** — the
  11 existing formats carry argument detail and ~10 test assertions match their prefixes.
- Emission: `DiagnosisEngine` gains `diagnose(String, TraceSink)` as the **implemented**
  method; existing `diagnose(String)` becomes a `default` passing `TraceSink.NOOP`. That
  direction is deliberate — engines implement the 2-arg form, so no engine can silently
  drop steps.
- ⚠️ **Cost corrected twice: this IS a source-breaking SPI change, and it is bigger than
  grep said.** The original claim ("all 34/50 tests keep compiling with zero assertion
  changes") was wrong — `DiagnosisEngine` is a genuine **SAM**, so moving the abstract method
  to the 2-arg form breaks **every 1-arg lambda**. doc-test put that at "16, in 2 files" from
  a grep. 🔬 **Spike LT1-SPI (2026-07-31) measured it with the compiler: 18 sites across 3
  files** — 14 `DiagnosisOrchestratorTest`, **2 `IncidentPollerTest`** (missed by grep), 2
  `PromptInjectionGuardrailTest`.
  The missed pair are positional constructor args named `i`, not `incident`, and they are the
  **primary + FND-7 fallback pair** — i.e. the file most relevant to the "one sink per engine
  call" invariant. *Enumerate a SAM change with the compiler, never a regex.*
  The spike also confirmed the migration **compiles and keeps 34/34 + 50/50 green** with zero
  assertion changes. **Accept the migration**; 18 mechanical edits is a fair price for
  un-droppable step emission. Alternative (keep 1-arg abstract, add 2-arg as `default`)
  reverses the safety property — an engine could then silently drop steps.
  Full record: `docs/design-java/concepts/J11-live-thinking-trace/verification-lt1-spi/`.
- **`TraceSink` must be thread-safe** (Codex, P-10c): on the ADK path it is invoked from
  ADK/RxJava callback threads, *not* the single virtual thread the orchestrator submits to.
- ⚠️ **One sink per engine call, never one per run** (doc-test 2026-07-30, found twice
  independently). `DiagnosisOrchestrator` **discards the primary engine's whole
  `DiagnosisResult` and trace** on an FND-7 degrade and returns only the fallback's. A
  single run-scoped sink would therefore accumulate the *abandoned* ADK steps plus the
  deterministic run's, with `seq` restarting at 0 — and because a timed-out virtual thread
  is **not killed** (FND-15's javadoc says cancellation is best-effort), the orphaned run
  can keep emitting *after* the fallback has started. Mirror the existing semantics: fresh
  sink per engine call, discard the primary's steps exactly as its trace is discarded, and
  prepend one synthetic `FAILED`/`FALLBACK_STARTED` boundary step.
- ⚠️ **`TraceStep` needs engine/attempt identity.** Without it a live view can show ADK
  steps next to a deterministic final report with no visible boundary — the FND-16 problem
  again. Add the engine (or an attempt ordinal) to the step, and keep the terminal
  disclosure reading `DiagnosisResult.engine`.
- ⚠️ **All three `DiagnosisResult` reconstruction sites must carry `steps` forward.**
  `DiagnosisOrchestrator` rebuilds the record three times (to set `engine`, then
  `writebackPosted`); using a back-compat constructor at any of them would silently default
  or drop the captured steps. Test the ADK-success, degraded-fallback, and
  writeback-disabled paths explicitly.
- **Rejected**: `ApplicationEventPublisher` (needs correlation ids + subscriber registry);
  a `ThreadLocal` — the FND-33 precedent does *not* transfer (that exists only because
  ADK's tool methods are `static`; here the orchestrator runs engines on **virtual
  threads**, where a caller-installed ThreadLocal isn't visible anyway).
- **Required discipline**: the orchestrator's five `result.trace().add(...)` calls must go
  through the collector, or `steps` and `trace` drift — which is the FND-8/16/25 family
  recommitted. Worth a test asserting the two views agree.
- Blast radius (D's count): **7 files changed, 5 new, 0 existing test assertions changed.**

### LT2 — `StepCatalog`: platform + in-progress label mapping

The in-progress verb strings (*"Searching Confluence for a runbook…"*) **exist nowhere
today** — only result strings do. A shared catalog keyed by *both* ADK snake_case tool
names (`search_confluence`) and deterministic dotted keys (`confluence.search`), yielding
`{platform, label}`. Non-platform steps (`understand:`, `contacts:`, `report assembled:`)
map to the `TRIAGEMATE` pseudo-platform. Add a **build-failing test that
`ALLOWED_TOOLS ⊆ catalog`** so a new tool can't ship unlabelled.

⚠️ **That gate cannot fire as described** (doc-test 2026-07-30): `ALLOWED_TOOLS` is
`private static final` **inside `src/main/adk/`**, and both `src/main/adk` and `src/adk-test`
compile *only* under `-Padk` — so a plain `mvn test` would never run the check, and the
"build-failing" guarantee is illusory on the default build. Fix: move the canonical tool-name
set into `StepCatalog` in `src/main/java/`, have `AdkDiagnosisEngine.ALLOWED_TOOLS` reference
*that* as its single source of truth, and assert the subset relation in a `src/test/` test so
bare `mvn test` enforces it.

### LT3 — Replay renderer with an honest frame ⭐ (v1; works on both engines)

Reveal completed steps at a perceptible cadence (~250–400 ms floor; Nielsen ~100 ms
threshold + Gemini's 250 ms minimum + B's 0.4 s converge here), each row showing its **real**
duration. Header must carry the four load-bearing words — *replaying*, *completed*,
*nothing is running now*, *real*:

> `↺ Replaying a completed run — 8 steps in 19 ms total. Nothing is running now; steps are
> revealed at 0.4 s each so they're readable. The order and the timings below are real.`

Plus log-scale duration bars (makes 2 ms vs 40 s legible in one visual language).
**Per P-9, pre-render only the unconditional step prefix — and only when `engine != ADK`**
(doc-test correction: the flat `LlmAgent` has no knowable fixed prefix, since the model picks
order; see the premise correction under the Decision above); append conditional steps
(`gitlab.searchCode`) as they actually fire.

**Ruled out** (B): unlabelled synthetic dwell + spinners + present tense (that is FND-8 in
a costume); fabricated reasoning copy for an engine that doesn't reason; ETA bars;
`Step N of 8` on the bounded ADK loop; any hardcoded "instant" copy.

### LT4 — Live step streaming, ADK-only (v2; **MANDATORY — D1 is the locked primary path**)

**MANDATORY, not conditional** (corrected 2026-07-30, doc-test): `DEMO-RUNBOOK.md` already
locks **D1 as the primary demo path**, so v1's replay-only renderer would leave the screen
blank for the entire live model run (up to the 90 s `timeout-ms`). LT4 is therefore in scope
for the ADK path, or the runbook must state that the presenter narrates the wait. The
earlier "only if D1 is primary" framing posed a question the runbook had already answered.

Wire ADK's three verified callback edges (P-5) — **all three, since `onToolError` is
required for the failure edge and `afterToolCallback` is NOT a `finally` hook**:
`beforeToolCallbackSync` → `ACTIVE`, `afterToolCallbackSync(…, Object result)` → `DONE`,
`onToolErrorCallbackSync` → `FAILED`. Join the edges with
**`ToolContext.functionCallId()`** (not a counter — ADK may execute several calls from one
`Event` in parallel).

> ⛔ **CRITICAL CORRECTION (doc-test 2026-07-30).** An earlier version of this section said
> *"All three **must** return `Optional.empty()`"*. **Implementing that would silently
> disable J8's entire guardrail leash.** `beforeToolCallback` is not only an observation
> point — it is the **policy owner**: `BoundsCallback` denies an out-of-allowlist or
> over-budget call precisely **by returning a non-empty `Optional`**
> (`AdkDiagnosisEngine.java:160-170`). Forcing it to return empty would make the allowlist
> and the call budget stop denying anything at all.
>
> The correct rule: **observation must not alter the result, but policy must stay
> unaltered.** So *compose*, don't conflate — keep `BoundsCallback`'s non-empty denial
> exactly as-is and emit the `DENIED` step from that same decision; a trace-only observer
> added on the `after`/`onError` edges returns `Optional.empty()` so it cannot rewrite a
> tool result. This also **closes spike Q1**: the `DENIED` terminal state is set on the
> `before` edge, because a denial short-circuits execution and the tool never runs.

**Transport is an OPEN fork (P-10d), deliberately not pre-resolved:**

| | A — poll a buffer | Codex — SSE + `Last-Event-ID` replay |
|---|---|---|
| Shape | client re-reads full buffer each tick | `GET /{runId}/events` |
| Race/reload/joiners | inherently correct | solved via monotonic `id` + retained short-TTL log |
| New surface | 1 endpoint | 1–2 endpoints + emitter lifecycle |
| Risks | polling latency, chattier | 30 s async-timeout trap, proxy buffering, concurrent `send()` |

Lean: **polling if v2 is built under time pressure; SSE if it gets proper time** (better end
state, and Codex's replay design answers A's main structural objection). Gemini's WebFlux
advice does not apply — this is the servlet stack.

**Two corrections from doc-test, both binding on either option:**

1. ⛔ **Keep the existing HTTP contract; streaming must be purely ADDITIVE.** The "`POST →
   202 + runId`" shape would change `POST /api/diagnose/{incidentNumber}` from its
   documented `200 DiagnosisResult`, which `index.html` consumes directly for
   `data.report`, `data.engine` (FND-16) and `data.writebackPosted` (FND-25). Breaking it
   would re-open two findings this repo already paid to close. So: leave the synchronous
   route and all four fields untouched, add the streaming paths alongside, and **end every
   stream with the complete `DiagnosisResult`** (or a typed terminal payload carrying
   `engine` + `writebackPosted`) so the disclosure fields survive the streaming path.
2. ⚠️ **Key the run/stream by `runId`, NOT by incident number.** (This resolves a direct
   *disagreement* between the two conflict perspectives; Codex is correct and there is a
   test proving it.) Sequential diagnoses of the same incident are **deliberately separate
   runs** — `DiagnosisOrchestratorTest#sequentialRunsOfTheSameIncidentAreNotCoalesced`
   asserts exactly that — so an incident-keyed buffer would leak or overwrite a prior run's
   events. Map incident → *current* `runId` only for the FND-31 concurrent-coalescing case,
   where two callers genuinely share one engine call and should therefore share one stream.

**Two traps that must be handled either way:**
- `spring.mvc.async.request-timeout` has **no Boot default** → embedded Tomcat's **30 s**,
  which silently conflicts with the 90 s engine deadline. Set `request-timeout: 3m` (or a
  per-emitter `new SseEmitter(180_000L)`).
- **The `TraceSink` must be thread-safe** — ADK callbacks may run on ADK/RxJava threads,
  not the orchestrator's virtual thread. Feeds back into LT1.

### LT5 — Visual vocabulary (already prototyped and verified)

C's `2-diverge/explorations/C-visual-design/sketch.html` is a working, headless-Chromium-verified
artifact (zero console errors; `queued → active → done` progresses; disclosure toggles work).
CSS-only, driven by a `data-state` attribute flip:

- Motion on the **32 px badge** (box-shadow glow pulse — area change survives projector
  contrast loss, unlike a spinner) and a 2 px sweeping rail. **The text never moves** —
  animated glyphs are the worst thing at distance.
- `queued` 32 % opacity / `active` pulse+rail / `done` 220 ms settle + `--ok` check /
  `warn`+`fail` coloured left border, no motion.
- `prefers-reduced-motion` swaps all animation for a static ring — state is never
  conveyed by motion alone.
- Reuses the existing `.ev` left-rail idiom so it looks native.

### LT6 — Real vendor logos ✅ **OPERATOR RULED 2026-07-30: use real vendor logos**

**Status: RULED and IMPLEMENTED** (as far as assets allow). This supersedes the
house-glyph default recommended below, which is retained for the record.

Implemented: real marks staged in `src/main/resources/static/logos/` (+ a provenance
`README.md`), swapped into C's `sketch.html` through the single `PLAT` table, and
**verified by headless-Chromium render** — not just asserted:

**All four are real vendor logos** (ruling extended 2026-07-31: *"use any logo you want, no
restrictions"* — internal throwaway demo). Uniform **32 px square** badges, each normalised
to one `<path fill="currentColor">` so the existing `--sn`/`--cf`/`--sl`/`--gl` vars tint it.

| Platform | Asset | Source | Render verdict at badge size |
|---|---|---|---|
| **GitLab** | square glyph | simple-icons | ✅ clearest — tanuki unmistakable |
| **ServiceNow** | square icon (loop mark) | **vectorlogo.zone** | ✅ clear green loop |
| **Confluence** | square glyph | simple-icons | ✅ clear |
| **Sumo Logic** | square **icon** | **vectorlogo.zone** | ✅ clear — see below |

**The sourcing problem was real and outlived the permission question.** "No restrictions"
did not by itself produce assets: `simple-icons` has **no ServiceNow entry at all** (3,450
titles searched) and ships **Sumo Logic only as a wordmark** (2,026 path chars of
letterforms, which render as an illegible smudge in a badge). Both were solved by sourcing
proper square *icons* from `vectorlogo.zone` instead.

**That also removed a layout compromise.** The interim treatment needed a **76 px wordmark
slot** because two brands were wordmark-only. With real square icons for all four, the badge
is back to a **uniform 32 px square** — better than both the house-glyph plan and the
wordmark attempt.

⚠️ **Implementation note**: `sumologic.svg` has a **non-zero viewBox origin**
(`22.84 23.58 64 64`). Do not "tidy" it to `0 0 64 64` — that crops the mark. (An earlier
viewBox error of mine cropped the wordmark to a dash, which looked plausible until rendered;
hence the render-verify step is not optional here.)

**Attribution footer retained**: *"Platform names and logos are trademarks of their
respective owners, used here to identify the systems consulted."*

**Scope of the ruling**: internal, offline, projector-only, throwaway. The marks are used
nominatively to identify which system each step consulted. Reassess only if that scope
changes — repo made public, deck published, or a recording distributed externally.

---

#### Superseded recommendation (retained for the record): house glyphs

**Default shipped: house glyphs + 2-letter lettermarks tinted with real brand hex**
(ServiceNow `#62D84E` `SN`, Confluence `#2684FF` `CF`, Sumo `#4C7CFF` `SL`, GitLab
`#FC6D26` `GL`). Chosen on **design merit**, not as a legal dodge: the real set cannot be
made consistent — **ServiceNow is absent from simple-icons entirely** and
`sumologic.svg` is a wordmark in a 24×24 viewBox. Colour is not a mark and carries the
recognition; the platform name is spelled out in the row text anyway.

**Operator/legal residual**: whether to use real marks. Relevant verified facts — CC0
covers the *drawing*, not the *trademark*; ServiceNow permits nominative **text** but
requires permission for **logo** use and retains a takedown-monitoring vendor; risk rises
if the repo goes public, the deck is posted externally, or a recorded run circulates.
The swap seam is deliberate: **one `PLAT` table, a 4-line diff.** If adopted, add the
11 px footer — *"Platform names and logos are trademarks of their respective owners; used
here to identify the systems consulted."*

### LT7 — `connectors` provenance chip (independent; arguably fixes a *current* gap)

Putting `4 ms` next to `servicenow.getIncident(INC0012345)` makes today's latent ambiguity
acute — that call hit a fixture and nothing on screen says so. Ships independently of
everything above and is a **better** stage line than the unqualified one. Candidate FND in
its own right.

⛔ **But NOT as a single global chip** (doc-test 2026-07-30 — flagged independently by two
perspectives, and it is the sharpest finding against this concept). A chip reading
`connectors: mock (fixtures, no network)` would itself be **untruthful in two separate
ways** — i.e. it would commit the exact defect LT7 exists to prevent:

1. **Connector selection is per-connector and explicitly mixable** (FND-10): ServiceNow can
   be `real` while evidence stays `mock`. One global label misreports every mixed run.
2. **"no network" is false on the ADK path even with all connectors mock** — the engine
   still calls the Copilot proxy. Connector mode and engine/backend mode are *independent*
   settings.

Correct shape: **two derived chips, never one** — a connector chip
(`connectors: servicenow=real, others=fixtures`, or `all fixtures`) and a separate
engine/backend chip (`deterministic · offline` vs `ADK · via Copilot proxy`). Scope the
"no network" claim to what it actually covers: *no connector network*.

---

## Spikes / open questions for CDS

1. **Does `afterToolCallback` fire when `beforeToolCallback` denied the call?** Decides
   whether a `DENIED` step's terminal state is set from the `before` edge. One assertion
   against `AdkLiveRoundTripTest`'s existing zero-budget "deny every tool" case settles it.
2. **Real per-step ADK latency** — `timeout-ms: 90000` is self-documented as a guess. Also
   decides how much LT4 actually matters. Needs the corp laptop + proxy.
3. **Reveal cadence: fixed floor vs proportional-with-floor** (P-2 tension 4). Cheap to
   tune live; pick during CDS.
4. **Projector check** on the glow-pulse-vs-spinner reasoning (C flags this as reasoned,
   not measured) — one dry run.
5. ✅ **CLOSED** — *"can a model emit multiple function calls in one turn?"* **Yes**, and ADK
   may execute them in **parallel** (Codex, P-10a); my earlier reasoning from
   `ParallelAgent` being a separate agent type was wrong. Moot for the design because
   **`ToolContext.functionCallId()`** is the proper correlation key — but the monotonic
   counter idea is retired.
6. **v2 transport fork** — polling vs SSE+`Last-Event-ID` (LT4). Only bites if v2 is built.

## Explicitly out of scope

Changing what the diagnosis does; persisting/replaying historical runs; multi-user
dashboards; the K1 unattended path (no UI by definition); adopting any frontend framework
or build step.
