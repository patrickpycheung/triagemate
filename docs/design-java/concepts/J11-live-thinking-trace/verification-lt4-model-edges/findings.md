# LT4 — Model-level callback edges (the invisible 76%)

**Status**: ✅ DONE (2026-08-01, CDS Round 5) · **Trust**: 🔬 Spiked — `javap` against the
real ADK 1.7.0 jar, plus arithmetic on the LT4 latency measurement
**Triggered by**: `../verification-lt4-latency/findings.md` — the measurement exposed a hole
in LT4's own design that no amount of desk reasoning had found.

## The problem the measurement exposed

LT4 wires **three tool edges**: `beforeToolCallbackSync` → `ACTIVE`,
`afterToolCallbackSync` → `DONE`, `onToolErrorCallbackSync` → `FAILED`. Every `TraceStep`
J11 can currently emit on the ADK path is bracketed around **a tool call**.

The latency spike measured where the time actually goes:

| | run 2 | run 3 | run 4 |
|---|---|---|---|
| Agent wall clock | 39.7 s | 38.4 s | 33.0 s |
| Proxy-side LLM time (4 calls) | 28 s | 28 s | 26 s |
| ⇒ tool execution | ~12 s | ~10 s | ~7 s |

**~75% of the run is the model thinking, and LT4 emits nothing during any of it.** Two
distinct dead windows:

1. **Between steps** — `after(tool N)` → `before(tool N+1)` is model think time. The trace's
   ~8 s "per-step gap" is only ~3 s tool execution; the rest is this.
2. **After the last tool** — the model composes the final JSON report. Measured at
   **13.1 s ± 1.3**, *consistently the single longest gap in every run*, and there is no tool
   call in it at all, so not one callback fires.

Window 2 is the worst possible place for it: the live view would show every step resolve to
`DONE` and then **freeze for 13 seconds at the exact moment the audience is waiting for the
answer** — after which the completed report appears from nowhere. That is precisely the
"blank screen" failure LT4 exists to prevent, surviving inside LT4's own design.

Neither window is visible from the code. Only the proxy log's four LLM calls against three
tool callbacks made the mismatch countable.

## What the API actually offers (verified)

`javap` against `~/.m2/…/google-adk-1.7.0.jar`:

```
LlmAgent.Builder:
  beforeModelCallbackSync(Callbacks$BeforeModelCallbackSync)
  afterModelCallbackSync (Callbacks$AfterModelCallbackSync)
  onModelErrorCallbackSync(Callbacks$OnModelErrorCallbackSync)

Callbacks$BeforeModelCallbackSync:
  Optional<LlmResponse> call(CallbackContext, LlmRequest.Builder)
Callbacks$AfterModelCallbackSync:
  Optional<LlmResponse> call(CallbackContext, LlmResponse)

CallbackContext:
  String eventId()            // correlation key
```

Three findings:

- **The model edges exist and mirror the tool edges exactly** — same `…Sync` naming, same
  builder pattern, same `Optional<T>` short-circuit return. Nothing new to learn.
- **`CallbackContext.eventId()` is the correlation key**, the model-side analogue of
  `ToolContext.functionCallId()` that LT4 already relies on. LT1's "a step is a mutable row
  keyed by `callId`" invariant carries over unchanged.
- ⛔ **Same short-circuit hazard as the `before` tool edge.** Returning a non-empty
  `Optional<LlmResponse>` from `beforeModelCallback` **replaces the model's response** —
  i.e. an observer that returned anything but `Optional.empty()` would silently substitute
  its own answer for the LLM's. This is the model-level twin of the trap LT4 already warns
  about for `BoundsCallback`'s denial path, and it is arguably worse: a tool denial is at
  least visible as a `DENIED` row, whereas a substituted `LlmResponse` would look like the
  model said something it never said — a direct FND-8 breach. **A trace observer on these
  edges MUST return `Optional.empty()` unconditionally.**

## Consequence for the design

LT4 becomes **six edges, not three**. A `TRIAGEMATE`-platform "reasoning" row occupies each
model window:

- `beforeModelCallback` → open a `TRIAGEMATE` row, `ACTIVE`, keyed on `eventId()`.
  Label depends on position: *"Deciding what to check next…"* between tools,
  *"Composing the diagnosis…"* once the model stops calling tools.
- `afterModelCallback` → resolve that row `DONE` with its real duration.
- `onModelErrorCallback` → `FAILED` (this is also where a proxy/model failure becomes
  visible, instead of the run simply stopping).

Two things fall out for free:

- **The label cannot be chosen up front** — "composing" vs "deciding next" is only knowable
  *retrospectively* (whether a tool call followed). Simplest honest resolution: label every
  model window *"Thinking…"* while `ACTIVE`, and on resolve set `result` to what it produced
  — *"chose search_confluence"* or *"produced the report"*. That keeps the row truthful at
  every instant without predicting the future, which is the same discipline the honesty
  contract already imposes elsewhere on this card.
- **This also fixes the FND-42 repair retry's invisibility.** A repair round trip is a model
  call with no tool call, so today it too would be silent; with model edges it shows as a
  second `Thinking…` row, which is honest about the retry rather than hiding it.

## Cost

Small. Same builder, same callback idiom, same correlation-key pattern already specified for
tools. No new transport, no change to the `runId` protocol, no change to LT1's `TraceStep`
shape (`TRIAGEMATE` is already a `Platform` and already the documented home for non-platform
steps under LT2).
