# CDS Workspace — Java Triage Copilot (Spring Boot + Google ADK)

Active design workspace. Turns the Java-pivot DDS decision
(`docs/discovery/servicenow-triage-java/`) into an implementation-ready design for
a **local Spring Boot** hackathon demo. Supersedes the suspended Rovo-native CDS
(`docs/design/`). See root `PIVOT.md` for the strategy switch.

## One-paragraph architecture

One Spring Boot process. `DiagnosisController` (K3, manual) and `IncidentPoller`
(K1, J10, off by default) both call `DiagnosisOrchestrator.run(...)`, which
delegates to whichever `DiagnosisEngine` is active (corrected 2026-07-30 — this
paragraph previously described only the ADK path as if it were the whole system):

- **`deterministic`** (the **default** — `matchIfMissing = true`, no `-Padk` build
  required): a fixed Java script, no LLM, no network, cannot fail the way a model
  can. This is D2, the demo's guaranteed floor.
- **`adk`** (opt-in, `-Padk` build + `triage.engine=adk`): one **flat** ADK
  `LlmAgent` (not `SequentialAgent` — corrected 2026-07-30, see J2/FND-13) holding
  all eight tools under a single **global** allowlist. `DiagnosisOrchestrator`
  auto-degrades to `deterministic` if this one fails or times out (FND-7/FND-15),
  disclosed via `DiagnosisResult.engine`.

Either way: fetch incident → clarify symptom → find similar incidents & ownership →
search knowledge → (optionally) query bounded logs → (optionally) targeted code
search → produce the structured J4 report → post two advisory work notes (unless
`triage.writeback.enabled=false`, disclosed via `DiagnosisResult.writebackPosted`,
FND-25). Each connector (ServiceNow, Confluence, Sumo, GitLab) is a Spring `@Service`
behind an interface, mock-or-real per connector (`triage.connectors.<system>`, **not**
a Spring profile — FND-10), exposed to the ADK engine as a `FunctionTool`. The app —
not the model — controls which tools are allowed and how many times (J8); on the
deterministic engine there is no model choosing anything, the script IS the bound.

```
ServiceNow incident ──(K1 poll, J10 — default OFF)──────────▶ IncidentPoller
                    └─(K3 manual: POST /api/diagnose/{number})──▶ DiagnosisController
                                                                    │
                                                        DiagnosisOrchestrator
                                            (deterministic engine, DEFAULT — no LLM)
                                              or (ADK LlmAgent, opt-in, -Padk build)
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
J8 guardrails-observability · J9 contact-suggestion · J10 incident-poller ·
J11 live-thinking-trace (🟠 in design).

Each concept is a single `README.md` — no `design.md`, no `mechanics/`. That is
**deliberate** for hackathon/RAPID rigor: one file per concept that stays current beats
three that drift. Recorded here so the layout reads as a choice, not an omission.

**Cross-cutting walkthrough**: [DETERMINISTIC-FLOW.md](DETERMINISTIC-FLOW.md) — how the
default engine turns a ServiceNow ticket into Confluence/Sumo/GitLab queries and a report,
step by step, with a live worked example. Read it before changing query construction or
contact extraction; it spans J1/J2/J6/J9 so no single concept card owns it.

## Two engines (read this before the diagram above)

`triage.engine` selects the active `DiagnosisEngine`: **`deterministic` is the default**
(offline, no LLM — `matchIfMissing = true`) and `adk` is opt-in and needs the `-Padk`
build. The orchestrator **auto-degrades** ADK failures to the deterministic engine and
discloses it via `DiagnosisResult.engine`. So the ADK path above is the *opt-in* path, and
a report is not necessarily LLM-produced — see J1, J2 and `DEMO-RUNBOOK.md`.

## Non-goals (hackathon)
No Rovo/Forge, no MCP, no deployment, no vector DB / enterprise indexing, no
auto-reassign/close/priority/remediation, no broad source-code investigation.
