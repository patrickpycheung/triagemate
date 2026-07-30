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

## Residual uncertainty

- Whether ADK can issue **parallel** tool calls in our flat-`LlmAgent` config (affects
  step-id strategy, point 3).
- Whether `afterToolCallback` fires when `beforeToolCallback` **denied** the call
  (`BoundsCallback` returns a non-empty `Optional` to short-circuit). If it does, a denied
  step could be double-counted; if it doesn't, denied steps need their terminal state set
  from the `before` edge itself. **Needs a test against the fake OpenAI server** —
  `AdkLiveRoundTripTest` already has a zero-budget "deny every tool" case that is the
  perfect place to assert this.
