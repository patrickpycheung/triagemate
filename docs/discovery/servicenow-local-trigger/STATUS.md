# STATUS — DDS: ServiceNow (cloud) → local app on corporate laptop

**Phase**: 4 COMPLETE — gating spike resolved by the operator 2026-07-29; K1 confirmed.
Consumed downstream by DDS `copilot-cli-runtime` (C4).

**Supersedes**: DDS `servicenow-auto-trigger` (2026-07-24), which deferred automation
because no inbound path existed. This DDS found the inbound assumption was the problem —
inverting to outbound polling revives automation. See that workspace's STATUS for the
superseded framing.
**Rigor**: Hackathon / RAPID (Claude + WebSearch; external triple-perspective skipped).
**Question**: Can a cloud ServiceNow instance *trigger* our locally-running Spring Boot
app on a **corporate laptop** (no Tailscale, Cloudflare blocked, no public inbound)?

## Answer (short)
- **Push webhook → local laptop: NOT feasible.** ServiceNow cloud cannot open a
  connection into the corp network; the usual escape hatch (an outbound tunnel —
  Tailscale / Cloudflare / ngrok) is blocked or un-installable on the corp laptop.
- **Automated triggering is still achievable by INVERTING direction:** the local app
  **polls** ServiceNow's REST API for new incidents over the *same outbound HTTPS
  channel it already uses* to read/update tickets. No inbound, no tunnel, no new
  software. Trade-off: poll latency (secs) instead of instant push.
- **So headless/automated is NOT dead** for the first (Copilot-CLI) DDS on *network*
  grounds. The remaining headless blockers are **C6** (licensing/ToS for unattended use),
  **C1** (does a proxy authenticate with the corp Copilot seat, and is the binary allowed
  by endpoint policy?) and **C2** (end-to-end run) — all in `copilot-cli-runtime`.
  **C6 alone does not clear headless.** Connectivity, however, is closed.

## Gating unknown — RESOLVED 🔬 (2026-07-29)
Operator confirmed: a `curl` with basic auth from the **real corp laptop** to the
ServiceNow instance, pulling incident details, **works**. Outbound reachability is
proven → **K1 outbound polling is confirmed viable**. No proxy workaround was needed.

## Files
- `1-elicit/` — problem, constraints, success criteria
- `2-diverge/explorations/` — A push-tunnel (dead) · B outbound-polling (winner) ·
  C MID Server (sanctioned, heavy) · D manual trigger (fallback)
- `3-synthesize/` — patterns, dead-ends
- `4-decide/` — decision + concepts-extracted (→ feeds the Copilot-CLI DDS)
