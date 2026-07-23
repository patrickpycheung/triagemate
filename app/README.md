# Incident Triage Copilot — Spring Boot POC

Local Spring Boot app that runs a **bounded, evidence-gathering** diagnosis of a
ServiceNow incident and produces an **advisory** report. Implements CDS concepts
J1–J8 (`../docs/design-java/`). Runs fully offline for the demo; a live ADK agent
drops in behind one profile.

## Run the offline demo (no network, no LLM)

```bash
cd app
mvn spring-boot:run
# open http://localhost:8080  → enter INC0012345 → Diagnose
```

Or via API:
```bash
curl -X POST http://localhost:8080/api/diagnose/INC0012345 | jq
```

You get the J4 report (clarified symptom, ranked candidate systems + confidence,
suggested team, evidence from all four sources incl. the **log↔code citation**
`payment_service.py:44`, contradicting evidence, missing info, next action) plus the
per-run tool-call **trace**.

Smoke test: `mvn test` (offline, asserts the demo-critical outcomes).

## Two engines (same tools, same output — J2)

| Engine | How | When |
|---|---|---|
| **deterministic** (default) | scripted phase flow, no LLM | offline demo, always works |
| **adk** | live ADK `LlmAgent` over the tools | `mvn -Padk spring-boot:run` + `triage.engine=adk` + `LLM_*` env |

```bash
export LLM_BASE_URL=https://llm.internal/v1   LLM_API_KEY=***   LLM_MODEL=gpt-4o-mini
mvn -Padk spring-boot:run -Dspring-boot.run.arguments=--triage.engine=adk
```
The ADK path is **Spike JS-1b** — see `src/main/adk/java/com/company/triage/agent/README.md`.

## Layout

```
src/main/java/com/company/triage
├── api/           DiagnosisController            (J1)
├── orchestration/ DiagnosisOrchestrator, DiagnosisEngine,
│                  DeterministicDiagnosisEngine, DiagnosisResult   (J1/J2)
├── gateway/       *Gateway interfaces + mock/*   (J3/J5/J6)
└── model/         DiagnosisReport + domain records (J4)
src/main/adk/…     ADK LlmAgent engine, tools, callbacks (J2, profile: adk)
src/main/resources/static/index.html   demo UI (J7)
```

## Guardrails (J8)
Advisory only — no reassign/close/priority/remediation. Work-note write-back is
**off** by default (`triage.writeback.enabled=false`) and additionally requires
`?confirmWriteback=true`. Sumo scopes are allowlisted; all fetched content is treated
as untrusted data. Every run emits a redacted tool-call trace.

## Swapping mocks for real connectors (JS-2)
Each `Mock*Gateway` (`@Profile("mock")`) has a `Real*Gateway` counterpart to add
under `@Profile("real")` — reuse the auspost-mcp GitLab/Confluence clients. No
orchestrator change; flip the active profile per gateway as access lands.
