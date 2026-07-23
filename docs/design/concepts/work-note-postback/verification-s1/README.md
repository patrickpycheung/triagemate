# Spike S1′ — Rovo chat agent write-action with confirm (RESULT)

**Question**: Can a Rovo **chat** agent execute a write Action (`post-worknote`) with a
confirm step for the demo?

**Result**: ✅ **YES, via the supported path.** Trust: 🔬 Spiked (Forge/Rovo docs, 2026).

## Findings (authoritative Forge/Rovo docs)
- A Rovo `action` module IS a Forge function with an `actionVerb` and typed `inputs`; an
  agent lists it under `actions:` and calls it. A write is just a function that performs
  the mutation (e.g. POST to ServiceNow) — nothing blocks a write action per se.
- **Fine-grained write-permission control is NOT supported.** You constrain an agent by
  *which actions it has*, not by a write-permission flag. → scope the agent to exactly
  our 4 actions.
- Rovo's built-in behavior **asks the user for missing details / confirms in chat**
  before acting. So the confirm-before-write UX is native: the playbook shows the draft
  note, the user says "yes", the agent then calls `post-worknote`.
- Truly *autonomous headless* writes (no human in loop) are the restricted case — which
  is exactly why the DDS chose the chat-confirm demo path and narrates the Automation
  `{{agentResponse}}` path for production.

## Design consequence for C5
- `post-worknote` action: `actionVerb: CREATE`, inputs = `{ ticketId, noteBody }`,
  function POSTs to ServiceNow work-notes REST endpoint using the encrypted token.
- Playbook rule: **always render the draft note in chat and get a yes before calling
  `post-worknote`.** (Safer + better demo narration.)

## Sources
- developer.atlassian.com/platform/forge/manifest-reference/modules/rovo-action/
- developer.atlassian.com/platform/forge/manifest-reference/modules/rovo-agent/
- support.atlassian.com/rovo/docs/agent-actions/
