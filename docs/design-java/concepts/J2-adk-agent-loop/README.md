# J2 — ADK Agent Loop

**State**: 🟡 Drafted · **Complexity**: Critical · **Depends on**: J1, J3 ·
**Gates on Spike JS-1**

## Essence
The agentic core: Google ADK-for-Java runs a **bounded** loop where an enterprise
LLM chooses search terms and interprets results, but the **application** controls
which tools are permitted, their order, and their limits.

## Design
- **Model wiring (provider-neutral)** — wrap an OpenAI-compatible enterprise
  endpoint with LangChain4j, then hand it to ADK:
  ```java
  OpenAiChatModel llm = OpenAiChatModel.builder()
      .baseUrl(env("LLM_BASE_URL")).apiKey(env("LLM_API_KEY"))
      .modelName(env("LLM_MODEL")).build();
  LangChain4j adkModel = LangChain4j.builder().chatModel(llm)
      .modelName(env("LLM_MODEL")).build();
  ```
- **Macro-flow = `SequentialAgent`** with sub-steps (deterministic order per the
  analysis): `understand → identifyCandidates → knowledge → (logs?) → (code?) →
  report`. Each step is an `LlmAgent` limited to the tools relevant to that step.
- **Tools** = `FunctionTool.create(XxxTool.class, "method")` (J3). The tool
  allowlist per step is set by the app, not the model.
- **Bounds (`RunConfig` + callbacks)**: max iterations / max tool calls, per-tool
  result caps, timeouts. Enforced by `beforeToolCallback` (J8) — reject
  out-of-allowlist calls, clamp result sizes, count calls.
- **Runner**: `runner.runAsync(userId, sessionId, msg, runConfig)` → `Flowable<Event>`;
  orchestrator (J1) subscribes, surfaces the final structured report and the event
  stream (→ J8 trace + J7 UI "it really consulted the sources").
- **Structured output**: final step is constrained to emit the J4 JSON contract
  (schema-guided; validate + one retry on malformed).

## Versions (Spike JS-1 verified, Maven Central)
`com.google.adk:google-adk:1.7.0` · `google-adk-langchain4j:1.7.0` (primary model
backend) · `dev.langchain4j:langchain4j-open-ai:1.0.0`. Fallback backend:
`google-adk-spring-ai:1.7.0` (run ADK on a Spring AI `ChatModel`). ADK-Java is **GA
1.x** — not the 0.8.0 the older docs show; re-pin `FunctionTool`/`Runner` signatures
against 1.7.0 javadoc at build time.

## Spike JS-1b (do first, build day 1 — live portion)
Prove: `LlmAgent` + **one** `FunctionTool` + `google-adk-langchain4j` → **live**
enterprise endpoint → one successful tool round-trip returning parsed JSON. Timebox
~½ day. **If the LangChain4j route fights the endpoint → swap to
`google-adk-spring-ai`** (backend-module change only; J1/J3/J4 untouched).
(Dependency existence + API surface already confirmed in Spike JS-1.)

## Verification
- With a stub `echoTool`, the agent calls it and returns the tool's payload.
- A `beforeToolCallback` denial (tool not in step allowlist) is observable and the
  run continues/aborts per policy.
- Malformed final JSON triggers exactly one repair retry, then a degraded report.

## Open / risks
- RxJava (`Flowable`/`Single`) ergonomics for a Java-team new to it → keep the
  reactive surface inside `AdkAgentConfig`; expose a blocking `run()` to J1.
- ADK v0.8.0 API drift → pin the version; Spring AI fallback de-risks it.
