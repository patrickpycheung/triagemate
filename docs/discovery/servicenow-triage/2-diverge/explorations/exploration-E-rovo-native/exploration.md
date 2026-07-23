# Exploration E — Rovo-Native, AI-First Triage Agent (detailed)

## 0. Framing

The operator has decided: the agent lives in **Atlassian Rovo**, and the wow is
watching **an AI agent reason** — not a deterministic algorithm. Rovo is the demo
star. This exploration designs that agent, its Actions, its trigger, the AI-first
correlation, the demo, the build effort, and — bluntly — where Rovo-native is
harder than a standalone app.

Round-1 established two load-bearing facts we build on: Rovo reaches non-Atlassian
REST APIs only through **Forge Actions** (Forge functions using `api.fetch` +
`permissions.external.fetch.backend` egress in `manifest.yml`), Confluence is
native, and a Rovo agent **cannot be triggered by an external webhook directly**.
2026 research confirms and sharpens these (see findings.md).

---

## 1. Architecture

### 1.1 The agent

One custom Rovo agent — call it **"Triage Sherlock"** — declared with a Forge
`rovo:agent` module. An agent is just two things: a **prompt** (its instructions /
persona) and a set of **Actions** it is allowed to call. The agent's own LLM
decides which Action to call, when, and how to reason over the results. That
autonomy is the product.

Note a Forge quirk that shapes packaging: **Rovo Actions are only registered if
the app also bundles a `rovo:agent` module.** You cannot ship bare Actions for
other agents to consume without an agent in the app. Fine for us — we ship the
agent and its Actions together.

### 1.2 The Actions (Forge functions)

Each Action is a serverless Forge function. External ones use `api.fetch` and
require an egress entry in `manifest.yml` under
`permissions.external.fetch.backend`. Secrets (PATs, keys) live in Forge
environment variables / storage.

| # | Action key | Purpose | REST under the hood |
|---|-----------|---------|---------------------|
| 1 | `read-ticket` | Read the ServiceNow incident | `GET /api/now/table/incident?sysparm_query=number=INC…` |
| 2 | `fetch-code` | Pull production source from GitLab `master` | `GET /projects/{id}/repository/tree?ref=master` + `…/files/{path}/raw?ref=master` |
| 3 | `query-logs` | Get the Sumo log window (**mocked for demo**) | `POST /search/jobs` → poll → `GET …/messages` |
| 4 | `post-worknote` | Write the root-cause note back | `PATCH /api/now/table/incident/{sys_id}` `{work_notes:…}` |
| 5 *(optional)* | `match-template` | Deterministic Drain3/AST log→line matcher | local compute, no egress |

**Confluence needs no Action** — Rovo's Teamwork Graph searches and summarizes
Confluence natively. The agent just "reads the design/runbook page for PROJ-X" and
Rovo handles retrieval. This is the single biggest Rovo-native advantage: one of
the four sources is free.

Action inputs/outputs are declared in the manifest with a small JSON schema so the
agent's LLM knows how to call them (e.g. `fetch-code(project_code, file_path)`).
Keep schemas tight — the LLM binds to them.

### 1.3 How the prompt drives the sequence

The agent instructions encode the triage playbook so the LLM orchestrates the
Actions in order. Sketch:

> You are a technical triage engineer. Given a ServiceNow incident number:
> 1. Call `read-ticket` to get the description, project code, and failure window.
> 2. Search Confluence (native) for the project's design/runbook page; note the
>    intended behaviour.
> 3. Call `fetch-code` to pull the relevant source files from `master`.
> 4. Call `query-logs` for the failure time window.
> 5. **Reason**: find the runtime log line that marks the failure, locate the exact
>    `log(...)` statement in the fetched source that emits it, and explain the
>    execution path that reached it. Cite `file:line` and quote the matched log
>    line verbatim. You MAY call `match-template` if you want a deterministic
>    confirmation, but reason first.
> 6. Call `post-worknote` with a structured root-cause hypothesis (matched log
>    line, file:line, why, suggested next step, confidence).

The LLM's tool-calling loop does the rest. Crucially, in **chat** the agent can
autonomously call multiple Actions *including the write* — so step 6 (the
post-back) is performed by the agent itself, on stage.

---

## 2. Trigger options, ranked by hackathon build-ease

### (a) Presenter invokes in Rovo chat — RECOMMENDED ✅

Presenter opens the Rovo chat panel and types `Triage INC0012345`. Easiest
possible build (no ServiceNow outbound config, no Automation rule, no webhook
shim) **and** the richest wow: the audience watches the agent autonomously call
`read-ticket`, `fetch-code`, `query-logs`, reason, then `post-worknote` — each
tool call streaming live in the transcript. This is the honest home of the
"AI agent reasoning" pitch, because chat is where Rovo agents can chain Actions
*and perform external writes autonomously*.

### (b) Incoming webhook → Jira/Confluence Automation → "Invoke Rovo Agent" — feasible, weaker ⚠️

ServiceNow (Business Rule / Flow Designer outbound REST) hits an Atlassian
**incoming webhook**, which fires an **Automation rule** whose **"Invoke Rovo
Agent"** action runs Triage Sherlock asynchronously. This is the *only* supported
way to trigger a Rovo agent from an external event, and it's real.

**But the 2026 caveat is decisive for the wow**: *within Automation, Rovo agents
"only return a response — they don't perform actions autonomously."* So the agent
can do the reading + reasoning, but the **write-back** must be done by a
*subsequent Automation step* (a ServiceNow REST call using the agent's returned
smart-value), not by the agent's own `post-worknote` Action. That's more plumbing
and it moves the climactic "the agent posted it itself" moment outside the agent.
Keep this as the **"here's how it becomes fully hands-off in production"** slide,
not the live demo.

### (c) Thin standalone shim invoking Rovo programmatically — NOT feasible as distinct ❌

There is **no public/external API and no headless Forge API to invoke a Rovo agent
and get its response**. Developers have explicitly requested
`api.asApp().requestRovo(agent, prompt)`; it does not exist. The only "external
invoke" path routes through an incoming webhook + Automation — i.e. (c) *is* (b).
The Forge `bridge` `rovo.open()` merely opens the chat sidebar in a Forge UI; it
doesn't run the agent headlessly. Report honestly: (c) collapses into (b).

*(Adjacent, for future awareness: Atlassian's **Remote Agents (EAP)** lets an
external agent be @mentioned/assigned in Jira via an `agentConnector` Forge
middleware — a different pattern aimed at plugging Copilot/Claude/Codex into Jira,
not at triggering a native Rovo agent from ServiceNow. Out of scope for the
hackathon.)*

**Verdict**: demo with (a); narrate (b) as the productionization path.

---

## 3. AI-first correlation — and making it visible

### 3.1 The reasoning

The agent holds two artifacts in context: the **log window** (from `query-logs`)
and the **source files** (from `fetch-code`). Because source code contains the
literal/format strings that produce log lines, the LLM can match a runtime line to
the exact `log(...)` statement that emitted it, then read the surrounding
control-flow to explain *why* that branch was reached. No Drain3 required — the LLM
does semantic correlation directly. The optional `match-template` Action exists
only if the presenter wants to show a deterministic cross-check.

### 3.2 Making it convincing on stage

The wow is not the answer — it's watching the agent *get* there. Rovo chat already
surfaces tool calls, so the transcript naturally shows:

- `→ calling read-ticket…` `→ calling fetch-code(payment-svc)…` `→ calling
  query-logs(14:20–14:40)…` — proof it really consulted all sources.
- Then the narration, e.g.:
  > *"The last log before failure is `ERROR Order %d failed to charge:
  > insufficient funds`. That exact format string is emitted at
  > `payment_service.py:87`, inside the `except InsufficientFunds` branch. That
  > branch is only reached when `charge()` raises — and the design page says
  > declines should fall back to a retry queue, which this code path skips. Root
  > cause: unhandled decline, no retry enqueue."*

Every claim is anchored to a real quoted log line, a real `file:line`, and a real
Confluence page. That specificity is what defeats the judges' "it just
hallucinated" reflex. Then `→ calling post-worknote…`, and the note materializes on
the ServiceNow ticket.

---

## 4. Demo storyboard (2–3 min, AI-first)

**Beat 1 — Set the scene (20s).** ServiceNow ticket `INC0012345` on screen:
*"Checkout failing for some orders."* Presenter: "Normally an engineer now spends
30 minutes gathering context. Watch our Rovo agent do it." Opens the Rovo chat
panel.

**Beat 2 — Invoke (10s).** Types `Triage INC0012345`. Agent calls `read-ticket`
(visible), echoes back the project code and failure window it extracted.

**Beat 3 — Autonomous gather (30–40s).** Agent streams `fetch-code`,
`query-logs`, and a native Confluence search — three/four sources, live, no human
input. The audience sees it *working*.

**Beat 4 — The reasoning wow (40–50s).** Agent narrates the log→code correlation:
quotes the matched log line, names `payment_service.py:87`, explains the execution
path, cites the Confluence design note it contradicts. This is the star moment.

**Beat 5 — Conclude (20s).** Agent calls `post-worknote`. Presenter flips to the
ServiceNow tab, refreshes — the structured root-cause work-note is there, with the
log line, the file:line, the reason, and a next step. "That's what the engineer
sees when they open the ticket."

**Live vs pre-seeded**:
- **Live**: the Rovo agent's reasoning + tool calls, `fetch-code` (GitLab is
  reliable), native Confluence, and `post-worknote` (single write, low risk).
- **Pre-seeded**: `query-logs` returns a **mock** (Sumo's async polling + 4 req/s
  limit + Forge's ~25s function timeout make live Sumo fragile); the demo GitLab
  repo with a **seeded bug**; the ticket pre-created.

---

## 5. Build effort + risks + fallbacks

### Effort: ~2–3 days (Forge-new); ~1–1.5 days (Forge-familiar)

| Chunk | Est. |
|-------|------|
| Forge app scaffold + `rovo:agent` module + deploy/install to a Rovo-enabled site | 0.5 day |
| 4 Actions (schemas + `api.fetch` + manifest egress + secrets) | 1 day |
| Agent prompt tuning so it reliably chains Actions in order | 0.5 day |
| Mock `query-logs`, seed GitLab repo + bug, rehearse | 0.5–1 day |

### Top risks

1. **Forge learning curve (biggest).** Manifest, tunnel, deploy/install cycle,
   remote-log debugging — all slower than a local backend. Mitigate: start from
   Atlassian's "hello world Rovo agent" template day 1.
2. **Action auth / secret storage.** Corporate PATs (GitLab), Sumo keys,
   ServiceNow creds in Forge env vars; egress allowlist must list every host.
   Mitigate: wire and test one Action end-to-end before building the rest.
3. **Forge ~25s function timeout vs Sumo async polling.** A poll-until-done search
   job can exceed it → **mock `query-logs`** (also removes rate-limit + network
   risk). This is the strongest technical reason to fake Sumo.
4. **Agent skipping/ reordering steps.** LLM orchestration is probabilistic.
   Mitigate: explicit numbered playbook in the prompt; the presenter can nudge
   with a natural follow-up chat message if a step is missed (still looks natural).
5. **Autonomous external write.** Fine in chat; confirm the `post-worknote` Action
   works with the integration user's `rest_service` role before demo day.

### Fallbacks

- Mock `query-logs`; pre-cache the GitLab file if the API hiccups.
- If `post-worknote` fails live, the agent has already *drafted* the note in chat —
  show that and paste manually.
- If the agent misfires mid-demo, re-invoke with a slightly more explicit prompt;
  chat makes a retry look like normal use.

---

## 6. Honest caveats (Rovo-native vs standalone)

- **The trigger is manual.** The demo's "autonomy" is the agent's multi-step
  reasoning + Actions, *not* auto-on-ticket-creation. The literal
  ticket-creation→auto-triage story only exists via path (b), and (b) can't have
  the agent post the note itself (Automation agents "only return a response"). Be
  upfront: chat-invoked demo, webhook-triggered future.
- **No headless Rovo invoke.** You cannot script the agent from outside Atlassian;
  everything external routes through webhook→Automation. A standalone app has no
  such constraint.
- **Forge iteration is slower.** Deploy/tunnel/remote-logs vs local `console.log`.
  Budget for it.
- **Sumo inside a Forge function is awkward** (timeout + async + rate limit) — the
  one source that's genuinely easier in a standalone backend.
- **Requires a Rovo-enabled Atlassian Cloud site** (paid add-on) with admin rights
  to install a Forge app. Confirm the org has this *before* committing.
- **Actions can't ship without a bundled agent module** — minor packaging
  constraint, but real.

**Net**: Rovo-native is the right call *for this operator's goal* — the AI visibly
living in Rovo, reasoning across free-native Confluence + custom Actions, is a
better story than a black-box backend. The price is a manual trigger and a slower
build loop. Worth it for the demo.
