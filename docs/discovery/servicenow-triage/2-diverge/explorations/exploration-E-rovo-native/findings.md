# Exploration E — Findings

Trust legend: 🔬 spiked/verified · 📚 documented · 🔍 inferred · 🤔 hypothesis · ❓ unknown

## Platform capability

- 🔬 **Rovo reaches external REST only via Forge Actions** — Forge functions using
  `api.fetch`, with per-host egress declared in
  `manifest.yml → permissions.external.fetch.backend`. Secrets via Forge env vars.
  *Risk: low — well documented.*
- 🔬 **Confluence is native** to Rovo (Teamwork Graph) — no Action needed; one of
  four sources is free. *Risk: none — this is a genuine Rovo advantage.*
- 🔬 **Rovo Actions require a bundled `rovo:agent` module** — you can't ship bare
  Actions for others' agents without an agent in the app. *Risk: low — we bundle.*
- 📚 **In Rovo chat, the agent autonomously chains multiple Actions AND can perform
  external writes** (the `post-worknote` PATCH runs from the agent itself). *Risk:
  medium — validate the write Action's permissions pre-demo.*

## Trigger

- 🔬 **A Rovo agent cannot be invoked by an external webhook directly, and there is
  NO headless/external API to run it and get its response.** Requested
  (`requestRovo(...)`) but does not exist. *Risk: high if you assumed otherwise —
  it kills path (c).*
- 🔬 **The only external trigger is: incoming webhook → Jira/Confluence Automation →
  "Invoke Rovo Agent" action.** ServiceNow (Business Rule/Flow) posts to the
  webhook. *Risk: medium build; feasible.*
- 📚 **Within Automation, the agent "only returns a response — it does not perform
  actions autonomously."** So the write-back must be a *subsequent Automation
  step*, not the agent's own Action. *Risk: high for the wow — the climactic
  "agent posted it itself" moment can't live in path (b).*
- 🔍 **Chat-invoke (path a) is both easiest to build and the fullest autonomous
  wow.** Recommended for the demo. *Risk: low; only caveat is the trigger is
  manual.*
- 📚 **Remote Agents (EAP)** is a separate pattern (external agent @mentioned in
  Jira via `agentConnector` middleware) — not a ServiceNow-trigger path; out of
  scope. *Risk: n/a — noted for awareness.*

## AI-first correlation

- 🤔 **The agent's LLM can match a runtime log line to its emitting `log(...)`
  statement and localize `file:line` by reasoning over the log window + fetched
  source**, no Drain3 needed. Format strings in code are the anchor. *Risk: medium
  — accuracy depends on the seeded case; pick a clean one for the demo.*
- 🔍 **Deterministic matcher (Drain3/AST) demoted to an optional `match-template`
  Action** the agent may call for confirmation. *Risk: low — optional.*
- 🔬 **Rovo chat surfaces tool calls**, so "it really consulted all four sources"
  is visible for free, and the reasoning narration precedes the work-note. *Risk:
  low — this is the demo's built-in credibility.*

## Build / technical

- 🤔 **Forge functions time out around ~25s** — Sumo's async poll-until-done search
  can exceed it. Strongest reason to **mock `query-logs`** (also dodges the
  4 req/s limit + network risk). *Risk: medium — mitigated by mocking.*
- 🔍 **Forge iteration (tunnel/deploy/remote-logs) is slower than a local
  backend.** *Risk: medium — budget time; start from the hello-world template.*
- 🔍 **Effort ~2–3 days** (Forge-new), dominated by the learning curve + 4 Actions
  + prompt tuning. *Risk: medium — the curve is the schedule risk.*
- 🤔 **LLM may skip/reorder steps** — mitigate with a numbered playbook prompt; a
  natural chat follow-up can nudge it live. *Risk: medium.*

## Open unknowns / to confirm before committing

- ❓ **Does the operator's Atlassian Cloud org have Rovo enabled (paid) + admin
  rights to install a Forge app?** Hard prerequisite. *Risk: blocking if absent.*
- ❓ **ServiceNow integration user has `rest_service` role + incident write?**
  Needed for `post-worknote`. *Risk: medium.*
- ❓ **GitLab PAT (`read_api`) + project-code→project-ID mapping available?** *Risk:
  low — cache the mapping.*
- ❓ **Is a real Sumo query even needed for the demo, or is a mock accepted?**
  (Recommend mock.) *Risk: low.*

## One-line recommendation

Build a single Rovo agent + 4 Forge Actions (Confluence native), **demo via
chat-invoke (path a)** for the full autonomous-reasoning wow, **mock Sumo**, and
narrate webhook→Automation (path b) as the production trigger. Promising: **YES**.
