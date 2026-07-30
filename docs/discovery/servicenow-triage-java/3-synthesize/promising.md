# Promising — the surviving approach

## ✅ Selected: Spring Boot orchestrator + ADK-Java bounded agent + plain-Java tool gateways

One local Spring Boot process. `DiagnosisController` → `DiagnosisOrchestrator` runs an
ADK `SequentialAgent` (deterministic phases) whose `LlmAgent` steps call **plain Java
gateway services** exposed as ADK `FunctionTool`s. The LLM is an enterprise
OpenAI-compatible endpoint reached via `google-adk-langchain4j` (primary) or
`google-adk-spring-ai` (fallback/alt). Output is the J4 JSON contract → advisory
ServiceNow work note + demo UI. Connectors mockable; scoped to one demo app.

**Why it wins**
- Satisfies every HIGH pattern (P1–P7).
- Team's stated engine (ADK) **and** provider-neutral **and** now GA-1.x low-risk.
- Cheapest path to a polished demo: mocks make it run offline today; real connectors
  drop in one at a time.
- Reuses `auspost-mcp` GitLab/Confluence Java integrations.

**Concepts**: J1 orchestrator · J2 adk-agent-loop · J3 connector-tools · J4
diagnosis-report · J5 servicenow-gateway · J6 knowledge-tools · J7 demo-ui-and-dataset
· J8 guardrails-observability. (Detailed in `docs/design-java/`.)

## Runner-up (kept as documented fallback, not a separate build)
Same architecture, **Spring AI `ChatClient` + `@Tool`** as the engine instead of ADK.
Now reachable *inside* ADK via `google-adk-spring-ai`, so it's a backend swap, not an
alternative to build. Trigger: if impl-time JS-1b shows ADK's agent loop fights the
enterprise endpoint.
