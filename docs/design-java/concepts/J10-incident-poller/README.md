# J10 — Incident Poller (the K1 trigger)

**State**: 🟢 Built (offline-verified) · **Complexity**: Moderate · **Depends on**: J1, J5
· **Carries**: K1–K3 from DDS `servicenow-local-trigger`

## Essence

The automatic trigger. ServiceNow (cloud) cannot reach an app running on a corp-network
laptop — no public tunnel, no MID Server, no inbound firewall hole — so instead of waiting
to be pushed to, the app **polls outbound** over the same HTTPS channel it already uses to
read and comment on tickets. Poll latency (seconds) is the accepted cost of needing no
inbound path at all.

**Off by default** (`triage.trigger.poll.enabled=false`). The demo drives the manual
`POST /api/diagnose/{number}` endpoint (K3); a poller waking mid-presentation and
diagnosing unrelated tickets is a stage hazard, not a feature.

## Design

- **`IncidentPoller`** — `@Scheduled(fixedDelay)`, gated on `triage.trigger.poll.enabled`.
  Asks the gateway for new incidents, runs each through `DiagnosisOrchestrator` (J1).
- **`ServiceNowGateway.findIncidentsCreatedSince(since, limit)`** → `List<NewIncident>`
  (`number` + `createdAt`), oldest first.
- **`NewIncident`** — carries `createdAt` because the cursor is derived from it, not from
  wall-clock time. See "Never skip" below.

```
@Scheduled ─▶ findIncidentsCreatedSince(cursor, limit)
                 │
                 ├─ completed? ──────────────▶ skip (advance over)
                 ├─ in flight? ──────────────▶ skip (do NOT advance)
                 └─ claim ▶ orchestrator.run() ▶ mark completed ▶ release
                                 │
                                 └─ threw ───▶ not completed, cursor frozen here
```

| Setting | Default | Meaning |
|---|---|---|
| `triage.trigger.poll.enabled` | `false` | Master switch |
| `triage.trigger.poll.interval-ms` | `30000` | Delay measured from previous run's **completion** |
| `triage.trigger.poll.batch-limit` | `10` | Max incidents per tick |
| `triage.trigger.poll.completed-cap` | `500` | Bound on the in-process completed set |
| `triage.trigger.poll.unattended-llm-ack` | `false` | C6 ToS acknowledgement; suppresses the K1+`adk` startup WARN (FND-45) |

## The two correctness properties

### 1. Never diagnose the same incident twice (FND-1)

Four layers, in order of strength:

1. **Query by `sys_created_on`, not `sys_updated_on`** — the structural fix. Every run posts
   two work notes (J5), each bumping `sys_updated_on`; an updated-since query therefore
   re-selects every ticket this app touches and re-runs the whole diagnosis in a loop,
   burning LLM calls. Creation time is immutable, so our own writes can never resurface a
   ticket. This is also what the original `C-T3: insert-only` constraint always intended.
2. **In-flight set** — a number is claimed before work starts, released after, so a
   concurrent or re-entrant tick cannot pick up a run already in progress.
3. **Completed set** — bounded FIFO of numbers finished in this process, so even a cursor
   that fails to advance cannot cause a second diagnosis.
4. **J5 idempotency** ("skip if an identical AI note exists") — a backstop only: it dedupes
   the *comment*, by which point the expensive, ToS-sensitive model run already happened.

**Accepted trade-off**: an incident that becomes eligible *later* (re-categorised into
scope) is not picked up. Correct for "triage newly-created incidents". If
eligibility-on-update is ever wanted, it must come with an explicit "don't re-select what we
wrote" filter — **not** a switch back to `sys_updated_on`.

### 2. Never skip an incident

> **Two accepted exceptions** (added 2026-07-31 — this section read as an unqualified
> guarantee while Open/risks below accepts two ways it can be broken): **FND-43**, a
> same-second creation burst larger than `batch-limit`; and **restart re-seeding**, which
> skips anything created while the app was down. Both are documented and accepted under
> Open/risks — the guarantee below holds for everything else.

- The cursor advances to a **handled incident's `createdAt`**, never to `now()`. Advancing
  to now drops anything created *while the batch was processing* — a real window, since a
  live agent run can take a while.
- It advances only across an **unbroken run of handled incidents**, oldest first. A plain
  "newest handled" mark is insufficient: if an early incident fails and a later one
  succeeds, the cursor would move past the failure and lose it permanently. The first
  unhandled incident freezes the cursor; later successes are re-queried next tick and
  short-circuited by the completed set — one cheap query, zero lost incidents.
- A batch that handles nothing leaves the cursor untouched, so the window is retried.

## Overlapping runs

`fixedDelay` measures from the previous run's **completion**, and Spring's default scheduler
is single-threaded — so ticks cannot overlap and **the interval does not need to exceed
processing time**. That invariant is easy to break though (switching to `fixedRate`, adding
a scheduler pool), so an `AtomicBoolean` guard enforces it explicitly and logs a warning
when a tick is refused.

## Degraded runs (FND-8)

An unattended poll has no UI, so `DiagnosisResult.engine` (`DETERMINISTIC` / `ADK` /
`DEGRADED_TO_DETERMINISTIC`) plus `degraded()` is how a caller tells a real agent run from a
fallback one without string-matching the trace. The poller logs a `WARN` on degradation —
currently the only place it surfaces on an unattended run.

## Verification

- `IncidentPollerTest` (FND-30: a hand-maintained count here drifts by construction
  — see what's covered, not how many; `mvn test` is the source of truth), including
  the two properties above stated as executable claims:
  `ownWorkNoteWritesNeverRetriggerDiagnosis`,
  `incidentCreatedDuringProcessingIsNotSkipped`,
  `failedIncidentIsRetriedAndDoesNotStopTheBatch`, `overlappingTickIsSkipped`.
- Offline end-to-end: `./run-deterministic.sh -Dspring-boot.run.arguments="--triage.trigger.poll.enabled=true --triage.trigger.poll.interval-ms=3000"`
  → mock reports one new incident, poller diagnoses it **once**, then stays quiet across
  subsequent ticks despite the run having posted work notes.

## Open / risks

- **C6 gate now warned, not just documented (FND-45, fixed 2026-07-31).** This bean existing
  at all means `poll.enabled=true`; combined with `triage.engine=adk` that is exactly the
  unattended, programmatic LLM use the C6 ToS ruling gates. Previously stated only in prose
  below — nothing warned or refused. The constructor now logs a WARN at startup unless
  `triage.trigger.poll.unattended-llm-ack=true` is explicitly set (deliberately a warning,
  not a hard failure — matches FND-49's precedent that a hackathon build shouldn't refuse to
  boot).
  ⚠️ **Config-triggered, not capability-triggered** (2026-07-31, found by two reviews): this
  reads the `triage.engine` string and does **not** check that an ADK bean exists. On a
  default (non-`-Padk`) build with `engine=adk`, J1's FND-49 warning fires first —
  "no ADK engine bean is active, running DETERMINISTIC only" — and then this one claims
  "unattended, programmatic LLM use" for a run that will never contact a model. **Both
  firing together is expected**; FND-49's is authoritative about what actually runs. Setting
  the ack in that state records a ToS acceptance for a run that makes no LLM call. FND-56.
  `IncidentPollerTest#warnsWhenPollingWithAdkEngineAndNoAck`,
  `#noWarningWhenAckIsSetOrEngineIsDeterministic`.
- **Config centralized (FND-57, fixed 2026-07-31).** `batch-limit`, `completed-cap`,
  `triage.engine`, and `unattended-llm-ack` were four independent `@Value` constructor params;
  now a single injected `TriageProperties` (see J1), read as
  `props.trigger().poll().batchLimit()` etc. The `engine == adk` string comparison above is now
  `props.engine() == TriageProperties.Engine.ADK` — same behaviour, one less place a typo could
  silently misfire. `triage.trigger.poll.interval-ms` (on `@Scheduled`) and `.enabled` (on
  `@ConditionalOnProperty`) are unchanged — those two resolve their own property placeholders
  independently of constructor injection, so migrating them buys nothing (see J1's FND-57 note).
- **Accepted limitation (FND-43, closed 2026-07-31, not fixed): same-second timestamp
  collision beyond `batch-limit`.** If more than `triage.trigger.poll.batch-limit` (default
  10) incidents share the exact same `sys_created_on` second, the "unbroken handled prefix"
  cursor advance could move past ones never actually fetched (they'd be beyond
  `sysparm_limit`). A strictly-correct fix needs a tie-break key (e.g. `sys_id`) in the
  query/cursor. **Decided not to build**: K1 is off by default and unused by the demo, and
  the trigger condition needs K1 enabled *and* a true same-second creation burst — low
  probability for hackathon-scale traffic. Revisit if K1 is ever turned on against real,
  bursty traffic.
- **State is in-process only.** A restart re-seeds the cursor to "now", so incidents created
  while the app was down are skipped rather than re-triaged. Skipping is the safe direction,
  but it is a real gap for anything beyond a demo. Durable options: persist the cursor +
  completed set (file or embedded DB), or query ServiceNow for "no AI work note yet" instead
  of keeping local state. **Not decided.**
- **Not yet run against a real ServiceNow instance.** `sys_created_on` filtering, the
  `sysparm_display_value=false` raw-datetime assumption, and the UTC parse are all
  unverified against a live instance.
- **Unattended running is gated on C6** (the Copilot ToS ruling): the human-present
  risk acceptance covers the demo, not a poller running by itself.
- **No filter on which incidents qualify.** Every new incident is triaged. Real use wants
  the `C-T4` narrow criteria (category / priority / assignment group) to control cost and
  noise.
