# Verification — ADK 1.7.0 tool-callback edges (spike, RESOLVED)

**Status**: ✅ **RESOLVED 2026-07-30** by direct `javap` inspection of the real jar.
**Why it mattered**: exploration A named this its top gating spike — *"whether ADK 1.7.0
exposes `afterToolCallbackSync` with a usable result — that gates 'resolves in place'
entirely."* Exploration A also asserted **"there is no `afterToolCallback`"**. That
assertion is **incorrect**, and it would have wrongly ruled out the operator's core
requested mechanic on the live-agent path.

The confusion is understandable: **TriageMate only registers `beforeToolCallbackSync`**
today (in `AdkDiagnosisEngine`, for `BoundsCallback`'s allowlist + budget). Absence in
*our usage* was read as absence in *the API*. It is not.

## Method

```bash
JAR=~/.m2/repository/com/google/adk/google-adk/1.7.0/google-adk-1.7.0.jar
javap -cp "$JAR" 'com.google.adk.agents.LlmAgent$Builder' | grep -i callback
javap -cp "$JAR" 'com.google.adk.agents.Callbacks$AfterToolCallbackSync'
```

## Finding — all three edges of a tool call are available

`LlmAgent.Builder` exposes (async + `…Sync` + `List<…>` overloads for each):

| Builder method | Edge |
|---|---|
| `beforeToolCallback` / `beforeToolCallbackSync` | tool call **starting** |
| `afterToolCallback` / `afterToolCallbackSync` | tool call **completed** |
| `onToolErrorCallback` / `onToolErrorCallbackSync` | tool call **failed** |

(The same before/after/onError triple also exists for the *model* call —
`beforeModelCallback`, `afterModelCallback`, `onModelErrorCallback` — and
`beforeAgentCallbackSync` / `afterAgentCallbackSync` for the whole agent run. Those give
a "thinking / calling the model" state distinct from "calling a tool", if wanted.)

Verified signatures:

```java
// START edge — what we already use for BoundsCallback
Callbacks$BeforeToolCallbackSync:
  Optional<Map<String,Object>> call(InvocationContext, BaseTool, Map<String,Object> args, ToolContext)

// COMPLETION edge — carries the RESULT as the trailing Object
Callbacks$AfterToolCallbackSync:
  Optional<Map<String,Object>> call(InvocationContext, BaseTool, Map<String,Object> args, ToolContext, Object result)

// FAILURE edge — carries the Exception
Callbacks$OnToolErrorCallbackSync:
  Optional<Map<String,Object>> call(InvocationContext, BaseTool, Map<String,Object> args, ToolContext, Exception e)
```

## Consequences for this DDS

1. **The operator's requested mechanic is achievable on the ADK path, natively.**
   `before` → emit `ACTIVE` step ("Searching Confluence…", platform derived from
   `tool.name()`); `after` → transition that same step to `DONE` and replace its text
   using `result`; `onError` → transition to `FAILED`. No polling of our own gateways, no
   guessing, no string-parsing of trace lines.
2. **`tool.name()` is the join key.** It returns the snake_case `@Schema` name
   (`search_confluence`, `sumo.search`…) — already established by FND-33's `ALLOWED_TOOLS`
   work, where using Java method names instead was a real bug. Platform attribution and
   the in-progress label can both be derived from it via one static map.
3. **A step needs a stable id** to correlate the two edges. `before` and `after` receive
   the same `args` map and `ToolContext`; simplest robust approach is a monotonic counter
   per run, since ADK's flat `LlmAgent` issues tool calls sequentially in our config —
   **but confirm sequentiality**, since parallel tool calls would break a naive counter.
   ⚠️ This is the one sub-question this spike did NOT settle.
4. **The deterministic engine gains nothing from this.** It has no ADK callbacks at all —
   its steps must be instrumented by hand (exploration D's territory). So the two engines
   still need a shared emission abstraction; this finding only removes the *ADK-side*
   uncertainty.
5. **`afterToolCallback`'s return value can REWRITE the tool result** (non-empty
   `Optional` replaces it). For trace purposes we must return `Optional.empty()` —
   observing must not mutate. Worth an explicit test, given this repo's history
   (FND-8/16/25 were all "the observability layer misrepresented reality").

## Update — third independent confirmation + the correlation key (Codex, 2026-07-30)

The Codex engineering pass inspected the same artifact independently (by bytecode, plus the
public 1.7.0 javadocs) and confirmed the callback triple. **Three independent verifications
now agree.** It also resolved point 3 above and corrected one of my conclusions:

- ✅ **`ToolContext.functionCallId()` returns `Optional<String>`** — and `FunctionCall` has
  its own `id()`. **This is the correct correlation key for joining the `before` → `after`
  edges**, and it removes the need for a monotonic counter entirely. Use it.
- ⚠️ **My "a monotonic counter is safe" conclusion was wrong.** I reasoned that because
  `ParallelAgent` is a separate agent type, a flat `LlmAgent` must be sequential. Codex
  found that **one `Event` may carry several `FunctionCall` parts and ADK may execute them
  in parallel**, and completion responses may likewise be merged — that is a property of
  the tool-execution path, not of agent composition. Moot in practice now, since
  `functionCallId()` is a proper key, but the counter approach is retired.
- ⚠️ **`onToolErrorCallback` is required for the failure edge; `afterToolCallback` must
  NOT be treated as a `finally` hook.** Confirms and sharpens the design: all three edges
  must be registered, not just two.
- ✅ Return-value semantics confirmed a third time, for all three callbacks: **non-empty
  `Optional` overrides** (short-circuits execution / replaces the result / converts the
  failure); empty continues. The trace sink must always return `Optional.empty()`.
- ⚠️ **Thread-safety requirement neither I nor exploration D flagged**: *"ADK callbacks may
  run on ADK/RxJava execution threads rather than the orchestration virtual thread. Make
  the progress sink thread-safe."* D's `TraceSink` design must account for this — it is
  not confined to the single virtual thread the orchestrator submits.
- Also available for observability, but **not** as precise execution timing:
  `Event.functionCalls()` / `Event.functionResponses()` off the `Flowable<Event>`. There is
  no dedicated tool-start event type; a function-call event means *"the model requested
  these calls"*, not *"this tool body has begun"*. Callbacks are the accurate edges.

## Residual uncertainty (remaining)

- Whether `afterToolCallback` fires when `beforeToolCallback` **denied** the call
  (`BoundsCallback` returns a non-empty `Optional` to short-circuit). If it does, a denied
  step could be double-counted; if it doesn't, denied steps need their terminal state set
  from the `before` edge itself. **Needs a test against the fake OpenAI server** —
  `AdkLiveRoundTripTest` already has a zero-budget "deny every tool" case that is the
  perfect place to assert this. Codex's note that the before-callback "short-circuits tool
  execution" *suggests* `after` does not fire, but that is an inference, not verified.

## Update — TASK-006 wiring, two more empirical findings (2026-08-02)

Both edges are now wired for real (`AdkDiagnosisEngine`, `AdkLiveRoundTripTest`). Running
the actual round trip against the fake server settled the residual uncertainty above, and
surfaced a second one this spike's bytecode-only method could not have seen:

- ✅ **The denial-double-count question is settled: it does not matter in practice, but the
  wiring defends against it anyway.** `AdkDiagnosisEngine` now tracks denied `callId`s and
  makes `after`/`onToolError` a no-op for one, so even if a future ADK version starts
  firing `after` post-denial, the `DENIED` row can never be silently overwritten by a
  same-`callId` `DONE` row.
- ⚠️ **`onToolErrorCallbackSync` is unreachable for a `FunctionTool`-wrapped tool that
  throws a normal business exception, on this ADK version.** `FunctionTool.runAsync`
  catches the reflective invocation's exception internally and resolves the call
  *successfully* with an error-shaped `{status=error, message="An internal error
  occurred."}` map — `afterToolCallbackSync` fires, not `onToolErrorCallbackSync`.
  Verified two ways: (1) an actual live round trip where `get_incident` throws
  `IncidentNotFoundException` resolves `DONE`, never `FAILED`; (2) `javap`-ing
  `FunctionTool.runAsync`'s bytecode shows a single `catch (Exception e)` wrapping the
  entire body. Every TriageMate tool is `FunctionTool`-wrapped, so this edge is dead code
  for this app today — kept wired anyway (LT4 design note: required regardless), and
  `resolveActiveCall`'s FAILED path is proven directly by
  `onToolErrorCallbackResolvesActiveCallToFailed` rather than through a live round trip
  that this ADK version cannot actually produce.
