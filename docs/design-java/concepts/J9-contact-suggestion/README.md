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

Signals from **three** name-bearing sources, all leashed to material the triage already used.
Each source contributes both what its API exposes as structured metadata **and** what its text
says (FND-64 — the free-text half was missing entirely until 2026-07-31):

| Source | Structured (API) | Free text |
|---|---|---|
| **ServiceNow** | author of each comment / work note (`sys_created_by`) | people **named** in the description or comments |
| **Confluence** | page author + last editor(s) (`contributors(doc)`) | people **named in the page body** — a runbook's escalation contact |
| **GitLab** | recent committers to the implicated file, since the last release/tag | — |
| **Sumo** | — | — |

**ServiceNow was contributing nothing at all** before FND-64, which was the biggest gap: the
ticket is where a human has *already written down* who else is involved, and someone engaged
with **this** incident generally beats someone who edited a runbook months ago. **Sumo is
deliberately empty** — log lines carry no identity, and deriving a person from a logger name
would be fabrication.

**Extraction is tuned for precision, not recall** (`MentionedPeople`): a false positive sends
an engineer to bother an uninvolved colleague. Three tiers — emails/`@handles` (unambiguous),
cue-phrase names (`spoke with X`, `owned by X`), then bare capitalised pairs filtered against a
static system-vocabulary denylist **and** the system names on this specific incident (CI,
assignment group, CMDB owner, log emitters). That last filter carries the most weight:
*Payment Service*, *Order Portal* and *Service Desk* all have person-name shape.

## Merge across sources (the ranking)
Merged on **normalised full name** when there is one, falling back to handle for handle-only
records, and ranked by **how many sources corroborate** the person.

> **FND-64 fixed the key here too.** It was handle-else-name, which silently fails when the
> same person arrives with different identifier completeness — now the normal case, since a
> prose mention has no handle while the API record does. The demo showed it immediately: Priya
> Nair appeared twice (`confluence+gitlab` and `servicenow`), Marcus Chen twice. Merging keeps
> whichever record carries the handle, so a contact first seen as a prose mention still ends up
> actionable. Ranking also counts sources now, rather than testing a boolean "contains a `+`"
> that could not tell two sources from three.

The seeded scenario demonstrates it end to end:

```
Priya Nair    [servicenow+confluence+gitlab]  handle=priya.nair@example.com
Marcus Chen   [confluence+gitlab]             handle=marcus.chen@example.com
jane.customer [servicenow]     m.chen [servicenow]     Tom Alvarez [confluence]
```

*Priya* is named in a ticket work note, edited `KB001234`, **and** made the most recent
`reconcile()` commits — three independent sources agreeing.

> **Known limitation.** `m.chen` (a ServiceNow username) and `Marcus Chen` (a display name) are
> almost certainly one person, but resolving that needs a directory lookup this app doesn't
> have. Listing both is honest; silently guessing they match is not.

**Full walkthrough**: [`../../DETERMINISTIC-FLOW.md`](../../DETERMINISTIC-FLOW.md) §3.

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
