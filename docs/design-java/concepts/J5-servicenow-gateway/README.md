# J5 — ServiceNow Gateway (the primary source)

**State**: 🟢 Built · **Complexity**: Moderate · **Depends on**: J3, J4 ·
**Carries**: RC5 (automatic work-note write — the original "confirmed" human-gate
wording was superseded 2026-07-23, see below)

## Essence
ServiceNow is the **primary** evidence source, not just the trigger. The biggest
routing signal is **historical incidents + CMDB ownership** — often more useful
than Confluence or GitLab. Plus the one **write**: an advisory work note.

## Reads (ADK tools the agent may call)
- `getIncident(number)` → `IncidentContext`: short/full description, caller info,
  category/subcategory, opened time, environment, current assignment, comments &
  work notes, configuration item, reassignment history.
- `findSimilarIncidents(ctx)` → past incidents with similar symptoms + their **final
  assignment groups** + resolution codes/notes. ("Seven prior INCs mentioning
  APIM-4032 were resolved by Identity Gateway team" is compelling on its own.)
- `findOwnership(app)` → CMDB support group / business service ownership.

Real impl: ServiceNow **REST Table API** (`/api/now/table/incident`, `cmdb_ci`,
`kb_knowledge`), OAuth or service account, **least-privilege read + write on `incident`**
(write scoped to one journal field — `work_notes` or `comments`; never reassign, close, or
re-prioritise), allowlisted fields. *(Corrected 2026-07-31: this said "least-privilege read
role", which J8:21 had already corrected as false — the two advisory work notes are the
app's entire payoff. Found by 3 independent reviews; the J8 fix simply wasn't propagated
here.)*

## Write (automatic, two comments — RC5, revised 2026-07-23)
No human in the loop. On every run the orchestrator posts **two** advisory comments
via `addWorkNote(number, note)`, **sources first**, then the diagnosis:
1. **Sources consulted** (`DiagnosisReport.toSourcesNote()`) — links to the exact
   Confluence page / Sumo window / GitLab `file:line` / similar incidents used.
2. **First-pass diagnosis** (`DiagnosisReport.toDiagnosisNote()`) — the view + suggested
   outcome, clearly labelled AI-assisted / advisory.

Sources first makes the diagnosis auditable — every claim is one click from its
evidence. Both are **advisory**: they **never** touch assignment, state, or priority.
Idempotent (skip if an identical AI note already exists) — **on both connectors**
(FND-14, fixed 2026-07-30). This previously held only for `MockServiceNowGateway`;
`RealServiceNowGateway.addWorkNote` PATCHed unconditionally, so a retried request
(a flaky proxy, a manual re-trigger, a poller edge case) could post a duplicate
advisory comment onto a real, customer-visible ticket. It now checks
`sys_journal_field` for an exact-match existing entry first — same semantics as the
mock, verified against actual HTTP request/response shapes in
`RealServiceNowGatewayTest` (not just an extracted predicate). Toggle with
`triage.writeback.enabled` (default true; safe in `mock` — the mock just logs).
The earlier human-confirm gate is **removed** (see `PIVOT.md` / J8).

**Live write-back (2026-07-24).** The connector is switchable per system
(`triage.connectors.servicenow=mock|real`), so the demo can post the two comments to a
**real dev ServiceNow ticket** while evidence stays mock (`snow-live` profile). The
target journal is configurable — `triage.servicenow.write-field=work_notes` (default,
internal) or `comments` (customer-facing). Reads use `sysparm_display_value=true` for
readable names. Runbook: `docs/design-java/DEMO-RUNBOOK.md` → "Live demo" (FND-11:
there is no `app/` directory — `pom.xml`/`src/` are at the repo root). Automatic
triggering is **no longer deferred** — see **J10** (K1 outbound polling); this
gateway's `findIncidentsCreatedSince` is what J10 polls through (FND-21).

## Verification
- `MockServiceNowGateway` serves the J7 dataset incident + 2–3 similar resolved
  incidents + a CMDB ownership record.
- JS-2: real `getIncident` reads one incident; real `addWorkNote` posts one note to
  a test incident and is idempotent on re-run.
- **FND-47, fixed 2026-07-31**: real `getIncident`'s `sysparm_fields` list omitted
  `u_environment` while the parser read it anyway, so `IncidentContext.environment` was
  always null against a real instance — invisible to mock-only tests, since the mock has no
  field-selection to get wrong. `RealServiceNowGatewayTest#getIncidentRequestsAndParsesEnvironment`
  asserts the field is both requested and parsed.
- **FND-51, fixed 2026-07-31**: `triage.servicenow.write-field` was interpolated directly
  into the PATCH body with no restriction, and the hand-rolled JSON escaping covered only
  `\`, `"`, `\n` (a `\r` or tab in evidence text produced invalid JSON). Note text is now
  serialized via Jackson (already a transitive dependency) rather than re-derived escaping
  rules.
- **FND-57, fixed 2026-07-31**: the `write-field` allowlist check above originally lived
  in this gateway's own constructor — a manual `IllegalArgumentException`-or-nothing throw
  that only ran when `triage.connectors.servicenow=real` constructed this bean, i.e. never
  under the default `mock` config. A typo'd value booted clean all week in mock and threw
  for the first time on stage under `snow-live`. Moved to a `@Pattern(regexp =
  "work_notes|comments")` on `TriageProperties.ServiceNow.writeField` — validated
  unconditionally at boot by Spring's Bean Validation (`@Validated` on the
  `@ConfigurationProperties` record), regardless of which connector mode is active.
  This gateway now just reads the already-validated value. See **J1** for the full
  `TriageProperties` shape.
  `RealServiceNowGatewayTest#rejectsAnUnrecognisedWriteField`,
  `#workNoteWithCarriageReturnAndTabIsValidJson`.

## Open / risks
- API role / ACL / trigger approval is the main blocker → mock unblocks the demo.
