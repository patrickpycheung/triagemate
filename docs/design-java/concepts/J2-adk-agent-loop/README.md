# J2 — ADK Agent Loop

**State**: 🟢 Built · **Complexity**: Critical · **Depends on**: J1, J3 ·
**Gates on Spike JS-1**

## Essence
The agentic core: Google ADK-for-Java runs a **bounded** loop where an enterprise
LLM chooses search terms and interprets results, but the **application** controls
which tools are permitted, their order, and their limits.

## One of TWO engines, and not the default (FND-3)

`DiagnosisEngine` has two implementations and `triage.engine` selects which is active:

| `triage.engine` | Implementation | Needs |
|---|---|---|
| `deterministic` (**default**, `matchIfMissing = true`) | `DeterministicDiagnosisEngine` | nothing — no LLM, no network |
| `adk` | `AdkDiagnosisEngine` (this card) | the `-Padk` build + a reachable OpenAI-compatible endpoint |

This split is load-bearing for the demo, not an implementation detail: it is **D2** in DDS
`orchestrator-vs-copilot-cli` — the guaranteed offline fallback that means the demo cannot
hard-fail on stage. Two consequences worth stating here:

- **The ADK engine is opt-in.** A plain `mvn spring-boot:run` never exercises this card;
  `src/main/adk` is not even compiled without `-Padk`. Use `./run-adk.sh`.
- **`DiagnosisOrchestrator` auto-degrades to the deterministic engine** if this one fails
  (its `LlmCallsLimitExceededException` backstop, or any model/proxy/network failure),
  disclosing it via `DiagnosisResult.engine = DEGRADED_TO_DETERMINISTIC` and a trace line
  (FND-7 / FND-8). So a failure here is a *quality* regression, not an outage — which also
  means a broken config can look like success. See J1.

## Design
- **Model wiring (provider-neutral)** — wrap an OpenAI-compatible endpoint with
  LangChain4j, then hand it to ADK. `AdkModelFactory` resolves each value
  **most-specific-first: system property → environment variable → `secrets.properties`**,
  so all three of these work (FND-4):

  | Purpose | `secrets.properties` key (**documented route**) | env / `-D` fallback |
  |---|---|---|
  | endpoint | `triage.integrations.llm.base-url` | `LLM_BASE_URL` |
  | key | `triage.integrations.llm.api-key` | `LLM_API_KEY` |
  | model | `triage.integrations.llm.model` | `LLM_MODEL` |

  ```java
  OpenAiChatModel llm = OpenAiChatModel.builder()
      .baseUrl(cfg("LLM_BASE_URL", "triage.integrations.llm.base-url"))
      .apiKey(cfg("LLM_API_KEY",  "triage.integrations.llm.api-key"))
      .modelName(cfg("LLM_MODEL", "triage.integrations.llm.model"))
      .temperature(0.0)          // deterministic-ish triage
      .build();
  LangChain4j adkModel = LangChain4j.builder().chatModel(llm).modelName(model).build();
  ```
  > **`api-key` must be non-blank even when the endpoint ignores it.** An OAuth-backed
  > Copilot proxy does not check the value, but the factory *requires* the key to be
  > present — and a blank one throws, which the FND-7 fallback then turns into a silent
  > degraded run (this is exactly how spike C2 appeared to pass while never calling the
  > model). `secrets.properties.example` ships a placeholder for this reason.
- **Macro-flow = one `LlmAgent` holding all eight tools (FND-13, corrected 2026-07-30).**
  Earlier drafts of this card specified a `SequentialAgent` with per-step tool
  allowlists (`understand → identifyCandidates → knowledge → logs? → code? →
  report`, each step limited to its own tools). The built agent is a single
  `LlmAgent`; the model chooses its own step order within one flat tool set. The J8
  allowlist (`BoundsCallback`, fixed for FND-8/J8 on 2026-07-30) is **global** — every
  registered tool, for the whole run — not per-step. If staged sub-agents are wanted
  later, that is new work, not a doc fix; until then this card should not claim the
  stronger per-step property.
- **Tools** = `FunctionTool.create(TriageMateTools.class, "method")` (J3/J6), the
  eight registered in `AdkDiagnosisEngine.ALLOWED_TOOLS`: `get_incident`,
  `find_similar_incidents`, `find_ownership`, `search_confluence`, `search_logs`,
  `search_code`, `find_page_contributors`, `find_recent_committers` — the last two
  back **J9**'s "who to talk to" suggestion (FND-21: not previously cross-referenced
  here). Names are ADK `@Schema` names (snake_case), **not** the Java method names —
  the allowlist must match what ADK actually reports as `tool.name()`.
- **Bounds (`RunConfig` + callbacks)**: max tool calls — `triage.agent.max-tool-calls`
  (default 10, declared in `application.yml`; FND-27, previously an inline default only,
  absent from config and every card) — an allowlist of exactly those eight names,
  per-tool result caps, timeouts (orchestrator-level, J1, FND-15).
  Enforced by `beforeToolCallback` (J8) — reject out-of-allowlist calls (real as of
  the J8 fix; previously documented but not implemented), clamp result sizes, count
  calls.
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
- RxJava (`Flowable`/`Single`) ergonomics for a Java-team new to it → the reactive
  surface stays inside `AdkDiagnosisEngine`; `diagnose()` is a blocking call to J1.
- ~~ADK v0.8.0 API drift~~ — resolved at Spike JS-1 (FND-28): the project is pinned to
  GA **1.7.0**, not 0.8.0; this line contradicted the Versions section above it and
  was stale from before that spike ran.
