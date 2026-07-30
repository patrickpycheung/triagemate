# STATUS — DDS: Live "thinking trace" UI (animated per-step agent trace)

**Phase**: 4 COMPLETE — decision recommended, **awaiting operator lock**.
**Rigor**: Hackathon / RAPID. 4 Claude explorations + Gemini web research + Codex
engineering pass + 2 direct `javap` verifications against the real ADK jar.
**Started / completed**: 2026-07-30.
**Graduation**: pending operator lock → then CDS concept `J11 — Live thinking trace`
(owning LT1–LT3, LT5), with amendments to J7/J8/J1/J2. LT6 (logos) and LT7 (provenance chip)
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
out as LT6 rather than self-approved, with a safe default shipped meanwhile.

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
- **The honesty question dissolves** once the *order actually taken* (recorded per run in
  `seq`) is separated from *reveal cadence* (a display property). FND-8/16/25 never forbade
  animation; all three were the UI **inferring a fact the backend already knew and was
  never asked for**, all fixed identically — *add a real field, read the real field*. That
  idiom prescribes this design's shape rather than prohibiting it.
  ⚠️ **Corrected by doc-test**: an earlier version justified this with "both engines run the
  same ordered flow", citing the deterministic engine's javadoc. That inference is invalid —
  the ADK engine is a flat `LlmAgent` where the *model* picks order and calls may run in
  parallel. The claim is now the weaker, true one: *"the order shown is the order it
  happened."*
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
- **Byproduct finding (LT7)**: showing `4 ms` beside `servicenow.getIncident(...)` makes an
  **existing** ambiguity acute — that call hit a fixture and nothing says so. A derived
  provenance chip is a candidate FND in its own right. ⚠️ **But NOT one global chip**
  (doc-test): connectors are per-connector mixable (FND-10), *and* "no network" is false on
  the ADK path even with all connectors mock (the Copilot proxy). Needs **two** chips —
  connector provenance + engine/backend — or LT7 commits the very defect it exists to fix.
- **Gemini's transport advice discarded**: it recommended Spring **WebFlux** SSE; verified
  there is no WebFlux in `pom.xml` (servlet stack, `spring-boot-starter-web`).
- **Codex added what the Claude passes missed** (P-10): the before→after correlation key is
  **`ToolContext.functionCallId()`** — which also *retired my own* "a monotonic counter is
  safe" conclusion, since ADK may execute several calls from one `Event` in parallel;
  `spring.mvc.async.request-timeout` has **no Boot default** and falls to Tomcat's **30 s**,
  silently conflicting with the 90 s engine deadline; and the **`TraceSink` must be
  thread-safe** because ADK callbacks run on ADK/RxJava threads, not the orchestrator's
  virtual thread.
- **One fork left genuinely open rather than papered over**: v2 transport — exploration A
  wants polling, Codex wants SSE + `Last-Event-ID` replay (which does answer A's main
  structural objection). It is a v2-only question; v1 needs no transport.

## Artifacts

- `1-elicit/problem-and-constraints.md` — 7 verified facts (F-1…F-7), 5 constraints
- `2-diverge/explorations/A-transport/` · `B-pacing-and-honesty/` ·
  `C-visual-design/` (+ **`sketch.html`**, verified in headless Chromium: zero console
  errors, `queued→active→done` progresses, disclosure toggles work) · `D-instrumentation/`
- `2-diverge/verification-adk-callbacks.md` — the closed spike
- `2-diverge/research/frontier-trace-ui-gemini.md` — product survey, UX literature
  (Nielsen 100 ms; Buell & Norton "labor illusion", 2011), trademark terms
- `2-diverge/research/spring-sse-adk-codex.md` — ✅ Codex engineering pass (681 lines).
  Third independent confirmation of the ADK callback triple, **plus** the correlation key
  and two traps the Claude explorations missed — see P-10.
- `3-synthesize/patterns.md` — P-1…P-10 + open tensions
- `4-decide/concepts-extracted.md` — LT1…LT7, spikes, out-of-scope

## Residual for the operator

1. **Lock the decision** (DDS Phase 4 checkpoint, per this repo's convention).
2. **Real vendor logos — yes or no?** Safe default (house glyphs) already chosen and needs
   no ruling; swapping in real marks is a deliberate 4-line seam. Legal facts in LT6.
3. ~~**Is v2 (live ADK streaming) in scope?**~~ — **WITHDRAWN, this was a false question**
   (doc-test 2026-07-30). `DEMO-RUNBOOK.md` already **locks D1 as the primary path**, which
   answers it: LT4 is mandatory, not conditional. Asking cost a round-trip on a decision the
   runbook had already made. The real remaining choice is only *how* (polling vs SSE), which
   is an ADM-2 technical fork recorded in LT4, not an operator question.

## Coherence with prior work

Extends **J7** (demo UI) and **J8** (observability: `DiagnosisResult.trace` is J8's
"flight recorder"). Reinforces **D4** from DDS `orchestrator-vs-copilot-cli` — "lead the
narrative with the evidence trail" — by making that trail legible per step rather than a
12 px list after the fact.

⚠️ **C6 dependency — corrected (doc-test 2026-07-30).** This section originally said the DDS
"depends on nothing gated by C6 (the Copilot ToS ruling): v1 works entirely on the offline
deterministic path." True of **v1 only**. Promoting **LT4 to mandatory** (residual 3 above)
changes the picture: LT4 exists *specifically* to make the live ADK run legible, so the part
of this design that is now non-optional **does** sit behind the C6 ToS gate. Split the
statement: **v1 (LT1–LT3, LT5) is C6-independent and offline; LT4 inherits C6.** That gate
remains satisfied for the human-present demo and unresolved for unattended use.
