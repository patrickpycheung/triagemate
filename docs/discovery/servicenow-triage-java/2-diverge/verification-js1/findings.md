# Spike JS-1 — ADK-Java + LangChain4j feasibility (runnable portion)

**Ran**: 2026-07-23 · **Trigger**: D1 rested on 🤔 Assumed "ADK-Java can drive an
enterprise OpenAI-compatible endpoint from a Spring Boot app." Auto-executed per DDS
spike rule.

## What was runnable here (no enterprise key in sandbox)
Environment + dependency-reality checks against Maven Central; API-surface confirm
via the ADK-Java GitHub README + Context7 docs. A **live** model round-trip needs the
enterprise endpoint (operator confirmed one is available) → deferred to impl-time
**JS-1b**.

## Results

| Fact | Result | Trust |
|------|--------|-------|
| Java runtime | **21.0.11** present (matches team stack) | 🔬 Spiked |
| Maven Central reachable | HTTP 200 | 🔬 Spiked |
| `com.google.adk:google-adk` exists & GA | **1.4.0** on Central; **1.7.0** in README | 🔬 Spiked |
| `com.google.adk:google-adk-langchain4j` exists | 1.4.0 on Central | 🔬 Spiked |
| `com.google.adk:google-adk-spring-ai` exists | 1.4.0 on Central | 🔬 Spiked |
| `com.google.adk:google-adk-a2a` exists | 1.4.0 on Central | 🔬 Spiked |
| `dev.langchain4j:langchain4j-open-ai` GA | **1.0.0** | 🔬 Spiked |
| `LlmAgent.builder()...tools(...)` API | Confirmed (README 1.7.0) | 📚 Documented |
| `FunctionTool.create`, `Runner.runAsync`, LangChain4j model wrapper | Pattern from Context7 (0.8.0 era) | 📚 Documented |
| `mvn` on this box | **absent** (team has it; `auspost-mcp` builds w/ Maven) | — |
| Live agent→enterprise-endpoint→tool round-trip | not runnable here (no key) | ❓ Unknown → JS-1b |

## Findings that change the DDS

1. **Version correction**: ADK-Java is **GA 1.x (1.4.0/1.7.0)**, not experimental
   0.8.0. Removes the "young 0.x SDK" weakness that was D1's main risk. → strengthens
   the ADK decision.
2. **ADK ⇄ Spring AI is not either/or**: `google-adk-spring-ai` lets ADK run on a
   **Spring AI `ChatModel`** backend. The D1 "fallback to Spring AI" is therefore a
   **module swap on the model backend**, not an engine rewrite — the orchestrator,
   tools, and report contract are untouched either way. Fallback risk ↓↓.
3. **Two model-backend paths, both real**: (a) `google-adk-langchain4j` +
   `langchain4j-open-ai` → OpenAI-compatible endpoint; (b) `google-adk-spring-ai` +
   Spring AI's OpenAI client. Pick at impl time from whichever wires to the
   enterprise endpoint cleanest. Both reach the same endpoint type.
4. **A2A module exists** (`google-adk-a2a`) → the deferred Rovo path (Rovo Agent
   Connector speaks Agent2Agent) is technically reachable later without re-platforming.

## JS-1b — compile half DONE (2026-07-23, ahead of build day)
Because Maven was available in-session, the ADK wiring was written and **compiled
against the real jars** — not just planned:
- `mvn -Padk compile` → **BUILD SUCCESS**. All ADK 1.7.0 symbols resolve with **no
  signature fixes**: `LlmAgent.builder().model().instruction().tools()`,
  `FunctionTool.create(Class,"m")`, `@com.google.adk.tools.Annotations.Schema`,
  `com.google.adk.models.langchain4j.LangChain4j`, `InMemoryRunner`,
  `runner.sessionService().createSession(app,user).blockingGet()`,
  `com.google.genai.types.Content/Part`, `RunConfig.builder().build()`,
  `runner.runAsync(...).blockingForEach(...)`, `Event.finalResponse()/functionCalls()/content()`.
- The predicted "0.8.0→1.7.0 signature drift" risk **did not materialize**. 🔬 Spiked.

## JS-1b — live half PROVEN in-session (2026-07-23) 🔬 Spiked
Rather than defer to a real endpoint, the loop was proven against a local
**fake OpenAI-compatible server** (`app/src/adk-test/.../FakeOpenAiServer.java`):
- `mvn -Padk test` → **3/3 pass**. The real ADK `LlmAgent` + `InMemoryRunner` loop
  ran end-to-end: model → `tool_call get_incident` → ADK routed to
  `MockServiceNowGateway` → result fed back → final content parsed into a valid
  `DiagnosisReport`.
- **Bounds callback wired & proven**: `LlmAgent.beforeToolCallbackSync(...)` returning
  `Optional.of(errorMap)` denies over-budget calls; a budget-0 test shows
  `DENIED get_incident` and the agent still returns a report. Plus a hard
  `RunConfig.setMaxLlmCalls` backstop (J8).

## Residual (build day, needs the real endpoint)
- Swap the fake base-URL for the enterprise endpoint; confirm it's genuinely
  OpenAI-compatible (base path, auth header, streaming) and that the model actually
  chooses the right tools/terms. The wiring itself is done and green.

## Net
D1 (use ADK-Java) moves from 🤔 Assumed to **🟢 Low Risk**: the dependency stack is
real, GA, resolvable, Java-21-native, with two independent model-backend routes to
the confirmed enterprise endpoint and a first-class Spring-AI fallback. No blocker
found; only signature-level detail remains for build day.
