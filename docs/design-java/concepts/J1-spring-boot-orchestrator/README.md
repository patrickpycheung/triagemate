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
  the active `DiagnosisEngine` (J2 — either the ADK agent or the offline deterministic
  engine, selected by `triage.engine`), collects the report (J4), triggers the
  work-note write (J5), emits the run trace (J8).
  **Wall-clock timeout (FND-15, fixed 2026-07-30)**: every engine call — on **either**
  engine, since deterministic also makes real HTTP calls once `triage.connectors.*=real`
  — runs on a virtual thread bounded by `triage.orchestrator.timeout-ms` (default
  45000). Previously enforced nowhere: a hung gateway hung the request forever,
  including on the K1 poller's single scheduler thread, where nobody would notice. A
  timeout on the ADK engine feeds the ordinary FND-7 fallback below; a timeout when
  deterministic is already the active engine propagates (nothing left to fall back to).
  Tool-call budget is a **J8/J2 concern on the ADK engine specifically**
  (`BoundsCallback`) — the deterministic engine runs a fixed script, not a
  model-selected loop, so a call-count budget doesn't apply to it the same way; this
  card previously implied a single uniform bound across both engines, which was wrong.
  **Concurrent-diagnosis coalescing (FND-31, fixed 2026-07-30)**: the manual K3 trigger
  (`DiagnosisController`) and the automatic K1 trigger (`IncidentPoller`) both call
  `run(incidentNumber)` — the one place their calls meet. Concurrent calls for the SAME
  incident number now coalesce: the second caller waits for the first's result instead
  of starting a duplicate diagnosis, so only one ServiceNow write happens. This is
  exactly the demo shape (polling on, presenter also clicks manually) and previously
  produced two full diagnoses and up to four advisory comments. Deliberately separate
  from `IncidentPoller`'s own in-flight/completed bookkeeping, which solves a different
  problem (don't re-poll something already handled, across scheduler ticks over time).
  **Auto-fallback (FND-7, fixed 2026-07-30)**: when the ADK engine is active and fails
  to converge (its own `LlmCallsLimitExceededException` backstop, or any other
  model/proxy/network failure), the orchestrator degrades to the deterministic engine
  instead of returning a 500 — disclosed as the first trace line, never silent. When the
  deterministic engine is already the active one, its failures propagate normally (there
  is nothing to fall back to, and a bug there should surface as a bug).
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
- Wall-clock timeout: `DiagnosisOrchestratorTest#engineTimeoutPropagatesWhenNoFallback`,
  `#engineTimeoutOnPrimaryDegradesToFallback` (both inject a slow engine lambda).
- Concurrency coalescing:
  `#concurrentRunsForSameIncidentCoalesceIntoOneEngineCallAndOneWriteback` (latch-forced
  overlap — one engine call, one writeback), `#sequentialRunsOfTheSameIncidentAreNotCoalesced`
  (non-overlapping calls are NOT coalesced — a deliberate manual re-trigger still runs).

## Open / risks
- Sync vs async response (long agent runs). MVP: synchronous with a timeout;
  revisit if runs exceed ~30s in the demo.
