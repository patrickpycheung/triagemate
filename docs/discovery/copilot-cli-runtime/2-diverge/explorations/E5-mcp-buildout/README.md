# E5 — MCP servers for ServiceNow / Sumo / Confluence / GitLab

**Bias**: Technical-depth. **Verdict**: Required for E1; **optional** for E2 → defer.

## What it is
Four **local STDIO MCP servers**, each exposing a few tools mirroring our gateways:
- ServiceNow: `get_incident`, `find_similar`, `find_ownership`, `add_worknote` (advisory).
- Sumo: `search_logs` (allowlisted scopes, bounded window).
- Confluence: `search_pages`, `page_contributors`.
- GitLab: `search_code`, `recent_committers`.

## Reality checks (L4/L5)
- **Local STDIO only** is fine (no OAuth-for-remote-MCP needed).
- **128-tool cap** is plenty; but the **≥7-tools empty-message bug** in headless `-p`
  (L5) is a live hazard — our four servers collectively exceed 7 tools.
- **Org policy gates MCP** (off by default) → **IT enablement** required.
- Real work: reimplement each gateway's auth/paging as an MCP tool; keep the advisory
  write-guard server-side.

## Cost/benefit
- **For E1**: mandatory — this is how Copilot reaches the systems.
- **For E2**: **not needed** — our Java gateways already do this; MCP would be duplicate
  surface. Only worth it if we later want the same tools reusable by Copilot IDE/other
  MCP clients (a separate, future goal).

## Recommendation
Don't build MCP servers for the hackathon unless E1 is chosen. If we later want an
MCP surface, wrap the *existing* gateway logic once and share it — but that's a
post-goal enhancement, not on the critical path.

## Trust / risk
📚/🔍 · 🟢 technically, 🟠 gated by the ≥7-tool headless bug + org MCP policy.
