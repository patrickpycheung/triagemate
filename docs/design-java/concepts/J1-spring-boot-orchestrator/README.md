# J1 — Spring Boot Orchestrator

**State**: 🟢 Built · **Complexity**: Moderate · **Depends on**: J3, J4, J5, J8

## Essence
The Spring Boot skeleton and the shared entry point both triggers call through.
`DiagnosisOrchestrator.run(incidentNumber)` runs the bounded diagnosis flow to
completion and returns the structured result (J4). "One careful investigator
holding several tools" — not four agents shouting at each other.

**Two triggers, one orchestrator (FND-21)**: the manual `DiagnosisController` below
(K3) and the automatic `IncidentPoller` (J10, K1) both call `run()` directly — that
is the one place their calls meet, which is also why concurrent-call coalescing
(FND-31, below) lives here rather than in either trigger.

## Design
- **`DiagnosisController`** — `POST /api/diagnose/{incidentNumber}` → 200 with
  `DiagnosisResult` JSON (`report` + `trace` + `engine` — see FND-23 below). This is
  the **K3 manual trigger** (DDS `servicenow-local-trigger`); K1 is J10's poller,
  calling the same orchestrator without going through this HTTP route at all. There
  is no inbound webhook here and none is planned — see the FND-24 note under J10:
  ServiceNow cannot reach this app on a corp-network laptop, which is the reason K1
  polling exists in the first place.
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
- Symptom clarification lives **inside each engine** (`DeterministicDiagnosisEngine` /
  `AdkDiagnosisEngine`, J2) — there is no separate understanding-service class (FND-12
  corrected this; earlier drafts named a component that was never built this way).
- **Connector selection**: per-connector `@ConditionalOnProperty(name=
  "triage.connectors.<system>", havingValue="mock"|"real")` (J3) — **not** Spring
  `@Profile`, and there is no `mock` Spring profile at all. Mix freely: e.g.
  ServiceNow real while evidence stays mock (FND-10 corrected this).
- **Maven**: single Spring Boot app module for the hackathon (multi-module later);
  Java 21, Spring Boot 3.4.x, mirrors `auspost-mcp` conventions.

## Interface sketch
```java
@RestController @RequestMapping("/api/diagnose")
class DiagnosisController {
  @PostMapping("/{incident}")
  DiagnosisResult diagnose(@PathVariable String incident) {
    return orchestrator.run(incident);   // report + trace + engine (FND-23)
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
