# STATUS — DDS: ServiceNow (cloud) → local app on corporate laptop

**Phase**: 4 (DECIDE) — concepts extracted, pending Phase 4 user checkpoint.
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
  grounds. Any remaining headless blocker is Copilot-CLI licensing/ToS, not connectivity.

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
