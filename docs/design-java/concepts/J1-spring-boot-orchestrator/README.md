# J1 — Spring Boot Orchestrator

**State**: 🟡 Drafted · **Complexity**: Moderate · **Depends on**: J3, J4

## Essence
The Spring Boot skeleton and the single entry point. A manual HTTP trigger accepts
an incident number and runs the bounded diagnosis flow to completion, returning the
structured report (J4). "One careful investigator holding several tools" — not four
agents shouting at each other.

## Design
- **`DiagnosisController`** — `POST /api/diagnose/{incidentNumber}` → 202/200 with
  `DiagnosisReport` JSON. (Manual trigger is the demo path; a ServiceNow
  Business-Rule webhook to this same endpoint is the "if we have time" upgrade —
  no code change to the core.)
- **`DiagnosisOrchestrator`** — owns the run: builds `IncidentContext` (J5), invokes
  the ADK agent (J2), collects the report (J4), triggers the work-note write (J5),
  emits the run trace (J8). Enforces a hard wall-clock timeout + max-tool-calls.
- **`IncidentUnderstandingService`** — Step-2 "clarify symptom": LLM turns the raw
  ticket into the structured interpretation (symptom, function, env, identifiers,
  missing info). Cheap, high-value, runs even if every other tool is mocked.
- **Profiles**: `mock` (default, self-contained demo) and `real` select
  `Mock*Gateway` vs `Real*Gateway` (J3) via `@Profile` / `@ConditionalOnProperty`.
- **Maven**: single Spring Boot app module for the hackathon (multi-module later);
  Java 21, Spring Boot 3.4.x, mirrors `auspost-mcp` conventions.

## Interface sketch
```java
@RestController @RequestMapping("/api/diagnose")
class DiagnosisController {
  @PostMapping("/{incident}")
  DiagnosisReport diagnose(@PathVariable String incident) {
    return orchestrator.run(incident);
  }
}
```

## Verification
- Boots with `mvn spring-boot:run`; `POST /api/diagnose/INC0012345` returns a
  well-formed `DiagnosisReport` end-to-end in `mock` profile with **no external
  network**.
- Wall-clock timeout and max-tool-calls are honored (inject a slow mock).

## Open / risks
- Sync vs async response (long agent runs). MVP: synchronous with a timeout;
  revisit if runs exceed ~30s in the demo.
