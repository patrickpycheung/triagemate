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
  `DiagnosisResult` JSON (`report` + `trace` + `engine` + `writebackPosted` — FND-23,
  FND-25; corrected here 2026-07-30, this line previously omitted `writebackPosted`).
  This is
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
  **120000 — MEASURED (FND-69, 2026-08-01)**, was 90000, itself self-documented as a
  guess. The LT4 spike ran the real agent against a Copilot-served model with real
  ServiceNow + Confluence: 3 tool calls → 44s, mean ~8.4s per call, ~14.6s for the final
  report, so `N × 8.4 + 14.6` puts a full 10-call run at ~99s. The old 90s was therefore
  SHORTER than a run its own `max-tool-calls: 10` budget permits — the agent could be
  killed by the timeout at ~99s and degrade mid-demo, which on stage is
  indistinguishable from the model failing. Raised the clock rather than cutting the
  budget: the tool budget is a J8 *safety* bound, the timeout a *liveness* one, and
  trading away investigation depth to fix a liveness number is the wrong lever.
  `verification-lt4-latency/findings.md`.) Previously enforced nowhere: a hung gateway hung the request
  forever, including on the K1 poller's single scheduler thread, where nobody would
  notice. A timeout on the ADK engine feeds the ordinary FND-7 fallback below; a
  timeout when deterministic is already the active engine propagates (nothing left
  to fall back to).
  Tool-call budget is a **J8/J2 concern on the ADK engine specifically**
  (`BoundsCallback`) — the deterministic engine runs a fixed script, not a
  model-selected loop, so a call-count budget doesn't apply to it the same way; this
  card previously implied a single uniform bound across both engines, which was wrong.
  **Incident-number normalization (FND-50, fixed 2026-07-31 — moved here from
  `DiagnosisController`)**: `run()` trims and uppercases the incident number *before*
  touching the coalescing map. FND-37 originally added this only in the controller, so K1
  (which passes ServiceNow's raw value) and K3 could still fail to coalesce on a case
  difference — defeating FND-31 for exactly the mixed-trigger case it exists for. Doing it
  here means every caller normalizes identically, by construction.
  **Concurrent-diagnosis coalescing (FND-31, fixed 2026-07-30)**: the manual K3 trigger
  (`DiagnosisController`) and the automatic K1 trigger (`IncidentPoller`) both call
  `run(incidentNumber)` — the one place their calls meet. Concurrent calls for the SAME
  incident number now coalesce: the second caller waits for the first's result instead
  of starting a duplicate diagnosis, so only one ServiceNow write happens. This is
  exactly the demo shape (polling on, presenter also clicks manually) and previously
  produced two full diagnoses and up to four advisory comments. Deliberately separate
  from `IncidentPoller`'s own in-flight/completed bookkeeping, which solves a different
  problem (don't re-poll something already handled, across scheduler ticks over time).
  **Misconfigured-engine warning (FND-49, fixed 2026-07-31)**: `triage.engine=adk` in a
  build without `-Padk` matches no ADK bean, so `engine == fallbackEngine` and the app runs
  deterministic-only with nothing announcing it — the FND-8 failure class (narrating a live
  model over a scripted run) via a misconfiguration path rather than a runtime one. The
  constructor now logs a WARN naming exactly this at startup. Deliberately not fail-fast: a
  hackathon build shouldn't refuse to boot over it.
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

> **Amended by J11** — the response gains a 5th component `List<TraceStep> steps`, and
> `DiagnosisEngine` becomes `diagnose(String, TraceSink)`. The documented four-property
> shape above stays wire-compatible (additive). J11 also adds a polling endpoint
> `GET /api/runs/{runId}/steps`; `POST /api/diagnose/{incidentNumber}` is unchanged.
> See `../J11-live-thinking-trace/README.md`.

**Error contract (FND-48, fixed 2026-07-31; hardened by FND-53 the same day; extended by
FND-58/FND-55 the same day)**: `DiagnosisApiExceptionHandler` maps `IncidentNotFoundException`
→ **404**, `DiagnosisTimeoutException` and `ResourceAccessException` → **504**,
`DiagnosisReportInvalidException` → **500**, `HandlerMethodValidationException` → **400**, each
to a `{"error": "..."}` JSON body. Previously none were handled and all fell through to
Spring's default error body, which has no `report` field; `index.html`'s `render()`
dereferences `data.report.candidateSystems`, so the stage failure mode was a raw `TypeError`.

Three FND-53 corrections to the first cut, all found by review before anyone ran it:
- The 404 was keyed on a **bare `IllegalStateException`**, which was safe only by accident —
  that same type is thrown for a missing LLM credential and for a JSON-serialisation failure,
  each shielded from the advice by an *unrelated* broad catch. A dedicated
  `IncidentNotFoundException` now carries the meaning.
- The advice was **unscoped**, therefore application-wide; it is now
  `basePackages = "com.company.triage.api"`, so a Spring-internal exception can't be
  translated into a client-facing "incident not found" with an internal message attached.
- `DiagnosisReportInvalidException` mapped to **502**, which claims an upstream failure. By
  the time it reaches this layer it cannot be one: an ADK-produced invalid report is caught
  by the FND-7 fallback and degrades (200 + banner). Only the *deterministic* engine's own
  validator can surface here — offline code, our own bug. **500** is the honest status.

Deliberately narrow: five types, four statuses (see FND-58/FND-55 below — `ResourceAccessException`
shares 504 with `DiagnosisTimeoutException`). Everything else
still falls through to Spring, which is why the UI must not assume a JSON body (see J7's
FND-52 note).

**Config centralized: `TriageProperties` (FND-57, fixed 2026-07-31).** Every `triage.*` value
that used to be an independent `@Value` on whichever constructor happened to need it —
`DiagnosisOrchestrator`, `IncidentPoller`, `RealServiceNowGateway`, `DeterministicDiagnosisEngine`,
`AdkDiagnosisEngine` — is now a single `@Validated @ConfigurationProperties(prefix = "triage")`
record, `TriageProperties` (`config/TriageProperties.java`), following the existing
`IntegrationProperties` convention and picked up by the app-wide `@ConfigurationPropertiesScan`.
Two concrete gaps this closes:
- **`triage.engine` is now a real enum** (`TriageProperties.Engine { DETERMINISTIC, ADK }`),
  not a bare `String` compared via `.equalsIgnoreCase`. An unrecognised value — `agent`, `llm`,
  `"Adk "` (trailing space), all three of FND-57's own examples — now fails application startup
  with a clear binding error, instead of silently resolving to deterministic with no warning
  (which was reopening the exact FND-8/FND-49 failure class via a typo). FND-49's own WARN
  (`props.engine() == Engine.ADK && engine == fallbackEngine`) is unchanged in behaviour — it
  catches a *different* case (a valid `adk` value, no ADK bean because the build lacks
  `-Padk`) that enum validation cannot catch, so both checks stay, now sharing one source.
- **Validation is unconditional at boot**, not conditional on which bean happens to get
  constructed. `@ConfigurationProperties` beans are eagerly instantiated at context refresh
  regardless of which `@ConditionalOnProperty` connector beans are active, so
  `TriageProperties`'s `@Pattern`/`@Min`/`@NotNull` constraints (e.g. `servicenow.writeField`,
  see J5's FND-57 note) run every boot — not only when `RealServiceNowGateway` happens to be
  constructed under `triage.connectors.servicenow=real`.

`triage.trigger.poll.interval-ms` and `.enabled` stay outside constructor-injected fields —
`@Scheduled(fixedDelayString = "...")` and `@ConditionalOnProperty` resolve their own property
placeholders independently of bean injection, at a different point in the Spring lifecycle, so
migrating them would need a custom `SchedulingConfigurer` for no behavioural gain. Both remain
documented fields on `TriageProperties.Trigger.Poll` for completeness/validation even though
the `@Scheduled`/`@ConditionalOnProperty` annotations read the raw property directly.

**Incident-number path validation (FND-58, fixed 2026-07-31).** `DiagnosisController` is now
`@Validated`, and `diagnose(@PathVariable @Pattern(regexp = "INC\\d{6,10}") String
incidentNumber)` rejects anything not shaped like `INC` + 6–10 digits before it reaches the
orchestrator. Previously unconstrained: `POST /api/diagnose/banana` was accepted and reached
`RealServiceNowGateway`, becoming part of a raw ServiceNow query string under
`connectors.servicenow=real`. FND-54 already makes the *mock* gateway reject any number but
its one seeded incident, so the demo path was never actually at risk — but the real-connector
contract gap was real. A `HandlerMethodValidationException` (Spring Boot 3.2+'s translation of
a `@Validated` controller's constraint violations) is now one of the API contract's mapped
types → **400**, `{"error": "invalid incident number"}`.

**Which timeout fires first (FND-34 vs FND-15) — added 2026-07-31, corrected 2026-07-31 (FND-55).**
These two bounds overlap and the HTTP one usually wins. `spring.http.client.read-timeout`
(20s) applies to `RealServiceNowGateway`'s *injected* builder — including `getIncident`
**inside** `engine.diagnose()`. So a hung real ServiceNow fails at ~20s with a
`ResourceAccessException`, **not** at 120s with `DiagnosisTimeoutException` — a genuinely
different exception type, now also mapped to **504** (same status as the timeout above, both
meaning "the app waited too long for an upstream"). Previously unmapped, surfacing as a bare
500. This is a status-code correctness fix only — it does NOT resolve the separate, still-open
question of whether 20s/120s are the *right* timeout values; the wall clock is now measured
(FND-69) but the 20s HTTP read timeout still isn't — that's the remaining real-latency tuning the J11
spike's real ADK-latency data will inform, orthogonal to which status a timeout returns today.
`DiagnosisApiExceptionHandler` is now five exception types mapped to four distinct statuses
(`ResourceAccessException` and `DiagnosisTimeoutException` share 504).

## Interface sketch
```java
@RestController @RequestMapping("/api/diagnose")
class DiagnosisController {
  @PostMapping("/{incident}")
  DiagnosisResult diagnose(@PathVariable String incident) {
    return orchestrator.run(incident);   // report + trace + engine + writebackPosted
  }
}
```

## Verification
- Boots with `mvn spring-boot:run`; `POST /api/diagnose/INC0010005` returns a
  well-formed `DiagnosisResult` end-to-end with the default (`mock`) connector
  config and **no external network** (corrected 2026-07-30: "mock profile" was
  stale wording left over after FND-10 — there is no Spring profile involved, see
  the Design section above and J3).
- Wall-clock timeout: `DiagnosisOrchestratorTest#engineTimeoutPropagatesWhenNoFallback`,
  `#engineTimeoutOnPrimaryDegradesToFallback` (both inject a slow engine lambda).
- Error contract (FND-48/FND-53): `DiagnosisApiExceptionHandlerTest` — 404/504/500 mapping,
  a bare `IllegalStateException` deliberately NOT translated, and a null exception message
  not breaking the handler.
- Normalization (FND-50): `DiagnosisOrchestratorTest#differentlyCasedIncidentNumbersStillCoalesce`.
- Concurrency coalescing:
  `#concurrentRunsForSameIncidentCoalesceIntoOneEngineCallAndOneWriteback` (latch-forced
  overlap — one engine call, one writeback), `#sequentialRunsOfTheSameIncidentAreNotCoalesced`
  (non-overlapping calls are NOT coalesced — a deliberate manual re-trigger still runs).

- **HTTP-level timeout on ServiceNow calls (FND-34, fixed 2026-07-30)**: the wall-clock
  timeout above only bounds `engine.diagnose()`. The two `addWorkNote` writeback calls
  in `runOnce()` and `IncidentPoller`'s own `findIncidentsCreatedSince` call run
  directly on the caller's thread (K3's HTTP request thread, or K1's single scheduler
  thread) with no wrapper — and `RestClient.Builder` previously had no configured
  timeout at all, so a network partition could hang either thread forever, unbounded.
  Closed via `spring.http.client.connect-timeout`/`read-timeout` (5s/20s), which Spring
  Boot applies to any autoconfigured `RestClient.Builder` — including
  `RealServiceNowGateway`'s injected one — with no code change there. Real
  Confluence/Sumo/GitLab calls build their own `RestClient` directly (not
  Spring-managed) and stay covered only by the existing virtual-thread wall-clock
  timeout above, since they run solely inside `engine.diagnose()`.

## Open / risks
- Sync vs async response (long agent runs). MVP: synchronous with a timeout;
  revisit if runs exceed ~30s in the demo.
