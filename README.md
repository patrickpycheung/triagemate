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

## Setup & deploy (from a fresh clone, step by step)

This is the full sequence to go from a clean checkout on a new machine to a
live Rovo agent. Steps marked **(interactive)** prompt for input and must be
run by hand in a terminal.

### Prerequisites

- **Node.js 18+** (this app was validated on Node v22). Check with `node --version`.
- An **Atlassian account** on a site with Rovo enabled.

### 1. Install the Forge CLI (global npm package)

```bash
npm install -g @forge/cli
forge --version          # confirm it's on PATH
```

### 2. Log in to Forge — **(interactive)**

`forge login` needs your Atlassian email and an **API token** (not your
password).

1. Create a token at <https://id.atlassian.com/manage-profile/security/api-tokens>
   → **Create API token** → copy it.
2. Run:

```bash
forge login
# prompts for: Atlassian account email, then the API token
```

**Verifying your token (optional).** If `forge login` fails and you want to
check the email/token pair in isolation, hit the same auth endpoint the CLI
uses. A `200` with your user details means the credentials are good; a `401`
or `"user": null` means the token is bad, expired, or paired with the wrong
email:

```bash
curl -s -u 'YOUR_EMAIL:YOUR_API_TOKEN' \
  -H 'Content-Type: application/json' \
  -X POST https://api.atlassian.com/graphql \
  -d '{"query":"query forge_cli_getUserDetails { me { user { name accountStatus accountId } } }"}'
```

Expected response:

```json
{ "data": { "me": { "user": {
  "name": "...", "accountStatus": "active", "accountId": "557058:..." } } } }
```

The equivalent in Postman: `POST https://api.atlassian.com/graphql`,
**Authorization → Basic Auth** (username = email, password = API token),
header `Content-Type: application/json`, and a raw-JSON body with the `query`
field above.

### 3. Install dependencies

```bash
npm install              # pulls the real @forge/api / @forge/resolver.
                         # This repo ships a local test-only stub at
                         # node_modules/@forge/api — npm install replaces it.
```

### 4. Validate

```bash
forge lint               # validate manifest.yml + code
```

### 5. Deploy

```bash
forge deploy             # deploys to the default (development) environment
```

The **first** `forge deploy` registers the app and assigns it an app ID
(written into `manifest.yml` as `app.id`). Commit that change so the next
machine reuses the same app.

### 6. Set the encrypted secrets — **(interactive)**

Each command prompts for the value; the secret is entered at the prompt, not
passed on the command line.

```bash
forge variables set --encrypt SN_TOKEN        # ServiceNow API token
forge variables set --encrypt GITLAB_TOKEN    # GitLab personal access token
# (SUMO_KEY not needed — getLogs reads the bundled fixture, not live Sumo)
```

Re-run `forge deploy` after changing variables so the new values take effect.

### 7. Install the app onto your site — **(interactive)**

```bash
forge install            # pick product (Jira) + enter your site URL when prompted
```

### 8. Seed the demo systems (before the first triage)

- Push `seed-repo/` (`payment_service.py`, `order_api.py`) to the GitLab
  project referenced by the manifest's GitLab egress domain /
  `GITLAB_PROJECT_ID`, on the `master` branch, so `getSource` can fetch real
  source.
- Create a ServiceNow incident **`INC0012345`** whose description references
  order **`INC-ORD-4471`**.
- (Optional) Create a Confluence "payments known issues" runbook page for the
  best-effort lookup.

### 9. Run the demo

In Rovo chat:

```
Triage INC0012345
```

(the seeded ticket / order `INC-ORD-4471`).

### Re-deploying after code changes

```bash
forge deploy             # push new code; installed sites pick it up automatically
```

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
