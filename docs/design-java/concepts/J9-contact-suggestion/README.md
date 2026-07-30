# J9 — Contact Suggestion ("Who to talk to")

**State**: 🟢 Built · **Complexity**: Simple · **Depends on**: J4, J6 ·
**Carries**: reuses the evidence J6 already gathered — no new source system ·
**DDS provenance**: **none** — see the note below

> **Provenance note** (added 2026-07-30 by `/doc-test dds`, gap G6). Unlike every other
> J-concept, **J9 was never extracted by any DDS** — the id `J9` appears in no
> `docs/discovery/**` file. J1–J8 trace to `servicenow-triage-java/4-decide/concepts-extracted.md`
> and J10 to `servicenow-local-trigger/4-decide/concepts-extracted.md`, but J9 originated
> directly in implementation (FND-21/FND-2, reusing `ConfluenceGateway.contributors` and
> `GitLabGateway.recentCommitters`) and was back-filled as a card. `docs/design-java/STATUS.md`
> names three source DDS workspaces, which slightly over-claims for this one concept.
> Not a defect in the design — it is a small, deliberate, RAPID-rigor shortcut — but recorded
> so the graduation register reconciles instead of showing a phantom source.

## Essence
After the triage has gathered its evidence, it also surfaces **who to talk to** about
the incident. This is not a fresh people-search: it is derived from the **same
evidence already cited**, so every suggested contact is one the run can justify.

Two signals, both leashed to material the triage already used:

- **Confluence** (`ConfluenceGateway.contributors(doc)`) — the **author and last
  editor(s)** of the runbook pages J6 returned. Someone who wrote or recently updated
  the known-error runbook has direct context.
- **GitLab** (`GitLabGateway.recentCommitters(project, file)`) — the **recent
  committers** to the implicated source file, from git history **since the last
  release/tag**. Whoever last changed the emitting code is the person to ask.

## Merge across sources (the ranking)
Contacts are merged by handle (email, falling back to name). Someone who **both**
edited a cited runbook **and** recently committed the implicated file collapses into a
single `confluence+gitlab` contact and is ranked **first** — the strongest signal.
The seeded scenario demonstrates this: *Priya Nair* edited `KB001234` and made the
most recent `reconcile()` commits, so she surfaces at the top over the single-source
contacts (*Tom Alvarez*, wiki author; *Marcus Chen*, other committer).

## Where it flows
- **J4 report**: new `suggestedContacts: List<Contact>` field
  (`name, handle, source, reason, link, signal`).
- **J7 UI**: a "Who to talk to" card, cross-source contacts first.
- **J2 ADK loop**: two extra FunctionTools — `find_page_contributors`,
  `find_recent_committers` — the agent may call only for pages/files it already cited
  (step 7 of the instruction).

## Guardrails (J8)
- **Display-only / advisory**: contacts are shown in the triage UI and returned in the
  JSON, but are **deliberately NOT** posted into the ServiceNow ticket — names and
  emails are not pushed into the record.
- **No broad people-search**: both gateway methods are called only for evidence the
  triage already used (a returned page, a tied file), never a directory scan.
- **Best-effort**: the real connectors return an empty list on any error, so the run
  degrades gracefully (the rest of the diagnosis is unaffected).

## Real connectors (JS-2)
- Confluence: content REST with `expand=history,version` → `version.by` (last editor)
  and `history.createdBy` (author).
- GitLab: newest tag → its `committed_date`, then
  `commits?path={file}&since={date}`, de-duplicated by author email with a commit
  count since that tag.
