# CDS Workspace — Java Triage Copilot (Spring Boot + Google ADK)

Active design workspace. Turns the Java-pivot DDS decision
(`docs/discovery/servicenow-triage-java/`) into an implementation-ready design for
a **local Spring Boot** hackathon demo. Supersedes the suspended Rovo-native CDS
(`docs/design/`). See root `PIVOT.md` for the strategy switch.

## One-paragraph architecture

One Spring Boot process. `DiagnosisController` accepts an incident number and calls
`DiagnosisOrchestrator`, which runs a **bounded** ADK `SequentialAgent`:
_fetch incident → clarify symptom → find similar incidents & ownership → search
knowledge → (optionally) query bounded logs → (optionally) targeted code search →
produce structured JSON report → post advisory work note_. Each connector
(ServiceNow, Confluence, Sumo, GitLab) is a Spring `@Service` behind an interface,
mock-or-real, exposed to the LLM as an ADK `FunctionTool`. The app — not the model —
controls which tools are allowed, their order, and their limits. The LLM (an
enterprise OpenAI-compatible endpoint via LangChain4j) picks search terms and
interprets results.

```
ServiceNow incident ──(K1 poll, J10 — default OFF)──────────▶ IncidentPoller
                    └─(K3 manual: POST /api/diagnose/{number})──▶ DiagnosisController
                                                                    │
                                                        DiagnosisOrchestrator
                                                     (ADK SequentialAgent, bounded)
        ┌───────────────┬───────────────┬───────────────┬───────────────┐
   ServiceNowTool   ConfluenceTool     SumoTool       GitLabTool   (ADK FunctionTools)
        │  incident      │ CQL search    │ bounded job    │ targeted code search
        │  similar+CMDB  │               │ (allowlist)    │ + log↔code line cite
        └───────────────┴───────┬───────┴───────────────┘
                                ▼
                    DiagnosisReport (strict JSON)
                        ├─▶ ServiceNow work notes ×2 (advisory, AUTOMATIC — no human gate)
                        └─▶ Simple demo UI
```

## Module layout (Maven, reuses `auspost-mcp` patterns)

```
com.company.triage
├── api            DiagnosisController
├── orchestration  DiagnosisOrchestrator, IncidentUnderstandingService, ReportService
├── agent          AdkAgentConfig, tools/* Tool (FunctionTool adapters), callbacks/*
├── gateway        ServiceNow/Confluence/Sumo/GitLab Gateway (iface) + Real*/Mock*
├── model          IncidentContext, CandidateSystem, Evidence, SuggestedAssignment,
│                  DiagnosisReport, ...
└── config         IntegrationProperties; per-connector selection via
                   @ConditionalOnProperty(triage.connectors.*) — NOT Spring profiles
```

## Concepts
J1 orchestrator · J2 adk-agent-loop · J3 connector-tools · J4 diagnosis-report ·
J5 servicenow-gateway · J6 knowledge-tools · J7 demo-ui-and-dataset ·
J8 guardrails-observability · J9 contact-suggestion · J10 incident-poller.

Each concept is a single `README.md` — no `design.md`, no `mechanics/`. That is
**deliberate** for hackathon/RAPID rigor: one file per concept that stays current beats
three that drift. Recorded here so the layout reads as a choice, not an omission.

## Two engines (read this before the diagram above)

`triage.engine` selects the active `DiagnosisEngine`: **`deterministic` is the default**
(offline, no LLM — `matchIfMissing = true`) and `adk` is opt-in and needs the `-Padk`
build. The orchestrator **auto-degrades** ADK failures to the deterministic engine and
discloses it via `DiagnosisResult.engine`. So the ADK path above is the *opt-in* path, and
a report is not necessarily LLM-produced — see J1, J2 and `DEMO-RUNBOOK.md`.

## Non-goals (hackathon)
No Rovo/Forge, no MCP, no deployment, no vector DB / enterprise indexing, no
auto-reassign/close/priority/remediation, no broad source-code investigation.
