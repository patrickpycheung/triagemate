# Phase 3 — Synthesize

Four Claude explorations (A transport · B pacing/honesty · C visual · D instrumentation),
one Gemini web-research pass, one Codex engineering pass, plus two direct `javap`
verifications against the real ADK jar.

## P-1 — The convergence: this is a *data model* problem, not an animation problem

**B and D reached the same conclusion independently, from opposite ends**, and it is the
single most load-bearing finding of this DDS:

> The trace must carry **structured steps with real per-step durations**, and the UI must
> read those fields. Not parse strings, not infer, not fabricate timing.

- **D** (data-model bias) arrived at `TraceStep(seq, platform, tool, label, result, state,
  startedAtEpochMs, durationMs)` emitted through a `TraceSink` handed into
  `DiagnosisEngine.diagnose(...)`.
- **B** (honesty bias) arrived at `TraceStep{label, platform, ms}` on `DiagnosisResult`
  with `trace()` kept as a derived view — *specifically because* real durations are what
  make a paced reveal honest.
- Both **reject parsing the existing trace strings**, and both cite the same precedent:
  **FND-16 was exactly this bug, in exactly this file** (`index.html` regexed a trace line
  to detect a degraded run), and its own resolution states that string-matching a trace
  line is not a contract. That text is quoted in `DiagnosisResult`'s javadoc today.

**Verdict: HIGH CONFIDENCE.** Two independent explorations + an existing documented
precedent in the same file. The structured-step model is the spine; everything else is a
renderer on top of it.

## P-2 — The honesty question dissolves once you separate *sequence* from *cadence*

B's reframe, which I judge correct:

- The step **sequence and the work are real** in both engines —
  `DeterministicDiagnosisEngine`'s own javadoc says it runs *"the SAME ordered steps the
  ADK agent runs"*. Nothing is being invented.
- Only the **reveal cadence** is a display property.
- So "did we add delay?" is the wrong test. The right test is: **for every pixel, is there
  a real field behind it?**

A spinner asserts *"executing now"* — that would be false during a replay. A reveal
cadence asserts nothing, *provided the frame says what it is*. And crucially: FND-8/16/25
never forbade animation. All three were the UI **inferring a fact the backend already knew
and was never asked for**, and all three were fixed identically — *add a real field, read
the real field*. Applied here, that idiom points straight at real per-step timings. The
project value doesn't prohibit this design; it prescribes its shape.

**Gemini converges from the UX literature**: Nielsen's ~100 ms threshold means a 19 ms
flash reads as a *glitch*, not as speed; and the **"labor illusion"** (Buell & Norton,
2011) finds that instant results for apparently-hard work *reduce* perceived value and
trust. Gemini's prescription — no fake multi-second delay, but a **250 ms minimum visible
duration per step** — is the same shape as B's ~0.4 s reveal cadence.

## P-3 — `connectors=real` is the argument that settles pacing

B's decisive point: **"the deterministic engine is instant" is a *config* property, not an
engine property.** With `triage.connectors.*=real` the *same* engine makes real HTTP calls
and is genuinely slow (and now bounded by FND-34's 5 s/20 s timeouts).

Therefore any *fixed* pace is wrong in both directions — too slow for mock, and it can
slide the UI past a step whose HTTP call is still open for real. **Capturing real
durations is the only design that is correct across all four mock/real × deterministic/ADK
combinations.** This is what makes P-1 non-negotiable rather than merely tidy.

## P-4 — Transport: replay first, live streaming as an ADK-only upgrade

**A's arithmetic is decisive for the default path**: at 2–19 ms, the deterministic run
finishes inside a single paint frame. No SSE flush, poll, or WebSocket frame can arrive
before it's over. So:

- A **client-side replay renderer works on both engines and must be built regardless.**
- Any real transport is therefore an **ADK-path-only enhancement**, not the foundation.

**But note the limit of pure replay** (my addition — neither A nor B stated this): on the
ADK path a real run takes tens of seconds. Replay-after-completion means the audience
watches *nothing* for 40 s and then a 4 s replay — strictly worse than live. So the
phasing matters:

| Phase | Renderer | Works on | Why |
|---|---|---|---|
| **v1** | replay, real durations, honest frame | **both** engines | de-risks the demo; correct on D2, the guaranteed floor |
| **v2** | live step stream | ADK only | fills the genuine 10–60 s wait that v1 leaves blank |

A prefers **polling a per-incident step buffer** over SSE for v2, and the reasoning is
structural, not merely cheaper: re-reading the full buffer each poll is automatically
correct for FND-31 coalescing, mid-run joiners, page reloads, and the fact that
`diagnoseWithFallback` **prepends** the degradation line at index 0 (so a trace is *not*
append-only in run order — a subtle finding that breaks naive streaming). SSE additionally
introduces three new failure modes: async-request-timeout vs the 90 s engine timeout,
non-thread-safe concurrent `send()`, and emitting after `future.cancel(true)`.

**Gemini's transport advice is WRONG for this stack** — it recommended "an SSE endpoint
from Spring WebFlux". A verified there is no WebFlux in `pom.xml`; this is the servlet
stack, where `SseEmitter` is the mechanism. Discard that recommendation.

## P-5 — The ADK path can natively do exactly what the operator asked (spike closed)

Exploration A named this its top gating spike and **asserted `afterToolCallback` does not
exist** — which would have ruled out "resolves in place" on the agent path entirely.

**That assertion is false.** Verified twice independently (my `javap` run and D's), against
`google-adk-1.7.0.jar`: `LlmAgent.Builder` exposes all three edges —
`beforeToolCallback(Sync)`, `afterToolCallback(Sync)`, `onToolErrorCallback(Sync)` — and

```java
AfterToolCallbackSync.call(InvocationContext, BaseTool, Map<String,Object> args,
                           ToolContext, Object result)   // ← the real tool return value
```

A read absence-in-*our-usage* (we only register `beforeToolCallbackSync`, for
`BoundsCallback`) as absence-in-the-*API*. Full detail: `../2-diverge/verification-adk-callbacks.md`.

Consequences: `before` → `ACTIVE` step; `after` → `DONE` + real result; `onError` →
`FAILED`. Also available: `beforeModelCallback`/`afterModelCallback` for a "thinking"
(model-call) state distinct from "calling a tool", and `beforeAgentCallback`/`after…` for
run boundaries. Two caveats: the callback must return `Optional.empty()` (a non-empty
return **rewrites the tool result** — observing must not mutate, and given this repo's
history that deserves an explicit test); and it is unconfirmed whether `after` fires when
`before` **denied** the call.

I also checked the parallel-tool-call worry: **`ParallelAgent` is a separate agent *type*
in ADK**, not implicit `LlmAgent` behaviour, and we deliberately use a flat `LlmAgent`
(FND-13). So a monotonic per-run counter is a safe correlation key, modulo a model
emitting multiple function calls in one turn.

## P-6 — Logos: the evidence overturns the obvious answer

**Direct conflict, and the better-evidenced side wins.** Gemini recommended using real
`simple-icons` SVGs "to maximise the wow factor". C actually *downloaded and queried*
`simple-icons.json` (3,450 icons) and found:

1. **ServiceNow is not in simple-icons at all.** Searching all 3,450 titles returns `[]`.
   The system of record — present in 4 of 9 trace lines — has **no drop-in mark**.
2. **`sumologic.svg` is a full wordmark inside a `0 0 24 24` viewBox.** At 20 px on a
   projector that is a grey smudge.
3. GitLab is **not** `CC-BY-SA-4.0` on current master (my prompt's premise was wrong, and
   C corrected it). simple-icons is CC0-1.0 at the repo level, but its own `DISCLAIMER.md`
   warns that this "doesn't imply that all icons within the project are also CC0" —
   and **CC0 waives copyright, not trademark**. A CC0 SVG of a trademarked logo is a CC0
   *drawing* of somebody else's *mark*.
4. **ServiceNow enforces actively**: logo use requires a licence or written permission, and
   they retain a vendor that proactively monitors for marks and files takedowns.

So the set is **inconsistent by nature** — 2 clean glyphs, 1 illegible wordmark, 1 missing
entirely. A real GitLab tanuki beside a hand-traced ServiceNow approximation looks *worse*
than four consistent house glyphs. C's recommendation — **house glyphs + 2-letter
lettermarks tinted with each platform's real brand hex** — wins on design merit
(consistent optical weight, legible at 3 m, colour carries recognition, zero asset
pipeline), with legal safety as a bonus rather than the driver. Colour is not a mark, and
the platform name is spelled out in the line text anyway, so the real-logo upside is small.

Keep the swap seam deliberate: all four marks in one `PLAT` table of inline SVG strings, so
adopting real marks later is a 4-line diff.

## P-7 — The operator's in-place mechanic is sound, with a precedent

Gemini reports frontier products mostly **append and collapse** rather than replace text
in place, because replacing destroys the auditable timeline. That is a real caution — but
it does **not** apply to what was actually asked. The operator described *each line*
resolving and the next then starting, with the message log retained. Per-row resolution
preserves history; it's the **GitHub Copilot Workspace checklist pattern** ("checkmarks
appear as steps complete").

It matters here specifically that we **not** adopt the collapse convention: the evidence
trail *is* this demo's differentiator (D4 — "lead with the evidence trail"). Collapsing
consulted sources away would hide the exact thing being sold.

## P-8 — Byproduct finding: a latent honesty gap that exists *today*

B's side observation, which I judge a genuine independent finding: putting `4 ms` next to
`servicenow.getIncident(INC0012345)` makes an **existing** ambiguity acute — that call hit
a *fixture*, and nothing on screen says so. The fix is a derived provenance chip —
`connectors: mock (fixtures, no network)` — read from the real `triage.connectors.*`
config. That is a *better* stage line than the unqualified one, and it is arguably a
FND-class gap in the current UI regardless of whether this feature ships.

## P-9 — Pre-rendering the plan is the honesty trap in a new costume

C's closing finding, and it is sharper than it first looks. The most *persuasive* version
of this UI pre-renders the whole step plan greyed-out ("here are the 9 things I'm about to
do"), then lights each row up as it happens. It reads as competence and it gives the
audience a progress frame.

But **`gitlab.searchCode` is conditional** in the emitter — it only runs if the log search
yielded a concrete error token. Pre-rendering it asserts *"I will search the code"* before
that is known, which is exactly the FND-8/16/25 failure mode (the UI stating something the
backend hasn't established) wearing a different costume. Same applies to any other
conditional step.

Resolvable three ways, in descending honesty:
1. **Append-as-they-occur** (no plan shown). Always true; loses the progress frame.
2. **Pre-render only the unconditional prefix** and append conditional steps as they fire.
   Honest and keeps most of the effect — the first 5 steps always run.
3. Pre-render all 9 with conditional rows visually marked as *"if needed"*. Honest only if
   that marking is unmissable at 3 m, which on a projector it probably isn't.

Recommend **(2)**. Note this is a *design* constraint discovered in synthesis, not an
implementation detail — it changes what the component can claim.

## Tensions left open

1. **v2's necessity is a timeline judgement, not a technical one.** v1 alone leaves the
   ADK path with a blank 10–60 s. If the demo runs D1 primary, v2 is not optional polish.
2. **Real-vs-house marks** is partly a brand/legal call, not purely technical (see P-6).
3. **Denied-tool step semantics** on the ADK path (does `after` fire after a `before`
   denial?) — one test against the existing zero-budget case in `AdkLiveRoundTripTest`
   settles it.
4. Whether the reveal cadence should be **fixed** (~250–400 ms/step) or **proportional**
   to real duration with a floor. B implies a floor; Gemini says 250 ms minimum. Unresolved
   detail, cheap to tune live.
