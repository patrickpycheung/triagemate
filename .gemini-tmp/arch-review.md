# Architecture Review: Spring Boot Triage Copilot

## 1. Route Structure & Data Flow (Controller -> Orchestrator)
- **Concept Mapping**: The REST interface (`DiagnosisController`) cleanly delegates to `DiagnosisOrchestrator` (J1).
- **[SEVERITY MEDIUM] gap**: `DiagnosisController` normalizes the incident number (`trim().toUpperCase()`) before passing it to the orchestrator, while `IncidentPoller` passes the raw value from the gateway. This means FND-31's coalescing map can fail to deduplicate concurrent requests if the cases differ between the manual and polled triggers.
  - **File:line**: `src/main/java/com/company/triage/api/DiagnosisController.java:34`
  - **Fix**: Move `incidentNumber = incidentNumber.trim().toUpperCase();` to the top of `DiagnosisOrchestrator.run()` so all triggers coalesce uniformly.

## 2. Service-Layer Patterns & Dependency Injection
- **Concept Mapping**: Standard Spring DI is used across the application, except in the ADK tool layer (J2/J3).
- **[SEVERITY HIGH] debt**: `TriageMateTools` uses static fields to hold Spring-managed gateway beans, wired via a static `wire()` method called from `AdkDiagnosisEngine`'s constructor. This breaks DI, introduces global mutable state, and makes testing fragile.
  - **File:line**: `src/main/adk/java/com/company/triage/agent/TriageMateTools.java:55`
  - **Fix**: Refactor `TriageMateTools` into a Spring `@Component` with constructor injection, and pass the injected instance to ADK's `FunctionTool.create()`.

## 3. Data Flow (Orchestrator & Poller)
- **Concept Mapping**: The orchestrator correctly enforces timeouts (FND-15) and degrading (FND-7). The poller implements J10 with in-flight/completed checks.
- **[SEVERITY HIGH] gap**: `IncidentPoller` advances its cursor to a handled incident's `createdAt` timestamp and queries strictly `> cursor`. If multiple incidents share the exact same timestamp (e.g. same second) and the batch boundary falls between them, the remaining incidents will be permanently skipped by the next poll.
  - **File:line**: `src/main/java/com/company/triage/orchestration/IncidentPoller.java:189`
  - **Fix**: Use a stable tie-breaker for pagination (e.g., query `>= cursor` and maintain a short-lived deduplication set of recently seen `sys_id`s, or include `sys_id` in the cursor itself).

## 4. Concept-to-Implementation Mapping
The codebase faithfully maps the CDS concepts to concrete implementations:
- **J1 (Orchestrator)**: `DiagnosisOrchestrator` governs the flow, handles timeouts, and implements graceful degradation.
- **J2 (Agent Loop)**: `AdkDiagnosisEngine` properly uses ADK `LlmAgent` and limits the tool execution via `BoundsCallback`.
- **J3, J5, J6 (Gateways & Tools)**: `TriageMateTools` maps the mock/real gateways into structured `FunctionTool` calls with proper J8 allowlist assertions.
- **J4 (Report)**: `DiagnosisReport` strictly dictates the JSON contract, backed by `DiagnosisReportValidator`.
- **J7 (UI)**: The UI effectively maps the JSON payload to the visual components.
- **J8 (Guardrails)**: Both budget limits (max calls) and tool allowlists are firmly enforced in `BoundsCallback`.
- **J9 (Contacts)**: Properly merges authors and committers in the deterministic script and supports it via explicit tools in the ADK engine.
- **J10 (Poller)**: `IncidentPoller` acts as the trigger for outbound-only discovery.
- **J11 (Live Trace)**: Remains design-only per STATUS.md, which is expected.
