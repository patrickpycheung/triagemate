# Concepts Extracted — Java Triage Copilot → CDS seeds (J1–J8)

Seeds the CDS workspace `docs/design-java/`. Each maps to a `concepts/Jn-*/` card.

| ID | Concept | Essence | Complexity | Carries |
|----|---------|---------|-----------|---------|
| **J1** | **spring-boot-orchestrator** | App skeleton: `DiagnosisController` (POST incident #) → `DiagnosisOrchestrator` running the bounded flow; Maven module layout; config; mock⇄real profiles. | Moderate | RC2 intent |
| **J2** | **adk-agent-loop** | ADK-Java wiring: `SequentialAgent` macro-flow, `LlmAgent` + `FunctionTool`s, LangChain4j→enterprise LLM, `RunConfig` bounds, before/after-tool callbacks. **Spike JS-1**. | Critical | RC2 intent |
| **J3** | **connector-tools** | Gateway interfaces (ServiceNow/Confluence/Sumo/GitLab) + mock & real impls; adapting each to an ADK `FunctionTool`; allowlist + limits. | Moderate | forge-actions intent |
| **J4** | **diagnosis-report** | Strict JSON contract (reportedSymptom, candidates[]+confidence, suggestedAssignment, evidence[], contradicting, missingInfo, nextAction). Render → work note + UI. | Simple | new |
| **J5** | **servicenow-gateway** | Read incident + similar incidents + CMDB/ownership; **confirmed advisory work-note write** (idempotent, labelled). | Moderate | RC5 |
| **J6** | **knowledge-tools** | Confluence CQL search; **bounded** Sumo Search-Job (allowlisted scope, fixed window, max results/searches); targeted GitLab code search + **log↔code line citation**. | Highway | RC3 |
| **J7** | **demo-ui-and-dataset** | Minimal web page showing evidence + report; one demo application; ground-truth dataset; reuse S3′ Sumo fixture + `seed-repo`; fallbacks. | Moderate | RC6, S3′ |
| **J8** | **guardrails-observability** | Cross-cutting: untrusted-input handling, least-privilege/allowlists, advisory-only writes, per-run trace log (tools called, params sans secrets, docs retrieved, model, diagnosis, accept/reject). | Simple | new |

## Dependency order (for CDS + implementation)
`J4` (contract, no deps) → `J3` (gateways+mocks) → `J1` (skeleton wiring J3/J4) →
`J2` (ADK loop; **Spike JS-1 first**) → `J5`/`J6` (real tool behaviors) → `J7`
(demo) → `J8` woven throughout.

## MVP slice (the analysis's "build this" list, mapped)
1. POST endpoint accepting an incident number → **J1**
2. ServiceNow incident reader (mock/real) → **J5**
3. Similar-incident + ownership lookup → **J5**
4. Confluence search connector → **J6**
5. Structured AI diagnosis report → **J4 + J2**
6. Basic web page showing evidence → **J7**
7. ServiceNow work-note writer → **J5**
8. One bounded Sumo query → **J6**
9. One targeted GitLab code search + line citation → **J6**
