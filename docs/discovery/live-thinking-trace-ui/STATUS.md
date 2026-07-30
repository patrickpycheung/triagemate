# STATUS — DDS: Live "thinking trace" UI (animated per-step agent trace)

**Phase**: 4 COMPLETE — decision recommended, **awaiting operator lock**.
**Rigor**: Hackathon / RAPID. 4 Claude explorations + Gemini web research + Codex
engineering pass + 2 direct `javap` verifications against the real ADK jar.
**Started / completed**: 2026-07-30.
**Graduation**: pending operator lock → then CDS concept `J11 — Live thinking trace`
(owning T1–T3, T5), with amendments to J7/J8/J1/J2. T6 (logos) and T7 (provenance chip)
are separable.

## One-line problem
Show the copilot's *process* — a live, per-step trace with platform identity, an
in-progress state, and each line resolving to its result — so a demo audience sees work
happening instead of a spinner.

## DECISION (recommended)
**Build the structured-step spine + replay renderer (v1) now; add live streaming as an
ADK-only upgrade (v2) if the demo runs D1 as primary. Default to house glyphs, not real
vendor logos.** Full reasoning + concepts: `4-decide/concepts-extracted.md`.

ADM class **ADM-2** — several viable options *and* real discriminating tests existed, both
of which changed the answer. Decided on evidence, not preference. Trademark use is carved
out as T6 rather than self-approved, with a safe default shipped meanwhile.

## What measurement changed the answer

The intuitive build — "stream the agent's steps over SSE and animate them" — was ruled out
as a *foundation* by two verified facts:

1. **The default engine finishes in 2–19 ms** (measured). Nothing can stream, poll, or
   paint inside that. And it is D2, the demo's *guaranteed* fallback — so an ADK-only
   design leaves the safety-net path blank exactly when the presenter is recovering.
2. **`connectors=real` makes the same engine genuinely slow.** "Instant" is a *config*
   property, not an engine property — so no *fixed* pace is correct in both directions.

What survives both: **capture real per-step durations as data, then render honestly.**

## Key findings

- **Convergence (HIGH confidence)** — two explorations reached the same answer from
  opposite ends: structured `TraceStep`s with real durations, and *no* parsing of trace
  strings. Precedent is decisive: **FND-16 was this exact bug in this exact file**, and its
  resolution ("string-matching a trace line is not a contract") is quoted in
  `DiagnosisResult`'s javadoc today.
- **The honesty question dissolves** once *sequence* (real in both engines — the
  deterministic engine's javadoc says it runs "the SAME ordered steps the ADK agent runs")
  is separated from *reveal cadence* (a display property). FND-8/16/25 never forbade
  animation; all three were the UI **inferring a fact the backend already knew and was
  never asked for**, all fixed identically — *add a real field, read the real field*. That
  idiom prescribes this design's shape rather than prohibiting it.
- **Spike closed, correcting an exploration.** Exploration A named "does ADK 1.7.0 expose
  `afterToolCallbackSync` with a usable result" its top gating spike and asserted the
  callback **does not exist** — which would have ruled out "resolves in place" on the agent
  path. **False**: verified twice independently via `javap` that `LlmAgent.Builder` exposes
  `beforeToolCallback`, `afterToolCallback` **and** `onToolErrorCallback`, with
  `AfterToolCallbackSync.call(…, Object result)` carrying the real tool return value. A had
  read absence-in-*our-usage* as absence-in-the-*API*.
  Detail: `2-diverge/verification-adk-callbacks.md`.
- **Logos: evidence overturned the obvious answer.** Gemini advised using real
  `simple-icons` SVGs "for wow factor". Exploration C downloaded and queried
  `simple-icons.json` (3,450 icons) and found **ServiceNow is not in the set at all** (it
  appears in 4 of 9 trace lines) and **`sumologic.svg` is a full wordmark in a 24×24
  viewBox** — a grey smudge at 20 px on a projector. The real set is inconsistent by
  nature; four consistent house glyphs look *better*, with legal safety as a bonus rather
  than the driver.
- **The operator's in-place mechanic is sound.** Frontier products mostly append-and-
  collapse, but per-row resolution (what was actually asked) preserves the timeline — it's
  the **GitHub Copilot Workspace checklist pattern**. And we must *not* adopt the collapse
  convention: the evidence trail **is** this demo's differentiator (D4).
- **New tension found in synthesis (P-9)**: pre-rendering the full step plan is the honesty
  trap in a new costume — `gitlab.searchCode` is *conditional*, so showing it upfront
  asserts work that may never happen. Resolution: pre-render only the unconditional prefix.
- **Byproduct finding (T7)**: showing `4 ms` beside `servicenow.getIncident(...)` makes an
  **existing** ambiguity acute — that call hit a fixture and nothing says so. A derived
  `connectors: mock (fixtures, no network)` chip is a candidate FND in its own right.
- **Gemini's transport advice discarded**: it recommended Spring **WebFlux** SSE; verified
  there is no WebFlux in `pom.xml` (servlet stack, `spring-boot-starter-web`).

## Artifacts

- `1-elicit/problem-and-constraints.md` — 7 verified facts (F-1…F-7), 5 constraints
- `2-diverge/explorations/A-transport/` · `B-pacing-and-honesty/` ·
  `C-visual-design/` (+ **`sketch.html`**, verified in headless Chromium: zero console
  errors, `queued→active→done` progresses, disclosure toggles work) · `D-instrumentation/`
- `2-diverge/verification-adk-callbacks.md` — the closed spike
- `2-diverge/research/frontier-trace-ui-gemini.md` — product survey, UX literature
  (Nielsen 100 ms; Buell & Norton "labor illusion", 2011), trademark terms
- `2-diverge/research/spring-sse-adk-codex.md` — ⏳ Codex engineering pass (pending at
  time of writing; confirmatory only — the ADK API was verified directly and the case
  against SSE-for-v1 rests on the 2–19 ms arithmetic)
- `3-synthesize/patterns.md` — P-1…P-9 + open tensions
- `4-decide/concepts-extracted.md` — T1…T7, spikes, out-of-scope

## Residual for the operator

1. **Lock the decision** (DDS Phase 4 checkpoint, per this repo's convention).
2. **Real vendor logos — yes or no?** Safe default (house glyphs) already chosen and needs
   no ruling; swapping in real marks is a deliberate 4-line seam. Legal facts in T6.
3. **Is v2 (live ADK streaming) in scope?** Not optional polish *if* D1 is the primary demo
   path — v1 alone leaves a real 10–60 s blank there.

## Coherence with prior work

Extends **J7** (demo UI) and **J8** (observability: `DiagnosisResult.trace` is J8's
"flight recorder"). Reinforces **D4** from DDS `orchestrator-vs-copilot-cli` — "lead the
narrative with the evidence trail" — by making that trail legible per step rather than a
12 px list after the fact. Depends on nothing gated by **C6** (the Copilot ToS ruling):
v1 works entirely on the offline deterministic path.
