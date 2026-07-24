# J5 — ServiceNow Gateway (the primary source)

**State**: 🟡 Drafted · **Complexity**: Moderate · **Depends on**: J3, J4 ·
**Carries**: RC5 (confirmed work-note write)

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
`kb_knowledge`), OAuth or service account, least-privilege read role, allowlisted
fields.

## Write (automatic, two comments — RC5, revised 2026-07-23)
No human in the loop. On every run the orchestrator posts **two** advisory comments
via `addWorkNote(number, note)`, **sources first**, then the diagnosis:
1. **Sources consulted** (`DiagnosisReport.toSourcesNote()`) — links to the exact
   Confluence page / Sumo window / GitLab `file:line` / similar incidents used.
2. **First-pass diagnosis** (`DiagnosisReport.toDiagnosisNote()`) — the view + suggested
   outcome, clearly labelled AI-assisted / advisory.

Sources first makes the diagnosis auditable — every claim is one click from its
evidence. Both are **advisory**: they **never** touch assignment, state, or priority.
Idempotent (skip if an identical AI note already exists). Toggle with
`triage.writeback.enabled` (default true; safe in `mock` — the mock just logs).
The earlier human-confirm gate is **removed** (see `PIVOT.md` / J8).

**Live write-back (2026-07-24).** The connector is switchable per system
(`triage.connectors.servicenow=mock|real`), so the demo can post the two comments to a
**real dev ServiceNow ticket** while evidence stays mock (`snow-live` profile). The
target journal is configurable — `triage.servicenow.write-field=work_notes` (default,
internal) or `comments` (customer-facing). Reads use `sysparm_display_value=true` for
readable names. Runbook: `app/README.md` → "Live demo". (Auto-trigger on ticket
creation is deferred — `docs/discovery/servicenow-auto-trigger/`.)

## Verification
- `MockServiceNowGateway` serves the J7 dataset incident + 2–3 similar resolved
  incidents + a CMDB ownership record.
- JS-2: real `getIncident` reads one incident; real `addWorkNote` posts one note to
  a test incident and is idempotent on re-run.

## Open / risks
- API role / ACL / trigger approval is the main blocker → mock unblocks the demo.
