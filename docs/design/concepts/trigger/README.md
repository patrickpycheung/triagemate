# C4 — Trigger (chat-invoked)

**Level**: 🏘️ Neighborhood · **Complexity**: 🟦 Simple · **Convergence**: 🟢 Converged

## One-liner
The presenter invokes the agent in Rovo chat: `Triage INC0012345`. Decided in DDS.

## Why chat, not autonomous
Rovo agents can't be triggered by an external API/webhook AND allowed to post an
autonomous write in the same headless flow (DDS Gemini research + Exploration E). The
only clean demo path is presenter-in-chat.

## Production vision (narrated, not built)
New-ticket → ServiceNow Business Rule / Automation bridge → invoke agent with
`{{ticketId}}` → agent reasons → Automation layer writes the note using
`{{agentResponse}}`. This is the "webhook-triggered future" line in the pitch.

## Open questions
- Exact chat invocation phrasing the agent recognizes (playbook parses the id).
- Fallback if the presenter mistypes the id (→ graceful error, C6).

## Depends on
Nothing. Kicks off C1.
