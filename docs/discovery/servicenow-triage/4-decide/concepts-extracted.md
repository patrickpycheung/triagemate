# Concepts Extracted — ServiceNow Technical Triage Assistant

**Handoff to CDS.** WHAT to design, not HOW. Prototype scope: hackathon demo wow + ease of build.
**Direction (post Round-2 redirect): Rovo-native, AI-first.** The AI agent lives in Atlassian Rovo and is the star; its own LLM does the reasoning. The standalone app is now the documented alternative.

## Chosen architecture (one line)
A single **Rovo agent** ("Triage Agent"), invoked in Rovo chat (`Triage INC0012345`), autonomously calls a few **Forge Actions** to fetch the ServiceNow ticket + GitLab `master` code + Sumo logs (Confluence is native), **reasons over logs↔code with its own LLM to localize the failing code**, narrates its reasoning live in chat, and posts a root-cause work-note back to the ticket.

---

## Concept list (Rovo-native)

### RC1 — Rovo Triage Agent  ⭐ (the star / AI-first)
- A Forge `rovo:agent` module (Rovo Studio no-code agent + prompt). A numbered "playbook" prompt drives the LLM to: read ticket → identify project → fetch code + logs → reason to localize failure → compose work-note.
- The agent's own LLM is the reasoning engine (no external Copilot needed → Round-1 blocker gone).
- Trust: 📚 (Rovo agent+actions architecture), risk 🟡 (Forge learning curve).

### RC2 — Forge Actions (external connectors)
- Forge functions using `@forge/api` `api.fetch` + `manifest.yml` `permissions.external.fetch.backend` egress. Keep to **<5 actions** (Atlassian reliability guidance):
  - `read-ticket` (ServiceNow: sys_id/project code/notes), `fetch-code` (GitLab `ref=master`, filtered files), `query-logs` (Sumo — **mocked** in demo), `post-worknote` (ServiceNow PATCH).
  - Optional `match-template` (deterministic Drain3/AST) as a precision **tool the agent may call** — demoted, not the headline.
- **Confluence = native** (Rovo Teamwork Graph) — no Action needed; one of four sources is free.
- **Limits to design around**: 5 MB per action return, ~25–55 s Forge function timeout → filter code/log slices before returning; mock Sumo (async polling won't fit the timeout).
- Trust: 📚, risk 🟡 (egress/auth secrets) / 🟢 Confluence.

### RC3 — AI-first Log↔Code Reasoning
- The agent's LLM matches a runtime log line (Sumo) to its emitting `log(...)` statement in the fetched source (format strings are the anchor), citing **file:line** + the execution path — no deterministic engine required.
- **Made visible**: Rovo chat surfaces every tool call + the reasoning narration precedes the note → "it really consulted all four sources" is shown for free. This visible reasoning IS the AI-first wow.
- Trust: 🔍 (LLM correlation plausible; accuracy scenario-dependent), risk 🟡 (hallucination → mitigate: agent must quote the exact matched log line + link the real file).

### RC4 — Trigger  ✅ DECIDED: chat-invoked demo (operator-confirmed)
- **Demo (LOCKED)**: presenter invokes in Rovo chat (`Triage INC0012345`). Easiest AND fullest wow — in chat the agent also posts the note itself (with confirm). This is the path CDS designs against.
- **Production (narrated, NOT built for the hackathon)**: ServiceNow webhook → **Jira/Confluence Automation rule** → "Invoke Rovo Agent" with payload → `{{agentResponse}}`, Automation layer does the write-back. Mention as the future vision only.
- Trust: 📚 (chat path), risk 🟢 demo / 🟠 autonomous trigger (out of scope).

### RC5 — Work-Note Post-back
- **Demo/chat**: agent's `post-worknote` Action (chat write-actions allowed, with human confirm) → PATCH `work_notes`.
- **Headless/production**: Rovo restricts autonomous CREATE/UPDATE writes → the **Automation rule does the write-back** using `{{agentResponse}}`, not the agent. Design the post-back to work in both places.
- Structured note: summary → evidence (quoted log line) → responsible file:line (GitLab link) → confidence → wiki link → suggested owner/next step.
- Trust: 📚, risk 🟡 (headless-write restriction — see spike S1').

### RC6 — Demo-Safety & Scope
- **Live**: agent reasoning + Action calls + GitLab fetch + native Confluence + post-back. **Pre-seeded**: mock Sumo fixture, seeded-bug demo repo, pre-created ticket.
- Fallbacks: cached transcript/work-note, high-confidence-only scenario, backup video.
- Trust: 🔍, risk 🟡 (Forge/latency on stage).

### RC7 — (ALTERNATIVE, not chosen) Standalone app + external LLM
- The Round-1 standalone Python orchestrator + deterministic correlation. Keep documented; pursue if Rovo's constraints (5-action / 5 MB / headless-write / Forge curve) prove blocking, or if a fully-autonomous on-ticket trigger with autonomous write-back is mandatory. Its LLM-engine question (Copilot headless) returns if this path is revived.

---

## Mandatory day-1 spikes (revised for Rovo-native)
- **S1' (top)**: Rovo write-action behavior — confirm the **chat** agent can execute `post-worknote` (with confirm) for the demo; confirm the Automation→agent invocation + Automation-layer write-back for the production narrative.
- **S2'**: Forge Action egress to GitLab/Sumo/ServiceNow — `manifest.yml` external-fetch permission + storing/using API secrets in Forge.
- **S3'**: seeded bug that emits a distinctive log line + a matching Sumo fixture, so RC3's correlation lands convincingly.

## Carried integration items for CDS (still apply)
- **R3 — Failure-window derivation**: how the agent scopes the Sumo query in time (ticket time ≠ failure time).
- **R4 — Degraded-note behavior**: what the note shows when the agent can't confidently localize (low-confidence path).
- **R5 — ServiceNow creds**: the integration user/token needs both read (`read-ticket`) and write (`post-worknote`).
- (R1 Confluence-timing now moot — native; R2 AST-index-timing now moot unless the optional `match-template` tool is built.)

## Demo north star
Presenter types `Triage INC0012345`; the Rovo agent visibly fetches ticket + GitLab code + Sumo logs + Confluence, narrates the log→code correlation, names the responsible **file:line** quoting the exact log line, cites the design page, and posts the work-note back — all on screen in ~2–3 min.
