# ServiceNow Triage Assistant

An Atlassian Forge Rovo-agent app (hackathon prototype). Given a ticket id
(e.g. "Triage INC0012345"), the agent:

1. reads the ServiceNow ticket,
2. pulls the current GitLab `master` source for the affected service,
3. pulls the failure-window log lines (mock Sumo fixture, bundled),
4. best-effort checks Confluence for a known-issues page,
5. correlates the log's ERROR line to the exact source line that emits it
   (quoting both, with a self-check to catch near-misses),
6. drafts a work-note with the root-cause hypothesis, and
7. **only after an explicit "yes" from the user** posts it to the ticket.

See `src/playbook.md` (also inlined via `manifest.yml`'s `prompt: file://src/playbook.md`)
for the full agent procedure, and `docs/design/concepts/` for the design
rationale (C1–C6).

## Layout

- `manifest.yml` — Forge app manifest: `nodejs24.x` runtime, `rovo:agent`
  module, 4 `action`/`function` pairs, exactly two egress backends
  (ServiceNow + GitLab — Sumo is mocked, not a live backend), and the
  `read:confluence-content.summary` scope for best-effort runbook lookup.
- `src/index.js` — the 4 Forge function handlers (`getTicket`, `getSource`,
  `getLogs`, `postWorknote`).
- `src/playbook.md` — the Rovo agent's playbook prompt.
- `src/resources/sumo-fixture.json` — bundled mock Sumo Logic response for
  the seeded order `INC-ORD-4471` (copied from
  `docs/design/concepts/log-code-reasoning/verification-s3/sumo-fixture.json`).
- `seed-repo/` — `payment_service.py` + `order_api.py`, the seeded demo repo.
  **The operator pushes these to the real GitLab project** so `getSource`
  has something real to fetch on `master`.
- `test/` — `node --test` unit tests with a mocked `@forge/api`.

## Deploying (operator steps — requires the Forge CLI, not available in this build environment)

```bash
npm install -g @forge/cli   # if not already installed
forge login

npm install                 # pulls the real @forge/api / @forge/resolver
                             # (this repo ships a local test-only stub at
                             # node_modules/@forge/api — npm install replaces it)

forge lint                  # validate manifest + code
forge deploy

forge variables set --encrypt SN_TOKEN
forge variables set --encrypt GITLAB_TOKEN
# (SUMO_KEY not needed — getLogs reads the bundled fixture, not live Sumo)

forge install                # install the app onto your Rovo/Jira site
```

Then in Rovo chat: `Triage INC0012345` (the seeded ticket / order
`INC-ORD-4471`).

Before deploying, push `seed-repo/` to the GitLab project referenced by
`GITLAB_PROJECT_ID` / the manifest's GitLab egress domain, and seed a
ServiceNow incident `INC0012345` whose description references order
`INC-ORD-4471`.

## Testing (this environment — no Forge CLI)

```bash
npm test
# -> node --test, all handlers exercised against a mocked @forge/api
```

This build environment has **no Forge CLI**, so `forge lint` / `forge
deploy` were **not run here** — the manifest was only checked for valid
YAML syntax, and the handlers were only checked with `node --test`. The
operator must run `forge lint` and `forge deploy` before this is a live
Rovo agent.

## Honest-autonomy caveat

This is a hackathon prototype, not a production triage system:

- **`getSource` returns a fixed seeded file set**, not real relevance
  selection — the grounding guarantee only holds for the seeded project.
- **`getLogs` reads a bundled fixture**, not live Sumo Logic — the failure
  window is pre-scoped, not derived from ticket time + heuristics.
- The log↔code correlation is **LLM reasoning, not a deterministic
  matcher** (no Drain3/AST engine). It's made trustworthy by the playbook's
  quote-both + self-check + degrade-not-invent rules, but on a real repo
  with non-unique log strings it could mis-rank — flagged, not solved, here.
- The agent **never writes autonomously**: `postWorknote` refuses unless
  `confirmed===true`, and the playbook only sets that after showing the
  exact draft and getting an explicit "yes." On a network error it reports
  "unknown outcome" rather than retrying, to avoid a duplicate work-note.
