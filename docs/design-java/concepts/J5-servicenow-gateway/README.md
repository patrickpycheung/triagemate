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

## Write (guarded — RC5)
- `addWorkNote(number, note)` — posts the rendered J4 report as a **work note**,
  clearly labelled AI-assisted / advisory. **Never** touches assignment, state, or
  priority. Idempotent (skip if an identical AI note already exists). Gated behind
  an explicit confirm flag in the demo; write only to a **test** incident.

## Verification
- `MockServiceNowGateway` serves the J7 dataset incident + 2–3 similar resolved
  incidents + a CMDB ownership record.
- JS-2: real `getIncident` reads one incident; real `addWorkNote` posts one note to
  a test incident and is idempotent on re-run.

## Open / risks
- API role / ACL / trigger approval is the main blocker → mock unblocks the demo.
