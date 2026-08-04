# J6 — Knowledge Tools (Confluence · Sumo · GitLab)

**State**: 🟢 Built · **Complexity**: Highway (most demo-wow, most care) ·
**Depends on**: J3 · **Carries**: RC3 (log↔code reasoning), S3′ fixture + seed-repo

## Essence
The three "arms" the investigator reaches for **after** it has a hypothesis. Ordered
by the analysis: Confluence (cheap, broad) → Sumo (targeted, bounded) → GitLab
(targeted, last). Each is heavily leashed. Two of these gateways also back **J9**'s
"who to talk to" suggestion — `ConfluenceGateway.contributors` and
`GitLabGateway.recentCommitters` (FND-21) — reusing the same evidence this app
already gathered rather than a fresh people-search.

## Confluence (`ConfluenceGateway.search`)
- **Real** (`RealConfluenceGateway`): genuine **CQL** (Confluence Query Language) —
  builds `text ~ "<query>"` and posts it as the `cql` param to
  `/wiki/rest/api/content/search` (re-verified 2026-07-30 against
  `RealConfluenceGateway.java:41`: the FND-29 fix landed in J3's doc on 2026-07-30
  but was never propagated to this card — this file kept the stale "both do keyword
  matching" claim until now). **Mock** (`MockConfluenceGateway`): plain in-memory
  keyword matching over a small fixture set — deliberately simpler, no CQL syntax
  involved, since it isn't querying a real Confluence. Either way → retrieve a
  **few** highly-relevant pages: app descriptions, runbooks, known-error docs,
  ownership, acronym/system-name interpretation.
- Best-effort: on failure the run degrades gracefully (evidence just omitted).
- Reuse `auspost-mcp` Confluence client.

## Sumo (`SumoGateway.search`) — bounded (the sharpest guardrail)
The agent must **not** invent arbitrary Sumo syntax and run it unrestricted. The app
enforces, server-side in `TriageMateTools.searchLogs` (FND-20, fixed 2026-07-30 — the
window and result count were previously accepted from the model unchecked):
- an **allowlisted** set of `_sourceCategory` scopes (`triage.sumo.allowed-scopes`),
- a time window **clamped** to `triage.sumo.max-window-minutes` (default 30), anchored
  on the requested end time — a too-wide request still searches the most recent
  relevant slice, not nothing,
- a **max result count** (`triage.sumo.max-results`, default 20), passed through to
  both `Mock`/`RealSumoGateway`.

Not yet true, flagging rather than leaving silently implied: there is no cap on the
**number** of `search_logs` calls specifically (only the overall tool-call budget,
J1/J2), and the **query text itself** is free-form model input, not a constrained
template — only the scope and window are structurally bounded.
```
_sourceCategory=prod/order-api ("ORD-4031" OR "abc-123") | where _messagetime between …
```
Real impl: Sumo **Search-Job API** (access id/key + correct regional endpoint,
submit→poll→fetch). Mock: **S3′ fixture** (`docs/design/concepts/log-code-reasoning/
verification-s3/sumo-fixture.json`).

## GitLab (`GitLabGateway.searchCode`) — targeted, optional, last
Only when logs/docs yield a concrete term (error code, endpoint, class, message).
Search **one** allowlisted project for the term (FND-38, fixed 2026-07-30: this was
documented as enforced but `search_code` previously accepted any model-supplied
project string unchecked — same class of gap as FND-20's Sumo bound, same fix shape:
`triage.gitlab.allowed-projects`, checked server-side in `TriageMateTools.searchCode`,
not by `beforeToolCallback`); return only matching files / surrounding lines — never
clone. Mock: reuse the seed repo (`order_api.py`,
`payment_service.py`), which lives at `docs/design/concepts/log-code-reasoning/
verification-s3/seed-repo/` (FND-26: not repo-root `seed-repo/` — moved into the
suspended Rovo-era CDS during the pivot) and emits the seeded distinctive log line.

## RC3 — Log↔Code reasoning (the wow moment)
The LLM matches a runtime **log line** from Sumo to its **emitting statement** in the
fetched source, citing **file:line** and the execution path — **on the agentic path this
needs no hand-written correlation engine**, the model does it over the retrieved snippets.
This visibly proves the copilot "consulted all the sources." Trust rule: it must **quote
the exact** line it matched.

> **Scope note (FND-5).** "No deterministic engine" describes *how the agent does it*, not
> a capability only the agent has. `DeterministicDiagnosisEngine` produces the same
> `file:line` citation with **no LLM at all** (see its `Evidence` construction), and it is
> both the app default and **D2**, the on-stage fallback. That matters: if the fallback
> could not do log↔code citation, degrading to it would silently drop the evidence trail
> that is the whole demo. It can. The difference is *how* the match is made — scripted
> pattern-matching over the fixture universe vs. the model reasoning over retrieved
> snippets — and how well it generalises beyond the seeded bug, not whether a citation
> appears.

## Verification
- Mock Sumo returns the seeded line; mock GitLab returns the emitting file; the agent
  produces an evidence item citing `order-api …:<line>` matching the fixture.
- A query outside the scope allowlist is rejected in `TriageMateTools.searchLogs`
  itself (not `beforeToolCallback` — that layer only enforces the tool allowlist and
  call budget, J2/J8). Window clamping and result capping: `TriageMateToolsSearchLogsTest`
  (FND-20) — a too-wide window is clamped anchored on the end time, and the configured
  `max-results` (not a hardcoded value) is what actually reaches the gateway.
- A GitLab project outside `triage.gitlab.allowed-projects` is rejected in
  `TriageMateTools.searchCode` the same way (FND-38):
  `outOfAllowlistGitLabProjectIsRejected` / `allowlistedGitLabProjectIsPassedThrough`
  in `TriageMateToolsSearchLogsTest`.

## Open / risks
- GitLab intranet reachability (mock covers demo). Sumo query-cost/limits.
- Line-number accuracy — keep the seed file stable; the S3′ verification pinned
  expected lines (43/44) — re-pin if the seed changes.
