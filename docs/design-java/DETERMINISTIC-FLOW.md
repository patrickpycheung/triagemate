# How the deterministic engine searches

What `DeterministicDiagnosisEngine` actually does, step by step: which fields it reads from
the incident, how it turns them into queries for Confluence / Sumo / GitLab, and how it
decides what the report says. Written so the logic can be reviewed without reading the code.

**Companion to:** [J1](concepts/J1-spring-boot-orchestrator/README.md) (orchestrator),
[J2](concepts/J2-adk-agent-loop/README.md) (the two engines and why both exist),
[J9](concepts/J9-contact-suggestion/README.md) (who-to-talk-to).
**Code:** `DeterministicDiagnosisEngine`, `IncidentSignals`, `MentionedPeople`.

---

## The one idea

> **Deterministic means predictable, not hardcoded.**

Both engines answer the same question — *what should we ask each platform?* The only
difference is **who decides**:

| | ADK engine (`triage.engine=adk`) | Deterministic engine (default) |
|---|---|---|
| Who picks the queries | the model, per run | fixed rules over the same ticket |
| Same input → same queries | not guaranteed | **guaranteed** |
| Needs network / LLM | yes | **no** |
| Cost per attempt | one LLM call (J8 budget: 10) | none |

Until 2026-07-31 this engine was reading "deterministic" as "hardcoded": a fixed Confluence
query string, `allowedScopes.get(0)` regardless of the incident, a literal GitLab project, and
report text written for the one seeded demo incident (FND-59/62/63). Everything below is now
derived from the ticket.

This matters beyond tidiness: **this engine is also the FND-7 fallback.** When the ADK engine
fails, the orchestrator degrades to this one — so "an incident that isn't the demo one" is
exactly the case it runs in for real.

---

## Step 0 — What we read from ServiceNow

One `GET /api/now/table/incident`, with an explicit `sysparm_fields` list (ServiceNow returns
*only* requested fields — FND-47 was caused by reading a field that was never asked for):

```
sys_id, number, short_description, description, caller_id,
category, subcategory, opened_at, cmdb_ci, assignment_group, u_environment
```

Plus two follow-up queries against `sys_journal_field` for `comments` and `work_notes`
(FND-61 — journal entries don't live on the incident row).

| ServiceNow field | `IncidentContext` | Used for |
|---|---|---|
| `number` | `number` | report id, evidence link |
| `short_description` | `shortDescription` | keywords, symptom, affected-function fallback |
| `description` | `description` | keywords, identifiers, names |
| `cmdb_ci` | `configurationItem` | **affected app** → allowlist ranking, ownership lookup |
| `assignment_group` | `currentAssignment` | denylist for name extraction |
| `category` / `subcategory` | same | `affectedFunction` |
| `opened_at` | `openedAt` | log search window (±10 min) |
| `u_environment` | `environment` | report field |
| `caller_id` | `caller` | (carried, not currently queried on) |
| `comments`, `work_notes` | `comments`, `workNotes` | **names**, keywords |
| — | `reassignmentHistory` | **not wired** — needs `sys_audit` |

---

## Step 1 — Extract signals from the ticket (`IncidentSignals`)

Everything downstream is built from these three derivations.

### Identifiers
Matched against the ticket text, **most specific pattern first**, deduped in order:

| Shape | Pattern | Example |
|---|---|---|
| Dashed id | `[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\d{3,}` | `INC-ORD-4471`, `ORD-1234`, `BATCH-778812` |
| UUID | standard 8-4-4-4-12 | `f47ac10b-…` |
| Hex trace | `[0-9a-f]{16,32}` | `3f2a1b4c5d6e7f8a…` |

The incident's **own number is excluded** — it identifies the ticket, not the failing
transaction. The first match becomes `primaryIdentifier`, which is the log-search term.

> Previously this was a single regex for `INC-ORD-\d+` — the demo fixture's exact shape.
> Every other incident fell through to searching logs for the literal string `"error"`.

### Keywords
Ticket text → split on non-alphanumerics → lowercase → drop tokens shorter than 4 chars,
pure digits, and **function words** (`the`, `with`, `sometimes`, `reported`, …) → dedupe,
first-appearance order, cap 8.

**Domain words are deliberately kept.** `error`, `order`, `payment`, `checkout` are precisely
what a runbook search needs; stripping them to look clever would defeat the purpose.

### Affected app
`configurationItem` when set (the CMDB's structured answer), else the symptom line. Used to
rank platform allowlists and as a denylist entry for name extraction.

---

## Step 2 — Query construction, per platform

### Confluence — `search(query)`
```
query = <keywords joined by space> + " " + <affected app>
```
Real gateway sends CQL `text ~ "<query>"`, `limit=5`, `expand=body.view,space`.

Demo: `"orders through checkout customers submit order fails error Order Portal"`

### Sumo — `search(scope, query, from, to, maxResults)`

| Parameter | Derivation |
|---|---|
| `scope` | allowlist **ranked then swept** (below) |
| `query` | `primaryIdentifier`, else top-3 keywords, else `"error"` |
| `from`/`to` | `openedAt` ± 10 minutes |
| `maxResults` | `triage.sumo.max-results` (20) |

### GitLab — `searchCode(project, searchTerm)`

| Parameter | Derivation |
|---|---|
| `project` | allowlist **ranked then swept** |
| `searchTerm` | the error token — `[A-Z][A-Z0-9]*(_[A-Z0-9]+)+` — from the first ERROR log line |

Only runs if a log line yielded an error token; otherwise there's nothing to tie code to.

### How a scope / project gets chosen: **rank, then sweep**

1. **Rank** every configured allowlist entry by token overlap with the affected app.
   `Ledger Export Service` → `prod/ledger` first. Ranking never *drops* an entry.
2. **Sweep** in that order, stopping at the first entry that yields a result (an ERROR line
   for Sumo; any hit for GitLab).

**Why sweep rather than just pick the best-ranked?** Because ranking is a guess, and the demo
incident is exactly where the guess is wrong: the CI says *Order Portal* while the failure is
downstream in *Payment Service* — unguessable from the ticket, which is the entire point of
running a diagnosis. A single wrong pick silently loses the evidence.

**Why can we afford to sweep?** Because this isn't the agent. The allowlist is small and
bounded by config, and there's no per-call LLM budget. The agent pays a call per attempt
against a hard J8 budget of 10, so it *must* choose — which is why FND-60 makes the allowlisted
values visible to it in the prompt. Same question, different economics, different answer.

---

## Step 3 — Who to talk to (`MentionedPeople`, J9)

Names come from **three** sources — API metadata *and* free text. Sumo contributes nothing:
log lines carry no identity, and inventing one from a logger name would be a fabrication.

| Source | Structured (API) | Free text |
|---|---|---|
| **ServiceNow** | journal author of each comment / work note (`sys_created_by`) | names in `description` + comments |
| **Confluence** | page author + last editor | names in the page body (a runbook's "escalation contact") |
| **GitLab** | recent committers to the implicated file | — |
| **Sumo** | — | — |

### Extracting a name from prose, without false positives
The risk is precision: a wrong name sends an engineer to bother an uninvolved colleague, and
`Payment Service` / `Order Portal` / `Service Desk` all have person-name shape. Three tiers:

1. **Emails and `@handles`** — unambiguous, no filtering needed.
2. **Cue-phrase names** — `spoke with X`, `escalated to X`, `owned by X`, `cc X`. High precision.
3. **Bare capitalised pairs** — filtered against (a) a static denylist of system/team/process
   vocabulary, and (b) **the system names on this specific incident** (CI, assignment group,
   CMDB owner, log emitters). (b) matters most: those are the exact system-shaped phrases this
   ticket is about, so they're the likeliest false positives.

### Merging
Keyed on **normalised full name** when there is one, falling back to handle for handle-only
records. This matters because the same person arrives with different completeness: a prose
mention has no email, the API record does. Merging keeps the handle so the contact stays
actionable.

Ranked by **number of corroborating sources**, descending. Demo output:

```
Priya Nair    [servicenow+confluence+gitlab]  handle=priya.nair@example.com
Marcus Chen   [confluence+gitlab]             handle=marcus.chen@example.com
jane.customer [servicenow]
m.chen        [servicenow]
Tom Alvarez   [confluence]
```

Priya is named in a ticket work note, edited the runbook, *and* committed the implicated file —
three independent sources agreeing is the strongest signal available.

> **Known limitation.** `m.chen` (a ServiceNow username) and `Marcus Chen` (a display name) are
> almost certainly the same person, but resolving that needs a directory lookup we don't have.
> Listing both is honest; silently guessing they match would not be.

---

## Step 4 — What the report says

Every field is derived from the run. Nothing is written for a particular incident.

| Field | Derivation |
|---|---|
| `reportedSymptom` | `shortDescription — description` |
| `affectedFunction` | `category / subcategory`, else `shortDescription` |
| `candidateSystems` | log emitters (prettified: `payment_service` → *Payment Service*) + CMDB owner. Confidence **0.86** with a log↔code citation, **0.70** ERROR-log-backed, **0.45** seen in logs, **0.55** CMDB-only, **0.30** nothing observed |
| `suggestedAssignment` | CMDB support group; else the resolution group of a similar incident (LOW); else explicitly unassigned |
| `evidence` | the ticket itself + similar incidents + CMDB + pages + log line + code hit |
| `contradictingEvidence` | CMDB owner named but absent from the logs → say so |
| `missingInformation` | no identifier / no logs / no runbook / no environment / no comments |
| `recommendedNextAction` | the resolved `file:line`, else the error token, else widen the window, else reproduce |
| `confidenceOverall` | MEDIUM, `advisory: true` always |

**`evidenceRefs` are filtered against evidence actually gathered**, so a dangling reference is
impossible by construction. This was FND-63: two refs were literal demo-fixture ids
(`e-kb-KB001234`, `e-sim-INC0011902`), so for any other incident the J4 validator threw — the
orchestrator would degrade to the fallback and get a **500** out of it. The fallback couldn't
fall back.

**The ticket itself is cited as evidence** (`e-incident`). Without it, an incident where all
four other sources return nothing has zero evidence and can't produce a valid J4 report at all
— and "here is what the ticket says, nothing corroborated it" is an honest triage outcome,
whereas failing to produce a report is not.

---

## Worked example — the demo incident

`POST /api/diagnose/INC0010005`, all connectors `mock`, no network. Real trace:

```
servicenow.getIncident(INC0010005) → CI=Order Portal, env=Production
understand: id=INC-ORD-4471, keywords=[orders, through, checkout, customers,
            submit, order, fails, error], app=Order Portal
servicenow.findSimilarIncidents → 2 hits
servicenow.findOwnership(Order Portal) → Payments Platform Support
confluence.search(query="orders through checkout customers submit order fails
            error Order Portal") → 1 page(s)
sumo.search(query="INC-ORD-4471", scopes=[prod/order-api, prod/payment]
            → prod/order-api, window=±10m, max=20) → 4 line(s);
            errorToken=PAYMENT_RECONCILE_MISMATCH
gitlab.searchCode(term='PAYMENT_RECONCILE_MISMATCH',
            projects=[order-payments/payment-service]) → 1 hit(s) (log↔code citation)
contacts: 5 suggested (from 1 doc(s) + 1 code file(s), merged across sources)
```

Result: **Payment Service 0.86** / Order Api 0.70 / Order Portal 0.55, assigned to *Payments
Platform Support*, next action *"Review payment_service.py:44, which emits
'PAYMENT_RECONCILE_MISMATCH'"*.

Note `Order Api` — a genuine third candidate that really is in the logs, which the old
hardcoded two-candidate list omitted. The narrative is unchanged from the demo we always told;
it is now *derived* rather than asserted.

---

## Limits — what this engine deliberately does not do

- **No synonym or semantic expansion.** Keywords are literal tokens from the ticket. A runbook
  titled "Checkout failures" won't match a ticket saying "basket won't submit". That's the
  agent's job.
- **No re-querying on a miss.** One Confluence query, one sweep each for logs and code. It does
  not read a result and go looking again.
- **Fixed step order.** Always the same seven steps; it can't decide the runbook made step 5
  unnecessary.
- **`reassignmentHistory` is never populated** (needs `sys_audit`).
- **Username ↔ display name is not resolved** (see the J9 limitation above).

These are the acceptable cost of "no LLM, no network, always works, same answer every time" —
which is exactly what a stage demo and a fallback path both need. When they bite, that's the
argument for `triage.engine=adk`, not for making this engine cleverer.
