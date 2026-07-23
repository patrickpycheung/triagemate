# C2 — Forge Actions (external connectors)

**Level**: 🔧 Plumbing · **Complexity**: 🟨 Moderate · **Convergence**: 🟢 Converged

## One-liner
The custom glue: Forge Actions the Rovo agent calls to reach GitLab, Sumo, and
ServiceNow. Confluence is native Rovo — not an Action.

## Actions (4 total — fits the <5/agent cap)
| Action | Source | Live/Mock | Notes |
|--------|--------|-----------|-------|
| `get-ticket` | ServiceNow | LIVE (read) | id → project, short desc, notes, opened-at |
| `get-source` | GitLab master | LIVE (read) | project → relevant source files/tree |
| `get-logs` | Sumo Logic | **MOCK fixture** | failure-window log lines (demo-safe) |
| `post-worknote` | ServiceNow | LIVE (write) | → see C5 |

## Constraints accepted (DDS)
- **5 MB / action payload**, ~25–55 s Forge timeout → filter payloads, return only
  relevant files/log window, never whole repos.
- Egress + secrets: manifest `permissions.external.fetch` + Forge secure storage for
  API tokens. → **Spike S2′** verifies this.

## Carried DDS items folded here
- **R2** (AST-index timing): dropped for prototype — no pre-built index; `get-source`
  returns files, C3 reasons directly (AI-first).
- **R5** (ServiceNow creds): integration user needs read (`get-ticket`) + write
  (`post-worknote`) scopes. Stored as Forge secrets.

## Open questions
- GitLab: fetch by path (need to know files) vs search API? Lean: fetch a known small
  file set for the seeded demo project.
- Sumo mock: static JSON fixture vs a tiny stub endpoint? (→ C6)

## Depends on
None (leaf). Consumed by C1.
