# ADK engine (J2) — Spike JS-1b recipe

This source root compiles **only** under `-Padk`, so the offline deterministic demo
is never blocked by it. Activating it is build-day-1's first spike (JS-1b): prove one
live `LlmAgent` + tool round-trip against the enterprise endpoint.

## Files
- `AdkModelFactory` — builds the model backend from `triage.integrations.llm.*` in
  `secrets.properties` (or `LLM_BASE_URL/LLM_API_KEY/LLM_MODEL` env / `-D` overrides)
  via `google-adk-langchain4j` + `langchain4j-open-ai`.
- `TriageMateTools` — the six gateway-backed `@Schema` tool methods ADK exposes to the model.
- `BoundsCallback` — max-tool-calls leash (J8), to attach to `beforeToolCallback`.
- `AdkDiagnosisEngine` — builds the `LlmAgent`, runs it, parses the J4 JSON.

## JS-1b checklist (½ day)
1. `mvn -Padk -DskipTests compile` — pin any renamed 1.7.0 symbols:
   - `com.google.adk.tools.FunctionTool.create(Class, "method")`
   - `com.google.adk.tools.Annotations.Schema` (package may differ)
   - `com.google.adk.models.langchain4j.LangChain4j`
2. Implement `AdkDiagnosisEngine.runAgent(...)` — replace the `UnsupportedOperationException`
   with the real `InMemoryRunner` + session + `runAsync` event collection (reference
   shape is in the method's javadoc).
3. Attach `BoundsCallback.allow(name)` to the agent's before-tool callback.
4. One live round-trip: `POST /api/diagnose/INC0012345` with `triage.engine=adk` →
   expect a parsed `DiagnosisReport` with the same demo-critical outcomes the
   deterministic engine's test asserts.

## Fallback (no rewrite)
If the LangChain4j route fights the endpoint, add `com.google.adk:google-adk-spring-ai`
and build the model from a Spring AI `ChatModel` in `AdkModelFactory`. Everything else
(tools, engine, contract, UI) is unchanged.
