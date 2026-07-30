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
  third-party trademark use (a real legal surface → carved out as T6).

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
backed by a real field: real step sequence (both engines run the same ordered flow), real
durations, real results, real platform attribution. The reveal cadence is the only display
property, and the frame says so explicitly.

---

## Concepts for CDS

Suggested home: **one new concept card `J11 — Live thinking trace`** owning T1–T3+T5, with
cross-reference amendments to J7 (UI), J8 (observability), J1/J2 (emission points). T6/T7
are separable.

### T1 — `TraceStep` model + `TraceSink` emission ⭐ (the spine — everything depends on it)

New package `com.company.triage.orchestration.trace` (in `src/main/java/`, so
`src/main/adk/` can import it — that direction is already established and the reverse stays
forbidden).

```java
record TraceStep(int seq, Platform platform, String tool, String label, String result,
                 State state, long startedAtEpochMs, Long durationMs) {}
enum Platform { SERVICENOW, CONFLUENCE, SUMO, GITLAB, TRIAGEMATE }
enum State    { PENDING, ACTIVE, DONE, FAILED, DENIED }
```

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
  drop steps. All 34/50 tests keep compiling with zero assertion changes.
- **Rejected**: `ApplicationEventPublisher` (needs correlation ids + subscriber registry);
  a `ThreadLocal` — the FND-33 precedent does *not* transfer (that exists only because
  ADK's tool methods are `static`; here the orchestrator runs engines on **virtual
  threads**, where a caller-installed ThreadLocal isn't visible anyway).
- **Required discipline**: the orchestrator's five `result.trace().add(...)` calls must go
  through the collector, or `steps` and `trace` drift — which is the FND-8/16/25 family
  recommitted. Worth a test asserting the two views agree.
- Blast radius (D's count): **7 files changed, 5 new, 0 existing test assertions changed.**

### T2 — `StepCatalog`: platform + in-progress label mapping

The in-progress verb strings (*"Searching Confluence for a runbook…"*) **exist nowhere
today** — only result strings do. A shared catalog keyed by *both* ADK snake_case tool
names (`search_confluence`) and deterministic dotted keys (`confluence.search`), yielding
`{platform, label}`. Non-platform steps (`understand:`, `contacts:`, `report assembled:`)
map to the `TRIAGEMATE` pseudo-platform. Add a **build-failing test that
`ALLOWED_TOOLS ⊆ catalog`** so a new tool can't ship unlabelled.

### T3 — Replay renderer with an honest frame ⭐ (v1; works on both engines)

Reveal completed steps at a perceptible cadence (~250–400 ms floor; Nielsen ~100 ms
threshold + Gemini's 250 ms minimum + B's 0.4 s converge here), each row showing its **real**
duration. Header must carry the four load-bearing words — *replaying*, *completed*,
*nothing is running now*, *real*:

> `↺ Replaying a completed run — 8 steps in 19 ms total. Nothing is running now; steps are
> revealed at 0.4 s each so they're readable. The order and the timings below are real.`

Plus log-scale duration bars (makes 2 ms vs 40 s legible in one visual language).
**Per P-9, pre-render only the unconditional step prefix**; append conditional steps
(`gitlab.searchCode`) as they actually fire.

**Ruled out** (B): unlabelled synthetic dwell + spinners + present tense (that is FND-8 in
a costume); fabricated reasoning copy for an engine that doesn't reason; ETA bars;
`Step N of 8` on the bounded ADK loop; any hardcoded "instant" copy.

### T4 — Live step streaming, ADK-only (v2; **needed if D1 is the primary demo path**)

Not optional polish if the demo runs D1: v1 alone leaves a genuine **10–60 s blank** on the
agent path. Wire ADK's three verified callback edges (P-5) —
`beforeToolCallbackSync` → `ACTIVE`, `afterToolCallbackSync(…, Object result)` → `DONE`,
`onToolErrorCallbackSync` → `FAILED` — and surface them via **polling a per-incident step
buffer** (A's recommendation, on structural grounds: automatically correct for FND-31
coalescing, mid-run joiners, page reloads, and the index-0 prepend). Reject SSE/WebSocket
for now: 3 new failure modes, and Gemini's WebFlux advice doesn't apply to this servlet stack.
Callbacks **must** return `Optional.empty()` — a non-empty return rewrites the tool result.

### T5 — Visual vocabulary (already prototyped and verified)

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

### T6 — Real vendor logos: operator/legal call (safe default already chosen)

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

### T7 — `connectors` provenance chip (independent; arguably fixes a *current* gap)

Derived from real `triage.connectors.*` config: `connectors: mock (fixtures, no network)`.
Putting `4 ms` next to `servicenow.getIncident(INC0012345)` makes today's latent ambiguity
acute — that call hit a fixture and nothing on screen says so. Ships independently of
everything above and is a **better** stage line than the unqualified one. Candidate FND in
its own right.

---

## Spikes / open questions for CDS

1. **Does `afterToolCallback` fire when `beforeToolCallback` denied the call?** Decides
   whether a `DENIED` step's terminal state is set from the `before` edge. One assertion
   against `AdkLiveRoundTripTest`'s existing zero-budget "deny every tool" case settles it.
2. **Real per-step ADK latency** — `timeout-ms: 90000` is self-documented as a guess. Also
   decides how much T4 actually matters. Needs the corp laptop + proxy.
3. **Reveal cadence: fixed floor vs proportional-with-floor** (P-2 tension 4). Cheap to
   tune live; pick during CDS.
4. **Projector check** on the glow-pulse-vs-spinner reasoning (C flags this as reasoned,
   not measured) — one dry run.
5. Whether a model can emit **multiple function calls in one turn** (would break a naive
   monotonic step counter; `ParallelAgent` is ruled out as the cause — it's a separate
   agent type and we use a flat `LlmAgent`).

## Explicitly out of scope

Changing what the diagnosis does; persisting/replaying historical runs; multi-user
dashboards; the K1 unattended path (no UI by definition); adopting any frontend framework
or build step.
