# J6 — Knowledge Tools (Confluence · Sumo · GitLab)

**State**: 🟡 Drafted · **Complexity**: Highway (most demo-wow, most care) ·
**Depends on**: J3 · **Carries**: RC3 (log↔code reasoning), S3′ fixture + seed-repo

## Essence
The three "arms" the investigator reaches for **after** it has a hypothesis. Ordered
by the analysis: Confluence (cheap, broad) → Sumo (targeted, bounded) → GitLab
(targeted, last). Each is heavily leashed.

## Confluence (`ConfluenceGateway.search`)
- CQL keyword search → retrieve a **few** highly-relevant pages: app descriptions,
  runbooks, known-error docs, ownership, acronym/system-name interpretation.
- Best-effort: on failure the run degrades gracefully (evidence just omitted).
- Reuse `auspost-mcp` Confluence client.

## Sumo (`SumoGateway.search`) — bounded (the sharpest guardrail)
The agent must **not** invent arbitrary Sumo syntax and run it unrestricted. The app
supplies:
- an **allowlisted** set of `_sourceCategory` scopes,
- a **fixed** time window (derived from incident open time ± delta),
- a **max result count** and **max number of searches**,
- **query templates** (agent fills in the error/correlation id only).
```
_sourceCategory=prod/order-api ("ORD-4031" OR "abc-123") | where _messagetime between …
```
Real impl: Sumo **Search-Job API** (access id/key + correct regional endpoint,
submit→poll→fetch). Mock: **S3′ fixture** (`docs/design/concepts/log-code-reasoning/
verification-s3/sumo-fixture.json`).

## GitLab (`GitLabGateway.searchCode`) — targeted, optional, last
Only when logs/docs yield a concrete term (error code, endpoint, class, message).
Search **one** allowlisted project for the term; return only matching files /
surrounding lines — never clone. Mock: reuse `seed-repo/` (order_api.py,
payment_service.py) which emits the seeded distinctive log line.

## RC3 — Log↔Code reasoning (the wow moment)
The LLM matches a runtime **log line** from Sumo to its **emitting statement** in the
fetched source, citing **file:line** and the execution path — no deterministic
engine, done by the model over the retrieved snippets. This visibly proves the copilot
"consulted all the sources." Trust rule: it must **quote the exact** line it matched.

## Verification
- Mock Sumo returns the seeded line; mock GitLab returns the emitting file; the agent
  produces an evidence item citing `order-api …:<line>` matching the fixture.
- Bounds enforced: a query outside the scope allowlist / over max-results is rejected
  by the `beforeToolCallback` (J8).

## Open / risks
- GitLab intranet reachability (mock covers demo). Sumo query-cost/limits.
- Line-number accuracy — keep the seed file stable; the S3′ verification pinned
  expected lines (43/44) — re-pin if the seed changes.
